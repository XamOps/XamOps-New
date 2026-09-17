package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.DashboardData;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The identity of a finding, independent of which system reported it.
 *
 * <p>
 * A finding has no id a user can act on. {@link DashboardData.SecurityFinding} — the shape the
 * Security Center page actually renders — carries only {@code resourceId}, {@code region},
 * {@code category}, {@code severity}, {@code description}, {@code complianceFramework} and
 * {@code controlId}. Adding an id would not help either, because the same problem arrives from
 * three places with three different ids: a Security Hub finding id, a Prowler check result with
 * no id at all, and a {@code SOURCE_XAMOPS_DETECTOR} row whose id is a hash of the CloudTrail
 * event that raised it and therefore cannot be recomputed from the state of the account.
 *
 * <p>
 * So identity is {@code resourceId|controlId} — the resource that is wrong and the rule it is
 * wrong against. This is not a new convention: it is exactly the pair
 * {@link SecurityFindingProjectionService} already dedupes on when merging live findings into a
 * scan's results, on the grounds that "a Config rule breach and the Prowler check for the same
 * control are the same problem said twice". If those two are the same finding for counting, they
 * are the same finding for resolving.
 */
public final class SecurityFindingKey {

    private SecurityFindingKey() {
    }

    /** The natural key of a finding as rendered on the page. */
    public static String of(DashboardData.SecurityFinding finding) {
        if (finding == null) {
            return of(null, null);
        }
        return of(finding.getResourceId(), finding.getControlId());
    }

    /** The natural key of a stored event-driven finding. */
    public static String of(CloudSecurityFinding finding) {
        if (finding == null) {
            return of(null, null);
        }
        return of(finding.getResourceArn(), finding.getControlId());
    }

    /**
     * The natural key of a resource/control pair.
     *
     * <p>
     * Null and blank collapse to the empty string rather than being rejected. Both components are
     * genuinely optional in the data — GuardDuty findings often have no control id, and a finding
     * whose resource could not be identified from a truncated CloudTrail detail has no resource —
     * and a key that threw on those would make precisely the least tractable findings the ones a
     * user cannot dismiss.
     */
    public static String of(String resourceId, String controlId) {
        return normalise(resourceId) + "|" + normalise(controlId);
    }

    /** The stored form of a key: fixed width, safe to index. */
    public static String hash(String key) {
        return sha256Short(key == null ? "" : key);
    }

    public static String hashOf(String resourceId, String controlId) {
        return hash(of(resourceId, controlId));
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        // "—" is what SecurityFindingProjectionService substitutes for a missing resource so the
        // table has something to render. Treated as absent here, so that dismissing such a finding
        // does not key on a dash that could just as easily have been an empty cell.
        if (trimmed.isEmpty() || "—".equals(trimmed) || "-".equals(trimmed)
                || "null".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed;
    }

    /**
     * Short SHA-256, 12 bytes as 24 hex characters.
     *
     * <p>
     * The single implementation behind both this and the derived finding ids in
     * {@link SecurityFindingIngestService}, which delegates here. Two copies of a hash used as a
     * database key is the kind of duplication that stays identical right up until someone
     * "improves" one of them.
     */
    static String sha256Short(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is mandatory on every JVM; if it is genuinely missing, a stable fallback
            // keeps ingest working rather than losing the finding.
            return Integer.toHexString(value.hashCode());
        }
    }
}
