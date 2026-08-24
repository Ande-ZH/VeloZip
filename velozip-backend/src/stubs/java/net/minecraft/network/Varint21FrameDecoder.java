package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * STUB of vanilla 26.1.2 net.minecraft.network.Varint21FrameDecoder (the TCP
 * frame decoder created by Connection.configureSerialization, pipeline name
 * "splitter"). Compile-time only; never packaged. The real constructor takes
 * a nullable BandwidthDebugMonitor (vanilla passes null for normal TCP
 * connections); VeloZip's subclass passes null too.
 */
public class Varint21FrameDecoder extends ByteToMessageDecoder {

    public Varint21FrameDecoder(BandwidthDebugMonitor monitor) {
        // Real class stores the monitor; unused by the stub.
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        throw new UnsupportedOperationException("stub");
    }
}
