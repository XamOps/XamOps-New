package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The HPA apply executor: a bounds/target patch through {@link HpaPatchClient}, with the same
 * idempotency, dry-run and expiry discipline as {@link DirectInPlaceResizeExecutor}.
 *
 * <h2>Two paths, one of them forward-compatible</h2>
 *
 * <ul>
 *   <li><strong>DIRECT</strong> — the backend reads the HPA, patches it via {@link HpaPatchClient},
 *       and records the outcome <em>at submit time</em> (the direct path completes inside the
 *       request). A replayed {@code correlationId} answers {@link SubmitState#DUPLICATE} and sends no
 *       second patch; an expired command is terminal and never applied; a dry run sends no patch.</li>
 *   <li><strong>AGENT</strong> — enqueues an {@code hpaPatch} / {@code hpaCreate} command on the
 *       existing agent command queue. Deferred like the vertical agent path: agents skip a command
 *       type they do not recognise, so this is forward-compatible with agents already deployed. No
 *       direct write happens on this path.</li>
 * </ul>
 *
 * <h2>Why the bean needs an explicit {@code @Autowired} constructor</h2>
 *
 * {@code HorizontalAutoscalingProperties} asks the container whether an {@link HpaApplyExecutor} bean
 * exists while it is being built. With no annotated constructor Spring cannot choose between the two
 * here and the <strong>whole context fails to start</strong> — exactly the failure mode
 * {@code DirectInPlaceResizeExecutor} documents.
 */
@Service
public class HpaApplyExecutor {

    private static final Logger logger = LoggerFactory.getLogger(HpaApplyExecutor.class);

    /** How many correlation ids are remembered, so a replay can be answered without a second patch. */
    private static final int OUTCOME_HISTORY = 500;

    private final HpaPatchClient patchClient;
    private final List<ClusterAccessProvider> accessProviders;
    private final Clock clock;

    /** {@code correlationId -> what happened}, bounded, newest-wins eviction. */
    private final Map<String, HpaApplyOutcome> outcomes = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HpaApplyOutcome> eldest) {
                    return size() > OUTCOME_HISTORY;
                }
            });

    /**
     * The constructor Spring must use. See the class note: with neither constructor annotated the
     * container cannot build this bean, and {@code HorizontalAutoscalingProperties} fails the context
     * when it asks for it.
     */
    @Autowired
    public HpaApplyExecutor(HpaPatchClient patchClient, List<ClusterAccessProvider> accessProviders) {
        this(patchClient, accessProviders, Clock.systemUTC());
    }

    HpaApplyExecutor(HpaPatchClient patchClient, List<ClusterAccessProvider> accessProviders,
                     Clock clock) {
        this.patchClient = patchClient;
        this.clock = clock;
        List<ClusterAccessProvider> ordered = new ArrayList<>(
                accessProviders == null ? List.of() : accessProviders);
        AnnotationAwareOrderComparator.sort(ordered);
        this.accessProviders = List.copyOf(ordered);
    }

    /** Which path this executor uses: the direct fast path. */
    public ExecutionPath path() {
        return ExecutionPath.DIRECT;
    }

    /** Whether this executor can act on {@code target}. Structural; performs no I/O. */
    public boolean supports(ClusterTarget target) {
        return target != null && accessProviders.stream().anyMatch(provider -> provider.supports(target));
    }

    /**
     * Opens its own connection, for a caller that has none. The apply endpoint uses
     * {@link #submit(HpaActionCommand, ClusterAccess, HorizontalPodAutoscaler)} instead, having
     * already opened one to re-validate the submitted numbers.
     */
    public HpaApplyOutcome submit(HpaActionCommand command) throws ClusterAccessException {
        Optional<ClusterAccessProvider> provider = accessProviders.stream()
                .filter(candidate -> candidate.supports(command.target()))
                .findFirst();
        if (provider.isEmpty()) {
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "No cluster access provider can reach " + command.target().describe() + ".");
        }
        try (ClusterAccess access = provider.get().open(command.target())) {
            HorizontalPodAutoscaler current = patchClient.currentHpa(access.client(),
                    command.hpaNamespace(), command.hpaName());
            return submit(command, access, current);
        }
    }

    /**
     * Applies {@code command} to the HPA through {@code access}.
     *
     * @param current the HPA as it exists <strong>now</strong>, used to rebuild the metrics array
     *                when the CPU target moves; null when the cluster no longer has the HPA
     */
    public HpaApplyOutcome submit(HpaActionCommand command, ClusterAccess access,
                                  HorizontalPodAutoscaler current) {
        String id = command.correlationId();
        HpaApplyOutcome replay = outcomes.get(id);
        if (replay != null) {
            logger.info("HPA correlation id {} was already processed ({}); no second patch is sent",
                    id, replay.terminal());
            return record(id, HpaApplyOutcome.duplicate(id, replay));
        }

        if (command.isExpiredAt(clock.instant())) {
            return record(id, HpaApplyOutcome.expired(command,
                    "The command expired at " + command.notValidAfter() + " before it was applied,"
                            + " so it was not sent: applying it now would write bounds derived from"
                            + " measurements that are no longer current."));
        }

        if (current == null) {
            return record(id, HpaApplyOutcome.refused(command,
                    "The HPA " + command.hpaNamespace() + "/" + command.hpaName()
                            + " is no longer present in this cluster, so there is nothing to patch."));
        }

        if (command.dryRun()) {
            // A rehearsal sends no patch; it echoes what the real apply would set.
            return record(id, HpaApplyOutcome.applied(command, true));
        }

        try {
            patchClient.patch(access.client(), command.hpaNamespace(), command.hpaName(), current,
                    command.minReplicas(), command.maxReplicas(), command.targetUtilizationPct());
        } catch (HpaPatchClient.HpaPatchRejected rejected) {
            if (rejected.conflict()) {
                return record(id, HpaApplyOutcome.refused(command,
                        "The HPA " + command.hpaNamespace() + "/" + command.hpaName()
                                + " was edited between the read and this patch, so it was not"
                                + " applied: " + rejected.getMessage()));
            }
            if (rejected.answered()) {
                return record(id, HpaApplyOutcome.failed(command, rejected.getMessage()));
            }
            // No answer at all: the patch may or may not have landed. Report it as unknown, never as
            // a success and never as a clean refusal.
            return record(id, HpaApplyOutcome.failed(command,
                    "The patch of HPA " + command.hpaNamespace() + "/" + command.hpaName()
                            + " could not be confirmed: " + rejected.getMessage()));
        }
        return record(id, HpaApplyOutcome.applied(command, false));
    }

    /**
     * The AGENT path: enqueue a command the in-cluster agent fetches and applies later.
     *
     * <p>Deferred, exactly as the vertical agent path is: the cluster-scoped command table, the new
     * command type on {@code /api/v1/agent/commands} and the expiry sweep are a separate, security-
     * reviewed piece of work. Until it lands this records the command as queued and performs
     * <strong>no direct write</strong>, so a private-endpoint cluster is never patched behind the
     * agent's back.
     */
    public HpaApplyOutcome enqueueForAgent(HpaActionCommand command) {
        String id = command.correlationId();
        HpaApplyOutcome replay = outcomes.get(id);
        if (replay != null) {
            return record(id, HpaApplyOutcome.duplicate(id, replay));
        }
        logger.info("HPA command {} queued for the in-cluster agent ({} path); no direct patch sent",
                id, ExecutionPath.AGENT);
        return record(id, HpaApplyOutcome.queued(command));
    }

    /** The outcome, once recorded; empty while a command is still queued or in flight. */
    public Optional<HpaApplyOutcome> poll(String correlationId) {
        return Optional.ofNullable(correlationId == null ? null : outcomes.get(correlationId));
    }

    private HpaApplyOutcome record(String id, HpaApplyOutcome outcome) {
        outcomes.put(id, outcome);
        return outcome;
    }
}
