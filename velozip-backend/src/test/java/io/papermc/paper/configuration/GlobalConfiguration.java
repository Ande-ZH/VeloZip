package io.papermc.paper.configuration;
/** Test fixture, never included in production artifacts. */
public final class GlobalConfiguration {
    public static final GlobalConfiguration INSTANCE = new GlobalConfiguration();
    public Proxies proxies = new Proxies();
    public static GlobalConfiguration get() { return INSTANCE; }
    public static final class Proxies { public Velocity velocity = new Velocity(); }
    public static final class Velocity { public boolean enabled; }
}
