package dev.velozip.velocity.adapter;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import dev.velozip.common.VeloZip;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.protocol.Negotiation;
import dev.velozip.common.transport.VeloZipBatchEncoder;
import dev.velozip.common.util.PlatformVersions;
import dev.velozip.velocity.network.VeloZipVelocityFrameDecoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Velocity backend-channel injection. Handler names are identical across
 * Velocity 3.4.0 (commit 6b1ea78) and 4.1.1 (commit db0a17e) — verified
 * against both sources (see docs/ANALYSIS.md §2.2 and the v0.2.0 appendix):
 *
 * <pre>
 * frame-decoder, read-timeout, frame-encoder, minecraft-decoder, flow-handler,
 * minecraft-encoder, handler
 * </pre>
 *
 * Negotiation timeline (all pipeline mutations run on the channel event loop):
 * <ol>
 *   <li>replace frame-decoder with the dual-mode decoder, then send REQ</li>
 *   <li>backend's first VeloZip frame (magic) triggers onTransportActive -&gt;
 *       remove compression-decoder/compression-encoder (or frame-encoder) and
 *       addBefore minecraft-encoder the VeloZip batch encoder</li>
 *   <li>REFUSE plugin message or 5 s timeout restores the original decoder</li>
 * </ol>
 */
public final class VelocityNetworkAdapter implements NetworkAdapter {

    /** Velocity pipeline handler names (Connections constants, unchanged 3.4.0 → 4.1.1). */
    private static final String FRAME_DECODER = "frame-decoder";
    private static final String FRAME_ENCODER = "frame-encoder";
    private static final String COMPRESSION_DECODER = "compression-decoder";
    private static final String COMPRESSION_ENCODER = "compression-encoder";
    private static final String MINECRAFT_ENCODER = "minecraft-encoder";
    private static final String VELOZIP_ENCODER = "velozip-encoder";

    /**
     * major.minor families whose network layer this adapter was verified
     * against. An unknown family logs a warning and negotiation still runs —
     * the pipeline type checks below fail safe (skip and stay vanilla) if the
     * internals ever diverge.
     */
    private static final Set<String> VERIFIED_FAMILIES =
            Set.of("3.4", "3.5", "4.0", "4.1");

    private static final AttributeKey<NegotiationState> STATE_KEY =
            AttributeKey.valueOf("velozip.negotiation");

    private final VeloZipConfig cfg;
    private final VeloZipMetrics metrics;
    private final VeloZipLogger logger;
    private final String pluginVersion;
    /** Family of the proxy version last passed to checkPlatform; null before startup. */
    private volatile String verifiedFamily;
    /** Per-server last-known negotiation failure, for require-velozip pre-checks. */
    private final Map<String, String> failedServers = new ConcurrentHashMap<>();

    public VelocityNetworkAdapter(VeloZipConfig cfg, VeloZipMetrics metrics,
                                  VeloZipLogger logger, String pluginVersion) {
        this.cfg = cfg;
        this.metrics = metrics;
        this.logger = logger;
        this.pluginVersion = pluginVersion;
    }

    /**
     * @param proxyVersion the value of {@code proxy.getVersion().getVersion()},
     *                     logged at plugin startup
     */
    public void checkPlatform(String proxyVersion) {
        String family = PlatformVersions.majorMinor(proxyVersion);
        verifiedFamily = family;
        if (family == null || !VERIFIED_FAMILIES.contains(family)) {
            logger.warn("VeloZip: Velocity {} has NOT been verified with this plugin "
                            + "(verified: 3.4.x, 3.5.x, 4.0.x, 4.1.x). Negotiation will "
                            + "still be attempted; if the pipeline layout differs, "
                            + "VeloZip skips the connection and leaves it vanilla.",
                    proxyVersion == null ? "unknown" : proxyVersion);
        }
    }

    @Override
    public boolean isSupported() {
        return verifiedFamily != null && VERIFIED_FAMILIES.contains(verifiedFamily);
    }

    /** @return a human-readable failure reason if this server failed negotiation before. */
    public String knownFailure(String serverName) {
        return failedServers.get(serverName);
    }

