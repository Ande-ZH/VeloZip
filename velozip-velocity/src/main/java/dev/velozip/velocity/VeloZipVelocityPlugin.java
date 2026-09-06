package dev.velozip.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import dev.velozip.common.VeloZip;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.protocol.Negotiation;
import dev.velozip.velocity.adapter.NetworkAdapter;
import dev.velozip.velocity.adapter.VeloZipChannelIds;
import dev.velozip.velocity.adapter.VelocityNetworkAdapter;
import dev.velozip.velocity.command.VeloZipCommand;
import dev.velozip.velocity.config.VelocityConfigLoader;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * VeloZip-Velocity: negotiates and drives the VeloZip transport on backend
 * connections. The client-facing side of the proxy is never touched.
 */
@Plugin(id = "velozip", name = "VeloZip", version = VeloZipVelocityPlugin.PLUGIN_VERSION,
        description = "Zstd Level 1 transport compression for Velocity <-> Purpur backend connections",
        authors = {"Ande-ZH"})
public final class VeloZipVelocityPlugin {

    public static final String PLUGIN_VERSION = "0.3.0";

    private final ProxyServer proxy;
    private final Logger slf4jLogger;
    private final Path dataDirectory;

    private VeloZipLogger logger;
    private VeloZipConfig config;
    private final VeloZipMetrics metrics = new VeloZipMetrics();
    private NetworkAdapter adapter;

    @Inject
    public VeloZipVelocityPlugin(ProxyServer proxy, Logger slf4jLogger,
                                 @com.velocitypowered.api.plugin.annotation.DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.slf4jLogger = slf4jLogger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.logger = new Slf4jVeloZipLogger(slf4jLogger);
        try {
            this.config = VelocityConfigLoader.load(dataDirectory.resolve("config.yml"));
        } catch (Exception e) {
            slf4jLogger.error("VeloZip: invalid configuration, the plugin will stay disabled (details suppressed)");
            return;
        }

        VelocityNetworkAdapter velocityAdapter =
                new VelocityNetworkAdapter(config, metrics, logger, PLUGIN_VERSION);
        this.adapter = velocityAdapter;
        velocityAdapter.checkPlatform(proxy.getVersion().getVersion());

        // Registering the channel makes Velocity route velozip plugin messages to
        // our PluginMessageEvent subscription instead of forwarding them to clients.
        proxy.getChannelRegistrar().register(VeloZipChannelIds.IDENTIFIER);

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("velozip").plugin(this).build(),
                new VeloZipCommand(metrics, PLUGIN_VERSION,
                        "Velocity " + proxy.getVersion().getVersion(), config, velocityAdapter));

        logger.info("VeloZip {}", PLUGIN_VERSION);
        logger.info("Platform: Velocity {}", proxy.getVersion().getVersion());
        logger.info("Transport protocol: {}", VeloZip.TRANSPORT_PROTOCOL);
        logger.info("Compression: {} Level {}", VeloZip.ALGORITHM_ZSTD, VeloZip.COMPRESSION_LEVEL);
        if (!config.secret.isEmpty()) {
            logger.info("Authentication: enabled (shared secret)");
        }
        if (config.requireVelozip) {
            logger.info("require-velozip: backends failing negotiation will be refused");
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (adapter == null || !config.enabled) {
            return;
        }
        // Must be fast: this event is @AwaitingEvent with autoRead paused.
        adapter.negotiate(event.getPlayer(), event.getServer());    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (adapter == null || !event.getIdentifier().equals(VeloZipChannelIds.IDENTIFIER)) {
            return;
        }
        // Never let velozip control traffic reach a real client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection source)) {
            return;
        }
        try {
            Negotiation.Refuse refuse = Negotiation.decodeRefuse(event.getData());
            ((VelocityNetworkAdapter) adapter).onRefuse(channelOf(source), refuse);
        } catch (Exception e) {
            logger.warn("VeloZip: malformed message from {}", source.getServerInfo().getName());
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (adapter == null || !config.enabled || !config.requireVelozip) {
            return;
        }
        String target = event.getResult().getServer()
                .map(s -> s.getServerInfo().getName())
                .orElseGet(() -> event.getOriginalServer().getServerInfo().getName());
        if (!config.enabledForServer(target)) {
            return;
        }
        String failure = ((VelocityNetworkAdapter) adapter).knownFailure(target);
        if (failure != null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            event.getPlayer().sendMessage(Component.text(
                    "VeloZip is required for server '" + target + "' but it previously failed: "
                            + failure, NamedTextColor.RED));
        }
    }

    private Channel channelOf(ServerConnection source) {
        try {
            com.velocitypowered.proxy.connection.backend.VelocityServerConnection internal =
                    (com.velocitypowered.proxy.connection.backend.VelocityServerConnection) source;
            return internal.getConnection() != null ? internal.getConnection().getChannel() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
