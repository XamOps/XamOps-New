package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.PodNameRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every FEAT-004 range-query builder, asserted as the exact string sent to Prometheus.
 *
 * <p>The metric names and label keys were read live on xamops-test-1 (kube-state-metrics 2.20.0,
 * Prometheus 3.15.0) and each of these strings was run there as a range query before it was pinned
 * here. A change to any of them is a change to what a chart shows, so it has to be deliberate.
 */
class PromQlSeriesTest {

    private static final PromQl.Selector PHP_APACHE = new PromQl.Selector("team-b",
            List.of("php-apache-7cd5b774b6-4s8nf", "php-apache-7cd5b774b6-5txj9"),
            List.of(PodNameRule.replicaSetPods("php-apache-7cd5b774b6")),
            List.of("php-apache"));

    private static final String SEL = "namespace=\"team-b\",pod=~\"php-apache-7cd5b774b6-4s8nf"
            + "|php-apache-7cd5b774b6-5txj9|php-apache-7cd5b774b6-[a-z0-9]{5}\",container=~\"php-apache\"";

    private static final String RUN = " * on(namespace, pod) group_left() (max by (namespace, pod)"
            + " (kube_pod_status_phase{phase=\"Running\"}) == 1)";

    // ---------------------------------------------------------------- workload scope

    @Test
    @DisplayName("workload used: the busiest pod's selected containers, with the engine's selector verbatim")
    void workloadUsed() {
        assertThat(PromQl.seriesWorkloadUsed(PHP_APACHE, PromQl.Resource.CPU))
                .isEqualTo("max(sum by (pod) (rate(container_cpu_usage_seconds_total{" + SEL + "}[5m])))");
        assertThat(PromQl.seriesWorkloadUsed(PHP_APACHE, PromQl.Resource.MEMORY))
                .isEqualTo("max(sum by (pod) (container_memory_working_set_bytes{" + SEL + "}))");
        assertThat(PromQl.seriesWorkloadUsed(PHP_APACHE, PromQl.Resource.CPU))
                .as("the selector is the engine's own labels(), not a copy of it")
                .contains("{" + PHP_APACHE.labels() + "}");
    }

    @Test
    @DisplayName("workload request and limit: declared per pod over the same containers, deduplicated per container")
    void workloadRequestAndLimit() {
        assertThat(PromQl.seriesWorkloadRequest(PHP_APACHE, PromQl.Resource.CPU))
                .isEqualTo("max(sum by (pod) (max by (pod, container) (kube_pod_container_resource_requests{"
                        + "resource=\"cpu\"," + SEL + "})))");
        assertThat(PromQl.seriesWorkloadRequest(PHP_APACHE, PromQl.Resource.MEMORY))
                .isEqualTo("max(sum by (pod) (max by (pod, container) (kube_pod_container_resource_requests{"
                        + "resource=\"memory\"," + SEL + "})))");
        assertThat(PromQl.seriesWorkloadLimit(PHP_APACHE, PromQl.Resource.CPU))
                .isEqualTo("max(sum by (pod) (max by (pod, container) (kube_pod_container_resource_limits{"
                        + "resource=\"cpu\"," + SEL + "})))");
        assertThat(PromQl.seriesWorkloadLimit(PHP_APACHE, PromQl.Resource.MEMORY))
                .isEqualTo("max(sum by (pod) (max by (pod, container) (kube_pod_container_resource_limits{"
                        + "resource=\"memory\"," + SEL + "})))");
    }

    // ---------------------------------------------------------------- namespace and cluster scope

    @Test
    @DisplayName("namespace scope: used per namespace, requested per namespace for Running pods only")
    void namespaceScope() {
        assertThat(PromQl.seriesNamespaceUsed(PromQl.Resource.CPU))
                .isEqualTo("sum by (namespace) (rate(container_cpu_usage_seconds_total{container!=\"\","
                        + "pod!=\"\"}[5m]))");
        assertThat(PromQl.seriesNamespaceUsed(PromQl.Resource.MEMORY))
                .isEqualTo("sum by (namespace) (container_memory_working_set_bytes{container!=\"\",pod!=\"\"})");
        assertThat(PromQl.seriesNamespaceRequest(PromQl.Resource.CPU))
                .isEqualTo("sum by (namespace) (max by (namespace, pod, container)"
                        + " (kube_pod_container_resource_requests{resource=\"cpu\"})" + RUN + ")");
        assertThat(PromQl.seriesNamespaceRequest(PromQl.Resource.MEMORY))
                .isEqualTo("sum by (namespace) (max by (namespace, pod, container)"
                        + " (kube_pod_container_resource_requests{resource=\"memory\"})" + RUN + ")");
    }

