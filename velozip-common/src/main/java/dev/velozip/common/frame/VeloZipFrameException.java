package dev.velozip.common.frame;

/**
 * Thrown when a VeloZip frame violates the protocol (bad magic/flags/length,
 * oversized frame, corrupted or short zstd data). Per the fail-safe rules this
 * must only ever affect the offending connection: callers log and close it.
 */
public class VeloZipFrameException extends RuntimeException {

    public VeloZipFrameException(String message) {
        super(message);
    }

    public VeloZipFrameException(String message, Throwable cause) {
        super(message, cause);
    }

    public static VeloZipFrameException closed() {
        return new VeloZipFrameException("VeloZip transport already closed");
    }
}
