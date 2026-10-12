package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.QosRules;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The first real {@link WorkloadActionExecutor}: an in-place resize through the pod
 * {@code resize} subresource, with a canary, a bounded rollout, real status waits and a proof.
 *
 * <h2>What it will not do</h2>
 *
 * <ul>
 *   <li><strong>It never patches a Deployment, StatefulSet or any other template.</strong> There is
 *       no write call in this class other than {@link PodResizeClient#patchResize}, which can only
 *       address {@code pods/{name}/resize}. A template patch restarts every pod, which is the
 *       opposite of what this feature promises, and it is also what GitOps would immediately revert.
 *       Template persistence is a separate, opt-in role and a separate code path that does not
 *       exist.</li>
 *   <li><strong>It never reports an unconfirmed success.</strong> {@code APPLIED} requires every
 *       target to be visible in {@code status.containerStatuses[].resources} on every pod touched.
 *       {@code Deferred}, {@code Infeasible}, a resize still in progress at the deadline, a restart
 *       and an expiry are each reported as themselves.</li>
 *   <li><strong>It never promises restart-free without proving it</strong> — same pod uid, same
 *       {@code containerID}, unchanged {@code restartCount}, pod still {@code Ready}, per pod. A
 *       container declaring {@code resizePolicy: RestartContainer} for a resource being changed is
 *       reported as restarting, because the kubelet will restart it and claiming otherwise would be
 *       a lie.</li>
 * </ul>
 *
 * <h2>Refused before any patch</h2>
 *
 * The resize subresource rejects adding a resource that was never set, removing one, and any change
 * of QoS class. Those are checked here first, against the <em>current</em> pod template, so the
 * refusal names the rule instead of surfacing an API-server validation error — and so
 * {@code dev-sim/no-requests-sidecar-app}, which declares no {@code resources} block at all, is
 * refused honestly rather than patched.
 *
 * <h2>Canary, then two at a time</h2>
 *
 * One pod is resized and confirmed before any other is touched, and the remainder go two at a time,
 * stopping at the first failure ({@code PARTIALLY_APPLIED}). On a two- or three-replica workload
 * that bounds the blast radius of a bad number to a single pod.
 *
 * <h2>Why the direct path's outcome is available at submit time</h2>
 *
 * The interface is asynchronous because the <em>primary</em> path is the agent command queue. The
 * direct path completes inside the request, so the outcome is recorded at submit time and
 * {@link #poll(String)} serves it from a bounded in-memory map. A replayed {@code correlationId}
 * answers {@link SubmitState#DUPLICATE} and sends no second patch, which is the at-least-once
 * guarantee the command type's idempotency key exists for.
 */
@Service
public class DirectInPlaceResizeExecutor implements WorkloadActionExecutor {

    private static final Logger logger = LoggerFactory.getLogger(DirectInPlaceResizeExecutor.class);

    /** How many pods beyond the canary are resized at once. */
    static final int ROLLOUT_CONCURRENCY = 2;

    /** How many correlation ids are remembered, so a replay can be answered without a second patch. */
    private static final int OUTCOME_HISTORY = 500;

    private static final String CPU = "cpu";
    private static final String MEMORY = "memory";
    private static final String RESTART_CONTAINER = "RestartContainer";

    private final PodResizeClient resizeClient;
    private final ClusterWorkloadInventory inventory;
    private final List<ClusterAccessProvider> accessProviders;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration resizeDeadline;

    /** {@code correlationId -> what happened}, bounded, newest-wins eviction. */
    private final Map<String, Record> records = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Record> eldest) {
                    return size() > OUTCOME_HISTORY;
                }
            });

    /**
     * The constructor Spring must use.
     *
     * <p>{@code @Autowired} is not decoration here. This class has two constructors, and with neither
     * annotated Spring cannot choose: it falls back to the no-arg path, finds none, and the
     * <strong>whole application fails to start</strong> — in every profile, because
     * {@code WorkloadAutoscalingProperties} asks the container for this bean while it is being built.
     * That is how it was found: the context would not come up at all. Asserted by
     * {@code ActionCommandContractTest.theExecutorTellsSpringWhichConstructorToUse}.
     */
    @Autowired
    public DirectInPlaceResizeExecutor(PodResizeClient resizeClient,
                                       ClusterWorkloadInventory inventory,
                                       List<ClusterAccessProvider> accessProviders) {
        this(resizeClient, inventory, accessProviders, Clock.systemUTC(),
                Duration.ofSeconds(2), Duration.ofSeconds(90));
    }

    /**
     * @param pollInterval   how often the pod's status is re-read while a resize actuates
     * @param resizeDeadline how long a single pod's resize may take before it is reported unconfirmed
     */
    DirectInPlaceResizeExecutor(PodResizeClient resizeClient,
                                ClusterWorkloadInventory inventory,
                                List<ClusterAccessProvider> accessProviders,
                                Clock clock,
                                Duration pollInterval,
                                Duration resizeDeadline) {
        this.resizeClient = resizeClient;
        this.inventory = inventory;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.resizeDeadline = resizeDeadline;
        List<ClusterAccessProvider> ordered = new ArrayList<>(
                accessProviders == null ? List.of() : accessProviders);
        AnnotationAwareOrderComparator.sort(ordered);
        this.accessProviders = List.copyOf(ordered);
    }

    @Override
    public ExecutionPath path() {
        return ExecutionPath.DIRECT;
    }

    @Override
    public boolean supports(ClusterTarget target) {
        return target != null && accessProviders.stream().anyMatch(provider -> provider.supports(target));
    }

    /**
     * Opens its own connection and resolves the workload, for a caller that has neither.
     *
     * <p>{@link #submit(ActionCommand, ClusterAccess, ClusterWorkloadInventory.Workload)} is the
     * overload the apply endpoint uses: it has already opened a connection and read the inventory in
     * order to re-validate the submitted numbers against the current cluster state, and re-doing both
     * would double the cluster contact of one user action.
     */
    @Override
    public ActionSubmission submit(ActionCommand command) throws ClusterAccessException {
        Optional<ClusterAccessProvider> provider = accessProviders.stream()
                .filter(candidate -> candidate.supports(command.target()))
                .findFirst();
        if (provider.isEmpty()) {
            throw new ClusterAccessException(ClusterAccessException.Kind.CLUSTER_ACCESS_NOT_CONFIGURED,
                    "No cluster access provider can reach " + command.target().describe() + ".");
        }
        try (ClusterAccess access = provider.get().open(command.target())) {
            ClusterWorkloadInventory.Workload workload = inventory.read(access.client()).workloads().stream()
                    .filter(candidate -> sameWorkload(candidate, command))
                    .findFirst()
                    .orElse(null);
            return submit(command, access, workload);
        }
    }

    /**
     * Applies {@code command} to {@code workload} through {@code access}.
     *
     * @param workload the workload as it exists <strong>now</strong>, which is what every refusal is
     *                 checked against; null when the cluster no longer has it
     */
    public ActionSubmission submit(ActionCommand command, ClusterAccess access,
                                   ClusterWorkloadInventory.Workload workload) {
        String id = command.correlationId();
        Record replay = records.get(id);
        if (replay != null) {
            logger.info("Correlation id {} was already processed ({}); no second patch is sent", id,
                    replay.proof().outcome());
            return ActionSubmission.duplicate(id);
        }

        if (command.isExpiredAt(clock.instant())) {
            String detail = "The command expired at " + command.notValidAfter()
                    + " before it was applied, so it was not sent: applying it now would write numbers"
                    + " derived from measurements that are no longer current.";
            return record(command, ResizeProof.expired(detail), SubmitState.REFUSED);
        }

        if (workload == null) {
            return record(command, ResizeProof.refused("The workload " + command.ref().describe()
                    + " is no longer present in this cluster, so there is nothing to resize."),
                    SubmitState.REFUSED);
        }

        Preflight preflight = preflight(command, workload);
        if (preflight.refusal() != null) {
            return record(command, ResizeProof.refused(preflight.refusal()), SubmitState.REFUSED);
        }

        if (command.dryRun()) {
            return record(command, dryRun(preflight), SubmitState.ACCEPTED);
        }

        List<WorkloadPods.Pod> pods = workload.pods().pods();
        if (pods.isEmpty()) {
            return record(command, ResizeProof.refused("The workload " + command.ref().describe()
                    + " currently has no pods, so there is nothing to resize in place."),
                    SubmitState.REFUSED);
        }

        return record(command, rollout(access.client(), command, preflight, pods), SubmitState.ACCEPTED);
    }

    @Override
    public Optional<ActionOutcome> poll(String correlationId) {
        Record stored = correlationId == null ? null : records.get(correlationId);
        return Optional.ofNullable(stored).map(Record::outcome);
    }

    /** The full proof for one submission, which is what the apply endpoint's {@code result} renders. */
    public Optional<ResizeProof> proof(String correlationId) {
        Record stored = correlationId == null ? null : records.get(correlationId);
        return Optional.ofNullable(stored).map(Record::proof);
    }

    /**
     * There is nothing to cancel: the direct path completes inside the submitting request, so by the
     * time a caller could ask, the outcome is already recorded and {@link #poll(String)} has it.
     */
    @Override
    public void cancel(String correlationId) {
        logger.info("Cancellation of {} is a no-op on the direct path: it completes synchronously",
                correlationId);
    }

    // ---------------------------------------------------------------- rollout

    /** Canary one pod, confirm it, then the rest two at a time, stopping at the first failure. */
    private ResizeProof rollout(KubernetesClient client, ActionCommand command, Preflight preflight,
                                List<WorkloadPods.Pod> pods) {
        String namespace = command.ref().namespace();
        List<ContainerApplyTarget> targets = preflight.targets();

        PodAttempt canary = resizeOnePod(client, namespace, pods.get(0), command, preflight);
        if (!canary.succeeded()) {
            logger.warn("Canary resize of pod {}/{} did not succeed ({}); {} further pod(s) were left"
                            + " untouched", namespace, pods.get(0).name(), canary.outcome(),
                    pods.size() - 1);
            return new ResizeProof(canary.outcome(), canary.detail(), false,
                    containerResults(preflight, canary.containerState(), canary.detail()),
                    canary.proof() == null ? List.of() : List.of(canary.proof()));
        }

        List<ResizeProof.PodProof> proofs = new ArrayList<>();
        proofs.add(canary.proof());
        List<WorkloadPods.Pod> remaining = pods.subList(1, pods.size());

        PodAttempt firstFailure = null;
        if (!remaining.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(ROLLOUT_CONCURRENCY, remaining.size()));
            try {
                for (int from = 0; from < remaining.size() && firstFailure == null;
                     from += ROLLOUT_CONCURRENCY) {
                    List<WorkloadPods.Pod> batch =
                            remaining.subList(from, Math.min(from + ROLLOUT_CONCURRENCY, remaining.size()));
                    List<Future<PodAttempt>> futures = new ArrayList<>();
                    for (WorkloadPods.Pod pod : batch) {
                        futures.add(pool.submit(
                                () -> resizeOnePod(client, namespace, pod, command, preflight)));
                    }
                    for (Future<PodAttempt> future : futures) {
                        PodAttempt attempt = await(future, namespace);
                        if (attempt.proof() != null) {
                            proofs.add(attempt.proof());
                        }
                        if (!attempt.succeeded() && firstFailure == null) {
                            firstFailure = attempt;
                        }
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }

        if (firstFailure != null) {
            String detail = "The canary pod was resized and confirmed, but pod "
                    + firstFailure.podName() + " was not (" + firstFailure.outcome() + "): "
                    + firstFailure.detail() + " The rollout stopped there.";
            return new ResizeProof(ResizeProof.Outcome.PARTIALLY_APPLIED, detail, false,
                    containerResults(preflight, ResizeProof.ContainerState.FAILED, detail), proofs);
        }

        boolean restartFree = proofs.stream().allMatch(DirectInPlaceResizeExecutor::provesRestartFree);
        List<ResizeProof.ContainerResult> results =
                containerResults(preflight, ResizeProof.ContainerState.APPLIED, null);
        if (!restartFree) {
            // Every pod confirmed the new values, but at least one container was restarted doing it
            // -- which is correct behaviour for a RestartContainer resize policy and must still not
            // be published as "restart-free".
            logger.info("Resize of {} applied on {} pod(s), but restart-free could not be proved for"
                    + " all of them", command.ref().describe(), proofs.size());
        }
        return new ResizeProof(ResizeProof.Outcome.APPLIED, null, restartFree, results, proofs);
    }

    private PodAttempt await(Future<PodAttempt> future, String namespace) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PodAttempt.failed("?", ResizeProof.Outcome.FAILED,
                    "The rollout in namespace " + namespace + " was interrupted, so whether this pod"
                            + " was resized is not known.", null);
        } catch (ExecutionException failed) {
            return PodAttempt.failed("?", ResizeProof.Outcome.FAILED,
                    "A pod resize in namespace " + namespace + " failed unexpectedly: "
                            + failed.getCause(), null);
        }
    }

    /** One pod: read the before-state, patch, poll the status, then prove nothing restarted. */
    private PodAttempt resizeOnePod(KubernetesClient client, String namespace, WorkloadPods.Pod pod,
                                    ActionCommand command, Preflight preflight) {
        PodResizeStatus before = readStatus(client, namespace, pod.name());
        if (before == null) {
            return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                    "Pod " + namespace + "/" + pod.name() + " no longer exists, so it was not resized.",
                    null);
        }
        if (pod.uid() != null && before.uid() != null && !pod.uid().equals(before.uid())) {
            // Same name, different pod: the pod that was measured is gone and this is its
            // replacement, so the submitted numbers were derived from something else.
            return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                    "Pod " + namespace + "/" + pod.name() + " was replaced between the read and the"
                            + " apply (uid " + pod.uid() + " is now " + before.uid()
                            + "), so it was not resized.", null);
        }

        PodResizeStatus patched;
        try {
            patched = PodResizeStatus.of(
                    resizeClient.patchResize(client, namespace, pod.name(), preflight.targets()));
        } catch (PodResizeClient.ResizeRejected rejected) {
            return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED, rejected.getMessage(),
                    proofOf(before, before));
        }

        Instant deadline = pollDeadline(command);
        PodResizeStatus latest = patched;
        while (true) {
            if (latest.isInfeasible()) {
                return PodAttempt.failed(pod.name(), ResizeProof.Outcome.INFEASIBLE,
                        "The kubelet reported the resize of pod " + namespace + "/" + pod.name()
                                + " as Infeasible, so it will never be applied on this node ("
                                + latest.describePending() + "). Nothing was changed.",
                        proofOf(before, latest));
            }
            if (latest.isDeferred()) {
                return PodAttempt.failed(pod.name(), ResizeProof.Outcome.DEFERRED,
                        "The kubelet deferred the resize of pod " + namespace + "/" + pod.name()
                                + " and will retry when there is capacity (" + latest.describePending()
                                + "). It is not applied.",
                        proofOf(before, latest));
            }
            if (latest.isResizeError()) {
                return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                        "The kubelet reported an error while resizing pod " + namespace + "/"
                                + pod.name() + " (" + latest.describePending() + ").",
                        proofOf(before, latest));
            }
            if (latest.appliedAll(preflight.targets())) {
                return verify(namespace, pod, before, latest, preflight);
            }
            if (!clock.instant().isBefore(deadline)) {
                break;
            }
            sleep();
            PodResizeStatus next = readStatus(client, namespace, pod.name());
            if (next == null) {
                return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                        "Pod " + namespace + "/" + pod.name() + " disappeared while its resize was"
                                + " actuating, so the outcome is not known.", proofOf(before, latest));
            }
            latest = next;
        }

        if (latest.isResizeInProgress()) {
            // The memory-decrease case: the kubelet will not shrink below live usage, so the resize
            // stays in progress indefinitely. Waiting longer would not change the answer.
            return PodAttempt.failed(pod.name(), ResizeProof.Outcome.STUCK_IN_PROGRESS,
                    "The resize of pod " + namespace + "/" + pod.name() + " was still in progress at"
                            + " the deadline and was not confirmed. A memory decrease the kubelet"
                            + " cannot complete stays in this state.", proofOf(before, latest));
        }
        return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                "The resize of pod " + namespace + "/" + pod.name() + " was not visible in the pod's"
                        + " status before the deadline, so it is not confirmed.",
                proofOf(before, latest));
    }

    /**
     * The restart-free proof, per pod: same uid, same {@code containerID}, unchanged
     * {@code restartCount}, still {@code Ready}.
     *
     * <p>A container declaring {@code resizePolicy: RestartContainer} for a resource being changed is
     * <em>expected</em> to restart, so its restart is not a failure — but it does mean restart-free
     * cannot be claimed, which {@link ResizeProof#restartFree()} then reports as false.
     */
    private PodAttempt verify(String namespace, WorkloadPods.Pod pod, PodResizeStatus before,
                              PodResizeStatus after, Preflight preflight) {
        ResizeProof.PodProof proof = proofOf(before, after);
        if (before.uid() != null && !before.uid().equals(after.uid())) {
            return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                    "Pod " + namespace + "/" + pod.name() + " was replaced during the resize (uid "
                            + before.uid() + " is now " + after.uid() + "), so the values now in"
                            + " place belong to a different pod.", proof);
        }
        for (ContainerApplyTarget target : preflight.targets()) {
            String name = target.containerName();
            PodResizeStatus.ContainerState was = before.container(name);
            PodResizeStatus.ContainerState is = after.container(name);
            boolean restartExpected = preflight.restartExpected().contains(name);
            if (was == null || is == null) {
                return PodAttempt.failed(pod.name(), ResizeProof.Outcome.FAILED,
                        "Container " + name + " of pod " + namespace + "/" + pod.name()
                                + " has no status, so a restart-free resize cannot be proved.", proof);
            }
            boolean restarted = is.restartCount() != was.restartCount()
                    || (was.containerID() != null && !was.containerID().equals(is.containerID()));
            if (restarted && !restartExpected) {
                return PodAttempt.failed(pod.name(), ResizeProof.Outcome.RESTART_DETECTED,
                        "restart detected: container " + name + " of pod " + namespace + "/"
                                + pod.name() + " went from restartCount " + was.restartCount()
                                + " to " + is.restartCount() + " (containerID " + was.containerID()
                                + " -> " + is.containerID() + "), so this was not an in-place,"
                                + " restart-free resize.", proof);
            }
        }
        if (!after.ready()) {
            return PodAttempt.failed(pod.name(), ResizeProof.Outcome.NOT_READY,
                    "Pod " + namespace + "/" + pod.name() + " applied the new resources but is no"
                            + " longer Ready, so the resize is not a success.", proof);
        }
        return PodAttempt.succeeded(pod.name(), proof);
    }

    private Instant pollDeadline(ActionCommand command) {
        Instant byDuration = clock.instant().plus(resizeDeadline);
        return byDuration.isBefore(command.notValidAfter()) ? byDuration : command.notValidAfter();
    }

    private void sleep() {
        try {
            Thread.sleep(Math.max(1L, pollInterval.toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private PodResizeStatus readStatus(KubernetesClient client, String namespace, String podName) {
        try {
            Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
            return pod == null ? null : PodResizeStatus.of(pod);
        } catch (KubernetesClientException unreadable) {
            logger.warn("Could not read pod {}/{}: {}", namespace, podName, unreadable.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- preflight

    /**
     * Everything the subresource would reject, checked against the workload as it is now.
     *
     * <p>Note what cannot be expressed and therefore needs no check: {@link ContainerApplyTarget}
     * uses {@code null} for "leave this alone", so there is no way to ask for a resource to be
     * <em>removed</em>, and its own constructor already rejects a request above its own limit and a
     * non-positive value.
     */
    private Preflight preflight(ActionCommand command, ClusterWorkloadInventory.Workload workload) {
        Map<String, ClusterWorkloadInventory.ContainerSpec> byName = new LinkedHashMap<>();
        for (ClusterWorkloadInventory.ContainerSpec container : workload.containers()) {
            byName.put(container.name(), container);
        }
        List<String> restartExpected = new ArrayList<>();
        for (ContainerApplyTarget target : command.containers()) {
            ClusterWorkloadInventory.ContainerSpec container = byName.get(target.containerName());
            if (container == null) {
                return Preflight.refused("Container " + target.containerName() + " is not part of "
                        + command.ref().describe() + " any more, so it cannot be resized.");
            }
            ResourceEnvelope current = container.current();
            String refusal = refusalFor(target, current);
            if (refusal != null) {
                return Preflight.refused(refusal);
            }
            if (restartsOnResize(container, target)) {
                restartExpected.add(target.containerName());
            }
        }
        return new Preflight(List.copyOf(command.containers()), List.copyOf(restartExpected), null);
    }

    /** @return the refusal, or null when this target is applicable in place */
    private static String refusalFor(ContainerApplyTarget target, ResourceEnvelope current) {
        if (QosRules.shapeOf(current) == QosRules.Shape.UNSET) {
            return "Container " + target.containerName() + " has no resource requests or limits at"
                    + " all, so it is BestEffort. The in-place resize subresource cannot add"
                    + " resources to a container that was created without them, and doing it through"
                    + " the template would restart the pod — so this needs a deliberate redeploy,"
                    + " not an in-place resize.";
        }
        String missing = firstUnsetFieldBeingSet(target, current);
        if (missing != null) {
            return "Container " + target.containerName() + " has no " + missing + " today. The"
                    + " in-place resize subresource can change a resource that is already set but"
                    + " cannot add one, so this would change the container's QoS class and be"
                    + " rejected.";
        }
        ResourceEnvelope wanted = merge(current, target);
        if (wanted.cpuRequestMillis() != null && wanted.cpuLimitMillis() != null
                && wanted.cpuRequestMillis() > wanted.cpuLimitMillis()) {
            return "Container " + target.containerName() + ": the submitted CPU request "
                    + wanted.cpuRequestMillis() + "m would exceed its limit "
                    + wanted.cpuLimitMillis() + "m, which the resize subresource rejects.";
        }
        if (wanted.memRequestBytes() != null && wanted.memLimitBytes() != null
                && wanted.memRequestBytes() > wanted.memLimitBytes()) {
            return "Container " + target.containerName() + ": the submitted memory request "
                    + wanted.memRequestBytes() + "B would exceed its limit "
                    + wanted.memLimitBytes() + "B, which the resize subresource rejects.";
        }
        if (QosRules.shapeOf(current) != QosRules.shapeOf(wanted)) {
            return "Container " + target.containerName() + ": this would change the container's QoS"
                    + " shape from " + QosRules.shapeOf(current) + " to " + QosRules.shapeOf(wanted)
                    + ". QoS class is fixed when a pod is created and the resize subresource rejects"
                    + " a change to it.";
        }
        return null;
    }

    private static String firstUnsetFieldBeingSet(ContainerApplyTarget target, ResourceEnvelope current) {
        if (target.cpuRequestMillis() != null && current.cpuRequestMillis() == null) {
            return "CPU request";
        }
        if (target.cpuLimitMillis() != null && current.cpuLimitMillis() == null) {
            return "CPU limit";
        }
        if (target.memRequestBytes() != null && current.memRequestBytes() == null) {
            return "memory request";
        }
        if (target.memLimitBytes() != null && current.memLimitBytes() == null) {
            return "memory limit";
        }
        return null;
    }

    /**
     * {@code current} with the target's non-null fields applied: what the container would become.
     *
     * <p>Public because the apply endpoint re-validates a submitted body against the same question
     * before it ever builds a command, and the two answers must be computed by the same code — a
     * second implementation of "what would this become" is how a QoS check starts disagreeing with
     * the one the cluster will actually enforce.
     */
    public static ResourceEnvelope merge(ResourceEnvelope current, ContainerApplyTarget target) {
        return new ResourceEnvelope(
                target.cpuRequestMillis() != null ? target.cpuRequestMillis() : current.cpuRequestMillis(),
                target.cpuLimitMillis() != null ? target.cpuLimitMillis() : current.cpuLimitMillis(),
                target.memRequestBytes() != null ? target.memRequestBytes() : current.memRequestBytes(),
                target.memLimitBytes() != null ? target.memLimitBytes() : current.memLimitBytes());
    }

    /**
     * Whether the kubelet will restart this container to apply the change, because it declares
     * {@code resizePolicy: RestartContainer} for a resource the command moves.
     */
    private static boolean restartsOnResize(ClusterWorkloadInventory.ContainerSpec container,
                                            ContainerApplyTarget target) {
        Map<String, String> policy = container.resizePolicy();
        boolean cpuMoving = target.cpuRequestMillis() != null || target.cpuLimitMillis() != null;
        boolean memMoving = target.memRequestBytes() != null || target.memLimitBytes() != null;
        return (cpuMoving && RESTART_CONTAINER.equalsIgnoreCase(policy.get(CPU)))
                || (memMoving && RESTART_CONTAINER.equalsIgnoreCase(policy.get(MEMORY)));
    }

    // ---------------------------------------------------------------- results

    private ResizeProof dryRun(Preflight preflight) {
        List<ResizeProof.ContainerResult> results = new ArrayList<>();
        for (ContainerApplyTarget target : preflight.targets()) {
            results.add(new ResizeProof.ContainerResult(target.containerName(),
                    ResizeProof.ContainerState.DRY_RUN,
                    "Dry run: this is the change that would have been sent.", requested(target),
                    preflight.restartExpected().contains(target.containerName())));
        }
        return new ResizeProof(ResizeProof.Outcome.DRY_RUN,
                "Dry run: no patch was sent to the cluster, so nothing was applied.", false,
                results, List.of());
    }

    private static List<ResizeProof.ContainerResult> containerResults(Preflight preflight,
                                                                      ResizeProof.ContainerState state,
                                                                      String detail) {
        List<ResizeProof.ContainerResult> results = new ArrayList<>();
        for (ContainerApplyTarget target : preflight.targets()) {
            results.add(new ResizeProof.ContainerResult(target.containerName(), state, detail,
                    requested(target), preflight.restartExpected().contains(target.containerName())));
        }
        return results;
    }

    private static ResourceEnvelope requested(ContainerApplyTarget target) {
        return new ResourceEnvelope(target.cpuRequestMillis(), target.cpuLimitMillis(),
                target.memRequestBytes(), target.memLimitBytes());
    }

    private static ResizeProof.PodProof proofOf(PodResizeStatus before, PodResizeStatus after) {
        List<ResizeProof.ContainerProof> containers = new ArrayList<>();
        for (Map.Entry<String, PodResizeStatus.ContainerState> entry : after.containers().entrySet()) {
            PodResizeStatus.ContainerState was = before.container(entry.getKey());
            PodResizeStatus.ContainerState is = entry.getValue();
            containers.add(new ResizeProof.ContainerProof(entry.getKey(),
                    was == null ? null : was.containerID(), is.containerID(),
                    was == null ? null : was.restartCount(), is.restartCount(),
                    was == null ? null : was.observed(), is.observed()));
        }
        return new ResizeProof.PodProof(after.podName(), before.uid(), after.uid(), after.ready(),
                containers);
    }

    private static boolean provesRestartFree(ResizeProof.PodProof proof) {
        return proof.samePod() && proof.ready()
                && proof.containers().stream().allMatch(ResizeProof.ContainerProof::sameContainer);
    }

    private ActionSubmission record(ActionCommand command, ResizeProof proof, SubmitState state) {
        ActionOutcome outcome = new ActionOutcome(command.correlationId(), command.dryRun(),
                proof.publishedTerminal(), perContainer(proof), clock.instant(), 1, proof.detail());
        records.put(command.correlationId(), new Record(outcome, proof));
        return switch (state) {
            case ACCEPTED -> ActionSubmission.accepted(command.correlationId());
            case DUPLICATE -> ActionSubmission.duplicate(command.correlationId());
            case REFUSED -> ActionSubmission.refused(command.correlationId(), proof.detail());
        };
    }

    private static Map<String, ActionOutcome.ContainerApplyStatus> perContainer(ResizeProof proof) {
        Map<String, ActionOutcome.ContainerApplyStatus> byName = new LinkedHashMap<>();
        for (ResizeProof.ContainerResult result : proof.containers()) {
            byName.put(result.name(), new ActionOutcome.ContainerApplyStatus(result.name(),
                    result.applied(), result.detail()));
        }
        return byName;
    }

    /** What was remembered for one correlation id, so a replay is answered without a second patch. */
    private record Record(ActionOutcome outcome, ResizeProof proof) {
    }

    /**
     * The checked command: the targets to send, which containers will restart doing it, and the
     * refusal if there is one.
     */
    private record Preflight(List<ContainerApplyTarget> targets, List<String> restartExpected,
                             String refusal) {

        private static Preflight refused(String refusal) {
            return new Preflight(List.of(), List.of(), refusal);
        }
    }

    /** One pod's attempt. */
    private record PodAttempt(String podName, ResizeProof.Outcome outcome, String detail,
                              ResizeProof.PodProof proof) {

        private static PodAttempt succeeded(String podName, ResizeProof.PodProof proof) {
            return new PodAttempt(podName, ResizeProof.Outcome.APPLIED, null, proof);
        }

        private static PodAttempt failed(String podName, ResizeProof.Outcome outcome, String detail,
                                         ResizeProof.PodProof proof) {
            return new PodAttempt(podName, outcome, detail, proof);
        }

        private boolean succeeded() {
            return outcome == ResizeProof.Outcome.APPLIED;
        }

        private ResizeProof.ContainerState containerState() {
            return switch (outcome) {
                case APPLIED -> ResizeProof.ContainerState.APPLIED;
                case DEFERRED -> ResizeProof.ContainerState.DEFERRED;
                case INFEASIBLE -> ResizeProof.ContainerState.INFEASIBLE;
                case STUCK_IN_PROGRESS -> ResizeProof.ContainerState.STUCK_IN_PROGRESS;
                case RESTART_DETECTED -> ResizeProof.ContainerState.RESTARTED;
                case REFUSED -> ResizeProof.ContainerState.REFUSED;
                case DRY_RUN -> ResizeProof.ContainerState.DRY_RUN;
                default -> ResizeProof.ContainerState.FAILED;
            };
        }
    }

    private static boolean sameWorkload(ClusterWorkloadInventory.Workload candidate,
                                        ActionCommand command) {
        return candidate.ref().namespace().equals(command.ref().namespace())
                && candidate.ref().kind().equalsIgnoreCase(command.ref().kind())
                && candidate.ref().name().equals(command.ref().name());
    }
}
