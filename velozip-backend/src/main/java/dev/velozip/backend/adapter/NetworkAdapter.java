package dev.velozip.backend.adapter;

import org.bukkit.entity.Player;

/**
 * Platform seam for backend-side pipeline work (prompt §26). Phase 1 ships
 * exactly one implementation, Purpur2612NetworkAdapter.
 */
public interface NetworkAdapter {

    boolean isSupported();

    /**
     * Activates the VeloZip transport on the player's connection (the REQ has
     * already been validated). All pipeline mutations run on the channel's
     * event loop, atomically.
     */
    void activate(Player player);
}
