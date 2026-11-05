package com.xammer.cloud.autospotting.ops;

import com.xammer.cloud.service.autospotting.core.ops.ErrorPolicy;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ErrorPolicy}: the §9.4 error table as pure logic (design-core.md §9.4,
 * plan-phase-07.md task 7.1). No DB, no Spring.
 */
class ErrorPolicyTest {

    private static StepOutcome.Failed failed(ErrorKind kind) {
        return new StepOutcome.Failed(kind, kind.name(), "redacted");
    }

    @Test
    void transientRetriesWithBackoffUntilTheAttemptCapThenCompensates() {
        for (int attempt = 1; attempt < ErrorPolicy.MAX_STEP_ATTEMPTS; attempt++) {
            ErrorPolicy.Decision d = ErrorPolicy.decide(failed(ErrorKind.TRANSIENT), attempt);
            assertThat(d.move()).isEqualTo(ErrorPolicy.Move.RETRY_BACKOFF);
            assertThat(d.backoff()).isGreaterThan(Duration.ZERO);
        }
        // The fifth attempt (cap) compensates.
        assertThat(ErrorPolicy.decide(failed(ErrorKind.TRANSIENT), ErrorPolicy.MAX_STEP_ATTEMPTS).move())
                .isEqualTo(ErrorPolicy.Move.COMPENSATE);
    }

    @Test
    void transientBackoffGrowsAndIsBounded() {
        Duration first = ErrorPolicy.decide(failed(ErrorKind.TRANSIENT), 1).backoff();
        Duration second = ErrorPolicy.decide(failed(ErrorKind.TRANSIENT), 2).backoff();
        assertThat(second).isGreaterThanOrEqualTo(first);
        // Bounded: the backoff before the cap never exceeds the 5 min ceiling.
        Duration late = ErrorPolicy.decide(failed(ErrorKind.TRANSIENT), ErrorPolicy.MAX_STEP_ATTEMPTS - 1).backoff();
        assertThat(late).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void throttledWaitsWithoutConsumingAttempts() {
        ErrorPolicy.Decision d = ErrorPolicy.decide(failed(ErrorKind.THROTTLED), 3);
        assertThat(d.move()).isEqualTo(ErrorPolicy.Move.WAIT_THROTTLED);
        assertThat(d.backoff()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void conflictWaitsForeignChange() {
        assertThat(ErrorPolicy.decide(failed(ErrorKind.CONFLICT), 1).move())
                .isEqualTo(ErrorPolicy.Move.WAIT_FOREIGN_CHANGE);
    }

    @Test
    void capacityPermissionAndValidationCompensate() {
        assertThat(ErrorPolicy.decide(failed(ErrorKind.CAPACITY), 1).move()).isEqualTo(ErrorPolicy.Move.COMPENSATE);
        assertThat(ErrorPolicy.decide(failed(ErrorKind.PERMISSION), 1).move()).isEqualTo(ErrorPolicy.Move.COMPENSATE);
        assertThat(ErrorPolicy.decide(failed(ErrorKind.VALIDATION), 1).move()).isEqualTo(ErrorPolicy.Move.COMPENSATE);
    }

    @Test
    void notFoundIsGone() {
        assertThat(ErrorPolicy.decide(failed(ErrorKind.NOT_FOUND), 1).move()).isEqualTo(ErrorPolicy.Move.GONE);
    }
}
