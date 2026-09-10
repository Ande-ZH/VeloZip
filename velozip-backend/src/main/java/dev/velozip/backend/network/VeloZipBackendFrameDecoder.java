package dev.velozip.backend.network;

import dev.velozip.common.VeloZip;
import dev.velozip.common.compression.ZstdDecompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.frame.VeloZipInbound;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Backend decoder without NMS linkage. Vanilla uses the 21-bit framing verified
 * in Paper 1.18 and later (docs/COMPATIBILITY-1.0.0.md). Existing decompression
 * remains installed until the first VeloZip frame.
 */
public final class VeloZipBackendFrameDecoder extends ByteToMessageDecoder {
    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;
    private final Runnable onTransportActive;
    private final ZstdDecompressor decompressor = new ZstdDecompressor();
    private boolean velozipMode;

    public VeloZipBackendFrameDecoder(VeloZipConfig cfg, VeloZipMetrics metrics,
                                      VeloZipLogger logger, Runnable onTransportActive) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
        this.onTransportActive = onTransportActive;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!ctx.channel().isActive()) {
            in.skipBytes(in.readableBytes());
            return;
        }
        if (!velozipMode) {
            if (!in.isReadable()) return;
            if (in.getByte(in.readerIndex()) == VeloZip.MAGIC_0) {
                switch (VeloZipInbound.probeMagic(in)) {
                    case MATCH -> {
                        velozipMode = true;
                        onTransportActive.run();
                    }
                    case NEED_MORE -> { return; }
                    case MISMATCH -> throw new VeloZipFrameException("bad VeloZip magic at frame boundary");
                }
            } else {
                int start = in.readerIndex();
                int length = VarInts.tryReadVarInt(in, 3, 0x1fffff);
                if (length < 0 || in.readableBytes() < length) {
                    in.readerIndex(start);
                    return;
                }
                if (length > 0) {
                    // Deliver now: a VeloZip frame in the same TCP read may remove
                    // decompression before ByteToMessageDecoder flushes its out list.
                    ctx.fireChannelRead(in.readRetainedSlice(length));
                }
                return;
            }
        }
        VeloZipInbound.parseFrames(ctx, in, out, cfg, decompressor, metrics);
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        decompressor.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("VeloZip: closing connection after frame error: {}", cause.toString());
        ctx.close();
    }
}
