package dev.velozip.common;

/**
 * Netty user events fired on VeloZip channels. The Velocity-side outbound
 * switch keys off {@link #TRANSPORT_ACTIVE}, which the dual-mode decoder fires
 * the moment it sees the backend's first VeloZip frame (the "magic ACK").
 */
public enum VeloZipEvent {
    TRANSPORT_ACTIVE
}
