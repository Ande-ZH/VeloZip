package dev.velozip.velocity.network;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import dev.velozip.common.VeloZip;
import dev.velozip.common.compression.ZstdDecompressor;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.frame.VeloZipInbound;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * Dual-mode frame decoder occupying the {@code frame-decoder} slot of a backend
 * channel. Vanilla mode delegates entirely to the real Velocity superclass; a
 * 0x00 0x5A magic at a frame boundary switches permanently to VeloZip mode and
 * triggers the outbound swap via {@code onTransportActive}.
 *
 * <p>MUST extend MinecraftVarintFrameDecoder: PLAY&lt;-&gt;CONFIG transitions call
 * pipeline().get(MinecraftVarintFrameDecoder.class).setState(...) with no null
 * check (verified in Velocity 3.4.0, see docs/ANALYSIS.md §2.4).
 */
public final class VeloZipVelocityFrameDecoder extends MinecraftVarintFrameDecoder {

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;
    private final Runnable onTransportActive;
    private final ZstdDecompressor decompressor = new ZstdDecompressor();
    private boolean velozipMode;

    public VeloZipVelocityFrameDecoder(VeloZipConfig cfg, VeloZipMetrics metrics,
                                       VeloZipLogger logger, Runnable onTransportActive) {
        super(ProtocolUtils.Direction.CLIENTBOUND); // matches BackendChannelInitializer
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
        this.onTransportActive = onTransportActive;
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
                        onTransportActive.run();
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
        // Mirror vanilla MinecraftConnection behavior (close), with our own log line.
        logger.warn("VeloZip: closing backend connection after frame error: {}", cause.toString());
        ctx.close();
    }
}
