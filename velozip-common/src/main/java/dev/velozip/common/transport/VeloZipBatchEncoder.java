package dev.velozip.common.transport;

import dev.velozip.common.VeloZip;
import dev.velozip.common.compression.ZstdCompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbound VeloZip transport handler: replaces the vanilla frame encoder and
 * compression encoder on the backend connection only.
 *
 * <p>Inbound to this handler are unprefixed Minecraft packet bodies (exactly
 * what the packet encoder stage emits downstream). Each body is length-prefixed
 * (taking over the role of the vanilla frame encoder) and accumulated into a
 * batch. A batch is emitted when it reaches {@code batch.max-size} bytes or
 * after {@code batch.max-delay-micros} — whichever comes first. Upstream
 * flush() calls mark the batch pending but do not break batching, because both
 * platforms flush per-packet on this link (see docs/ANALYSIS.md §8); the delay
 * window is the latency budget.
 *
 * <p>All state is confined to the channel's event loop thread.
 */
public final class VeloZipBatchEncoder extends ChannelDuplexHandler {

    private static final int INITIAL_BATCH_CAPACITY = 16384;

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;
    private final ZstdCompressor compressor = new ZstdCompressor();

    private ByteBuf batch;                 // null when no batch in progress
    private ScheduledFuture<?> timer;
    private List<ChannelPromise> promises; // promises of packets folded into the current batch
    private boolean closed;

    public VeloZipBatchEncoder(VeloZipConfig cfg, VeloZipMetrics metrics, VeloZipLogger logger) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        metrics.totalConnections.increment();
        metrics.activeConnections.increment();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cleanup(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup(ctx);
        super.channelInactive(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf body)) {
            // Same behavior as the vanilla frame encoder (MessageToMessageEncoder<ByteBuf>):
            // non-ByteBuf messages pass through untouched.
            if (cfg.debug && msg != null) {
                logger.debug("VeloZip encoder passthrough for non-ByteBuf message: {}", msg.getClass());
            }
            ctx.write(msg, promise);
            return;
        }
        try {
            int len = body.readableBytes();
            if (len == 0) {
                // Vanilla receivers ignore zero-length frames; skip them entirely.
                body.release();
                promise.trySuccess();
                return;
            }
            if (closed) {
                body.release();
                promise.tryFailure(VeloZipFrameException.closed());
                return;
            }
            if (len > cfg.maxUncompressedSize) {
                logger.error("VeloZip: single packet of {} B exceeds limits.max-uncompressed-size ({} B); "
                        + "closing backend connection. Raise the limit if this is expected traffic.", null,
                        len, cfg.maxUncompressedSize);
                body.release();
                promise.tryFailure(new VeloZipFrameException("packet exceeds max-uncompressed-size"));
                ctx.close();
                return;
            }

            if (batch != null
                    && batch.readableBytes() + VarInts.varIntLen(len) + len > cfg.batchMaxSize) {
                emitNow(ctx, "batch size");
            }
            if (batch == null) {
                batch = ctx.alloc().directBuffer(
                        Math.min(INITIAL_BATCH_CAPACITY, cfg.batchMaxSize + 64), cfg.maxUncompressedSize);
                promises = new ArrayList<>(8);
                timer = ctx.executor().schedule(() -> emitNow(ctx, "delay window"),
                        cfg.batchMaxDelayMicros, TimeUnit.MICROSECONDS);
            }
            batch.ensureWritable(VarInts.varIntLen(len) + len, true);
            VarInts.writeVarInt(batch, len);
            batch.writeBytes(body);
            body.release();
            if (!promise.isVoid()) {
                promises.add(promise);
            }
            if (batch.readableBytes() >= cfg.batchMaxSize) {
                emitNow(ctx, "batch size");
            }
        } catch (Throwable t) {
            if (body.refCnt() > 0) {
                body.release();
            }
            promise.tryFailure(t);
            throw t;
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (batch == null) {
            // Nothing held back: forward the flush immediately.
            ctx.flush();
        }
        // Else: the delay-window timer (<= max-delay-micros away) emits and
        // flushes. Swallowing this flush is what makes batching effective.
    }

