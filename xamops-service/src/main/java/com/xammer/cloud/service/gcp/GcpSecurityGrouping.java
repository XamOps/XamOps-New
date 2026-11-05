package com.xammer.cloud.service.gcp;

import com.xammer.cloud.dto.gcp.GcpSecurityFinding;

/**
 * How individual security findings collapse into one alert row. Shared by the streaming digest in
 * {@link GcpSecurityService} and the alert builder in {@link GcpCloudGuardService}, so both agree on
 * what a "group" is.
 */
public final class GcpSecurityGrouping {

    private GcpSecurityGrouping() {
    }

    /** category + severity + resource group, which is the identity of one row. */
    public static String groupKey(GcpSecurityFinding finding) {
        return blankToDefault(finding.getCategory(), "SECURITY_FINDING").toUpperCase()
                + "|" + finding.getSeverity().toUpperCase()
                + "|" + resourceGroup(finding.getResourceName());
    }

    /**
     * The stable part of a resource name, with the version-specific tail removed.
     *
     * <p>
     * For an Artifact Registry image the digest is what differs between findings that are otherwise
     * the same problem, so {@code .../dockerImages/api-server@sha256:abc…} groups with
     * {@code .../dockerImages/api-server@sha256:def…} under {@code .../dockerImages/api-server}.
     * Tags are treated the same way. Anything without a version marker is returned unchanged, which
     * makes each such resource its own group — correct, since we have no evidence they are related.
     */
    public static String resourceGroup(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return "unknown";
        }
        String trimmed = resourceName;
        int digest = trimmed.indexOf('@');
        if (digest > 0) {
            trimmed = trimmed.substring(0, digest);
        } else {
            // A tag separator, but only in the last path segment — a colon earlier in the string is
            // part of the scheme (`//artifactregistry.googleapis.com/...`), not a tag.
            int lastSlash = trimmed.lastIndexOf('/');
            int tag = trimmed.indexOf(':', lastSlash + 1);
            if (tag > 0) {
                trimmed = trimmed.substring(0, tag);
            }
        }
        return trimmed;
    }

    public static String blankToDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
