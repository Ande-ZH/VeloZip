package dev.velozip.backend.adapter;

import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.transport.VeloZipBatchEncoder;
import dev.velozip.common.util.PlatformVersions;
import dev.velozip.backend.network.VeloZipBackendFrameDecoder;
import net.minecraft.network.Connection;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

/**
 * Purpur backend connection injection. Handler names and the NMS field chain
 * are identical across Purpur 26.1.2 (build 2592) and Purpur 26.2 (build
 * 2627) — verified against Paper ver/26.1.2 and main (26.2) patch sets (see
 * docs/ANALYSIS.md §3.1 and the v0.2.0 appendix). Pipeline layout:
 *
 * <pre>
 * [FlushConsolidationHandler] timeout (legacy_query) splitter [decompress]
 * [FlowControlHandler] decoder [prepender] [compress] encoder packet_handler
 * </pre>
 *
 * Activation (single event-loop task): splitter -> dual-mode decoder; remove
 * decompress/compress/prepender; addBefore("encoder") the VeloZip batch
 * encoder. From that point the connection's outbound bytes are VeloZip frames
 * — the first one is the de-facto ACK the Velocity side keys off.
 */
public final class PurpurNetworkAdapter implements NetworkAdapter {

    /** Server pipeline handler names (HandlerNames constants, unchanged 26.1.2 → 26.2). */
    private static final String SPLITTER = "splitter";
    private static final String DECOMPRESS = "decompress";
    private static final String COMPRESS = "compress";
    private static final String PREPENDER = "prepender";
    private static final String ENCODER = "encoder";
    private static final String VELOZIP_ENCODER = "velozip-encoder";

    private static final io.netty.util.AttributeKey<Boolean> ACTIVE_KEY =
            io.netty.util.AttributeKey.valueOf("velozip.active");

    /**
     * major.minor Minecraft versions whose network layer this adapter was
     * verified against. An unknown version logs a warning and activation
     * still runs — the pipeline type checks fail safe (skip and stay
     * vanilla) if the internals ever diverge.
     */
    private static final java.util.Set<String> VERIFIED_VERSIONS =
            java.util.Set.of("1.21.11", "26.1.2", "26.2");

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;

    /** Family of the bukkit version last passed to checkPlatform; null before startup. */
    private volatile String verifiedFamily;

    public PurpurNetworkAdapter(VeloZipConfig cfg, VeloZipMetrics metrics, VeloZipLogger logger) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
    }

    /**
     * @param bukkitVersion the value of {@code getServer().getBukkitVersion()},
     *                      logged at plugin startup
     */
    public void checkPlatform(String bukkitVersion) {
        String family = bukkitVersion == null ? null : bukkitVersion.split("[-+]|\\.build\\.")[0];
        verifiedFamily = family;
        if (family == null || !VERIFIED_VERSIONS.contains(family)) {
                    logger.warn("VeloZip: {} has NOT been verified with this plugin "
                                    + "(ABI + live smoke: 1.21.11, 26.1.2, 26.2; see exact build matrix). Activation will still be "
                                    + "attempted; if the pipeline layout differs, VeloZip skips "
                                    + "the connection and leaves it vanilla.",
                            bukkitVersion == null ? "unknown" : bukkitVersion);
        }
    }

    @Override
    public boolean isSupported() {
        return verifiedFamily != null && VERIFIED_VERSIONS.contains(verifiedFamily);
    }

    @Override
    public void activate(Player player) {
        try {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            ServerGamePacketListenerImpl listener = handle.connection;
            if (listener == null) {
                logger.warn("VeloZip: no packet listener for {}", player.getName());
                return;
            }
            Connection connection = listener.connection;
            if (connection == null) {
                logger.warn("VeloZip: no Connection for {}", player.getName());
                return;
            }
            Channel channel = connection.channel;
            if (channel == null || !channel.isActive()) {
                logger.warn("VeloZip: channel not active for {}", player.getName());
                return;
            }
            if (Boolean.TRUE.equals(channel.attr(ACTIVE_KEY).get())) {
                return; // already active on this connection
            }

            ChannelPipeline pipeline = channel.pipeline();
            if (!(pipeline.get(SPLITTER) instanceof Varint21FrameDecoder)) {
                logger.warn("VeloZip: unexpected splitter type {}, skipping",
                        pipeline.get(SPLITTER) == null ? "null" : pipeline.get(SPLITTER).getClass().getName());
                return;
            }

            channel.eventLoop().execute(() -> {
                try {
                    if (!channel.isActive() || Boolean.TRUE.equals(channel.attr(ACTIVE_KEY).get())) {
                        return;
                    }
                    if (!(pipeline.get(SPLITTER) instanceof Varint21FrameDecoder)
                            || pipeline.get(ENCODER) == null
                            || (pipeline.get(PREPENDER) == null && pipeline.get(COMPRESS) == null)) {
                        logger.warn("VeloZip: missing required pipeline anchors, skipping");
                        return;
                    }
                    pipeline.replace(SPLITTER, SPLITTER, new VeloZipBackendFrameDecoder(cfg, metrics, logger));
                    if (pipeline.get(DECOMPRESS) != null) {
                        pipeline.remove(DECOMPRESS);
                    }
                    if (pipeline.get(COMPRESS) != null) {
                        pipeline.remove(COMPRESS);
                    }
                    if (pipeline.get(PREPENDER) != null) {
                        pipeline.remove(PREPENDER);
                    }
                    pipeline.addBefore(ENCODER, VELOZIP_ENCODER,
                            new VeloZipBatchEncoder(cfg, metrics, logger));
                    channel.attr(ACTIVE_KEY).set(Boolean.TRUE);
                    logger.info("VeloZip transport enabled for {}", player.getName());
                } catch (Throwable t) {
                    logger.error("VeloZip: failed to activate transport for {}", t, player.getName());
                    channel.close();
                }
            });
        } catch (Throwable t) {
            logger.error("VeloZip: activation failed, leaving connection vanilla", t);
        }
    }
}