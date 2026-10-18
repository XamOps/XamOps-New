package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.WorkloadPods;
import com.xammer.cloud.service.automation.workload.usage.PrometheusProxyTransport;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared fakes for the FEAT-004 history services: a range-query transport that answers from a
 * script, Prometheus matrix bodies shaped like the real ones, inventory workloads and providers.
 */
final class UsageFixtures {

    static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");

    /** The pinned clock: every fixed window ends at 17:06:00Z. */
    static final Instant NOW = Instant.parse("2026-10-16T17:06:30Z");
    static final long END = Instant.parse("2026-10-16T17:06:00Z").getEpochSecond();

    static final VerticalMetricsProperties METRICS =
            new VerticalMetricsProperties("monitoring", "prometheus-server", 80, 20, 120, 72, "RECOMMEND");

    static final String EMPTY_MATRIX =
            "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";

    private UsageFixtures() {
    }

    /** A matrix body from series built by {@link #series}. */
    static String matrix(String... series) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + String.join(",", series) + "]}}";
    }

    /** One matrix series: {@code values[i]} at {@code start + i * step}; a null value is a gap. */
    static String series(Map<String, String> labels, long start, int step, String... values) {
        StringBuilder body = new StringBuilder("{\"metric\":{");
        body.append(labels.entrySet().stream()
                .map(label -> "\"" + label.getKey() + "\":\"" + label.getValue() + "\"")
                .collect(Collectors.joining(",")));
        body.append("},\"values\":[");
        boolean first = true;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append('[').append(start + (long) i * step).append(",\"").append(values[i]).append("\"]");
        }
        return body.append("]}").toString();
    }

    static Map<String, String> pod(String namespace, String pod, String container) {
        return Map.of("namespace", namespace, "pod", pod, "container", container);
    }

    /** A Deployment with these live pods, pod-name rules and template containers. */
    static ClusterWorkloadInventory.Workload deployment(String namespace, String name, List<String> pods,
                                                        List<PodNameRule> rules, String... containers) {
        return deployment(namespace, name, pods, rules, null, containers);
    }

    static ClusterWorkloadInventory.Workload deployment(String namespace, String name, List<String> pods,
                                                        List<PodNameRule> rules, HpaView hpa,
                                                        String... containers) {
        WorkloadRef ref = new WorkloadRef(namespace, "Deployment", name, "uid-" + name);
        return new ClusterWorkloadInventory.Workload(ref, Math.max(1, pods.size()), false, false, hpa, null,
                Arrays.stream(containers)
                        .map(container -> new ClusterWorkloadInventory.ContainerSpec(container,
                                ResourceEnvelope.UNSET, Map.of()))
                        .toList(),
                new WorkloadPods(ref, pods.stream().map(pod -> new WorkloadPods.Pod(pod, "uid-" + pod)).toList(),
                        rules));
    }

    /** team-b/php-apache as on xamops-test-1: two live pods, its current and one retained ReplicaSet. */
    static ClusterWorkloadInventory.Workload phpApache() {
        return deployment("team-b", "php-apache",
                List.of("php-apache-7cd5b774b6-4s8nf", "php-apache-7cd5b774b6-5txj9"),
                List.of(PodNameRule.replicaSetPods("php-apache-7cd5b774b6"),
                        PodNameRule.replicaSetPods("php-apache-5f9c8d7b6c")),
                "php-apache");
    }

    static ClusterWorkloadInventory inventory(ClusterWorkloadInventory.Workload... workloads) {
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);
        when(inventory.read(any())).thenReturn(new ClusterWorkloadInventory.Inventory(List.of(workloads), null));
        return inventory;
    }

    static ClusterAccess access() {
        return new ClusterAccess(mock(KubernetesClient.class), "https://127.0.0.1", "xamops-test-1",
                "ap-south-1", "fake");
    }

    /** One query the fake was asked, with its span. */
    record Asked(String query, Instant start, Instant end, int step) {
    }

    /**
     * Answers range queries from a script — the first fragment the query contains, optionally only
     * at one step — and records every query. Subclasses the real transport, so the production path
     * building and failure copy stay covered by {@code PrometheusProxyTransportTest}.
     */
    static final class FakeTransport extends PrometheusProxyTransport {

        final List<Asked> asked = new ArrayList<>();
        private final List<Rule> script = new ArrayList<>();
        UsageUnavailableException failure;

        FakeTransport() {
            super(METRICS);
        }

        FakeTransport on(String fragment, String body) {
            script.add(new Rule(fragment, null, body));
            return this;
        }

        FakeTransport on(String fragment, int step, String body) {
            script.add(new Rule(fragment, step, body));
            return this;
        }

        @Override
        public String rangeQuery(KubernetesClient client, String promql, Instant start, Instant end,
                                 int stepSeconds) throws UsageUnavailableException {
            asked.add(new Asked(promql, start, end, stepSeconds));
            if (failure != null) {
                throw failure;
            }
            for (Rule rule : script) {
                if (promql.contains(rule.fragment()) && (rule.step() == null || rule.step() == stepSeconds)) {
                    return rule.body();
                }
            }
            return EMPTY_MATRIX;
        }

        @Override
        public String instantQuery(KubernetesClient client, String promql) {
            throw new AssertionError("the history endpoints send range queries only: " + promql);
        }

        List<String> queries() {
            return asked.stream().map(Asked::query).toList();
        }

        private record Rule(String fragment, Integer step, String body) {
        }
    }

    /** Opens successfully and counts how often it was asked to. */
    static final class OpeningProvider implements ClusterAccessProvider {

        int opened;

        @Override
        public String providerName() {
            return "fake-open";
        }

        @Override
        public boolean supports(ClusterTarget target) {
            return true;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) {
            opened++;
            return access();
        }
    }

    /** Always fails with one kind, and counts the attempts. */
    static final class FailingProvider implements ClusterAccessProvider {

        private final ClusterAccessException.Kind kind;
        int opened;

        FailingProvider(ClusterAccessException.Kind kind) {
            this.kind = kind;
        }

        @Override
        public String providerName() {
            return "fake-failing";
        }

        @Override
        public boolean supports(ClusterTarget target) {
            return true;
        }

        @Override
        public ClusterAccess open(ClusterTarget target) throws ClusterAccessException {
            opened++;
            throw new ClusterAccessException(kind, "fake " + kind);
        }
    }
}
