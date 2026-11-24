package com.xammer.cloud.service.gcp;

/** GCP credential source stored in {@code cloud_account.gcp_auth_mode}. */
public enum GcpAuthMode {
    /** A stored service-account key (today's behaviour). */
    KEY,
    /** Keyless: Workload Identity Federation, then impersonation. Not offered until the pool is approved (PO-GCP-KEY). */
    IMPERSONATION;

    /** Null or unknown reads as {@link #KEY}, so existing rows keep working. */
    public static GcpAuthMode fromColumn(String value) {
        return IMPERSONATION.name().equalsIgnoreCase(value == null ? null : value.trim()) ? IMPERSONATION : KEY;
    }
}
