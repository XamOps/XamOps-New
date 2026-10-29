package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;

import java.time.Duration;

/**
 * Translates a step's {@link StepOutcome.Failed} into the runner's next move, per the §9.4 error
 * table (design-core.md §9.4, plan-phase-07.md task 7.1). It is a pure function of the
 * {@link ErrorKind}, the step attempts already made and whether the op has mutated yet; it holds no
 * state and touches no store, so it is trivially unit-testable.
 *
 * <p>The §9.4 table, as this maps it:
 *
 * <ul>
 *   <li>{@code TRANSIENT}: the SDK already retried within the per-call budget (§4.3). The step then
 *       returns {@link Decision#retry} with backoff, up to {@link #MAX_STEP_ATTEMPTS} attempts; the
 *       next is {@link Decision#compensate}.</li>
 *   <li>{@code THROTTLED}: adaptive backoff, no attempt consumed, for up to
 *       {@link #THROTTLE_WINDOW} (30 min), then {@link Decision#compensate}.</li>
 *   <li>{@code CONFLICT}: {@link Decision#waitForeignChange} (WAITING {@code FOREIGN_CHANGE}), no
 *       attempt consumed; the drift rules (§5.2, Phase 08) decide from there.</li>
 *   <li>{@code CAPACITY}: {@link Decision#capacity} — no blind retry; the provider decides
 *       diversification or fallback, which for the core means compensate (SAFETY ops keep waiting,
 *       handled by the runner from the {@code compensation_target}).</li>
 *   <li>{@code PERMISSION}, {@code VALIDATION}: {@link Decision#failOrCompensate} — FAILED before the
 *       first mutation, else compensate.</li>
 *   <li>{@code NOT_FOUND}: {@link Decision#gone} — the DR-7 GONE check.</li>
 * </ul>
 *
 * <p>"Compensate" means the op switches to {@code phase=COMPENSATING} toward its
 * {@code compensation_target}; before the first mutation it means "fail, back to {@code prior_state}".
 * The runner owns that split and the SAFETY-op exception (target {@code NONE} keeps waiting and
 * alerts; §9.4, §2.2.1); this class only names the move.
 */
public final class ErrorPolicy {

    /** TRANSIENT retries up to five step attempts before compensation (§9.4). */
    public static final int MAX_STEP_ATTEMPTS = 5;

    /** THROTTLED backs off adaptively for up to 30 min without consuming an attempt (§9.4). */
    public static final Duration THROTTLE_WINDOW = Duration.ofMinutes(30);

    /** The TRANSIENT in-step backoff grows 5s, 10s, 20s, 40s (bounded), between attempts. */
    private static final Duration TRANSIENT_BASE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    /** The adaptive THROTTLED backoff per retry (bounded by {@link #MAX_BACKOFF}). */
    private static final Duration THROTTLE_BASE_BACKOFF = Duration.ofSeconds(30);

    /** The FOREIGN_CHANGE wait before re-checking a CONFLICT (§9.4). */
    private static final Duration FOREIGN_CHANGE_WAIT = Duration.ofSeconds(30);

    private ErrorPolicy() {
    }

    /** What the runner should do about a failed step. */
    public enum Move {
        /** Retry this step after {@code backoff} as WAITING(BACKOFF); the attempt is consumed. */
        RETRY_BACKOFF,
        /** Wait {@code backoff} as WAITING(BACKOFF) for a throttle to clear; no attempt consumed. */
        WAIT_THROTTLED,
        /** Wait {@code backoff} as WAITING(FOREIGN_CHANGE) for a foreign change to settle. */
        WAIT_FOREIGN_CHANGE,
        /** Compensate now (or fail pre-mutation): CAPACITY, PERMISSION, VALIDATION, or TRANSIENT/THROTTLED exhausted. */
        COMPENSATE,
        /** The group or artifact is gone (NOT_FOUND): run the DR-7 GONE path. */
        GONE
    }

    /**
     * The runner's next move after a {@link StepOutcome.Failed}.
     *
     * @param move    the move to make
     * @param backoff the wait before the next attempt or poll (null for COMPENSATE/GONE)
     */
    public record Decision(Move move, Duration backoff) {

        static Decision retry(Duration backoff) {
            return new Decision(Move.RETRY_BACKOFF, backoff);
        }

        static Decision waitThrottled(Duration backoff) {
            return new Decision(Move.WAIT_THROTTLED, backoff);
        }

        static Decision waitForeignChange(Duration backoff) {
            return new Decision(Move.WAIT_FOREIGN_CHANGE, backoff);
        }

        static Decision compensate() {
            return new Decision(Move.COMPENSATE, null);
        }

        static Decision gone() {
            return new Decision(Move.GONE, null);
        }
    }

    /**
     * Decide the next move for a failed step.
     *
     * @param failed        the step's failure (its {@link ErrorKind} drives the decision, §9.4)
     * @param attemptsSoFar the number of attempts already made on this step (1 after the first run)
     */
    public static Decision decide(StepOutcome.Failed failed, int attemptsSoFar) {
        return switch (failed.kind()) {
            case TRANSIENT -> attemptsSoFar >= MAX_STEP_ATTEMPTS
                    ? Decision.compensate()
                    : Decision.retry(transientBackoff(attemptsSoFar));
            case THROTTLED -> Decision.waitThrottled(throttleBackoff(attemptsSoFar));
            case CONFLICT -> Decision.waitForeignChange(FOREIGN_CHANGE_WAIT);
            case CAPACITY, PERMISSION, VALIDATION -> Decision.compensate();
            case NOT_FOUND -> Decision.gone();
        };
    }

    /** Exponential backoff for TRANSIENT retries: 5s, 10s, 20s, 40s, capped at {@link #MAX_BACKOFF}. */
    static Duration transientBackoff(int attemptsSoFar) {
        int shift = Math.max(0, attemptsSoFar - 1);
        Duration backoff = TRANSIENT_BASE_BACKOFF.multipliedBy(1L << Math.min(shift, 6));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    /** Adaptive THROTTLED backoff: 30s, 60s, 120s, ..., capped at {@link #MAX_BACKOFF}. */
    static Duration throttleBackoff(int attemptsSoFar) {
        int shift = Math.max(0, attemptsSoFar);
        Duration backoff = THROTTLE_BASE_BACKOFF.multipliedBy(1L << Math.min(shift, 6));
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }
}
