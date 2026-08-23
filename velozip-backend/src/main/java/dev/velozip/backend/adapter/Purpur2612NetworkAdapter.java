package dev.velozip.backend.adapter;

import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.transport.VeloZipBatchEncoder;
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
 * Purpur 26.1.2 connection injection. Handler names verified against the
 * 26.1.2 server (see docs/ANALYSIS.md §3.1):
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
public final class Purpur2612NetworkAdapter implements NetworkAdapter {

    /** Server pipeline handler names (HandlerNames constants, 26.1.2). */
    private static final String SPLITTER = "splitter";
    private static final String DECOMPRESS = "decompress";
    private static final String COMPRESS = "compress";
    private static final String PREPENDER = "prepender";
    private static final String ENCODER = "encoder";
    private static final String VELOZIP_ENCODER = "velozip-encoder";

    private static final io.netty.util.AttributeKey<Boolean> ACTIVE_KEY =
            io.netty.util.AttributeKey.valueOf("velozip.active");

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;

    public Purpur2612NetworkAdapter(VeloZipConfig cfg, VeloZipMetrics metrics, VeloZipLogger logger) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
    }

    @Override
    public boolean isSupported() {
        return true; // phase 1 targets exactly Purpur 26.1.2 (Paper 26.1.2 base)
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
                    if (Boolean.TRUE.equals(channel.attr(ACTIVE_KEY).get())) {
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
