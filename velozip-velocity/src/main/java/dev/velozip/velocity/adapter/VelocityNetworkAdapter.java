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
 *   <li>REFUSE or timeout puts the installed decoder into vanilla-only mode</li>
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
    private final Map<String, NegotiationState> latestAttempts = new ConcurrentHashMap<>();
    private final Map<String, ServerStatus> serverStatuses = new ConcurrentHashMap<>();
    private static final long COOLDOWN_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    public record ServerStatus(String state, String reason, long retryAfterNanos) {}
    public Map<String, ServerStatus> statuses() { return Map.copyOf(serverStatuses); }
    public boolean retry(String serverName) {
        ServerStatus status = serverStatuses.get(serverName);
        return status != null && status.state().equals("FAILED") && serverStatuses.remove(serverName, status);
    }
    private void failed(String serverName, String reason) {
        serverStatuses.put(serverName, new ServerStatus("FAILED", reason, System.nanoTime() + COOLDOWN_NANOS));
    }

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
        ServerStatus status = serverStatuses.get(serverName);
        if (status == null || !status.state().equals("FAILED")) return null;
        if (System.nanoTime() - status.retryAfterNanos() < 0) return status.reason();
        serverStatuses.remove(serverName, status);
        return null;
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
            if (!cfg.enabledForServer(serverName) || knownFailure(serverName) != null) {
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
                failed(serverName, "unexpected frame decoder");
                if (cfg.requireVelozip) channel.close();
                logger.warn("VeloZip: unexpected frame-decoder type {} on backend pipeline, skipping",
                        originalDecoder == null ? "null" : originalDecoder.getClass().getName());
                return;
            }

            NegotiationState state = new NegotiationState(channel, serverName, player, vanillaDecoder);
            channel.attr(STATE_KEY).set(state);
            latestAttempts.put(serverName, state);
            serverStatuses.put(serverName, new ServerStatus("NEGOTIATING", "", 0));
            channel.closeFuture().addListener(f -> {
                state.finished.set(true);
                if (state.timeout != null) state.timeout.cancel(false);
                channel.attr(STATE_KEY).compareAndSet(state, null);
                if (latestAttempts.remove(serverName, state))
                    serverStatuses.computeIfPresent(serverName, (k, status) ->
                            status.state().equals("FAILED") ? status : new ServerStatus("CLOSED", "", 0));
            });

            byte[] nonce = Negotiation.newNonce();
            byte[] hmac = cfg.secret.isEmpty() ? null
                    : Negotiation.hmacSha256(cfg.secret.getBytes(StandardCharsets.UTF_8),
                            nonce, VeloZip.TRANSPORT_PROTOCOL, Negotiation.ALGO_ZSTD, VeloZip.COMPRESSION_LEVEL);
            byte[] req = Negotiation.encodeReq(VeloZip.TRANSPORT_PROTOCOL, Negotiation.ALGO_ZSTD,
                    VeloZip.COMPRESSION_LEVEL, nonce, hmac, pluginVersion);

            mc.eventLoop().execute(() -> {
                try {
                    if (!channel.isActive() || channel.attr(STATE_KEY).get() != state || state.finished.get()) return;
                    if (pipeline.get(MINECRAFT_ENCODER) == null
                            || (pipeline.get(FRAME_ENCODER) == null && pipeline.get(COMPRESSION_ENCODER) == null)) {
                        failed(serverName, "missing required pipeline anchors");
                        restore(channel, state);
                        if (cfg.requireVelozip) channel.close();
                        return;
                    }
                    pipeline.replace(FRAME_DECODER, FRAME_DECODER,
                            new VeloZipVelocityFrameDecoder(cfg, metrics, logger,
                                    () -> onTransportActive(channel, state)));
                    // Old CraftPlayer.sendPluginMessage silently drops REFUSE
                    // unless the remote side has advertised this channel. Only
                    // the backend connection is registered, before REQ in TCP order.
                    boolean sent = conn.sendPluginMessage(VeloZipChannelIds.REGISTER,
                            VeloZip.CHANNEL_FULL.getBytes(StandardCharsets.UTF_8))
                            && conn.sendPluginMessage(VeloZipChannelIds.IDENTIFIER, req);
                    if (!sent) {
                        failed(serverName, "negotiation send failed");
                        if (cfg.requireVelozip) channel.close();
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
                    failed(serverName, "negotiation startup failed");
                    if (cfg.requireVelozip) channel.close();
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
        if (!channel.isActive() || channel.attr(STATE_KEY).get() != state
                || !state.finished.compareAndSet(false, true)) {
            return;
        }
        if (state.timeout != null) {
            state.timeout.cancel(false);
        }
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(MINECRAFT_ENCODER) == null
                    || (pipeline.get(FRAME_ENCODER) == null && pipeline.get(COMPRESSION_ENCODER) == null))
                throw new IllegalStateException("missing required pipeline anchors");
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
            failed(state.serverName, "activation failed");
            logger.error("VeloZip: failed to activate transport for {}", t, state.serverName);
            channel.close();
            return;
        }
        if (latestAttempts.get(state.serverName) == state)
            serverStatuses.put(state.serverName, new ServerStatus("ACTIVE", "", 0));
        logger.info("VeloZip transport enabled for {}", state.serverName);
    }

    private void onTimeout(NegotiationState state) {
        if (!state.channel.isActive() || state.channel.attr(STATE_KEY).get() != state || state.finished.get()) {
            return;
        }
        logger.warn("VeloZip: no VeloZip response from {} within {} s "
                        + "(backend plugin missing, disabled, or refused)",
                state.serverName, VeloZip.NEGOTIATION_TIMEOUT_SECONDS);
        if (latestAttempts.get(state.serverName) == state) failed(state.serverName, "negotiation timeout");
        restore(state.channel, state);
        if (cfg.requireVelozip) {
            state.player.disconnect(Component.text(
                    "VeloZip is required for server '" + state.serverName
                            + "' but negotiation failed (see proxy logs)"));
        }
    }

    /** Called by the plugin when a REFUSE message arrives on the velozip channel. */
    public void onRefuse(Channel channel, Negotiation.Refuse refuse) {
        if (channel == null || refuse == null) return;
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> onRefuse(channel, refuse));
            return;
        }
        NegotiationState state = channel.attr(STATE_KEY).get();
        if (state == null || state.finished.get() || !channel.isActive()) {
            return;
        }
        if (state.timeout != null) {
            state.timeout.cancel(false);
        }
        logger.warn("VeloZip: backend refused VeloZip for {}: {} (reason code {})",
                state.serverName, refuse.message(), refuse.reasonCode());
        if (latestAttempts.get(state.serverName) == state) failed(state.serverName, "refused (code " + refuse.reasonCode() + ")");
        restore(state.channel, state);
        if (cfg.requireVelozip) {
            state.player.disconnect(Component.text(
                    "VeloZip negotiation refused by '" + state.serverName + "': " + refuse.message()));
        }
    }

    private void restore(Channel channel, NegotiationState state) {
        if (channel.attr(STATE_KEY).get() != state) return;
        state.finished.set(true);
        if (state.timeout != null) state.timeout.cancel(false);
        try {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(FRAME_DECODER) instanceof VeloZipVelocityFrameDecoder decoder) {
                // ByteToMessageDecoder is not sharable: removed instances cannot
                // be added again. Keep this instance and its pending bytes/state.
                decoder.fallbackToVanilla();
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
        final AtomicBoolean finished = new AtomicBoolean();
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
