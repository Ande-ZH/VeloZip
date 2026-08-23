package com.velocitypowered.proxy.protocol.netty;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * STUB of Velocity 3.4.0 internal (proxy/src/main/java/com/velocitypowered/proxy/
 * protocol/netty/MinecraftVarintFrameDecoder.java). Compile-time only; never
 * packaged. Only the surface used by VeloZip is declared; the real class also
 * has setState(String) which PLAY<->CONFIG transitions invoke — our subclass
 * inherits it from the real class at runtime, which is exactly why VeloZip's
 * dual-mode decoder must extend this type.
 */
public class MinecraftVarintFrameDecoder extends ByteToMessageDecoder {

    public MinecraftVarintFrameDecoder(ProtocolUtils.Direction direction) {
        // Real class stores the direction; unused by the stub.
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, io.netty.buffer.ByteBuf in, List<Object> out)
            throws Exception {
        // Real implementation parses 21-bit varint length frames.
        throw new UnsupportedOperationException("stub");
    }
}
