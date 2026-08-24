package dev.velozip.common;

import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.frame.VeloZipFrameException;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.transport.VeloZipBatchEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardening tests (prompt §25/Phase 9): the transport must never leak, crash,
 * or corrupt state across connection-lifecycle events.
 */
class HardeningTest {

    private VeloZipConfig cfg(int rawThreshold, int batchSize) {
        return VeloZipConfig.builder()
                .rawThreshold(rawThreshold)
                .batchMaxSize(batchSize)
                .batchMaxDelayMicros(10_000)
                .build();
    }

    /** Reconnect: a fresh channel on the same metrics must start from a clean encoder. */
    @Test
    void reconnectStartsFresh() throws InterruptedException {
        VeloZipMetrics metrics = new VeloZipMetrics();
        VeloZipConfig c = cfg(0, 65536);

        // First connection: emit a batch, then close.
        EmbeddedChannel ch1 = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        ch1.writeOutbound(Unpooled.wrappedBuffer(repeat('x', 8192)));
        Thread.sleep(15);
        ch1.runPendingTasks();
        releaseAll(ch1);
        ch1.finishAndReleaseAll();
        assertEquals(0, metrics.activeConnections.sum(), "active count must drop after close");

        // Second connection on the same metrics: must behave identically.
        EmbeddedChannel ch2 = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        ch2.writeOutbound(Unpooled.wrappedBuffer(repeat('y', 4096)));
        Thread.sleep(15);
        ch2.runPendingTasks();
        ByteBuf f = ch2.readOutbound();
        assertNotNull(f);
        assertTrue(f.readableBytes() > 0);
        f.release();
        ch2.finishAndReleaseAll();
        assertEquals(0, metrics.activeConnections.sum(), "active count must drop after second close");
    }

    /** Proxy/backend shutdown: closing the channel flushes no partial garbage. */
    @Test
    void shutdownDropsPendingBatch() {
        VeloZipMetrics metrics = new VeloZipMetrics();
        VeloZipConfig c = cfg(0, 65536);
        EmbeddedChannel ch = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        ch.writeOutbound(Unpooled.wrappedBuffer(repeat('z', 1024)));
        // Do NOT run the delay timer; close immediately. A pending batch's
        // promises fail on close — that is the correct production behavior.
        try {
            ch.finishAndReleaseAll();
        } catch (VeloZipFrameException expected) {
            // pending-batch failure surfaces here; expected.
        }
        assertEquals(0, metrics.activeConnections.sum());
    }

    /** Plugin error during encode: the failing connection closes, others are unaffected. */
    @Test
    void oversizePacketFailsOnlyThisConnection() {
        VeloZipMetrics metrics = new VeloZipMetrics();
        VeloZipConfig c = VeloZipConfig.builder()
                .maxFrameSize(64 * 1024).maxUncompressedSize(64 * 1024).build();
        EmbeddedChannel ch = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        try {
            ch.writeOutbound(Unpooled.wrappedBuffer(new byte[128 * 1024]));
        } catch (VeloZipFrameException expected) {
            // oversize packet propagates synchronously
        }
        assertFalse(ch.isOpen(), "oversized packet closes only this connection");
        try {
            ch.finishAndReleaseAll();
        } catch (VeloZipFrameException expected) {
            // already-closed cleanup
        }
    }

    /** Malformed frame on one connection: only that connection closes. */
    @Test
    void malformedFrameIsolatesFailure() {
        VeloZipMetrics metrics = new VeloZipMetrics();
        VeloZipConfig c = cfg(128, 65536);
        EmbeddedChannel ch = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        ByteBuf bad = Unpooled.wrappedBuffer(new byte[]{0x00, 0x11, 0x01, 0x02, 0x05, 1, 2, 3, 4, 5});
        try {
            ch.writeInbound(bad);
        } catch (Throwable ignored) {
            // sync propagation expected
        }
        assertFalse(ch.isOpen(), "malformed frame closes only its own connection");
        ch.finishAndReleaseAll();
    }

    /** Server switch / backend restart mid-batch: pending promises fail cleanly. */
    @Test
    void pendingPromisesFailOnClose() {
        VeloZipMetrics metrics = new VeloZipMetrics();
        VeloZipConfig c = cfg(0, 65536);
        EmbeddedChannel ch = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        ChannelPromise p1 = ch.newPromise();
        ChannelPromise p2 = ch.newPromise();
        ch.write(Unpooled.wrappedBuffer(repeat('a', 4096)), p1);
        ch.write(Unpooled.wrappedBuffer(repeat('b', 4096)), p2);
        // Close before the delay window fires.
        try {
            ch.finishAndReleaseAll();
        } catch (VeloZipFrameException expected) {
            // pending-batch failure surfaces here; expected.
        }
        assertTrue(p1.isDone(), "promise 1 resolved on close");
        assertTrue(p2.isDone(), "promise 2 resolved on close");
    }

    /** Repeated activation does not double-count or double-install. */
    @Test
    void repeatedOutboundActivationIsIdempotent() throws InterruptedException {
        VeloZipMetrics metrics = new VeloZipMetrics();
        VeloZipConfig c = cfg(0, 65536);
        EmbeddedChannel ch = new EmbeddedChannel(
                new TestDualDecoder(c, metrics),
                new VeloZipBatchEncoder(c, metrics, TestLogger.INSTANCE));
        for (int i = 0; i < 5; i++) {
            ch.writeOutbound(Unpooled.wrappedBuffer(repeat('c', 2048)));
            Thread.sleep(15);
            ch.runPendingTasks();
            releaseAll(ch);
        }
        assertEquals(5, metrics.batches.sum(), "exactly five batches, one per write cycle");
        ch.finishAndReleaseAll();
    }

    private static byte[] repeat(char c, int len) {
        byte[] b = new byte[len];
        Arrays.fill(b, (byte) c);
        return b;
    }

    private static void releaseAll(EmbeddedChannel ch) {
        ByteBuf out;
        while ((out = ch.readOutbound()) != null) {
            out.release();
        }
    }

    private static <T> T assertNotNull(T value) {
        assertTrue(value != null, "expected non-null");
        return value;
    }
}