package dev.velozip.common;

import com.github.luben.zstd.ZstdCompressCtx;
import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.transport.VeloZipBatchEncoder;
import dev.velozip.common.util.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeloZipTransportTest {

    private final VeloZipMetrics metrics = new VeloZipMetrics();
    private EmbeddedChannel channel;

    /**
     * Tests use the maximum delay window (10 ms) so the timer never fires in
     * the middle of a test's (slow, JVM-warmup-ridden) sequence of writes;
     * emission is then fully controlled by the size cap or runTimers().
     */
    private VeloZipConfig cfg(int rawThreshold, int batchSize) {
        return VeloZipConfig.builder()
                .rawThreshold(rawThreshold)
                .batchMaxSize(batchSize)
                .batchMaxDelayMicros(10_000)
                .build();
    }

    private EmbeddedChannel newChannel(VeloZipConfig cfg) {
        channel = new EmbeddedChannel(
                new TestDualDecoder(cfg, metrics),
                new VeloZipBatchEncoder(cfg, metrics, TestLogger.INSTANCE));
        return channel;
    }

    /** Drives the delay-window timer past the 10 ms test window. */
    private void runTimers() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(15);
        channel.runPendingTasks();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            try {
                channel.finishAndReleaseAll();
            } catch (dev.velozip.common.frame.VeloZipFrameException expected) {
                // A batch still pending at close time fails its packet promises;
                // EmbeddedChannel surfaces that here. Expected, not a leak.
            }
        }
        assertEquals(0, metrics.activeConnections.sum(), "active connection counter leaked");
    }

    private static ByteBuf packet(String data) {
        return Unpooled.wrappedBuffer(data.getBytes(StandardCharsets.UTF_8));
    }

    private static ByteBuf packet(int size, Random random) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return Unpooled.wrappedBuffer(bytes);
    }

    private static ByteBuf packetFill(int size, byte fill) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, fill);
        return Unpooled.wrappedBuffer(bytes);
    }

    @SuppressWarnings("DataFlowIssue")
    private TestDualDecoder dual(EmbeddedChannel ch) {
        return (TestDualDecoder) ch.pipeline().get(ByteToMessageDecoder.class);
    }

    /** Drains every frame the encoder produced so far. */
    private List<ByteBuf> drainOutbound(EmbeddedChannel ch) {
        List<ByteBuf> frames = new ArrayList<>();
        ByteBuf out;
        while ((out = ch.readOutbound()) != null) {
            frames.add(out);
        }
        return frames;
    }

    private void releaseFrames(List<ByteBuf> frames) {
        for (ByteBuf f : frames) {
            f.release();
        }
    }

    /** Runs an inbound write that is expected to be fatal; asserts the channel closes. */
    private void assertClosedAfter(EmbeddedChannel ch, ByteBuf input) {
        try {
            ch.writeInbound(input);
        } catch (Throwable expected) {
            // synchronous propagation is fine too
        }
        assertFalse(ch.isOpen(), "malformed input must close the connection");
    }

    // ---------- happy paths ----------

    @Test
    void vanillaFramesPassThroughDualDecoder() {
        EmbeddedChannel ch = newChannel(cfg(128, 65536));
        ByteBuf vanilla = Unpooled.buffer();
        VarInts.writeVarInt(vanilla, 3);
        vanilla.writeBytes(new byte[]{1, 2, 3});
        assertTrue(ch.writeInbound(vanilla));
        // Vanilla frame decoders emit the packet BODY (length varint consumed).
        ByteBuf decoded = ch.readInbound();
        assertNotNull(decoded);
        assertEquals(3, decoded.readableBytes());
        decoded.release();
        assertFalse(dual(ch).isVeloZipMode());
    }

    @Test
    void roundTripSmallBatchIsRawFrame() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(128, 65536));
        ch.writeOutbound(packet("hello world"));
        assertTrue(drainOutbound(ch).isEmpty(), "batch must wait for the delay window");
        runTimers();

        List<ByteBuf> frames = drainOutbound(ch);
        assertEquals(1, frames.size());
        ByteBuf frame = frames.get(0);
        // RAW frame: magic(2) ver(1) flags(1) varint(len) payload — inspect without consuming
        assertEquals(VeloZip.MAGIC_0, frame.getByte(0));
        assertEquals(VeloZip.MAGIC_1, frame.getByte(1));
        assertEquals(VeloZip.TRANSPORT_PROTOCOL, frame.getUnsignedByte(2));
        assertEquals(VeloZip.FLAG_RAW, frame.getUnsignedByte(3));
        assertEquals(1, metrics.snapshot().tx().rawFrames());
        assertEquals(0, metrics.snapshot().compressionOps());

        // Feed the frame back through the decoder (ownership transfers).
        assertTrue(ch.writeInbound(frame));
        ByteBuf inner = ch.readInbound();
        assertNotNull(inner, "RAW frame must yield one inner Minecraft frame");
        assertEquals("hello world", inner.toString(StandardCharsets.UTF_8));
        inner.release();
    }

    @Test
    void roundTripLargeCompressibleBatch() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(0, 65536)); // rawThreshold 0 -> always try zstd
        List<String> originals = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            char fill = (char) ('a' + (i % 26));
            originals.add(String.valueOf(fill).repeat(2048));
            ch.writeOutbound(packet(originals.get(originals.size() - 1)));
        }
        runTimers();
        List<ByteBuf> frames = drainOutbound(ch);
        assertEquals(1, frames.size(), "single batch expected");
        assertEquals(VeloZip.FLAG_ZSTD, frames.get(0).getUnsignedByte(3),
                "large compressible batch must use zstd");

        for (ByteBuf frame : frames) {
            assertTrue(ch.writeInbound(frame));
        }
        for (String original : originals) {
            ByteBuf decoded = ch.readInbound();
            assertNotNull(decoded, "missing decoded packet");
            assertEquals(original, decoded.toString(StandardCharsets.UTF_8));
            decoded.release();
        }
        assertNull(ch.readInbound());
    }

    @Test
    void incompressibleBatchFallsBackToRaw() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(0, 65536));
        ch.writeOutbound(packet(16384, new Random(7))); // random data: zstd cannot shrink it
        runTimers();
        List<ByteBuf> frames = drainOutbound(ch);
        assertEquals(1, frames.size());
        assertEquals(VeloZip.FLAG_RAW, frames.get(0).getUnsignedByte(3),
                "incompressible batch must fall back to RAW");
        assertEquals(1, metrics.snapshot().compressionOps());
        assertEquals(1, metrics.snapshot().tx().rawFrames());
        assertEquals(0, metrics.snapshot().rx().totalFrames());
        assertFalse(Double.isNaN(metrics.snapshot().compressP50Us()));
        releaseFrames(frames);
    }

    @Test
    void sizeCapSplitsIntoMultipleFrames() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(0, 4096));
        Random random = new Random(11);
        for (int i = 0; i < 4; i++) {
            ch.writeOutbound(packet(2048, random));
        }
        // 2 KiB + 2 KiB crosses the 4 KiB cap: packets 1..3 each flush the previous
        // batch synchronously; packet 4 stays in the delay window.
        List<ByteBuf> frames = drainOutbound(ch);
        assertEquals(3, frames.size());
        releaseFrames(frames);
        runTimers();
        releaseFrames(drainOutbound(ch)); // flush the last batch
    }

    @Test
    void promisesCompleteWhenFrameWritten() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(128, 65536));
        List<ChannelPromise> promises = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ChannelPromise promise = ch.newPromise();
            ch.write(packet("pkt" + i), promise);
            promises.add(promise);
        }
        ch.flush();
        for (ChannelPromise p : promises) {
            assertFalse(p.isSuccess(), "promise must not complete before the frame hits the wire");
        }
        runTimers();
        for (ChannelPromise p : promises) {
            assertTrue(p.isSuccess(), "promise must complete with the frame write");
        }
        releaseFrames(drainOutbound(ch));
    }

    @Test
    void oversizeSinglePacketClosesConnection() {
        VeloZipConfig cfg = VeloZipConfig.builder()
                .maxFrameSize(64 * 1024).maxUncompressedSize(64 * 1024).build();
        EmbeddedChannel ch = newChannel(cfg);
        ChannelPromise promise = ch.newPromise();
        ch.write(packet(128 * 1024, new Random(3)), promise);
        assertFalse(ch.isOpen(), "oversized packet must close the connection");
        assertTrue(promise.isDone(), "oversized packet promise must be failed");
        assertFalse(promise.isSuccess());
    }

    @Test
    void coalescedVeloZipFramesParseCompletely() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(0, 8192));
        List<String> originals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            char fill = (char) ('A' + i);
            originals.add(String.valueOf(fill).repeat(1024));
            ch.writeOutbound(packet(originals.get(originals.size() - 1)));
        }
        runTimers();
        // Coalesce all pending frames into one buffer, like TCP coalescing would.
        ByteBuf coalesced = Unpooled.buffer();
        ByteBuf f;
        while ((f = ch.readOutbound()) != null) {
            coalesced.writeBytes(f);
            f.release();
        }
        assertTrue(ch.writeInbound(coalesced));
        for (String original : originals) {
            ByteBuf decoded = ch.readInbound();
            assertNotNull(decoded);
            assertEquals(original, decoded.toString(StandardCharsets.UTF_8));
            decoded.release();
        }
        assertNull(ch.readInbound());
    }

    @Test
    void emptyPacketIsSkipped() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(128, 65536));
        ChannelPromise promise = ch.newPromise();
        ch.write(Unpooled.EMPTY_BUFFER, promise);
        ch.flush();
        assertTrue(promise.isSuccess(), "empty packets are dropped, promise succeeds");
        runTimers();
        assertTrue(drainOutbound(ch).isEmpty(), "no frame for an empty-only batch");
    }

    @Test
    void metricsAccumulate() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(0, 65536));
        ch.writeOutbound(packetFill(8192, (byte) 'z'));
        runTimers();
        List<ByteBuf> frames = drainOutbound(ch);
        assertEquals(1, frames.size());
        assertEquals(1, metrics.snapshot().tx().zstdFrames());
        assertEquals(0, metrics.snapshot().rx().totalFrames());
        // Feed the frame through the decoder as well; directions stay independent.
        assertTrue(ch.writeInbound(frames.get(0)));
        ByteBuf decoded = ch.readInbound();
        assertNotNull(decoded);
        decoded.release();

        VeloZipMetrics.Snapshot snapshot = metrics.snapshot();
        // originalBytes counts the uncompressed wire format incl. the length varint
        // we add per packet, on BOTH sides of this in-process channel: the encoder
        // (V->B) and the decoder (B->V) each add their observation.
        assertEquals(2 * (8192 + VarInts.varIntLen(8192)), snapshot.originalBytes());
        assertEquals(1, snapshot.batches());
        assertEquals(2, snapshot.zstdFrames());
        assertEquals(1, snapshot.tx().zstdFrames());
        assertEquals(1, snapshot.rx().zstdFrames());
        assertEquals(8192 + VarInts.varIntLen(8192), snapshot.averageBatchBytes());
        assertTrue(snapshot.wireBytes() > 0);
        assertEquals(1, snapshot.decompressionOps());
    }

    @Test
    void dualModeSwitchIsPermanent() throws InterruptedException {
        EmbeddedChannel ch = newChannel(cfg(0, 65536));
        ByteBuf vanilla = Unpooled.buffer();
        VarInts.writeVarInt(vanilla, 4);
        vanilla.writeBytes(new byte[]{9, 9, 9, 9});
        assertTrue(ch.writeInbound(vanilla));
        ByteBuf decoded = ch.readInbound();
        assertNotNull(decoded);
        decoded.release();
        assertFalse(dual(ch).isVeloZipMode());

        ch.writeOutbound(packetFill(512, (byte) 'x'));
        runTimers();
        List<ByteBuf> frames = drainOutbound(ch);
        assertEquals(1, frames.size());
        assertTrue(ch.writeInbound(frames.get(0)));
        assertTrue(dual(ch).isVeloZipMode());
        ByteBuf inner = ch.readInbound();
        assertNotNull(inner);
        inner.release();
    }

    // ---------- malformed input handling ----------

    @Test
    void badMagicRejected() {
        EmbeddedChannel ch = newChannel(cfg(128, 65536));
        ByteBuf in = Unpooled.wrappedBuffer(new byte[]{0x00, 0x11, 0x01, 0x02, 0x05, 1, 2, 3, 4, 5});
        assertClosedAfter(ch, in);
    }

    @Test
    void badVersionRejected() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(9).writeByte(VeloZip.FLAG_RAW);
        VarInts.writeVarInt(frame, 0);
        assertClosedAfter(newChannel(cfg(128, 65536)), frame);
    }

    @Test
    void badFlagsRejected() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(0x77);
        VarInts.writeVarInt(frame, 0);
        assertClosedAfter(newChannel(cfg(128, 65536)), frame);
    }

    @Test
    void oversizedFrameLengthRejectedBeforeAllocation() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_RAW);
        VarInts.writeVarInt(frame, 100 * 1024 * 1024); // way over max-frame-size (1 MiB)
        assertClosedAfter(newChannel(cfg(128, 65536)), frame);
    }

    @Test
    void oversizedUncompressedLengthRejected() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_ZSTD);
        VarInts.writeVarInt(frame, 100);
        VarInts.writeVarInt(frame, 100 * 1024 * 1024); // over the 2 MiB cap
        assertClosedAfter(newChannel(cfg(128, 65536)), frame);
    }

    @Test
    void truncatedFrameWaitsForMoreData() {
        EmbeddedChannel ch = newChannel(cfg(128, 65536));
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_RAW);
        VarInts.writeVarInt(frame, 100);
        frame.writeBytes(new byte[10]); // only 10 of 100 payload bytes
        assertFalse(ch.writeInbound(frame));
        assertTrue(ch.isOpen(), "partial frame must not close the connection");

        // The rest arrives; payload is all zeroes -> zero-length inner frame -> corruption.
        ByteBuf rest = Unpooled.wrappedBuffer(new byte[90]);
        assertClosedAfter(ch, rest);
    }

    @Test
    void decompressionBombRejected() {
        ZstdCompressCtx ctx = new ZstdCompressCtx();
        ctx.setLevel(1);
        byte[] small = ctx.compress("bomb".getBytes(StandardCharsets.UTF_8));
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_ZSTD);
        VarInts.writeVarInt(frame, small.length);
        VarInts.writeVarInt(frame, 2 * 1024 * 1024); // <= cap, so it passes size validation
        frame.writeBytes(small);
        assertClosedAfter(newChannel(cfg(128, 65536)), frame);
    }

    @Test
    void garbageZstdPayloadRejected() {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(VeloZip.MAGIC_0).writeByte(VeloZip.MAGIC_1)
                .writeByte(VeloZip.TRANSPORT_PROTOCOL).writeByte(VeloZip.FLAG_ZSTD);
        byte[] garbage = new byte[64];
        new Random(1).nextBytes(garbage);
        VarInts.writeVarInt(frame, garbage.length);
        VarInts.writeVarInt(frame, 128);
        frame.writeBytes(garbage);
        assertClosedAfter(newChannel(cfg(128, 65536)), frame);
    }
}
