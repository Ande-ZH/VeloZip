package dev.velozip.backend;

import dev.velozip.backend.adapter.NetworkAdapter;
import dev.velozip.backend.adapter.Purpur2612NetworkAdapter;
import dev.velozip.backend.command.VeloZipBackendCommand;
import dev.velozip.backend.config.BackendConfigLoader;
import dev.velozip.common.VeloZip;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.protocol.Negotiation;
import io.papermc.paper.configuration.GlobalConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * VeloZip-Backend: listens for the negotiation plugin message from
 * VeloZip-Velocity on player connections and activates the VeloZip transport
 * after validation. Connections that never send a REQ (direct players, other
 * proxies) are never touched.
 */
public final class VeloZipBackendPlugin extends JavaPlugin implements org.bukkit.plugin.messaging.PluginMessageListener {

    private VeloZipLogger logger;
    private VeloZipConfig config;
    private final VeloZipMetrics metrics = new VeloZipMetrics();
    private NetworkAdapter adapter;

    @Override
    public void onEnable() {
        Logger jul = getLogger();
        this.logger = new JulVeloZipLogger(jul);
        try {
            Path configFile = getDataFolder().toPath().resolve("config.yml");
            this.config = BackendConfigLoader.load(configFile);
        } catch (Exception e) {
            jul.severe("VeloZip: invalid configuration, the plugin will stay disabled: " + e.getMessage());
            // Keep the plugin loaded so /velozip can at least report the failure.
            getCommand("velozip").setExecutor((sender, command, label, args) -> {
                sender.sendMessage("§cVeloZip is disabled due to a configuration error: " + e.getMessage());
                return true;
            });
            return;
        }
        if (!config.enabled) {
            jul.info("VeloZip is disabled by configuration (enabled: false)");
            return;
        }

        this.adapter = new Purpur2612NetworkAdapter(config, metrics, logger);

        getServer().getMessenger().registerIncomingPluginChannel(this, VeloZip.CHANNEL_FULL, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, VeloZip.CHANNEL_FULL);

        VeloZipBackendCommand command = new VeloZipBackendCommand(metrics,
                getServer().getName() + " " + getServer().getBukkitVersion());
        getCommand("velozip").setExecutor(command);
        getCommand("velozip").setTabCompleter(command);

        logger.info("VeloZip {}", "0.1.0");
        logger.info("Platform: {} {}", getServer().getName(), getServer().getBukkitVersion());
        logger.info("Transport protocol: {}", VeloZip.TRANSPORT_PROTOCOL);
        logger.info("Compression: {} Level {}", VeloZip.ALGORITHM_ZSTD, VeloZip.COMPRESSION_LEVEL);
        if (!config.secret.isEmpty()) {
            logger.info("Authentication: enabled (shared secret)");
        }
        logger.info("Awaiting VeloZip negotiation from Velocity on channel {}", VeloZip.CHANNEL_FULL);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    /**
     * REQ from VeloZip-Velocity. Runs on the main thread (Bukkit plugin
     * message dispatch is scheduled off the netty thread by the server).
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!VeloZip.CHANNEL_FULL.equals(channel) || adapter == null) {
            return;
        }
        try {
            Negotiation.Req req;
            try {
                req = Negotiation.decodeReq(message);
            } catch (IllegalArgumentException e) {
                logger.warn("VeloZip: malformed REQ from {}: {}", player.getName(), e.getMessage());
                return; // do not switch anything on garbage
            }

            // A REQ is only trustworthy when the connection itself came through
            // Velocity modern forwarding: with forwarding enabled, a direct
            // connection cannot pass login HMAC verification.
            if (!velocityForwardingEnabled()) {
                logger.warn("VeloZip: ignoring REQ from {} because paper-global.yml "
                        + "proxies.velocity.enabled is false (direct connections cannot be verified)", player.getName());
                refuse(player, new Negotiation.Refuse(Negotiation.REASON_NOT_PROXY,
                        "backend does not enforce Velocity modern forwarding"));
                return;
            }

            Negotiation.Refuse refuse = Negotiation.validateReq(req, config.secret, config.enabled);
            if (refuse != null) {
                refuse(player, refuse);
                return;
            }

            adapter.activate(player);
        } catch (Throwable t) {
            logger.error("VeloZip: error handling REQ from {}", t, player.getName());
        }
    }

    private boolean velocityForwardingEnabled() {
        try {
            return GlobalConfiguration.get().proxies.velocity.enabled;
        } catch (Throwable t) {
            logger.warn("VeloZip: could not read paper-global configuration, assuming no forwarding: {}", t.toString());
            return false;
        }
    }

    private void refuse(Player player, Negotiation.Refuse refuse) {
        logger.warn("VeloZip: refusing activation for {} ({}): {}",
                player.getName(), refuse.reasonCode(), refuse.message());
        try {
            player.sendPluginMessage(this, VeloZip.CHANNEL_FULL, Negotiation.encodeRefuse(refuse));
        } catch (Throwable t) {
            // Sending requires the channel to be declared by the remote side;
            // if it fails, VeloZip-Velocity will time out and fall back.
            logger.debug("VeloZip: REFUSE delivery failed (Velocity will time out): {}", t.toString());
        }
    }
}
