package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.DbHealthSnapshot;
import com.xammer.cloud.dto.dbops.*;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbHealthSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class DbHealthScoringService {

    private static final Logger logger = LoggerFactory.getLogger(DbHealthScoringService.class);

    private final DbOpsCostService           dbOpsCostService;
    private final DbHealthSnapshotRepository snapshotRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final ObjectMapper               objectMapper;

    public DbHealthScoringService(DbOpsCostService dbOpsCostService,
                                  DbHealthSnapshotRepository snapshotRepository,
                                  DbAgentDataCacheRepository dbAgentDataCacheRepository,
                                  ObjectMapper objectMapper) {
        this.dbOpsCostService           = dbOpsCostService;
        this.snapshotRepository         = snapshotRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.objectMapper               = objectMapper;
    }

    @Transactional
    public List<DbHealthSnapshot> scoreAccount(CloudAccount account) {
        String accountId = String.valueOf(account.getId());
        List<DbHealthSnapshot> saved = new ArrayList<>();
        try {
            List<DbInstanceDto> inventory = dbOpsCostService
                .getDbInventory(accountId, false).get(10, TimeUnit.SECONDS);
            if (inventory == null || inventory.isEmpty()) return saved;

            Map<String, DbMetricsDto> metrics = dbOpsCostService
                .getDbMetrics(accountId, false).get(10, TimeUnit.SECONDS);

            List<DbSecurityCheckDto> security = dbOpsCostService
                .getSecurityChecks(accountId, false).get(10, TimeUnit.SECONDS);

            SlowQueriesResponseDto slowResp = dbOpsCostService
                .getSlowQueries(accountId, false).get(10, TimeUnit.SECONDS);
            int totalSlowQueries = slowResp != null && slowResp.getQueries() != null
                ? slowResp.getQueries().size() : 0;

            for (DbInstanceDto instance : inventory) {
                DbMetricsDto m = metrics != null ? metrics.get(instance.getInstanceId()) : null;
                AgentInstanceMetricsDto agentMetrics = resolveAgentMetrics(account.getId(), instance);
                List<AgentVacuumStatDto> vacuumStats = resolveVacuumStats(account.getId(), instance);
                List<AgentWaitEventDto> waitEvents   = resolveWaitEvents(account.getId(), instance);
                List<AgentDiagnosticDto> diagnostics  = resolveDiagnostics(account.getId());

                int failedSecurity = security == null ? 0 : (int) security.stream()
                    .filter(s -> s.getInstanceId().equals(instance.getInstanceId()) && isFailed(s))
                    .count();

                ScoreResult result = computeScore(m, agentMetrics, totalSlowQueries,
                    failedSecurity, vacuumStats, waitEvents, diagnostics, instance);

                DbHealthSnapshot snap = new DbHealthSnapshot();
                snap.setAccountId(account.getId());
                snap.setInstanceId(instance.getInstanceId());
                snap.setScore(result.score);
                snap.setStatus(result.status);
                try { snap.setIssuesJson(objectMapper.writeValueAsString(result.issues)); }
                catch (Exception e) { snap.setIssuesJson("[]"); }
                snap.setComputedAt(LocalDateTime.now());
                saved.add(snapshotRepository.save(snap));
                logger.info("DbHealthScoring: {} scored {}/{} issues={}", instance.getInstanceId(), result.score, result.status, result.issues.size());
            }
        } catch (Exception e) {
            logger.warn("DbHealthScoring: failed for account {}: {}", accountId, e.getMessage());
        }
        return saved;
    }

    @Transactional
    public void purgeOldSnapshots() {
        int deleted = snapshotRepository.deleteOlderThan(LocalDateTime.now().minusDays(7));
        if (deleted > 0) logger.info("DbHealthScoring: purged {} snapshots older than 7d", deleted);
    }

    // ── Scoring algorithm ─────────────────────────────────────────────────────

    static class ScoreResult {
        int score; String status; List<String> issues;
        ScoreResult(int s, String st, List<String> i) { score = s; status = st; issues = i; }
    }

    ScoreResult computeScore(DbMetricsDto m, AgentInstanceMetricsDto am,
            int slowQueriesCount, int failedSecurityChecks,
            List<AgentVacuumStatDto> vacuumStats, List<AgentWaitEventDto> waitEvents,
            List<AgentDiagnosticDto> diagnostics, DbInstanceDto instance) {

        int score = 100;
        List<String> issues = new ArrayList<>();

        if (m != null) {
            double cpu = m.getAvgCpuPercent();
            if (cpu > 80)       { score -= 20; issues.add("High CPU (" + Math.round(cpu) + "%)"); }
            else if (cpu > 60)  { score -= 10; issues.add("Elevated CPU (" + Math.round(cpu) + "%)"); }
            if (m.getAvgSwapUsageMb() > 100) { score -= 15; issues.add("Swap usage detected"); }
            if (m.getReplicaLagSeconds() > 30) { score -= 10; issues.add("High replica lag"); }
            double burst = m.getBurstBalance();
            if (burst > 0 && burst < 20) { score -= 10; issues.add("Low burst balance"); }
        }
        if (am != null) {
            long maxConn = am.getMaxConnections();
            double connPct = maxConn > 0 ? (double) am.getCurrentConnections() / maxConn * 100 : 0;
            if (connPct > 80)      { score -= 20; issues.add("Connection pool near limit (" + Math.round(connPct) + "%)"); }
            else if (connPct > 60) { score -= 10; issues.add("Connection pool elevated (" + Math.round(connPct) + "%)"); }
            double hitPct = am.getInnodbBufferPoolHitPct();
            if (hitPct > 0 && hitPct < 90) { score -= 10; issues.add("Low buffer pool hit (" + Math.round(hitPct) + "%)"); }
        }
        if (slowQueriesCount > 20)     { score -= 20; issues.add(slowQueriesCount + " slow queries detected"); }
        else if (slowQueriesCount > 5) { score -= 10; issues.add(slowQueriesCount + " slow queries"); }
        if (failedSecurityChecks > 0) {
            score -= Math.min(failedSecurityChecks * 5, 20);
            issues.add(failedSecurityChecks + " security failure" + (failedSecurityChecks > 1 ? "s" : ""));
        }
        long highBloat = vacuumStats.stream().filter(v -> v.getDeadTupRatio() > 0.30).count();
        if (highBloat > 0) {
            score -= (int) Math.min(highBloat * 3, 15);
            issues.add(highBloat + " table" + (highBloat > 1 ? "s" : "") + " with high bloat");
        }
        int totalSessions = waitEvents.stream().mapToInt(AgentWaitEventDto::getSessionCount).sum();
        int lockSessions  = waitEvents.stream()
            .filter(w -> "Lock".equals(w.getWaitCategory()))
            .mapToInt(AgentWaitEventDto::getSessionCount).sum();
        if (totalSessions > 0 && (double) lockSessions / totalSessions > 0.30) {
            score -= 10;
            issues.add("High lock contention (" + Math.round((double) lockSessions / totalSessions * 100) + "% waits)");
        }
        boolean hasDiagError = diagnostics.stream()
            .anyMatch(d -> d.getErrorType() != null && !d.getErrorType().isEmpty()
                && matchesInstance(d.getDbHost(), instance));
        if (hasDiagError) { score -= 20; issues.add("Agent connection or privilege error"); }

        score = Math.max(0, Math.min(100, score));
        String status = score >= 80 ? "healthy" : score >= 50 ? "warning" : "critical";
        return new ScoreResult(score, status, issues);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isFailed(DbSecurityCheckDto s) {
        // DbSecurityCheckDto uses status field: "PASS", "FAIL", "WARN"
        return "FAIL".equals(s.getStatus());
    }

    private boolean matchesInstance(String dbHost, DbInstanceDto instance) {
        if (dbHost == null) return false;
        if (instance.getEndpoint() != null && instance.getEndpoint().contains(dbHost.split(":")[0])) return true;
        return dbHost.contains(instance.getInstanceId());
    }

    // ── Cache readers ─────────────────────────────────────────────────────────

    private AgentInstanceMetricsDto resolveAgentMetrics(Long accountId, DbInstanceDto instance) {
        try {
            for (DbAgentDataCache cache : dbAgentDataCacheRepository
                    .findByDataTypeStartingWithAndAgentConfig_CloudAccount_Id("db-instance-metrics:", accountId)) {
                if (cache.getDataJson() == null) continue;
                for (AgentInstanceMetricsDto am : objectMapper.readValue(
                        cache.getDataJson(), new TypeReference<List<AgentInstanceMetricsDto>>() {})) {
                    if (am.getDbHost() != null && instance.getEndpoint() != null
                            && instance.getEndpoint().contains(am.getDbHost().split(":")[0]))
                        return am;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<AgentVacuumStatDto> resolveVacuumStats(Long accountId, DbInstanceDto instance) {
        List<AgentVacuumStatDto> result = new ArrayList<>();
        try {
            for (DbAgentDataCache cache : dbAgentDataCacheRepository
                    .findByDataTypeStartingWithAndAgentConfig_CloudAccount_Id("db-vacuum-stats:", accountId)) {
                if (cache.getDataJson() == null) continue;
                objectMapper.readValue(cache.getDataJson(), new TypeReference<List<AgentVacuumStatDto>>() {})
                    .stream().filter(v -> v.getDbHost() != null && instance.getEndpoint() != null
                        && instance.getEndpoint().contains(v.getDbHost().split(":")[0]))
                    .forEach(result::add);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private List<AgentWaitEventDto> resolveWaitEvents(Long accountId, DbInstanceDto instance) {
        List<AgentWaitEventDto> result = new ArrayList<>();
        try {
            for (DbAgentDataCache cache : dbAgentDataCacheRepository
                    .findByDataTypeStartingWithAndAgentConfig_CloudAccount_Id("db-wait-events:", accountId)) {
                if (cache.getDataJson() == null) continue;
                objectMapper.readValue(cache.getDataJson(), new TypeReference<List<AgentWaitEventDto>>() {})
                    .stream().filter(w -> w.getDbHost() != null && instance.getEndpoint() != null
                        && instance.getEndpoint().contains(w.getDbHost().split(":")[0]))
                    .forEach(result::add);
            }
        } catch (Exception ignored) {}
        return result;
    }

    List<AgentDiagnosticDto> resolveDiagnostics(Long accountId) {
        List<AgentDiagnosticDto> result = new ArrayList<>();
        try {
            for (DbAgentDataCache cache : dbAgentDataCacheRepository
                    .findByDataTypeStartingWithAndAgentConfig_CloudAccount_Id("db-diagnostics:", accountId)) {
                if (cache.getDataJson() == null) continue;
                result.addAll(objectMapper.readValue(cache.getDataJson(), new TypeReference<List<AgentDiagnosticDto>>() {}));
            }
        } catch (Exception ignored) {}
        return result;
    }
}
