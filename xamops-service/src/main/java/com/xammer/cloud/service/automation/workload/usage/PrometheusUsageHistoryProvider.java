package com.xammer.cloud.service.automation.workload.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The real usage source: the customer cluster's own Prometheus, read through the Kubernetes API
 * service proxy.
 *
 * <h2>What it measures, per container</h2>
 *
 * Seven queries per workload, each aggregated {@code by (container)} and each carrying the
 * namespace, the owner-resolved pod list and the container names (see {@link PromQl}): the CPU
 * percentile the policy asked for, the memory working-set maximum, the CFS throttle ratio, OOM kills,
 * the real sample count, and the real first and last sample timestamps.
 *
 * <h2>Nothing is inferred from the clock or from configuration</h2>
 *
 * {@code windowStart} and {@code windowEnd} are the <strong>measured</strong> first and last sample
 * timestamps, not {@code now} minus the policy window. {@code sampleCoverage} is the real sample
 * count against the number of samples the observed cadence implies for the policy window, and the
 * cadence itself is derived from the response — not from a configured scrape interval, which would be
 * a guess about somebody else's Prometheus. The one limitation worth stating: a gap <em>inside</em>
 * the observed span inflates the derived cadence as well as thinning the count, so coverage is an
 * upper bound on how much of the window was really observed. It is never an under-estimate that
 * could suppress a decision, and never an estimate that invents data.
 *
 * <h2>Two failures, both typed, never a zero</h2>
 *
 * <ul>
 *   <li>{@code NO_METRICS_SOURCE} — the proxy is unreachable or forbidden, or Prometheus answered
 *       {@code {"status":"error"}}. Nothing can be measured.</li>
 *   <li>{@code INSUFFICIENT_HISTORY} — Prometheus answered, but a container has no samples, fewer
 *       than two samples (one sample is not a window), a zero-length window, a window shorter than
 *       {@link WorkloadPolicyDefaults#MIN_WINDOW} or coverage below
 *       {@link WorkloadPolicyDefaults#MIN_SAMPLE_COVERAGE}.</li>
 * </ul>
 *
 * An empty Prometheus {@code result} array is the second case, never {@code 0}: "we measured zero
 * CPU" and "we measured nothing" are different statements, and only one of them is a basis for
 * resizing a production container.
 */
@Service
@Primary
public class PrometheusUsageHistoryProvider implements UsageHistoryProvider {

    private static final Logger logger = LoggerFactory.getLogger(PrometheusUsageHistoryProvider.class);

    /** Reported as the recommendation's basis, so a number always says where it came from. */
    public static final String SOURCE_NAME = "prometheus-proxy";

    private final PrometheusProxyTransport transport;
    private final VerticalMetricsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PrometheusUsageHistoryProvider(PrometheusProxyTransport transport,
                                          VerticalMetricsProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public boolean reachable(ClusterAccess access) {
        return access != null && transport.reachable(access.client());
    }

    @Override
    public Map<String, ContainerUsageAggregate> aggregates(ClusterAccess access, UsageQuery query)
            throws UsageUnavailableException {
        if (access == null) {
            throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                    "No cluster connection is open, so Prometheus cannot be queried for usage.");
        }
        KubernetesClient client = access.client();
        PromQl.Selector selector = new PromQl.Selector(query.ref().namespace(), query.podNames(),
                query.podNameRules(), query.containerNames());
        int window = query.windowHours();
        int offset = properties.ignoreContainerStartSeconds();

        Map<String, Double> cpu = vectorByContainer(client,
                PromQl.cpuPercentileMillis(selector, query.percentile(), window, offset));
        Map<String, Double> memory = vectorByContainer(client,
                PromQl.memoryMaxBytes(selector, window, offset));
        Map<String, Double> throttle = vectorByContainer(client,
                PromQl.cpuThrottleRatio(selector, window, offset));
        Map<String, Double> oom = vectorByContainer(client,
                PromQl.oomKills(selector, window, offset));
        Map<String, Double> samples = vectorByContainer(client,
                PromQl.sampleCount(selector, window, offset));
        Map<String, Double> firstSeen = vectorByContainer(client,
                PromQl.firstSampleTimestamp(selector, window, offset));
        Map<String, Double> lastSeen = vectorByContainer(client,
                PromQl.lastSampleTimestamp(selector, window, offset));
        // The confidence volatility half: the mean and stddev of the SAME rate(...) subquery the
        // percentile is taken over. A missing or NaN value stays absent, never coerced to zero.
        Map<String, Double> cpuMean = vectorByContainer(client,
                PromQl.cpuMeanMillis(selector, window, offset));
        Map<String, Double> cpuStddev = vectorByContainer(client,
                PromQl.cpuStddevMillis(selector, window, offset));

        Map<String, ContainerUsageAggregate> byContainer = new LinkedHashMap<>();
        for (String container : query.containerNames()) {
            byContainer.put(container, aggregate(container, query, cpu, memory, throttle, oom, samples,
                    firstSeen, lastSeen, cpuMean, cpuStddev));
        }
        return byContainer;
    }

    /** One container's aggregate, or a typed refusal. Never a partially-measured aggregate. */
    private ContainerUsageAggregate aggregate(String container, UsageQuery query,
                                              Map<String, Double> cpu, Map<String, Double> memory,
                                              Map<String, Double> throttle, Map<String, Double> oom,
                                              Map<String, Double> samples, Map<String, Double> firstSeen,
                                              Map<String, Double> lastSeen, Map<String, Double> cpuMean,
                                              Map<String, Double> cpuStddev)
            throws UsageUnavailableException {
        Double cpuMillis = cpu.get(container);
        Double memBytes = memory.get(container);
        Double sampleCount = samples.get(container);
        Double first = firstSeen.get(container);
        Double last = lastSeen.get(container);

        if (cpuMillis == null || memBytes == null || sampleCount == null || first == null || last == null) {
            throw insufficient(container, query, "Prometheus returned no samples for it in the last "
                    + query.windowHours() + "h");
        }
        if (sampleCount < 2) {
            // A single sample has no cadence and no window: there is nothing to take a percentile of.
            throw insufficient(container, query, "only " + sampleCount.longValue()
                    + " sample(s) were observed in the last " + query.windowHours() + "h");
        }

        Instant windowStart = instant(first);
        Instant windowEnd = instant(last);
        if (!windowEnd.isAfter(windowStart)) {
            throw insufficient(container, query,
                    "its observed window has no length (first and last sample share a timestamp)");
        }
        Duration observed = Duration.between(windowStart, windowEnd);
        if (observed.compareTo(WorkloadPolicyDefaults.MIN_WINDOW) < 0) {
            throw insufficient(container, query, "only " + humanize(observed)
                    + " of history has been observed, below the " + humanize(WorkloadPolicyDefaults.MIN_WINDOW)
                    + " minimum a percentile needs");
        }

        double coverage = coverage(sampleCount, observed, query.windowHours());
        if (coverage < WorkloadPolicyDefaults.MIN_SAMPLE_COVERAGE) {
            throw insufficient(container, query, String.format(
                    "only %.0f%% of the %dh window was observed, below the %.0f%% the engine requires",
                    coverage * 100, query.windowHours(),
                    WorkloadPolicyDefaults.MIN_SAMPLE_COVERAGE * 100));
        }

        return new ContainerUsageAggregate(container,
                // CPU: one measured percentile, exactly the one the policy asked for. No max is
                // published because none was queried -- null means "the source did not provide it".
                new PercentileSet(Map.of(query.percentile(), nonNegative(cpuMillis)), null),
                // Memory: the brief specifies p100/max, and PercentileSet.at() must answer EXACTLY
                // the policy's percentile or throw. So the measured window maximum is filed under the
                // requested percentile and also carried as max -- the value is the same measurement
                // either way, and this is the only shape in which the engine can read it without a
                // neighbour substitution the whole type exists to prevent.
                new PercentileSet(Map.of(query.percentile(), nonNegative(memBytes)), nonNegative(memBytes)),
                ratio(throttle.get(container)),
                kills(oom.get(container)),
                windowStart, windowEnd, coverage, SOURCE_NAME,
                // Mean/stddev are the confidence volatility inputs. Absent (NaN/no series) stays
                // null -- vectorByContainer already dropped non-finite values -- so the score
                // degrades to neutral volatility rather than inventing a zero.
                millis(cpuMean.get(container)),
                millis(cpuStddev.get(container)));
    }

    /**
     * Observed samples against the samples the <em>observed</em> cadence implies for the policy
     * window.
     *
     * <p>Equivalent to {@code observedSpan / policyWindow} once the cadence cancels, which is exactly
     * the quantity the measured harness run made visible: 4h52m of history against a 24h window is
     * coverage 0.2, and the engine refuses it rather than recommending from a fifth of the data.
     */
    private static double coverage(double sampleCount, Duration observed, int windowHours) {
        double cadenceSeconds = observed.toSeconds() / (sampleCount - 1);
        double expected = (windowHours * 3600.0) / cadenceSeconds;
        if (expected <= 0) {
            return 0;
        }
        return Math.min(1.0, sampleCount / expected);
    }

    /** Parses one instant-query response into {@code container -> value}, dropping non-finite values. */
    private Map<String, Double> vectorByContainer(KubernetesClient client, String promql)
            throws UsageUnavailableException {
        String body = transport.instantQuery(client, promql);
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception notJson) {
            // Never swallowed into a zero: an unreadable response means nothing was measured.
            throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                    "Prometheus at " + transport.describeTarget() + " returned a body that is not JSON,"
                            + " so no usage could be measured.", notJson);
        }
        String status = root.path("status").asText("");
        if (!"success".equals(status)) {
            throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                    "Prometheus at " + transport.describeTarget() + " refused the query: "
                            + root.path("error").asText("status=" + status));
        }
        Map<String, Double> byContainer = new LinkedHashMap<>();
        for (JsonNode series : root.path("data").path("result")) {
            String container = series.path("metric").path("container").asText("");
            JsonNode value = series.path("value");
            if (container.isEmpty() || !value.isArray() || value.size() < 2) {
                continue;
            }
            double parsed;
            try {
                parsed = Double.parseDouble(value.get(1).asText());
            } catch (NumberFormatException notANumber) {
                // Prometheus writes "NaN" for 0/0 ratios. Treated as absent, not as zero.
                logger.debug("Non-numeric Prometheus value for container {}: {}", container,
                        value.get(1).asText());
                continue;
            }
            if (!Double.isFinite(parsed)) {
                continue;
            }
            byContainer.put(container, parsed);
        }
        return byContainer;
    }

    /**
     * An absent or unmeasurable throttle ratio reads as {@code 0}.
     *
     * <p>{@link ContainerUsageAggregate} types this as a {@code double} in {@code [0,1]} with no
     * "unknown", so there is a value to choose and {@code 0} is the conservative one: it withholds the
     * {@code THROTTLED} finding and withholds {@code DEMAND_CENSORED_BY_LIMIT}, so a missing CFS
     * signal can only make the engine recommend <em>less</em> of an increase, never more.
     */
    private static double ratio(Double value) {
        if (value == null) {
            return 0;
        }
        return Math.min(1.0, Math.max(0.0, value));
    }

    /** OOM kills: the series exists only for a container that terminated, so absent means none. */
    private static int kills(Double value) {
        if (value == null || value <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }

    private static long nonNegative(double value) {
        return Math.max(0L, Math.round(value));
    }

    /**
     * A nullable millicore scalar: absent stays {@code null} (no volatility data), present is rounded
     * and floored at zero. Never a fabricated zero for an absent series — that is why it is nullable.
     */
    private static Long millis(Double value) {
        if (value == null) {
            return null;
        }
        return Math.max(0L, Math.round(value));
    }

    private static Instant instant(double unixSeconds) {
        return Instant.ofEpochMilli(Math.round(unixSeconds * 1000));
    }

    private static String humanize(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h" + minutes + "m" : minutes + "m";
    }

    private UsageUnavailableException insufficient(String container, UsageQuery query, String why) {
        return new UsageUnavailableException(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY,
                "Container '" + container + "' of " + query.ref().describe()
                        + " cannot be sized yet: " + why + ". This is a waiting state, not an error —"
                        + " XamOps will not recommend from history it has not observed.");
    }
}
