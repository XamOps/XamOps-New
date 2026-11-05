package com.xammer.cloud.autospotting.core;

import com.xammer.cloud.domain.autospotting.DriftStatus;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.core.GroupGaugeBinder;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link GroupGaugeBinder} reads {@code spot_group} counts behind a 60 s cache and binds
 * {@code autospotting.groups} / {@code autospotting.groups.drift} through {@link AutoSpottingMetrics}
 * (design-ux-ops.md §6.2, plan-phase-10.md task 10.1). This exercises the binder with a mocked
 * repository: the gauges report the per-state count and the state-agnostic MATERIAL drift count.
 */
class GroupGaugeBinderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AutoSpottingMetrics metrics = new AutoSpottingMetrics(registry);
    private final SpotGroupRepository groups = mock(SpotGroupRepository.class);

    @Test
    void groupStateAndDriftGaugesReportTheCachedCounts() {
        SpotGroup active1 = group(GroupState.ACTIVE, DriftStatus.MATERIAL);
        SpotGroup active2 = group(GroupState.ACTIVE, DriftStatus.NONE);
        SpotGroup degraded = group(GroupState.DEGRADED, DriftStatus.MINOR);
        when(groups.findByProviderAndState(Provider.AWS, GroupState.ACTIVE))
                .thenReturn(List.of(active1, active2));
        when(groups.findByProviderAndState(Provider.AWS, GroupState.DEGRADED))
                .thenReturn(List.of(degraded));

        GroupGaugeBinder binder = new GroupGaugeBinder(groups, metrics);
        ReflectionTestUtils.invokeMethod(binder, "bind");

        assertThat(gauge(AutoSpottingMetrics.GROUPS, "provider", "aws", "state", "ACTIVE")).isEqualTo(2.0);
        assertThat(gauge(AutoSpottingMetrics.GROUPS, "provider", "aws", "state", "DEGRADED")).isEqualTo(1.0);
        assertThat(gauge(AutoSpottingMetrics.GROUPS, "provider", "aws", "state", "FAILED")).isEqualTo(0.0);
        // MATERIAL drift: one group (active1). MINOR drift: one group (degraded).
        assertThat(gauge(AutoSpottingMetrics.GROUPS_DRIFT, "provider", "aws", "severity", "MATERIAL"))
                .isEqualTo(1.0);
        assertThat(gauge(AutoSpottingMetrics.GROUPS_DRIFT, "provider", "aws", "severity", "MINOR"))
                .isEqualTo(1.0);
        // GCP has no rows; every GCP cell reads 0.
        assertThat(gauge(AutoSpottingMetrics.GROUPS, "provider", "gcp", "state", "ACTIVE")).isEqualTo(0.0);
    }

    private static SpotGroup group(GroupState state, DriftStatus drift) {
        SpotGroup g = mock(SpotGroup.class);
        when(g.getDriftStatus()).thenReturn(drift);
        return g;
    }

    private double gauge(String name, String... tagKv) {
        var search = registry.find(name);
        for (int i = 0; i < tagKv.length; i += 2) {
            search = search.tag(tagKv[i], tagKv[i + 1]);
        }
        var g = search.gauge();
        return g == null ? Double.NaN : g.value();
    }
}
