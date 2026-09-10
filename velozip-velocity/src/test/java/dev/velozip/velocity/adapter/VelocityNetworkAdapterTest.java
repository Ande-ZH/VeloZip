package dev.velozip.velocity.adapter;

import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.metrics.VeloZipMetrics;
import dev.velozip.common.logging.VeloZipLogger;
import dev.velozip.common.protocol.Negotiation;
import dev.velozip.velocity.network.VeloZipVelocityFrameDecoder;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.netty.MinecraftVarintFrameDecoder;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class VelocityNetworkAdapterTest {
    @Test void refusalKeepsInstalledDecoderInsteadOfReaddingNonSharableOriginal() {
        var cfg = VeloZipConfig.builder().build();
        var metrics = new VeloZipMetrics();
        var logger = new VeloZipLogger() {
            public void info(String message, Object... args) {}
            public void warn(String message, Object... args) {}
            public void debug(String message, Object... args) {}
            public void error(String message, Throwable error, Object... args) { fail(message, error); }
        };
        var adapter = new VelocityNetworkAdapter(cfg, metrics, logger, "test");
        var channel = new EmbeddedChannel();
        var original = new MinecraftVarintFrameDecoder(ProtocolUtils.Direction.CLIENTBOUND);
        var decoder = new VeloZipVelocityFrameDecoder(cfg, metrics, logger, () -> fail("unexpected activation"));
        try {
            channel.pipeline().addLast("frame-decoder", original);
            channel.pipeline().replace("frame-decoder", "frame-decoder", decoder);
            var state = new VelocityNetworkAdapter.NegotiationState(channel, "backend", null, original);
            var key = AttributeKey.<VelocityNetworkAdapter.NegotiationState>valueOf("velozip.negotiation");
            channel.attr(key).set(state);
            adapter.onRefuse(channel, new Negotiation.Refuse(4, "authentication refused"));
            assertTrue(channel.isOpen());
            assertTrue(state.finished.get());
            assertNull(channel.attr(key).get());
            assertSame(decoder, channel.pipeline().get("frame-decoder"));
            assertDoesNotThrow(decoder::fallbackToVanilla);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void cooldownRetryAndImmutableSnapshots() throws Exception {
        var adapter = new VelocityNetworkAdapter(VeloZipConfig.builder().build(), new VeloZipMetrics(), null, "test");
        Method failed = VelocityNetworkAdapter.class.getDeclaredMethod("failed", String.class, String.class);
        failed.setAccessible(true);
        failed.invoke(adapter, "backend", "timeout");
        var before = adapter.statuses();
        assertEquals("timeout", adapter.knownFailure("backend"));
        assertThrows(UnsupportedOperationException.class, () -> before.clear());
        assertTrue(adapter.retry("backend"));
        assertNull(adapter.knownFailure("backend"));
        assertEquals("FAILED", before.get("backend").state());
        assertFalse(adapter.retry("missing"));
        assertDoesNotThrow(() -> adapter.onRefuse(null, null));
        var field = VelocityNetworkAdapter.class.getDeclaredField("serverStatuses");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var statuses = (java.util.Map<String, VelocityNetworkAdapter.ServerStatus>) field.get(adapter);
        statuses.put("expired", new VelocityNetworkAdapter.ServerStatus("FAILED", "timeout", System.nanoTime() - 1));
        assertNull(adapter.knownFailure("expired"));
        assertFalse(adapter.statuses().containsKey("expired"));
        statuses.put("active", new VelocityNetworkAdapter.ServerStatus("ACTIVE", "", 0));
        assertFalse(adapter.retry("active"));
    }
}
