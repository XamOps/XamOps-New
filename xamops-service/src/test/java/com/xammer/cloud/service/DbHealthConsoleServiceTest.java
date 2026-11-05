package com.xammer.cloud.service;

import com.xammer.cloud.dto.dbops.AgentInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.AgentQueryStatDto;
import com.xammer.cloud.dto.dbops.DbHealthView;
import com.xammer.cloud.dto.dbops.Finding;
import com.xammer.cloud.service.DbHealthConsoleService.HealthInputs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * Pillar A derivation (§6.3). The core {@link DbHealthConsoleService#build(HealthInputs)} is pure, so
 * these feed fixtures directly: the headline is the call-weighted mean (COR-21), slow-query findings
 * rank by per-digest total time and are literal-scrubbed, delta findings come from two history
 * samples, and a single snapshot yields the "collecting" state, never a fabricated healthy score.
 */
class DbHealthConsoleServiceTest {

    private final DbHealthConsoleService service = new DbHealthConsoleService(
            mock(com.xammer.cloud.repository.DbAgentConfigRepository.class),
            mock(com.xammer.cloud.repository.DbAgentDataCacheRepository.class),
            mock(com.xammer.cloud.repository.DbAgentMetricsHistoryRepository.class));

    private AgentQueryStatDto q(String digest, long calls, double avgSec, double totalSec) {
        AgentQueryStatDto s = new AgentQueryStatDto();
        s.setDsnAlias("db-0");
        s.setDbEngine("postgres");
        s.setQueryDigest(digest);
        s.setCallCount(calls);
        s.setAvgTimeSeconds(avgSec);
        s.setTotalTimeSeconds(totalSec);
        return s;
    }

    private HealthInputs inputs(List<AgentQueryStatDto> queryStats,
                                AgentInstanceMetricsDto latest, AgentInstanceMetricsDto previous, int historyRowCount) {
        return new HealthInputs("db-0", "pg.internal", List.of("db-0"), "2026-10-29T12:00:00Z", false,
                queryStats, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                latest, previous, historyRowCount, 60);
    }

    @Test
    void headlineIsCallWeightedMeanNotMeanOfMeans() {
        // Two digests: a fast/frequent one and a slow/rare one. The call-weighted mean (14.93 ms)
        // is an order of magnitude below the mean-of-means (115.27 ms) — exactly the COR-21 bug.
        List<AgentQueryStatDto> stats = List.of(
                q("SELECT * FROM orders WHERE id = $1", 417, 0.010, 4.17),      // 10 ms mean
                q("SELECT * FROM audit WHERE ts > $1", 10, 0.22054, 2.2054));    // 220.54 ms mean

        double weighted = DbHealthConsoleService.callWeightedAvgMs(stats);
        double meanOfMeans = (10.0 + 220.54) / 2.0; // 115.27 ms — the wrong figure

        assertThat(weighted).isCloseTo(14.93, within(0.05));
        assertThat(weighted).isNotCloseTo(meanOfMeans, within(1.0));
    }

    @Test
    void slowQueryFindingRanksByTotalTimeAndScrubsLiterals() {
        // headline > 100 ms so the finding surfaces; the CREATE ROLE digest has the highest total
        // time but must be scrubbed out, so the named top digest is the SELECT.
        List<AgentQueryStatDto> stats = List.of(
                q("SELECT * FROM orders WHERE id = $1", 500, 0.30, 150.0),            // scrubbed-in, top total
                q("UPDATE t SET a = $1 WHERE id = $2", 100, 0.05, 5.0),
                q("CREATE ROLE app PASSWORD 'hunter2'", 10, 1.0, 10.0));              // must be scrubbed OUT

        DbHealthView view = service.build(inputs(stats, null, null, 0));

        Finding slow = view.findings().stream().filter(f -> "slowQueries".equals(f.metric())).findFirst().orElseThrow();
        assertThat(slow.evidence()).contains("orders");
        assertThat(slow.evidence()).doesNotContain("hunter2").doesNotContain("CREATE ROLE");
    }

    @Test
    void literalScrubRejectsUtilityAndResidualLiterals() {
        assertThat(DbHealthConsoleService.admitDigestForFinding("SELECT * FROM orders WHERE id = $1")).isTrue();
        assertThat(DbHealthConsoleService.admitDigestForFinding("WITH t AS (SELECT $1) SELECT * FROM t")).isTrue();
        assertThat(DbHealthConsoleService.admitDigestForFinding("CREATE ROLE app PASSWORD 'hunter2'")).isFalse();
        assertThat(DbHealthConsoleService.admitDigestForFinding("SELECT * FROM t WHERE name = 'bob'")).isFalse();
        assertThat(DbHealthConsoleService.admitDigestForFinding("UPDATE accounts SET balance = 500 WHERE id = $1")).isFalse();
        assertThat(DbHealthConsoleService.admitDigestForFinding("SET work_mem = '256MB'")).isFalse();
    }

    @Test
    void deltaFindingsComeFromTwoHistorySamples() {
        AgentInstanceMetricsDto prev = new AgentInstanceMetricsDto();
        prev.setInnodbDeadlocks(2);
        prev.setTempBytes(1024);
        prev.setCheckpointsReq(4);
        prev.setCheckpointsTimed(2);
        AgentInstanceMetricsDto latest = new AgentInstanceMetricsDto();
        latest.setInnodbDeadlocks(5);       // +3 deadlocks
        latest.setTempBytes(2048);           // +1024 temp bytes
        latest.setCheckpointsReq(10);        // +6 requested
        latest.setCheckpointsTimed(2);       // +0 timed → forced checkpoints finding

        DbHealthView view = service.build(inputs(List.of(), latest, prev, 2));

        assertThat(view.findings()).anyMatch(f -> "deadlocks".equals(f.metric()));
        assertThat(view.findings()).anyMatch(f -> "tempSpill".equals(f.metric()));
        assertThat(view.findings()).anyMatch(f -> "checkpointWal".equals(f.metric()));
        assertThat(view.findings()).noneMatch(f -> "collecting-deltas".equals(f.id()));
    }

    @Test
    void singleSnapshotYieldsCollectingState() {
        AgentInstanceMetricsDto latest = new AgentInstanceMetricsDto(); // one snapshot, no previous
        DbHealthView view = service.build(inputs(List.of(), latest, null, 1));

        assertThat(view.status()).isEqualTo("collecting");
        assertThat(view.findings()).anyMatch(f -> "collecting-deltas".equals(f.id()));
    }

    @Test
    void emptyInputYieldsNoDataNotFabricatedHealthy() {
        DbHealthView view = service.build(inputs(List.of(), null, null, 0));
        assertThat(view.status()).isEqualTo("no_data");
        assertThat(view.findings()).isEmpty();
    }
}
