package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cadence is pinned so the deferred scheduler has no numbers of its own to drift from.
 */
class ReconcileCadenceTest {

    @Test
    void theFourIntervalsArePinned() {
        assertThat(ReconcileCadence.next(ReconcileOutcome.NORMAL)).isEqualTo(Duration.ofMinutes(30));
        assertThat(ReconcileCadence.next(ReconcileOutcome.VOLATILE)).isEqualTo(Duration.ofMinutes(2));
        assertThat(ReconcileCadence.next(ReconcileOutcome.METRICS_FAILURE)).isEqualTo(Duration.ofMinutes(1));
        assertThat(ReconcileCadence.next(ReconcileOutcome.DISABLED)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void everyOutcomeIsCovered() {
        for (ReconcileOutcome outcome : ReconcileOutcome.values()) {
            assertThat(ReconcileCadence.next(outcome)).isNotNull().isPositive();
        }
    }

    @Test
    void aMissingOutcomeIsAnError() {
        assertThatThrownBy(() -> ReconcileCadence.next(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNormalCadenceIsLongerThanTheCooldown() {
        // Otherwise the engine would re-evaluate a workload inside its own cooldown window on every
        // single pass, and every pass would answer COOLDOWN.
        assertThat(ReconcileCadence.next(ReconcileOutcome.NORMAL))
                .isLessThanOrEqualTo(Duration.ofMinutes(WorkloadPolicyDefaults.COOLDOWN_MINUTES));
    }
}
