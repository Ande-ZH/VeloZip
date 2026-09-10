package dev.velozip.backend.adapter;

import dev.velozip.backend.network.VeloZipBackendFrameDecoder;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.transport.VeloZipBatchEncoder;
import dev.velozip.common.util.PlatformVersions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;
import org.bukkit.entity.Player;
import java.util.Set;

/** Paper/Purpur 1.18+ backend adapter; see docs/COMPATIBILITY-1.0.0.md. */
public final class PurpurNetworkAdapter implements NetworkAdapter {
    private static final AttributeKey<Boolean> ACTIVE_KEY = AttributeKey.valueOf("velozip.active");
    private static final Set<String> SUPPORTED_FAMILIES = Set.of("1.18", "1.19", "1.20", "1.21", "26.1", "26.2");
    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;
    private volatile String family;

    public PurpurNetworkAdapter(VeloZipConfig cfg, VeloZipMetrics metrics, VeloZipLogger logger) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
    }

    public void checkPlatform(String version) {
        family = PlatformVersions.majorMinor(version);
        if (!isSupported()) {
            logger.warn("VeloZip: backend {} is outside the supported families "
                    + "(1.18–1.21, 26.1–26.2). See exact tested builds in the compatibility matrix.", version);
        }
    }

    @Override
    public boolean isSupported() {
        return family != null && SUPPORTED_FAMILIES.contains(family);
    }

    @Override
    public void activate(Player player) {
        try {
            Channel channel = PlayerChannels.find(player);
            if (channel == null || !channel.isActive()) return;
            String name = player.getName();
            channel.eventLoop().execute(() -> activateChannel(channel, name));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logger.error("VeloZip: cannot resolve backend connection; leaving it vanilla", e);
        }
    }

    /** All inspection and mutation is confined to this channel's event loop. */
    void activateChannel(Channel channel, String name) {
        if (!channel.eventLoop().inEventLoop()) throw new IllegalStateException("Not on channel event loop");
        if (!cfg.enabled || !channel.isActive() || Boolean.TRUE.equals(channel.attr(ACTIVE_KEY).get())) return;
        ChannelPipeline pipeline = channel.pipeline();
        if (!(pipeline.get("splitter") instanceof ByteToMessageDecoder)
                || pipeline.get("encoder") == null || pipeline.get("prepender") == null
                || pipeline.get("velozip-encoder") != null) {
            logger.warn("VeloZip: missing required backend pipeline anchors, skipping {}", name);
            return;
        }
        try {
            // Old Paper shares a native compressor, closed by EITHER handler's
            // removal. Keep both alive until inbound switches; outbound VeloZip
            // frames bypass the dormant vanilla chain at the prepender context.
            pipeline.addBefore("encoder", "velozip-encoder",
                    new VeloZipBatchEncoder(cfg, metrics, logger, "prepender"));
            pipeline.replace("splitter", "splitter", new VeloZipBackendFrameDecoder(cfg, metrics, logger, () -> {
                for (String handler : new String[]{"decompress", "compress", "prepender"}) {
                    if (pipeline.get(handler) != null) pipeline.remove(handler);
                }
            }));
            channel.attr(ACTIVE_KEY).set(Boolean.TRUE);
            logger.info("VeloZip transport enabled for {}", name);
        } catch (RuntimeException | LinkageError e) {
            logger.error("VeloZip: failed to activate transport for {}", e, name);
            channel.close();
        }
    }
}
