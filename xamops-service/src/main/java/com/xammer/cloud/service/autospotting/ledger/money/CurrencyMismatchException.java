package com.xammer.cloud.service.autospotting.ledger.money;

import java.util.Currency;

/**
 * Thrown when two {@link Money} values of different currencies meet in one operation (AC-LED-08).
 * Arithmetic never coerces: the only way across currencies is a recorded FX conversion. A mismatch
 * inside a ledger run is a bug that fails the run, never a silent conversion (design-ledger.md §9.3).
 */
public class CurrencyMismatchException extends RuntimeException {

    private final Currency left;
    private final Currency right;

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Currency mismatch: " + left.getCurrencyCode() + " vs " + right.getCurrencyCode());
        this.left = left;
        this.right = right;
    }

    public Currency getLeft() {
        return left;
    }

    public Currency getRight() {
        return right;
    }
}
