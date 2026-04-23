package com.xammer.cloud.exception;

/**
 * Thrown when Authentik returns HTTP 401 for a tenant's API token.
 * This means the token is invalid or has been revoked.
 *
 * The controller maps this to HTTP 503 so the frontend can surface a
 * "Identity service unavailable — contact support" message without
 * revealing the underlying cause.
 *
 * The super admin must re-provision the tenant token to recover.
 * Token values are never included in the exception message.
 */
public class AuthentikUnauthorizedException extends RuntimeException {

    public AuthentikUnauthorizedException(Long clientId) {
        super("Authentik token invalid or revoked for client " + clientId
              + ". Re-provisioning required.");
    }
}
