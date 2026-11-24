package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;

/**
 * A failed AWS read, already translated to a provider-neutral {@link ErrorKind} (design-core.md §9.4).
 * Carries the name of the check or call that failed and never the raw SDK message (FR-SEC-06), so
 * {@code toString()} is safe to log.
 */
public final class AwsReadException extends RuntimeException {

    private final ErrorKind kind;
    private final String check;

    public AwsReadException(String check, ErrorKind kind) {
        super(check + ":" + kind, null, false, false);
        this.kind = kind;
        this.check = check;
    }

    public ErrorKind kind() {
        return kind;
    }

    public String check() {
        return check;
    }
}