    /**
     * Builds and writes the current batch as one VeloZip frame. Always flushes
     * (we own the flush semantics while a batch is in flight).
     */
    private void emitNow(ChannelHandlerContext ctx, @SuppressWarnings("unused") String trigger) {
        if (batch == null || closed) {
            return;
        }
        if (cfg.debug) {
            logger.debug("VeloZip batch emitted (trigger={})", trigger);
        }
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        ByteBuf content = batch;
        List<ChannelPromise> folded = promises;
        batch = null;
        promises = null;

        int origLen = content.readableBytes();
        ByteBuf frame;
        boolean zstd = false;
        try {
            if (origLen < cfg.rawThreshold) {
                frame = rawFrame(ctx, content);
            } else {
                int bound = compressor.bound(origLen);
                ByteBuf dst = ctx.alloc().directBuffer(bound, bound);
                int compressed;
                long t0 = System.nanoTime();
                try {
                    compressed = compressor.compress(dst, content);
                } catch (Throwable t) {
                    dst.release();
                    throw new VeloZipFrameException("zstd compression failed", t);
                }
                long dt = System.nanoTime() - t0;
                if (compressed <= 0) {
                    dst.release();
                    throw new VeloZipFrameException("zstd compression produced no output (dst too small?)");
                }
                if (compressed >= origLen || compressed > cfg.maxFrameSize) {
                    // Compression does not pay off (or exceeds the wire limit): send RAW.
                    dst.release();
                    frame = rawFrame(ctx, content);
                } else {
                    metrics.compressionOps.increment();
                    metrics.recordCompress(dt);
                    zstd = true;
                    content.release(); // content only served as compression source
                    frame = zstdFrame(ctx, dst, compressed, origLen);
                }
            }
        } catch (Throwable t) {
            content.release();
            failPromises(folded, t);
            logger.error("VeloZip: failed to build batch frame, closing backend connection", t);
            ctx.close();
            return;
        }

        int wireLen = frame.readableBytes();
        metrics.originalBytes.add(origLen);
        metrics.wireBytes.add(wireLen);
        metrics.batches.increment();

        ChannelPromise writePromise;
        if (folded == null || folded.isEmpty()) {
            writePromise = ctx.voidPromise();
        } else {
            writePromise = ctx.newPromise();
            writePromise.addListener(future -> {
                for (ChannelPromise p : folded) {
                    if (future.isSuccess()) {
                        p.trySuccess();
                    } else {
                        p.tryFailure(future.cause());
                    }
                }
            });
        }
        ctx.write(frame, writePromise);
        ctx.flush();
    }

    /** @param content ownership transfers into the returned frame */
    private ByteBuf rawFrame(ChannelHandlerContext ctx, ByteBuf content) {
        int origLen = content.readableBytes();
        if (origLen > cfg.maxFrameSize) {
            throw new VeloZipFrameException(
                    "RAW frame of " + origLen + " B exceeds limits.max-frame-size");
        }
        ByteBuf prefix = ctx.alloc().buffer(8);
        prefix.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_RAW);
        VarInts.writeVarInt(prefix, origLen);
        CompositeByteBuf frame = ctx.alloc().compositeBuffer(2);
        frame.addComponent(true, prefix);
        frame.addComponent(true, content);
        return frame;
    }

    /** @param dst ownership transfers into the returned frame (first {@code len} bytes used) */
    private ByteBuf zstdFrame(ChannelHandlerContext ctx, ByteBuf dst, int len, int origLen) {
        dst.setIndex(0, len);
        ByteBuf prefix = ctx.alloc().buffer(12);
        prefix.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_ZSTD);
        VarInts.writeVarInt(prefix, len);
        VarInts.writeVarInt(prefix, origLen);
        CompositeByteBuf frame = ctx.alloc().compositeBuffer(2);
        frame.addComponent(true, prefix);
        frame.addComponent(true, dst);
        return frame;
    }

    private void cleanup(ChannelHandlerContext ctx) {
        if (closed) {
            return;
        }
        closed = true;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        if (batch != null) {
            batch.release();
            batch = null;
        }
        if (promises != null) {
            failPromises(promises, VeloZipFrameException.closed());
            promises = null;
        }
        compressor.close();
        metrics.activeConnections.decrement();
    }

    private static void failPromises(List<ChannelPromise> list, Throwable cause) {
        if (list == null) {
            return;
        }
        for (ChannelPromise p : list) {
            p.tryFailure(cause);
        }
    }
}
