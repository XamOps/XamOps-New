package com.xammer.cloud.service.automation.workload.inventory;

import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.QuantityParseException;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads upstream VPA's own recommendations, so the UI can show them beside XamOps'.
 *
 * <h2>Why</h2>
 *
 * The honest way to present a new rightsizer is next to the thing the industry already trusts. The
 * harness installs nine {@code updateMode: "Off"} VPA objects precisely so both numbers exist for the
 * same workload at the same moment. This reader needs one read-only ClusterRole rule beyond the set
 * the brief enumerates ({@code autoscaling.k8s.io/verticalpodautoscalers: get,list,watch}), which is
 * committed as a separately-labelled optional rule.
 *
 * <h2>Not granted, or not installed, is never a number</h2>
 *
 * A {@code 403} (the optional rule was not granted) or a {@code 404} (the CRD is not installed)
 * yields {@link View#notReadable()}: every container's {@code upstreamVpa} is {@code null} and the
 * workload carries the finding {@link #FINDING_NOT_READABLE}, which the UI renders as "—" with a
 * "not granted" note. There is no code path here that produces a target XamOps did not read from the
 * VPA object itself.
 */
@Component
public class UpstreamVpaReader {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamVpaReader.class);

    /** The workload finding that says the comparison column has no source, rather than no value. */
    public static final String FINDING_NOT_READABLE = "VPA_NOT_READABLE";

    /** What {@code ContainerDto.upstreamVpa.source} reports, so a number always names its origin. */
    public static final String SOURCE = "vpa";

    private static final CustomResourceDefinitionContext VPA_CONTEXT =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup("autoscaling.k8s.io")
                    .withVersion("v1")
                    .withScope("Namespaced")
                    .withPlural("verticalpodautoscalers")
                    .build();

    /**
     * Every VPA recommendation in the cluster, keyed by {@code namespace/kind/name} of the workload
     * its {@code spec.targetRef} points at.
     */
    public View read(KubernetesClient client) {
        List<GenericKubernetesResource> objects;
        try {
            objects = client.genericKubernetesResources(VPA_CONTEXT).inAnyNamespace().list().getItems();
        } catch (KubernetesClientException e) {
            logger.info("Upstream VPA recommendations are not readable (HTTP {}): {}",
                    e.getCode(), e.getMessage());
            return View.notReadable();
        }

        Map<String, Map<String, ContainerTarget>> byWorkload = new LinkedHashMap<>();
        for (GenericKubernetesResource vpa : objects) {
            String namespace = vpa.getMetadata() == null ? null : vpa.getMetadata().getNamespace();
            Map<String, Object> spec = nested(vpa.getAdditionalProperties(), "spec");
            Map<String, Object> targetRef = nested(spec, "targetRef");
            String kind = string(targetRef, "kind");
            String name = string(targetRef, "name");
            if (namespace == null || kind == null || name == null) {
                continue;
            }
            Map<String, ContainerTarget> targets = targets(vpa);
            if (!targets.isEmpty()) {
                byWorkload.put(key(namespace, kind, name), targets);
            }
        }
        return new View(byWorkload, true);
    }

    /** {@code status.recommendation.containerRecommendations[].target}, per container. */
    private Map<String, ContainerTarget> targets(GenericKubernetesResource vpa) {
        Map<String, Object> status = nested(vpa.getAdditionalProperties(), "status");
        Map<String, Object> recommendation = nested(status, "recommendation");
        Object containerRecommendations = recommendation.get("containerRecommendations");
        if (!(containerRecommendations instanceof List<?> entries)) {
            return Map.of();
        }
        Instant observedAt = recommendationProvidedAt(status);
        Map<String, ContainerTarget> byContainer = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> recommended = (Map<String, Object>) raw;
            String container = string(recommended, "containerName");
            Map<String, Object> target = nested(recommended, "target");
            if (container == null || target.isEmpty()) {
                continue;
            }
            try {
                Integer cpuMillis = string(target, "cpu") == null
                        ? null : Quantities.cpuMillis(string(target, "cpu"), "VPA target cpu");
                Long memBytes = string(target, "memory") == null
                        ? null : Quantities.memBytes(string(target, "memory"), "VPA target memory");
                if (cpuMillis != null || memBytes != null) {
                    byContainer.put(container, new ContainerTarget(cpuMillis, memBytes, observedAt));
                }
            } catch (QuantityParseException unreadable) {
                // Dropped rather than rounded or zeroed: an unreadable comparison is no comparison.
                logger.debug("Unreadable VPA target for container {}: {}", container,
                        unreadable.getMessage());
            }
        }
        return byContainer;
    }

    /**
     * When VPA last provided this recommendation, from its {@code RecommendationProvided} condition.
     *
     * <p>Null when the condition is absent: a comparison shown without saying how old it is invites
     * the reader to assume it is current, and VPA objects left in {@code updateMode: "Off"} can go
     * stale.
     */
    private static Instant recommendationProvidedAt(Map<String, Object> status) {
        Object conditions = status.get("conditions");
        if (!(conditions instanceof List<?> entries)) {
            return null;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> condition = (Map<String, Object>) raw;
            if (!"RecommendationProvided".equals(string(condition, "type"))) {
                continue;
            }
            String at = string(condition, "lastTransitionTime");
            if (at == null) {
                return null;
            }
            try {
                return Instant.parse(at);
            } catch (RuntimeException notAnInstant) {
                return null;
            }
        }
        return null;
    }

    public static String key(String namespace, String kind, String name) {
        return namespace + "/" + kind + "/" + name;
    }

    public static String key(WorkloadRef ref) {
        return key(ref.namespace(), ref.kind(), ref.name());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> parent, String field) {
        if (parent == null) {
            return Map.of();
        }
        Object value = parent.get(field);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String string(Map<String, Object> parent, String field) {
        Object value = parent == null ? null : parent.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * @param byWorkload {@code namespace/kind/name -> container -> target}
     * @param readable   false when the optional ClusterRole rule was not granted, or the CRD is
     *                   absent; every {@code upstreamVpa} is then null and the finding is published
     */
    public record View(Map<String, Map<String, ContainerTarget>> byWorkload, boolean readable) {

        public View {
            byWorkload = byWorkload == null ? Map.of() : Map.copyOf(byWorkload);
        }

        public static View notReadable() {
            return new View(Map.of(), false);
        }

        /** The honest default when no VPA read was attempted: readable, with nothing to show. */
        public static View none() {
            return new View(Map.of(), true);
        }

        public Map<String, ContainerTarget> forWorkload(WorkloadRef ref) {
            return byWorkload.getOrDefault(key(ref), Map.of());
        }
    }

    /**
     * One container's VPA target, in the engine's canonical units.
     *
     * @param cpuMillis  millicores, or null when VPA did not target CPU
     * @param memBytes   bytes, or null when VPA did not target memory
     * @param observedAt when VPA last provided it, or null when it does not say
     */
    public record ContainerTarget(Integer cpuMillis, Long memBytes, Instant observedAt) {
    }
}
