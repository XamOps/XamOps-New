package com.xammer.cloud.service.autospotting.core.lease;

/**
 * Thrown when the lease store itself can't be reached (a {@code DataAccessException} on an acquire,
 * renew or release), as opposed to an answered "you lost the lease" ({@link LeaseLostException},
 * design-core.md §3.4). {@link LeaseService} maps the store error to this exception, surfaced as 503
 * {@code AS_LEASE_UNAVAILABLE} with {@code retryable=true}, and increments
 * {@code autospotting.lease.failures{reason=store_error}} (AC-SPN-02). No cloud call is made.
 */
public class LeaseUnavailableException extends RuntimeException {

    /** The error code callers map to the 503 response. */
    public static final String ERROR_CODE = "AS_LEASE_UNAVAILABLE";

    public LeaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Always retryable: the store was unreachable, not the lease lost. */
    public boolean isRetryable() {
        return true;
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
