package com.xammer.cloud.service.autospotting.gcp;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;

/**
 * A failed GCP call, already translated to a provider-neutral {@link ErrorKind} (design-gcp.md §2.8,
 * design-core.md §9.4). It carries the name of the call and the HTTP status (0 when there was none)
 * and never the raw response body (FR-SEC-06), so {@code toString()} is safe to log.
 */
public final class GcpReadException extends RuntimeException {
    private final ErrorKind kind;
    private final String check;
    private final int httpStatus;

    public GcpReadException(String check, ErrorKind kind) {
        this(check, kind, 0);
    }

    public GcpReadException(String check, ErrorKind kind, int httpStatus) {
        super(check + ":" + kind, null, false, false);
        this.kind = kind;
        this.check = check;
        this.httpStatus = httpStatus;
    }

    public ErrorKind kind() {
        return kind;
    }

    public String check() {
        return check;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
