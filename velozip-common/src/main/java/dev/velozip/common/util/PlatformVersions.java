package dev.velozip.common.util;

/**
 * Platform version-string parsing for the runtime platform check. Proxy and
 * server version strings are messy ("3.4.0", "4.1.1-SNAPSHOT",
 * "26.1.2-R0.1-SNAPSHOT"); adapters compare the major.minor family against
 * the families whose network layer has been source-verified.
 */
public final class PlatformVersions {

    private PlatformVersions() {
    }

    /**
     * Extracts the "major.minor" family from a platform version string.
     *
     * @return e.g. "3.4" for "3.4.0" or "4.1" for "4.1.1-SNAPSHOT" or
     *         "26.1" for "26.1.2-R0.1-SNAPSHOT"; {@code null} when the string
     *         does not start with at least "major.minor" numeric tokens
     */
    public static String majorMinor(String version) {
        if (version == null) {
            return null;
        }
        int digitsEnd = -1;   // end of a run of digits (exclusive), seen so far
        int secondDigitsEnd = -1; // end of the minor digits run, once a dot was seen
        boolean sawDot = false;
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c >= '0' && c <= '9') {
                if (sawDot) {
                    secondDigitsEnd = i + 1;
                } else {
                    digitsEnd = i + 1;
                }
            } else if (c == '.' && !sawDot && digitsEnd > 0) {
                sawDot = true;
            } else {
                break;
            }
        }
        if (!sawDot || secondDigitsEnd <= 0) {
            return null; // no "major.minor" pair: "3", "unknown", "", "SNAPSHOT-4.1.1"
        }
        return version.substring(0, secondDigitsEnd);
    }
}