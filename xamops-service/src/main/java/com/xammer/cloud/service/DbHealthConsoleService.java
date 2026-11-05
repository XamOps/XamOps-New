package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DbAgentConfig;
import com.xammer.cloud.domain.DbAgentDataCache;
import com.xammer.cloud.domain.DbAgentMetricsHistory;
import com.xammer.cloud.dto.dbops.AgentInstanceMetricsDto;
import com.xammer.cloud.dto.dbops.AgentIndexUsageDto;
import com.xammer.cloud.dto.dbops.AgentLockWaitDto;
import com.xammer.cloud.dto.dbops.AgentQueryStatDto;
import com.xammer.cloud.dto.dbops.AgentReplicationDto;
import com.xammer.cloud.dto.dbops.AgentTableActivityDto;
import com.xammer.cloud.dto.dbops.AgentTableSizeDto;
import com.xammer.cloud.dto.dbops.AgentVacuumStatDto;
import com.xammer.cloud.dto.dbops.DbHealthView;
import com.xammer.cloud.dto.dbops.Finding;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pillar A — turns the agent's collected metrics for one DSN into a ranked list of actionable
 * {@link Finding}s plus a rolled-up {@link DbHealthView} status (§6.3). This is the thing that stops
 * the Health view being a raw metric dump.
 *
 * <p>The derivation core, {@link #build(HealthInputs)}, is pure (parsed DTOs in, view out) so it is
 * unit-testable without a database; {@link #buildForAccount} does the cache reading and calls it.
 * Thresholds live here as constants (one owner), and an empty input yields an explicit
 * {@code no_data} view — never a fabricated "100 — healthy" (the COR-1/COR-22 mistake).
 */
@Service
public class DbHealthConsoleService {

    private static final Logger logger = LoggerFactory.getLogger(DbHealthConsoleService.class);

    // ── Thresholds (one owner) ────────────────────────────────────────────────
    static final double SLOW_WARN_MS = 100.0;
    static final double SLOW_CRIT_MS = 500.0;
    static final long   MISSING_INDEX_SEQSCAN_FLOOR = 50;
    static final long   MISSING_INDEX_SIZE_FLOOR_BYTES = 10L * 1024 * 1024;   // 10 MB
    static final long   UNUSED_INDEX_SIZE_FLOOR_BYTES = 1L * 1024 * 1024;     // 1 MB
    static final int    LONGXACT_CRIT_MULTIPLE = 3;                            // critical at ≥ 3× threshold
    static final double CACHE_HIT_WARN = 0.99;
    static final double CONN_WARN_RATIO = 0.80;
    static final double CONN_CRIT_RATIO = 0.90;
    static final double REPL_LAG_WARN_SECS = 30.0;
    static final double REPL_LAG_CRIT_SECS = 300.0;
    static final double VACUUM_BLOAT_WARN_RATIO = 0.10;
    static final long   WRAPAROUND_WARN_AGE = 150_000_000L;                   // ~75% of default 200M
    static final long   WRAPAROUND_CRIT_AGE = 300_000_000L;
    /** Matches the agent's DB_LONGXACT_SECS default so the emit filter and classification agree. */
    static final int    DEFAULT_LONGXACT_SECS = 60;
    /** Per-cycle collector cadence; data older than 2× this is flagged stale (PERF-10). */
    static final int    DEFAULT_COLLECTOR_INTERVAL_SECS = 300;
    private static final int TOP_FINDINGS_PER_FAMILY = 5;

    private final DbAgentConfigRepository dbAgentConfigRepository;
    private final DbAgentDataCacheRepository dbAgentDataCacheRepository;
    private final DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository;
    private final ObjectMapper objectMapper;

    public DbHealthConsoleService(DbAgentConfigRepository dbAgentConfigRepository,
                                  DbAgentDataCacheRepository dbAgentDataCacheRepository,
                                  DbAgentMetricsHistoryRepository dbAgentMetricsHistoryRepository) {
        this.dbAgentConfigRepository = dbAgentConfigRepository;
        this.dbAgentDataCacheRepository = dbAgentDataCacheRepository;
        this.dbAgentMetricsHistoryRepository = dbAgentMetricsHistoryRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /** Parsed, DSN-filtered inputs for {@link #build(HealthInputs)}. */
    public record HealthInputs(
            String dsnAlias,
            String dbHost,
            List<String> availableDsnAliases,
            String collectedAt,
            boolean stale,
            List<AgentQueryStatDto> queryStats,
            List<AgentTableActivityDto> tableActivity,
            List<AgentTableSizeDto> tableSizes,
            List<AgentIndexUsageDto> indexUsage,
            List<AgentLockWaitDto> lockWaits,
            List<AgentReplicationDto> replication,
            List<AgentVacuumStatDto> vacuumStats,
            AgentInstanceMetricsDto latestMetrics,
            AgentInstanceMetricsDto previousMetrics,
            int historyRowCount,
            int longXactSecs) {
    }

    // ───────────────────────────── cache-reading entry point ─────────────────────────────

    /**
     * Resolve the account's agents, pick the DSN (the requested alias or the first sorted ascending),
     * assemble {@link HealthInputs} from the cached batches, and derive the view. Returns an explicit
     * {@code no_data} view when no agent has pushed anything.
     */
    public DbHealthView buildForAccount(CloudAccount account, String dsnAlias) {
        List<DbAgentConfig> agents = dbAgentConfigRepository.findByCloudAccountId(account.getId());

        // Combined, per-collector parsed rows across all agents for this account.
        List<AgentInstanceMetricsDto> allMetrics = new ArrayList<>();
        List<AgentQueryStatDto> queryStats = new ArrayList<>();
        List<AgentTableActivityDto> tableActivity = new ArrayList<>();
        List<AgentTableSizeDto> tableSizes = new ArrayList<>();
        List<AgentIndexUsageDto> indexUsage = new ArrayList<>();
        List<AgentLockWaitDto> lockWaits = new ArrayList<>();
        List<AgentReplicationDto> replication = new ArrayList<>();
        List<AgentVacuumStatDto> vacuumStats = new ArrayList<>();

        for (DbAgentConfig agent : agents) {
            allMetrics.addAll(readGeneric(agent, "db-instance-metrics", new TypeReference<>() {}));
            queryStats.addAll(readGeneric(agent, "db-query-stats", new TypeReference<>() {}));
            tableActivity.addAll(readGeneric(agent, "db-table-activity", new TypeReference<>() {}));
            tableSizes.addAll(readGeneric(agent, "db-table-sizes", new TypeReference<>() {}));
            indexUsage.addAll(readGeneric(agent, "db-index-usage", new TypeReference<>() {}));
            vacuumStats.addAll(readGeneric(agent, "db-vacuum-stats", new TypeReference<>() {}));
            lockWaits.addAll(this.<AgentLockWaitDto>readByPrefix(agent, "db-lock-waits:", new TypeReference<>() {}));
            replication.addAll(this.<AgentReplicationDto>readByPrefix(agent, "db-replication:", new TypeReference<>() {}));
        }

        // Alias inventory for the selector — ascending, from the metric/query feeds.
        TreeSet<String> aliasSet = new TreeSet<>();
        allMetrics.forEach(m -> addAlias(aliasSet, m.getDsnAlias()));
        queryStats.forEach(q -> addAlias(aliasSet, q.getDsnAlias()));
        lockWaits.forEach(l -> addAlias(aliasSet, l.getDsnAlias()));
        replication.forEach(r -> addAlias(aliasSet, r.getDsnAlias()));
        List<String> availableDsnAliases = new ArrayList<>(aliasSet);

        if (availableDsnAliases.isEmpty()) {
            return DbHealthView.empty("no_data"); // no agent data at all
        }

        String selected = (dsnAlias != null && aliasSet.contains(dsnAlias))
                ? dsnAlias : availableDsnAliases.get(0);

        // History (per agent) → the two latest db-instance-metrics entries for the selected DSN.
        AgentInstanceMetricsDto latest = null;
        AgentInstanceMetricsDto previous = null;
        int historyRowCount = 0;
        for (DbAgentConfig agent : agents) {
            List<DbAgentMetricsHistory> rows = dbAgentMetricsHistoryRepository
                    .findTop2ByDbAgentIdAndDataTypeOrderByCollectedAtDesc(agent.getId(), "db-instance-metrics");
            List<AgentInstanceMetricsDto> perRow = new ArrayList<>();
            for (DbAgentMetricsHistory row : rows) {
                AgentInstanceMetricsDto entry = pickDsn(parseList(row.getDataJson(),
                        new TypeReference<List<AgentInstanceMetricsDto>>() {}), selected);
                perRow.add(entry); // may be null if the DSN is absent from that row
            }
            if (perRow.stream().anyMatch(e -> e != null)) {
                historyRowCount = (int) perRow.stream().filter(e -> e != null).count();
                latest = perRow.size() > 0 ? perRow.get(0) : null;
                previous = perRow.size() > 1 ? perRow.get(1) : null;
                break;
            }
        }

        String dbHost = firstDbHost(allMetrics, selected);
        String collectedAt = latest != null ? latest.getCollectedAt() : null;
        boolean stale = isStale(collectedAt, DEFAULT_COLLECTOR_INTERVAL_SECS);

        HealthInputs in = new HealthInputs(selected, dbHost, availableDsnAliases, collectedAt, stale,
                filterByDsn(queryStats, AgentQueryStatDto::getDsnAlias, selected),
                filterByDsn(tableActivity, AgentTableActivityDto::getDsnAlias, selected),
                filterByDsn(tableSizes, AgentTableSizeDto::getDsnAlias, selected),
                filterByDsn(indexUsage, AgentIndexUsageDto::getDsnAlias, selected),
                filterByDsn(lockWaits, AgentLockWaitDto::getDsnAlias, selected),
                filterByDsn(replication, AgentReplicationDto::getDsnAlias, selected),
                filterByDsn(vacuumStats, AgentVacuumStatDto::getDsnAlias, selected),
                latest, previous, historyRowCount, DEFAULT_LONGXACT_SECS);
        return build(in);
    }

    // ───────────────────────────── pure derivation ─────────────────────────────

    public DbHealthView build(HealthInputs in) {
        List<Finding> findings = new ArrayList<>();

        addSlowQueryFinding(in, findings);
        addMissingIndexFindings(in, findings);
        addUnusedIndexFindings(in, findings);
        addLockFindings(in, findings);
        addReplicationFindings(in, findings);
        addVacuumFindings(in, findings);
        boolean haveTwoSamples = in.historyRowCount() >= 2 && in.latestMetrics() != null && in.previousMetrics() != null;
        if (in.latestMetrics() != null) {
            addConnectionFinding(in, findings);
            addWraparoundFinding(in, findings);
            addCacheHitFinding(in, findings);
            if (haveTwoSamples) {
                addDeltaFindings(in, findings);
            } else {
                findings.add(new Finding("collecting-deltas", "info", "deadlocks",
                        "Collecting — need a second sample",
                        "Rate-based findings (deadlocks, temp spill, checkpoint/WAL) need two agent samples; "
                                + in.historyRowCount() + " collected so far.",
                        "Wait for the next agent collection cycle."));
            }
        }

        findings.sort(Comparator.comparingInt((Finding f) -> severityRank(f.severity())).reversed());

        String status = rollupStatus(in, findings);
        return new DbHealthView(in.dsnAlias(), in.dbHost(), status, in.collectedAt(), in.stale(),
                in.availableDsnAliases() == null ? 0 : in.availableDsnAliases().size(),
                in.availableDsnAliases() == null ? List.of() : in.availableDsnAliases(),
                findings);
    }

    // ── Pillar A derivations ──────────────────────────────────────────────────

    private void addSlowQueryFinding(HealthInputs in, List<Finding> out) {
        List<AgentQueryStatDto> stats = in.queryStats();
        if (stats == null || stats.isEmpty()) return;

        long sumCalls = stats.stream().mapToLong(AgentQueryStatDto::getCallCount).sum();
        if (sumCalls <= 0) return;

        // Headline Avg Latency = call-weighted mean ACROSS digests (COR-21), not mean-of-means.
        double headlineMs = callWeightedAvgMs(stats);

        // Finding ranking = per-digest impact (mean × calls = that digest's total_exec_time).
        Optional<AgentQueryStatDto> topScrubbed = stats.stream()
                .filter(s -> admitDigestForFinding(s.getQueryDigest()))
                .max(Comparator.comparingDouble(AgentQueryStatDto::getTotalTimeSeconds));

        String severity;
        if (headlineMs >= SLOW_CRIT_MS) severity = "critical";
        else if (headlineMs >= SLOW_WARN_MS) severity = "warning";
        else severity = "info";

        String evidence = String.format(Locale.ROOT, "%.2f ms weighted avg over %,d calls across %d tracked digests",
                headlineMs, sumCalls, stats.size());
        // (ranking + evidence below name the top SCRUBBED digest only)
        if (topScrubbed.isPresent()) {
            AgentQueryStatDto t = topScrubbed.get();
            evidence += String.format(Locale.ROOT, "; top digest %.2f ms mean × %,d calls: %s",
                    t.getAvgTimeSeconds() * 1000.0, t.getCallCount(), truncate(t.getQueryDigest(), 120));
        }
        // Only emit as an actionable finding when the headline clears the warn floor; otherwise the
        // view simply shows an ok status with no slow-query card.
        if (!"info".equals(severity)) {
            out.add(new Finding("slow-queries", severity, "slowQueries",
                    "Slow queries", evidence,
                    "Review the highest-total-time digests; add indexes or reduce call volume."));
        }
    }

    private void addMissingIndexFindings(HealthInputs in, List<Finding> out) {
        if (in.tableActivity() == null) return;
        List<AgentTableActivityDto> ranked = new ArrayList<>(in.tableActivity());
        ranked.sort(Comparator.comparingLong((AgentTableActivityDto t) ->
                t.getSeqScan() * Math.max(1, tableSizeBytes(in, t.getSchemaName(), t.getTableName()))).reversed());
        int emitted = 0;
        for (AgentTableActivityDto t : ranked) {
            if (emitted >= TOP_FINDINGS_PER_FAMILY) break;
            long size = tableSizeBytes(in, t.getSchemaName(), t.getTableName());
            if (t.getSeqScan() >= MISSING_INDEX_SEQSCAN_FLOOR
                    && t.getSeqScan() > t.getIdxScan()
                    && size >= MISSING_INDEX_SIZE_FLOOR_BYTES) {
                String tbl = t.getSchemaName() + "." + t.getTableName();
                out.add(new Finding("missing-index:" + tbl, "warning", "missingIndex",
                        "Possible missing index",
                        String.format(Locale.ROOT, "%s: %,d sequential scans vs %,d index scans (%s)",
                                tbl, t.getSeqScan(), t.getIdxScan(), humanBytes(size)),
                        "A frequently seq-scanned large table may benefit from an index on the filtered columns."));
                emitted++;
            }
        }
    }

    private void addUnusedIndexFindings(HealthInputs in, List<Finding> out) {
        if (in.indexUsage() == null) return;
        List<AgentIndexUsageDto> ranked = new ArrayList<>(in.indexUsage());
        ranked.sort(Comparator.comparingLong(AgentIndexUsageDto::getIndexSizeBytes).reversed());
        int emitted = 0;
        for (AgentIndexUsageDto idx : ranked) {
            if (emitted >= TOP_FINDINGS_PER_FAMILY) break;
            if (idx.getIdxScan() == 0 && idx.getIndexSizeBytes() >= UNUSED_INDEX_SIZE_FLOOR_BYTES) {
                String tbl = idx.getSchemaName() + "." + idx.getTableName();
                out.add(new Finding("unused-index:" + tbl + "." + idx.getIndexName(), "info", "unusedIndex",
                        "Unused index",
                        String.format(Locale.ROOT, "%s on %s: 0 scans since reset, %s reclaimable",
                                idx.getIndexName(), tbl, humanBytes(idx.getIndexSizeBytes())),
                        "If this index is genuinely unused it can be dropped to reclaim space and speed writes."));
                emitted++;
            }
        }
    }

    private void addLockFindings(HealthInputs in, List<Finding> out) {
        if (in.lockWaits() == null) return;
        int longXact = in.longXactSecs() > 0 ? in.longXactSecs() : DEFAULT_LONGXACT_SECS;
        for (AgentLockWaitDto w : in.lockWaits()) {
            String sev;
            String title;
            switch (w.getCategory() == null ? "" : w.getCategory()) {
                case "blocked" -> { sev = "critical"; title = "Blocked session"; }
                case "blocking" -> { sev = "warning"; title = "Blocking session"; }
                case "idle_in_transaction" -> {
                    sev = w.getXactAgeSeconds() >= (long) longXact * LONGXACT_CRIT_MULTIPLE ? "critical" : "warning";
                    title = "Idle in transaction";
                }
                default -> { // long_running
                    sev = w.getXactAgeSeconds() >= (long) longXact * LONGXACT_CRIT_MULTIPLE ? "critical" : "warning";
                    title = "Long-running transaction";
                }
            }
            // Identity/wait/age only — NEVER query text (the queryId hash is shown if useful).
            String evidence = String.format(Locale.ROOT,
                    "pid %d, user %s, app %s, %s/%s, txn age %.0fs%s",
                    w.getPid(), orDash(w.getUsename()), orDash(w.getApplicationName()),
                    orDash(w.getWaitEventType()), orDash(w.getWaitEvent()), w.getXactAgeSeconds(),
                    (w.getQueryId() != null && !w.getQueryId().isEmpty()) ? (" (queryId " + w.getQueryId() + ")") : "");
            if ("blocked".equals(w.getCategory()) && w.getBlockedByPids() != null && !w.getBlockedByPids().isEmpty()) {
                evidence += ", blocked by pid(s) " + w.getBlockedByPids();
            }
            out.add(new Finding("locks:" + w.getPid(), sev, "locks", title, evidence,
                    "Investigate the holding session; consider a statement/lock timeout for this workload."));
        }
    }

    private void addReplicationFindings(HealthInputs in, List<Finding> out) {
        if (in.replication() == null || in.replication().isEmpty()) return;
        boolean anyStandby = false;
        for (AgentReplicationDto r : in.replication()) {
            Double lag = maxLag(r);
            if ("replica".equals(r.getRole()) || r.getClientAddr() != null && !r.getClientAddr().isEmpty()) {
                anyStandby = true;
            }
            if (lag != null) {
                anyStandby = true;
                String sev = lag >= REPL_LAG_CRIT_SECS ? "critical" : (lag >= REPL_LAG_WARN_SECS ? "warning" : "info");
                if (!"info".equals(sev)) {
                    out.add(new Finding("repl-lag:" + orDash(r.getRole()), sev, "replication",
                            "Replication lag",
                            String.format(Locale.ROOT, "%s lag %.1fs", orDash(r.getRole()), lag),
                            "Check standby apply throughput, network, and long-running queries on the replica."));
                }
            }
        }
        if (!anyStandby) {
            out.add(new Finding("repl-none", "info", "replication", "No standby",
                    "No replication standby is connected to this primary.",
                    "If high availability is expected, verify the standby is streaming."));
        }
    }

    private void addVacuumFindings(HealthInputs in, List<Finding> out) {
        if (in.vacuumStats() == null) return;
        List<AgentVacuumStatDto> ranked = new ArrayList<>(in.vacuumStats());
        ranked.sort(Comparator.comparingDouble(AgentVacuumStatDto::getDeadTupRatio).reversed());
        int emitted = 0;
        for (AgentVacuumStatDto v : ranked) {
            if (emitted >= TOP_FINDINGS_PER_FAMILY) break;
            if (v.getDeadTupRatio() >= VACUUM_BLOAT_WARN_RATIO) {
                String tbl = v.getSchemaName() + "." + v.getTableName();
                out.add(new Finding("vacuum-bloat:" + tbl, "warning", "vacuumBloat",
                        "Table bloat / autovacuum lag",
                        String.format(Locale.ROOT, "%s: %.0f%% dead tuples (%,d dead / %,d live)",
                                tbl, v.getDeadTupRatio() * 100.0, v.getNDeadTup(), v.getNLiveTup()),
                        "Check autovacuum settings for this table; a manual VACUUM may be needed."));
                emitted++;
            }
        }
    }

    private void addConnectionFinding(HealthInputs in, List<Finding> out) {
        AgentInstanceMetricsDto m = in.latestMetrics();
        if (m.getMaxConnections() <= 0) return;
        double ratio = (double) m.getCurrentConnections() / m.getMaxConnections();
        String sev = ratio >= CONN_CRIT_RATIO ? "critical" : (ratio >= CONN_WARN_RATIO ? "warning" : "info");
        if (!"info".equals(sev)) {
            out.add(new Finding("connections", sev, "connections", "Connections near limit",
                    String.format(Locale.ROOT, "%,d of %,d connections (%.0f%%)",
                            m.getCurrentConnections(), m.getMaxConnections(), ratio * 100.0),
                    "Add connection pooling (PgBouncer) or raise max_connections if the host can bear it."));
        }
    }

    private void addWraparoundFinding(HealthInputs in, List<Finding> out) {
        long age = in.latestMetrics().getDatfrozenxidAge();
        if (age < WRAPAROUND_WARN_AGE) return;
        String sev = age >= WRAPAROUND_CRIT_AGE ? "critical" : "warning";
        out.add(new Finding("wraparound", sev, "wraparound", "Transaction-ID wraparound risk",
                String.format(Locale.ROOT, "oldest datfrozenxid age is %,d transactions", age),
                "Ensure autovacuum is keeping up; a wraparound-prevention VACUUM may be required."));
    }

    private void addCacheHitFinding(HealthInputs in, List<Finding> out) {
        AgentInstanceMetricsDto m = in.latestMetrics();
        // cacheHitRatio of 0 with no block activity means "not yet observed", not a 0% cache — skip.
        if (m.getBlksHit() + m.getBlksRead() <= 0) return;
        if (m.getCacheHitRatio() < CACHE_HIT_WARN) {
            out.add(new Finding("cache-hit", "warning", "cacheHit", "Low cache-hit ratio",
                    String.format(Locale.ROOT, "%.2f%% of block reads served from shared buffers (instance-wide)",
                            m.getCacheHitRatio() * 100.0),
                    "Frequent disk reads — consider more shared_buffers/RAM or indexing to reduce reads."));
        }
    }

    private void addDeltaFindings(HealthInputs in, List<Finding> out) {
        AgentInstanceMetricsDto cur = in.latestMetrics();
        AgentInstanceMetricsDto prev = in.previousMetrics();

        long deadlockDelta = cur.getInnodbDeadlocks() - prev.getInnodbDeadlocks();
        if (deadlockDelta > 0) {
            out.add(new Finding("deadlocks", "warning", "deadlocks", "Deadlocks detected",
                    String.format(Locale.ROOT, "%,d deadlock(s) since the previous sample (current database)", deadlockDelta),
                    "Review the conflicting transactions; order writes consistently to avoid cyclic lock waits."));
        }
        long tempBytesDelta = cur.getTempBytes() - prev.getTempBytes();
        if (tempBytesDelta > 0) {
            out.add(new Finding("temp-spill", "info", "tempSpill", "Temp-file spill",
                    String.format(Locale.ROOT, "%s of temp files written since the previous sample", humanBytes(tempBytesDelta)),
                    "Large sorts/hashes are spilling to disk; consider raising work_mem for those queries."));
        }
        long reqDelta = cur.getCheckpointsReq() - prev.getCheckpointsReq();
        long timedDelta = cur.getCheckpointsTimed() - prev.getCheckpointsTimed();
        if (reqDelta > timedDelta && reqDelta > 0) {
            out.add(new Finding("checkpoint-wal", "warning", "checkpointWal", "Checkpoints forced by load",
                    String.format(Locale.ROOT, "%,d requested vs %,d timed checkpoints since the previous sample",
                            reqDelta, timedDelta),
                    "Raise max_wal_size so checkpoints are time-driven, not forced by WAL pressure."));
        }
    }

    // ── status rollup ──────────────────────────────────────────────────────────

    private String rollupStatus(HealthInputs in, List<Finding> findings) {
        boolean anyData = in.latestMetrics() != null
                || notEmpty(in.queryStats()) || notEmpty(in.tableActivity()) || notEmpty(in.lockWaits())
                || notEmpty(in.replication()) || notEmpty(in.vacuumStats()) || notEmpty(in.indexUsage());
        if (!anyData) return "no_data";

        int worst = 0;
        for (Finding f : findings) {
            worst = Math.max(worst, severityRank(f.severity()));
        }
        if (worst >= 3) return "critical";
        if (worst == 2) return "warning";
        // Only informational findings (or none). If we have a snapshot but cannot yet derive deltas,
        // surface the lifecycle "collecting" state rather than a bare ok.
        if (in.latestMetrics() != null && in.historyRowCount() < 2) return "collecting";
        return "ok";
    }

    private static int severityRank(String severity) {
        if (severity == null) return 0;
        return switch (severity) {
            case "critical" -> 3;
            case "warning" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    /**
     * Headline Avg Latency = call-weighted mean ACROSS all tracked digests —
     * {@code SUM(total_exec_time) / SUM(calls)} — the COR-21 fix. This is <em>not</em> the mean of
     * per-digest means (which is the bug), and it is identical to the shipped frontend
     * {@code computeQueryStatsKpis}, so the two surfaces agree.
     */
    static double callWeightedAvgMs(List<AgentQueryStatDto> stats) {
        double sumTotal = stats.stream().mapToDouble(AgentQueryStatDto::getTotalTimeSeconds).sum();
        long sumCalls = stats.stream().mapToLong(AgentQueryStatDto::getCallCount).sum();
        return sumCalls > 0 ? sumTotal / sumCalls * 1000.0 : 0.0;
    }

    // ── literal scrub (SEC-8 defensive filter) ──────────────────────────────────

    private static final java.util.Set<String> ADMIT_STARTS =
            java.util.Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "WITH");
    private static final Pattern PARAM_TOKEN = Pattern.compile("\\$\\d+");
    private static final Pattern BARE_NUMBER = Pattern.compile("(?<![\\w$.])\\d+(?:\\.\\d+)?");

    /**
     * Admit a digest into a slow-query finding only if it is a normalized DML/SELECT/WITH statement
     * that carries no residual literal. pg_stat_statements stores utility statements verbatim with
     * {@code track_utility=on}, so "normalized by pg_stat_statements" is not a sufficient guarantee:
     * reject utility statements, and reject any admitted candidate that still carries a quoted string
     * or a bare numeric constant (a correctly normalized digest has only {@code $n} parameters).
     */
    static boolean admitDigestForFinding(String digest) {
        if (digest == null) return false;
        String trimmed = digest.trim();
        if (trimmed.isEmpty()) return false;
        int i = 0;
        while (i < trimmed.length() && (Character.isLetter(trimmed.charAt(i)))) i++;
        String first = trimmed.substring(0, i).toUpperCase(Locale.ROOT);
        if (!ADMIT_STARTS.contains(first)) return false;
        String stripped = PARAM_TOKEN.matcher(trimmed).replaceAll(" ");
        if (stripped.indexOf('\'') >= 0) return false;            // residual string literal
        if (BARE_NUMBER.matcher(stripped).find()) return false;   // residual numeric literal
        return true;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private <T> List<T> readGeneric(DbAgentConfig agent, String dataType, TypeReference<List<T>> typeRef) {
        Optional<DbAgentDataCache> row = dbAgentDataCacheRepository
                .findByDbAgentIdAndDataType(agent.getId(), dataType);
        return row.map(c -> parseList(c.getDataJson(), typeRef)).orElseGet(List::of);
    }

    private <T> List<T> readByPrefix(DbAgentConfig agent, String prefix, TypeReference<List<T>> typeRef) {
        List<T> result = new ArrayList<>();
        for (DbAgentDataCache c : dbAgentDataCacheRepository.findByDbAgentId(agent.getId())) {
            if (c.getDataType() == null || !c.getDataType().startsWith(prefix) || c.getDataJson() == null) continue;
            result.addAll(parseList(c.getDataJson(), typeRef));
        }
        return result;
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<T> items = objectMapper.readValue(json, typeRef);
            return items != null ? items : List.of();
        } catch (Exception e) {
            logger.warn("[DbHealth] parse failed for {}: {}", typeRef.getType(), e.getMessage());
            return List.of();
        }
    }

    private static <T> List<T> filterByDsn(List<T> items, java.util.function.Function<T, String> alias, String selected) {
        List<T> out = new ArrayList<>();
        for (T item : items) {
            if (selected.equals(alias.apply(item))) out.add(item);
        }
        return out;
    }

    private static AgentInstanceMetricsDto pickDsn(List<AgentInstanceMetricsDto> list, String selected) {
        if (list == null) return null;
        return list.stream().filter(m -> selected.equals(m.getDsnAlias())).findFirst().orElse(null);
    }

    private static String firstDbHost(List<AgentInstanceMetricsDto> metrics, String selected) {
        return metrics.stream().filter(m -> selected.equals(m.getDsnAlias()))
                .map(AgentInstanceMetricsDto::getDbHost).filter(h -> h != null && !h.isEmpty())
                .findFirst().orElse(null);
    }

    private long tableSizeBytes(HealthInputs in, String schema, String table) {
        if (in.tableSizes() == null) return 0;
        return in.tableSizes().stream()
                .filter(s -> schema != null && schema.equals(s.getSchemaName()) && table != null && table.equals(s.getTableName()))
                .mapToLong(AgentTableSizeDto::getTotalSizeBytes).findFirst().orElse(0);
    }

    private static Double maxLag(AgentReplicationDto r) {
        Double m = null;
        for (Double v : new Double[]{r.getWriteLagSeconds(), r.getFlushLagSeconds(), r.getReplayLagSeconds()}) {
            if (v != null && (m == null || v > m)) m = v;
        }
        return m;
    }

    private static void addAlias(TreeSet<String> set, String alias) {
        if (alias != null && !alias.isEmpty()) set.add(alias);
    }

    private static boolean notEmpty(List<?> l) {
        return l != null && !l.isEmpty();
    }

    static boolean isStale(String collectedAt, int collectorIntervalSecs) {
        if (collectedAt == null || collectedAt.isEmpty()) return false;
        try {
            Instant t = Instant.parse(collectedAt);
            return Instant.now().isAfter(t.plusSeconds(2L * collectorIntervalSecs));
        } catch (Exception e) {
            return false;
        }
    }

    private static String orDash(String s) {
        return (s == null || s.isEmpty()) ? "-" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }
}
