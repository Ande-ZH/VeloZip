package dev.velozip.velocity.adapter;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Platform seam for Velocity-side pipeline work (prompt §26). Phase 1 ships
 * exactly one implementation, Velocity340NetworkAdapter; future Velocity
 * versions add new implementations instead of changing the compression core.
 */
public interface NetworkAdapter {

    boolean isSupported();

    /** Begins negotiation on the player's current backend connection. */
    void negotiate(Player player, RegisteredServer server);
}
