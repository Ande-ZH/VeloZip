package dev.velozip.velocity.adapter;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Platform seam for Velocity-side pipeline work. The single implementation
 * covers Velocity 3.4.0 through 4.1.1 (the network layer is identical across
 * that range — see VelocityNetworkAdapter); genuinely divergent future
 * versions would add new implementations instead of changing the core.
 */
public interface NetworkAdapter {

    /** Whether the detected platform family is among the verified ones. */
    boolean isSupported();

    /** Begins negotiation on the player's current backend connection. */
    void negotiate(Player player, RegisteredServer server);
}
