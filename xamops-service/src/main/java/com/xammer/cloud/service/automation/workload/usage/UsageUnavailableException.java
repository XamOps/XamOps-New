package com.xammer.cloud.service.automation.workload.usage;

/**
 * The only failure a {@link UsageHistoryProvider} may raise, with two typed kinds that are both
 * shared reason codes the UI renders with their own copy and remedy.
 */
public class UsageUnavailableException extends Exception {

    public enum Kind {
        /**
         * Nothing can supply usage percentiles for this cluster: no Prometheus reachable through the
         * API service proxy, and no agent maintaining a rolling window.
         *
         * <p>Raised when the Prometheus service proxy cannot be reached, is forbidden by RBAC, or
         * answers with an error body. It is what keeps the read endpoint honest: an empty
         * {@code workloads} array with a stated reason, rather than a fabricated recommendation.
         */
        NO_METRICS_SOURCE,

        /**
         * A source exists but has not observed enough of the policy window to decide from — too few
         * samples, or a window shorter than the engine's minimum.
         *
         * <p>A waiting state, not an error. Recommending from four hours of data against a 24h
         * policy would be a quiet lie about the basis of the number.
         */
        INSUFFICIENT_HISTORY
    }

    private final Kind kind;

    public UsageUnavailableException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public UsageUnavailableException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The shared reason code, without the {@code K8S_} prefix, as it appears in payload bodies. */
    public String reasonCode() {
        return kind.name();
    }
}
