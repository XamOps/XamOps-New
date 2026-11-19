package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.aws.events.AwsEventStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AC-EVT-04: only COVERED passes; MISSING, SKIPPED, UNKNOWN and an unreadable table all block. */
class AwsCoverageJobTest {

    private static CloudAccount account() {
        CloudAccount a = new CloudAccount();
        a.setId(5L);
        return a;
    }

    private static AwsRegionCoverage with(String status) {
        AwsEventStore store = mock(AwsEventStore.class);
        when(store.coverageStatus(5L, "ap-south-1")).thenReturn(Optional.ofNullable(status));
        return new AwsRegionCoverage(store);
    }

    @Test
    void onlyCoveredPasses() {
        assertThat(with("COVERED").covered(account(), "ap-south-1")).isTrue();
        assertThat(with("MISSING").covered(account(), "ap-south-1")).isFalse();
        assertThat(with("SKIPPED").covered(account(), "ap-south-1")).isFalse();
        assertThat(with("UNKNOWN").covered(account(), "ap-south-1")).isFalse();
        assertThat(with(null).covered(account(), "ap-south-1")).isFalse();
    }

    @Test
    void anUnreadableTableFailsClosed() {
        AwsEventStore store = mock(AwsEventStore.class);
        when(store.coverageStatus(5L, "ap-south-1")).thenThrow(new IllegalStateException("db down"));
        assertThat(new AwsRegionCoverage(store).covered(account(), "ap-south-1")).isFalse();
        assertThat(new AwsRegionCoverage().covered(account(), "ap-south-1")).isFalse();
    }

    @Test
    void stackOutputsSplitIntoRegions() {
        assertThat(AwsCoverageJob.split("ap-south-1, us-east-1,us-east-1")).containsExactly("ap-south-1", "us-east-1");
        assertThat(AwsCoverageJob.split("none")).isEmpty();
        assertThat(AwsCoverageJob.split(null)).isEmpty();
        assertThat(AwsCoverageJob.split("bad region,eu-west-2")).isEqualTo(List.of("eu-west-2"));
    }
}
