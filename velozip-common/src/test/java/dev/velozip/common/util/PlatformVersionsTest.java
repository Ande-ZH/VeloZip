package dev.velozip.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlatformVersionsTest {

    @Test
    void parsesPlainVersions() {
        assertEquals("3.4", PlatformVersions.majorMinor("3.4.0"));
        assertEquals("4.1", PlatformVersions.majorMinor("4.1.1"));
        assertEquals("26.1", PlatformVersions.majorMinor("26.1.2"));
        assertEquals("26.2", PlatformVersions.majorMinor("26.2"));
    }

    @Test
    void stripsBuildSuffixes() {
        assertEquals("4.1", PlatformVersions.majorMinor("4.1.1-SNAPSHOT"));
        assertEquals("26.1", PlatformVersions.majorMinor("26.1.2-R0.1-SNAPSHOT"));
        assertEquals("3.5", PlatformVersions.majorMinor("3.5.1+build"));
    }

    @Test
    void rejectsNonVersionStrings() {
        assertNull(PlatformVersions.majorMinor(null));
        assertNull(PlatformVersions.majorMinor("unknown"));
        assertNull(PlatformVersions.majorMinor("3"));
        assertNull(PlatformVersions.majorMinor("3."));
        assertNull(PlatformVersions.majorMinor(""));
        assertNull(PlatformVersions.majorMinor("SNAPSHOT-4.1.1"));
    }
}