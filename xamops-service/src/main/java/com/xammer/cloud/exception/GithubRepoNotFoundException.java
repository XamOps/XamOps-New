package com.xammer.cloud.exception;

/**
 * Thrown when the GitHub API returns 404 for a configured repository
 * (HTTP 404 "Not Found"). Signals that the repository owner/name is wrong,
 * or the repository is private and the stored token has no access to it —
 * as opposed to the repository simply having no workflow runs.
 */
public class GithubRepoNotFoundException extends RuntimeException {

    public GithubRepoNotFoundException(String message) {
        super(message);
    }

    public GithubRepoNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
