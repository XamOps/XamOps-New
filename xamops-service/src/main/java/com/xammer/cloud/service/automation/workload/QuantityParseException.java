package com.xammer.cloud.service.automation.workload;

/**
 * Raised when a Kubernetes quantity string cannot be read.
 *
 * <p>Exists because the Go reference wrote {@code qty, _ := resource.ParseQuantity(rawMetric)} and
 * discarded the error, so an unparseable metric became a zero and a zero became a near-zero
 * recommendation. There is deliberately <strong>no</strong> {@code parseOrDefault} anywhere: a
 * resource value that could not be read is not a resource value of zero, and the difference is the
 * gap between "we don't know" and "we recommend nothing".
 *
 * <p>Unchecked, so that no caller can quietly swallow it with an empty catch block that the compiler
 * would otherwise have demanded.
 */
public class QuantityParseException extends RuntimeException {

    private final String field;
    private final String offendingValue;

    public QuantityParseException(String field, String offendingValue, String detail) {
        super("could not read " + field + " from " + describe(offendingValue) + ": " + detail);
        this.field = field;
        this.offendingValue = offendingValue;
    }

    public QuantityParseException(String field, String offendingValue, String detail, Throwable cause) {
        super("could not read " + field + " from " + describe(offendingValue) + ": " + detail, cause);
        this.field = field;
        this.offendingValue = offendingValue;
    }

    private static String describe(String value) {
        if (value == null) {
            return "null";
        }
        return "'" + value + "'";
    }

    /** Which field the value came from, so the message names something a human can go and fix. */
    public String field() {
        return field;
    }

    public String offendingValue() {
        return offendingValue;
    }
}
