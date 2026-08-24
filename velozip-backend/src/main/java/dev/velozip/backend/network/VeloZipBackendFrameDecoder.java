package dev.velozip.backend.network;

import dev.velozip.common.VeloZip;
import dev.velozip.common.compression.ZstdDecompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.frame.VeloZipInbound;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Varint21FrameDecoder;

import java.util.List;

/**
 * Dual-mode frame decoder occupying the {@code splitter} slot of a player
 * connection that came through Velocity. Extends the real vanilla decoder so
 * the vanilla path (super.decode) is byte-for-byte the server's own behavior;
 * a 0x00 0x5A magic at a frame boundary switches permanently to VeloZip mode.
 */
public final class VeloZipBackendFrameDecoder extends Varint21FrameDecoder {

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;
    private final ZstdDecompressor decompressor = new ZstdDecompressor();
    private boolean velozipMode;

    public VeloZipBackendFrameDecoder(VeloZipConfig cfg, VeloZipMetrics metrics,
                                      VeloZipLogger logger) {
        super(null); // no BandwidthDebugMonitor, matching a normal TCP connection
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!velozipMode) {
            if (!in.isReadable()) {
                return;
            }
            if (in.getByte(in.readerIndex()) == VeloZip.MAGIC_0) {
                switch (VeloZipInbound.probeMagic(in)) {
                    case MATCH -> {
                        velozipMode = true;
                        VeloZipInbound.parseFrames(ctx, in, out, cfg, decompressor, metrics);
                    }
                    case NEED_MORE -> {
                        return;
                    }
                    case MISMATCH -> throw new VeloZipFrameException(
                            "0x00-led frame is not VeloZip magic (vanilla streams cannot produce this)");
                }
            } else {
                super.decode(ctx, in, out);
            }
            return;
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
