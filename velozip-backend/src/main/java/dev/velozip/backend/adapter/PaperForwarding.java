package dev.velozip.backend.adapter;

/** Reads the live forwarding configuration, never a possibly stale YAML file. */
public final class PaperForwarding {
    private PaperForwarding() {}

    public static boolean enabled(ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> config;
        try {
            config = Class.forName("io.papermc.paper.configuration.GlobalConfiguration", true, loader);
        } catch (ClassNotFoundException legacy) {
            // Paper 1.18: source evidence in docs/COMPATIBILITY-1.0.0.md.
            Class<?> paperConfig = Class.forName("com.destroystokyo.paper.PaperConfig", true, loader);
            return paperConfig.getField("velocitySupport").getBoolean(null);
        }
        // Only absence permits fallback: a broken/disabled modern config must
        // never fall back to a legacy enabled flag.
        Object global = config.getMethod("get").invoke(null);
        Object proxies = global.getClass().getField("proxies").get(global);
        Object velocity = proxies.getClass().getField("velocity").get(proxies);
        return velocity.getClass().getField("enabled").getBoolean(velocity);
    }
}
