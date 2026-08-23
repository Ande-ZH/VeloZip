package net.minecraft.network;

import io.netty.channel.Channel;

/**
 * STUB of vanilla net.minecraft.network.Connection (pipeline name
 * "packet_handler"). Compile-time only; never packaged. The real class exposes
 * the channel (public field in modern mojang mappings; verified live in M4,
 * see docs/STUBS.md).
 */
public class Connection {

    public Channel channel = null;
}
