package com.xammer.cloud.service.autospotting.spi;

import java.time.Duration;
import java.util.List;

/**
 * The outcome of a {@link Step} execution or probe (design-core.md §9.1):
 * {@code Done(output, requestIds) | InProgress(pollAfter, progress) | Failed(ErrorKind, code, redactedMsg)}.
 * A sealed interface so a caller must handle every case.
 */
public sealed interface StepOutcome permits StepOutcome.Done, StepOutcome.InProgress, StepOutcome.Failed {

    /** The step finished. {@code outputJson} is redacted on write; {@code requestIds} are the cloud request ids. */
    record Done(String outputJson, List<String> requestIds) implements StepOutcome {
        public Done {
            requestIds = requestIds == null ? List.of() : List.copyOf(requestIds);
        }
    }

    /** The step's cloud work is still running; poll again after {@code pollAfter}. {@code progressJson} optional. */
    record InProgress(Duration pollAfter, String progressJson) implements StepOutcome {
        public InProgress {
            if (pollAfter == null) {
                throw new IllegalArgumentException("pollAfter is required for InProgress");
            }
        }
    }

    /**
     * The step failed. {@code kind} drives retry and the op outcome (§9.4); {@code code} is a stable
     * code; {@code redactedMessage} is already scrubbed and safe to store/log (FR-SEC-06).
     */
    /**
     * A failed step. {@code noEffect} is true only when the cloud definitively rejected the step's request so that
     * nothing changed (an AWS 4xx client/validation rejection of an atomic call, review round 2 #3); a timeout, a 5xx
     * or any unknown outcome is false. The runner uses it for the first mutating step only (design-core 9.4).
     */
    record Failed(ErrorKind kind, String code, String redactedMessage, boolean noEffect) implements StepOutcome {
        public Failed {
            if (kind == null) {
                throw new IllegalArgumentException("ErrorKind is required for Failed");
            }
        }

        /** A failure whose effect on the cloud is not known to be none (the conservative default). */
        public Failed(ErrorKind kind, String code, String redactedMessage) {
            this(kind, code, redactedMessage, false);
        }

        /** The same failure without the no-effect claim (a resumed step cannot vouch for an earlier attempt). */
        public Failed withoutNoEffect() {
            return noEffect ? new Failed(kind, code, redactedMessage, false) : this;
        }
    }
}
