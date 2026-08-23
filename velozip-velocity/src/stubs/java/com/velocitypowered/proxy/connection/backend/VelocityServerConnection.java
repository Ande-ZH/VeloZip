package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;

/**
 * STUB of Velocity 3.4.0 internal (com.velocitypowered.proxy.connection.backend.
 * VelocityServerConnection). Compile-time only; never packaged.
 */
public class VelocityServerConnection {

    public MinecraftConnection getConnection() {
        throw new UnsupportedOperationException("stub");
    }

    public MinecraftConnection ensureConnected() {
        throw new UnsupportedOperationException("stub");
    }

    public RegisteredServer getServer() {
        throw new UnsupportedOperationException("stub");
    }

    public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
        throw new UnsupportedOperationException("stub");
    }
}
