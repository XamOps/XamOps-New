package com.xammer.cloud.service.automation.workload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.config.K8sAutomationReadinessProperties;
import com.xammer.cloud.config.K8sReadinessKey;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.automation.proposal.ApprovalModeReader;
import com.xammer.cloud.service.automation.proposal.ProposalActionType;
import com.xammer.cloud.service.automation.proposal.ProposalService;
import com.xammer.cloud.service.automation.proposal.ProposalService.NewProposal;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.VerticalApplyService;
import com.xammer.cloud.service.automation.workload.api.VerticalAutomationReadService;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyRequestDto;
import com.xammer.cloud.service.automation.workload.api.dto.ApplyResponseDto;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalEngineResponse;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Manual mode's Java-side proposer (design 3.3c): once per interval, for each MANUAL cluster, probe up
 * to 25 {@code AUTO}-mode, auto-apply-eligible workloads with a <em>dry-run</em> apply
 * ({@link ActionTrigger#PROPOSAL}: the full gate chain runs, nothing is written to the cluster, no
 * activity row is recorded) and store the result as a proposal, {@code PENDING} when the chain allows it
 * and {@code REFUSED} (with the refusal code) when it does not.
 *
 * <p>Gates: the {@code manual-approval} readiness key and {@code autoscalingProperties.isAutoApplyPermitted()}
 * (engine readiness). It deliberately does <em>not</em> require {@code auto-apply.enabled}: that is the kill
 * switch of the autonomous loop, whereas Manual never applies on its own, a probe writes nothing, and a
 * real apply needs a human approval through the complete gate chain.
 *
 * <p>Like {@link AutomaticApplyService#applyOnePerReadyCluster} the entry method is
 * {@code @Transactional(readOnly = true)} so {@code account.getClient()} resolves; every proposal write goes
 * through {@link ProposalService#upsertWorkload}, which runs in its own {@code REQUIRES_NEW} transaction.
 */
@Component
public class WorkloadProposer {

    private static final Logger log = LoggerFactory.getLogger(WorkloadProposer.class);

    static final int MAX_WORKLOADS_PER_CLUSTER = 25;
    static final String ORIGIN = "vertical-engine";
    static final String METHOD = "IN_PLACE";

    private final K8sAutomationReadinessProperties readiness;
    private final WorkloadAutoscalingProperties autoscalingProperties;
    private final VerticalAutomationReadService readService;
    private final VerticalApplyService applyService;
    private final KubernetesClusterConfigRepository clusters;
    private final AutomaticApplyService autoApply;
    private final SystemActorResolver actors;
    private final ProposalService proposalService;
    private final ApprovalModeReader modeReader;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public WorkloadProposer(K8sAutomationReadinessProperties readiness,
                            WorkloadAutoscalingProperties autoscalingProperties,
                            VerticalAutomationReadService readService,
                            VerticalApplyService applyService,
                            KubernetesClusterConfigRepository clusters,
                            AutomaticApplyService autoApply,
                            SystemActorResolver actors,
                            ProposalService proposalService,
                            ApprovalModeReader modeReader) {
        this.readiness = readiness;
        this.autoscalingProperties = autoscalingProperties;
        this.readService = readService;
        this.applyService = applyService;
        this.clusters = clusters;
        this.autoApply = autoApply;
        this.actors = actors;
        this.proposalService = proposalService;
        this.modeReader = modeReader;
    }

    @Scheduled(
            fixedDelayString = "#{${xamops.automation.manual.workload-propose-interval-minutes:15} * 60 * 1000}",
            initialDelay = 90_000)
    public void runCycle() {
        if (!readiness.isReady(K8sReadinessKey.MANUAL_APPROVAL) || !autoscalingProperties.isAutoApplyPermitted()) {
            return;
        }
        try {
            proposeForManualClusters();
        } catch (RuntimeException unexpected) {
            log.warn("Workload proposer cycle failed before reaching any cluster: {}", unexpected.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public int proposeForManualClusters() {
        int proposed = 0;
        for (KubernetesClusterConfig config : clusters.findByAutomationApprovalMode(ApprovalModeReader.MANUAL)) {
            try {
                proposed += proposeForCluster(config);
            } catch (RuntimeException clusterFailure) {
                log.warn("Workload proposer skipped cluster config {} after an unexpected error: {}",
                        config.getId(), clusterFailure.getMessage());
            }
        }
        return proposed;
    }

    private int proposeForCluster(KubernetesClusterConfig config) {
        Long clusterConfigId = config.getId();
        if (clusterConfigId == null || !ApprovalModeReader.MANUAL.equals(modeReader.storedMode(clusterConfigId))) {
            return 0;
        }
        CloudAccount account = autoApply.resolveAccount(config).orElse(null);
        if (account == null || !"read-write".equalsIgnoreCase(account.getAccessType())) {
            return 0;
        }
        Long clientId = account.getClient() == null ? null : account.getClient().getId();
        if (clientId == null) {
            return 0;
        }
        Optional<ClientUserDetails> actor = actors.resolve(clientId);
        if (actor.isEmpty()) {
            return 0;
        }
        ClusterTarget target = new ClusterTarget(clientId, clusterConfigId, account.getAwsAccountId(),
                config.getClusterName(), config.getRegion());
        VerticalEngineResponse read = readService.read(account, target, config);
        if (!AutomaticApplyService.clusterIsApplyable(read)) {
            return 0;
        }
        List<VerticalWorkloadDto> candidates = read.workloads().stream()
                .filter(VerticalWorkloadDto::autoApplyEligible)
                .filter(w -> w.policy() != null && "AUTO".equalsIgnoreCase(w.policy().mode()))
                .sorted(Comparator.comparing((VerticalWorkloadDto w) -> w.ref().namespace())
                        .thenComparing(w -> w.ref().name()))
                .limit(MAX_WORKLOADS_PER_CLUSTER)
                .toList();
        int proposed = 0;
        for (VerticalWorkloadDto workload : candidates) {
            List<ApplyRequestDto.Container> body = AutomaticApplyService.applyBody(workload);
            if (body.isEmpty()) {
                continue;
            }
            WorkloadRef ref = WorkloadRef.of(workload.ref().namespace(), workload.ref().kind(), workload.ref().name());
            try {
                ApplyResponseDto proof = applyService.apply(actor.get(), account, target, ref,
                        new ApplyRequestDto(body, METHOD, Boolean.TRUE), ActionTrigger.PROPOSAL);
                if (upsert(clusterConfigId, clientId, workload, "ALLOWED", List.of(), null, proof)) {
                    proposed++;
                }
            } catch (VerticalApplyService.ApplyRefusedException refused) {
                String code = refused.code() == null ? "REFUSED" : refused.code();
                if (upsert(clusterConfigId, clientId, workload, "REFUSED", List.of(code), refused.getMessage(), null)) {
                    proposed++;
                }
            } catch (RuntimeException unexpected) {
                log.warn("Workload proposer: probing {} on cluster config {} failed: {}", ref.describe(),
                        clusterConfigId, unexpected.getMessage());
            }
        }
        return proposed;
    }

    private boolean upsert(Long clusterConfigId, Long clientId, VerticalWorkloadDto w, String verdict,
                           List<String> refusalCodes, String refusalReason, ApplyResponseDto proof) {
        ObjectNode params = params(w);
        ObjectNode evidence = mapper.createObjectNode();
        if (proof != null) {
            evidence.set("proof", mapper.valueToTree(proof));
        }
        evidence.put("verdict", verdict);
        String targetRef = w.ref().namespace() + "/" + w.ref().kind() + "/" + w.ref().name();
        NewProposal np = new NewProposal(ProposalActionType.WORKLOAD_RESIZE, METHOD, ProposalService.SOURCE_BACKEND,
                ORIGIN, "WORKLOAD", targetRef, null, "WORKLOAD_RESIZE:" + METHOD + ":" + ORIGIN + ":" + targetRef,
                params.toString(), evidence.toString(), verdict, refusalCodes, refusalReason, null, false,
                fingerprint(params, verdict, refusalCodes), null);
        return proposalService.upsertWorkload(clusterConfigId, clientId, np) == ProposalService.UpsertResult.ACCEPTED;
    }

    /** {@code params} for WORKLOAD_RESIZE: the full current and recommended envelopes the reviewer sees. */
    static ObjectNode paramsOf(ObjectMapper mapper, VerticalWorkloadDto w) {
        ObjectNode params = mapper.createObjectNode();
        params.put("namespace", w.ref().namespace());
        params.put("kind", w.ref().kind());
        params.put("name", w.ref().name());
        params.put("method", METHOD);
        ArrayNode current = params.putArray("current");
        ArrayNode recommended = params.putArray("recommended");
        for (VerticalWorkloadDto.ContainerDto c : w.containers()) {
            current.add(container(mapper, c.name(), c.current()));
            if (c.recommended() != null) {
                recommended.add(container(mapper, c.name(), c.recommended()));
            }
        }
        return params;
    }

    private ObjectNode params(VerticalWorkloadDto w) {
        return paramsOf(mapper, w);
    }

    private static ObjectNode container(ObjectMapper mapper, String name, VerticalWorkloadDto.ResourcesDto r) {
        ObjectNode n = mapper.createObjectNode();
        n.put("container", name);
        putInt(n, "cpuRequestM", r == null ? null : r.cpuRequestM());
        putInt(n, "cpuLimitM", r == null ? null : r.cpuLimitM());
        putLong(n, "memRequestMi", r == null ? null : r.memRequestMi());
        putLong(n, "memLimitMi", r == null ? null : r.memLimitMi());
        return n;
    }

    private static void putInt(ObjectNode n, String f, Integer v) {
        if (v == null) {
            n.putNull(f);
        } else {
            n.put(f, v);
        }
    }

    private static void putLong(ObjectNode n, String f, Long v) {
        if (v == null) {
            n.putNull(f);
        } else {
            n.put(f, v);
        }
    }

    /**
     * {@code sha256:} over the canonical JSON of {@code {namespace, kind, name, current, recommended,
     * verdict, sorted refusalCodes}} (fixed field order, no whitespace).
     */
    static String fingerprint(ObjectNode params, String verdict, List<String> refusalCodes) {
        ObjectMapper canonical = new ObjectMapper();
        ObjectNode n = canonical.createObjectNode();
        n.set("namespace", params.get("namespace"));
        n.set("kind", params.get("kind"));
        n.set("name", params.get("name"));
        n.set("current", params.get("current"));
        n.set("recommended", params.get("recommended"));
        n.put("verdict", verdict);
        ArrayNode codes = n.putArray("refusalCodes");
        new ArrayList<>(refusalCodes).stream().distinct().sorted().forEach(codes::add);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.writeValueAsString(n).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
