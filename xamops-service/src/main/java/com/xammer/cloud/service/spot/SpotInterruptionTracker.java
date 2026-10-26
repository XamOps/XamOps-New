package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.AgentEvent;
import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.AgentEventRepository;
import com.xammer.cloud.repository.K8sSpotInterruptionEventRepository;
import com.xammer.cloud.service.spot.SpotObservation.SpotKey;
import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Turns what agents actually pushed into spot interruption history (design §B4.2-§B4.5).
 *
 * <p>Three producers feed it, after the ingest transaction commits:
 * <ul>
 *   <li>{@code node-optimizer-report}: the autoscaler's {@code interruptionNotices} (NTH taints,
 *       legacy label) with its {@code interruptionHandling} decision;</li>
 *   <li>{@code k8s-resources}: Karpenter {@code SpotInterrupted} events, NTH taints, Spot nodes
 *       that vanished between two snapshots (stored as {@code unconfirmed}) and recovery tracking;</li>
 *   <li>{@code autoscaling-actions}: spot-interruption drain outcomes, plus a durable
 *       {@code xamops-node-drained} {@code agent_events} row for each succeeded live XamOps drain,
 *       so removal inference can exclude nodes XamOps itself removed.</li>
 * </ul>
 *
 * <p><b>Ingest isolation (NFR3).</b> {@code AgentIngestService.processIngest} is one transaction;
 * this listener runs only after it commits, catches every {@link RuntimeException}, and writes in
 * {@code REQUIRES_NEW} transactions, so a tracking failure can never fail or roll back a push. It
 * runs synchronously on the ingest thread and times itself (WARN above 2 s). Logs never carry
 * account numbers, only the cluster config id.
 *
 * <p><b>Clocks.</b> The injected clock is {@code Clock.systemUTC()} in production; server-written
 * {@code LocalDateTime}s are converted only through {@link ServerTime}.
 */
@Service
public class SpotInterruptionTracker {

    private static final Logger log = LoggerFactory.getLogger(SpotInterruptionTracker.class);

    static final String NODE_OPTIMIZER_REPORT = "node-optimizer-report";
    static final String K8S_RESOURCES = "k8s-resources";
    /** The collector whose previous snapshot {@code AgentIngestService} hands over. */
    public static final String K8S_RESOURCES_COLLECTOR = K8S_RESOURCES;
    static final String AUTOSCALING_ACTIONS = "autoscaling-actions";
    /** The collectors {@code AgentIngestService} publishes an {@link AgentBatchStoredEvent} for. */
    public static final Set<String> COLLECTORS = Set.of(NODE_OPTIMIZER_REPORT, K8S_RESOURCES, AUTOSCALING_ACTIONS);

    /** agent_events type of a succeeded live XamOps drain (≤ 50 chars). */
    public static final String XAMOPS_NODE_DRAINED = "xamops-node-drained";
    static final String SPOT_INTERRUPTION_TRIGGER = "spot-interruption";
    static final Set<String> XAMOPS_DRAIN_TRIGGERS = Set.of("idle-drain", "spot-ratio-drain");
    static final Duration DRAIN_DEDUP_WINDOW = Duration.ofHours(2);
    static final Duration DRAIN_JOIN_WINDOW = Duration.ofMinutes(30);
    /** Slack added to the removal gap for the xamops-node-drained exclusion lookback. */
    static final Duration DRAIN_EXCLUSION_SLACK = Duration.ofMinutes(15);
    static final long SLOW_MS = 2_000;

    static final int PURGE_BATCH_SIZE = 5_000;
    static final int PURGE_MAX_BATCHES = 200;

    private final AgentDataCacheRepository agentDataCacheRepository;
    private final AgentEventRepository agentEventRepository;
    private final K8sSpotInterruptionEventRepository eventRepository;
    private final SpotInterruptionWriter writer;
    private final SpotInterruptionProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public SpotInterruptionTracker(AgentDataCacheRepository agentDataCacheRepository,
                                   AgentEventRepository agentEventRepository,
                                   K8sSpotInterruptionEventRepository eventRepository,
                                   SpotInterruptionWriter writer,
                                   SpotInterruptionProperties properties) {
        this(agentDataCacheRepository, agentEventRepository, eventRepository, writer, properties, Clock.systemUTC());
    }