    @Test
    @DisplayName("cluster scope: used, requested (Running), allocatable and capacity, deduplicated per node")
    void clusterScope() {
        assertThat(PromQl.seriesClusterUsed(PromQl.Resource.CPU))
                .isEqualTo("sum(rate(container_cpu_usage_seconds_total{container!=\"\",pod!=\"\"}[5m]))");
        assertThat(PromQl.seriesClusterUsed(PromQl.Resource.MEMORY))
                .isEqualTo("sum(container_memory_working_set_bytes{container!=\"\",pod!=\"\"})");
        assertThat(PromQl.seriesClusterRequest(PromQl.Resource.CPU))
                .isEqualTo("sum(max by (namespace, pod, container) (kube_pod_container_resource_requests{"
                        + "resource=\"cpu\"})" + RUN + ")");
        assertThat(PromQl.seriesClusterAllocatable(PromQl.Resource.CPU))
                .isEqualTo("sum(max by (node) (kube_node_status_allocatable{resource=\"cpu\"}))");
        assertThat(PromQl.seriesClusterAllocatable(PromQl.Resource.MEMORY))
                .isEqualTo("sum(max by (node) (kube_node_status_allocatable{resource=\"memory\"}))");
        assertThat(PromQl.seriesClusterCapacity(PromQl.Resource.CPU))
                .isEqualTo("sum(max by (node) (kube_node_status_capacity{resource=\"cpu\"}))");
        assertThat(PromQl.seriesClusterCapacity(PromQl.Resource.MEMORY))
                .isEqualTo("sum(max by (node) (kube_node_status_capacity{resource=\"memory\"}))");
    }

    // ---------------------------------------------------------------- sparklines batch

    @Test
    @DisplayName("sparklines: one used and one request query per cluster, per container, over exactly the analysed namespaces")
    void sparklineBatch() {
        List<String> namespaces = List.of("team-b", "dev-sim", "team-b", "prod-sim");

        assertThat(PromQl.seriesPodContainerUsed(namespaces, PromQl.Resource.CPU))
                .isEqualTo("sum by (namespace, pod, container) (rate(container_cpu_usage_seconds_total{"
                        + "namespace=~\"dev-sim|prod-sim|team-b\",container!=\"\",pod!=\"\"}[5m]))");
        assertThat(PromQl.seriesPodContainerUsed(namespaces, PromQl.Resource.MEMORY))
                .isEqualTo("sum by (namespace, pod, container) (container_memory_working_set_bytes{"
                        + "namespace=~\"dev-sim|prod-sim|team-b\",container!=\"\",pod!=\"\"})");
        assertThat(PromQl.seriesPodContainerRequest(namespaces, PromQl.Resource.CPU))
                .isEqualTo("max by (namespace, pod, container) (kube_pod_container_resource_requests{"
                        + "namespace=~\"dev-sim|prod-sim|team-b\",resource=\"cpu\"})");
    }

    @Test
    @DisplayName("a namespace list is escaped, and an empty or blank one is refused rather than matching everything")
    void namespaceListsAreEscapedAndMandatory() {
        assertThat(PromQl.seriesPodContainerRequest(List.of("a.b"), PromQl.Resource.CPU))
                .contains("namespace=~\"a\\\\.b\"");
        assertThatThrownBy(() -> PromQl.seriesPodContainerUsed(List.of(), PromQl.Resource.CPU))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PromQl.seriesPodContainerRequest(List.of(" "), PromQl.Resource.CPU))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- HPA

