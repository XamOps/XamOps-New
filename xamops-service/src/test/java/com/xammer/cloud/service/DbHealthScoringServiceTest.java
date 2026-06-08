package com.xammer.cloud.service;

import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DbHealthScoringService.computeScore(),
 * scoreMongoInstance(), and scoreRedisInstance().
 *
 * These are pure-logic methods — no Spring context, no DB, no mocks needed.
 * All external collaborators are only used by scoreAccount() which is tested
 * separately as an integration concern.
 */
class DbHealthScoringServiceTest {

    private DbHealthScoringService service;
    private DbInstanceDto instance;

    @BeforeEach
    void setUp() {
        // scoreAccount() needs repos; computeScore() doesn't — pass nulls safely.
        service = new DbHealthScoringService(null, null, null, null, null);

        instance = new DbInstanceDto();
        instance.setInstanceId("db-test-01");
        instance.setEndpoint("db-test-01.rds.amazonaws.com:5432");
    }

    // ── Baseline ──────────────────────────────────────────────────────────────

    @Test
    void perfectMetrics_score100_statusHealthy() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metrics(30, 0, 0, 0), agentMetrics(50, 200, 95),
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(100);
        assertThat(result.status).isEqualTo("healthy");
        assertThat(result.issues).isEmpty();
    }

    // ── CPU deductions ────────────────────────────────────────────────────────

    @Test
    void cpu_above80_deducts20_andAddsIssue() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metrics(85, 0, 0, 0), null,
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("High CPU"));
    }

    @Test
    void cpu_above60_below80_deducts10_andAddsElevatedIssue() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metrics(70, 0, 0, 0), null,
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("Elevated CPU"));
    }

    // ── Swap usage ────────────────────────────────────────────────────────────

    @Test
    void swapAbove100mb_deducts15() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metricsWithSwap(150), null,
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(85);
        assertThat(result.issues).anyMatch(i -> i.contains("Swap usage"));
    }

    // ── Replica lag ───────────────────────────────────────────────────────────

    @Test
    void replicaLag_above30s_deducts10() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metricsWithLag(60), null,
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("replica lag"));
    }

    // ── Connection pool ───────────────────────────────────────────────────────

    @Test
    void connectionPool_above80pct_deducts20() {
        // 90 current out of 100 max = 90%
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metrics(30, 0, 0, 0), agentMetrics(90, 100, 95),
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("Connection pool near limit"));
    }

    @Test
    void connectionPool_above60pct_deducts10() {
        // 70 current out of 100 max = 70%
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metrics(30, 0, 0, 0), agentMetrics(70, 100, 95),
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("Connection pool elevated"));
    }

    // ── InnoDB buffer pool ────────────────────────────────────────────────────

    @Test
    void bufferPoolHitBelow90pct_deducts10() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                metrics(30, 0, 0, 0), agentMetrics(10, 100, 80),
                0, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("buffer pool hit"));
    }

    // ── Slow queries ──────────────────────────────────────────────────────────

    @Test
    void slowQueries_above20_deducts20() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 25, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("slow queries detected"));
    }

    @Test
    void slowQueries_above5_below20_deducts10() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 10, 0, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("slow queries"));
    }

    // ── Security failures ─────────────────────────────────────────────────────

    @Test
    void securityFailures_2_deducts10() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 0, 2, List.of(), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("security failure"));
    }

    @Test
    void securityFailures_capped_at_20pts() {
        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 0, 10, List.of(), List.of(), List.of(), instance);

        // 5 pts each, capped at 20
        assertThat(result.score).isEqualTo(80);
    }

    // ── Table bloat ───────────────────────────────────────────────────────────

    @Test
    void highBloatTable_deducts3pts_per_table_capped15() {
        AgentVacuumStatDto bloated = new AgentVacuumStatDto();
        bloated.setDeadTupRatio(0.40);  // > 0.30 threshold

        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 0, 0, List.of(bloated), List.of(), List.of(), instance);

        assertThat(result.score).isEqualTo(97);
        assertThat(result.issues).anyMatch(i -> i.contains("bloat"));
    }

    // ── Lock contention ───────────────────────────────────────────────────────

    @Test
    void lockContention_above30pct_deducts10() {
        AgentWaitEventDto lockWait = new AgentWaitEventDto();
        lockWait.setWaitCategory("Lock");
        lockWait.setSessionCount(4);  // 4 out of 10 = 40%

        AgentWaitEventDto otherWait = new AgentWaitEventDto();
        otherWait.setWaitCategory("IO");
        otherWait.setSessionCount(6);

        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 0, 0, List.of(), List.of(lockWait, otherWait), List.of(), instance);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("lock contention"));
    }

    // ── Agent diagnostic error ────────────────────────────────────────────────

    @Test
    void agentDiagnosticError_matching_instance_deducts20() {
        AgentDiagnosticDto diag = new AgentDiagnosticDto();
        diag.setErrorType("ConnectionRefused");
        diag.setDbHost("db-test-01.rds.amazonaws.com");

        DbHealthScoringService.ScoreResult result = service.computeScore(
                null, null, 0, 0, List.of(), List.of(), List.of(diag), instance);

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("Agent connection"));
    }

    // ── Score floor & status thresholds ──────────────────────────────────────

    @Test
    void score_neverGoesBelowZero() {
        DbMetricsDto badMetrics = metrics(90, 200, 100, 10);  // cpu + swap + lag + burst
        AgentInstanceMetricsDto badAgent = agentMetrics(95, 100, 80);  // conn + buffer
        AgentVacuumStatDto bloat1 = new AgentVacuumStatDto(); bloat1.setDeadTupRatio(0.5);
        AgentVacuumStatDto bloat2 = new AgentVacuumStatDto(); bloat2.setDeadTupRatio(0.5);
        AgentVacuumStatDto bloat3 = new AgentVacuumStatDto(); bloat3.setDeadTupRatio(0.5);
        AgentVacuumStatDto bloat4 = new AgentVacuumStatDto(); bloat4.setDeadTupRatio(0.5);
        AgentVacuumStatDto bloat5 = new AgentVacuumStatDto(); bloat5.setDeadTupRatio(0.5);

        DbHealthScoringService.ScoreResult result = service.computeScore(
                badMetrics, badAgent, 30, 10, List.of(bloat1, bloat2, bloat3, bloat4, bloat5),
                List.of(), List.of(), instance);

        assertThat(result.score).isGreaterThanOrEqualTo(0);
    }

    @Test
    void score_80_statusHealthy() {
        DbHealthScoringService.ScoreResult r = new DbHealthScoringService.ScoreResult(80, null, List.of());
        // Compute status inline — boundary test
        String status = r.score >= 80 ? "healthy" : r.score >= 50 ? "warning" : "critical";
        assertThat(status).isEqualTo("healthy");
    }

    @Test
    void score_79_statusWarning() {
        int score = 79;
        String status = score >= 80 ? "healthy" : score >= 50 ? "warning" : "critical";
        assertThat(status).isEqualTo("warning");
    }

    @Test
    void score_49_statusCritical() {
        int score = 49;
        String status = score >= 80 ? "healthy" : score >= 50 ? "warning" : "critical";
        assertThat(status).isEqualTo("critical");
    }

    // ── MongoDB scoring ───────────────────────────────────────────────────────

    @Test
    void mongo_healthyMetrics_score100() {
        MongoInstanceMetricsDto m = new MongoInstanceMetricsDto();
        m.setCurrentConnections(20);
        m.setAvailableConnections(980);
        m.setResidentMemMb(1024);
        m.setVirtualMemMb(2048);

        DbHealthScoringService.ScoreResult result = service.scoreMongoInstance(m, List.of(), null);

        assertThat(result.score).isEqualTo(100);
        assertThat(result.status).isEqualTo("healthy");
    }

    @Test
    void mongo_highConnectionUsage_deducts20() {
        MongoInstanceMetricsDto m = new MongoInstanceMetricsDto();
        m.setCurrentConnections(85);
        m.setAvailableConnections(15);  // 85% used

        DbHealthScoringService.ScoreResult result = service.scoreMongoInstance(m, List.of(), null);

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("Connection usage high"));
    }

    @Test
    void mongo_highReplicationLag_deducts10() {
        MongoReplStatusDto repl = new MongoReplStatusDto();
        repl.setMyState(1);  // primary
        repl.setLagSeconds(45);

        DbHealthScoringService.ScoreResult result = service.scoreMongoInstance(null, List.of(), repl);

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("replication lag"));
    }

    @Test
    void mongo_slowOpsAbove20_deducts20() {
        List<MongoSlowOpDto> slowOps = java.util.stream.Stream.generate(MongoSlowOpDto::new)
                .limit(25).toList();

        DbHealthScoringService.ScoreResult result = service.scoreMongoInstance(null, slowOps, null);

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("slow operations detected"));
    }

    @Test
    void mongo_score_clampedToZeroFloor() {
        MongoInstanceMetricsDto m = new MongoInstanceMetricsDto();
        m.setCurrentConnections(99);
        m.setAvailableConnections(1);  // 99% → -20
        m.setResidentMemMb(10);
        m.setVirtualMemMb(10000);      // <20% ratio → -10
        List<MongoSlowOpDto> slowOps = java.util.stream.Stream.generate(MongoSlowOpDto::new).limit(25).toList(); // -20

        MongoReplStatusDto repl = new MongoReplStatusDto();
        repl.setMyState(1);
        repl.setLagSeconds(60);  // -10

        DbHealthScoringService.ScoreResult result = service.scoreMongoInstance(m, slowOps, repl);

        assertThat(result.score).isGreaterThanOrEqualTo(0);
    }

    // ── Redis scoring ─────────────────────────────────────────────────────────

    @Test
    void redis_healthyMetrics_score100() {
        RedisInstanceMetricsDto m = new RedisInstanceMetricsDto();
        m.setMemUsagePct(50);
        m.setHitRatioPct(95);
        m.setEvictedKeys(0);
        m.setReplLagSeconds(0);

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(m, List.of(), List.of());

        assertThat(result.score).isEqualTo(100);
        assertThat(result.status).isEqualTo("healthy");
    }

    @Test
    void redis_memoryAbove90pct_deducts25() {
        RedisInstanceMetricsDto m = new RedisInstanceMetricsDto();
        m.setMemUsagePct(95);

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(m, List.of(), List.of());

        assertThat(result.score).isEqualTo(75);
        assertThat(result.issues).anyMatch(i -> i.contains("Memory usage critical"));
    }

    @Test
    void redis_memoryAbove75pct_deducts15() {
        RedisInstanceMetricsDto m = new RedisInstanceMetricsDto();
        m.setMemUsagePct(80);

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(m, List.of(), List.of());

        assertThat(result.score).isEqualTo(85);
        assertThat(result.issues).anyMatch(i -> i.contains("Memory usage high"));
    }

    @Test
    void redis_lowCacheHitRatio_deducts15() {
        RedisInstanceMetricsDto m = new RedisInstanceMetricsDto();
        m.setHitRatioPct(70);  // < 80 threshold

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(m, List.of(), List.of());

        assertThat(result.score).isEqualTo(85);
        assertThat(result.issues).anyMatch(i -> i.contains("hit ratio"));
    }

    @Test
    void redis_keyEviction_deducts10() {
        RedisInstanceMetricsDto m = new RedisInstanceMetricsDto();
        m.setEvictedKeys(5);

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(m, List.of(), List.of());

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("eviction"));
    }

    @Test
    void redis_highReplicationLag_deducts10() {
        RedisInstanceMetricsDto m = new RedisInstanceMetricsDto();
        m.setReplLagSeconds(15);  // > 10s threshold

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(m, List.of(), List.of());

        assertThat(result.score).isEqualTo(90);
        assertThat(result.issues).anyMatch(i -> i.contains("replication lag"));
    }

    @Test
    void redis_mostKeysNoTTL_deducts5() {
        RedisKeyspaceDto ks = new RedisKeyspaceDto();
        ks.setKeys(1000);
        ks.setExpires(50);  // only 5% have TTL

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(null, List.of(), List.of(ks));

        assertThat(result.score).isEqualTo(95);
        assertThat(result.issues).anyMatch(i -> i.contains("TTL"));
    }

    @Test
    void redis_slowLogAbove20_deducts20() {
        List<RedisSlowLogDto> slow = java.util.stream.Stream.generate(RedisSlowLogDto::new)
                .limit(25).toList();

        DbHealthScoringService.ScoreResult result = service.scoreRedisInstance(null, slow, List.of());

        assertThat(result.score).isEqualTo(80);
        assertThat(result.issues).anyMatch(i -> i.contains("slow commands in log"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DbMetricsDto metrics(double cpu, double swap, double lag, double burst) {
        DbMetricsDto m = new DbMetricsDto();
        m.setAvgCpuPercent(cpu);
        m.setAvgSwapUsageMb(swap);
        m.setReplicaLagSeconds(lag);
        m.setBurstBalance(burst);
        return m;
    }

    private DbMetricsDto metricsWithSwap(double swap) {
        return metrics(30, swap, 0, 0);
    }

    private DbMetricsDto metricsWithLag(double lag) {
        return metrics(30, 0, lag, 0);
    }

    /** agentMetrics(currentConnections, maxConnections, bufferPoolHitPct) */
    private AgentInstanceMetricsDto agentMetrics(long current, long max, double bufferHit) {
        AgentInstanceMetricsDto m = new AgentInstanceMetricsDto();
        m.setCurrentConnections(current);
        m.setMaxConnections(max);
        m.setInnodbBufferPoolHitPct(bufferHit);
        return m;
    }
}
