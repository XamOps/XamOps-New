package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the instance-store classification that blocks an automated stop.
 *
 * <p>Stopping an instance with local NVMe storage destroys everything on it, with no warning
 * and no recovery. A running instance's {@code blockDeviceMappings} lists only EBS volumes, so
 * the instance type is the signal available at enforcement time.
 */
class CloudsitterStopGuardTest {

    @Test
    @DisplayName("storage-optimised families are recognised as instance-store")
    void storageOptimisedFamilies() {
        assertThat(CloudsitterService.hasInstanceStoreVolumes("i3.large")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("i3en.2xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("i4i.xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("d2.xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("h1.2xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("x1e.xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("im4gn.large")).isTrue();
    }

    @Test
    @DisplayName("the d modifier denotes local NVMe on any family")
    void dModifierFamilies() {
        assertThat(CloudsitterService.hasInstanceStoreVolumes("m5d.xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("c5d.2xlarge")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("r5dn.large")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("m6gd.medium")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("c6gd.large")).isTrue();
    }

    @Test
    @DisplayName("EBS-only families are schedulable")
    void ebsOnlyFamiliesAreSchedulable() {
        assertThat(CloudsitterService.hasInstanceStoreVolumes("t3.micro")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("t2.small")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("m5.large")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("c5.xlarge")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("r6i.large")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("t4g.small")).isFalse();
    }

    @Test
    @DisplayName("a Graviton family without the d modifier is not instance-store")
    void gravitonWithoutDIsNotInstanceStore() {
        // The 'g' denotes Graviton, not disk — m6g must not be confused with m6gd.
        assertThat(CloudsitterService.hasInstanceStoreVolumes("m6g.large")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("c7g.xlarge")).isFalse();
    }

    @Test
    @DisplayName("malformed or missing input does not block scheduling")
    void malformedInputIsSafe() {
        assertThat(CloudsitterService.hasInstanceStoreVolumes(null)).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("")).isFalse();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("   ")).isFalse();
        // No family/size separator — cannot be classified, so do not block.
        assertThat(CloudsitterService.hasInstanceStoreVolumes("m5d")).isFalse();
    }

    @Test
    @DisplayName("case is normalised")
    void caseInsensitive() {
        assertThat(CloudsitterService.hasInstanceStoreVolumes("M5D.XLARGE")).isTrue();
        assertThat(CloudsitterService.hasInstanceStoreVolumes("I3.LARGE")).isTrue();
    }
}
