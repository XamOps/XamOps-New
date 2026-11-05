package com.xammer.cloud.xampulse.exception;

import java.util.Map;

/** Error carrying a stable XP_* code and the HTTP status from the shared API contract (2.1). */
public class XamPulseException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final Map<String, Object> details;

    public XamPulseException(String code, int httpStatus, String message) {
        this(code, httpStatus, message, Map.of());
    }

    public XamPulseException(String code, int httpStatus, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? Map.of() : details;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public Map<String, Object> getDetails() { return details; }

    /** Identical for "missing" and "not yours" so ids cannot be probed. */
    public static XamPulseException notFound() {
        return new XamPulseException("XP_NOT_FOUND", 404, "Not found.");
    }
    public static XamPulseException badFilter(String message) {
        return new XamPulseException("XP_BAD_FILTER", 400, message);
    }
    public static XamPulseException invalid(String message) {
        return new XamPulseException("XP_INVALID_REQUEST", 400, message);
    }
    public static XamPulseException tooBroad(int maxRangeDays) {
        return new XamPulseException("XP_QUERY_TOO_BROAD", 400,
                "Time range is too wide for interactive queries.", Map.of("maxRangeDays", maxRangeDays));
    }
    public static XamPulseException notSetUp() {
        return new XamPulseException("XP_NOT_SET_UP", 409, "XamPulse is not set up.");
    }
    public static XamPulseException storeUnavailable() {
        return new XamPulseException("XP_STORE_UNAVAILABLE", 503, "The telemetry store is unavailable.");
    }
    public static XamPulseException rateLimited() {
        return new XamPulseException("XP_RATE_LIMITED", 429, "Too many concurrent queries. Retry shortly.");
    }
    public static XamPulseException timeout() {
        return new XamPulseException("XP_QUERY_TIMEOUT", 504, "The query took too long. Narrow the time range or filter.");
    }
    public static XamPulseException conflict(String message) {
        return new XamPulseException("XP_STATE_CONFLICT", 409, message);
    }
    public static XamPulseException forbidden(String message) {
        return new XamPulseException("XP_FORBIDDEN", 403, message);
    }

    public static XamPulseException idempotencyKeyReused() {
        return new XamPulseException("XP_IDEMPOTENCY_KEY_REUSED", 409, "That Idempotency-Key was already used with a different request.");
    }

    public static XamPulseException estimateChanged() {
        return new XamPulseException("XP_ARCHIVE_ESTIMATE_CHANGED", 409, "The archive estimate changed. Review the new estimate and confirm again.");
    }
}
