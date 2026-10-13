package com.xammer.cloud.service.automation.workload.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.WorkloadVerticalAction;
import com.xammer.cloud.repository.WorkloadVerticalActionRepository;
import com.xammer.cloud.service.automation.workload.ActionTrigger;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.execute.ContainerApplyTarget;
import com.xammer.cloud.service.automation.workload.execute.ResizeProof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the durable {@code vertical_workload_action} audit row for one apply, from the same path a
 * human's Apply takes.
 *
 * <p>Separate from {@link WorkloadPolicyService} because the two write different tables for
 * different reasons: {@code recordApplied} stamps the cooldown on the policy row, this records what
 * actually happened for the audit history and for FEAT-002's loop to read back. Both are called side
 * by side from {@code VerticalApplyService} for a non-dry-run apply that changed a pod.
 *
 * <h2>What a row captures</h2>
 *
 * The trigger ({@code AUTO} / {@code MANUAL}), the dry-run flag, the published five-state outcome
 * ({@code ResizeProof.publishedTerminal()}), the restart-free proof, the correlation id, and
 * before/after resources serialised to JSON so the record reads without a join. "Before" is the
 * numbers that were submitted; "after" is what the proof observed on the pods.
 *
 * <p>Tenant-scoped by {@code clientId} <em>and</em> {@code clusterConfigId}, never the ambiguous
 * {@code cloud_account_id}.
 */
@Service
public class WorkloadActionRecorder {

