package com.xammer.cloud.service.automation.workload.execute;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.automation.workload.Quantities;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
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
 * The only writing I/O in this feature: one {@code PATCH} to a pod's <strong>resize
 * subresource</strong>.
 *
 * <pre>
 * PATCH /api/v1/namespaces/{ns}/pods/{name}/resize?fieldManager=xamops-vertical-rightsizer
 * Content-Type: application/strategic-merge-patch+json
 * </pre>
 *
 * <h2>Why the request is hand-built</h2>
 *
 * Verified against the jars this module compiles against (fabric8 7.8.0):
 *
 * <ul>
 *   <li>{@code PodResource} has <strong>no</strong> {@code resize()} subresource method, so there is
 *       no DSL to call.</li>
 *   <li>{@code Client.raw(url, method, payload)} hard-codes {@code Content-Type: application/json}
 *       ({@code OperationSupport.handleRaw}), and the API server rejects that content type for a
 *       {@code PATCH}.</li>
 * </ul>
 *
 * {@code client.getHttpClient().newHttpRequestBuilder().method(String, String, String)} is the
 * supported seam that lets the content type be set, and it carries the client's own authentication
 * and TLS configuration — so this is not a second credential path.
 *
 * <h2>What the body contains</h2>
 *
 * <strong>Only the container resources that are changing</strong>, with a resource's request and its
 * limit sent <em>together</em> in the same patch. Sending a request without its limit is what
 * silently moved a workload from Guaranteed to Burstable in the measured reference run, and the
 * resize subresource rejects a QoS-class change outright — so splitting them would be both
 * destructive and unsatisfiable. Nothing else about the pod appears in the body: this class cannot
 * express a change to an image, a command, a replica count or a Deployment.
 */
@Component
public class PodResizeClient {

    private static final Logger logger = LoggerFactory.getLogger(PodResizeClient.class);

    /** Server-side-apply ownership marker, so a field this feature wrote is attributable. */
    public static final String FIELD_MANAGER = "xamops-vertical-rightsizer";

    /** The one content type the resize subresource accepts for this patch shape. */
    public static final String CONTENT_TYPE = "application/strategic-merge-patch+json";

    private static final String CPU = "cpu";
    private static final String MEMORY = "memory";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A single patch is a user-facing action, so it fails fast rather than hanging a request. */
    private final long requestTimeoutSeconds;

    public PodResizeClient() {
        this(20L);
    }

    PodResizeClient(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    /**
     * Sends the resize patch and returns the pod the API server answered with.
     *
     * <p>The answer is the updated pod, which is also the first status sample a caller can judge
     * completion from — so no extra read is needed before polling begins.
     *
     * @throws ResizeRejected on any non-2xx answer, or when the request could not be completed; the
     *                        HTTP status is carried so a caller can tell "the kubelet says never"
     *                        from "the API server was unreachable"
     */
    public Pod patchResize(KubernetesClient client, String namespace, String podName,
                           List<ContainerApplyTarget> targets) throws ResizeRejected {
        String body = patchBody(targets);
        String uri = resizeUri(client, namespace, podName);
        logger.info("Resizing pod {}/{} through the pods/resize subresource", namespace, podName);

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
            throw new ResizeRejected(0, "The resize of pod " + namespace + "/" + podName
                    + " was interrupted, so whether it was applied is not known.");
        } catch (ExecutionException | TimeoutException failed) {
            throw new ResizeRejected(0, "The resize of pod " + namespace + "/" + podName
                    + " could not be sent: " + failed.getMessage());
        }

        String answer = response.body();
        if (!response.isSuccessful()) {
            throw new ResizeRejected(response.code(), "The API server refused the resize of pod "
                    + namespace + "/" + podName + " (HTTP " + response.code() + "): " + answer);
        }
        if (answer == null || answer.isBlank()) {
            throw new ResizeRejected(response.code(), "The API server accepted the resize of pod "
                    + namespace + "/" + podName + " but returned no pod, so the result cannot be"
                    + " confirmed.");
        }
        return Serialization.unmarshal(answer, Pod.class);
    }

    /**
     * {@code {"spec":{"containers":[{"name":…,"resources":{"requests":{…},"limits":{…}}}]}}} — the
     * changed fields and nothing else.
     *
     * <p>Package-private so the exact body is assertable without a server.
     */
    String patchBody(List<ContainerApplyTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("a resize patch with no container targets would be a no-op");
        }
        List<Map<String, Object>> containers = new ArrayList<>();
        for (ContainerApplyTarget target : targets) {
            Map<String, Object> requests = new LinkedHashMap<>();
            Map<String, Object> limits = new LinkedHashMap<>();
            if (target.cpuRequestMillis() != null) {
                requests.put(CPU, Quantities.formatCpu(target.cpuRequestMillis()));
            }
            if (target.memRequestBytes() != null) {
                requests.put(MEMORY, Quantities.formatMem(target.memRequestBytes()));
            }
            if (target.cpuLimitMillis() != null) {
                limits.put(CPU, Quantities.formatCpu(target.cpuLimitMillis()));
            }
            if (target.memLimitBytes() != null) {
                limits.put(MEMORY, Quantities.formatMem(target.memLimitBytes()));
            }
            Map<String, Object> resources = new LinkedHashMap<>();
            if (!requests.isEmpty()) {
                resources.put("requests", requests);
            }
            if (!limits.isEmpty()) {
                resources.put("limits", limits);
            }
            Map<String, Object> container = new LinkedHashMap<>();
            container.put("name", target.containerName());
            container.put("resources", resources);
            containers.add(container);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("containers", containers);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("spec", spec);
        try {
            return JSON.writeValueAsString(patch);
        } catch (JsonProcessingException impossible) {
            // Only Strings, Maps and Lists are in the tree; there is no type Jackson can refuse.
            throw new IllegalStateException("the resize patch body could not be serialised", impossible);
        }
    }

    /** Package-private so the path and the field manager are assertable without a server. */
    String resizeUri(KubernetesClient client, String namespace, String podName) {
        String base = client.getMasterUrl().toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + resizePath(namespace, podName);
    }

    static String resizePath(String namespace, String podName) {
        return "/api/v1/namespaces/" + namespace + "/pods/" + podName + "/resize"
                + "?fieldManager=" + FIELD_MANAGER;
    }

    /**
     * The resize was not accepted. Carries the HTTP status so the caller can report what happened
     * rather than "something went wrong".
     *
     * <p>{@code code} is {@code 0} when no answer was received at all — which is <em>not</em> the
     * same as a refusal, and must never be reported as one.
     */
    public static class ResizeRejected extends Exception {

        private final int code;

        public ResizeRejected(int code, String message) {
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
    }
}
