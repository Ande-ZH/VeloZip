package io.papermc.paper.configuration;

/**
 * STUB of paper-server's io.papermc.paper.configuration.GlobalConfiguration.
 * Compile-time only; never packaged. Used to read paper-global.yml
 * {@code proxies.velocity.enabled} — when modern forwarding is enforced, a
 * direct (non-proxy) connection cannot complete login, which is what makes the
 * VeloZip REQ trustworthy.
 */
public final class GlobalConfiguration {

    public static GlobalConfiguration get() {
        throw new UnsupportedOperationException("stub");
    }

    public Proxies proxies = new Proxies();

    public static final class Proxies {
        public Velocity velocity = new Velocity();

        public static final class Velocity {
            public boolean enabled = false;
            public String secret = "";
        }
    }
}