    /** Test constructor with a fixed clock. */
    SpotInterruptionTracker(AgentDataCacheRepository agentDataCacheRepository,
                            AgentEventRepository agentEventRepository,
                            K8sSpotInterruptionEventRepository eventRepository,
                            SpotInterruptionWriter writer,
                            SpotInterruptionProperties properties,
                            Clock clock) {
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.agentEventRepository = agentEventRepository;
        this.eventRepository = eventRepository;
        this.writer = writer;
        this.properties = properties;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBatchStored(AgentBatchStoredEvent e) {
        if (!properties.recordingEnabled()) {
            return;
        }
        long start = System.nanoTime();
        try {
            switch (e.collector()) {
                case NODE_OPTIMIZER_REPORT -> onNodeOptimizerReport(e);
                case K8S_RESOURCES -> onK8sResources(e);
                case AUTOSCALING_ACTIONS -> onAutoscalingActions(e);
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            log.warn("spot-interruption tracking failed for cluster config {} ({}): {}",
                    e.clusterConfigId(), e.collector(), ex.toString());
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.debug("spot-interruption tracking took {} ms for cluster config {} ({})",
                    ms, e.clusterConfigId(), e.collector());
            if (ms > SLOW_MS) {
                log.warn("spot-interruption tracking took {} ms for cluster config {} ({})",
                        ms, e.clusterConfigId(), e.collector());
            }
        }
    }

    private Instant now() {
        // Postgres and H2 keep microseconds; truncating keeps stored and in-memory times equal.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // ── node-optimizer-report ────────────────────────────────────────────────

    private void onNodeOptimizerReport(AgentBatchStoredEvent e) {
        JsonNode root = readTree(e.dataJson());
        if (root == null) {
            return;
        }
        JsonNode notices = root.path("interruptionNotices");
        if (!notices.isArray() || notices.isEmpty()) {
            return; // older agents don't send it; an empty list is nothing to record
        }
        JsonNode decision = root.get("interruptionHandling");
        boolean eligibilityUnknown = root.path("interruptedNodesDetectionFailed").asBoolean(false);
        Instant now = now();
        Long id = e.clusterConfigId();
        // The latest agent snapshot, parsed at most once and only if some row needs its workloads.
        Supplier<SnapshotView> latest = memo(() -> writer.read(() -> agentDataCacheRepository
                        .findByClusterConfigIdAndDataType(id, K8S_RESOURCES))
                .map(c -> SnapshotView.parse(objectMapper, c.getDataJson(), ServerTime.serverInstant(c.getReceivedAt())))
                .orElse(null));

        for (JsonNode n : notices) {
            String nodeName = SnapshotView.text(n, "nodeName");
            Instant createdAt = SnapshotView.parseInstant(SnapshotView.text(n, "nodeCreatedAt"));
            String kind = SnapshotView.text(n, "kind");
            String source = SnapshotView.text(n, "source");
            if (nodeName == null || createdAt == null || source == null
                    || !(SpotNoticeRules.KIND_INTERRUPTION.equals(kind) || SpotNoticeRules.KIND_REBALANCE.equals(kind))) {
                log.debug("skipping an interruption notice without a usable node, creation time, kind or source "
                        + "(cluster config {})", id);
                continue;
            }
            SpotHandling handling = SpotHandling.from(decision, n.path("drainEligible").asBoolean(false),
                    eligibilityUnknown, source);
            // A cordoned node was cordoned before removal; an uncordoned one may still be cordoned later.
            Boolean cordoned = n.path("cordoned").asBoolean(false) ? Boolean.TRUE : null;
            SpotObservation obs = new SpotObservation(new SpotKey(id, nodeName, createdAt),
                    SnapshotView.text(n, "instanceId"), SnapshotView.text(n, "instanceType"),
                    SnapshotView.text(n, "zone"), SnapshotView.text(n, "capacityType"),
                    SnapshotView.text(n, "nodegroup"), kind, source, now, handling, null, null, cordoned);
            writer.upsert(obs, at -> WorkloadCaptureBuilder.capture(latest.get(), nodeName, at), now);
        }
    }

    // ── k8s-resources ────────────────────────────────────────────────────────

    private void onK8sResources(AgentBatchStoredEvent e) {
        Instant t1 = now();
        SnapshotView s1 = SnapshotView.parse(objectMapper, e.dataJson(), t1);
        if (s1 == null) {
            return;
        }
        Instant t0 = e.prevReceivedAt();
        SnapshotView s0 = e.prevJson() != null && t0 != null ? SnapshotView.parse(objectMapper, e.prevJson(), t0) : null;
        Long id = e.clusterConfigId();

        recordKarpenterEvents(id, s0, s1, t1);
        recordNthTaints(id, s0, s1, t1);
        inferRemovals(id, s0, s1, t0, t1);
        // Loaded after steps 1-3 committed, so rows created or moved to tracking above are
        // evaluated against this same S1.
        trackRecovery(id, s1, t1);
    }

    /** S0's capture when S0 lists the node (S0 is before the notice), else, or when it is unknown, S1's. */
    private static WorkloadProvider s0ThenS1(SnapshotView s0, SnapshotView s1, String nodeName) {
        return at -> {
            WorkloadCapture c0 = s0 != null && s0.listsNode(nodeName) ? WorkloadCaptureBuilder.capture(s0, nodeName, at) : null;
            return c0 != null ? c0 : WorkloadCaptureBuilder.capture(s1, nodeName, at);
        };
    }

    /** Step 1: Karpenter {@code SpotInterrupted} Warning events on Nodes this or the previous snapshot lists. */
    private void recordKarpenterEvents(Long id, SnapshotView s0, SnapshotView s1, Instant t1) {
        for (SnapshotView.EventView ev : s1.events()) {
            if (!"Warning".equals(ev.type()) || !"SpotInterrupted".equals(ev.reason()) || !"Node".equals(ev.objectKind())
                    || ev.involvedObject() == null) {
                continue;
            }
            String nodeName = ev.involvedObject();
            SnapshotView.NodeView node = s1.node(nodeName);
            if (node == null && s0 != null) {
                node = s0.node(nodeName);
            }
            if (node == null || node.createdAt() == null) {
                continue;
            }
            Instant noticedAt = Optional.ofNullable(SnapshotView.parseInstant(ev.firstTime()))
                    .or(() -> Optional.ofNullable(SnapshotView.parseInstant(ev.lastTime())))
                    .orElse(t1);
            SpotObservation obs = new SpotObservation(new SpotKey(id, nodeName, node.createdAt()), null,
                    node.instanceType(), node.zone(), node.capacityType(), node.nodegroup(),
                    SpotNoticeRules.KIND_INTERRUPTION, SpotNoticeRules.SOURCE_KARPENTER_EVENT, noticedAt,
                    SpotHandling.agentSnapshot(), null, null, null);
            writer.upsert(obs, s0ThenS1(s0, s1, nodeName), t1);
        }
    }

    /** Step 2: NTH taints (and the legacy label) on nodes in S1. */
    private void recordNthTaints(Long id, SnapshotView s0, SnapshotView s1, Instant t1) {
        for (SnapshotView.NodeView node : s1.nodesByName().values()) {
            SpotNoticeRules.Notice notice = SpotNoticeRules.classify(node.taintKeys(), node.labels());
            if (notice == null || node.createdAt() == null) {
                continue;
            }
            SpotObservation obs = new SpotObservation(new SpotKey(id, node.name(), node.createdAt()), null,
                    node.instanceType(), node.zone(), node.capacityType(), node.nodegroup(),
                    notice.kind(), notice.source(), t1, SpotHandling.agentSnapshot(), t1, null,
                    node.unschedulable() ? Boolean.TRUE : null);
            writer.upsert(obs, s0ThenS1(s0, s1, node.name()), t1);
        }
    }

    /** Step 3: Spot nodes of S0 that S1 no longer lists (§B4.3). */
    private void inferRemovals(Long id, SnapshotView s0, SnapshotView s1, Instant t0, Instant t1) {
        List<SnapshotView.NodeView> removed =
                RemovalInference.removedSpotNodes(s0, s1, t0, t1, properties.removalMaxGap());
        if (removed.isEmpty()) {
            return;
        }
        Supplier<Set<String>> drained = memo(() -> xamopsDrainedNodes(id,
                t1.minus(properties.removalMaxGap()).minus(DRAIN_EXCLUSION_SLACK)));
        for (SnapshotView.NodeView n0 : removed) {
            SpotKey key = new SpotKey(id, n0.name(), n0.createdAt());
            boolean exists = writer.read(() -> eventRepository
                    .findFirstByClusterConfigIdAndNodeNameAndNodeCreatedAt(id, n0.name(), n0.createdAt())).isPresent();
            WorkloadProvider fromS0 = at -> WorkloadCaptureBuilder.capture(s0, n0.name(), at);
            switch (RemovalInference.decide(n0, exists, drained)) {
                case UPDATE_EXISTING -> writer.update(key,
                        row -> RemovalInference.applyRemoval(row, t0, t1, n0.unschedulable()), fromS0, t1);
                case CREATE_UNCONFIRMED -> writer.upsert(RemovalInference.unconfirmed(id, n0, t0, t1), fromS0, t1);
                case SKIP_EXCLUSION_TAINT, SKIP_XAMOPS_DRAIN -> log.debug(
                        "Spot node removal not recorded (known cause) for cluster config {}", id);
            }
        }
    }

    /** Nodes with an {@code xamops-node-drained} row created after {@code since}. */
    private Set<String> xamopsDrainedNodes(Long id, Instant since) {
        List<AgentEvent> rows = writer.read(() -> agentEventRepository.findByClusterConfigIdAndEventTypeAndCreatedAtAfter(
                id, XAMOPS_NODE_DRAINED, ServerTime.serverLocal(since)));
        Set<String> nodes = new HashSet<>();
        for (AgentEvent row : rows) {
            JsonNode details = readTree(row.getDetails());
            String node = details == null ? null : SnapshotView.text(details, "node");
            if (node != null) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    /** Step 4: every {@code tracking} row against S1 (§B4.4). */
    private void trackRecovery(Long id, SnapshotView s1, Instant t1) {
        List<K8sSpotInterruptionEvent> rows = writer.read(() ->
                eventRepository.findByClusterConfigIdAndRecoveryStatus(id, SpotInterruptionMerge.TRACKING));
        for (K8sSpotInterruptionEvent tracked : rows) {
            SpotKey key = new SpotKey(id, tracked.getNodeName(), tracked.getNodeCreatedAt());
            writer.update(key, row -> {
                if (!SpotInterruptionMerge.TRACKING.equals(row.getRecoveryStatus())) {
                    return; // changed since it was loaded
                }
                List<WorkloadEntry> workloads = WorkloadCapture.fromJson(row.getAffectedWorkloads());
                RecoveryEvaluator.apply(row,
                        RecoveryEvaluator.evaluate(row, workloads, s1, t1, properties.recoveryWindow()), t1);
            }, null, t1);
        }
    }

    // ── autoscaling-actions ──────────────────────────────────────────────────

    private void onAutoscalingActions(AgentBatchStoredEvent e) {
        JsonNode records = readTree(e.dataJson());
        if (records == null || !records.isArray()) {
            return;
        }
        Long id = e.clusterConfigId();
        Instant now = now();
        Supplier<Set<String>> recorded = memo(() -> {
            List<AgentEvent> rows = writer.read(() -> agentEventRepository.findByClusterConfigIdAndEventTypeAndCreatedAtAfter(
                    id, XAMOPS_NODE_DRAINED, ServerTime.serverLocal(clock.instant().minus(DRAIN_DEDUP_WINDOW))));
            Set<String> details = new HashSet<>();
            rows.forEach(r -> details.add(r.getDetails()));
            return details;
        });
        for (JsonNode record : records) {
            List<String> nodes = SpotActionTargets.targetNodes(SnapshotView.text(record, "target"));
            if (nodes.isEmpty()) {
                continue;
            }
            String trigger = SnapshotView.text(record.path("details"), "trigger");
            String status = SnapshotView.text(record, "status");
            Instant startedAt = SnapshotView.parseInstant(SnapshotView.text(record, "startedAt"));
            Instant finishedAt = SnapshotView.parseInstant(SnapshotView.text(record, "finishedAt"));
            boolean live = record.path("dryRun").isBoolean() && !record.path("dryRun").asBoolean();

            if (SPOT_INTERRUPTION_TRIGGER.equals(trigger) && startedAt != null) {
                for (String node : nodes) {
                    recordDrainOutcome(id, node, startedAt, status, SnapshotView.text(record, "reason"),
                            record.path("dryRun").isBoolean() ? record.path("dryRun").asBoolean() : null,
                            finishedAt, now);
                }
            }
            if ("succeeded".equals(status) && live && XAMOPS_DRAIN_TRIGGERS.contains(trigger)) {
                String finished = SnapshotView.text(record, finishedAt != null ? "finishedAt" : "startedAt");
                for (String node : nodes) {
                    captureXamopsDrain(id, node, trigger, finished, recorded.get());
                }
            }
        }
    }

    /** Fills the drain outcome on the newest row of {@code node} noticed within ±30 min of the drain start. */
    private void recordDrainOutcome(Long id, String node, Instant startedAt, String status, String reason,
                                    Boolean dryRun, Instant finishedAt, Instant now) {
        List<K8sSpotInterruptionEvent> rows = writer.read(() -> eventRepository
                .findByClusterConfigIdAndNodeNameAndNoticedAtBetween(id, node,
                        startedAt.minus(DRAIN_JOIN_WINDOW), startedAt.plus(DRAIN_JOIN_WINDOW)));
        Optional<K8sSpotInterruptionEvent> newest = rows.stream()
                .max(Comparator.comparing(K8sSpotInterruptionEvent::getNoticedAt));
        if (newest.isEmpty()) {
            log.debug("spot-interruption drain record has no matching row (cluster config {})", id);
            return;
        }
        K8sSpotInterruptionEvent target = newest.get();
        writer.update(new SpotKey(id, target.getNodeName(), target.getNodeCreatedAt()), row -> {
            // Latest outcome wins.
            if (row.getDrainFinishedAt() == null || (finishedAt != null && finishedAt.isAfter(row.getDrainFinishedAt()))) {
                row.setDrainStatus(SpotInterruptionMerge.cap(status, 16));
                row.setDrainReason(reason);
                row.setDrainDryRun(dryRun);
                row.setDrainFinishedAt(finishedAt);
            }
        }, null, now);
    }

    /** One {@code xamops-node-drained} row per (node, trigger, finishedAt), deduplicated over 2 h. */
    private void captureXamopsDrain(Long id, String node, String trigger, String finishedAt, Set<String> recorded) {
        ObjectNode details = objectMapper.createObjectNode();
        details.put("node", node);
        details.put("trigger", trigger);
        details.put("finishedAt", finishedAt);
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("drain details did not serialize", ex);
        }
        if (recorded.contains(json)) {
            return;
        }
        AgentEvent event = new AgentEvent(id, XAMOPS_NODE_DRAINED);
        event.setDetails(json);
        writer.inNewTransaction(() -> agentEventRepository.save(event));
        recorded.add(json);
    }

    // ── retention ────────────────────────────────────────────────────────────

    /**
     * Deletes rows noticed more than {@code retention-days} ago, 5 000 per transaction, at most 200
     * batches per run; anything left is removed on the next run.
     *
     * @return rows deleted
     */
    public int purgeExpired() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int total = 0;
        for (int i = 0; i < PURGE_MAX_BATCHES; i++) {
            int deleted = eventRepository.deleteOlderThanBatch(cutoff, PURGE_BATCH_SIZE);
            total += deleted;
            if (deleted < PURGE_BATCH_SIZE) {
                log.info("Spot interruption retention: {} rows older than {} days deleted", total, properties.retentionDays());
                return total;
            }
        }
        log.warn("Spot interruption retention hit the {}-batch cap with rows still remaining; "
                + "the rest will be removed on the next run", PURGE_MAX_BATCHES);
        return total;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** A supplier computed at most once (null results included). */
    static <T> Supplier<T> memo(Supplier<T> delegate) {
        return new Supplier<>() {
            private boolean done;
            private T value;

            @Override
            public T get() {
                if (!done) {
                    value = delegate.get();
                    done = true;
                }
                return value;
            }
        };
    }
}
