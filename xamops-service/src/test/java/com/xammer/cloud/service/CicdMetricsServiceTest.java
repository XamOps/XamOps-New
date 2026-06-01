package com.xammer.cloud.service;

import com.xammer.cloud.domain.CicdRunRecord;
import com.xammer.cloud.dto.cicd.CicdMetricsDto;
import com.xammer.cloud.repository.CicdRunRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class CicdMetricsServiceTest {

    @Mock
    private CicdRunRecordRepository repository;

    private CicdMetricsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CicdMetricsService(repository);
    }

    private CicdRunRecord run(String status, long durationSecs, Instant startedAt) {
        CicdRunRecord r = new CicdRunRecord();
        r.setProvider("GITHUB");
        r.setUserId(1L);
        r.setStatus(status);
        r.setDurationSeconds(durationSecs);
        r.setStartedAt(startedAt);
        r.setWorkflowName("ci");
        r.setRunId("r" + startedAt.toEpochMilli());
        return r;
    }

    @Test
    void successRate_calculatedCorrectly() {
        Instant now = Instant.now();
        List<CicdRunRecord> current = List.of(
            run("success", 100, now.minus(1, ChronoUnit.DAYS)),
            run("failure", 50, now.minus(2, ChronoUnit.DAYS)),
            run("success", 80, now.minus(3, ChronoUnit.DAYS))
        );
        when(repository.findByUserIdAndProviderAndStartedAtAfterOrderByStartedAtDesc(eq(1L), eq("GITHUB"), any()))
            .thenReturn(current, List.of());

        CicdMetricsDto metrics = service.computeMetrics(1L, "GITHUB", 7);

        assertThat(metrics.getSuccessRate()).isCloseTo(66.67, within(0.01));
        assertThat(metrics.getTotalRuns()).isEqualTo(3);
        assertThat(metrics.getFailureCount()).isEqualTo(1);
        assertThat(metrics.getAvgDurationSeconds()).isEqualTo(76); // (100+50+80)/3
    }

    @Test
    void successRate_withZeroRuns_returnsZero() {
        when(repository.findByUserIdAndProviderAndStartedAtAfterOrderByStartedAtDesc(any(), any(), any()))
            .thenReturn(List.of());

        CicdMetricsDto metrics = service.computeMetrics(1L, "GITHUB", 7);

        assertThat(metrics.getSuccessRate()).isEqualTo(0.0);
        assertThat(metrics.getTotalRuns()).isEqualTo(0);
    }

    @Test
    void mttr_calculatedCorrectly() {
        Instant base = Instant.parse("2026-05-20T10:00:00Z");
        CicdRunRecord failure = run("failure", 60, base);
        failure.setWorkflowName("deploy");
        CicdRunRecord recovery = run("success", 60, base.plus(30, ChronoUnit.MINUTES));
        recovery.setWorkflowName("deploy");

        long mttr = service.computeMttr(List.of(failure, recovery));
        assertThat(mttr).isEqualTo(1800L);
    }

    @Test
    void changeFailureRate_calculatedCorrectly() {
        Instant now = Instant.now();
        CicdRunRecord deploy1 = run("success", 100, now.minus(1, ChronoUnit.DAYS));
        deploy1.setDeployment(true);
        CicdRunRecord deploy2 = run("failure", 50, now.minus(2, ChronoUnit.DAYS));
        deploy2.setDeployment(true);
        CicdRunRecord noDeploy = run("success", 80, now.minus(3, ChronoUnit.DAYS));

        double cfr = service.computeChangeFailureRate(List.of(deploy1, deploy2, noDeploy));
        assertThat(cfr).isCloseTo(50.0, within(0.01));
    }
}
