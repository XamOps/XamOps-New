package com.xammer.cloud.service.automation.workload;

import java.util.List;

/**
 * QoS classification and the rules that keep it intact (audit bug 7).
 *
 * <h2>What went wrong</h2>
 *
 * The measured live run wrote <strong>requests without limits</strong>. A workload whose containers
 * had {@code requests == limits} (Guaranteed) was patched to {@code requests: 1m/4Mi} against
 * untouched limits of {@code 1 CPU / 1Gi}, so its class silently became Burstable — a change in
 * eviction order and CPU scheduling guarantees that nobody asked for and nothing reported. The
 * in-place resize subresource <em>rejects</em> a QoS-class change, so this is also the reason the
 * deferred executor would have failed on every such patch.
 *
 * <h2>The rule</h2>
 *
 * Kubernetes computes QoS per pod: Guaranteed when every container has CPU and memory requests
 * equal to its limits; BestEffort when nothing is set; Burstable otherwise. This class works at
 * container granularity as well, because <strong>preserving every container's shape preserves the
 * pod's class</strong> — which makes the check local, and therefore enforceable at the point a
 * recommendation is constructed.
 */
public final class QosRules {

    /** One container's contribution to its pod's QoS class. */
    public enum Shape {
        /** Both resources set, and each request equals its limit. */
        GUARANTEED_SHAPED,
        /** Something is set, but not every request equals its limit. */
        BURSTABLE_SHAPED,
        /** Neither request is set. */
        UNSET
    }

    private QosRules() {
    }

    public static Shape shapeOf(ResourceEnvelope envelope) {
        if (envelope == null) {
            return Shape.UNSET;
        }
        boolean nothingSet = envelope.cpuRequestMillis() == null && envelope.cpuLimitMillis() == null
                && envelope.memRequestBytes() == null && envelope.memLimitBytes() == null;
        if (nothingSet) {
            return Shape.UNSET;
        }
        boolean cpuPinned = envelope.cpuRequestMillis() != null
                && envelope.cpuRequestMillis().equals(envelope.cpuLimitMillis());
        boolean memPinned = envelope.memRequestBytes() != null
                && envelope.memRequestBytes().equals(envelope.memLimitBytes());
        return cpuPinned && memPinned ? Shape.GUARANTEED_SHAPED : Shape.BURSTABLE_SHAPED;
    }

    /**
     * Whether moving from {@code current} to {@code recommended} leaves the QoS class alone.
     *
     * <p>The single allowed transition is out of {@link Shape#UNSET}: a container with no requests
     * has no class to preserve, and naming a size for it is the point of the advisory. Every such
     * recommendation is marked {@code REQUESTS_UNSET} and is never auto-applied.
     */
    public static boolean preservesShape(ResourceEnvelope current, ResourceEnvelope recommended) {
        Shape from = shapeOf(current);
        return from == Shape.UNSET || from == shapeOf(recommended);
    }

    /** The pod-level class, over every container's envelope. */
    public static QosClass podClass(List<ResourceEnvelope> containers) {
        if (containers == null || containers.isEmpty()) {
            return QosClass.BEST_EFFORT;
        }
        boolean allGuaranteed = true;
        boolean anythingSet = false;
        for (ResourceEnvelope envelope : containers) {
            Shape shape = shapeOf(envelope);
            if (shape != Shape.UNSET) {
                anythingSet = true;
            }
            if (shape != Shape.GUARANTEED_SHAPED) {
                allGuaranteed = false;
            }
        }
        if (!anythingSet) {
            return QosClass.BEST_EFFORT;
        }
        return allGuaranteed ? QosClass.GUARANTEED : QosClass.BURSTABLE;
    }

    /**
     * The limit that must accompany {@code newRequest} on a resource that is moving.
     *
     * <ul>
     *   <li><strong>Guaranteed-shaped:</strong> the limit moves <em>with</em> the request, to the same
     *       value. Anything else changes the class.</li>
     *   <li><strong>Burstable with a limit set:</strong> the request:limit ratio is preserved, then
     *       clamped to at least the new request and at most
     *       {@code currentLimit × maxLimitIncreaseFactor}. A limit is never lowered below the
     *       recommended request, so an in-place resize cannot fail validation on "request exceeds
     *       limit".</li>
     *   <li><strong>No limit on this resource:</strong> it stays absent. Inventing a limit would
     *       change the class and cap a workload the operator chose to leave uncapped.</li>
     * </ul>
     *
     * @return the new limit, or null when the resource has no limit today
     */
    public static Long limitFor(Shape shape, Long currentRequest, Long currentLimit,
                                long newRequest, double maxLimitIncreaseFactor) {
        if (currentLimit == null) {
            return shape == Shape.GUARANTEED_SHAPED ? newRequest : null;
        }
        if (shape == Shape.GUARANTEED_SHAPED) {
            return newRequest;
        }
        if (currentRequest == null || currentRequest <= 0) {
            // No ratio to preserve: keep the limit, but never below the new request.
            return Math.max(currentLimit, newRequest);
        }
        double ratio = (double) currentLimit / (double) currentRequest;
        long scaled = Math.round(newRequest * ratio);
        long ceiling = Math.max(currentLimit, Math.round(currentLimit * maxLimitIncreaseFactor));
        return Math.max(newRequest, Math.min(scaled, ceiling));
    }
}
