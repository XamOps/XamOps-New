package com.xammer.cloud.service.automation.workload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.AutomationProposal;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.AutomationProposalRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.proposal.ProposalException;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.PreApplyCheck;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

/**
 * Executes an approved {@code WORKLOAD_RESIZE} proposal in the Java backend (design 3.4), through
 * {@link VerticalApplyService#apply} with {@link ActionTrigger#APPROVED}, so every gate the manual Apply
 * button runs (write permission, read-only connection, controller readiness, apply method, provider,
 * GitOps, VPA guard, engine holds, cooldown, in-flight lock) runs again.
 *
 * <p>The approve request thread marks the row DISPATCHED and, after commit, hands {@code (id, cluster)}
 * to a bounded pool (core 2, max 4, queue 20). A rejection puts the row back to APPROVED and answers
 * 503 {@code EXECUTOR_BUSY}. Nothing from the request's {@code SecurityContext} or impersonation state
 * reaches the pool thread: the approve-time write check is the only authorisation done for the human,
 * and the apply itself runs as {@code SystemActorResolver}'s actor for the client (the same identity the
 * autonomous loop uses). The approver's name is what is audited on the proposal.
 *
 * <p>{@link #execute} is {@code @Transactional(readOnly = true)} so {@code account.getClient()} resolves,
 * and re-resolves the account and target itself on the pool thread. The patch body is
 * {@link WorkloadApplyBodyMapper#diff} of the stored {@code params} (never {@code recommended}
 * verbatim), and a live resource set that differs from {@code params.current} is refused
 * {@code PROPOSAL_STALE} before any patch.
 */
