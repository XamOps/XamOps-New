package com.xammer.cloud.util;

import java.util.regex.Pattern;

/**
 * Validates user-supplied values before they are interpolated into shell commands
 * that are executed on customer instances (AWS SSM {@code SendCommand}, GCP/Azure
 * run-command equivalents).
 *
 * <p>These commands run as root. Any value that reaches them must be constrained to
 * a strict allowlist rather than sanitised by escaping, because escaping rules differ
 * between the shell, {@code sed} expressions and {@code /etc/fstab} entries that the
 * same value is embedded into.
 *
 * <p>All methods throw {@link IllegalArgumentException} on rejection so that callers
 * surface a 400-class error instead of executing a partially-trusted command.
 */
public final class RemoteCommandInputValidator {

    /**
     * Absolute POSIX mount path. Permits alphanumerics, dash, underscore and dot as
     * path segment characters. Deliberately excludes shell metacharacters
     * ({@code ; | & $ ` ( ) < > newline}), whitespace, single and double quotes,
     * and backslashes.
     */
    private static final Pattern MOUNT_POINT = Pattern.compile("^/(?:[A-Za-z0-9._-]+/?)*$");

    /** Linux block device path, e.g. {@code /dev/xvdf} or {@code /dev/nvme1n1p1}. */
    private static final Pattern DEVICE_PATH = Pattern.compile("^/dev/[A-Za-z0-9/_-]{1,60}$");

    /** EC2 instance id, e.g. {@code i-0123456789abcdef0}. */
    private static final Pattern INSTANCE_ID = Pattern.compile("^i-[0-9a-f]{8,32}$");

    /** EBS volume id, e.g. {@code vol-0123456789abcdef0}. */
    private static final Pattern VOLUME_ID = Pattern.compile("^vol-[0-9a-f]{8,32}$");

    /** AWS region id, e.g. {@code ap-south-1}. */
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(?:-[a-z]+)+-\\d$");

    private static final int MAX_PATH_LENGTH = 255;

    private RemoteCommandInputValidator() {
    }

    /**
     * Validates a filesystem mount point that will be interpolated into a remote shell
     * command. Rejects relative paths, path traversal, the root filesystem itself, and
     * anything containing shell metacharacters.
     *
     * @param mountPoint caller-supplied mount path
     * @return the mount point with any single trailing slash removed
     * @throws IllegalArgumentException if the value is not a safe absolute path
     */
    public static String requireSafeMountPoint(String mountPoint) {
        if (mountPoint == null || mountPoint.isBlank()) {
            throw new IllegalArgumentException("mountPoint is required");
        }
        String trimmed = mountPoint.trim();
        if (trimmed.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("mountPoint exceeds " + MAX_PATH_LENGTH + " characters");
        }
        if (!MOUNT_POINT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "mountPoint must be an absolute path containing only letters, digits, '.', '_', '-' and '/': "
                            + trimmed);
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("mountPoint must not contain path traversal ('..'): " + trimmed);
        }
        // Strip a single trailing slash so callers can append "/" unambiguously.
        String normalised = trimmed.length() > 1 && trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1)
                : trimmed;
        if ("/".equals(normalised)) {
            throw new IllegalArgumentException(
                    "Refusing to operate on the root filesystem '/'. Specify a data-volume mount point.");
        }
        return normalised;
    }

    /**
     * Validates a Linux block device path destined for a remote shell command.
     *
     * @throws IllegalArgumentException if the value is not a {@code /dev/...} path
     */
    public static String requireSafeDevicePath(String device) {
        if (device == null || device.isBlank()) {
            throw new IllegalArgumentException("device is required");
        }
        String trimmed = device.trim();
        if (!DEVICE_PATH.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("device must be a /dev/... path: " + trimmed);
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("device must not contain path traversal ('..'): " + trimmed);
        }
        return trimmed;
    }

    /**
     * Validates an EC2 instance id.
     *
     * @throws IllegalArgumentException if the value is not a well-formed instance id
     */
    public static String requireValidInstanceId(String instanceId) {
        if (instanceId == null || !INSTANCE_ID.matcher(instanceId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid EC2 instance id: " + instanceId);
        }
        return instanceId.trim();
    }

    /**
     * Validates an EBS volume id.
     *
     * @throws IllegalArgumentException if the value is not a well-formed volume id
     */
    public static String requireValidVolumeId(String volumeId) {
        if (volumeId == null || !VOLUME_ID.matcher(volumeId.trim()).matches()) {
            throw new IllegalArgumentException("Invalid EBS volume id: " + volumeId);
        }
        return volumeId.trim();
    }

    /**
     * Validates an AWS region id.
     *
     * @throws IllegalArgumentException if the value is not a well-formed region id
     */
    public static String requireValidRegion(String region) {
        if (region == null || !REGION.matcher(region.trim()).matches()) {
            throw new IllegalArgumentException("Invalid AWS region: " + region);
        }
        return region.trim();
    }
}
