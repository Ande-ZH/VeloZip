package dev.velozip.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a generic YAML tree (as produced by snakeyaml on either platform) onto
 * {@link VeloZipConfig}. Unknown keys are ignored so new versions stay
 * forward-compatible; known keys with wrong types/values fail with a clear
 * message (prompt §16/§17: never silently accept).
 */
public final class YamlConfigs {

    private YamlConfigs() {
    }

    public static VeloZipConfig parse(Map<String, Object> root) {
        VeloZipConfig.Builder b = VeloZipConfig.builder();
        if (root == null) {
            return b.build();
        }
        b.enabled(bool(root, "enabled", true));
        Map<String, Object> compression = section(root, "compression");
        if (compression != null) {
            b.algorithm = string(compression, "algorithm", "zstd");
            b.level = (int) number(compression, "level", 1);
            b.rawThreshold = (int) number(compression, "raw-threshold", 128);
        }
        Map<String, Object> batch = section(root, "batch");
        if (batch != null) {
            b.batchMaxSize = (int) number(batch, "max-size", 65536);
            b.batchMaxDelayMicros = (int) number(batch, "max-delay-micros", 500);
        }
        Map<String, Object> limits = section(root, "limits");
        if (limits != null) {
            b.maxFrameSize = (int) number(limits, "max-frame-size", 1024 * 1024);
            b.maxUncompressedSize = (int) number(limits, "max-uncompressed-size", 2 * 1024 * 1024);
        }
        Map<String, Object> auth = section(root, "authentication");
        if (auth != null) {
            b.secret = string(auth, "secret", "");
        }
        b.requireVelozip = bool(root, "require-velozip", false);
        b.debug = bool(root, "debug", false);
        Map<String, Object> servers = section(root, "servers");
        if (servers != null) {
            Map<String, Boolean> perServer = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : servers.entrySet()) {
                if (e.getValue() instanceof Boolean bool) {
                    perServer.put(e.getKey(), bool);
                }
            }
            b.servers = perServer;
        }
        return b.build(); // validates
    }

    /** Writes the bundled default config if the target does not exist yet. */
    public static void writeDefaultIfMissing(Path target, Class<?> resourceBase) throws IOException {
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = resourceBase.getClassLoader().getResourceAsStream("velozip-default.yml")) {
            if (in == null) {
                throw new IOException("velozip-default.yml not found on classpath");
            }
            Files.copy(in, target);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return def;
        }
        throw new IllegalArgumentException(key + ": expected true/false, got " + value);
    }

    private static String string(Map<String, Object> map, String key, String def) {
        Object value = map.get(key);
        return value == null ? def : String.valueOf(value);
    }

    private static double number(Map<String, Object> map, String key, double def) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return def;
        }
        throw new IllegalArgumentException(key + ": expected a number, got " + value);
    }

    @SuppressWarnings("unused")
    private static List<Object> list(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        return List.of();
    }
}
