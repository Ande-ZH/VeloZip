package dev.velozip.common;

import dev.velozip.common.config.YamlConfigs;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class YamlConfigsTest {
    @Test void nestedAndShorthandServers() {
        var cfg = YamlConfigs.parse(Map.of("servers", Map.of(
                "nested", Map.of("enabled", false), "short", false, "default", Map.of())));
        assertFalse(cfg.enabledForServer("nested"));
        assertFalse(cfg.enabledForServer("short"));
        assertTrue(cfg.enabledForServer("default"));
        assertTrue(cfg.enabledForServer("absent"));
    }

    @Test void rejectsWrongTypesWithoutDisclosingValues() {
        String secret = "do-not-disclose";
        for (var root : java.util.List.<Map<String, Object>>of(
                Map.of("enabled", secret), Map.of("compression", secret),
                Map.of("compression", Map.of("algorithm", secret)),
                Map.of("authentication", Map.of("secret", java.util.List.of(secret))),
                Map.of("servers", Map.of("test", secret)),
                Map.of("servers", Map.of("test", Map.of("enabled", secret))))) {
            var error = assertThrows(IllegalArgumentException.class, () -> YamlConfigs.parse(root));
            assertFalse(error.getMessage().contains(secret));
        }
    }

    @Test void rejectsFractionalOverflowAndExplicitNull() {
        for (Object value : java.util.List.of(1.5, 1.0, Long.MAX_VALUE, Double.NaN, "1")) {
            assertThrows(IllegalArgumentException.class,
                    () -> YamlConfigs.parse(Map.of("compression", Map.of("level", value))));
        }
        var root = new HashMap<String, Object>();
        root.put("enabled", null);
        assertThrows(IllegalArgumentException.class, () -> YamlConfigs.parse(root));
        assertEquals(1, YamlConfigs.parse(Map.of()).level);
        assertThrows(IllegalArgumentException.class, () -> YamlConfigs.parse(Map.of("typo", true)));
        assertThrows(IllegalArgumentException.class, () -> YamlConfigs.parse(Map.of("servers", Map.of("x", Map.of("enabld", false)))));
        var secretConfig = dev.velozip.common.config.VeloZipConfig.builder().secret("private-value").build();
        assertFalse(secretConfig.diagnostics().contains("private-value"));
    }
}
