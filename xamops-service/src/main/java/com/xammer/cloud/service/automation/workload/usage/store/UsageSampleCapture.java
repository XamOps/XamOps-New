package com.xammer.cloud.service.automation.workload.usage.store;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Captures usage samples from each agent {@code k8s-resources} snapshot, off the ingest path.
 *
 * <p>{@link #submit} is called from inside the ingest transaction. It only schedules: the work is
 * enqueued on the single-thread {@code usageRollupExecutor} after the ingest commits (or at once when
 * there is no transaction), and {@code submit} never throws. Everything that runs later is caught,
 * logged at WARN with the cluster config id only, and counted, so a broken rollup can never fail or
 * slow an agent push.
 *
 * <p>The queue holds {@link K8sSnapshotUsageExtractor#compact} of each snapshot (the few fields the
 * extractor reads), not the whole snapshot, so a backlog stays small. The compact copy is made after
 * the commit, outside the ingest transaction.
 *
 * <p>A snapshot whose agent {@code collectedAt} was already captured for its cluster (the agent
 * re-sends the same body when a push response is lost) is skipped, whatever {@code sampled_at} it
 * would get; in-window replays also dedupe on the sample primary key.
 *
 * <h2>Counters</h2>
 * <ul>
 *   <li>{@value #SAMPLES_WRITTEN} — sample rows newly inserted</li>
 *   <li>{@value #DROPPED} — snapshots rejected because the queue was full</li>
 *   <li>{@value #ERRORS}{@code {stage=enqueue|capture}} — failures while scheduling / processing</li>
 * </ul>
 */
@Service
public class UsageSampleCapture {

    private static final Logger logger = LoggerFactory.getLogger(UsageSampleCapture.class);

    public static final String SAMPLES_WRITTEN = "xamops.k8s.usage_rollup.samples_written";
    public static final String DROPPED = "xamops.k8s.usage_rollup.dropped";
    public static final String ERRORS = "xamops.k8s.usage_rollup.errors";

    /** Accept the agent's {@code collectedAt} only this far behind / ahead of server receive time. */
    static final Duration MAX_COLLECTED_AT_LAG = Duration.ofMinutes(15);
    static final Duration MAX_COLLECTED_AT_LEAD = Duration.ofMinutes(2);
    static final Duration CLIENT_CACHE_TTL = Duration.ofMinutes(10);
    static final Duration DROP_WARN_INTERVAL = Duration.ofMinutes(1);
    /** {@code collectedAt} values remembered per cluster for replay dedupe: ~80 min at 300 s pushes. */
    static final int RECENT_SNAPSHOTS_PER_CLUSTER = 16;

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final UsageRollupProperties properties;
    private final UsageRollupStore store;
    private final TaskExecutor executor;
    private final Clock clock;
    private final Counter samplesWritten;
    private final Counter dropped;
    private final Counter enqueueErrors;
    private final Counter captureErrors;

    private record CachedClient(long clientId, Instant expiresAt) {
    }

    private final Map<Long, CachedClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, Deque<LocalDateTime>> recentSnapshots = new ConcurrentHashMap<>();
    private final AtomicLong nextDropWarnAtMillis = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public UsageSampleCapture(UsageRollupProperties properties, UsageRollupStore store,
                              @Qualifier(UsageRollupConfig.EXECUTOR_BEAN) TaskExecutor executor,
                              ObjectProvider<MeterRegistry> meterRegistry) {
        this(properties, store, executor, meterRegistry.getIfAvailable(), Clock.systemUTC());
    }

    /** For tests: a pinned clock and an explicit (nullable) registry. */
    UsageSampleCapture(UsageRollupProperties properties, UsageRollupStore store, TaskExecutor executor,
                       MeterRegistry meterRegistry, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.executor = executor;
        this.clock = clock;
        // No registry (a context without actuator): count locally so the code path is the same.
        MeterRegistry registry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
        this.samplesWritten = counter(registry, SAMPLES_WRITTEN, null);
        this.dropped = counter(registry, DROPPED, null);
        this.enqueueErrors = counter(registry, ERRORS, "enqueue");
        this.captureErrors = counter(registry, ERRORS, "capture");
    }

    /**
     * Schedules capture of one snapshot. Returns immediately when the feature is off, an argument is
     * missing, or the item is not a JSON object. Never throws.
     *
     * @param clusterConfigId  {@code kubernetes_cluster_config.id}
     * @param cloudAccountId   {@code kubernetes_cluster_config.cloud_account_id}; required, so a
     *                         half-registered cluster is not captured
     * @param k8sResourcesItem {@code batch.getItems().get(0)} of the {@code k8s-resources} batch
     * @param batchCollectedAt the agent's {@code collectedAt} (agent-side UTC)
     */
    public void submit(Long clusterConfigId, Long cloudAccountId, Object k8sResourcesItem,
                       LocalDateTime batchCollectedAt) {
        try {
            if (!properties.enabled() || clusterConfigId == null || cloudAccountId == null
                    || batchCollectedAt == null || !(k8sResourcesItem instanceof Map<?, ?> raw)) {
                return;
            }
            Instant receivedAt = clock.instant();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        enqueue(clusterConfigId, raw, batchCollectedAt, receivedAt);
                    }
                });
            } else {
                enqueue(clusterConfigId, raw, batchCollectedAt, receivedAt);
            }
        } catch (RuntimeException e) {
            enqueueFailed(clusterConfigId, e);
        }
    }

    private void enqueue(Long clusterConfigId, Map<?, ?> raw, LocalDateTime batchCollectedAt, Instant receivedAt) {
        try {
            Map<String, Object> snapshot = K8sSnapshotUsageExtractor.compact(raw);
            executor.execute(() -> process(clusterConfigId, snapshot, batchCollectedAt, receivedAt));
        } catch (TaskRejectedException e) {
            dropped.increment();
            long now = clock.millis();
            long next = nextDropWarnAtMillis.get();
            if (now >= next && nextDropWarnAtMillis.compareAndSet(next, now + DROP_WARN_INTERVAL.toMillis())) {
                logger.warn("Usage rollup queue full: dropped a snapshot of cluster config {} "
                        + "(further drops in the next minute are only counted in {})", clusterConfigId, DROPPED);
            }
        } catch (RuntimeException e) {
            enqueueFailed(clusterConfigId, e);
        }
    }

    private void enqueueFailed(Long clusterConfigId, RuntimeException e) {
        enqueueErrors.increment();
        logger.warn("Usage rollup: could not schedule capture for cluster config {}: {}",
                clusterConfigId, e.toString());
    }

    /** Runs on the rollup thread. Package-private for tests. */
    void process(long clusterConfigId, Map<String, Object> snapshot, LocalDateTime batchCollectedAt,
                 Instant receivedAt) {
        try {
            if (alreadyCaptured(clusterConfigId, batchCollectedAt)) {
                logger.debug("Usage rollup: cluster config {}: snapshot collected at {} already captured (replayed"
                        + " push); skipped", clusterConfigId, batchCollectedAt);
                return;
            }
            Instant sampledAt = sampledAt(batchCollectedAt, receivedAt);
            Optional<Long> clientId = clientOf(clusterConfigId, receivedAt);
            if (clientId.isEmpty()) {
                logger.info("Usage rollup: cluster config {} has no resolvable client; snapshot not captured",
                        clusterConfigId);
                return;
            }
            K8sSnapshotUsageExtractor.Result extracted = K8sSnapshotUsageExtractor.extract(snapshot);
            if (extracted.samples().isEmpty()) {
                markCaptured(clusterConfigId, batchCollectedAt);
                return;
            }

            Map<String, UsageRollupStore.IdentityKey> keys = new LinkedHashMap<>();
            for (K8sSnapshotUsageExtractor.Sample s : extracted.samples()) {
                String key = s.workloadKey();
                keys.putIfAbsent(key, new UsageRollupStore.IdentityKey(s.namespace(), s.kind(), s.name(),
                        s.container(), key));
            }
            long client = clientId.get();
            Map<String, Long> ids = store.upsertIdentities(client, clusterConfigId, keys.values(), sampledAt);

            List<UsageRollupStore.SampleRow> rows = new ArrayList<>(extracted.samples().size());
            for (K8sSnapshotUsageExtractor.Sample s : extracted.samples()) {
                // Absent: the key is owned by another client in this cluster. Skip, never re-attribute.
                Long workloadId = ids.get(s.workloadKey());
                if (workloadId != null) {
                    rows.add(new UsageRollupStore.SampleRow(clusterConfigId, client, workloadId, sampledAt,
                            s.memB(), s.memReqB(), s.memLimB(), s.cpuM(), s.cpuReqM(), s.cpuLimM(),
                            s.podHash(), s.runningReplicas()));
                }
            }

            store.ensurePartition(UsagePartitions.Table.SAMPLE, LocalDate.ofInstant(sampledAt, ZoneOffset.UTC));
            int written = store.insertSamples(rows);
            markCaptured(clusterConfigId, batchCollectedAt);
            samplesWritten.increment(written);
            logger.debug("Usage rollup: cluster config {} at {}: {} pods, {} samples ({} new), "
                            + "{} containers without metrics, {} unparseable",
                    clusterConfigId, sampledAt, extracted.podsSeen(), rows.size(), written,
                    extracted.containersWithoutMetrics(), extracted.skippedUnparseable());
        } catch (RuntimeException e) {
            captureErrors.increment();
            if (FOREIGN_KEY_VIOLATION.equals(sqlState(e))) {
                // The cluster (or its client) was deleted or re-bound: forget what we cached for it.
                store.evictCluster(clusterConfigId);
                clients.remove(clusterConfigId);
                recentSnapshots.remove(clusterConfigId);
            }
            logger.warn("Usage rollup: capture failed for cluster config {}: {}", clusterConfigId, e.toString());
        }
    }

    /** True when this cluster's snapshot with this agent {@code collectedAt} was already written. */
    private boolean alreadyCaptured(long clusterConfigId, LocalDateTime collectedAt) {
        Deque<LocalDateTime> recent = recentSnapshots.get(clusterConfigId);
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            return recent.contains(collectedAt);
        }
    }

    /** Remembers a written snapshot; only the last {@value #RECENT_SNAPSHOTS_PER_CLUSTER} per cluster. */
    private void markCaptured(long clusterConfigId, LocalDateTime collectedAt) {
        Deque<LocalDateTime> recent = recentSnapshots.computeIfAbsent(clusterConfigId, k -> new ArrayDeque<>());
        synchronized (recent) {
            recent.addLast(collectedAt);
            while (recent.size() > RECENT_SNAPSHOTS_PER_CLUSTER) {
                recent.removeFirst();
            }
        }
    }

    /**
     * The agent's {@code collectedAt} (UTC) when it is plausibly close to the server's receive time,
     * else the receive time; truncated to seconds so an in-window replay also dedupes on the sample PK.
     */
    static Instant sampledAt(LocalDateTime batchCollectedAt, Instant receivedAt) {
        Instant chosen = receivedAt;
        if (batchCollectedAt != null) {
            Instant collected = batchCollectedAt.toInstant(ZoneOffset.UTC);
            if (!collected.isBefore(receivedAt.minus(MAX_COLLECTED_AT_LAG))
                    && !collected.isAfter(receivedAt.plus(MAX_COLLECTED_AT_LEAD))) {
                chosen = collected;
            }
        }
        return chosen.truncatedTo(ChronoUnit.SECONDS);
    }

    private Optional<Long> clientOf(long clusterConfigId, Instant now) {
        CachedClient cached = clients.get(clusterConfigId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return Optional.of(cached.clientId());
        }
        Optional<Long> resolved = store.resolveClientId(clusterConfigId);
        if (resolved.isPresent()) {
            clients.put(clusterConfigId, new CachedClient(resolved.get(), now.plus(CLIENT_CACHE_TTL)));
        } else {
            clients.remove(clusterConfigId);
        }
        return resolved;
    }

    private static Counter counter(MeterRegistry registry, String name, String stage) {
        Counter.Builder builder = Counter.builder(name);
        if (stage != null) {
            builder.tag("stage", stage);
        }
        return builder.register(registry);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
