package com.xammer.cloud.service.autospotting.core.lease;

/**
 * Thrown inside a fenced transaction ({@link FencedTx}) when the fence row no longer matches the
 * handle's token and generation, i.e. the lease was lost to another holder (design-core.md §3.4,
 * AC-SPN-03). The transaction rolls back with nothing committed.
 *
 * <p>Unlike {@link LeaseUnavailableException}, this is not a store outage: the store answered, and
 * the answer is "you no longer hold this lease". The caller stops before the next cloud call.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(String message) {
        super(message);
    }
}
