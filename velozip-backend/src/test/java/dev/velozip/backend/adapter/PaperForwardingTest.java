package dev.velozip.backend.adapter;

import com.destroystokyo.paper.PaperConfig;
import io.papermc.paper.configuration.GlobalConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperForwardingTest {
    private final ClassLoader loader = getClass().getClassLoader();

    @Test void readsLiveModernValueWithoutFallingBackToLegacy() throws Exception {
        PaperConfig.velocitySupport = true;
        GlobalConfiguration.INSTANCE.proxies.velocity.enabled = false;
        assertFalse(PaperForwarding.enabled(loader));
        GlobalConfiguration.INSTANCE.proxies.velocity.enabled = true;
        assertTrue(PaperForwarding.enabled(loader));
    }

    @Test void readsLegacyOnlyWhenModernConfigurationClassIsAbsent() throws Exception {
        ClassLoader oldServer = new ClassLoader(loader) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(GlobalConfiguration.class.getName())) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        PaperConfig.velocitySupport = true;
        assertTrue(PaperForwarding.enabled(oldServer));
        PaperConfig.velocitySupport = false;
        assertFalse(PaperForwarding.enabled(oldServer));
    }

    @Test void brokenModernConfigurationCannotFallBackToLegacyEnabledFlag() {
        PaperConfig.velocitySupport = true;
        var previous = GlobalConfiguration.INSTANCE.proxies;
        GlobalConfiguration.INSTANCE.proxies = null;
        try { assertThrows(NullPointerException.class, () -> PaperForwarding.enabled(loader)); }
        finally { GlobalConfiguration.INSTANCE.proxies = previous; }
    }
}
