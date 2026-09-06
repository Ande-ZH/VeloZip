package dev.velozip.velocity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class VelocityConfigLoaderTest {
    @TempDir Path temp;

    @Test void rejectsDuplicateKeysRootScalarsAndUnsafeTagsWithoutSecretCauses() throws Exception {
        Path config = temp.resolve("config.yml");
        for (String text : new String[]{"enabled: true\nenabled: false\n", "private-secret", "authentication:\n  secret: [private-secret\n", "!!java.net.URL ['private-secret']"}) {
            Files.writeString(config, text);
            var error = assertThrows(Exception.class, () -> VelocityConfigLoader.load(config));
            assertFalse(error.toString().contains("private-secret"));
            assertNull(error.getCause());
        }
    }

    @Test void honorsNestedAndShorthandAndRejectsDecimal() throws Exception {
        Path config = temp.resolve("config.yml");
        Files.writeString(config, "servers:\n  nested:\n    enabled: false\n  short: false\n");
        var parsed = VelocityConfigLoader.load(config);
        assertFalse(parsed.enabledForServer("nested"));
        assertFalse(parsed.enabledForServer("short"));
        Files.writeString(config, "compression:\n  level: 1.5\n");
        assertThrows(IllegalArgumentException.class, () -> VelocityConfigLoader.load(config));
    }
}
