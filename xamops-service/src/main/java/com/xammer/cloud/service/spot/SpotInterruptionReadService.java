package com.xammer.cloud.service.spot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Coverage;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Drain;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Event;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Fleet;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Handling;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Pending;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Recovery;
import com.xammer.cloud.dto.spot.SpotInterruptionsDto.Workload;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.K8sSpotInterruptionEventRepository;
import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles the Spot interruptions page (design §B5): one cluster's events in a window, the Spot
 * share from the latest agent snapshot and what each producer last reported.
 *
 * <p>Serves only observed data: a value nobody reported is {@code null}, never {@code 0} or
 * {@code []}. Every timestamp is a UTC instant; server-written {@code LocalDateTime}s go through
 * {@link ServerTime}. The caller passes the config the request guard resolved, and every query is
 * scoped by its id.
 */
@Service
public class SpotInterruptionReadService {

    private static final Logger log = LoggerFactory.getLogger(SpotInterruptionReadService.class);

    private final K8sSpotInterruptionEventRepository eventRepository;
    private final AgentDataCacheRepository agentDataCacheRepository;
    private final SpotInterruptionProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Row ids whose affected_workloads did not parse, so each is logged once. */
    private final Set<Long> warnedWorkloadIds = ConcurrentHashMap.newKeySet();

    @Autowired
    public SpotInterruptionReadService(K8sSpotInterruptionEventRepository eventRepository,
                                       AgentDataCacheRepository agentDataCacheRepository,
                                       SpotInterruptionProperties properties) {
        this(eventRepository, agentDataCacheRepository, properties, Clock.systemUTC());
    }

