package dev.velozip.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BackendConfigLoaderTest {
    @TempDir Path temp;

    @Test void isolatedYamlRejectsDuplicatesAndUnsafeTagsWithoutLeakingSecrets() throws Exception {
        Path config = temp.resolve("config.yml");
        for (String content : new String[]{"enabled: true\nenabled: false\n", "!!java.net.URL ['private-secret']",
                "authentication:\n  secret: [private-secret\n"}) {
            Files.writeString(config, content);
            Exception error = assertThrows(Exception.class, () -> BackendConfigLoader.load(config));
            assertFalse(error.toString().contains("private-secret"));
            assertNull(error.getCause());
        }
    }

    @Test void createsDefaultsAndReadsExistingSettings() throws Exception {
        Path config = temp.resolve("config.yml");
        assertTrue(BackendConfigLoader.load(config).enabled);
        Files.writeString(config, "enabled: false\nauthentication:\n  secret: testing\n");
        var loaded = BackendConfigLoader.load(config);
        assertFalse(loaded.enabled);
        assertEquals("testing", loaded.secret);
    }
}
