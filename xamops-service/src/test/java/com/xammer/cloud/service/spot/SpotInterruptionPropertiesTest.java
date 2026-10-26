package com.xammer.cloud.service.spot;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SpotInterruptionPropertiesTest {

    @Test
    void defaults() {
        SpotInterruptionProperties p = SpotInterruptionProperties.defaults();
        assertThat(p.recordingEnabled()).isTrue();
        assertThat(p.retentionDays()).isEqualTo(365);
        assertThat(p.removalMaxGapMinutes()).isEqualTo(15);
        assertThat(p.recoveryWindowHours()).isEqualTo(6);
        assertThat(p.reportMaxAgeMinutes()).isEqualTo(10);
        assertThat(p.removalMaxGap()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.recoveryWindow()).isEqualTo(Duration.ofHours(6));
        assertThat(p.reportMaxAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.retention()).isEqualTo(Duration.ofDays(365));
    }

    @Test
    void valuesBelowTheRangeAreClampedUp() {
        SpotInterruptionProperties p = new SpotInterruptionProperties(false, 1, 1, 0, 1);
        assertThat(p.recordingEnabled()).isFalse();
        assertThat(p.retentionDays()).isEqualTo(30);
        assertThat(p.removalMaxGapMinutes()).isEqualTo(5);
        assertThat(p.recoveryWindowHours()).isEqualTo(1);
        assertThat(p.reportMaxAgeMinutes()).isEqualTo(3);
    }

    @Test
    void valuesAboveTheRangeAreClampedDown() {
        SpotInterruptionProperties p = new SpotInterruptionProperties(true, 5000, 600, 100, 999);
        assertThat(p.retentionDays()).isEqualTo(730);
        assertThat(p.removalMaxGapMinutes()).isEqualTo(60);
        assertThat(p.recoveryWindowHours()).isEqualTo(48);
        assertThat(p.reportMaxAgeMinutes()).isEqualTo(60);
    }

    @Test
    void valuesInsideTheRangeAreKept() {
        SpotInterruptionProperties p = new SpotInterruptionProperties(true, 90, 20, 12, 15);
        assertThat(p.retentionDays()).isEqualTo(90);
        assertThat(p.removalMaxGapMinutes()).isEqualTo(20);
        assertThat(p.recoveryWindowHours()).isEqualTo(12);
        assertThat(p.reportMaxAgeMinutes()).isEqualTo(15);
    }
}