    @Override
    public void negotiate(Player player, RegisteredServer server) {
        try {
            if (!cfg.enabled) {
                return;
            }
            ConnectedPlayer connected = (ConnectedPlayer) player;
            VelocityServerConnection candidate = connected.getConnectionInFlight();
            if (candidate == null) {
                candidate = connected.getConnectedServer();
            }
            if (candidate == null) {
                return;
            }
            final VelocityServerConnection conn = candidate;
            final RegisteredServer target = conn.getServer();
            final String serverName;
            if (target != null) {
                serverName = target.getServerInfo().getName();
                if (server != null
                        && !serverName.equals(server.getServerInfo().getName())) {
                    return; // in-flight connection does not match this event
                }
            } else if (server != null) {
                serverName = server.getServerInfo().getName();
            } else {
                return;
            }
            if (!cfg.enabledForServer(serverName)) {
                return;
            }
            MinecraftConnection mc = conn.getConnection();
            if (mc == null) {
                return;
            }
            Channel channel = mc.getChannel();
            if (channel.attr(STATE_KEY).get() != null) {
                return; // already negotiated/negotiating on this connection
            }

            ChannelPipeline pipeline = channel.pipeline();
            Object originalDecoder = pipeline.get(FRAME_DECODER);
            if (!(originalDecoder instanceof MinecraftVarintFrameDecoder vanillaDecoder)) {
                logger.warn("VeloZip: unexpected frame-decoder type {} on backend pipeline, skipping",
                        originalDecoder == null ? "null" : originalDecoder.getClass().getName());
                return;
            }

            NegotiationState state = new NegotiationState(channel, serverName, player, vanillaDecoder);
            channel.attr(STATE_KEY).set(state);

            byte[] nonce = Negotiation.newNonce();
            byte[] hmac = cfg.secret.isEmpty() ? null
                    : Negotiation.hmacSha256(cfg.secret.getBytes(StandardCharsets.UTF_8),
                            nonce, VeloZip.TRANSPORT_PROTOCOL, Negotiation.ALGO_ZSTD, VeloZip.COMPRESSION_LEVEL);
            byte[] req = Negotiation.encodeReq(VeloZip.TRANSPORT_PROTOCOL, Negotiation.ALGO_ZSTD,
                    VeloZip.COMPRESSION_LEVEL, nonce, hmac, pluginVersion);

            mc.eventLoop().execute(() -> {
                try {
                    pipeline.replace(FRAME_DECODER, FRAME_DECODER,
                            new VeloZipVelocityFrameDecoder(cfg, metrics, logger,
                                    () -> onTransportActive(channel, state)));
                    boolean sent = conn.sendPluginMessage(VeloZipChannelIds.IDENTIFIER, req);
                    if (!sent) {
                        logger.warn("VeloZip: could not send negotiation message to {}", serverName);
                        restore(channel, state);
                        return;
                    }
                    state.timeout = mc.eventLoop().schedule(() -> onTimeout(state),
                            VeloZip.NEGOTIATION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    if (cfg.debug) {
                        logger.debug("VeloZip: negotiation sent to {}", serverName);
                    }
                } catch (Throwable t) {
                    logger.error("VeloZip: failed to start negotiation with {}", t, serverName);
                    restore(channel, state);
                }
            });
        } catch (Throwable t) {
            logger.error("VeloZip: negotiate() failed, leaving connection vanilla", t);
        }
    }

    /** Runs on the channel event loop when the backend's first VeloZip frame arrives. */
    private void onTransportActive(Channel channel, NegotiationState state) {
        if (!state.activated.compareAndSet(false, true)) {
            return;
        }
        if (state.timeout != null) {
            state.timeout.cancel(false);
        }
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(COMPRESSION_DECODER) != null) {
                pipeline.remove(COMPRESSION_DECODER);
            }
            if (pipeline.get(COMPRESSION_ENCODER) != null) {
                pipeline.remove(COMPRESSION_ENCODER);
            }
            if (pipeline.get(FRAME_ENCODER) != null) {
                pipeline.remove(FRAME_ENCODER);
            }
            pipeline.addBefore(MINECRAFT_ENCODER, VELOZIP_ENCODER,
                    new VeloZipBatchEncoder(cfg, metrics, logger));
        } catch (Throwable t) {
            logger.error("VeloZip: failed to activate transport for {}", t, state.serverName);
            channel.close();
            return;
        }
        logger.info("VeloZip transport enabled for {}", state.serverName);
    }

    private void onTimeout(NegotiationState state) {
        if (state.activated.get()) {
            return;
        }
        logger.warn("VeloZip: no VeloZip response from {} within {} s "
                        + "(backend plugin missing, disabled, or refused)",
                state.serverName, VeloZip.NEGOTIATION_TIMEOUT_SECONDS);
        failedServers.put(state.serverName, "negotiation timeout");
        restore(state.channel, state);
        if (cfg.requireVelozip) {
            state.player.disconnect(Component.text(
                    "VeloZip is required for server '" + state.serverName
                            + "' but negotiation failed (see proxy logs)"));
        }
    }

    /** Called by the plugin when a REFUSE message arrives on the velozip channel. */
    public void onRefuse(Channel channel, Negotiation.Refuse refuse) {
        NegotiationState state = channel.attr(STATE_KEY).get();
        if (state == null || state.activated.get()) {
            return;
        }
        if (state.timeout != null) {
            state.timeout.cancel(false);
        }
        logger.warn("VeloZip: backend refused VeloZip for {}: {} (reason code {})",
                state.serverName, refuse.message(), refuse.reasonCode());
        failedServers.put(state.serverName, "refused: " + refuse.message());
        state.channel.eventLoop().execute(() -> restore(state.channel, state));
        if (cfg.requireVelozip) {
            state.player.disconnect(Component.text(
                    "VeloZip negotiation refused by '" + state.serverName + "': " + refuse.message()));
        }
    }

    private void restore(Channel channel, NegotiationState state) {
        state.activated.set(true); // stop any late activation
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(FRAME_DECODER) instanceof VeloZipVelocityFrameDecoder) {
                pipeline.replace(FRAME_DECODER, FRAME_DECODER, state.originalDecoder);
            }
        } catch (Throwable t) {
            logger.error("VeloZip: failed to restore vanilla decoder", t);
        }
        channel.attr(STATE_KEY).set(null);
    }

    /** Per-connection negotiation bookkeeping. */
    static final class NegotiationState {
        final Channel channel;
        final String serverName;
        final Player player;
        final MinecraftVarintFrameDecoder originalDecoder;
        final AtomicBoolean activated = new AtomicBoolean();
        volatile ScheduledFuture<?> timeout;

        NegotiationState(Channel channel, String serverName, Player player,
                         MinecraftVarintFrameDecoder originalDecoder) {
            this.channel = channel;
            this.serverName = serverName;
            this.player = player;
            this.originalDecoder = originalDecoder;
        }
    }
}