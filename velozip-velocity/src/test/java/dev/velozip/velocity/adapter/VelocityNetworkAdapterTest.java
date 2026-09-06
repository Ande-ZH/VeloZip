package dev.velozip.velocity.adapter;

import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.metrics.VeloZipMetrics;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class VelocityNetworkAdapterTest {
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
