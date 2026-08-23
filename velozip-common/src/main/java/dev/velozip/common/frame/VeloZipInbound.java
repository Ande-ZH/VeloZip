package dev.velozip.common.frame;

import dev.velozip.common.VeloZip;
import dev.velozip.common.compression.ZstdDecompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * VeloZip-mode inbound parsing shared by both platform dual-mode decoders.
 *
 * <p>All size fields are validated against the configured limits BEFORE any
 * allocation (prompt §12: no unbounded {@code allocate(length)}), and the zstd
 * result length must equal the declared original length — this is the
 * decompression-bomb guard on top of the size caps.
 */
public final class VeloZipInbound {

    /** Result of probing the first bytes of a frame boundary in vanilla mode. */
    public enum Probe { MATCH, NEED_MORE, MISMATCH }

    private VeloZipInbound() {
    }

    /**
     * Checks whether {@code in} starts with the VeloZip magic at the current
     * reader index (a vanilla frame boundary). Never consumes bytes on
     * NEED_MORE/MISMATCH.
     */
    public static Probe probeMagic(ByteBuf in) {
        int readable = in.readableBytes();
        if (readable < 1) {
            return Probe.NEED_MORE;
        }
        if (in.getByte(in.readerIndex()) != VeloZip.MAGIC_0) {
            return Probe.MISMATCH;
        }
        if (readable < 2) {
            return Probe.NEED_MORE;
        }
        return in.getByte(in.readerIndex() + 1) == VeloZip.MAGIC_1 ? Probe.MATCH : Probe.MISMATCH;
    }

    /**
     * Parses as many complete VeloZip frames as {@code in} holds, appending the
     * restored Minecraft frames (each with its varint length prefix, exactly
     * what a vanilla frame decoder would emit) to {@code out}.
     *
     * @return true if at least one frame was consumed
     * @throws VeloZipFrameException on any protocol violation
     */
    public static boolean parseFrames(ChannelHandlerContext ctx, ByteBuf in, List<Object> out,
                                      VeloZipConfig cfg, ZstdDecompressor decompressor,
                                      VeloZipMetrics metrics) {
        boolean progressed = false;
        while (parseOne(ctx, in, out, cfg, decompressor, metrics)) {
            progressed = true;
        }
        return progressed;
    }

    private static boolean parseOne(ChannelHandlerContext ctx, ByteBuf in, List<Object> out,
                                    VeloZipConfig cfg, ZstdDecompressor decompressor,
                                    VeloZipMetrics metrics) {
        int start = in.readerIndex();

        if (in.readableBytes() < 4) { // magic(2) + ver(1) + flags(1)
            return false;
        }
        if (in.readByte() != VeloZip.MAGIC_0 || in.readByte() != VeloZip.MAGIC_1) {
            throw new VeloZipFrameException("bad magic in VeloZip mode");
        }
        int version = in.readUnsignedByte();
        if (version != VeloZip.TRANSPORT_PROTOCOL) {
            throw new VeloZipFrameException("transport protocol mismatch in stream: " + version);
        }
        int flags = in.readUnsignedByte();
        if (flags != VeloZip.FLAG_ZSTD && flags != VeloZip.FLAG_RAW) {
            throw new VeloZipFrameException("invalid frame flags 0x" + Integer.toHexString(flags));
        }

        // Validate lengths BEFORE allocating anything for the payload.
        int payloadLen = VarInts.tryReadVarInt(in, 5, cfg.maxFrameSize);
        if (payloadLen < 0) {
            in.readerIndex(start);
            return false;
        }
        int origLen = payloadLen;
        if (flags == VeloZip.FLAG_ZSTD) {
            origLen = VarInts.tryReadVarInt(in, 5, cfg.maxUncompressedSize);
            if (origLen < 0) {
                in.readerIndex(start);
                return false;
            }
        }
        if (in.readableBytes() < payloadLen) {
            in.readerIndex(start);
            return false;
        }

        ByteBuf payload = in.readRetainedSlice(payloadLen);
        try {
            ByteBuf content;
            if (flags == VeloZip.FLAG_ZSTD) {
                // The cumulation buffer may be heap-backed depending on the channel's
                // allocator; zstd-jni requires direct buffers, so copy when needed.
                ByteBuf zstdSrc = payload;
                boolean copied = false;
                if (!payload.isDirect()) {
                    zstdSrc = ctx.alloc().directBuffer(payloadLen, payloadLen);
                    zstdSrc.writeBytes(payload);
                    copied = true;
                }
                try {
                    long t0 = System.nanoTime();
                    content = ctx.alloc().directBuffer(origLen, origLen);
                    int produced;
                    try {
                        produced = decompressor.decompress(content, origLen, zstdSrc, payloadLen);
                    } catch (Throwable t) {
                        content.release();
                        throw new VeloZipFrameException("zstd decompression failed", t);
                    }
                    long dt = System.nanoTime() - t0;
                    metrics.decompressionOps.increment();
                    metrics.recordDecompress(dt);
                    if (produced != origLen) {
                        content.release();
                        throw new VeloZipFrameException(
                                "decompressed size mismatch: declared=" + origLen + " produced=" + produced
                                        + " (corrupted frame or decompression bomb)");
                    }
                    metrics.zstdFrames.increment();
                } finally {
                    if (copied) {
                        zstdSrc.release();
                    }
                }
            } else {
                content = payload.retain(); // RAW payload is the content itself
                metrics.rawFrames.increment();
            }
            try {
                splitMinecraftFrames(content, out, cfg);
            } finally {
                content.release();
            }
        } finally {
            payload.release();
        }

        metrics.wireBytes.add(in.readerIndex() - start);
        metrics.originalBytes.add(origLen);
        return true;
    }

    /**
     * Splits a batch payload (sequence of complete, varint-length-prefixed
     * Minecraft frames) into individual frame buffers. The caller owns
     * {@code content}; emitted slices are retained.
     */
    private static void splitMinecraftFrames(ByteBuf content, List<Object> out, VeloZipConfig cfg) {
        while (content.isReadable()) {
            int frameLen = VarInts.readVarInt(content, 5, cfg.maxUncompressedSize);
            if (frameLen == 0) {
                throw new VeloZipFrameException("zero-length inner Minecraft frame (corruption)");
            }
            if (content.readableBytes() < frameLen) {
                throw new VeloZipFrameException("truncated inner Minecraft frame (corruption)");
            }
            out.add(content.readRetainedSlice(frameLen));
        }
    }

    /** Utility used by tests to build a batch payload from packet bodies. */
    public static ByteBuf batchPayload(ChannelHandlerContext ctx, ByteBuf... packetBodies) {
        CompositeByteBuf composite = ctx.alloc().compositeBuffer(packetBodies.length * 2);
        for (ByteBuf body : packetBodies) {
            ByteBuf prefix = ctx.alloc().buffer(VarInts.varIntLen(body.readableBytes()));
            VarInts.writeVarInt(prefix, body.readableBytes());
            composite.addComponent(true, prefix);
            composite.addComponent(true, body.retain());
        }
        return composite;
    }
}
