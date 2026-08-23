package dev.velozip.common.config;

import java.util.Map;

/**
 * Immutable VeloZip configuration shared by both platforms. Platform modules
 * parse their own YAML into {@link Builder} and {@link #validate()} it here so
 * the rules live exactly once.
 *
 * <p>Phase 1 accepts exactly algorithm=zstd and level=1; anything else is a
 * configuration error (prompt §16/§17: never silently accept wrong values).
 */
public final class VeloZipConfig {

    public final boolean enabled;
    public final String algorithm;
    public final int level;
    public final int rawThreshold;
    public final int batchMaxSize;
    public final int batchMaxDelayMicros;
    public final int maxFrameSize;
    public final int maxUncompressedSize;
    public final String secret; // empty string = authentication disabled
    public final boolean requireVelozip;
    public final boolean debug;
    /** Velocity side only: server name -> enabled (absent = default enabled). */
    public final Map<String, Boolean> servers;

    private VeloZipConfig(Builder b) {
        this.enabled = b.enabled;
        this.algorithm = b.algorithm;
        this.level = b.level;
        this.rawThreshold = b.rawThreshold;
        this.batchMaxSize = b.batchMaxSize;
        this.batchMaxDelayMicros = b.batchMaxDelayMicros;
        this.maxFrameSize = b.maxFrameSize;
        this.maxUncompressedSize = b.maxUncompressedSize;
        this.secret = b.secret == null ? "" : b.secret;
        this.requireVelozip = b.requireVelozip;
        this.debug = b.debug;
        this.servers = Map.copyOf(b.servers);
    }

    /** Velocity-side per-server gate; backend side ignores {@code servers}. */
    public boolean enabledForServer(String serverName) {
        return servers.getOrDefault(serverName, Boolean.TRUE);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @throws IllegalArgumentException with a user-facing message on invalid config. */
    public static VeloZipConfig validateAndBuild(Builder b) {
        if (!"zstd".equals(b.algorithm)) {
            throw new IllegalArgumentException(
                    "compression.algorithm: phase 1 only supports 'zstd', got '" + b.algorithm + "'");
        }
        if (b.level != 1) {
            throw new IllegalArgumentException(
                    "compression.level: phase 1 only supports 1, got " + b.level);
        }
        if (b.rawThreshold < 0) {
            throw new IllegalArgumentException("compression.raw-threshold must be >= 0");
        }
        if (b.batchMaxSize < 256) {
            throw new IllegalArgumentException("batch.max-size must be >= 256 bytes");
        }
        if (b.batchMaxDelayMicros <= 0 || b.batchMaxDelayMicros > 10_000) {
            throw new IllegalArgumentException("batch.max-delay-micros must be in (0, 10000]");
        }
        if (b.maxFrameSize < b.batchMaxSize) {
            throw new IllegalArgumentException("limits.max-frame-size must be >= batch.max-size");
        }
        if (b.maxUncompressedSize < b.maxFrameSize) {
            throw new IllegalArgumentException(
                    "limits.max-uncompressed-size must be >= limits.max-frame-size");
        }
        if (b.batchMaxSize > b.maxUncompressedSize) {
            throw new IllegalArgumentException("batch.max-size must be <= limits.max-uncompressed-size");
        }
        return new VeloZipConfig(b);
    }

    public static final class Builder {
        public boolean enabled = true;
        public String algorithm = "zstd";
        public int level = 1;
        public int rawThreshold = 128;
        public int batchMaxSize = 65536;
        public int batchMaxDelayMicros = 500;
        public int maxFrameSize = 1024 * 1024;
        public int maxUncompressedSize = 2 * 1024 * 1024;
        public String secret = "";
        public boolean requireVelozip = false;
        public boolean debug = false;
        public Map<String, Boolean> servers = Map.of();

        private Builder() {
        }

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder rawThreshold(int v) {
            this.rawThreshold = v;
            return this;
        }

        public Builder batchMaxSize(int v) {
            this.batchMaxSize = v;
            return this;
        }

        public Builder batchMaxDelayMicros(int v) {
            this.batchMaxDelayMicros = v;
            return this;
        }

        public Builder maxFrameSize(int v) {
            this.maxFrameSize = v;
            return this;
        }

        public Builder maxUncompressedSize(int v) {
            this.maxUncompressedSize = v;
            return this;
        }

        public Builder secret(String v) {
            this.secret = v;
            return this;
        }

        public Builder requireVelozip(boolean v) {
            this.requireVelozip = v;
            return this;
        }

        public Builder debug(boolean v) {
            this.debug = v;
            return this;
        }

        public Builder servers(Map<String, Boolean> v) {
            this.servers = v;
            return this;
        }

        public VeloZipConfig build() {
            return validateAndBuild(this);
        }
    }
}
