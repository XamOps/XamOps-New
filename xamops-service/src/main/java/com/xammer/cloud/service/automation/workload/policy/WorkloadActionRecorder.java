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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * <h2>The activity log (FEAT-007)</h2>
 *
 * Besides the applies {@link #record} has always written, the activity feed records what used to go
 * unrecorded, into the same table and with no migration: a test run ({@link #OUTCOME_TEST_RUN}), a
 * real apply that changed no pod, such as a deferral XamOps put back ({@link #OUTCOME_NOT_CHANGED}),
 * a user's apply a gate refused ({@link #OUTCOME_REFUSED}), and every HPA apply
 * ({@link #KIND_HPA}). None of those outcomes can come out of {@link #record}, so endpoint 1, which
 * skips them, reports the same {@code lastAction} it always did.
 *
 * <p>Every new row stores {@code after_json} as an object,
 * {@code {"terminal", "reverted", "containers": [{container, observedBefore, observedAfter}],
 * "pods": [{pod, node, uidChanged, restartsBefore, restartsAfter, ready, revert}]}}, so a put-back
 * deferral and the per-pod proof can be shown. Rows written before this build hold an array of the
 * observed sizes, and the reader handles both.
 *
 * <p>The new write methods are <strong>fail-soft</strong>: they catch everything, log one WARN per
 * cluster per {@link #WARNING_INTERVAL} and return, so the audit can never fail an apply. They are
 * deliberately not {@code @Transactional}, since a failed save inside a transaction that annotation
 * opened would mark it rollback-only and surface at commit, in the caller. Each save instead runs in
 * a transaction of its own ({@code REQUIRES_NEW}) that the catch sits outside of: the automatic loop
 * calls the apply inside a read-only transaction, and a failed insert that joined it would mark that
 * transaction rollback-only and, on PostgreSQL, abort it for every cluster still to come in the
 * cycle.
 *
 * <p>Tenant-scoped by {@code clientId} <em>and</em> {@code clusterConfigId}, never the ambiguous
 * {@code cloud_account_id}.
 */
@Service
public class WorkloadActionRecorder {

    private static final Logger logger = LoggerFactory.getLogger(WorkloadActionRecorder.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A dry run: nothing was sent to the cluster. Recorded with {@code dry_run=true}. */
    public static final String OUTCOME_TEST_RUN = "TEST_RUN";

    /** A user's apply a gate refused before anything was sent; {@code detail} is "code: message". */
    public static final String OUTCOME_REFUSED = "REFUSED";

    /**
     * A real apply the executor finished without changing any pod: a deferral or an infeasible
     * resize XamOps put back, or a patch that was declined. {@code after_json} says which.
     */
    public static final String OUTCOME_NOT_CHANGED = "NOT_CHANGED";

    /** {@code workload_kind} of an HPA row, whose {@code workload_name} is the HPA's own name. */
    public static final String KIND_HPA = "HorizontalPodAutoscaler";

    /** How often a failing audit write is logged at WARN, per cluster. */
    static final Duration WARNING_INTERVAL = Duration.ofMinutes(10);

    private final WorkloadVerticalActionRepository repository;
    /** Runs each fail-soft save in a transaction of its own; see the class comment. */
    private final TransactionOperations ownTransaction;
    private final Clock clock;
    /** Cluster config id to the last WARN, so a broken database cannot flood the log. */
    private final Map<Long, Instant> lastWarnedAt = new ConcurrentHashMap<>();

    @Autowired
    public WorkloadActionRecorder(WorkloadVerticalActionRepository repository,
                                  PlatformTransactionManager transactionManager) {
        this(repository, requiresNew(transactionManager), Clock.systemUTC());
    }

    /** Saves with no transaction of its own: for tests over a mocked repository. */
    WorkloadActionRecorder(WorkloadVerticalActionRepository repository, Clock clock) {
        this(repository, TransactionOperations.withoutTransaction(), clock);
    }

    WorkloadActionRecorder(WorkloadVerticalActionRepository repository, TransactionOperations ownTransaction,
                           Clock clock) {
        this.repository = repository;
        this.ownTransaction = ownTransaction;
        this.clock = clock;
    }

    /** A new transaction for every fail-soft save, suspending the caller's if there is one. */
    static TransactionOperations requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
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
    // REQUIRES_NEW: this audit row is an IDENTITY INSERT, and the auto-apply loop
    // ({@code AutomaticApplyService.applyOnePerReadyCluster}) scans inside a {@code readOnly=true}
    // transaction so its LAZY {@code CloudAccount.client} reads resolve off the scheduled thread. On
    // PostgreSQL a Spring read-only transaction reaches the DB as {@code BEGIN READ ONLY}, so an
    // INSERT that joined it would fail with "cannot execute INSERT in a read-only transaction" and
    // the automatic resize would reach the pods but never be recorded. Committing in its own
    // read-write transaction is what makes the write land; the manual path (endpoint 3) runs under no
    // enclosing transaction, so a new one here is simply its own. The call crosses a bean boundary
    // from {@code VerticalApplyService}, so the proxy honours this propagation.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        row.setAfterJson(proofJson(proof));
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

    /**
     * Records an apply {@link #record} does not: a dry run ({@link #OUTCOME_TEST_RUN},
     * {@code dry_run=true}), or a real apply whose proof shows no pod changed
     * ({@link #OUTCOME_NOT_CHANGED}), such as a deferral XamOps put back and proved back.
     *
     * <p>{@code detail} is the proof's own detail with the revert state appended, and
     * {@code after_json} carries the proof's terminal, so the feed can tell "deferred, put back"
     * apart from "declined, nothing sent". Fail-soft: never throws.
     *
     * @param submitted the targets the apply sent (or, on a dry run, would have sent)
     * @param proof     the executor's evidence; never null
     */
    public void recordAttempt(Long clientId, ClusterTarget target, WorkloadRef ref, ActionTrigger trigger,
                              boolean dryRun, List<ContainerApplyTarget> submitted, ResizeProof proof,
                              Instant at) {
        try {
            requireRecordable(target, ref, at);
            Objects.requireNonNull(proof, "proof is mandatory to record an attempt");
            WorkloadVerticalAction row = newRow(clientId, target, ref, trigger, at);
            row.setDryRun(dryRun);
            row.setOutcome(dryRun ? OUTCOME_TEST_RUN : OUTCOME_NOT_CHANGED);
            row.setRestartFree(proof.restartFree());
            row.setBeforeJson(submittedJson(submitted));
            row.setAfterJson(proofJson(proof));
            row.setDetail(attemptDetail(proof));

            saveInOwnTransaction(row);
            logger.info("Recorded a {} {} on {} of cluster config {}: {}", row.getTriggerSource(),
                    row.getOutcome(), ref.describe(), target.clusterConfigId(), proof.outcome());
        } catch (RuntimeException notRecorded) {
            warnNotRecorded(target, dryRun ? "a test run" : "an apply that changed no pod", notRecorded);
        }
    }

    /**
     * Records a gate's refusal of an apply ({@link #OUTCOME_REFUSED}), with {@code detail}
     * "{@code <code>: <message>}". The caller decides which refusals are recorded (a user's, never
     * the automatic loop's and never the demo identity's); this only writes the row. Fail-soft:
     * never throws.
     *
     * @param ref the workload the apply named; on the HPA path, the HPA
     */
    public void recordRefusal(Long clientId, ClusterTarget target, WorkloadRef ref, ActionTrigger trigger,
                              boolean dryRun, String code, String message, Instant at) {
        try {
            requireRecordable(target, ref, at);
            WorkloadVerticalAction row = newRow(clientId, target, ref, trigger, at);
            row.setDryRun(dryRun);
            row.setOutcome(OUTCOME_REFUSED);
            row.setRestartFree(Boolean.FALSE);
            row.setDetail(code + ": " + message);

            saveInOwnTransaction(row);
            logger.info("Recorded a {} refusal on {} of cluster config {}: {}", row.getTriggerSource(),
                    ref.describe(), target.clusterConfigId(), code);
        } catch (RuntimeException notRecorded) {
            warnNotRecorded(target, "a refusal", notRecorded);
        }
    }

    /**
     * An HPA's bounds and CPU target as JSON fields; null means not set, or not known.
     *
     * @param targetUtilizationPct the CPU {@code averageUtilization} target
     */
    public record HpaBounds(Integer minReplicas, Integer maxReplicas, Integer targetUtilizationPct) {
    }

    /**
     * Records one HPA apply ({@code workload_kind} {@link #KIND_HPA}, {@code workload_name} the HPA's
     * name): {@code before_json} the live bounds read before the patch, {@code after_json} the
     * bounds the apply set (or, on a dry run, would have set). An HPA patch touches no pod, so
     * {@code restart_free} is false. Fail-soft: never throws.
     *
     * @param outcome       {@code APPLIED | FAILED | EXPIRED | REFUSED}, or {@link #OUTCOME_TEST_RUN}
     *                      for a dry run
     * @param correlationId the command's idempotency key
     */
    public void recordHpa(Long clientId, ClusterTarget target, String hpaNamespace, String hpaName,
                          ActionTrigger trigger, boolean dryRun, HpaBounds before, HpaBounds after,
                          String outcome, String detail, String correlationId, Instant at) {
        try {
            WorkloadRef hpa = WorkloadRef.of(hpaNamespace, KIND_HPA, hpaName);
            requireRecordable(target, hpa, at);
            if (outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("outcome is mandatory to record an HPA apply");
            }
            WorkloadVerticalAction row = newRow(clientId, target, hpa, trigger, at);
            row.setDryRun(dryRun);
            row.setOutcome(outcome);
            row.setRestartFree(Boolean.FALSE);
            row.setCorrelationId(correlationId);
            row.setBeforeJson(boundsJson(before));
            row.setAfterJson(boundsJson(after));
            row.setDetail(detail);

            saveInOwnTransaction(row);
            logger.info("Recorded a {} HPA {} on {} of cluster config {}", row.getTriggerSource(), outcome,
                    hpa.describe(), target.clusterConfigId());
        } catch (RuntimeException notRecorded) {
            warnNotRecorded(target, "an HPA apply", notRecorded);
        }
    }

    // ---------------------------------------------------------------- rows

    /**
     * Saves {@code row} in a transaction of its own, so a failure rolls back that transaction alone
     * and reaches the caller's catch, never the caller's transaction.
     */
    private void saveInOwnTransaction(WorkloadVerticalAction row) {
        ownTransaction.executeWithoutResult(status -> repository.save(row));
    }

    private static void requireRecordable(ClusterTarget target, WorkloadRef ref, Instant at) {
        if (target == null || ref == null || at == null) {
            throw new IllegalArgumentException("target, ref and at are mandatory to record an action");
        }
    }

    private static WorkloadVerticalAction newRow(Long clientId, ClusterTarget target, WorkloadRef ref,
                                                 ActionTrigger trigger, Instant at) {
        WorkloadVerticalAction row = new WorkloadVerticalAction();
        row.setClientId(clientId == null ? target.clientId() : clientId);
        row.setClusterConfigId(target.clusterConfigId());
        row.setNamespace(ref.namespace());
        row.setWorkloadKind(ref.kind());
        row.setWorkloadName(ref.name());
        row.setTriggerSource((trigger == null ? ActionTrigger.MANUAL : trigger).name());
        row.setAppliedAt(at);
        return row;
    }

    /** The proof's detail and, when a pod needed putting back, what putting it back achieved. */
    private static String attemptDetail(ResizeProof proof) {
        String detail = proof.detail() == null ? "" : proof.detail().trim();
        Set<String> reverts = new LinkedHashSet<>();
        for (ResizeProof.PodProof pod : proof.pods()) {
            if (pod.revert() != null) {
                reverts.add(pod.revert().state().name());
            }
        }
        if (!reverts.isEmpty()) {
            detail = (detail + " Revert: " + String.join(", ", reverts) + ".").trim();
        }
        return detail.isEmpty() ? null : detail;
    }

    /**
     * Logs a failed audit write at WARN at most once per cluster per {@link #WARNING_INTERVAL}, and at
     * DEBUG otherwise.
     */
    private void warnNotRecorded(ClusterTarget target, String what, RuntimeException cause) {
        Long key = target == null || target.clusterConfigId() == null ? -1L : target.clusterConfigId();
        Instant now = clock.instant();
        boolean[] due = {false};
        lastWarnedAt.compute(key, (ignored, last) -> {
            if (last == null || !now.isBefore(last.plus(WARNING_INTERVAL))) {
                due[0] = true;
                return now;
            }
            return last;
        });
        if (due[0]) {
            logger.warn("Could not record {} for cluster config {} in the activity log; the apply"
                            + " itself is unaffected. Further failures for this cluster are logged at"
                            + " DEBUG for {} minutes: {}", what, key, WARNING_INTERVAL.toMinutes(),
                    cause.toString());
        } else {
            logger.debug("Could not record {} for cluster config {}: {}", what, key, cause.toString());
        }
    }

    // ---------------------------------------------------------------- JSON

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
     * The proof as the "after" side: its full terminal, whether declined pods were put back, the
     * observed sizes before and after per container (from the first pod touched, the canary, whose
     * final state for a confirmed apply is the applied size), and every pod's restart-free evidence.
     *
     * <p>{@code terminal} is the first key, so a reader can classify a row by its prefix. A pod's
     * {@code node} is not part of the proof, so it is written as null rather than guessed.
     */
    static String proofJson(ResizeProof proof) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("terminal", proof.outcome().name());
        json.put("reverted", proof.reverted());

        List<Map<String, Object>> containers = new ArrayList<>();
        if (!proof.pods().isEmpty()) {
            for (ResizeProof.ContainerProof container : proof.pods().get(0).containers()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("container", container.name());
                fields.put("observedBefore", envelope(container.observedBefore()));
                fields.put("observedAfter", envelope(container.observedAfter()));
                containers.add(fields);
            }
        }
        json.put("containers", containers);

        List<Map<String, Object>> pods = new ArrayList<>();
        for (ResizeProof.PodProof pod : proof.pods()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("pod", pod.podName());
            fields.put("node", null);
            fields.put("uidChanged", pod.uidBefore() == null || pod.uidAfter() == null
                    ? null : !pod.uidBefore().equals(pod.uidAfter()));
            fields.put("restartsBefore", restarts(pod, true));
            fields.put("restartsAfter", restarts(pod, false));
            fields.put("ready", pod.ready());
            if (pod.revert() == null) {
                fields.put("revert", null);
            } else {
                Map<String, Object> revert = new LinkedHashMap<>();
                revert.put("state", pod.revert().state().name());
                revert.put("detail", pod.revert().detail());
                fields.put("revert", revert);
            }
            pods.add(fields);
        }
        json.put("pods", pods);
        return write(json);
    }

    /** A pod's restart count summed over its containers, or null when any container's is unknown. */
    private static Integer restarts(ResizeProof.PodProof pod, boolean before) {
        if (pod.containers().isEmpty()) {
            return null;
        }
        int total = 0;
        for (ResizeProof.ContainerProof container : pod.containers()) {
            Integer count = before ? container.restartCountBefore() : container.restartCountAfter();
            if (count == null) {
                return null;
            }
            total += count;
        }
        return total;
    }

    private static Map<String, Object> envelope(ResourceEnvelope resources) {
        if (resources == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("cpuRequestMillis", resources.cpuRequestMillis());
        fields.put("cpuLimitMillis", resources.cpuLimitMillis());
        fields.put("memRequestBytes", resources.memRequestBytes());
        fields.put("memLimitBytes", resources.memLimitBytes());
        return fields;
    }

    private static String boundsJson(HpaBounds bounds) {
        if (bounds == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("minReplicas", bounds.minReplicas());
        fields.put("maxReplicas", bounds.maxReplicas());
        fields.put("targetUtilizationPct", bounds.targetUtilizationPct());
        return write(fields);
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
