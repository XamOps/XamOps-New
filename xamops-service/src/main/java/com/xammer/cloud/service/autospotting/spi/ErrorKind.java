package com.xammer.cloud.service.autospotting.spi;

/**
 * The provider-neutral error kinds (design-core.md §9.4). Providers translate raw SDK exceptions
 * into these at the boundary; raw messages are logged redacted and never returned to the UI
 * (FR-SEC-06). The kind decides retry-in-step and the operation outcome:
 *
 * <ul>
 *   <li>{@link #TRANSIENT}: 5xx, timeouts, connection reset — SDK retries, then InProgress backoff,
 *       up to 5 step attempts, then COMPENSATE.</li>
 *   <li>{@link #THROTTLED}: throttling / rate-limit / 429 — adaptive backoff, no attempt consumed for
 *       30 min, then COMPENSATE.</li>
 *   <li>{@link #CONFLICT}: refresh in progress, resource not ready, fingerprint mismatch — WAITING
 *       FOREIGN_CHANGE, drift rules.</li>
 *   <li>{@link #CAPACITY}: insufficient capacity / pool exhausted / quota — no blind retry; the
 *       provider decides diversification or fallback.</li>
 *   <li>{@link #PERMISSION}: access denied / unauthorized / 403 — FAILED pre-mutation or COMPENSATE.</li>
 *   <li>{@link #NOT_FOUND}: group or artifact missing — GONE check (DR-7).</li>
 *   <li>{@link #VALIDATION}: bad request, a bug — COMPENSATE, then alert.</li>
 * </ul>
 */
public enum ErrorKind {
    TRANSIENT,
    THROTTLED,
    CONFLICT,
    CAPACITY,
    PERMISSION,
    NOT_FOUND,
    VALIDATION
}
