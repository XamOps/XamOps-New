package com.xammer.cloud.service.automation.proposal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.K8sReadinessKey;
import com.xammer.cloud.domain.AutomationProposal;
import com.xammer.cloud.domain.AutomationProposalEvent;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ApprovedAction;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.ApprovedBy;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.BatchResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.DispatchedItem;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.DispatchedResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Item;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeRequest;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.OutcomeResponse;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Rejected;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Suppressed;
import com.xammer.cloud.dto.autoscaling.AgentProposalDtos.Target;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApprovalModeResponse;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ApproveRequest;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.Counts;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ManualListAvailability;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalDetailDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalEventDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.ProposalListDto;
import com.xammer.cloud.dto.autoscaling.ProposalDtos.RejectRequest;
import com.xammer.cloud.exception.K8sAutomationRefusedException;
import com.xammer.cloud.repository.AutomationProposalEventRepository;
import com.xammer.cloud.repository.AutomationProposalRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.K8sAutomationAccessService;
import com.xammer.cloud.security.K8sAutomationRequestContext;
import com.xammer.cloud.service.EksAutoscalingService;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The proposal state machine (design 3.2) and the single owner of every transition.
 *
 * <p>Every status change is one guarded {@code @Modifying} query that includes the cluster id (a
 * proposal of another cluster is a 404, never a 403) and, where it matters, the version and plan
 * fingerprint the approver saw. A writer that returns 0 lost a race; the caller re-reads and
 * classifies. Upserts, claims and mode changes take a row lock on the cluster's
 * {@code kubernetes_cluster_config} row first, so two concurrent writers for one cluster serialise
 * whatever the schema contains (the partial unique index is only a backstop).
 *
 * <p>Public methods that can refuse are {@code @Transactional(noRollbackFor = ProposalException.class)}:
 * a lazy expiry recorded just before a refusal is intentional and must commit with the 409.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    public static final String SOURCE_AGENT = "AGENT";
    public static final String SOURCE_BACKEND = "BACKEND";
    public static final String MANUAL_V1 = "manual-v1";

    private static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern DNS_1123 = Pattern.compile(
            "^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$");
    private static final int MAX_BATCH_ITEMS = 100;
    private static final int MAX_PARAMS_BYTES = 64 * 1024;
    private static final int MAX_EVIDENCE_BYTES = 256 * 1024;
    private static final int MAX_OUTCOME_BYTES = 1024 * 1024;
    private static final int MAX_CONSOLIDATION_BYTES = 256 * 1024;
    private static final int MAX_NOTE = 500;
    private static final int MAX_REASON = 1000;
    private static final long RATE_LIMIT_MILLIS = 2_000;
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");

    static final List<String> OUTCOME_STATUSES = List.of("succeeded", "simulated", "failed", "refused",
            "skipped", "aborted", "stale", "not-started");

    private final AutomationProposalRepository proposals;
    private final AutomationProposalEventRepository events;
    private final KubernetesClusterConfigRepository clusters;
    private final ApprovalModeReader modeReader;
    private final ManualProperties props;
    private final K8sAutomationReadinessProperties readiness;
    private final EksAutoscalingService autoscalingService;
    private final K8sAutomationAccessService accessService;
    private final WorkloadAutoscalingProperties workloadProperties;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Last accepted batch POST per cluster, for the 1-per-2-seconds rate limit. */
    private final Map<Long, Long> lastBatchAt = new ConcurrentHashMap<>();

    public ProposalService(AutomationProposalRepository proposals,
                           AutomationProposalEventRepository events,
                           KubernetesClusterConfigRepository clusters,
                           ApprovalModeReader modeReader,
                           ManualProperties props,
                           K8sAutomationReadinessProperties readiness,
                           EksAutoscalingService autoscalingService,
                           K8sAutomationAccessService accessService,
                           WorkloadAutoscalingProperties workloadProperties) {
        this(proposals, events, clusters, modeReader, props, readiness, autoscalingService, accessService,
                workloadProperties, Clock.systemUTC());
    }

    ProposalService(AutomationProposalRepository proposals,
                    AutomationProposalEventRepository events,
                    KubernetesClusterConfigRepository clusters,
                    ApprovalModeReader modeReader,
                    ManualProperties props,
                    K8sAutomationReadinessProperties readiness,
                    EksAutoscalingService autoscalingService,
                    K8sAutomationAccessService accessService,
                    WorkloadAutoscalingProperties workloadProperties,
                    Clock clock) {
        this.proposals = proposals;
        this.events = events;
        this.clusters = clusters;
        this.modeReader = modeReader;
        this.props = props;
        this.readiness = readiness;
        this.autoscalingService = autoscalingService;
        this.accessService = accessService;
        this.workloadProperties = workloadProperties;
        this.clock = clock;
    }

    // =====================================================================================
    // Results
    // =====================================================================================

    /** Outcome of approve/reject. {@code dispatchWorkload} tells the controller to hand the row to the Java executor after commit. */
    public record DecisionResult(String id, String status, boolean alreadyDecided, boolean dispatchWorkload) {
    }

    /** What a proposer hands {@link #upsert}. */
    public record NewProposal(ProposalActionType actionType, String operation, String source,
                              String originController, String targetKind, String targetRef,
                              String targetIdentity, String dedupeKey, String paramsJson, String evidenceJson,
                              String verdict, List<String> refusalCodes, String refusalReason, String gateJson,
                              boolean dryRun, String planFingerprint, Integer dwellSeconds) {
    }

    public enum UpsertResult { ACCEPTED, SUPPRESSED_COOLDOWN, QUEUE_FULL, NOT_MANUAL }

    // =====================================================================================
    // User API: read
    // =====================================================================================

    @Transactional(readOnly = true)
    public ProposalListDto list(KubernetesClusterConfig config, String status, Integer limitParam, String cursor) {
        int limit = limitParam == null ? 50 : limitParam;
        if (limit < 1 || limit > 200) {
            throw ProposalException.badRequest("BAD_LIMIT", "limit must be a whole number from 1 to 200.");
        }
        List<String> statuses;
        if (status == null || status.isBlank()) {
            statuses = ProposalStatus.allNames();
        } else {
            statuses = List.of(ProposalStatus.parse(status.trim())
                    .orElseThrow(() -> ProposalException.badRequest("BAD_STATUS",
                            "status must be one of the nine proposal statuses.")).name());
        }
        Instant ts = FAR_FUTURE;
        String lastId = "\uffff";
        if (cursor != null && !cursor.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int bar = decoded.indexOf('|');
                if (bar < 1) {
                    throw new IllegalArgumentException("no separator");
                }
                ts = Instant.ofEpochMilli(Long.parseLong(decoded.substring(0, bar)));
                lastId = decoded.substring(bar + 1);
            } catch (RuntimeException e) {
                throw ProposalException.badRequest("BAD_CURSOR", "The cursor isn't valid.");
            }
        }
        Instant now = clock.instant();
        Counts counts = counts(config.getId());
        List<AutomationProposal> rows = proposals.page(config.getId(), statuses, ts, lastId,
                PageRequest.of(0, limit + 1));
        boolean more = rows.size() > limit;
        List<AutomationProposal> pageRows = more ? rows.subList(0, limit) : rows;
        List<ProposalDto> items = new ArrayList<>();
        String state = "OK";
        try {
            for (AutomationProposal p : pageRows) {
                items.add(toDto(p));
            }
        } catch (UnreadableProposalException e) {
            // List-level only: never partial rows, never a single unreadable row in an OK list.
            log.error("Unreadable proposal row(s) on cluster config {}: {}", config.getId(), e.getMessage());
            state = "UNREADABLE";
            items = List.of();
            more = false;
        }
        String next = null;
        if (more && !pageRows.isEmpty()) {
            AutomationProposal last = pageRows.get(pageRows.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.getProposedAt().toEpochMilli() + "|" + last.getId()).getBytes(StandardCharsets.UTF_8));
        }
        return new ProposalListDto(state, config.getAutomationApprovalMode(), effective(config, now),
                availability(config), counts, items, next, now, agentAliveAt(config));
    }

    @Transactional(readOnly = true)
    public ProposalDetailDto detail(KubernetesClusterConfig config, String id) {
        AutomationProposal p = proposals.findByIdAndClusterConfigId(id, config.getId())
                .orElseThrow(ProposalException::notFound);
        ProposalDto dto;
        try {
            dto = toDto(p);
        } catch (UnreadableProposalException e) {
            throw ProposalException.conflict("UNREADABLE", "That proposal can't be read.");
        }
        List<ProposalEventDto> eventDtos = events
                .findByProposalIdAndClusterConfigIdOrderByAtAscIdAsc(id, config.getId()).stream()
                .map(e -> new ProposalEventDto(e.getAt(), e.getActorType(), e.getActorName(), e.getEvent(),
                        e.getDetail()))
                .toList();
        return new ProposalDetailDto(dto, eventDtos);
    }

    private Counts counts(Long clusterConfigId) {
        int pending = 0;
        int approved = 0;
        int dispatched = 0;
        int refused = 0;
        for (Object[] row : proposals.countsByStatus(clusterConfigId)) {
            int n = ((Number) row[1]).intValue();
            switch (String.valueOf(row[0])) {
                case "PENDING" -> pending = n;
                case "APPROVED" -> approved = n;
                case "DISPATCHED" -> dispatched = n;
                case "REFUSED" -> refused = n;
                default -> { }
            }
        }
        return new Counts(pending, approved, dispatched, refused);
    }

    /** Why this page's list may be empty (the list-scoping object, not the capabilities one). */
    ManualListAvailability availability(KubernetesClusterConfig config) {
        boolean ready = readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL);
        return new ManualListAvailability(ready, ready ? null : "CONTROLLER_NOT_READY",
                workloadProperties.isAutoApplyPermitted());
    }

    // =====================================================================================
    // Effective mode and agent liveness (design 3.5)
    // =====================================================================================

    /** {@code max(policy_served_at, automation_agent_features_seen_at)}; null when neither was ever written. */
    public Instant agentAliveAt(KubernetesClusterConfig config) {
        Instant a = config.getPolicyServedAt();
        Instant b = config.getAutomationAgentFeaturesSeenAt();
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /**
     * {@code active} when the agent was last served the stored mode within the effective window,
     * {@code switching} while the agent is alive but has not fetched it yet, {@code unknown} when the
     * agent has not been seen recently at all. A cluster that was never Manual has nothing to confirm.
     */
    public String effective(KubernetesClusterConfig config, Instant now) {
        String mode = config.getAutomationApprovalMode();
        String served = config.getPolicyServedApprovalMode();
        if (ApprovalModeReader.AUTONOMOUS.equals(mode) && served == null) {
            return "active";
        }
        Instant servedAt = config.getPolicyServedAt();
        if (served != null && served.equals(mode) && servedAt != null
                && !servedAt.isBefore(now.minusSeconds(props.getEffectiveWindowSeconds()))) {
            return "active";
        }
        Instant alive = agentAliveAt(config);
        if (alive != null && !alive.isBefore(now.minus(Duration.ofMinutes(props.getAgentAliveMinutes())))) {
            return "switching";
        }
        return "unknown";
    }

    private boolean agentAlive(KubernetesClusterConfig config, Instant now) {
        Instant alive = agentAliveAt(config);
        return alive != null && !alive.isBefore(now.minus(Duration.ofMinutes(props.getAgentAliveMinutes())));
    }

    // =====================================================================================
    // User API: approve / reject
    // =====================================================================================

    @Transactional(noRollbackFor = ProposalException.class)
    public DecisionResult approve(K8sAutomationRequestContext ctx, String id, ApproveRequest request) {
        if (request == null || request.expectedVersion() == null || request.planFingerprint() == null
                || request.planFingerprint().isBlank()) {
            throw ProposalException.badRequest("INVALID_REQUEST", "expectedVersion and planFingerprint are required.");
        }
        validateNote(request.note());
        KubernetesClusterConfig config = ctx.config();
        ClientUserDetails user = ctx.user();
        Instant now = clock.instant();

        AutomationProposal p = proposals.findByIdAndClusterConfigId(id, config.getId())
                .orElseThrow(ProposalException::notFound);
        try {
            toDto(p); // an unreadable row is never approvable
        } catch (UnreadableProposalException e) {
            throw ProposalException.conflict("UNREADABLE", "That proposal can't be read, so it can't be approved.");
        }
        ProposalActionType type = ProposalActionType.parse(p.getActionType()).orElseThrow(
                () -> ProposalException.conflict("UNREADABLE", "That proposal can't be read, so it can't be approved."));

        switch (ProposalStatus.parse(p.getStatus()).orElse(ProposalStatus.FAILED)) {
            case PENDING -> { /* continue */ }
            case APPROVED, DISPATCHED, EXECUTED -> {
                if (!request.planFingerprint().equals(p.getPlanFingerprint())) {
                    throw ProposalException.conflict("FINGERPRINT_MISMATCH",
                            "This proposal changed just now. Review the refreshed evidence and approve again.");
                }
                return new DecisionResult(p.getId(), p.getStatus(), true, false);
            }
            case REFUSED -> throw ProposalException.conflict("REFUSED_NOT_APPROVABLE",
                    "XamOps refused this action, so it can't be approved.");
            case EXPIRED -> throw ProposalException.conflict("EXPIRED", "This proposal expired.");
            case STALE -> throw ProposalException.conflict("STALE", "This proposal is out of date.");
            default -> throw ProposalException.conflict("ALREADY_DECIDED", "This proposal was already decided.");
        }

        if (!now.isBefore(p.getExpiresAt())) {
            if (proposals.markExpired(p.getId(), config.getId(), "PENDING", "TTL", now) == 1) {
                recordEvent(p.getId(), config.getId(), "SYSTEM", null, null, "EXPIRED", "TTL");
            }
            throw ProposalException.conflict("EXPIRED", "This proposal expired.");
        }
        if ("REFUSED".equals(p.getVerdict())) {
            throw ProposalException.conflict("REFUSED_NOT_APPROVABLE", "XamOps refused this action, so it can't be approved.");
        }

        String storedMode = modeReader.storedMode(config.getId());
        if (!ApprovalModeReader.MANUAL.equals(storedMode)) {
            throw ProposalException.conflict("NOT_MANUAL", "This cluster isn't in Manual mode.");
        }
        if ("disabled".equals(autoscalingService.storedAutoscalerMode(config))) {
            throw ProposalException.conflict("K8S_MODE_DISABLED", "Node automation is paused, so nothing can be approved.");
        }
        requireReady(type, p.getOriginController());

        if (type == ProposalActionType.WORKLOAD_RESIZE) {
            // The account scope, never relaxed per cluster; VerticalApplyService re-checks at execution.
            Optional<K8sAutomationRefusedException> refusal =
                    accessService.writeDecision(user, ctx.gateAccounts()).firstRefusal();
            if (refusal.isPresent()) {
                throw refusal.get();
            }
        } else if (!agentAlive(config, now)) {
            throw ProposalException.conflict("AGENT_NOT_REACHABLE",
                    "The automation agent hasn't checked in recently, so an approved action couldn't run.");
        }
        if (p.getApprovableAfter() != null && now.isBefore(p.getApprovableAfter())) {
            throw ProposalException.conflict("NOT_YET_APPROVABLE",
                    "This consolidation hasn't been a candidate long enough to approve.");
        }
        if (request.expectedVersion() != p.getVersion()) {
            throw ProposalException.conflict("VERSION_CONFLICT", "Someone else decided this proposal first.");
        }
        if (!request.planFingerprint().equals(p.getPlanFingerprint())) {
            throw ProposalException.conflict("FINGERPRINT_MISMATCH",
                    "This proposal changed just now. Review the refreshed evidence and approve again.");
        }

        Instant approvedUntil = now.plus(Duration.ofMinutes(props.getApprovedClaimWindowMinutes()));
        int updated = proposals.approve(p.getId(), config.getId(), p.getVersion(), p.getPlanFingerprint(),
                user.getId(), user.getUsername(), trimToNull(request.note()), now, approvedUntil);
        if (updated != 1) {
            return classifyLostApprove(config.getId(), p.getId(), request);
        }
        recordEvent(p.getId(), config.getId(), "USER", user.getId(), user.getUsername(), "APPROVED",
                trimToNull(request.note()));

        boolean dispatchWorkload = false;
        if (type == ProposalActionType.WORKLOAD_RESIZE) {
            if (proposals.dispatch(p.getId(), config.getId(), now) == 1) {
                recordEvent(p.getId(), config.getId(), "SYSTEM", null, null, "DISPATCHED", "java executor");
                dispatchWorkload = true;
            }
        }
        return new DecisionResult(p.getId(), "APPROVED", false, dispatchWorkload);
    }

    private DecisionResult classifyLostApprove(Long clusterConfigId, String id, ApproveRequest request) {
        AutomationProposal now = proposals.findByIdAndClusterConfigId(id, clusterConfigId)
                .orElseThrow(ProposalException::notFound);
        switch (ProposalStatus.parse(now.getStatus()).orElse(ProposalStatus.FAILED)) {
            case APPROVED, DISPATCHED, EXECUTED -> {
                if (request.planFingerprint().equals(now.getPlanFingerprint())) {
                    return new DecisionResult(now.getId(), now.getStatus(), true, false);
                }
                throw ProposalException.conflict("FINGERPRINT_MISMATCH",
                        "This proposal changed just now. Review the refreshed evidence and approve again.");
            }
            case PENDING -> {
                if (!request.planFingerprint().equals(now.getPlanFingerprint())) {
                    throw ProposalException.conflict("FINGERPRINT_MISMATCH",
                            "This proposal changed just now. Review the refreshed evidence and approve again.");
                }
                throw ProposalException.conflict("VERSION_CONFLICT", "Someone else decided this proposal first.");
            }
            case EXPIRED -> throw ProposalException.conflict("EXPIRED", "This proposal expired.");
            case STALE -> throw ProposalException.conflict("STALE", "This proposal is out of date.");
            case REFUSED -> throw ProposalException.conflict("REFUSED_NOT_APPROVABLE",
                    "XamOps refused this action, so it can't be approved.");
            default -> throw ProposalException.conflict("ALREADY_DECIDED", "This proposal was already decided.");
        }
    }

    @Transactional(noRollbackFor = ProposalException.class)
    public DecisionResult reject(K8sAutomationRequestContext ctx, String id, RejectRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw ProposalException.badRequest("INVALID_REQUEST", "expectedVersion is required.");
        }
        validateNote(request.note());
        KubernetesClusterConfig config = ctx.config();
        ClientUserDetails user = ctx.user();
        Instant now = clock.instant();
        AutomationProposal p = proposals.findByIdAndClusterConfigId(id, config.getId())
                .orElseThrow(ProposalException::notFound);
        switch (ProposalStatus.parse(p.getStatus()).orElse(ProposalStatus.FAILED)) {
            case PENDING -> { /* continue */ }
            case REJECTED -> {
                return new DecisionResult(p.getId(), "REJECTED", true, false);
            }
            case EXPIRED -> throw ProposalException.conflict("EXPIRED", "This proposal expired.");
            case STALE -> throw ProposalException.conflict("STALE", "This proposal is out of date.");
            default -> throw ProposalException.conflict("ALREADY_DECIDED", "This proposal was already decided.");
        }
        if (request.expectedVersion() != p.getVersion()) {
            throw ProposalException.conflict("VERSION_CONFLICT", "Someone else decided this proposal first.");
        }
        int updated = proposals.reject(p.getId(), config.getId(), p.getVersion(), user.getId(),
                user.getUsername(), trimToNull(request.note()), now);
        if (updated != 1) {
            AutomationProposal again = proposals.findByIdAndClusterConfigId(id, config.getId())
                    .orElseThrow(ProposalException::notFound);
            if ("REJECTED".equals(again.getStatus())) {
                return new DecisionResult(again.getId(), "REJECTED", true, false);
            }
            if ("PENDING".equals(again.getStatus())) {
                throw ProposalException.conflict("VERSION_CONFLICT", "Someone else decided this proposal first.");
            }
            throw ProposalException.conflict("ALREADY_DECIDED", "This proposal was already decided.");
        }
        recordEvent(p.getId(), config.getId(), "USER", user.getId(), user.getUsername(), "REJECTED",
                trimToNull(request.note()));
        return new DecisionResult(p.getId(), "REJECTED", false, false);
    }

    private void requireReady(ProposalActionType type, String origin) {
        if (!readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL) || !readiness.isReady(type.readinessKey(origin))) {
            throw ProposalException.conflict("K8S_CONTROLLER_NOT_READY",
                    "Automated apply is disabled until this controller passes safety validation.");
        }
    }

    private static void validateNote(String note) {
        if (note != null && note.length() > MAX_NOTE) {
            throw ProposalException.badRequest("INVALID_REQUEST", "note must be at most 500 characters.");
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // =====================================================================================
    // User API: approval mode
    // =====================================================================================

    @Transactional(noRollbackFor = ProposalException.class)
    public ApprovalModeResponse setApprovalMode(K8sAutomationRequestContext ctx, String modeValue) {
        String mode = modeValue == null ? "" : modeValue.trim().toUpperCase(Locale.ROOT);
        if (!ApprovalModeReader.MANUAL.equals(mode) && !ApprovalModeReader.AUTONOMOUS.equals(mode)) {
            throw ProposalException.badRequest("INVALID_REQUEST", "mode must be MANUAL or AUTONOMOUS.");
        }
        Instant now = clock.instant();
        // Fresh row under the cluster lock: the guard's entity may be stale, the lock serialises with claims/upserts.
        KubernetesClusterConfig config = clusters.findByIdForUpdate(ctx.config().getId())
                .orElseThrow(ProposalException::notFound);
        String current = config.getAutomationApprovalMode();
        if (ApprovalModeReader.MANUAL.equals(mode)) {
            if (!readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL)) {
                throw ProposalException.conflict("K8S_CONTROLLER_NOT_READY",
                        "Manual approval mode isn't enabled in this environment.");
            }
            String key = config.getAutomationAgentApiKey();
            if (key == null || key.isEmpty()) {
                throw ProposalException.conflict("AGENT_KEY_LEGACY",
                        "Manual mode needs the automation agent key for this cluster.");
            }
            if (!agentSupportsManual(config, now)) {
                throw ProposalException.conflict("K8S_AGENT_TOO_OLD",
                        "The automation agent hasn't reported that it supports Manual mode. Update it first.");
            }
        }
        ClientUserDetails user = ctx.user();
        if (!mode.equals(current)) {
            if (clusters.setApprovalMode(config.getId(), mode, now, user.getId()) != 1) {
                throw ProposalException.notFound();
            }
            autoscalingService.recordConfigChange(config.getId(), "Approval mode changed to " + mode + " by "
                    + user.getUsername());
            recordEvent("-", config.getId(), "SYSTEM", user.getId(), user.getUsername(), "MODE_CHANGED",
                    current + " -> " + mode);
            if (ApprovalModeReader.AUTONOMOUS.equals(mode)) {
                staleOpenProposals(config.getId(), now);
            }
        }
        String effective = ApprovalModeReader.MANUAL.equals(mode) && !mode.equals(current)
                ? "switching"
                : effectiveAfterChange(config, mode, now);
        return new ApprovalModeResponse(mode, effective, now);
    }

    private String effectiveAfterChange(KubernetesClusterConfig config, String mode, Instant now) {
        // The entity may predate the change; recompute against the mode just written.
        String served = config.getPolicyServedApprovalMode();
        Instant servedAt = config.getPolicyServedAt();
        if (ApprovalModeReader.AUTONOMOUS.equals(mode) && served == null) {
            return "active";
        }
        if (served != null && served.equals(mode) && servedAt != null
                && !servedAt.isBefore(now.minusSeconds(props.getEffectiveWindowSeconds()))) {
            return "active";
        }
        return agentAlive(config, now) ? "switching" : "unknown";
    }

    /** True when a leader automation heartbeat reported {@code manual-v1} within {@code agent-features-max-age-hours}. */
    boolean agentSupportsManual(KubernetesClusterConfig config, Instant now) {
        String features = config.getAutomationAgentFeatures();
        Instant seenAt = config.getAutomationAgentFeaturesSeenAt();
        if (features == null || seenAt == null) {
            return false;
        }
        if (seenAt.isBefore(now.minus(Duration.ofHours(props.getAgentFeaturesMaxAgeHours())))) {
            return false;
        }
        return List.of(features.split(",")).contains(MANUAL_V1);
    }

    private void staleOpenProposals(Long clusterConfigId, Instant now) {
        for (String openId : proposals.findOpenIds(clusterConfigId)) {
            int n = proposals.markStale(openId, clusterConfigId, "PENDING", "MODE_CHANGED", now);
            if (n == 0) {
                n = proposals.markStale(openId, clusterConfigId, "APPROVED", "MODE_CHANGED", now);
            }
            if (n == 1) {
                recordEvent(openId, clusterConfigId, "SYSTEM", null, null, "STALE", "MODE_CHANGED");
            }
        }
    }

    // =====================================================================================
    // Proposal creation / refresh (shared by the agent batch and the Java proposer)
    // =====================================================================================

    /**
     * Applies the refresh semantics of design 3.4 to one candidate. The caller holds the cluster-row
     * lock. Returns SUPPRESSED_COOLDOWN or QUEUE_FULL without inserting, ACCEPTED otherwise (a new row,
     * a refreshed row, or a replacement).
     */
    UpsertResult upsertLocked(Long clusterConfigId, Long clientId, NewProposal np, Instant now) {
        List<AutomationProposal> live = proposals.findLive(clusterConfigId, np.dedupeKey());
        boolean refused = "REFUSED".equals(np.verdict());
        Instant firstSeen = now;
        if (live.isEmpty()) {
            // Rule 0: cooldown after a cooldown-bearing terminal row of this key.
            Instant last = proposals.lastCooldownTerminalAt(clusterConfigId, np.dedupeKey());
            if (last != null && last.isAfter(now.minus(Duration.ofMinutes(props.getReproposeCooldownMinutes())))) {
                return UpsertResult.SUPPRESSED_COOLDOWN;
            }
        } else {
            AutomationProposal existing = live.get(0);
            switch (ProposalStatus.parse(existing.getStatus()).orElse(ProposalStatus.FAILED)) {
                case PENDING -> {
                    if (existing.getPlanFingerprint().equals(np.planFingerprint())) {
                        proposals.touchPending(existing.getId(), clusterConfigId, np.planFingerprint(), now,
                                np.evidenceJson());
                        return UpsertResult.ACCEPTED;
                    }
                    // Rule 3: the plan changed; replace it and keep the candidacy start.
                    if (proposals.markStale(existing.getId(), clusterConfigId, "PENDING", "PLAN_CHANGED", now) == 1) {
                        recordEvent(existing.getId(), clusterConfigId, "SYSTEM", null, null, "STALE", "PLAN_CHANGED");
                    }
                    firstSeen = existing.getFirstSeenAt();
                }
                case APPROVED, DISPATCHED -> {
                    // Rule 4: approval commits to what was seen; only last_seen moves.
                    proposals.touchSeen(existing.getId(), clusterConfigId, now);
                    return UpsertResult.ACCEPTED;
                }
                case REFUSED -> {
                    // Rule 5: a proposal-stage refusal is live; promote it when the verdict or plan changes.
                    boolean changed = !refused || !existing.getPlanFingerprint().equals(np.planFingerprint());
                    if (!changed) {
                        proposals.touchSeen(existing.getId(), clusterConfigId, now);
                        return UpsertResult.ACCEPTED;
                    }
                    if (proposals.markRefusedStale(existing.getId(), clusterConfigId,
                            refused ? "PLAN_CHANGED" : "no longer refused", now) == 1) {
                        recordEvent(existing.getId(), clusterConfigId, "SYSTEM", null, null, "STALE",
                                refused ? "PLAN_CHANGED" : "no longer refused");
                    }
                    firstSeen = existing.getFirstSeenAt();
                }
                default -> { /* not live; unreachable */ }
            }
        }
        if (!refused && proposals.countByClusterConfigIdAndStatus(clusterConfigId, "PENDING")
                >= props.getMaxPendingPerCluster()) {
            return UpsertResult.QUEUE_FULL;
        }
        insert(clusterConfigId, clientId, np, firstSeen, now);
        return UpsertResult.ACCEPTED;
    }

    private void insert(Long clusterConfigId, Long clientId, NewProposal np, Instant firstSeen, Instant now) {
        AutomationProposal row = new AutomationProposal();
        row.setId(UUID.randomUUID().toString());
        row.setClusterConfigId(clusterConfigId);
        row.setClientId(clientId);
        row.setActionType(np.actionType().name());
        row.setOperation(np.operation());
        row.setSource(np.source());
        row.setOriginController(np.originController());
        row.setTargetKind(np.targetKind());
        row.setTargetRef(np.targetRef());
        row.setTargetIdentity(np.targetIdentity());
        row.setDedupeKey(np.dedupeKey());
        row.setParamsJson(np.paramsJson());
        row.setEvidenceJson(np.evidenceJson());
        row.setVerdict(np.verdict());
        boolean refused = "REFUSED".equals(np.verdict());
        row.setRefusalCodes(np.refusalCodes() == null || np.refusalCodes().isEmpty()
                ? null : truncate(String.join(",", np.refusalCodes()), 512));
        row.setRefusalReason(np.refusalReason());
        row.setGateJson(np.gateJson());
        row.setDryRun(np.dryRun());
        row.setPlanFingerprint(np.planFingerprint());
        row.setStatus(refused ? "REFUSED" : "PENDING");
        row.setRefusalStage(refused ? "PROPOSAL" : null);
        row.setProposedAt(now);
        row.setFirstSeenAt(firstSeen);
        row.setLastSeenAt(now);
        row.setDwellSeconds(np.dwellSeconds());
        if (np.actionType() == ProposalActionType.NODE_CONSOLIDATE && np.dwellSeconds() != null) {
            // Backend-owned: the agent reports only its dwell setting, never the timestamp.
            row.setApprovableAfter(firstSeen.plusSeconds(np.dwellSeconds()));
        }
        int ttl = np.actionType() == ProposalActionType.WORKLOAD_RESIZE
                ? props.getPendingTtlMinutes().getWorkload() : props.getPendingTtlMinutes().getNode();
        row.setExpiresAt(now.plus(Duration.ofMinutes(ttl)));
        row.setDispatchAttempts(0);
        row.setVersion(0);
        proposals.save(row);
        recordEvent(row.getId(), clusterConfigId, np.source().equals(SOURCE_AGENT) ? "AGENT" : "SYSTEM", null, null,
                "PROPOSED", refused ? "refused: " + truncate(String.join(",", np.refusalCodes() == null
                        ? List.of() : np.refusalCodes()), 200) : null);
    }

    /** Java-side proposer entry (the workload loop). Takes the cluster-row lock itself. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public UpsertResult upsertWorkload(Long clusterConfigId, Long clientId, NewProposal np) {
        clusters.findByIdForUpdate(clusterConfigId).orElseThrow(ProposalException::notFound);
        if (!ApprovalModeReader.MANUAL.equals(modeReader.storedMode(clusterConfigId))) {
            return UpsertResult.NOT_MANUAL;
        }
        return upsertLocked(clusterConfigId, clientId, np, clock.instant());
    }

    // =====================================================================================
    // Agent API
    // =====================================================================================

    @Transactional(noRollbackFor = ProposalException.class)
    public BatchResponse acceptBatch(KubernetesClusterConfig resolved, BatchRequest request) {
        Long cid = resolved.getId();
        if (request == null || request.schemaVersion() == null
                || request.schemaVersion() != AgentProposalDtos.SCHEMA_VERSION) {
            throw ProposalException.badRequest("UNSUPPORTED_SCHEMA", "Unsupported schemaVersion.");
        }
        List<Item> items = request.items() == null ? List.of() : request.items();
        if (items.size() > MAX_BATCH_ITEMS) {
            throw ProposalException.badRequest("TOO_MANY_ITEMS", "A batch carries at most 100 items.");
        }
        AgentProposalDtos.Rebalance rebalance = request.rebalance();
        if (rebalance != null && (rebalance.complete() == null || rebalance.candidateCount() == null
                || rebalance.candidateCount() < 0 || rebalance.candidateCount() > 500)) {
            throw ProposalException.badRequest("BAD_REBALANCE", "rebalance must carry complete and candidateCount 0..500.");
        }
        boolean complete = rebalance != null && Boolean.TRUE.equals(rebalance.complete());
        if (items.isEmpty() && !complete) {
            throw ProposalException.badRequest("INVALID_REQUEST", "items must not be empty.");
        }
        long nowMillis = clock.millis();
        Long previous = lastBatchAt.get(cid);
        if (previous != null && nowMillis - previous < RATE_LIMIT_MILLIS) {
            throw new ProposalException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "At most one proposal batch every 2 seconds.");
        }
        lastBatchAt.put(cid, nowMillis);

        clusters.findByIdForUpdate(cid).orElseThrow(ProposalException::notFound);
        if (!ApprovalModeReader.MANUAL.equals(modeReader.storedMode(cid))) {
            throw ProposalException.conflict("NOT_MANUAL", "This cluster isn't in Manual mode.");
        }
        Instant now = clock.instant();
        int accepted = 0;
        boolean queueFull = false;
        List<Suppressed> suppressed = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (Item item : items) {
            String key = item == null ? null : item.dedupeKey();
            ItemCheck check = validate(item);
            if (check.rejection != null) {
                rejected.add(new Rejected(key, check.rejection));
                continue;
            }
            ProposalActionType type = check.type;
            if (!readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL)
                    || !readiness.isReady(type.readinessKey(item.originController()))) {
                rejected.add(new Rejected(key, "CONTROLLER_NOT_READY"));
                continue;
            }
            NewProposal np = new NewProposal(type, item.operation(), SOURCE_AGENT, item.originController(), "NODE",
                    item.target().name(), item.target().uid(), item.dedupeKey(), check.paramsJson, check.evidenceJson,
                    item.verdict(), item.refusal() == null ? List.of() : item.refusal().codes(),
                    item.refusal() == null ? null : item.refusal().reason(), check.gateJson,
                    item.gate() != null && Boolean.TRUE.equals(item.gate().dryRun()), item.planFingerprint(),
                    type == ProposalActionType.NODE_CONSOLIDATE ? item.dwellSeconds() : null);
            switch (upsertLocked(cid, null, np, now)) {
                case ACCEPTED -> accepted++;
                case SUPPRESSED_COOLDOWN -> suppressed.add(new Suppressed(key, "COOLDOWN"));
                case NOT_MANUAL -> rejected.add(new Rejected(key, "NOT_MANUAL"));
                case QUEUE_FULL -> {
                    queueFull = true;
                    rejected.add(new Rejected(key, "QUEUE_FULL"));
                }
            }
        }
        // Rebalance lifecycle (3.9): consume the stored request only when the agent enumerated every
        // candidate and no item of the batch hit QUEUE_FULL; same transaction, so a failure rolls back all.
        if (complete && !queueFull) {
            autoscalingService.consumeRebalanceRequest(resolved);
        }
        return new BatchResponse(accepted, suppressed, rejected);
    }

    private record ItemCheck(ProposalActionType type, String rejection, String paramsJson, String evidenceJson,
                             String gateJson) {
        static ItemCheck reject(String code) {
            return new ItemCheck(null, code, null, null, null);
        }
    }

    private ItemCheck validate(Item item) {
        if (item == null || item.dedupeKey() == null || item.dedupeKey().isEmpty() || item.dedupeKey().length() > 640) {
            return ItemCheck.reject("BAD_DEDUPE_KEY");
        }
        ProposalActionType type = ProposalActionType.parse(item.actionType()).orElse(null);
        if (type == null || !type.isNodeAction()) {
            return ItemCheck.reject("BAD_TYPE");
        }
        if (!type.validOperation(item.operation())) {
            return ItemCheck.reject("BAD_OPERATION");
        }
        if (!validOrigin(type, item.originController())) {
            return ItemCheck.reject("BAD_ORIGIN");
        }
        Target t = item.target();
        if (t == null || !"NODE".equals(t.kind()) || t.name() == null || t.name().isEmpty()
                || t.name().length() > 253 || !DNS_1123.matcher(t.name()).matches()
                || (t.uid() != null && t.uid().length() > 256)
                || (t.providerId() != null && t.providerId().length() > 256)
                || (t.nodeGroup() != null && t.nodeGroup().length() > 256)) {
            return ItemCheck.reject("BAD_TARGET");
        }
        if (!"ALLOWED".equals(item.verdict()) && !"REFUSED".equals(item.verdict())) {
            return ItemCheck.reject("BAD_VERDICT");
        }
        if ("REFUSED".equals(item.verdict())
                && (item.refusal() == null || item.refusal().codes() == null || item.refusal().codes().isEmpty())) {
            return ItemCheck.reject("BAD_VERDICT");
        }
        if (item.params() == null || !item.params().isObject() || item.evidence() == null
                || !item.evidence().isObject()) {
            return ItemCheck.reject("BAD_PAYLOAD");
        }
        // The row has no columns for providerId/nodeGroup; they ride in params so the claim can return them.
        com.fasterxml.jackson.databind.node.ObjectNode paramsNode = ((com.fasterxml.jackson.databind.node.ObjectNode) item.params()).deepCopy();
        if (t.providerId() != null && !paramsNode.has("providerId")) {
            paramsNode.put("providerId", t.providerId());
        }
        if (t.nodeGroup() != null && !paramsNode.has("nodeGroup")) {
            paramsNode.put("nodeGroup", t.nodeGroup());
        }
        String paramsJson = serialize(paramsNode);
        String evidenceJson = serialize(item.evidence());
        if (paramsJson == null || evidenceJson == null) {
            return ItemCheck.reject("BAD_PAYLOAD");
        }
        if (paramsJson.getBytes(StandardCharsets.UTF_8).length > MAX_PARAMS_BYTES
                || evidenceJson.getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES) {
            return ItemCheck.reject("TOO_LARGE");
        }
        if (item.planFingerprint() == null || !FINGERPRINT.matcher(item.planFingerprint()).matches()) {
            return ItemCheck.reject("BAD_FINGERPRINT");
        }
        if (type == ProposalActionType.NODE_CONSOLIDATE
                && (item.dwellSeconds() == null || item.dwellSeconds() < 0 || item.dwellSeconds() > 86_400)) {
            return ItemCheck.reject("BAD_DWELL");
        }
        String gateJson = null;
        if (item.gate() != null) {
            try {
                gateJson = mapper.writeValueAsString(item.gate());
            } catch (JsonProcessingException e) {
                return ItemCheck.reject("BAD_PAYLOAD");
            }
        }
        return new ItemCheck(type, null, paramsJson, evidenceJson, gateJson);
    }

    private static boolean validOrigin(ProposalActionType type, String origin) {
        return switch (type) {
            case NODE_DRAIN -> "node-optimizer".equals(origin) || "evictor".equals(origin);
            case NODE_CONSOLIDATE -> "consolidation".equals(origin);
            case REBALANCE -> "rebalancer".equals(origin);
            case WORKLOAD_RESIZE -> false;
        };
    }

    /** Claim the oldest approved node proposal of this cluster, or empty (204). */
    @Transactional(noRollbackFor = ProposalException.class)
    public Optional<ApprovedAction> claim(KubernetesClusterConfig resolved, AgentProposalDtos.ClaimRequest request) {
        if (request == null || request.cycleId() == null || request.cycleId().length() > 64) {
            throw ProposalException.badRequest("INVALID_REQUEST", "cycleId is required (at most 64 characters).");
        }
        Long cid = resolved.getId();
        KubernetesClusterConfig config = clusters.findByIdForUpdate(cid).orElseThrow(ProposalException::notFound);
        if (!ApprovalModeReader.MANUAL.equals(modeReader.storedMode(cid))) {
            throw ProposalException.conflict("NOT_MANUAL", "This cluster isn't in Manual mode.");
        }
        if ("disabled".equals(autoscalingService.storedAutoscalerMode(config))) {
            throw ProposalException.conflict("K8S_MODE_DISABLED", "Node automation is paused.");
        }
        if (accessService.agentPolicyBlock(config) != null) {
            return Optional.empty();
        }
        if (proposals.countDispatchedNodeActions(cid) > 0) {
            return Optional.empty(); // one node action in flight; a repeat claim never re-delivers a body
        }
        Instant now = clock.instant();
        for (AutomationProposal candidate : proposals.findClaimable(cid, now, PageRequest.of(0, 10))) {
            ProposalActionType type = ProposalActionType.parse(candidate.getActionType()).orElse(null);
            if (type == null || !type.isNodeAction() || "REFUSED".equals(candidate.getVerdict())
                    || !readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL)
                    || !readiness.isReady(type.readinessKey(candidate.getOriginController()))) {
                continue;
            }
            if (proposals.dispatch(candidate.getId(), cid, now) == 1) {
                recordEvent(candidate.getId(), cid, "AGENT", null, null, "DISPATCHED", null);
                AutomationProposal fresh = proposals.findByIdAndClusterConfigId(candidate.getId(), cid).orElseThrow();
                try {
                    return Optional.of(toApprovedAction(fresh));
                } catch (UnreadableProposalException e) {
                    // Unknown means refuse: release the claim and report nothing.
                    proposals.finish(fresh.getId(), cid, "FAILED", "UNREADABLE", null, "failed", false, null, null, now);
                    recordEvent(fresh.getId(), cid, "SYSTEM", null, null, "FAILED", "UNREADABLE");
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public DispatchedResponse dispatched(KubernetesClusterConfig resolved) {
        List<DispatchedItem> out = new ArrayList<>();
        for (AutomationProposal p : proposals.findDispatchedNodeActions(resolved.getId())) {
            out.add(new DispatchedItem(p.getId(), p.getTargetRef(), p.getTargetIdentity(), "proposal-" + p.getId(),
                    p.getActionType(), p.getOperation(), p.getDispatchedAt(), p.isDryRun()));
        }
        return new DispatchedResponse(out);
    }

    @Transactional(noRollbackFor = ProposalException.class)
    public OutcomeResponse outcome(KubernetesClusterConfig resolved, String pathId, OutcomeRequest request) {
        if (request == null) {
            throw ProposalException.badRequest("INVALID_REQUEST", "A body is required.");
        }
        if (request.schemaVersion() == null || request.schemaVersion() != AgentProposalDtos.SCHEMA_VERSION) {
            throw ProposalException.badRequest("UNSUPPORTED_SCHEMA", "Unsupported schemaVersion.");
        }
        if (request.proposalId() == null || !request.proposalId().equals(pathId)) {
            throw ProposalException.badRequest("ID_MISMATCH", "The proposalId must equal the path id.");
        }
        String status = request.status();
        boolean running = "running".equals(status);
        if (!running && (status == null || !OUTCOME_STATUSES.contains(status))) {
            throw ProposalException.badRequest("BAD_STATUS", "status isn't a known outcome status.");
        }
        if (request.stage() != null && !"PROPOSAL".equals(request.stage()) && !"EXECUTION".equals(request.stage())) {
            throw ProposalException.badRequest("BAD_STAGE", "stage must be PROPOSAL or EXECUTION.");
        }
        if (request.reason() != null && request.reason().length() > MAX_REASON) {
            throw ProposalException.badRequest("BAD_REASON", "reason is at most 1000 characters.");
        }
        if (!("proposal-" + pathId).equals(request.operationId())) {
            throw ProposalException.badRequest("BAD_OPERATION_ID", "operationId must be proposal-<id>.");
        }
        validateArray(request.actions(), "actions");
        validateArray(request.reports(), "reports");
        if (request.consolidation() != null && !request.consolidation().isNull()) {
            if (!request.consolidation().isObject()) {
                throw ProposalException.badRequest("BAD_CONSOLIDATION", "consolidation must be null or an object.");
            }
            String c = serialize(request.consolidation());
            if (c == null || c.getBytes(StandardCharsets.UTF_8).length > MAX_CONSOLIDATION_BYTES) {
                throw new ProposalException(HttpStatus.BAD_REQUEST, "BAD_CONSOLIDATION", "consolidation is too large.");
            }
        }
        String outcomeJson = serialize(mapper.valueToTree(request));
        if (outcomeJson == null || outcomeJson.getBytes(StandardCharsets.UTF_8).length > MAX_OUTCOME_BYTES) {
            throw new ProposalException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "The outcome is too large.");
        }

        Long cid = resolved.getId();
        Instant now = clock.instant();
        AutomationProposal p = proposals.findByIdAndClusterConfigId(pathId, cid).orElseThrow(ProposalException::notFound);

        if (running) {
            if (!"DISPATCHED".equals(p.getStatus()) || proposals.recordProgress(pathId, cid, now) != 1) {
                throw ProposalException.conflict("NOT_DISPATCHED", "That proposal isn't dispatched.");
            }
            return new OutcomeResponse("DISPATCHED");
        }
        if (!"DISPATCHED".equals(p.getStatus())) {
            if (status.equals(p.getOutcomeStatus())) {
                return new OutcomeResponse(p.getStatus()); // idempotent replay of the same outcome
            }
            throw ProposalException.conflict("NOT_DISPATCHED", "That proposal isn't dispatched.");
        }

        if ("not-started".equals(status)) {
            if (proposals.requeueNotStarted(pathId, cid, now) == 1) {
                recordEvent(pathId, cid, "AGENT", null, null, "REQUEUED", "not started");
                return new OutcomeResponse("APPROVED");
            }
            return finishOutcome(cid, pathId, "FAILED", "NOT_STARTED", null, status, false, outcomeJson, now);
        }
        boolean verified = Boolean.TRUE.equals(request.verified());
        String reason = truncate(request.reason(), 255);
        return switch (status) {
            case "succeeded", "simulated" -> finishOutcome(cid, pathId, "EXECUTED", reason, null, status, verified,
                    outcomeJson, now);
            case "refused" -> finishOutcome(cid, pathId, "REFUSED", reason, "EXECUTION", status, false, outcomeJson, now);
            case "stale" -> finishOutcome(cid, pathId, "STALE", reason, null, status, false, outcomeJson, now);
            default -> finishOutcome(cid, pathId, "FAILED", reason, null, status, false, outcomeJson, now);
        };
    }

    private OutcomeResponse finishOutcome(Long cid, String id, String to, String reason, String stage,
                                          String outcomeStatus, boolean verified, String outcomeJson, Instant now) {
        if (proposals.finish(id, cid, to, reason, stage, outcomeStatus, verified, outcomeJson, "proposal-" + id,
                now) != 1) {
            throw ProposalException.conflict("NOT_DISPATCHED", "That proposal isn't dispatched.");
        }
        recordEvent(id, cid, "AGENT", null, null, to, reason);
        return new OutcomeResponse(to);
    }

    private void validateArray(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray() || node.size() > 50) {
            throw ProposalException.badRequest("BAD_" + name.toUpperCase(Locale.ROOT),
                    name + " must be an array of at most 50 objects.");
        }
        for (JsonNode element : node) {
            if (!element.isObject()) {
                throw ProposalException.badRequest("BAD_" + name.toUpperCase(Locale.ROOT),
                        name + " must be an array of at most 50 objects.");
            }
        }
    }

    // =====================================================================================
    // Java workload executor support
    // =====================================================================================

    /** The Java executor pool rejected the work: put the row back (nothing ran). */
    @Transactional
    public void undoDispatch(Long clusterConfigId, String id) {
        if (proposals.undoDispatch(id, clusterConfigId) == 1) {
            recordEvent(id, clusterConfigId, "SYSTEM", null, null, "REQUEUED", "executor busy");
        }
    }

    /** Terminal result of a Java workload apply. */
    @Transactional
    public void finishWorkload(Long clusterConfigId, String id, String to, String reason, String stage,
                               String outcomeStatus, boolean verified, String outcomeJson, String correlationId) {
        Instant now = clock.instant();
        if (proposals.finish(id, clusterConfigId, to, truncate(reason, 255), stage, outcomeStatus, verified,
                outcomeJson, correlationId, now) == 1) {
            recordEvent(id, clusterConfigId, "SYSTEM", null, null, to, truncate(reason, 500));
        }
    }

    // =====================================================================================
    // Sweeper
    // =====================================================================================

    /** What one sweep did, for the log line and the tests. */
    public record SweepResult(int expiredPending, int expiredApproved, int failedDispatched, int staleNotReseen,
                              int deleted) {
    }

    @Transactional
    public SweepResult sweep() {
        Instant now = clock.instant();
        int expiredPending = 0;
        for (AutomationProposal p : proposals.findExpiredPending(now)) {
            if (proposals.markExpired(p.getId(), p.getClusterConfigId(), "PENDING", "TTL", now) == 1) {
                recordEvent(p.getId(), p.getClusterConfigId(), "SYSTEM", null, null, "EXPIRED", "TTL");
                expiredPending++;
            }
        }
        int expiredApproved = 0;
        for (AutomationProposal p : proposals.findExpiredApproved(now)) {
            if (proposals.markExpired(p.getId(), p.getClusterConfigId(), "APPROVED", "APPROVAL_WINDOW", now) == 1) {
                recordEvent(p.getId(), p.getClusterConfigId(), "SYSTEM", null, null, "EXPIRED", "APPROVAL_WINDOW");
                expiredApproved++;
            }
        }
        int failed = 0;
        for (AutomationProposal p : proposals.findAllDispatched()) {
            Integer drain = clusters.findById(p.getClusterConfigId())
                    .map(KubernetesClusterConfig::getAutomationAgentDrainTimeoutSeconds).orElse(null);
            long leaseMinutes = props.effectiveLeaseMinutes(drain);
            Instant dispatchedAt = p.getDispatchedAt() == null ? p.getProposedAt() : p.getDispatchedAt();
            boolean pastLease = dispatchedAt.plus(Duration.ofMinutes(leaseMinutes)).isBefore(now);
            Instant progress = p.getLastProgressAt();
            boolean silent = progress == null
                    || progress.isBefore(now.minus(Duration.ofMinutes(props.getAgentAliveMinutes())));
            // Never fail a row whose node the agent still reports as draining.
            if (pastLease && silent
                    && proposals.failDispatched(p.getId(), p.getClusterConfigId(), "LEASE_EXPIRED", now) == 1) {
                recordEvent(p.getId(), p.getClusterConfigId(), "SYSTEM", null, null, "FAILED", "LEASE_EXPIRED");
                failed++;
            }
        }
        int stale = sweepNotReseen(SOURCE_AGENT, now.minus(Duration.ofMinutes(props.getLastSeenGraceMinutes().getAgent())), now)
                + sweepNotReseen(SOURCE_BACKEND, now.minus(Duration.ofMinutes(props.getLastSeenGraceMinutes().getBackend())), now);
        Instant retention = now.minus(Duration.ofDays(props.getHistoryRetentionDays()));
        int deleted = proposals.deleteFinishedBefore(retention);
        events.deleteOlderThan(retention);
        return new SweepResult(expiredPending, expiredApproved, failed, stale, deleted);
    }

    private int sweepNotReseen(String source, Instant cutoff, Instant now) {
        int n = 0;
        for (AutomationProposal p : proposals.findNotReseen(source, cutoff)) {
            int updated = "PENDING".equals(p.getStatus())
                    ? proposals.markStale(p.getId(), p.getClusterConfigId(), "PENDING", "NOT_RESEEN", now)
                    : proposals.markRefusedStale(p.getId(), p.getClusterConfigId(), "NOT_RESEEN", now);
            if (updated == 1) {
                recordEvent(p.getId(), p.getClusterConfigId(), "SYSTEM", null, null, "STALE", "NOT_RESEEN");
                n++;
            }
        }
        return n;
    }

    // =====================================================================================
    // Mapping
    // =====================================================================================

    /** A stored row that can't be mapped (bad JSON, unknown status or action type). */
    static final class UnreadableProposalException extends Exception {
        UnreadableProposalException(String message) {
            super(message);
        }
    }

    ProposalDto toDto(AutomationProposal p) throws UnreadableProposalException {
        if (ProposalStatus.parse(p.getStatus()).isEmpty()) {
            throw new UnreadableProposalException("unknown status on " + p.getId());
        }
        if (ProposalActionType.parse(p.getActionType()).isEmpty()) {
            throw new UnreadableProposalException("unknown action type on " + p.getId());
        }
        JsonNode params = readObject(p.getParamsJson(), p.getId());
        JsonNode evidence = readObject(p.getEvidenceJson(), p.getId());
        List<String> codes = p.getRefusalCodes() == null || p.getRefusalCodes().isBlank()
                ? List.of() : List.of(p.getRefusalCodes().split(","));
        return new ProposalDto(p.getId(), p.getVersion(), p.getActionType(), p.getOperation(),
                p.getOriginController(), p.getTargetKind(), p.getTargetRef(), p.getStatus(), p.getStatusReason(),
                p.getVerdict(), codes, p.getRefusalReason(), p.getRefusalStage(), p.getPlanFingerprint(),
                p.isDryRun(), p.getProposedAt(), p.getFirstSeenAt(), p.getLastSeenAt(), p.getExpiresAt(),
                p.getApprovableAfter(), p.getApprovedUntil(), p.getDecidedByName(), p.getDecidedAt(),
                p.getDecisionNote(), p.getOutcomeStatus(), p.getOutcomeVerified(), params, evidence);
    }

    private JsonNode readObject(String json, String id) throws UnreadableProposalException {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new UnreadableProposalException("not a JSON object on " + id);
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new UnreadableProposalException("invalid JSON on " + id);
        }
    }

    private ApprovedAction toApprovedAction(AutomationProposal p) throws UnreadableProposalException {
        JsonNode params = readObject(p.getParamsJson(), p.getId());
        return new ApprovedAction(p.getId(), p.getVersion(), p.getActionType(), p.getOperation(),
                p.getOriginController(), p.getDedupeKey(),
                new Target(p.getTargetKind(), p.getTargetRef(), p.getTargetIdentity(),
                        text(params, "providerId"), text(params, "nodeGroup")),
                params, p.getPlanFingerprint(), p.getFirstSeenAt(),
                ProposalActionType.NODE_CONSOLIDATE.name().equals(p.getActionType()) ? p.getDwellSeconds() : null,
                new ApprovedBy(p.getDecidedBy(), p.getDecidedByName()), p.getDecidedAt(), p.getApprovedUntil(),
                p.isDryRun());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private String serialize(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    void recordEvent(String proposalId, Long clusterConfigId, String actorType, Long actorId, String actorName,
                     String event, String detail) {
        AutomationProposalEvent e = new AutomationProposalEvent();
        e.setProposalId(proposalId);
        e.setClusterConfigId(clusterConfigId);
        e.setAt(clock.instant());
        e.setActorType(actorType);
        e.setActorId(actorId);
        e.setActorName(truncate(actorName, 255));
        e.setEvent(event);
        e.setDetail(detail);
        events.save(e);
    }
}
