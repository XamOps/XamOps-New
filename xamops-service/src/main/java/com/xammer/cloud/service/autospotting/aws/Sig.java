package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;

/**
 * The outcome of reading one input of an eligibility check: a value, a failed read (never a pass,
 * AC-ELG-03), or a read LIST mode did not run (reported as {@code DEFERRED}, design-core.md §9.2).
 */
public sealed interface Sig<T> {

    /** The read succeeded with {@code value}. */
    record Ok<T>(T value) implements Sig<T> {
    }

    /** The read failed with {@code kind}; the check becomes a BLOCKED {@code CHECK_FAILED}. */
    record Failed<T>(ErrorKind kind) implements Sig<T> {
    }

    /** The read was not made in this mode; the check is reported as deferred. */
    record Deferred<T>() implements Sig<T> {
    }

    static <T> Sig<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Sig<T> failed(ErrorKind kind) {
        return new Failed<>(kind);
    }

    static <T> Sig<T> deferred() {
        return new Deferred<>();
    }
}
