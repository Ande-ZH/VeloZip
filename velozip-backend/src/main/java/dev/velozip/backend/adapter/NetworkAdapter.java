package dev.velozip.backend.adapter;

import org.bukkit.entity.Player;

/**
 * Platform seam for backend-side pipeline work. The single implementation
 * covers Purpur 26.1.2 and 26.2 (the network layer is identical across both
 * — see PurpurNetworkAdapter).
 */
public interface NetworkAdapter {

    /** Whether the detected platform family is among the verified ones. */
    boolean isSupported();

    /**
     * Activates the VeloZip transport on the player's connection (the REQ has
     * already been validated). All pipeline mutations run on the channel's
     * event loop, atomically.
     */
    void activate(Player player);
}