    /** Test constructor with a fixed clock. */
    SpotInterruptionReadService(K8sSpotInterruptionEventRepository eventRepository,
                                AgentDataCacheRepository agentDataCacheRepository,
                                SpotInterruptionProperties properties,
                                Clock clock) {
        this.eventRepository = eventRepository;
        this.agentDataCacheRepository = agentDataCacheRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param days  window, already validated to 1..365
     * @param limit maximum events, already validated to 1..500
     */
    @Transactional(readOnly = true)
    public SpotInterruptionsDto read(KubernetesClusterConfig cfg, int days, int limit) {
        Long clusterConfigId = cfg.getId();
        Instant since = clock.instant().minus(Duration.ofDays(days));
        List<K8sSpotInterruptionEvent> rows = eventRepository.findByClusterConfigIdAndNoticedAtAfterOrderByNoticedAtDesc(
                clusterConfigId, since, PageRequest.of(0, limit + 1));
        boolean truncated = rows.size() > limit;
        List<Event> events = (truncated ? rows.subList(0, limit) : rows).stream().map(this::toEvent).toList();

        Optional<AgentDataCache> snapshot = agentDataCacheRepository.findByClusterConfigIdAndDataType(
                clusterConfigId, SpotInterruptionTracker.K8S_RESOURCES);
        Optional<AgentDataCache> report = agentDataCacheRepository.findByClusterConfigIdAndDataType(
                clusterConfigId, SpotInterruptionTracker.NODE_OPTIMIZER_REPORT);

        Coverage coverage = new Coverage(
                snapshot.map(r -> iso(ServerTime.serverInstant(r.getReceivedAt()))).orElse(null),
                report.map(r -> iso(ServerTime.serverInstant(r.getReceivedAt()))).orElse(null),
                report.map(this::handlingNow).orElse(null));

        return new SpotInterruptionsDto(days, truncated, properties.retentionDays(), properties.recordingEnabled(),
                snapshot.map(this::fleet).orElse(null), coverage, events);
    }

    /** Node counts of the latest snapshot; null when its node list is missing or unreadable. */
    private Fleet fleet(AgentDataCache row) {
        JsonNode nodes;
        try {
            nodes = row.getDataJson() == null ? null : objectMapper.readTree(row.getDataJson()).path("nodes");
        } catch (Exception e) {
            log.warn("spot-interruptions: k8s-resources snapshot did not parse for cluster config {}",
                    row.getClusterConfigId());
            return null;
        }
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        int total = nodes.size();
        int spot = 0;
        for (JsonNode n : nodes) {
            if ("spot".equalsIgnoreCase(SnapshotView.text(n, "capacityType"))) {
                spot++;
            }
        }
        Integer share = total == 0 ? null : (int) Math.round(100.0 * spot / total);
        return new Fleet(total, spot, share, iso(ServerTime.serverInstant(row.getReceivedAt())));
    }

    /** The report's current handling decision, only while the report is fresh. */
    private Handling handlingNow(AgentDataCache row) {
        if (!ServerTime.fresh(row.getReceivedAt(), clock, properties.reportMaxAge()) || row.getDataJson() == null) {
            return null;
        }
        JsonNode decision;
        try {
            decision = objectMapper.readTree(row.getDataJson()).get("interruptionHandling");
        } catch (Exception e) {
            log.warn("spot-interruptions: node-optimizer-report did not parse for cluster config {}",
                    row.getClusterConfigId());
            return null;
        }
        // Mode only: drain eligibility is per notice, so it does not apply here.
        SpotHandling h = SpotHandling.from(decision, true, false, null);
        return new Handling(h.mode(), h.reason());
    }

    private Event toEvent(K8sSpotInterruptionEvent row) {
        List<Workload> workloads = workloads(row);
        Boolean workloadsTruncated = null;
        if (workloads != null && row.getPodCount() != null) {
            int listed = workloads.stream().mapToInt(Workload::podsOnNode).sum();
            workloadsTruncated = listed < row.getPodCount();
        }
        return new Event(
                row.getId(),
                row.getNodeName(),
                iso(row.getNodeCreatedAt()),
                row.getInstanceId(),
                row.getInstanceType(),
                row.getZone(),
                row.getCapacityType(),
                row.getNodegroup(),
                row.getNoticeKind(),
                sources(row.getSources()),
                iso(row.getNoticedAt()),
                iso(row.getRebalanceNoticedAt()),
                iso(row.getInterruptionNoticedAt()),
                iso(row.getNodeRemovedAt()),
                row.getCordonedBeforeRemoval(),
                row.getHandlingMode() == null ? null : new Handling(row.getHandlingMode(), row.getHandlingReason()),
                row.getDrainStatus() == null ? null : new Drain(row.getDrainStatus(), row.getDrainReason(),
                        row.getDrainDryRun(), iso(row.getDrainFinishedAt())),
                workloads,
                row.getPodCount(),
                workloadsTruncated,
                iso(row.getWorkloadsSnapshotAt()),
                iso(row.getNodeLastSeenAt()),
                recoveryBounds(row),
                row.getPendingFirstSeenAt() == null ? null : new Pending(iso(row.getPendingFirstSeenAt()),
                        iso(row.getPendingLastSeenAt()), row.getPendingMaxPods()));
    }

    /** null for an unknown capture and for one that does not parse (WARN once per row), never []. */
    private List<Workload> workloads(K8sSpotInterruptionEvent row) {
        String json = row.getAffectedWorkloads();
        if (json == null) {
            return null;
        }
        List<WorkloadEntry> entries = WorkloadCapture.fromJson(json);
        if (entries == null) {
            if (row.getId() == null || warnedWorkloadIds.add(row.getId())) {
                log.warn("spot-interruptions: affected_workloads did not parse for event {}", row.getId());
            }
            return null;
        }
        return entries.stream().map(e -> new Workload(e.namespace(), e.kind(), e.name(), e.podsOnNode())).toList();
    }

    private static List<String> sources(String csv) {
        if (csv == null) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Recovery as a snapshot-granularity range (design §B5 "Recovery bounds"). Bounds are null
     * unless the row is {@code recovered}. Notice rows start at {@code noticed_at}; an unconfirmed
     * removal started somewhere in {@code (node_last_seen_at, noticed_at]}, so its upper bound is
     * measured from {@code node_last_seen_at}. An upper bound of 0 means the start was never
     * observed: no duration is claimed and {@code recoveredByFirstSighting} is set.
     */
    public static Recovery recoveryBounds(K8sSpotInterruptionEvent row) {
        String recoveryStatus = row.getRecoveryStatus();
        Instant noticed = row.getNoticedAt();
        Instant recovered = row.getRecoveredObservedAt();
        String observedAt = iso(recovered);
        if (!SpotInterruptionMerge.RECOVERED.equals(recoveryStatus) || recovered == null || noticed == null) {
            return new Recovery(recoveryStatus, null, null, false, observedAt);
        }
        Instant lastUnrecovered = row.getRecoveryLastUnrecoveredAt() != null ? row.getRecoveryLastUnrecoveredAt() : noticed;
        long lower = Duration.between(noticed, lastUnrecovered).getSeconds();
        long upper;
        if (SpotNoticeRules.KIND_UNCONFIRMED.equals(row.getNoticeKind())) {
            lower = Math.max(0, lower);
            Instant start = row.getNodeLastSeenAt() != null ? row.getNodeLastSeenAt() : noticed;
            upper = Duration.between(start, recovered).getSeconds();
        } else {
            upper = Duration.between(noticed, recovered).getSeconds();
        }
        if (upper <= 0) {
            return new Recovery(recoveryStatus, 0L, 0L, true, observedAt);
        }
        return new Recovery(recoveryStatus, lower, upper, false, observedAt);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
