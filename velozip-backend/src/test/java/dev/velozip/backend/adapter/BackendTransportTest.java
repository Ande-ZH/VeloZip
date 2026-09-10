package dev.velozip.backend.adapter;

import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import static org.junit.jupiter.api.Assertions.*;

class BackendTransportTest {
    private final VeloZipMetrics metrics = new VeloZipMetrics();
    private final VeloZipConfig cfg = VeloZipConfig.builder().batchMaxSize(256).batchMaxDelayMicros(10000).build();
    private final PurpurNetworkAdapter adapter = new PurpurNetworkAdapter(cfg, metrics,
            new VeloZipLogger() {
                public void info(String message, Object... args) {}
                public void warn(String message, Object... args) {}
                public void error(String message, Throwable error, Object... args) { throw new AssertionError(message, error); }
                public void debug(String message, Object... args) {}
            });

    private static final class NativeState { boolean closed; int decoded; }

    private EmbeddedChannel channel(NativeState state, boolean compressed) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("splitter", new ByteToMessageDecoder() {
            @Override protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                int start = in.readerIndex();
                int len = VarInts.tryReadVarInt(in, 3, 0x1fffff);
                if (len < 0 || in.readableBytes() < len) { in.readerIndex(start); return; }
                out.add(in.readRetainedSlice(len));
            }
        });
        if (compressed) ch.pipeline().addLast("decompress", new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                assertFalse(state.closed, "shared native resource closed before last vanilla packet");
                ByteBuf in = (ByteBuf) msg;
                Inflater inflater = new Inflater();
                try {
                    int len = VarInts.readVarInt(in, 5, 1 << 21);
                    byte[] compressedBytes = new byte[in.readableBytes()];
                    in.readBytes(compressedBytes);
                    inflater.setInput(compressedBytes);
                    byte[] result = new byte[len];
                    assertEquals(len, inflater.inflate(result));
                    assertTrue(inflater.finished());
                    state.decoded++;
                    ctx.fireChannelRead(Unpooled.wrappedBuffer(result));
                } finally { inflater.end(); in.release(); }
            }
            @Override public void handlerRemoved(ChannelHandlerContext ctx) { state.closed = true; }
        });
        ch.pipeline().addLast("prepender", forbiddenOutbound());
        if (compressed) ch.pipeline().addLast("compress", new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ((ByteBuf) msg).release();
                promise.setFailure(new AssertionError("VeloZip was double-compressed"));
            }
            @Override public void handlerRemoved(ChannelHandlerContext ctx) { state.closed = true; }
        });
        ch.pipeline().addLast("encoder", new ChannelOutboundHandlerAdapter());
        return ch;
    }

    private static ChannelHandler forbiddenOutbound() {
        return new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ((ByteBuf) msg).release();
                promise.setFailure(new AssertionError("VeloZip passed through vanilla framing"));
            }
        };
    }

    private static ByteBuf raw(int value) {
        return Unpooled.buffer().writeBytes(new byte[]{0, 0x5a, 1, 2, 2, 1, (byte) value});
    }

    private static ByteBuf vanilla(byte[] body) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(body); deflater.finish();
            byte[] buffer = new byte[body.length + 128];
            int size = deflater.deflate(buffer);
            ByteBuf framed = Unpooled.buffer();
            VarInts.writeVarInt(framed, size + VarInts.varIntLen(body.length));
            VarInts.writeVarInt(framed, body.length);
            return framed.writeBytes(buffer, 0, size);
        } finally { deflater.end(); }
    }

    private static void assertBody(EmbeddedChannel ch, byte[] bytes) {
        ByteBuf body = ch.readInbound();
        assertNotNull(body);
        try {
            byte[] actual = new byte[body.readableBytes()]; body.readBytes(actual);
            assertArrayEquals(bytes, actual);
        } finally { body.release(); }
    }

    @Test void sharedNativeResourceSurvivesOutboundActivationAndCoalescedInboundSwitch() {
        NativeState state = new NativeState();
        EmbeddedChannel ch = channel(state, true);
        try {
            adapter.activateChannel(ch, "test");
            adapter.activateChannel(ch, "test");
            assertEquals(1, metrics.activeConnections.sum());
            assertFalse(state.closed);
            ch.writeOutbound(Unpooled.buffer().writeZero(300)); // emit immediately
            ByteBuf wire = ch.readOutbound();
            assertNotNull(wire);
            try { assertEquals(0x005a, wire.readUnsignedShort()); } finally { wire.release(); }
            byte[] plain = new byte[300]; plain[0] = 7;
            ByteBuf mixed = vanilla(plain), next = raw(42);
            mixed.writeBytes(next); next.release();
            ch.writeInbound(mixed);
            assertBody(ch, plain);
            assertBody(ch, new byte[]{42});
            assertEquals(1, state.decoded);
            assertTrue(state.closed);
            assertNull(ch.pipeline().get("decompress"));
            assertNull(ch.pipeline().get("compress"));
            assertNull(ch.pipeline().get("prepender"));
            ch.writeOutbound(Unpooled.buffer().writeZero(300));
            ByteBuf after = ch.readOutbound();
            try { assertEquals(0x005a, after.readUnsignedShort()); } finally { after.release(); }
        } finally { ch.finishAndReleaseAll(); }
        assertEquals(0, metrics.activeConnections.sum());
    }

    @Test void replacementPreservesAlreadyBufferedPartialVanillaFrame() {
        NativeState state = new NativeState();
        EmbeddedChannel ch = channel(state, true);
        ByteBuf pending = vanilla(new byte[]{3, 4, 5});
        try {
            ch.writeInbound(pending.readRetainedSlice(2));
            adapter.activateChannel(ch, "test");
            ch.writeInbound(pending.readRetainedSlice(pending.readableBytes()));
            assertBody(ch, new byte[]{3, 4, 5});
            assertFalse(state.closed);
            ch.writeInbound(raw(6));
            assertBody(ch, new byte[]{6});
            assertTrue(state.closed);
        } finally { pending.release(); ch.finishAndReleaseAll(); }
    }

    @Test void handlesEveryFragmentAndUncompressedVanillaBeforeSwitch() {
        EmbeddedChannel ch = channel(new NativeState(), false);
        try {
            adapter.activateChannel(ch, "test");
            // Magic inside a vanilla body is data, not a switch.
            ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{3, 0, 0x5a, 5}));
            assertBody(ch, new byte[]{0, 0x5a, 5});
            ByteBuf next = raw(9);
            try { while (next.isReadable()) ch.writeInbound(next.readRetainedSlice(1)); }
            finally { next.release(); }
            assertBody(ch, new byte[]{9});
            assertNull(ch.readInbound());
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test void delayedBatchBypassesVanillaAndCloseReleasesResources() throws Exception {
        NativeState state = new NativeState();
        EmbeddedChannel ch = channel(state, true);
        try {
            adapter.activateChannel(ch, "test");
            ch.writeOutbound(Unpooled.wrappedBuffer(new byte[]{1, 2}));
            assertNull(ch.readOutbound());
            TimeUnit.MILLISECONDS.sleep(15); ch.runPendingTasks();
            ByteBuf wire = ch.readOutbound();
            assertNotNull(wire);
            try { assertEquals(0x005a, wire.readUnsignedShort()); } finally { wire.release(); }
            assertFalse(state.closed);
        } finally { ch.finishAndReleaseAll(); }
        assertTrue(state.closed);
        assertEquals(0, metrics.activeConnections.sum());
    }

    @Test void missingAnchorsLeavePipelineUntouched() {
        EmbeddedChannel ch = channel(new NativeState(), false);
        try {
            ch.pipeline().remove("encoder");
            List<String> before = ch.pipeline().names();
            adapter.activateChannel(ch, "test");
            assertEquals(before, ch.pipeline().names());
            assertEquals(0, metrics.activeConnections.sum());
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test void malformedAndOversizedPrefixesCloseConnection() {
        for (byte[] bad : new byte[][]{{0, 1}, {(byte) 0x80, (byte) 0x80, (byte) 0x80}, {0, 0x5a, 9, 2}}) {
            EmbeddedChannel ch = channel(new NativeState(), false);
            try {
                adapter.activateChannel(ch, "test");
                ch.writeInbound(Unpooled.wrappedBuffer(bad));
                assertFalse(ch.isOpen());
            } finally { ch.finishAndReleaseAll(); }
        }
    }

    @Test void recognizesOldAndCurrentBackendFamiliesWithoutClaimingFutureOnes() {
        for (String version : List.of("1.18", "1.18.2-R0.1-SNAPSHOT", "1.19.4", "1.20.4", "1.21.11", "26.1.2", "26.2")) {
            adapter.checkPlatform(version); assertTrue(adapter.isSupported(), version);
        }
        for (String version : List.of("1.17.1", "27.1", "unknown")) {
            adapter.checkPlatform(version); assertFalse(adapter.isSupported(), version);
        }
    }
}
