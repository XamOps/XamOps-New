package com.xammer.cloud.service.automation.workload.rca;

import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.RcaCauseDto;
import com.xammer.cloud.service.automation.workload.api.dto.RcaDependencyDto;
import com.xammer.cloud.service.automation.workload.api.dto.RcaEvidenceDto;
import com.xammer.cloud.service.automation.workload.api.dto.RcaResponseDto;
import com.xammer.cloud.service.automation.workload.policy.WorkloadActionRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Assembles the read-only RCA payload for one cluster (design §2.6).
 *
 * <h2>Read-only, tenant-scoped, no cluster call</h2>
 *
 * It loads rows through the exact repository method {@code VerticalAutomationReadService.lastActions}
 * already uses — {@code findByClusterConfigIdOrderByAppliedAtDesc(clusterConfigId)} — scoped to the
 * entitled {@code clusterConfigId}, so a query cannot reach another tenant's row by construction. It
 * <strong>never writes</strong> and makes <strong>no AWS or cluster call at all</strong> (a pure DB
 * read plus the pure {@link RcaAnalyzer}), so it works even with the automation master switch off.
 *
 * <h2>Honest empty state</h2>
 *
 * With no rows (or none in-window), {@code causes} is empty and {@code analyzed.actionRows} is the
 * real count. The static {@code dependencies} list names the two patterns v1 cannot yet prove.
 */
@Service
public class RcaReadService {

    /** The causes v1 cannot prove, named with what each needs (design §2.1). Static, never faked. */
    private static final List<RcaDependencyDto> DEPENDENCIES = List.of(
            new RcaDependencyDto(RcaPatternKind.OOM_EVICTION.name(),
                    "Kubernetes Warning events persisted server-side"),
            new RcaDependencyDto(RcaPatternKind.HPA_FLAP.name(),
                    "persisted HPA replica-change history"));

    private final WorkloadVerticalActionRepository actionRepository;
    private final RcaProperties properties;
    private final Clock clock;

    @Autowired
    public RcaReadService(WorkloadVerticalActionRepository actionRepository,
                          RcaProperties properties) {
        this(actionRepository, properties, Clock.systemUTC());
    }

    /** @param clock the analyzer's only ambient input; the seam the tests pin */
    RcaReadService(WorkloadVerticalActionRepository actionRepository, RcaProperties properties,
                   Clock clock) {
        this.actionRepository = actionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Analyse one cluster's durable history.
     *
     * @param target      the entitled cluster; only {@link ClusterTarget#clusterConfigId()} is used
     *                    to scope the read
     * @param windowHours the already-clamped trailing window
     */
    public RcaResponseDto read(ClusterTarget target, int windowHours) {
        List<WorkloadVerticalAction> rows = actionRepository
                .findByClusterConfigIdOrderByAppliedAtDesc(target.clusterConfigId());
        RcaConfig config = properties.toConfig().withWindowHours(windowHours);
        Instant now = clock.instant();

        List<RootCause> causes = RcaAnalyzer.analyze(rows, config, now);

        AnalyzedCounts analyzed = analyzed(rows, config, now);
        List<RcaCauseDto> causeDtos = causes.stream().map(RcaReadService::toCauseDto).toList();

        return new RcaResponseDto(now, windowHours,
                new RcaResponseDto.AnalyzedDto(analyzed.actionRows(), analyzed.workloads()),
                causeDtos, DEPENDENCIES);
    }

    /**
     * How much in-window, non-admin history the analyzer actually saw — the same filter the analyzer
     * applies, so the reported count matches what the causes were drawn from.
     */
    private static AnalyzedCounts analyzed(List<WorkloadVerticalAction> rows, RcaConfig config,
                                           Instant now) {
        Instant windowStart = now.minusSeconds((long) config.windowHours() * 3600L);
        int actionRows = 0;
        Set<String> workloads = new HashSet<>();
        for (WorkloadVerticalAction row : rows) {
            if (WorkloadActionRecorder.OUTCOME_POLICY_CHANGE.equals(row.getOutcome())) {
                continue;
            }
            Instant at = row.getAppliedAt();
            if (at == null || at.isBefore(windowStart)) {
                continue;
            }
            actionRows++;
            workloads.add(row.getClusterConfigId() + "|" + row.getNamespace() + "|"
                    + row.getWorkloadKind() + "|" + row.getWorkloadName());
        }
        return new AnalyzedCounts(actionRows, workloads.size());
    }

    private static RcaCauseDto toCauseDto(RootCause cause) {
        List<RcaEvidenceDto> evidence = cause.evidence().stream()
                .map(e -> new RcaEvidenceDto(e.at(), e.outcome(), e.detail(), e.correlationId()))
                .toList();
        return new RcaCauseDto(cause.kind().name(), cause.title(), cause.confidence(),
                cause.workload(), cause.occurrences(), cause.windowHours(), cause.remediation(),
                evidence);
    }

    private record AnalyzedCounts(int actionRows, int workloads) {
    }
}