    @Test
    @DisplayName("HPA series: the five kube-state-metrics series by namespace and HPA name, CPU filtered to utilization")
    void hpaSeries() {
        PromQl.HpaSelector hpa = new PromQl.HpaSelector("team-b", "php-apache");
        String labels = "namespace=~\"team-b\",horizontalpodautoscaler=~\"php-apache\"";

        assertThat(hpa.labels()).isEqualTo(labels);
        assertThat(PromQl.seriesHpaCurrentReplicas(hpa))
                .isEqualTo("max(kube_horizontalpodautoscaler_status_current_replicas{" + labels + "})");
        assertThat(PromQl.seriesHpaMinReplicas(hpa))
                .isEqualTo("max(kube_horizontalpodautoscaler_spec_min_replicas{" + labels + "})");
        assertThat(PromQl.seriesHpaMaxReplicas(hpa))
                .isEqualTo("max(kube_horizontalpodautoscaler_spec_max_replicas{" + labels + "})");
        assertThat(PromQl.seriesHpaCpuUtilization(hpa))
                .isEqualTo("max(kube_horizontalpodautoscaler_status_target_metric{" + labels
                        + ",metric_name=\"cpu\",metric_target_type=\"utilization\"})");
        assertThat(PromQl.seriesHpaCpuTarget(hpa))
                .isEqualTo("max(kube_horizontalpodautoscaler_spec_target_metric{" + labels
                        + ",metric_name=\"cpu\",metric_target_type=\"utilization\"})");
        assertThat(PromQl.seriesHpaAtMax(hpa))
                .isEqualTo("max(kube_horizontalpodautoscaler_status_current_replicas{" + labels + "})"
                        + " >= bool max(kube_horizontalpodautoscaler_spec_max_replicas{" + labels + "})");
    }

    @Test
    @DisplayName("an HPA name is escaped through escape(), so a dotted name stays an exact match")
    void hpaNamesAreEscaped() {
        PromQl.HpaSelector dotted = new PromQl.HpaSelector("team.b", "php.apache");

        assertThat(dotted.labels())
                .isEqualTo("namespace=~\"team\\\\.b\",horizontalpodautoscaler=~\"php\\\\.apache\"");
        String labels = dotted.labels();
        String quoted = labels.substring(labels.indexOf("horizontalpodautoscaler=~\"") + 26, labels.length() - 1);
        Pattern anchored = Pattern.compile("^(?:" + promqlStringValue(quoted) + ")$");
        assertThat(anchored.matcher("php.apache").matches()).isTrue();
        assertThat(anchored.matcher("phpXapache").matches())
                .as("an unescaped dot would match any character")
                .isFalse();
        assertThat(anchored.matcher("php.apache-canary").matches()).isFalse();
    }

