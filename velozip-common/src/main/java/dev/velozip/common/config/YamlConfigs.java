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
 * {@link VeloZipConfig}. Unknown keys and wrong types/values fail with a clear
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
        validateKeys(root, java.util.Set.of("enabled", "compression", "batch", "limits", "authentication", "require-velozip", "debug", "servers"));
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
                } else if (e.getValue() instanceof Map<?, ?>) {
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    validateKeys(entry, java.util.Set.of("enabled"));
                    perServer.put(e.getKey(), bool(entry, "enabled", true));
                } else {
                    throw new IllegalArgumentException("servers entry: expected true/false or mapping with enabled");
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
            java.util.Set<String> allowed = switch (key) {
                case "compression" -> java.util.Set.of("algorithm", "level", "raw-threshold");
                case "batch" -> java.util.Set.of("max-size", "max-delay-micros");
                case "limits" -> java.util.Set.of("max-frame-size", "max-uncompressed-size");
                case "authentication" -> java.util.Set.of("secret");
                default -> null;
            };
            for (Object k : map.keySet()) if (!(k instanceof String))
                throw new IllegalArgumentException("configuration keys must be strings");
            if (allowed != null) validateKeys(map, allowed);
            return (Map<String, Object>) map;
        }
        if (!root.containsKey(key)) return null;
        throw new IllegalArgumentException("configuration section: expected a mapping");
    }

    private static void validateKeys(Map<?, ?> map, java.util.Set<String> allowed) {
        for (Object key : map.keySet()) if (!(key instanceof String) || !allowed.contains(key))
            throw new IllegalArgumentException("unknown configuration key (value suppressed)");
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (!map.containsKey(key)) return def;
        throw new IllegalArgumentException(key + ": expected true/false");
    }

    private static String string(Map<String, Object> map, String key, String def) {
        Object value = map.get(key);
        if (!map.containsKey(key)) return def;
        if (value instanceof String s) return s;
        throw new IllegalArgumentException(key + ": expected a string");
    }

    private static int number(Map<String, Object> map, String key, int def) {
        if (!map.containsKey(key)) return def;
        Object value = map.get(key);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            try {
                return new java.math.BigInteger(value.toString()).intValueExact();
            } catch (ArithmeticException ignored) {
                // Never truncate or disclose the supplied value.
            }
        }
        throw new IllegalArgumentException(key + ": expected a 32-bit integer");
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
