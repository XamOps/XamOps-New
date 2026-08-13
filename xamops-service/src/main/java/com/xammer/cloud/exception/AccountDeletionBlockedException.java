package com.xammer.cloud.exception;

/**
 * Raised when a cloud account cannot be deleted because it still owns records
 * that must not be destroyed as a side effect of removing the account —
 * currently invoices and agreements.
 *
 * Operational data (spot configs, disk alerts, labels, access grants, …) is
 * cleaned up automatically and never produces this exception.
 */
public class AccountDeletionBlockedException extends RuntimeException {

    public AccountDeletionBlockedException(String message) {
        super(message);
    }
}
