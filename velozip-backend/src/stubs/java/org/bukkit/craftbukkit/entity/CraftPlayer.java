package org.bukkit.craftbukkit.entity;

import net.minecraft.server.level.ServerPlayer;

/**
 * STUB of CraftBukkit's CraftPlayer (unversioned package since 1.20.5+).
 * Compile-time only; never packaged. Deliberately does not implement the
 * Player interface — the cast site only needs getHandle(), and at runtime the
 * real CraftPlayer is a Player.
 */
public class CraftPlayer {

    public ServerPlayer getHandle() {
        throw new UnsupportedOperationException("stub");
    }
}
