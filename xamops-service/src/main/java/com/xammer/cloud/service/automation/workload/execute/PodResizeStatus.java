package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.QuantityParseException;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a pod's status actually says about a resize — the only evidence an apply may be judged from.
 *
 * <h2>Completion is read from the status, not from the spec</h2>
 *
 * The API server accepts a resize patch the moment it validates, so {@code spec} reflects the
 * <em>request</em>. {@code status.containerStatuses[].resources} is the value the kubelet has
 * actually put in place, which is why that — and not the spec, and not
 * {@code allocatedResources}, which is kubelet bookkeeping — is what {@link #applied} compares
 * against.
 *
 * <h2>{@code status.resize} is read but never required</h2>
 *
 * From Kubernetes 1.33 the {@code status.resize} field is <strong>superseded</strong> by the
 * {@code PodResizePending} and {@code PodResizeInProgress} conditions, and a 1.33+ API server may not
 * set it at all. This cluster is 1.36. A poller that waited for {@code status.resize} to clear would
 * therefore hang for ever against exactly the servers this feature targets, so it is captured for
 * diagnostics and nothing branches on its absence.
 *
 * <h2>The two conditions, and their reasons</h2>
 *
 * <ul>
 *   <li>{@code PodResizePending} / {@code Deferred} — the kubelet cannot do it <em>right now</em>
 *       (typically no node capacity) and will retry. Not a failure, and not a success.</li>
 *   <li>{@code PodResizePending} / {@code Infeasible} — the kubelet will <strong>never</strong> do it
 *       on this node. Terminal.</li>
 *   <li>{@code PodResizeInProgress} — actuation is under way; with {@code reason: Error} something
 *       went wrong during it. A memory <em>decrease</em> can sit here indefinitely, because the
 *       kubelet will not shrink a limit below live usage; that is the "stuck in progress" case, and
 *       it is reported as itself rather than waited out for ever.</li>
 * </ul>
 *
 * @param podName            {@code metadata.name}
 * @param uid                {@code metadata.uid} — identity that survives a name being reused
 * @param ready              whether the pod's {@code Ready} condition is True
 * @param observedGeneration {@code status.observedGeneration}, or null when the server omits it
 * @param containers         per-container status, keyed by container name
 * @param pending            the {@code PodResizePending} condition, or null
 * @param inProgress         the {@code PodResizeInProgress} condition, or null
 * @param legacyResize       {@code status.resize}, for diagnostics only; superseded from 1.33
 */
public record PodResizeStatus(String podName,
                              String uid,
                              boolean ready,
                              Long observedGeneration,
                              Map<String, ContainerState> containers,
                              Condition pending,
                              Condition inProgress,
                              String legacyResize) {

    private static final Logger logger = LoggerFactory.getLogger(PodResizeStatus.class);

    public static final String PENDING_CONDITION = "PodResizePending";
    public static final String IN_PROGRESS_CONDITION = "PodResizeInProgress";
    public static final String REASON_DEFERRED = "Deferred";
    public static final String REASON_INFEASIBLE = "Infeasible";
    public static final String REASON_ERROR = "Error";

    private static final String READY_CONDITION = "Ready";
    private static final String CONDITION_TRUE = "True";
    private static final String CPU = "cpu";
    private static final String MEMORY = "memory";

    public PodResizeStatus {
        containers = containers == null ? Map.of() : Map.copyOf(containers);
    }

    /**
     * Reads one pod.
     *
     * @throws IllegalArgumentException when the pod carries no metadata at all, which is not a state
     *                                 the API server produces and must not be silently defaulted
     */
    public static PodResizeStatus of(Pod pod) {
        Objects.requireNonNull(pod, "pod");
        if (pod.getMetadata() == null || pod.getMetadata().getName() == null) {
            throw new IllegalArgumentException("a pod with no metadata.name cannot be verified");
        }
        Map<String, ResourceEnvelope> declared = declaredResources(pod);

        Map<String, ContainerState> containers = new LinkedHashMap<>();
        List<ContainerStatus> statuses = pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                ? List.of() : pod.getStatus().getContainerStatuses();
        for (ContainerStatus status : statuses) {
            if (status.getName() == null) {
                continue;
            }
            containers.put(status.getName(), new ContainerState(
                    status.getName(),
                    status.getContainerID(),
                    status.getRestartCount() == null ? 0 : status.getRestartCount(),
                    Boolean.TRUE.equals(status.getReady()),
                    envelope(status.getResources(), "status.resources of " + status.getName()),
                    allocated(status),
                    declared.get(status.getName())));
        }

        return new PodResizeStatus(
                pod.getMetadata().getName(),
                pod.getMetadata().getUid(),
                conditionIsTrue(pod, READY_CONDITION),
                pod.getStatus() == null ? null : pod.getStatus().getObservedGeneration(),
                containers,
                condition(pod, PENDING_CONDITION),
                condition(pod, IN_PROGRESS_CONDITION),
                pod.getStatus() == null ? null : pod.getStatus().getResize());
    }

    // ---------------------------------------------------------------- questions a poller asks

    /** The kubelet will never satisfy this resize on this node. Terminal, and never a success. */
    public boolean isInfeasible() {
        return pending != null && pending.isTrue() && REASON_INFEASIBLE.equalsIgnoreCase(pending.reason());
    }

    /** The kubelet cannot satisfy it right now — typically no node capacity — and will retry. */
    public boolean isDeferred() {
        return pending != null && pending.isTrue() && REASON_DEFERRED.equalsIgnoreCase(pending.reason());
    }

    /** Actuation is under way. A memory decrease can stay here indefinitely. */
    public boolean isResizeInProgress() {
        return inProgress != null && inProgress.isTrue();
    }

    /** Something failed during actuation: {@code PodResizeInProgress} with {@code reason: Error}. */
    public boolean isResizeError() {
        return isResizeInProgress() && REASON_ERROR.equalsIgnoreCase(inProgress.reason());
    }

    /**
     * Whether every field of {@code target} is <strong>already in place</strong> according to
     * {@code status.containerStatuses[].resources}.
     *
     * @return false when the container has no status yet, which is "not confirmed", never "done"
     */
    public boolean applied(ContainerApplyTarget target) {
        ContainerState state = containers.get(target.containerName());
        if (state == null || state.observed() == null) {
            return false;
        }
        ResourceEnvelope observed = state.observed();
        return matches(target.cpuRequestMillis(), observed.cpuRequestMillis())
                && matches(target.cpuLimitMillis(), observed.cpuLimitMillis())
                && matches(target.memRequestBytes(), observed.memRequestBytes())
                && matches(target.memLimitBytes(), observed.memLimitBytes());
    }

    public boolean appliedAll(List<ContainerApplyTarget> targets) {
        for (ContainerApplyTarget target : targets) {
            if (!applied(target)) {
                return false;
            }
        }
        return true;
    }

    public ContainerState container(String name) {
        return containers.get(name);
    }

    /** The reason and message a refusal or a wait should be reported with, or null when neither. */
    public String describePending() {
        if (pending != null && pending.isTrue()) {
            return pending.describe();
        }
        if (isResizeError()) {
            return inProgress.describe();
        }
        return null;
    }

    private static boolean matches(Number requested, Number observed) {
        if (requested == null) {
            return true;        // "leave this alone" is satisfied by whatever is there
        }
        return observed != null && requested.longValue() == observed.longValue();
    }

    // ---------------------------------------------------------------- reading

    private static Map<String, ResourceEnvelope> declaredResources(Pod pod) {
        Map<String, ResourceEnvelope> byName = new LinkedHashMap<>();
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return byName;
        }
        for (Container container : pod.getSpec().getContainers()) {
            if (container.getName() == null) {
                continue;
            }
            byName.put(container.getName(),
                    envelope(container.getResources(), "spec.resources of " + container.getName()));
        }
        return byName;
    }

    /**
     * {@code allocatedResources} is kubelet bookkeeping of what it has <em>reserved</em>, not what is
     * in place. It is carried for the proof record and nothing is judged from it.
     */
    private static ResourceEnvelope allocated(ContainerStatus status) {
        Map<String, Quantity> allocated = status.getAllocatedResources();
        if (allocated == null || allocated.isEmpty()) {
            return null;
        }
        try {
            return new ResourceEnvelope(cpu(allocated), null, memory(allocated), null);
        } catch (QuantityParseException unreadable) {
            logger.debug("Unreadable allocatedResources on container {}: {}", status.getName(),
                    unreadable.getMessage());
            return null;
        }
    }

    /**
     * @return the envelope, or null when the resource block is absent or unreadable — never a
     *         fabricated zero, because a zero here would be compared against a real request and
     *         would read as "not applied yet" rather than "unknown"
     */
    private static ResourceEnvelope envelope(ResourceRequirements resources, String where) {
        if (resources == null) {
            return null;
        }
        try {
            return new ResourceEnvelope(
                    cpu(resources.getRequests()), cpu(resources.getLimits()),
                    memory(resources.getRequests()), memory(resources.getLimits()));
        } catch (QuantityParseException unreadable) {
            logger.warn("Unreadable {}: {}", where, unreadable.getMessage());
            return null;
        }
    }

    private static Integer cpu(Map<String, Quantity> values) {
        Quantity quantity = values == null ? null : values.get(CPU);
        return quantity == null ? null : Quantities.cpuMillis(quantity.toString(), "cpu");
    }

    private static Long memory(Map<String, Quantity> values) {
        Quantity quantity = values == null ? null : values.get(MEMORY);
        return quantity == null ? null : Quantities.memBytes(quantity.toString(), "memory");
    }

    private static Condition condition(Pod pod, String type) {
        for (PodCondition condition : conditions(pod)) {
            if (type.equalsIgnoreCase(condition.getType())) {
                return new Condition(condition.getType(), condition.getStatus(), condition.getReason(),
                        condition.getMessage());
            }
        }
        return null;
    }

    private static boolean conditionIsTrue(Pod pod, String type) {
        for (PodCondition condition : conditions(pod)) {
            if (type.equalsIgnoreCase(condition.getType())) {
                return CONDITION_TRUE.equalsIgnoreCase(condition.getStatus());
            }
        }
        return false;
    }

    private static List<PodCondition> conditions(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return List.of();
        }
        return pod.getStatus().getConditions();
    }

    // ---------------------------------------------------------------- records

    /**
     * One container's status.
     *
     * @param containerID  the running container's id; a change here is a restart, whatever
     *                     {@code restartCount} says
     * @param restartCount {@code status.containerStatuses[].restartCount}
     * @param observed     {@code status.containerStatuses[].resources} — the ACTUAL applied values,
     *                     or null when the kubelet has not reported them
     * @param allocated    {@code allocatedResources}; diagnostics only
     * @param declared     {@code spec.containers[].resources} on the same pod, for the before/after
     *                     record
     */
    public record ContainerState(String name, String containerID, int restartCount, boolean ready,
                                 ResourceEnvelope observed, ResourceEnvelope allocated,
                                 ResourceEnvelope declared) {

        public ContainerState {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("container name is mandatory");
            }
        }
    }

    /** One pod condition, flattened to the four fields that matter here. */
    public record Condition(String type, String status, String reason, String message) {

        public boolean isTrue() {
            return CONDITION_TRUE.equalsIgnoreCase(status);
        }

        /** {@code Deferred: 0/4 nodes had enough capacity} — the reason and what the kubelet said. */
        public String describe() {
            String detail = message == null || message.isBlank() ? null : message;
            String why = reason == null || reason.isBlank() ? type : reason;
            return detail == null ? why : why + ": " + detail;
        }
    }
}