@Service
public class ProposalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ProposalExecutionService.class);

    private final AutomationProposalRepository proposals;
    private final KubernetesClusterConfigRepository clusters;
    private final AutomaticApplyService autoApply;
    private final SystemActorResolver actors;
    private final VerticalApplyService applyService;
    private final ProposalService proposalService;
    private final ObjectProvider<ProposalExecutionService> self;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ThreadPoolTaskExecutor pool;

    public ProposalExecutionService(AutomationProposalRepository proposals,
                                    KubernetesClusterConfigRepository clusters,
                                    AutomaticApplyService autoApply,
                                    SystemActorResolver actors,
                                    VerticalApplyService applyService,
                                    ProposalService proposalService,
                                    ObjectProvider<ProposalExecutionService> self) {
        this.proposals = proposals;
        this.clusters = clusters;
        this.autoApply = autoApply;
        this.actors = actors;
        this.applyService = applyService;
        this.proposalService = proposalService;
        this.self = self;
        this.pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(20);
        pool.setThreadNamePrefix("manual-workload-apply-");
        pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        pool.initialize();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /**
     * Hands a DISPATCHED workload proposal to the pool. Call after the approving transaction committed.
     *
     * @throws ProposalException {@code 503 EXECUTOR_BUSY} when the pool is saturated (the row is put back to APPROVED)
     */
    public void submit(Long clusterConfigId, String proposalId, String approverName) {
        try {
            pool.execute(() -> {
                try {
                    self.getObject().execute(clusterConfigId, proposalId, approverName);
                } catch (RuntimeException e) {
                    log.warn("Approved workload proposal {} failed unexpectedly: {}", proposalId, e.toString());
                    proposalService.finishWorkload(clusterConfigId, proposalId, "FAILED", "EXECUTION_ERROR", null,
                            "failed", false, null, null);
                }
            });
        } catch (RejectedExecutionException busy) {
            proposalService.undoDispatch(clusterConfigId, proposalId);
            throw new ProposalException(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTOR_BUSY",
                    "Too many changes are being applied right now. Try approving again in a moment.");
        }
    }

    @Transactional(readOnly = true)
    public void execute(Long clusterConfigId, String proposalId, String approverName) {
        Optional<AutomationProposal> found = proposals.findByIdAndClusterConfigId(proposalId, clusterConfigId);
        if (found.isEmpty() || !"DISPATCHED".equals(found.get().getStatus())) {
            return;
        }
        AutomationProposal p = found.get();
        Parsed parsed;
        try {
            parsed = parse(p);
        } catch (RuntimeException | java.io.IOException bad) {
            proposalService.finishWorkload(clusterConfigId, proposalId, "FAILED", "BAD_PARAMS", null, "failed", false,
                    null, null);
            return;
        }
        KubernetesClusterConfig config = clusters.findById(clusterConfigId).orElse(null);
        CloudAccount account = config == null ? null : autoApply.resolveAccount(config).orElse(null);
        Long clientId = account == null || account.getClient() == null ? null : account.getClient().getId();
        Optional<ClientUserDetails> actor = clientId == null ? Optional.empty() : actors.resolve(clientId);
        if (config == null || account == null || clientId == null || actor.isEmpty()) {
            proposalService.finishWorkload(clusterConfigId, proposalId, "FAILED", "NO_ACTOR_OR_ACCOUNT", null,
                    "failed", false, null, null);
            return;
        }
        ClusterTarget target = new ClusterTarget(clientId, clusterConfigId, account.getAwsAccountId(),
                config.getClusterName(), config.getRegion());

        List<ApplyRequestDto.Container> body;
        try {
            body = WorkloadApplyBodyMapper.diff(parsed.current(), parsed.recommended());
        } catch (IllegalArgumentException duplicate) {
            proposalService.finishWorkload(clusterConfigId, proposalId, "FAILED", "BAD_PARAMS", null, "failed", false,
                    null, null);
            return;
        }
        if (body.isEmpty()) {
            proposalService.finishWorkload(clusterConfigId, proposalId, "STALE", "no longer differs from current",
                    null, "stale", false, null, null);
            return;
        }
        PreApplyCheck check = PreApplyCheck.currentMatches(parsed.current().stream()
                .map(c -> new PreApplyCheck.Expected(c.name(), c.cpuRequestM(), c.cpuLimitM(), c.memRequestMi(),
                        c.memLimitMi())).toList());
        try {
            ApplyResponseDto response = applyService.apply(actor.get(), account, target, parsed.ref(),
                    new ApplyRequestDto(body, parsed.method(), Boolean.FALSE), ActionTrigger.APPROVED, check);
            ObjectNode outcome = mapper.createObjectNode();
            outcome.put("approvedBy", approverName);
            outcome.set("proof", mapper.valueToTree(response));
            String terminal = response.result() == null ? null : response.result().terminal();
            if ("APPLIED".equals(terminal)) {
                proposalService.finishWorkload(clusterConfigId, proposalId, "EXECUTED", "applied", null, "succeeded",
                        true, outcome.toString(), response.correlationId());
            } else {
                proposalService.finishWorkload(clusterConfigId, proposalId, "FAILED",
                        terminal == null ? "no proof" : terminal, null, "failed", false, outcome.toString(),
                        response.correlationId());
            }
        } catch (VerticalApplyService.ApplyRefusedException refused) {
            if (PreApplyCheck.STALE_CODE.equals(refused.code())) {
                proposalService.finishWorkload(clusterConfigId, proposalId, "STALE", refused.getMessage(), null,
                        "stale", false, null, null);
            } else {
                proposalService.finishWorkload(clusterConfigId, proposalId, "REFUSED",
                        refused.code() + ": " + refused.getMessage(), "EXECUTION", "refused", false, null, null);
            }
        }
    }

    // ------------------------------------------------------------------ params

    private record Parsed(WorkloadRef ref, String method, List<WorkloadApplyBodyMapper.ContainerResources> current,
                          List<WorkloadApplyBodyMapper.ContainerResources> recommended) {
    }

    private Parsed parse(AutomationProposal p) throws java.io.IOException {
        JsonNode params = mapper.readTree(p.getParamsJson());
        WorkloadRef ref = WorkloadRef.of(required(params, "namespace"), required(params, "kind"),
                required(params, "name"));
        String method = params.hasNonNull("method") ? params.get("method").asText() : "IN_PLACE";
        return new Parsed(ref, method, containers(params.get("current")), containers(params.get("recommended")));
    }

    private static String required(JsonNode node, String field) {
        if (!node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return node.get(field).asText();
    }

    private static List<WorkloadApplyBodyMapper.ContainerResources> containers(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("not an array");
        }
        List<WorkloadApplyBodyMapper.ContainerResources> out = new ArrayList<>();
        for (JsonNode c : array) {
            out.add(new WorkloadApplyBodyMapper.ContainerResources(required(c, "container"),
                    intOrNull(c, "cpuRequestM"), intOrNull(c, "cpuLimitM"),
                    longOrNull(c, "memRequestMi"), longOrNull(c, "memLimitMi")));
        }
        return out;
    }

    private static Integer intOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asInt() : null;
    }

    private static Long longOrNull(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asLong() : null;
    }
}
