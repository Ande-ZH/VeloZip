package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * STUB of vanilla 26.1.2 net.minecraft.network.Varint21FrameDecoder (the TCP
 * frame decoder created by Connection.configureSerialization, pipeline name
 * "splitter"). Compile-time only; never packaged. The real class's decode()
 * performs vanilla varint21 length framing; VeloZip's dual-mode decoder calls
 * super.decode() for vanilla frames and only replaces the transport framing.
 */
public class Varint21FrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        throw new UnsupportedOperationException("stub");
    }
}
