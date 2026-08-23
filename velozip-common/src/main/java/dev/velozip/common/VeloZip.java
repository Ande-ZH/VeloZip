package dev.velozip.common;

/**
 * Protocol-wide constants. The transport protocol version is independent of the
 * plugin version: two plugins may talk as long as transport protocol versions
 * match and the negotiated parameters are compatible.
 */
public final class VeloZip {

    /** Transport protocol version implemented by this build. */
    public static final int TRANSPORT_PROTOCOL = 1;

    /** Fixed compression algorithm for protocol 1: Zstandard. */
    public static final String ALGORITHM_ZSTD = "zstd";

    /** Fixed compression level for protocol 1. */
    public static final int COMPRESSION_LEVEL = 1;

    /** Plugin message channel used for the negotiation handshake. */
    public static final String CHANNEL_NAMESPACE = "velozip";
    public static final String CHANNEL_NAME = "negotiate";
    public static final String CHANNEL_FULL = CHANNEL_NAMESPACE + ":" + CHANNEL_NAME;

    /**
     * Frame magic. A vanilla Minecraft stream can never begin a frame with 0x00
     * (a single 0x00 varint encodes length 0, which is not a legal frame), so a
     * 0x00 0x5A prefix at a frame boundary is an unambiguous transport marker.
     * See docs/ANALYSIS.md §2.4.
     */
    public static final byte MAGIC_0 = 0x00;
    public static final byte MAGIC_1 = 0x5A;

    public static final byte FLAG_ZSTD = 0x01;
    public static final byte FLAG_RAW = 0x02;

    /** Seconds VeloZip-Velocity waits for the first frame (or REFUSE) before falling back. */
    public static final int NEGOTIATION_TIMEOUT_SECONDS = 5;

    private VeloZip() {
    }
}
