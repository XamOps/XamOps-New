package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Where the usage source lives, and the two defaults that apply only when nobody chose a policy.
 *
 * <p>Same style as {@link com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties}:
 * {@code @Value} with a {@code :} placeholder default on every field, so an absent property, an
 * empty property and a missing profile all read as the safe value. The keys are declared explicitly
 * in all five {@code application*.properties} files as well, and
 * {@code VerticalMetricsPropertiesTest} reads those files from disk — a binding test cannot see a
 * profile that quietly moved one of them.
 *
 * <h2>The metrics block</h2>
 *
 * Prometheus is reached through the Kubernetes API <strong>service proxy</strong>, so the namespace,
 * service and port here are the three components of that proxied path and nothing more: no host, no
 * scheme, no credential. The defaults are what {@code eks/test-clusters/harness/prometheus-values.yaml}
 * installs, which is also the URL the Go agent and autoscaler hard-code.
 *
 * <h2>The two defaults</h2>
 *
 * {@link #defaultWindowHours()} and {@link #defaultMode()} are applied by the read path
 * <strong>only when the resolved policy's source is {@code DEFAULTS}</strong>. A stored
 * {@code vertical_workload_policy} row always wins, so {@code provenance.windowHours} and
 * {@code policy.mode} stay truthful about what was actually used. Changing
 * {@link WorkloadPolicyDefaults#WINDOW_HOURS} or the SQL column default instead would move a
 * published contract value.
 */
@Component
public class VerticalMetricsProperties {

    private static final Logger logger = LoggerFactory.getLogger(VerticalMetricsProperties.class);

    public static final String METRICS_NAMESPACE_KEY = "xamops.automation.vertical.metrics.namespace";
    public static final String METRICS_SERVICE_KEY = "xamops.automation.vertical.metrics.service";
    public static final String METRICS_PORT_KEY = "xamops.automation.vertical.metrics.port";
    public static final String QUERY_TIMEOUT_KEY = "xamops.automation.vertical.metrics.query-timeout-seconds";
    public static final String IGNORE_CONTAINER_START_KEY =
            "xamops.automation.vertical.metrics.ignore-container-start-seconds";
    public static final String DEFAULT_WINDOW_HOURS_KEY = "xamops.automation.vertical.default-window-hours";
    public static final String DEFAULT_MODE_KEY = "xamops.automation.vertical.default-mode";

    /** Every key this class binds, in declaration order — what the profile-parity test iterates. */
    public static final List<String> KEYS = List.of(
            METRICS_NAMESPACE_KEY, METRICS_SERVICE_KEY, METRICS_PORT_KEY, QUERY_TIMEOUT_KEY,
            IGNORE_CONTAINER_START_KEY, DEFAULT_WINDOW_HOURS_KEY, DEFAULT_MODE_KEY);

    private final String metricsNamespace;
    private final String metricsService;
    private final int metricsPort;
    private final int queryTimeoutSeconds;
    private final int ignoreContainerStartSeconds;
    private final int defaultWindowHours;
    private final WorkloadMode defaultMode;

    public VerticalMetricsProperties(
            @Value("${" + METRICS_NAMESPACE_KEY + ":monitoring}") String metricsNamespace,
            @Value("${" + METRICS_SERVICE_KEY + ":prometheus-server}") String metricsService,
            @Value("${" + METRICS_PORT_KEY + ":9090}") int metricsPort,
            @Value("${" + QUERY_TIMEOUT_KEY + ":20}") int queryTimeoutSeconds,
            @Value("${" + IGNORE_CONTAINER_START_KEY + ":120}") int ignoreContainerStartSeconds,
            @Value("${" + DEFAULT_WINDOW_HOURS_KEY + ":24}") int defaultWindowHours,
            @Value("${" + DEFAULT_MODE_KEY + ":OFF}") String defaultMode) {
        this.metricsNamespace = blankTo(metricsNamespace, "monitoring");
        this.metricsService = blankTo(metricsService, "prometheus-server");
        this.metricsPort = positiveOr(metricsPort, 9090, METRICS_PORT_KEY);
        this.queryTimeoutSeconds = positiveOr(queryTimeoutSeconds, 20, QUERY_TIMEOUT_KEY);
        // Zero is meaningful here: it means "do not exclude young containers at all".
        this.ignoreContainerStartSeconds = ignoreContainerStartSeconds < 0 ? 120 : ignoreContainerStartSeconds;
        this.defaultWindowHours = positiveOr(defaultWindowHours, WorkloadPolicyDefaults.WINDOW_HOURS,
                DEFAULT_WINDOW_HOURS_KEY);
        this.defaultMode = mode(defaultMode);
    }

    public String metricsNamespace() {
        return metricsNamespace;
    }

    public String metricsService() {
        return metricsService;
    }

    public int metricsPort() {
        return metricsPort;
    }

    /** Passed to Prometheus as the query's own {@code timeout}, so a slow query fails rather than hangs. */
    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    /**
     * Containers younger than this are excluded, by offsetting every range selector back by it: a
     * container that started seconds ago has no samples in the evaluated window and is reported as
     * {@code INSUFFICIENT_HISTORY} rather than sized from its first few scrapes.
     */
    public int ignoreContainerStartSeconds() {
        return ignoreContainerStartSeconds;
    }

    /** The policy window used when no row exists. Never overrides a stored row. */
    public int defaultWindowHours() {
        return defaultWindowHours;
    }

    /** The mode used when no row exists. Never overrides a stored row. */
    public WorkloadMode defaultMode() {
        return defaultMode;
    }

    /**
     * An unrecognised mode falls back to {@code OFF} with a warning rather than throwing, for the
     * same reason {@code WorkloadPolicyService} does: a typo in a properties file must not take the
     * read endpoint down, and the safe value is the one that does nothing.
     */
    private static WorkloadMode mode(String configured) {
        if (configured == null || configured.isBlank()) {
            return WorkloadMode.OFF;
        }
        try {
            return WorkloadMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            logger.warn("Unknown {} value '{}'; treating it as OFF", DEFAULT_MODE_KEY, configured);
            return WorkloadMode.OFF;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int positiveOr(int value, int fallback, String key) {
        if (value <= 0) {
            logger.warn("Non-positive {} value {}; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }
}
