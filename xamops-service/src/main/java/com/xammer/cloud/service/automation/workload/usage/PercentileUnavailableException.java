package com.xammer.cloud.service.automation.workload.usage;

import java.util.Collection;
import java.util.List;

/**
 * Raised when a policy asks for a percentile the usage source did not compute.
 *
 * <p>Unchecked, because it is a programming or wiring error rather than a user-facing condition: the
 * provider and the policy disagree about what was measured, and the fix is to make the provider
 * compute it, never to answer with a different number.
 */
public class PercentileUnavailableException extends RuntimeException {

    private final int requested;
    private final List<Integer> available;

    public PercentileUnavailableException(int requested, Collection<Integer> available) {
        super("usage source did not supply p" + requested + "; it supplied " + available
                + ". Substituting a neighbouring percentile would misreport the basis of the"
                + " recommendation, so this is an error rather than a fallback.");
        this.requested = requested;
        this.available = List.copyOf(available);
    }

    public int requested() {
        return requested;
    }

    public List<Integer> available() {
        return available;
    }
}
