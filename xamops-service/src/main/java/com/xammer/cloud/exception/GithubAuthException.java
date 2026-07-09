package com.xammer.cloud.exception;

/**
 * Thrown when the GitHub API rejects the stored Personal Access Token
 * (HTTP 401 "Bad credentials"). Signals that the user's PAT is invalid,
 * expired, or revoked and needs to be reconnected — as opposed to a repo
 * simply having no workflow runs.
 */
public class GithubAuthException extends RuntimeException {

    public GithubAuthException(String message) {
        super(message);
    }

    public GithubAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
