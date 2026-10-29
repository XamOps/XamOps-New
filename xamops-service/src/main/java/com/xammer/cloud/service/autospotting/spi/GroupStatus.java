package com.xammer.cloud.service.autospotting.spi;

/**
 * The live status of a group as a provider's {@code getStatus} reads it (design-core.md §9.1, §9.2):
 * the live configuration fingerprint, health, and whether foreign activity is in progress.
 * {@code getStatus} makes bounded calls per group (NFR-04) and distinguishes NOT_FOUND from
 * PERMISSION. The reconciler feeds this to {@code reconcile}, which decides without further I/O.
 *
 * <p>Cloud-neutral: {@code liveFingerprint} is the provider's managed-field hash (never a raw
 * provider etag); {@code health} and {@code capacity} are opaque JSON the provider fills, read back
 * by that provider. {@code foreignActivity} is true when a customer-initiated change is in flight.
 * {@code found} is false when the group no longer exists (NOT_FOUND, feeds the GONE check DR-7).
 */
public record GroupStatus(
        boolean found,
        String liveFingerprint,
        String healthJson,
        String capacityJson,
        boolean foreignActivity) {

    /** The group was not found in the cloud (NOT_FOUND): feeds the DR-7 GONE check. */
    public static GroupStatus notFound() {
        return new GroupStatus(false, null, null, null, false);
    }
}
