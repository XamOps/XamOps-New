package com.xammer.cloud.service.automation.workload.rca;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.RcaResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read service: tenant-scoped, read-only, no write, no cluster call.
 */
class RcaReadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-cluster", "ap-south-1");

    private final WorkloadVerticalActionRepository repository =
            mock(WorkloadVerticalActionRepository.class);
    private final RcaReadService service = new RcaReadService(repository,
            new RcaProperties(24, 10, 2, 3, 2, 2, 3), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("rows are loaded scoped to the target's clusterConfigId only")
    void loadsRowsScopedByClusterConfigId() {
        when(repository.findByClusterConfigIdOrderByAppliedAtDesc(42L)).thenReturn(List.of());

        service.read(TARGET, 24);

        verify(repository).findByClusterConfigIdOrderByAppliedAtDesc(42L);
        // never reads any other scope, and never writes.
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an empty history is an honest empty state, not an empty-looking success")
    void emptyHistoryIsHonest() {
        when(repository.findByClusterConfigIdOrderByAppliedAtDesc(eq(42L))).thenReturn(List.of());

        RcaResponseDto response = service.read(TARGET, 24);

        assertThat(response.windowHours()).isEqualTo(24);
        assertThat(response.causes()).isEmpty();
        assertThat(response.analyzed().actionRows()).isZero();
        assertThat(response.analyzed().workloads()).isZero();
        assertThat(response.dependencies()).extracting(d -> d.kind())
                .containsExactlyInAnyOrder("OOM_EVICTION", "HPA_FLAP");
    }

    @Test
    @DisplayName("analyzed counts exclude POLICY_CHANGE and out-of-window rows, matching the analyzer")
    void analyzedCountsMatchTheFilteredHistory() {
        List<WorkloadVerticalAction> rows = new ArrayList<>();
        rows.add(row("api", "FAILED", NOW.minusSeconds(100)));
        rows.add(row("api", "FAILED", NOW.minusSeconds(200)));
        rows.add(row("api", "POLICY_CHANGE", NOW.minusSeconds(300)));     // admin, excluded
        rows.add(row("web", "APPLIED", NOW.minus(java.time.Duration.ofHours(48)))); // out of window
        when(repository.findByClusterConfigIdOrderByAppliedAtDesc(42L)).thenReturn(rows);

        RcaResponseDto response = service.read(TARGET, 24);

        assertThat(response.analyzed().actionRows()).isEqualTo(2);
        assertThat(response.analyzed().workloads()).isEqualTo(1);
        assertThat(response.causes()).isNotEmpty();
        assertThat(response.causes().get(0).kind()).isEqualTo("REPEATED_FAILED");
    }

    @Test
    @DisplayName("the clamped windowHours the controller passes drives the analysis window")
    void usesThePassedWindow() {
        when(repository.findByClusterConfigIdOrderByAppliedAtDesc(42L)).thenReturn(List.of());

        assertThat(service.read(TARGET, 1).windowHours()).isEqualTo(1);
        assertThat(service.read(TARGET, 168).windowHours()).isEqualTo(168);
    }

    private WorkloadVerticalAction row(String name, String outcome, Instant at) {
        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setClientId(7L);
        row.setClusterConfigId(42L);
        row.setNamespace("ns");
        row.setWorkloadKind("Deployment");
        row.setWorkloadName(name);
        row.setTriggerSource("MANUAL");
        row.setDryRun(false);
        row.setOutcome(outcome);
        row.setRestartFree(false);
        row.setAppliedAt(at);
        return row;
    }
}
