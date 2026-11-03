package com.xammer.cloud.service.onboarding;

import org.springframework.http.HttpStatus;

/**
 * A structured onboarding failure (design §4). Carries a stable {@code code}, the HTTP status to
 * return, a human message and an optional remediation string. A {@code @ControllerAdvice} maps it
 * to {@code { "error": CODE, "message": "...", "remediation": "..."? }}.
 */
public class OnboardingException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final String remediation;

    public OnboardingException(String code, HttpStatus status, String message) {
        this(code, status, message, null);
    }

    public OnboardingException(String code, HttpStatus status, String message, String remediation) {
        super(message);
        this.code = code;
        this.status = status;
        this.remediation = remediation;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getRemediation() {
        return remediation;
    }
}
