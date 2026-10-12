package com.xammer.cloud.service.automation.workload.usage;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The only I/O in the Prometheus usage path: one PromQL instant query, sent through the Kubernetes
 * API <strong>service proxy</strong>.
 *
 * <p>Identical in shape to {@code OpenCostService.proxyRequest} — the pattern already in production.
 * No new network path is opened, nothing is exposed publicly, and the only credential involved is
 * the cluster connection the caller already holds: the API server does the proxying.
 *
 * <h2>Why this is a separate class</h2>
 *
 * The module has no {@code kubernetes-server-mock} and no WireMock dependency, so a provider that
 * built PromQL, performed the call and parsed the response in one class could only be tested against
 * a live cluster. With the I/O behind this seam, {@link PrometheusUsageHistoryProvider} is unit-
 * testable against recorded Prometheus JSON with zero network, and this class is testable against a
 * JDK {@code HttpServer} on loopback.
 *
 * <h2>Reachability is probed through the proxy, not by reading the Service object</h2>
 *
 * {@code OpenCostService} checks {@code client.services().withName(svc).get() != null}. That check is
 * <strong>wrong for this feature</strong>: the committed ClusterRole
 * ({@code eks/test-clusters/xamops-workload-autoscaler-rbac.yaml}) grants {@code get} on
 * {@code services/proxy} restricted by {@code resourceNames} and deliberately does <em>not</em>
 * grant {@code get} on {@code services} — verified live, where {@code kubectl auth can-i get
 * services -n monitoring} answers {@code no} for the role. Probing the Service object would report
 * "no metrics source" on a cluster whose Prometheus is perfectly reachable, so {@link #reachable}
 * sends a real {@code up} query down the exact path the real queries use instead. It proves the
 * whole chain — RBAC, proxy, Prometheus — rather than one object's existence.
 */
@Component
public class PrometheusProxyTransport {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusProxyTransport.class);

    /** The cheapest query that proves Prometheus is answering: every target reports {@code up}. */
    private static final String PROBE_QUERY = "up";

    private final VerticalMetricsProperties properties;

    public PrometheusProxyTransport(VerticalMetricsProperties properties) {
        this.properties = properties;
    }

    /**
     * Sends one PromQL instant query and returns the raw response body.
     *
     * @return the response body, never null or blank
     * @throws UsageUnavailableException {@code NO_METRICS_SOURCE} — the only failure this can raise.
     *                                   A transport that threw anything else would turn a missing
     *                                   Prometheus into a 500.
     */
    public String instantQuery(KubernetesClient client, String promql) throws UsageUnavailableException {
        String path = queryPath(promql);
        try {
            String body = client.raw(path);
            if (body == null || body.isBlank()) {
                throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                        "Prometheus at " + describeTarget() + " returned an empty body for an instant"
                                + " query, so no usage could be measured.");
            }
            return body;
        } catch (KubernetesClientException e) {
            throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                    failureMessage(e), e);
        }
    }

    /**
     * Whether Prometheus answers through the proxy right now.
     *
     * <p>Swallows the failure deliberately: this is the question "is there a metrics source", and the
     * answer is a boolean the read endpoint reports as {@code metricsSource.reachable}. The reason is
     * logged, and any real query that follows raises the typed
     * {@link UsageUnavailableException.Kind#NO_METRICS_SOURCE} with the detail.
     */
    public boolean reachable(KubernetesClient client) {
        try {
            String body = instantQuery(client, PROBE_QUERY);
            boolean success = body.contains("\"status\":\"success\"") || body.contains("\"status\": \"success\"");
            if (!success) {
                logger.info("Prometheus at {} answered the reachability probe without status=success",
                        describeTarget());
            }
            return success;
        } catch (UsageUnavailableException unreachable) {
            logger.info("Prometheus at {} is not reachable through the API service proxy: {}",
                    describeTarget(), unreachable.getMessage());
            return false;
        }
    }

    /**
     * {@code /api/v1/namespaces/{ns}/services/{svc}:{port}/proxy/api/v1/query?query=…&timeout=…s}
     *
     * <p>Package-private so the path itself is assertable without a server.
     */
    String queryPath(String promql) {
        return String.format("/api/v1/namespaces/%s/services/%s:%d/proxy/api/v1/query?query=%s&timeout=%ds",
                properties.metricsNamespace(), properties.metricsService(), properties.metricsPort(),
                URLEncoder.encode(promql, StandardCharsets.UTF_8), properties.queryTimeoutSeconds());
    }

    /** {@code monitoring/prometheus-server:9090} — for messages, never for lookups. */
    String describeTarget() {
        return properties.metricsNamespace() + "/" + properties.metricsService() + ":"
                + properties.metricsPort();
    }

    /**
     * A 403 names the exact ClusterRole rule that is missing, because that is the one failure the
     * customer can fix and the one they cannot diagnose from "Forbidden".
     */
    private String failureMessage(KubernetesClientException e) {
        if (e.getCode() == 403) {
            return "XamOps is not allowed to reach Prometheus through the Kubernetes API service"
                    + " proxy. Grant the ClusterRole rule: apiGroups [\"\"], resources"
                    + " [\"services/proxy\"], resourceNames [\"" + properties.metricsService() + ":"
                    + properties.metricsPort() + "\"], verbs [\"get\"] in namespace "
                    + properties.metricsNamespace() + ".";
        }
        return "Prometheus at " + describeTarget() + " could not be queried through the Kubernetes API"
                + " service proxy (HTTP " + e.getCode() + "): " + e.getMessage();
    }
}