    @Test
    @DisplayName("several HPAs: the same five series and the at-maximum comparison, one per HPA by its two labels")
    void hpaBatchSeries() {
        PromQl.HpaBatchSelector hpas = new PromQl.HpaBatchSelector(List.of(
                new PromQl.HpaSelector("team-b", "php-apache"), new PromQl.HpaSelector("production", "worker-hpa"),
                new PromQl.HpaSelector("team-b", "php.v2")));
        String labels = "namespace=~\"production|team-b\",horizontalpodautoscaler=~\"php-apache|php\\\\.v2|worker-hpa\"";
        String by = "max by (namespace, horizontalpodautoscaler) (";

        assertThat(hpas.labels()).as("sorted, without repeats, every name escaped").isEqualTo(labels);
        assertThat(PromQl.seriesHpasCurrentReplicas(hpas))
                .isEqualTo(by + "kube_horizontalpodautoscaler_status_current_replicas{" + labels + "})");
        assertThat(PromQl.seriesHpasMinReplicas(hpas))
                .isEqualTo(by + "kube_horizontalpodautoscaler_spec_min_replicas{" + labels + "})");
        assertThat(PromQl.seriesHpasMaxReplicas(hpas))
                .isEqualTo(by + "kube_horizontalpodautoscaler_spec_max_replicas{" + labels + "})");
        assertThat(PromQl.seriesHpasCpuUtilization(hpas))
                .isEqualTo(by + "kube_horizontalpodautoscaler_status_target_metric{" + labels
                        + ",metric_name=\"cpu\",metric_target_type=\"utilization\"})");
        assertThat(PromQl.seriesHpasCpuTarget(hpas))
                .isEqualTo(by + "kube_horizontalpodautoscaler_spec_target_metric{" + labels
                        + ",metric_name=\"cpu\",metric_target_type=\"utilization\"})");
        assertThat(PromQl.seriesHpasAtMax(hpas))
                .isEqualTo(by + "kube_horizontalpodautoscaler_status_current_replicas{" + labels + "})"
                        + " >= bool " + by + "kube_horizontalpodautoscaler_spec_max_replicas{" + labels + "})");
        assertThatThrownBy(() -> new PromQl.HpaBatchSelector(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an HPA selector refuses anything that is not a Kubernetes name, quotes and wildcards included")
    void hpaSelectorsRefuseNonNames() {
        for (String bad : List.of("", "Team-B", "a\"b", "a.*", "-a", "a b")) {
            assertThatThrownBy(() -> new PromQl.HpaSelector(bad, "php-apache"))
                    .as("namespace %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PromQl.HpaSelector("team-b", bad))
                    .as("name %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PromQl.HpaSelector(null, "php-apache"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- the selector's own matcher

    @Test
    @DisplayName("podNamePredicate decides exactly what Prometheus' anchored =~ decides for the same selector")
    void thePodPredicateAgreesWithPrometheus() {
        PromQl.Selector selector = new PromQl.Selector("team-a", List.of("php-apache-4e7a-aaaaa"),
                List.of(PodNameRule.replicaSetPods("php-apache-4e7a"), PodNameRule.replicaSetPods("php-apache-1b3d"),
                        PodNameRule.statefulSetPods("queue.v2")),
                List.of("php-apache"));
        Predicate<String> predicate = selector.podNamePredicate();
        Pattern prometheus = prometheusPodMatcher(selector);

        for (String pod : List.of("php-apache-4e7a-aaaaa", "php-apache-4e7a-zq8m2", "php-apache-1b3d-hk4p9",
                "php-apache-canary-6f2a-aaaaa", "php-apache-canary-6f2a", "php-apache-9c1e-aaaaa",
                "php-apache-aaaaa", "php-apache-4e7a-aaaa", "php-apache-4e7a-aaaaaa", "queue.v2-0", "queue.v2-17",
                "queueXv2-0", "queue.v2-replica-0", "", "php-apache-4e7a-AAAAA")) {
            assertThat(predicate.test(pod))
                    .as("pod %s", pod)
                    .isEqualTo(prometheus.matcher(pod).matches());
        }
        assertThat(predicate.test("php-apache-1b3d-hk4p9")).as("an older revision's pod").isTrue();
        assertThat(predicate.test("php-apache-canary-6f2a-aaaaa")).as("the canary's pod").isFalse();
        assertThat(predicate.test(null)).isFalse();
    }

    @Test
    @DisplayName("selectsContainer is membership in the selector's container list, nothing looser")
    void containersAreExact() {
        assertThat(PHP_APACHE.selectsContainer("php-apache")).isTrue();
        assertThat(PHP_APACHE.selectsContainer("istio-proxy")).isFalse();
        assertThat(PHP_APACHE.selectsContainer("php-apache-sidecar")).isFalse();
        assertThat(PHP_APACHE.selectsContainer(null)).isFalse();
    }

    /**
     * The pod matcher as Prometheus compiles it: the value of the PromQL string (Go escaping decoded)
     * anchored as {@code ^(?:…)$}. The same decoding {@code PodNameRuleSelectorTest} uses.
     */
    private static Pattern prometheusPodMatcher(PromQl.Selector selector) {
        String labels = selector.labels();
        String quoted = labels.substring(labels.indexOf("pod=~\"") + 6, labels.indexOf("\",container=~\""));
        return Pattern.compile("^(?:" + promqlStringValue(quoted) + ")$");
    }

    /** A PromQL double-quoted string's value, decoded as the PromQL (Go) parser decodes it. */
    private static String promqlStringValue(String quoted) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < quoted.length(); i++) {
            char c = quoted.charAt(i);
            if (c == '\\') {
                char next = quoted.charAt(++i);
                if (next != '\\' && next != '"') {
                    throw new AssertionError("PromQL rejects the escape \\" + next);
                }
                value.append(next);
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }
}
