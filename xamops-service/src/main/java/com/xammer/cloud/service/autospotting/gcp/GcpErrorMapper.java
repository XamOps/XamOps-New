package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;

/**
 * Translates GCP responses and failures into {@link ErrorKind} (design-gcp.md §2.8). Only the HTTP
 * status and the machine {@code reason}/{@code code} are read; the response body text is never kept
 * or returned (FR-SEC-06, AC-GCP-10).
 *
 * <p>A 409, a 412 and {@code resourceNotReady} are always {@link ErrorKind#CONFLICT}, never success
 * (§2.7). A finished operation that carries an {@code error} is a failure, never a success
 * (ux-ops §9.8): see {@link #operationOutcome}.
 */
public final class GcpErrorMapper {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GcpErrorMapper() {
    }

    /** The kind for an HTTP status and the first machine-readable reason of the error body. */
    public static ErrorKind fromHttp(int status, String reason) {
        String r = reason == null ? "" : reason;
        if (status == 409 || status == 412 || "resourceNotReady".equals(r) || "conditionNotMet".equals(r)) {
            return ErrorKind.CONFLICT;
        }
        if (status == 429 || "rateLimitExceeded".equals(r) || "userRateLimitExceeded".equals(r)) {
            return ErrorKind.THROTTLED;
        }
        if (status == 401 || status == 403) {
            return ErrorKind.PERMISSION;
        }
        if (status == 404) {
            return ErrorKind.NOT_FOUND;
        }
        if (status >= 500) {
            return ErrorKind.TRANSIENT;
        }
        return ErrorKind.VALIDATION;
    }

    /** The kind for an operation or {@code listErrors} error code (§2.8). */
    public static ErrorKind fromOperationCode(String code) {
        String c = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (c.startsWith("ZONE_RESOURCE_POOL_EXHAUSTED") || c.startsWith("QUOTA_EXCEEDED")
                || c.contains("QUOTA") || c.startsWith("RESOURCE_POOL_EXHAUSTED")) {
            return ErrorKind.CAPACITY;
        }
        if (c.contains("PERMISSION") || c.equals("FORBIDDEN")) {
            return ErrorKind.PERMISSION;
        }
        if (c.contains("RESOURCE_NOT_READY") || c.contains("CONDITION_NOT_MET")) {
            return ErrorKind.CONFLICT;
        }
        if (c.contains("RESOURCE_NOT_FOUND") || c.equals("NOT_FOUND")) {
            return ErrorKind.NOT_FOUND;
        }
        if (c.contains("INTERNAL") || c.contains("UNAVAILABLE") || c.contains("TIMEOUT")) {
            return ErrorKind.TRANSIENT;
        }
        return ErrorKind.VALIDATION;
    }

    /** The kind of any thrown failure (a {@link GcpReadException} keeps its own). */
    public static ErrorKind classify(Throwable t) {
        if (t instanceof GcpReadException g) {
            return g.kind();
        }
        if (t instanceof RestClientResponseException e) {
            return fromHttp(e.getStatusCode().value(), reasonOf(e.getResponseBodyAsString()));
        }
        if (t instanceof ResourceAccessException) {
            return ErrorKind.TRANSIENT;
        }
        return ErrorKind.TRANSIENT;
    }

    /** Wrap a failure as a {@link GcpReadException} naming {@code call}; the body is read for its reason only. */
    public static GcpReadException wrap(String call, Throwable t) {
        if (t instanceof GcpReadException g) {
            return g;
        }
        int status = t instanceof RestClientResponseException e ? e.getStatusCode().value() : 0;
        return new GcpReadException(call, classify(t), status);
    }

    /** The first {@code error.errors[].reason} of a Compute error body, or null. Nothing else is kept. */
    static String reasonOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode first = JSON.readTree(body).path("error").path("errors").path(0);
            String reason = first.path("reason").asText(null);
            return reason == null || reason.isBlank() ? null : reason;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The state of a zone, region or global operation resource. {@code done} is false until
     * {@code status == DONE}. A DONE operation with an {@code error} block is a failure whose kind
     * comes from the first error code (CAPACITY for pool exhaustion or quota). A DONE operation with
     * no error is the only success.
     */
    public static OperationOutcome operationOutcome(JsonNode operation) {
        if (operation == null || !"DONE".equals(operation.path("status").asText())) {
            return new OperationOutcome(false, false, null, null);
        }
        JsonNode errors = operation.path("error").path("errors");
        if (operation.has("error") && !operation.path("error").isNull()) {
            String code = errors.path(0).path("code").asText(null);
            int http = operation.path("httpErrorStatusCode").asInt(0);
            ErrorKind kind = code != null ? fromOperationCode(code) : (http > 0 ? fromHttp(http, null) : ErrorKind.VALIDATION);
            return new OperationOutcome(true, false, kind, code);
        }
        return new OperationOutcome(true, true, null, null);
    }

    /** Outcome of one operation poll: {@code success} only when done and error-free. */
    public record OperationOutcome(boolean done, boolean success, ErrorKind errorKind, String errorCode) {
        public boolean failed() {
            return done && !success;
        }
    }
}
