package dev.velozip.common;

import dev.velozip.common.compression.ZstdDecompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.frame.VeloZipInbound;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Test-only dual-mode decoder mirroring the platform decoders: vanilla frames
 * (plain varint split) until the 0x00 0x5A magic appears at a frame boundary,
 * then permanent VeloZip mode. The production decoders delegate the vanilla
 * half to the real platform superclasses.
 */
public class TestDualDecoder extends ByteToMessageDecoder {

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final ZstdDecompressor decompressor = new ZstdDecompressor();
    private boolean velozipMode;

    public TestDualDecoder(VeloZipConfig cfg, VeloZipMetrics metrics) {
        this.cfg = cfg;
        this.metrics = metrics;
    }

    public boolean isVeloZipMode() {
        return velozipMode;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!velozipMode) {
            if (!in.isReadable()) {
                return;
            }
            if (in.getByte(in.readerIndex()) == VeloZip.MAGIC_0) {
                switch (VeloZipInbound.probeMagic(in)) {
                    case MATCH -> velozipMode = true;
                    case NEED_MORE -> {
                        return;
                    }
                    case MISMATCH -> throw new VeloZipFrameException("bad VeloZip magic at frame boundary");
                }
            } else {
                // vanilla mode: standard varint frame split
                int start = in.readerIndex();
                int len = VarInts.tryReadVarInt(in, 3, 1 << 21);
                if (len <= 0 || in.readableBytes() < len) {
                    in.readerIndex(start);
                    return;
                }
                out.add(in.readRetainedSlice(len));
                return;
            }
        }
        VeloZipInbound.parseFrames(ctx, in, out, cfg, decompressor, metrics);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Mirrors the production behavior: any framing error closes the connection.
        ctx.close();
    }
}