    private static final Logger logger = LoggerFactory.getLogger(WorkloadActionRecorder.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WorkloadVerticalActionRepository repository;

    public WorkloadActionRecorder(WorkloadVerticalActionRepository repository) {
        this.repository = repository;
    }

    /**
     * Records one action from the apply's own inputs and the executor's proof.
     *
     * @param trigger   who initiated the apply; {@link ActionTrigger#MANUAL} on every shipped path
     * @param submitted the targets the apply sent, serialised as the "before/requested" side
     * @param proof     the executor's evidence; its {@link ResizeProof#publishedTerminal()} is the
     *                  recorded outcome and its pod observations are the "after" side
     * @param appliedAt the instant the change was applied
     */
    @Transactional
    public void record(Long clientId, ClusterTarget target, WorkloadRef ref, ActionTrigger trigger,
                       boolean dryRun, List<ContainerApplyTarget> submitted, ResizeProof proof,
                       Instant appliedAt) {
        if (target == null || ref == null || proof == null || appliedAt == null) {
            throw new IllegalArgumentException(
                    "target, ref, proof and appliedAt are mandatory to record an action");
        }
        ActionTrigger resolved = trigger == null ? ActionTrigger.MANUAL : trigger;

        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setClientId(clientId == null ? target.clientId() : clientId);
        row.setClusterConfigId(target.clusterConfigId());
        row.setNamespace(ref.namespace());
        row.setWorkloadKind(ref.kind());
        row.setWorkloadName(ref.name());
        row.setTriggerSource(resolved.name());
        row.setDryRun(dryRun);
        row.setOutcome(proof.publishedTerminal().name());
        row.setRestartFree(proof.restartFree());
        row.setBeforeJson(submittedJson(submitted));
        row.setAfterJson(observedJson(proof));
        row.setDetail(proof.detail());
        row.setAppliedAt(appliedAt);

        repository.save(row);
        logger.info("Recorded a {} action on {} of cluster config {}: {} (restartFree={})",
                resolved, ref.describe(), target.clusterConfigId(), proof.publishedTerminal(),
                proof.restartFree());
    }

    /**
     * The {@code outcome} value recorded for a mode switch, so the audit history and the read
     * endpoint's {@code lastAction} can tell a policy change apart from a resize.
     *
     * <p>Text, like every other {@code outcome}, so a new audit verb needs no migration — the same
     * reason {@code trigger_source} and the resize outcomes are text.
     */
    public static final String OUTCOME_POLICY_CHANGE = "POLICY_CHANGE";

    /**
     * Records that a user switched a workload's policy mode through endpoint 2 — the durable audit
     * row behind "who turned this workload Automatic, and when".
     *
     * <p>Written from the same tenant-scoped pair as a resize ({@code clientId} and
     * {@code clusterConfigId}, never {@code cloud_account_id}). A mode change touches no pod, so
     * there is no restart-free proof and no before/after resources: {@code outcome} is
     * {@link #OUTCOME_POLICY_CHANGE}, {@code restartFree} is false (nothing was proved about any
     * pod), the before/after JSON is left null and {@code detail} names the transition in words.
     *
     * @param trigger  who initiated the change; {@link ActionTrigger#MANUAL} for a user on endpoint 2
     * @param previous the mode the workload had before this change
     * @param next     the mode it has now
     * @param at       the instant the change was recorded; the audit timeline's instant
     */
    @Transactional
    public void recordPolicyChange(Long clientId, ClusterTarget target, WorkloadRef ref,
                                   ActionTrigger trigger, String previous, String next, Instant at) {
        if (target == null || ref == null || next == null || at == null) {
            throw new IllegalArgumentException(
                    "target, ref, next and at are mandatory to record a policy change");
        }
        ActionTrigger resolved = trigger == null ? ActionTrigger.MANUAL : trigger;

        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setClientId(clientId == null ? target.clientId() : clientId);
        row.setClusterConfigId(target.clusterConfigId());
        row.setNamespace(ref.namespace());
        row.setWorkloadKind(ref.kind());
        row.setWorkloadName(ref.name());
        row.setTriggerSource(resolved.name());
        row.setDryRun(Boolean.FALSE);
        row.setOutcome(OUTCOME_POLICY_CHANGE);
        row.setRestartFree(Boolean.FALSE);
        row.setDetail("Mode changed from " + previous + " to " + next + ".");
        row.setAppliedAt(at);

        repository.save(row);
        logger.info("Recorded a {} policy change on {} of cluster config {}: {} -> {}",
                resolved, ref.describe(), target.clusterConfigId(), previous, next);
    }

    /** The numbers submitted, per container — the "before/requested" side of the record. */
    private static String submittedJson(List<ContainerApplyTarget> submitted) {
        if (submitted == null || submitted.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> containers = submitted.stream()
                .map(target -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("container", target.containerName());
                    putIfPresent(fields, "cpuRequestMillis", target.cpuRequestMillis());
                    putIfPresent(fields, "cpuLimitMillis", target.cpuLimitMillis());
                    putIfPresent(fields, "memRequestBytes", target.memRequestBytes());
                    putIfPresent(fields, "memLimitBytes", target.memLimitBytes());
                    return fields;
                })
                .toList();
        return write(containers);
    }

    /**
     * The values the proof observed on the pods afterwards, per container — the "after" side. Taken
     * from the first pod's final container state, which for a confirmed apply is the applied size.
     */
    private static String observedJson(ResizeProof proof) {
        if (proof.pods().isEmpty()) {
            return null;
        }
        ResizeProof.PodProof pod = proof.pods().get(0);
        if (pod.containers().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> containers = pod.containers().stream()
                .map(container -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("container", container.name());
                    ResourceEnvelope after = container.observedAfter();
                    if (after != null) {
                        putIfPresent(fields, "cpuRequestMillis", after.cpuRequestMillis());
                        putIfPresent(fields, "cpuLimitMillis", after.cpuLimitMillis());
                        putIfPresent(fields, "memRequestBytes", after.memRequestBytes());
                        putIfPresent(fields, "memLimitBytes", after.memLimitBytes());
                    }
                    return fields;
                })
                .toList();
        return write(containers);
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Number value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException cannotSerialize) {
            // The audit row is written on the apply path, so a serialisation hiccup must not fail the
            // apply itself: record the row with no before/after rather than throwing.
            logger.warn("Could not serialise action resources: {}", cannotSerialize.getMessage());
            return null;
        }
    }
}
