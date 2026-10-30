package com.xammer.cloud.dto.autospotting.v2;

import java.util.List;

/**
 * The AutoSpotting error envelope (design-core.md §11 "Error envelope"): RFC 9457
 * {@code application/problem+json}. {@code code} is the stable contract the UI and tests match on;
 * {@code detail} is safe text, never a raw provider message (FR-SEC-02, AC-SEC-04).
 *
 * <p>Emitted by {@link com.xammer.cloud.controller.autospotting.AutoSpottingProblemAdvice}, ahead of
 * {@code GlobalExceptionHandler}, for every AutoSpotting controller. The same shape (as a flat JSON
 * body) is produced by the {@code permissions-common} CSRF/origin filter for its 403s, so a client
 * sees one error contract across the filter and the controllers.
 *
 * @param type        a stable URI naming the error class, {@code https://xamops.com/errors/as/<code>}
 * @param title       a short human title for the status
 * @param status      the HTTP status
 * @param code        the stable machine code (design-core.md §11 code list)
 * @param detail      safe explanatory text; never a raw cloud SDK message
 * @param requestId   the request id from MDC, or null
 * @param operationId the related operation id, or null
 * @param engagedSwitch the engaged kill-switch name for a gate block (serialized as {@code switch}),
 *                      or null
 * @param retryable   whether the client may retry (true for the store-outage 503s)
 * @param fieldErrors per-field validation messages (empty unless a 400 validation failure)
 */
public record ProblemDto(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String requestId,
        java.util.UUID operationId,
        @com.fasterxml.jackson.annotation.JsonProperty("switch") String engagedSwitch,
        boolean retryable,
        List<FieldError> fieldErrors) {

    private static final String TYPE_PREFIX = "https://xamops.com/errors/as/";

    public ProblemDto {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    /** One rejected field: its name and a safe message (AC-SEC-02). */
    public record FieldError(String field, String message) {
    }

    /** Builds the envelope for {@code code} at {@code status} with {@code detail}. */
    public static Builder of(String code, int status, String detail) {
        return new Builder(code, status, detail);
    }

    public static final class Builder {
        private final String code;
        private final int status;
        private final String detail;
        private String title;
        private String requestId;
        private java.util.UUID operationId;
        private String engagedSwitch;
        private boolean retryable;
        private List<FieldError> fieldErrors = List.of();

        private Builder(String code, int status, String detail) {
            this.code = code;
            this.status = status;
            this.detail = detail;
            this.title = code;
        }

        public Builder title(String v) { this.title = v; return this; }
        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder operationId(java.util.UUID v) { this.operationId = v; return this; }
        public Builder engagedSwitch(String v) { this.engagedSwitch = v; return this; }
        public Builder retryable(boolean v) { this.retryable = v; return this; }
        public Builder fieldErrors(List<FieldError> v) { this.fieldErrors = v; return this; }

        public ProblemDto build() {
            return new ProblemDto(TYPE_PREFIX + code, title, status, code, detail, requestId,
                    operationId, engagedSwitch, retryable, fieldErrors);
        }
    }
}
