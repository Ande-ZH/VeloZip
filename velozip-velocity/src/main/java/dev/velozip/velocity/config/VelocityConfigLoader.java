package dev.velozip.velocity.config;

import dev.velozip.common.config.VeloZipConfig;
import dev.velozip.common.config.YamlConfigs;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Loads plugins/velozip-velocity/config.yml (snakeyaml is shaded into the jar). */
public final class VelocityConfigLoader {

    private VelocityConfigLoader() {
    }

    public static VeloZipConfig load(Path file) throws IOException {
        YamlConfigs.writeDefaultIfMissing(file, VelocityConfigLoader.class);
        try (InputStream in = Files.newInputStream(file)) {
            Object parsed = new Yaml().load(in);
            Map<String, Object> root = parsed instanceof Map<?, ?> map
                    ? uncheckedCast(map)
                    : Map.of();
            return YamlConfigs.parse(root);
        } catch (YAMLException e) {
            throw new IOException("invalid YAML in " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedCast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
