package com.xammer.cloud.service.automation.workload.execute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only writing I/O in this feature: one {@code PATCH} to a HorizontalPodAutoscaler, limited to
 * its bounds and the one CPU target. The horizontal counterpart to {@link PodResizeClient}.
 *
 * <pre>
 * PATCH /apis/autoscaling/v2/namespaces/{ns}/horizontalpodautoscalers/{name}?fieldManager=xamops-hpa
 * Content-Type: application/strategic-merge-patch+json
 * </pre>
 *
 * <h2>Why the request is hand-built</h2>
 *
 * Same platform constraint {@link PodResizeClient} documents, verified against fabric8 7.8.0:
 * {@code Client.raw(url, method, payload)} hard-codes {@code Content-Type: application/json}, which
 * the API server rejects for a strategic-merge {@code PATCH}.
 * {@code client.getHttpClient().newHttpRequestBuilder().method(String, String, String)} is the
 * supported seam that lets the content type be set, and it carries the client's own authentication
 * and TLS — so this is not a second credential path.
 *
 * <h2>What the body contains, and what it cannot</h2>
 *
 * <strong>Only</strong> {@code spec.minReplicas}, {@code spec.maxReplicas} and the matching
 * {@code Resource: cpu} metric's {@code target.averageUtilization}. A bound left null is absent from
 * the body entirely, so the cluster leaves it alone. The CPU target, when it is moved, is sent as
 * the <em>full</em> {@code spec.metrics} array rebuilt from the HPA's current spec with only that one
 * number changed — because {@code metrics} is a list and a strategic-merge of a partial list is not
 * deterministic. Nothing else about the HPA appears: this class cannot express a change to the
 * scale target, a behaviour policy, a non-CPU metric, or any annotation.
 *
 * <p>Reading the HPA first ({@link #currentHpa}) is what makes the target rebuild possible and what
 * supplies the {@code resourceVersion} the caller uses as the optimistic precondition.
 */
@Component
public class HpaPatchClient {

    private static final Logger logger = LoggerFactory.getLogger(HpaPatchClient.class);

    /** Server-side-apply ownership marker, so a field this feature wrote is attributable. */
    public static final String FIELD_MANAGER = "xamops-hpa";

    /** The one content type this patch shape is sent with. */
    public static final String CONTENT_TYPE = "application/strategic-merge-patch+json";

    private static final String CPU = "cpu";
    private static final String RESOURCE = "Resource";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A single patch is a user-facing action, so it fails fast rather than hanging a request. */
    private final long requestTimeoutSeconds;

    public HpaPatchClient() {
        this(20L);
    }

    HpaPatchClient(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    /**
     * Reads the HPA as it exists now — the source of the {@code resourceVersion} and the current CPU
     * target the patch body is rebuilt from.
     *
     * @return the HPA, or null when the cluster no longer has it
     */
    public HorizontalPodAutoscaler currentHpa(KubernetesClient client, String namespace, String name) {
        return client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace(namespace).withName(name).get();
    }

    /**
     * Sends the bounds/target patch and returns the HPA the API server answered with.
     *
     * @param current the HPA as just read, so the full {@code metrics} array can be rebuilt when the
     *                CPU target moves; mandatory when {@code targetUtilizationPct} is non-null
     * @throws HpaPatchRejected on any non-2xx answer, or when the request could not be completed; the
     *                          HTTP status is carried so a caller can tell "the API server says no"
     *                          from "the API server was unreachable"
     */
    public HorizontalPodAutoscaler patch(KubernetesClient client, String namespace, String name,
                                         HorizontalPodAutoscaler current,
                                         Integer minReplicas, Integer maxReplicas,
                                         Integer targetUtilizationPct) throws HpaPatchRejected {
        String body = patchBody(current, minReplicas, maxReplicas, targetUtilizationPct);
        String uri = patchUri(client, namespace, name);
        logger.info("Patching HPA {}/{} bounds/target through autoscaling/v2", namespace, name);

        HttpRequest request = client.getHttpClient().newHttpRequestBuilder()
                .uri(uri)
                .method("PATCH", CONTENT_TYPE, body)
                .build();

        HttpResponse<String> response;
        try {
            response = client.getHttpClient().sendAsync(request, String.class)
                    .get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new HpaPatchRejected(0, "The patch of HPA " + namespace + "/" + name
                    + " was interrupted, so whether it was applied is not known.");
        } catch (ExecutionException | TimeoutException failed) {
            throw new HpaPatchRejected(0, "The patch of HPA " + namespace + "/" + name
                    + " could not be sent: " + failed.getMessage());
        }

        String answer = response.body();
        if (!response.isSuccessful()) {
            throw new HpaPatchRejected(response.code(), "The API server refused the patch of HPA "
                    + namespace + "/" + name + " (HTTP " + response.code() + "): " + answer);
        }
        if (answer == null || answer.isBlank()) {
            throw new HpaPatchRejected(response.code(), "The API server accepted the patch of HPA "
                    + namespace + "/" + name + " but returned no object, so the result cannot be"
                    + " confirmed.");
        }
        return Serialization.unmarshal(answer, HorizontalPodAutoscaler.class);
    }

    /**
     * {@code {"spec":{"minReplicas":…,"maxReplicas":…,"metrics":[…]}}} — only the changed fields.
     *
     * <p>A null bound is absent from the body. The CPU target, when moved, is sent as the whole
     * rebuilt {@code metrics} list; when it is not moved, {@code metrics} is absent entirely.
     *
     * <p>Package-private so the exact body is assertable without a server.
     */
    String patchBody(HorizontalPodAutoscaler current, Integer minReplicas, Integer maxReplicas,
                     Integer targetUtilizationPct) {
        if (minReplicas == null && maxReplicas == null && targetUtilizationPct == null) {
            throw new IllegalArgumentException("an HPA patch with no bound and no target would be a no-op");
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        if (minReplicas != null) {
            spec.put("minReplicas", minReplicas);
        }
        if (maxReplicas != null) {
            spec.put("maxReplicas", maxReplicas);
        }
        if (targetUtilizationPct != null) {
            spec.put("metrics", rebuiltMetrics(current, targetUtilizationPct));
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spec", spec);
        try {
            return JSON.writeValueAsString(patch);
        } catch (JsonProcessingException impossible) {
            // Only Strings, Numbers, Maps and Lists are in the tree; there is no type Jackson refuses.
            throw new IllegalStateException("the HPA patch body could not be serialised", impossible);
        }
    }

    /**
     * The HPA's current {@code spec.metrics}, rebuilt with the one {@code Resource: cpu} metric's
     * {@code target.averageUtilization} set to {@code targetUtilizationPct} and every other metric
     * carried through unchanged.
     *
     * <p>The whole list is sent because strategic-merge cannot patch one element of a non-keyed list
     * in place. Carrying the other metrics through verbatim is what keeps the patch limited to the
     * CPU target rather than silently dropping a memory or custom metric.
     */
    private static List<Map<String, Object>> rebuiltMetrics(HorizontalPodAutoscaler current,
                                                            int targetUtilizationPct) {
        if (current == null || current.getSpec() == null || current.getSpec().getMetrics() == null) {
            throw new IllegalArgumentException(
                    "the current HPA and its metrics are required to move the CPU target, so the "
                            + "non-CPU metrics can be preserved rather than dropped");
        }
        boolean found = false;
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (MetricSpec metric : current.getSpec().getMetrics()) {
            Map<String, Object> raw = JSON.convertValue(metric, LinkedHashMap.class);
            if (isCpuResourceMetric(metric)) {
                found = true;
                @SuppressWarnings("unchecked")
                Map<String, Object> resource = (Map<String, Object>) raw.get("resource");
                @SuppressWarnings("unchecked")
                Map<String, Object> target = resource == null
                        ? null : (Map<String, Object>) resource.get("target");
                if (target == null) {
                    throw new IllegalArgumentException(
                            "the CPU Resource metric has no target to co-adjust");
                }
                // Utilization-only: the engine only ever co-adjusts averageUtilization, and leaving
                // averageValue in place beside it would make the HPA ambiguous.
                target.put("type", "Utilization");
                target.put("averageUtilization", targetUtilizationPct);
                target.remove("averageValue");
                target.remove("value");
            }
            metrics.add(raw);
        }
        if (!found) {
            throw new IllegalArgumentException(
                    "the HPA has no Resource: cpu metric, so its CPU target cannot be moved");
        }
        return metrics;
    }

    private static boolean isCpuResourceMetric(MetricSpec metric) {
        return RESOURCE.equals(metric.getType()) && metric.getResource() != null
                && CPU.equals(metric.getResource().getName());
    }

    /** Package-private so the path and the field manager are assertable without a server. */
    String patchUri(KubernetesClient client, String namespace, String name) {
        String base = client.getMasterUrl().toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + patchPath(namespace, name);
    }

    static String patchPath(String namespace, String name) {
        return "/apis/autoscaling/v2/namespaces/" + namespace + "/horizontalpodautoscalers/" + name
                + "?fieldManager=" + FIELD_MANAGER;
    }

    /**
     * The patch was not accepted. Carries the HTTP status so a caller can report what happened rather
     * than "something went wrong".
     *
     * <p>{@code code} is {@code 0} when no answer was received at all — which is <em>not</em> a
     * refusal and must never be reported as one. {@code 409} is a lost optimistic race (the HPA
     * changed under the command's {@code resourceVersion}).
     */
    public static class HpaPatchRejected extends Exception {

        private final int code;

        public HpaPatchRejected(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }

        /** True when the API server answered at all. A {@code false} means the outcome is unknown. */
        public boolean answered() {
            return code > 0;
        }

        /** True on a {@code 409}: the HPA was edited between the read and this patch. */
        public boolean conflict() {
            return code == 409;
        }
    }
}
