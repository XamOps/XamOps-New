package com.xammer.mediaops.validation;

/**
 * Exception thrown when transcode job request validation fails.
 * Contains an error code for machine-readable identification and a human-readable message.
 */
public class ValidationException extends RuntimeException {

    private final String errorCode;

    public ValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
