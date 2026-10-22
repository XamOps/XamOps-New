package com.xammer.cloud.service.automation.workload.api;

/**
 * A read named a workload or HPA this cluster does not have: neither the inventory nor Prometheus
 * knows it. Rendered as {@code 404 K8S_INVALID_REQUEST} with {@link #field()} named (design v2 §9.7),
 * so the UI drops a stale link instead of drawing an empty chart for something that does not exist.
 *
 * <p>Raised only after tenancy and registration have passed, so it says nothing about another
 * tenant's clusters.
 */
public class UnknownTargetException extends RuntimeException {

    private final String field;

    public UnknownTargetException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The parameter that named the unknown target. */
    public String field() {
        return field;
    }
}
