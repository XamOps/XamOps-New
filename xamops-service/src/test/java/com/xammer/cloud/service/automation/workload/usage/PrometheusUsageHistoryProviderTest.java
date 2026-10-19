package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * The real provider, driven through a fake transport over recorded Prometheus responses.
 *
 * <p>Fixtures live in {@code src/test/resources/k8s/prometheus/}. No network, no cluster, no
 * {@code kubernetes-server-mock}: the transport seam exists precisely so the PromQL and the parsing
 * are assertable as values.
 */
class PrometheusUsageHistoryProviderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/k8s/prometheus");

    private static final ClusterTarget TARGET =
            new ClusterTarget(7L, 42L, "102227938259", "xamops-test-1", "ap-south-1");
    private static final WorkloadRef REF =
            new WorkloadRef("dev-sim", "Deployment", "no-requests-sidecar-app", "dep-uid");
    private static final List<String> PODS =
            List.of("no-requests-sidecar-app-abc-1", "no-requests-sidecar-app-abc-2");
    private static final List<String> CONTAINERS = List.of("app", "logshipper");

    @Test
    @DisplayName("one aggregate per container, with the sidecar measured separately (audit bug 8)")
    void everyContainerGetsItsOwnMeasurement() throws Exception {
        FakeTransport transport = healthy();

        Map<String, ContainerUsageAggregate> aggregates = provider(transport).aggregates(access(), query());

        assertThat(aggregates).containsOnlyKeys("app", "logshipper");

        ContainerUsageAggregate app = aggregates.get("app");
        assertThat(app.containerName()).isEqualTo("app");
        assertThat(app.cpuMillis().at(95)).isEqualTo(123);
        assertThat(app.memBytes().at(95)).isEqualTo(537395200L);
        assertThat(app.cpuThrottleRatio()).isEqualTo(0.25);
        assertThat(app.oomKillsInWindow()).isEqualTo(2);

        ContainerUsageAggregate sidecar = aggregates.get("logshipper");
        assertThat(sidecar.cpuMillis().at(95))
                .as("the Go rightsizer gave a 10m log shipper the API container's recommendation")
                .isEqualTo(8);
        assertThat(sidecar.memBytes().at(95)).isEqualTo(41943040L);
        assertThat(sidecar.oomKillsInWindow())
                .as("kube-state-metrics has no series for a container that never terminated")
                .isZero();
        assertThat(sidecar.cpuThrottleRatio())
                .as("Prometheus answers NaN for 0/0 CFS periods; the conservative reading is 'no "
                        + "throttling measured', which withholds every limit-derived increase")
                .isZero();
    }

    @Test
    @DisplayName("the window bounds and the coverage are measured, not taken from the clock")
    void theWindowAndCoverageAreMeasured() throws Exception {
        Map<String, ContainerUsageAggregate> aggregates =
                provider(healthy()).aggregates(access(), query());

        ContainerUsageAggregate app = aggregates.get("app");
        assertThat(app.windowStart()).isEqualTo(Instant.ofEpochSecond(1760000000L));
        assertThat(app.windowEnd()).isEqualTo(Instant.ofEpochSecond(1760086370L));
        assertThat(app.window()).isEqualTo(Duration.ofSeconds(86370));
        assertThat(app.sampleCoverage())
                .as("2880 samples at the observed 30s cadence is a fully covered 24h window")
                .isEqualTo(1.0);
        assertThat(app.sourceName()).isEqualTo("prometheus-proxy");
    }

    @Test
    @DisplayName("CPU mean and stddev populate when measured, stay null on NaN, and don't move coverage")
    void cpuMeanAndStddevPopulateOrStayNull() throws Exception {
        Map<String, ContainerUsageAggregate> aggregates =
                provider(healthy()).aggregates(access(), query());

        ContainerUsageAggregate app = aggregates.get("app");
        assertThat(app.cpuMeanMillis())
                .as("Prometheus already evaluated the * 1000, so the body carries millicores")
                .isEqualTo(100L);
        assertThat(app.cpuStddevMillis()).isEqualTo(20L);

        ContainerUsageAggregate sidecar = aggregates.get("logshipper");
        assertThat(sidecar.cpuMeanMillis()).isEqualTo(5L);
        assertThat(sidecar.cpuStddevMillis())
                .as("Prometheus answered NaN for the stddev; it stays null, never a fabricated zero")
                .isNull();

        assertThat(app.sampleCoverage())
                .as("the two added queries must not change the measured coverage")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("at() answers exactly the policy percentile; a neighbour throws")
    void onlyTheRequestedPercentileIsAnswerable() throws Exception {
        Map<String, ContainerUsageAggregate> aggregates =
                provider(healthy()).aggregates(access(), query());
        ContainerUsageAggregate app = aggregates.get("app");

        assertThat(app.cpuMillis().at(95)).isEqualTo(123);
        assertThrows(PercentileUnavailableException.class, () -> app.cpuMillis().at(90));
        assertThrows(PercentileUnavailableException.class, () -> app.memBytes().at(99));
    }

    @Test
    @DisplayName("memory is the window max, filed under the requested percentile AND carried as max")
    void memoryIsTheWindowMaximum() throws Exception {
        // The brief specifies p100/max for memory: a memory shortfall OOM-kills immediately. But
        // PercentileSet.at() must answer exactly the policy's percentile or throw, so the measured
        // maximum is filed under it -- the same measurement either way, with no substitution.
        Map<String, ContainerUsageAggregate> aggregates =
                provider(healthy()).aggregates(access(), query());

        ContainerUsageAggregate app = aggregates.get("app");
        assertThat(app.memBytes().max()).isEqualTo(537395200L);
        assertThat(app.memBytes().at(95)).isEqualTo(app.memBytes().max());
        assertThat(app.cpuMillis().max())
                .as("no CPU maximum was queried, and null is how 'not provided' is said")
                .isNull();
    }

    @Test
    @DisplayName("a different percentile is asked of Prometheus, not substituted afterwards")
    void thePolicyPercentileReachesTheQuery() throws Exception {
        FakeTransport transport = healthy();

        provider(transport).aggregates(access(), new UsageQuery(TARGET, REF, CONTAINERS, PODS, 99, 24));

        assertThat(transport.queryContaining("quantile_over_time")).contains("quantile_over_time(0.99,");
    }

    // ---------------------------------------------------------------- the selector (audit bug 8)

    @Test
    @DisplayName("every query carries the namespace, the resolved pods and the containers")
    void everyQueryIsFullyScoped() throws Exception {
        FakeTransport transport = healthy();

        provider(transport).aggregates(access(), query());

        assertThat(transport.queries).hasSize(9);
        for (String promql : transport.queries) {
            assertThat(promql).contains("namespace=\"dev-sim\"");
            assertThat(promql).contains(
                    "pod=~\"no-requests-sidecar-app-abc-1|no-requests-sidecar-app-abc-2\"");
            assertThat(promql).contains("container=~\"app|logshipper\"");
            assertThat(promql)
                    .as("a name-prefix regex is how one workload claims another's pods")
                    .doesNotContain(".*");
            assertThat(promql)
                    .as("results must stay per container, or one metric is applied to all of them")
                    .contains("by (container)");
        }
    }

    @Test
    @DisplayName("containers younger than the ignore window are excluded by an offset")
    void youngContainersAreExcluded() throws Exception {
        FakeTransport transport = healthy();

        provider(transport).aggregates(access(), query());

        for (String promql : transport.queries) {
            assertThat(promql)
                    .as("a container that started seconds ago has no samples in the evaluated"
                            + " window, so it waits instead of being sized from two scrapes")
                    .contains("offset 120s");
        }
    }

    @Test
    @DisplayName("the window the policy asked for is the window that is queried")
    void thePolicyWindowReachesTheQuery() {
        FakeTransport transport = healthy();

        // The recorded fixtures hold 24h of samples, so a 72h policy window is correctly refused as
        // one-third covered. That refusal IS the behaviour under test everywhere else; here it just
        // has to not hide which window was asked for.
        UsageUnavailableException refused = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(),
                        new UsageQuery(TARGET, REF, CONTAINERS, PODS, 95, 72)));

        assertThat(refused.kind()).isEqualTo(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY);
        assertThat(transport.queryContaining("quantile_over_time")).contains("[72h:]");
        assertThat(transport.queryContaining("count_over_time")).contains("[72h]");
    }

    @Test
    @DisplayName("a pod name with regex metacharacters cannot widen the selector")
    void podNamesAreRegexEscaped() throws Exception {
        FakeTransport transport = healthy();

        provider(transport).aggregates(access(),
                new UsageQuery(TARGET, REF, CONTAINERS, List.of("api.prod", "api-2"), 95, 24));

        assertThat(transport.queryContaining("count_over_time"))
                .contains("pod=~\"api\\\\.prod|api-2\"");
    }

    // ------------------------------------------- history outlives the current pods (addendum A)

    @Test
    @DisplayName("the pods the workload owned EARLIER in the window are measured too (addendum A)")
    void historicalPodsAreMeasured() throws Exception {
        FakeTransport transport = healthy();

        // Two retained ReplicaSets: the one serving traffic now and the revision it replaced. The
        // pods of the old revision are long gone, but their samples are still in Prometheus -- and
        // before this they were unreachable, so one rollout reset the workload's history to zero.
        provider(transport).aggregates(access(), new UsageQuery(TARGET, REF, CONTAINERS,
                List.of("no-requests-sidecar-app-9f3c-aaaaa"),
                List.of(PodNameRule.replicaSetPods("no-requests-sidecar-app-9f3c"),
                        PodNameRule.replicaSetPods("no-requests-sidecar-app-5b8c")),
                95, 24));

        for (String promql : transport.queries) {
            assertThat(promql).contains("pod=~\"no-requests-sidecar-app-9f3c-aaaaa"
                    + "|no-requests-sidecar-app-9f3c-[a-z0-9]{5}"
                    + "|no-requests-sidecar-app-5b8c-[a-z0-9]{5}\"");
            assertThat(promql)
                    .as("reaching back must not reintroduce the prefix match: the bound is five"
                            + " characters of ReplicaSet suffix, never an unbounded quantifier")
                    .doesNotContain(".*")
                    .doesNotContain(".+");
        }
    }

    @Test
    @DisplayName("a StatefulSet's historical ordinals are measured, a sibling's are not")
    void statefulSetOrdinalsAreMeasured() throws Exception {
        FakeTransport transport = healthy();

        provider(transport).aggregates(access(), new UsageQuery(TARGET, REF, CONTAINERS,
                List.of("stateful-queue-0"),
                List.of(PodNameRule.statefulSetPods("stateful-queue")), 95, 24));

        assertThat(transport.queryContaining("count_over_time"))
                .contains("pod=~\"stateful-queue-0|stateful-queue-[0-9]+\"");
    }

    // ---------------------------------------------------------------- the two typed failures

    @Test
    @DisplayName("an empty Prometheus result is INSUFFICIENT_HISTORY, never a zero")
    void anEmptyResultIsNotZero() {
        // "We measured zero CPU" and "we measured nothing" are different statements, and only one
        // of them is a basis for resizing a production container.
        FakeTransport transport = healthy();
        transport.override("count_over_time", "empty.json");

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(), query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY);
        assertThat(failure.getMessage()).contains("no samples");
    }

    @Test
    @DisplayName("a Prometheus error body is NO_METRICS_SOURCE, and names what Prometheus said")
    void aPrometheusErrorIsNoSource() {
        FakeTransport transport = healthy();
        transport.override("quantile_over_time", "error.json");

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(), query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
        assertThat(failure.getMessage()).contains("parse error");
    }

    @Test
    @DisplayName("a response that is not JSON is NO_METRICS_SOURCE, never swallowed into a zero")
    void anUnreadableBodyIsNoSource() {
        FakeTransport transport = healthy();
        transport.raw("quantile_over_time", "<html>504 Gateway Timeout</html>");

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(), query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
    }

    @Test
    @DisplayName("coverage below the engine's minimum is INSUFFICIENT_HISTORY, not a recommendation")
    void thinCoverageIsRefused() {
        // The measured harness run had 4h52m of a 24h window. Lowering MIN_SAMPLE_COVERAGE to get a
        // nicer demo is exactly what this refusal exists to prevent.
        FakeTransport transport = healthy();
        transport.override("count_over_time", "sample-count-low.json");
        transport.override("min_over_time(timestamp", "first-sample-partial.json");

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(), query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY);
        assertThat(failure.getMessage())
                .contains("20% of the 24h window")
                .contains(String.valueOf((int) (WorkloadPolicyDefaults.MIN_SAMPLE_COVERAGE * 100)));
    }

    @Test
    @DisplayName("a window shorter than the engine's minimum is INSUFFICIENT_HISTORY")
    void aShortWindowIsRefused() {
        FakeTransport transport = healthy();
        transport.override("min_over_time(timestamp", "first-sample-short.json");

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(), query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY);
        assertThat(failure.getMessage()).contains("1h0m").contains("2h0m");
    }

    @Test
    @DisplayName("a single sample has no cadence and no window, so it is INSUFFICIENT_HISTORY")
    void oneSampleIsNotAWindow() {
        FakeTransport transport = healthy();
        transport.override("count_over_time", "sample-count-one.json");

        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(transport).aggregates(access(), query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.INSUFFICIENT_HISTORY);
        assertThat(failure.getMessage()).contains("1 sample");
    }

    @Test
    @DisplayName("with no cluster connection the answer is NO_METRICS_SOURCE, not an empty map")
    void withoutAccessNothingIsMeasured() {
        UsageUnavailableException failure = assertThrows(UsageUnavailableException.class,
                () -> provider(healthy()).aggregates(null, query()));

        assertThat(failure.kind()).isEqualTo(UsageUnavailableException.Kind.NO_METRICS_SOURCE);
    }

    @Test
    @DisplayName("reachability is delegated to the transport's real proxied probe")
    void reachabilityIsProbed() {
        FakeTransport transport = healthy();
        transport.reachable = false;

        assertThat(provider(transport).reachable(access())).isFalse();
        assertThat(provider(transport).reachable(null)).isFalse();

        transport.reachable = true;
        assertThat(provider(transport).reachable(access())).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private static UsageQuery query() {
        return new UsageQuery(TARGET, REF, CONTAINERS, PODS, WorkloadPolicyDefaults.PERCENTILE, 24);
    }

    private static ClusterAccess access() {
        return new ClusterAccess(mock(KubernetesClient.class), "https://127.0.0.1", "xamops-test-1",
                "ap-south-1", "fake");
    }

    private static PrometheusUsageHistoryProvider provider(FakeTransport transport) {
        return new PrometheusUsageHistoryProvider(transport, metricsProperties());
    }

    private static VerticalMetricsProperties metricsProperties() {
        return new VerticalMetricsProperties("monitoring", "prometheus-server", 9090, 20, 120, 24, "OFF");
    }

    private static FakeTransport healthy() {
        FakeTransport transport = new FakeTransport();
        transport.override("quantile_over_time", "cpu-p95.json");
        transport.override("container_memory_working_set_bytes", "memory-max.json");
        transport.override("cfs_throttled", "throttle.json");
        transport.override("last_terminated_reason", "oom.json");
        transport.override("count_over_time", "sample-count.json");
        transport.override("min_over_time(timestamp", "first-sample.json");
        transport.override("max_over_time(timestamp", "last-sample.json");
        transport.override("avg_over_time", "cpu-mean.json");
        transport.override("stddev_over_time", "cpu-stddev.json");
        return transport;
    }

    /**
     * Serves recorded responses by matching a fragment of the query, and records every query sent.
     *
     * <p>Subclasses the real transport rather than hiding behind an interface, so the production
     * class's own path building and message copy stay exercised by
     * {@link PrometheusProxyTransportTest} and nothing here can drift from it.
     */
    private static final class FakeTransport extends PrometheusProxyTransport {

        private final Map<String, String> bodiesByFragment = new HashMap<>();
        private final List<String> queries = new ArrayList<>();
        private boolean reachable = true;

        private FakeTransport() {
            super(metricsProperties());
        }

        private void override(String fragment, String fixture) {
            bodiesByFragment.put(fragment, read(fixture));
        }

        private void raw(String fragment, String body) {
            bodiesByFragment.put(fragment, body);
        }

        @Override
        public String instantQuery(KubernetesClient client, String promql) {
            queries.add(promql);
            for (Map.Entry<String, String> entry : bodiesByFragment.entrySet()) {
                if (promql.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            throw new IllegalStateException("no recorded response for query: " + promql);
        }

        @Override
        public boolean reachable(KubernetesClient client) {
            return reachable;
        }

        private String queryContaining(String fragment) {
            return queries.stream().filter(query -> query.contains(fragment)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no query contained " + fragment));
        }
    }

    private static String read(String fixture) {
        try {
            return Files.readString(FIXTURES.resolve(fixture), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
