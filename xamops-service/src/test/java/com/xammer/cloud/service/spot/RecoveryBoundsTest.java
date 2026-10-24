package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Recovery;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Recovery as a snapshot-granularity range (design §B5 "Recovery bounds", AC23, AC26). */
class RecoveryBoundsTest {

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-24T10:05:00Z");

    private static K8sSpotInterruptionEvent row(String kind, String recoveryStatus) {
        K8sSpotInterruptionEvent e = new K8sSpotInterruptionEvent();
        e.setNoticeKind(kind);
        e.setRecoveryStatus(recoveryStatus);
        return e;
    }

    @Test
    void aSameSnapshotUnconfirmedRecoveryIsZeroToTheSnapshotGap_AC23() {
        K8sSpotInterruptionEvent e = row(SpotNoticeRules.KIND_UNCONFIRMED, SpotInterruptionMerge.RECOVERED);
        e.setNodeLastSeenAt(T0);
        e.setNoticedAt(T1);
        e.setRecoveredObservedAt(T1);

        Recovery r = SpotInterruptionReadService.recoveryBounds(e);

        assertThat(r.lowerSeconds()).isEqualTo(0L);
        assertThat(r.upperSeconds()).isEqualTo(300L);
        assertThat(r.recoveredByFirstSighting()).isFalse();
        assertThat(r.status()).isEqualTo("recovered");
        assertThat(r.observedAt()).isEqualTo("2026-10-24T10:05:00Z");
    }

    @Test
    void aNoticeRowIsMeasuredFromTheNotice() {
        K8sSpotInterruptionEvent e = row(SpotNoticeRules.KIND_INTERRUPTION, SpotInterruptionMerge.RECOVERED);
        e.setNoticedAt(T0);
        e.setRecoveryLastUnrecoveredAt(T0.plusSeconds(180));
        e.setRecoveredObservedAt(T0.plusSeconds(480));

        Recovery r = SpotInterruptionReadService.recoveryBounds(e);

        assertThat(r.lowerSeconds()).isEqualTo(180L);
        assertThat(r.upperSeconds()).isEqualTo(480L);
        assertThat(r.recoveredByFirstSighting()).isFalse();
    }

    @Test
    void anUnconfirmedRowWithoutLastSeenFallsBackToTheNotice() {
        K8sSpotInterruptionEvent e = row(SpotNoticeRules.KIND_UNCONFIRMED, SpotInterruptionMerge.RECOVERED);
        e.setNoticedAt(T0);
        e.setRecoveredObservedAt(T1);

        Recovery r = SpotInterruptionReadService.recoveryBounds(e);

        assertThat(r.lowerSeconds()).isEqualTo(0L);
        assertThat(r.upperSeconds()).isEqualTo(300L);
    }

    @Test
    void aNoticeFirstSeenAndRecoveredInTheSameSnapshotIsRecoveredByFirstSighting() {
        K8sSpotInterruptionEvent e = row(SpotNoticeRules.KIND_INTERRUPTION, SpotInterruptionMerge.RECOVERED);
        e.setNoticedAt(T1);
        e.setRecoveredObservedAt(T1);

        Recovery r = SpotInterruptionReadService.recoveryBounds(e);

        assertThat(r.recoveredByFirstSighting()).isTrue();
        assertThat(r.lowerSeconds()).isEqualTo(0L);
        assertThat(r.upperSeconds()).isEqualTo(0L);
    }

    @Test
    void notMeasurableAndEveryOtherStatusServeNullBounds_AC26() {
        for (String s : new String[] {SpotInterruptionMerge.NOT_MEASURABLE, SpotInterruptionMerge.TRACKING,
                SpotInterruptionMerge.NOT_OBSERVED, SpotInterruptionMerge.NODE_NOT_REMOVED,
                SpotInterruptionMerge.NO_WORKLOADS, SpotInterruptionMerge.NO_SNAPSHOT}) {
            K8sSpotInterruptionEvent e = row(SpotNoticeRules.KIND_INTERRUPTION, s);
            e.setNoticedAt(T0);

            Recovery r = SpotInterruptionReadService.recoveryBounds(e);

            assertThat(r.status()).isEqualTo(s);
            assertThat(r.lowerSeconds()).as(s).isNull();
            assertThat(r.upperSeconds()).as(s).isNull();
            assertThat(r.recoveredByFirstSighting()).isFalse();
            assertThat(r.observedAt()).isNull();
        }
    }
}
