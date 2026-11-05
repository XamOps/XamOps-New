package com.xammer.cloud.service.envallocation;

/**
 * A refusal the caller can act on, carrying the HTTP status it should be answered with.
 *
 * <p>The message is shown to the user as written, so it names the problem and the fix rather
 * than an internal state.
 */
public class EnvAllocationException extends RuntimeException {

    private final int status;

    public EnvAllocationException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static EnvAllocationException badRequest(String message) {
        return new EnvAllocationException(400, message);
    }

    public static EnvAllocationException notFound(String message) {
        return new EnvAllocationException(404, message);
    }

    public static EnvAllocationException conflict(String message) {
        return new EnvAllocationException(409, message);
    }
}
