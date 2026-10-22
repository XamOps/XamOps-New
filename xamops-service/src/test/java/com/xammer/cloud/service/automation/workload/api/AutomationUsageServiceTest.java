package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.SparklinesDto;
import com.xammer.cloud.service.automation.workload.api.dto.UsageSeriesDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.usage.PromQl;
import com.xammer.cloud.service.automation.workload.usage.PrometheusProxyTransport;
import com.xammer.cloud.service.automation.workload.usage.PrometheusRangeReader;
import com.xammer.cloud.service.automation.workload.usage.PrometheusUsageHistoryProvider;
import com.xammer.cloud.service.automation.workload.usage.UsageQuery;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.END;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.FakeTransport;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.METRICS;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.NOW;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.TARGET;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.access;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.deployment;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.inventory;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.matrix;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.phpApache;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.pod;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The usage history service against recorded-shape Prometheus matrices: no network, no cluster.
 *
 * <p>What matters most here: the workload chart and the sparklines read exactly the pods and
 * containers the engine reads (the same {@link PromQl.Selector}), a missing sample stays null, and
 * every source failure is a typed {@code unavailable} rather than a series of zeros.
 */
class AutomationUsageServiceTest {

    private static final UsageWindow DAY = UsageWindow.fixed("24h").orElseThrow();
    private static final UsageWindow THREE_DAYS = UsageWindow.fixed("72h").orElseThrow();
    private static final WorkloadRef PHP_APACHE = WorkloadRef.of("team-b", "Deployment", "php-apache");

    // ------------------------------------------------------------------ gates and access

    @Test
    @DisplayName("with the vertical engine off nothing is opened or queried, and the answer says ENGINE_DISABLED")
    void theEngineSwitchGatesEveryCall() {
        FakeTransport transport = new FakeTransport();
        UsageFixtures.OpeningProvider provider = new UsageFixtures.OpeningProvider();
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);
        AutomationUsageService service = service(false, transport, inventory, List.of(provider), () -> 0L);

        UsageSeriesDto usage = service.usage(TARGET, cluster(THREE_DAYS));
        SparklinesDto sparklines = service.sparklines(TARGET, PromQl.Resource.CPU);

        assertThat(usage.unavailable().code()).isEqualTo("ENGINE_DISABLED");
        assertThat(usage.series()).isEmpty();
        assertThat(usage.coverage()).isNull();
        assertThat(sparklines.unavailable().code()).isEqualTo("ENGINE_DISABLED");
        assertThat(sparklines.workloads()).isEmpty();
        assertThat(provider.opened).isZero();
        assertThat(transport.asked).isEmpty();
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("no provider for the cluster is CLUSTER_ACCESS_NOT_CONFIGURED; an unreachable one is CLUSTER_UNREACHABLE")
    void accessFailuresAreUnavailableNotErrors() {
        FakeTransport transport = new FakeTransport();

        UsageSeriesDto none = service(true, transport, inventory(), List.of(), () -> 0L)
                .usage(TARGET, cluster(THREE_DAYS));
        UsageSeriesDto unreachable = service(true, transport, inventory(),
                List.of(new UsageFixtures.FailingProvider(ClusterAccessException.Kind.CLUSTER_UNREACHABLE)), () -> 0L)
                .usage(TARGET, cluster(THREE_DAYS));

        assertThat(none.unavailable().code()).isEqualTo("CLUSTER_ACCESS_NOT_CONFIGURED");
        assertThat(unreachable.unavailable().code()).isEqualTo("CLUSTER_UNREACHABLE");
        assertThat(unreachable.series()).isEmpty();
        assertThat(unreachable.source().service())
                .as("the source is still named, so the empty state can say where data would come from")
                .isEqualTo("monitoring/prometheus-server:80");
        assertThat(transport.asked).isEmpty();
    }

    @Test
    @DisplayName("the API server refusing the inventory lists is CLUSTER_ACCESS_NOT_CONFIGURED; other failures UNREACHABLE")
    void inventoryFailuresAreTypedLikeAccess() {
        ClusterWorkloadInventory forbidden = mock(ClusterWorkloadInventory.class);
        when(forbidden.read(any())).thenThrow(new KubernetesClientException("Forbidden", 403, null));
        ClusterWorkloadInventory broken = mock(ClusterWorkloadInventory.class);
        when(broken.read(any())).thenThrow(new KubernetesClientException("etcdserver: leader changed", 500, null));

        assertThat(service(true, new FakeTransport(), forbidden, opening(), () -> 0L)
                .usage(TARGET, workload(THREE_DAYS, PHP_APACHE)).unavailable().code())
                .isEqualTo("CLUSTER_ACCESS_NOT_CONFIGURED");
        assertThat(service(true, new FakeTransport(), broken, opening(), () -> 0L)
                .sparklines(TARGET, PromQl.Resource.CPU).unavailable().code())
                .isEqualTo("CLUSTER_UNREACHABLE");
    }

    // ------------------------------------------------------------------ cluster scope

    @Test
    @DisplayName("cluster scope: used, requested, allocatable and provisioned, every step present and gaps null")
    void clusterScope() {
        long start = END - 24 * 3600;
        FakeTransport transport = new FakeTransport()
                .on("kube_node_status_allocatable", matrix(series(Map.of(), start, 600, "7.72", "7.72")))
                .on("kube_node_status_capacity", matrix(series(Map.of(), start, 600, "8", "8")))
                .on("kube_pod_container_resource_requests", matrix(series(Map.of(), start, 600, "5.985", null, "4.44")))
                .on("container_cpu_usage_seconds_total",
                        matrix(series(Map.of(), start, 600, null, "1.01", "NaN", "0.93")));

        UsageSeriesDto response = service(true, transport, inventory(), opening(), () -> 0L)
                .usage(TARGET, cluster(DAY));

        assertThat(response.unavailable()).isNull();
        assertThat(response.schemaVersion()).isEqualTo(1);
        assertThat(response.scope()).isEqualTo("cluster");
        assertThat(response.resource()).isEqualTo("cpu");
        assertThat(response.unit()).isEqualTo("cores");
        assertThat(response.window()).isEqualTo("24h");
        assertThat(response.stepSeconds()).isEqualTo(600);
        assertThat(response.start()).isEqualTo(Instant.ofEpochSecond(start));
        assertThat(response.end()).isEqualTo(Instant.ofEpochSecond(END));
        assertThat(response.workloadRef()).isNull();
        assertThat(response.namespaces()).isNull();
        assertThat(response.source()).isEqualTo(new UsageSeriesDto.Source("PROMETHEUS_PROXY",
                "monitoring/prometheus-server:80"));

        assertThat(response.series()).extracting(UsageSeriesDto.Series::key)
                .containsExactly("used", "request", "allocatable", "capacity");
        assertThat(response.series()).extracting(UsageSeriesDto.Series::kind)
                .containsExactly("used", "request", "allocatable", "capacity");
        assertThat(response.series()).extracting(UsageSeriesDto.Series::label)
                .containsExactly("Used", "Requested", "Allocatable", "Provisioned");
        assertThat(response.series()).extracting(UsageSeriesDto.Series::query).containsExactly(
                PromQl.seriesClusterUsed(PromQl.Resource.CPU), PromQl.seriesClusterRequest(PromQl.Resource.CPU),
                PromQl.seriesClusterAllocatable(PromQl.Resource.CPU),
                PromQl.seriesClusterCapacity(PromQl.Resource.CPU));

        List<UsageSeriesDto.Point> used = response.series().get(0).points();
        assertThat(used).hasSize(145);
        assertThat(used.get(0)).as("no sample yet: null, not 0").isEqualTo(new UsageSeriesDto.Point(start, null));
        assertThat(used.get(1)).isEqualTo(new UsageSeriesDto.Point(start + 600, 1.01));
        assertThat(used.get(2).v()).as("NaN is not a measurement").isNull();
        assertThat(used.get(3).v()).isEqualTo(0.93);
        assertThat(used.get(144)).isEqualTo(new UsageSeriesDto.Point(END, null));
        assertThat(response.series().get(1).points().get(1).v()).as("a gap in requests stays a gap").isNull();

        assertThat(response.coverage().firstSampleAt()).isEqualTo(Instant.ofEpochSecond(start + 600));
        assertThat(response.coverage().covered()).isEqualTo(SeriesPoints.round4(2 / 145.0));

        assertThat(transport.asked).hasSize(4).allSatisfy(asked -> {
            assertThat(asked.start()).isEqualTo(Instant.ofEpochSecond(start));
            assertThat(asked.end()).isEqualTo(Instant.ofEpochSecond(END));
            assertThat(asked.step()).isEqualTo(600);
        });
    }

    @Test
    @DisplayName("the fixed windows step at 10 min, 30 min and 1 h, ending at the current whole minute")
    void fixedWindowsAndSteps() {
        FakeTransport transport = new FakeTransport();
        AutomationUsageService service = service(true, transport, inventory(), opening(), () -> 0L);

        for (String window : List.of("24h", "72h", "168h")) {
            UsageSeriesDto response = service.usage(TARGET, cluster(UsageWindow.fixed(window).orElseThrow()));
            assertThat(response.end()).isEqualTo(Instant.ofEpochSecond(END));
            assertThat(response.series().get(0).points())
                    .as(window)
                    .hasSize(window.equals("168h") ? 169 : 145)
                    .allSatisfy(point -> assertThat(point.v()).isNull());
            assertThat(response.coverage()).isEqualTo(new UsageSeriesDto.Coverage(null, 0.0));
        }
        assertThat(transport.asked).extracting(UsageFixtures.Asked::step)
                .containsExactly(600, 600, 600, 600, 1800, 1800, 1800, 1800, 3600, 3600, 3600, 3600);
    }

    // ------------------------------------------------------------------ namespace scope

    @Test
    @DisplayName("namespace scope: every namespace either query returned, requested now and the average used")
    void namespaceScope() {
        long start = END - 72 * 3600;
        String[] teamBRequests = new String[145];
        Arrays.fill(teamBRequests, "1.2");
        teamBRequests[144] = "1.125";
        FakeTransport transport = new FakeTransport()
                .on("kube_pod_container_resource_requests", matrix(
                        series(Map.of("namespace", "team-b"), start, 1800, teamBRequests),
                        series(Map.of("namespace", "kube-system"), start, 1800, "1")))
                .on("container_cpu_usage_seconds_total", matrix(
                        series(Map.of("namespace", "team-b"), start, 1800, "0.6", "0.62"),
                        series(Map.of("namespace", "team-a"), start, 1800, null, "0.0001")));

        UsageSeriesDto response = service(true, transport, inventory(), opening(), () -> 0L)
                .usage(TARGET, new AutomationUsageService.UsageRequest(AutomationUsageService.Scope.NAMESPACE,
                        PromQl.Resource.CPU, THREE_DAYS, null));

        assertThat(response.scope()).isEqualTo("namespace");
        assertThat(response.series()).isEmpty();
        assertThat(response.namespaces()).extracting(UsageSeriesDto.NamespaceSeries::namespace)
                .containsExactly("kube-system", "team-a", "team-b");

        UsageSeriesDto.NamespaceSeries kubeSystem = response.namespaces().get(0);
        assertThat(kubeSystem.requestedNow()).as("no sample at the last step: unknown, not 0").isNull();
        assertThat(kubeSystem.usedAvg()).as("never measured: unknown, not 0").isNull();

        UsageSeriesDto.NamespaceSeries teamA = response.namespaces().get(1);
        assertThat(teamA.usedAvg()).isEqualTo(0.0001);
        assertThat(teamA.requestedNow()).isNull();

        UsageSeriesDto.NamespaceSeries teamB = response.namespaces().get(2);
        assertThat(teamB.requestedNow()).isEqualTo(1.125);
        assertThat(teamB.usedAvg()).isCloseTo(0.61, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(teamB.series()).extracting(UsageSeriesDto.Series::key).containsExactly("used", "request");
        assertThat(teamB.series()).extracting(UsageSeriesDto.Series::label).containsExactly("Used", "Requested");
        assertThat(teamB.series().get(1).query()).isEqualTo(PromQl.seriesNamespaceRequest(PromQl.Resource.CPU));
        assertThat(teamB.series().get(0).points().get(2).v()).isNull();

        assertThat(response.coverage().firstSampleAt()).isEqualTo(Instant.ofEpochSecond(start));
        assertThat(response.coverage().covered())
                .as("a step is covered when any namespace was measured at it")
                .isEqualTo(SeriesPoints.round4(2 / 145.0));
    }

    // ------------------------------------------------------------------ workload scope

    @Test
    @DisplayName("scope=workload selects with the engine's own Selector: the UsageQuery path's labels, verbatim")
    void theWorkloadChartReadsTheEnginesPods() {
        ClusterWorkloadInventory.Workload php = phpApache();
        FakeTransport transport = new FakeTransport();

        UsageSeriesDto response = service(true, transport, inventory(php), opening(), () -> 0L)
                .usage(TARGET, workload(THREE_DAYS, PHP_APACHE));

        // The engine's path, end to end: the UsageQuery VerticalAutomationReadService.measure builds,
        // through the real PrometheusUsageHistoryProvider, recording what it sends.
        UsageQuery engineQuery = new UsageQuery(TARGET, php.ref(), php.containerNames(), php.pods().names(),
                php.pods().nameRules(), 95, 72);
        InstantRecorder engine = new InstantRecorder();
        assertThrows(UsageUnavailableException.class,
                () -> new PrometheusUsageHistoryProvider(engine, METRICS).aggregates(access(), engineQuery));
        String engineCpu = engine.queries.stream().filter(query -> query.contains("quantile_over_time"))
                .findFirst().orElseThrow();
        String engineMemory = engine.queries.stream().filter(query -> query.contains("max_over_time("
                + "container_memory_working_set_bytes")).findFirst().orElseThrow();

        assertThat(selectorOf(transport.queries().get(0), "container_cpu_usage_seconds_total"))
                .isEqualTo(selectorOf(engineCpu, "container_cpu_usage_seconds_total"))
                .isEqualTo("namespace=\"team-b\",pod=~\"php-apache-7cd5b774b6-4s8nf|php-apache-7cd5b774b6-5txj9"
                        + "|php-apache-7cd5b774b6-[a-z0-9]{5}|php-apache-5f9c8d7b6c-[a-z0-9]{5}\","
                        + "container=~\"php-apache\"");
        assertThat(AutomationUsageService.selectorFor(php)).isEqualTo(new PromQl.Selector(
                engineQuery.ref().namespace(), engineQuery.podNames(), engineQuery.podNameRules(),
                engineQuery.containerNames()));
        assertThat(transport.queries()).containsExactly(
                PromQl.seriesWorkloadUsed(AutomationUsageService.selectorFor(php), PromQl.Resource.CPU),
                PromQl.seriesWorkloadRequest(AutomationUsageService.selectorFor(php), PromQl.Resource.CPU),
                PromQl.seriesWorkloadLimit(AutomationUsageService.selectorFor(php), PromQl.Resource.CPU));

        // Memory reads the same pods as the engine's memory query, too.
        FakeTransport memory = new FakeTransport();
        service(true, memory, inventory(php), opening(), () -> 0L).usage(TARGET,
                new AutomationUsageService.UsageRequest(AutomationUsageService.Scope.WORKLOAD,
                        PromQl.Resource.MEMORY, THREE_DAYS, PHP_APACHE));
        assertThat(selectorOf(memory.queries().get(0), "container_memory_working_set_bytes"))
                .isEqualTo(selectorOf(engineMemory, "container_memory_working_set_bytes"));

        assertThat(response.workloadRef()).isEqualTo(new UsageSeriesDto.Ref("team-b", "Deployment", "php-apache"));
        assertThat(response.series()).extracting(UsageSeriesDto.Series::label)
                .containsExactly("Used", "Request now", "Limit");
    }

    @Test
    @DisplayName("a workload the inventory does not have is a 404 naming the field, and nothing is queried")
    void anUnknownWorkloadIsNotFound() {
        FakeTransport transport = new FakeTransport();
        AutomationUsageService service = service(true, transport, inventory(phpApache()), opening(), () -> 0L);

        UnknownTargetException byName = assertThrows(UnknownTargetException.class,
                () -> service.usage(TARGET, workload(THREE_DAYS, WorkloadRef.of("team-b", "Deployment", "nope"))));
        UnknownTargetException byKind = assertThrows(UnknownTargetException.class,
                () -> service.usage(TARGET, workload(THREE_DAYS,
                        WorkloadRef.of("team-b", "StatefulSet", "php-apache"))));

        assertThat(byName.field()).isEqualTo("name");
        assertThat(byKind.field()).isEqualTo("name");
        assertThat(transport.asked).isEmpty();
    }

    @Test
    @DisplayName("a workload with no running pod has no selector, so it says INSUFFICIENT_HISTORY and queries nothing")
    void aWorkloadWithoutPodsIsAWaitingState() {
        FakeTransport transport = new FakeTransport();
        ClusterWorkloadInventory.Workload idle = deployment("team-b", "php-apache", List.of(),
                List.of(PodNameRule.replicaSetPods("php-apache-7cd5b774b6")), "php-apache");

        UsageSeriesDto response = service(true, transport, inventory(idle), opening(), () -> 0L)
                .usage(TARGET, workload(THREE_DAYS, PHP_APACHE));

        assertThat(response.unavailable().code()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(response.unavailable().message()).contains("team-b/php-apache").contains("no running pod");
        assertThat(response.workloadRef()).isEqualTo(new UsageSeriesDto.Ref("team-b", "Deployment", "php-apache"));
        assertThat(response.series()).isEmpty();
        assertThat(transport.asked).isEmpty();
    }

    @Test
    @DisplayName("a custom window is read exactly as asked: from, to and the step, at most 600 points")
    void aCustomWindowIsReadAsAsked() {
        Instant from = Instant.parse("2026-10-16T09:00:00Z");
        Instant to = Instant.parse("2026-10-16T15:00:00Z");
        FakeTransport transport = new FakeTransport();

        UsageSeriesDto response = service(true, transport, inventory(phpApache()), opening(), () -> 0L)
                .usage(TARGET, workload(UsageWindow.custom(from, to, 60), PHP_APACHE));

        assertThat(response.window()).isEqualTo("custom");
        assertThat(response.stepSeconds()).isEqualTo(60);
        assertThat(response.start()).isEqualTo(from);
        assertThat(response.end()).isEqualTo(to);
        assertThat(response.series().get(0).points()).hasSize(361);
        assertThat(transport.asked).hasSize(3).allSatisfy(asked -> {
            assertThat(asked.start()).isEqualTo(from);
            assertThat(asked.end()).isEqualTo(to);
            assertThat(asked.step()).isEqualTo(60);
        });
    }

    // ------------------------------------------------------------------ sparklines

    @Test
    @DisplayName("sparklines: two queries per cluster, each series sorted into its workload by the engine's own selector")
    void sparklinesUseTheEnginesSelection() {
        long start = END - 72 * 3600;
        ClusterWorkloadInventory.Workload php = phpApache();
        ClusterWorkloadInventory.Workload canary = deployment("team-b", "php-apache-canary",
                List.of("php-apache-canary-6f2a9c-aaaaa"),
                List.of(PodNameRule.replicaSetPods("php-apache-canary-6f2a9c")), "php-apache");
        ClusterWorkloadInventory.Workload otherNamespace = deployment("team-a", "php-apache",
                List.of("php-apache-77aa88bb99-xxxxx"),
                List.of(PodNameRule.replicaSetPods("php-apache-77aa88bb99")), "php-apache");
        ClusterWorkloadInventory.Workload web = deployment("team-b", "web", List.of("web-6d4f5b7c8d-abcde"),
                List.of(PodNameRule.replicaSetPods("web-6d4f5b7c8d")), "web", "log-shipper");
        ClusterWorkloadInventory.Workload idle = deployment("dev-sim", "idle", List.of(),
                List.of(PodNameRule.replicaSetPods("idle-55c8f955d9")), "app");

        FakeTransport transport = new FakeTransport()
                .on("kube_pod_container_resource_requests", matrix(
                        series(pod("team-b", "php-apache-7cd5b774b6-4s8nf", "php-apache"), start, 3600, "0.2", "0.2"),
                        series(pod("team-b", "php-apache-7cd5b774b6-4s8nf", "istio-proxy"), start, 3600, "0.1", "0.1"),
                        series(pod("team-b", "php-apache-canary-6f2a9c-aaaaa", "php-apache"), start, 3600, "0.5")))
                .on("container_cpu_usage_seconds_total", matrix(
                        series(pod("team-b", "php-apache-7cd5b774b6-4s8nf", "php-apache"), start, 3600, "0.10", "0.20"),
                        // An injected sidecar the template does not declare: the engine never measures it.
                        series(pod("team-b", "php-apache-7cd5b774b6-4s8nf", "istio-proxy"), start, 3600, "0.50", "0.50"),
                        series(pod("team-b", "php-apache-7cd5b774b6-5txj9", "php-apache"), start, 3600, null, "0.15"),
                        // A pod of the retained older ReplicaSet: in, through its PodNameRule.
                        series(pod("team-b", "php-apache-5f9c8d7b6c-old11", "php-apache"), start, 3600, "0.30"),
                        // The canary Deployment: php-apache-.* would have claimed it.
                        series(pod("team-b", "php-apache-canary-6f2a9c-aaaaa", "php-apache"), start, 3600, "0.90", "0.90"),
                        // The same name in another namespace.
                        series(pod("team-a", "php-apache-77aa88bb99-xxxxx", "php-apache"), start, 3600, "0.70"),
                        series(pod("team-b", "web-6d4f5b7c8d-abcde", "web"), start, 3600, "0.5"),
                        series(pod("team-b", "web-6d4f5b7c8d-abcde", "log-shipper"), start, 3600, "0.25")));

        SparklinesDto response = service(true, transport, inventory(web, php, idle, canary, otherNamespace),
                opening(), () -> 0L).sparklines(TARGET, PromQl.Resource.CPU);

        assertThat(response.unavailable()).isNull();
        assertThat(response.window()).isEqualTo("72h");
        assertThat(response.stepSeconds()).isEqualTo(3600);
        assertThat(response.unit()).isEqualTo("cores");
        assertThat(transport.queries()).containsExactly(
                PromQl.seriesPodContainerUsed(List.of("dev-sim", "team-a", "team-b"), PromQl.Resource.CPU),
                PromQl.seriesPodContainerRequest(List.of("dev-sim", "team-a", "team-b"), PromQl.Resource.CPU));
        assertThat(response.queries()).isEqualTo(new SparklinesDto.Queries(transport.queries().get(0),
                transport.queries().get(1)));
        assertThat(response.workloads()).extracting(row -> row.ref().namespace() + "/" + row.ref().name())
                .containsExactly("dev-sim/idle", "team-a/php-apache", "team-b/php-apache", "team-b/php-apache-canary",
                        "team-b/web");

        SparklinesDto.WorkloadSparkline phpRow = response.workloads().get(2);
        assertThat(phpRow.used()).hasSize(73);
        assertThat(values(phpRow.used()).subList(0, 3))
                .as("busiest pod per step: the old revision's 0.30, then 4s8nf's 0.20; never the canary's 0.90,"
                        + " the sidecar's 0.50 or team-a's 0.70")
                .containsExactly(0.30, 0.20, null);
        assertThat(values(phpRow.request()).subList(0, 3)).containsExactly(0.2, 0.2, null);

        assertThat(values(response.workloads().get(3).used()).subList(0, 2)).containsExactly(0.90, 0.90);
        assertThat(values(response.workloads().get(1).used()).subList(0, 2)).containsExactly(0.70, null);
        assertThat(values(response.workloads().get(4).used()).get(0))
                .as("a pod's declared containers are summed: 0.5 + 0.25")
                .isEqualTo(0.75);
        assertThat(values(response.workloads().get(0).used()))
                .as("no running pod: every point null, nothing invented")
                .hasSize(73)
                .containsOnlyNulls();
        assertThat(response.workloads().get(0).used().get(72).t()).isEqualTo(END);
    }

    @Test
    @DisplayName("sparklines on a cluster with no analysed workload send no query at all")
    void noWorkloadsNoQueries() {
        FakeTransport transport = new FakeTransport();

        SparklinesDto response = service(true, transport, inventory(), opening(), () -> 0L)
                .sparklines(TARGET, PromQl.Resource.MEMORY);

        assertThat(response.workloads()).isEmpty();
        assertThat(response.unavailable()).isNull();
        assertThat(response.queries()).isNull();
        assertThat(response.unit()).isEqualTo("bytes");
        assertThat(transport.asked).isEmpty();
    }

    // ------------------------------------------------------------------ source failures

    @Test
    @DisplayName("Prometheus failures are 200 answers with a typed code: no source, timeout, unreadable")
    void metricsFailuresAreTyped() {
        FakeTransport refused = new FakeTransport();
        refused.failure = new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "XamOps is not allowed to reach Prometheus through the Kubernetes API service proxy.");
        FakeTransport slow = new FakeTransport();
        slow.failure = new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "could not be queried", new KubernetesClientException("request failed",
                new SocketTimeoutException("Read timed out")));
        FakeTransport garbled = new FakeTransport().on("", "<html>502 Bad Gateway</html>");

        UsageSeriesDto noSource = service(true, refused, inventory(), opening(), () -> 0L)
                .usage(TARGET, cluster(THREE_DAYS));
        UsageSeriesDto timeout = service(true, slow, inventory(), opening(), () -> 0L)
                .usage(TARGET, cluster(THREE_DAYS));
        UsageSeriesDto unreadable = service(true, garbled, inventory(), opening(), () -> 0L)
                .usage(TARGET, cluster(THREE_DAYS));
        SparklinesDto sparklines = service(true, refused, inventory(phpApache()), opening(), () -> 0L)
                .sparklines(TARGET, PromQl.Resource.CPU);

        assertThat(noSource.unavailable()).isEqualTo(new UsageSeriesDto.Unavailable("NO_METRICS_SOURCE",
                "XamOps is not allowed to reach Prometheus through the Kubernetes API service proxy."));
        assertThat(timeout.unavailable().code()).isEqualTo("METRICS_TIMEOUT");
        assertThat(unreadable.unavailable().code()).isEqualTo("METRICS_UNREADABLE");
        assertThat(sparklines.unavailable().code()).isEqualTo("NO_METRICS_SOURCE");
        for (UsageSeriesDto failed : List.of(noSource, timeout, unreadable)) {
            assertThat(failed.series()).isEmpty();
            assertThat(failed.coverage()).isNull();
        }
    }

    // ------------------------------------------------------------------ cache

    @Test
    @DisplayName("an answer is reused for 60 s per tenant, cluster and arguments, failures included")
    void answersAreCachedBriefly() {
        AtomicLong nanos = new AtomicLong(5_000);
        FakeTransport transport = new FakeTransport();
        AutomationUsageService service = service(true, transport, inventory(), opening(), nanos::get);

        UsageSeriesDto first = service.usage(TARGET, cluster(THREE_DAYS));
        assertThat(service.usage(TARGET, cluster(THREE_DAYS))).isSameAs(first);
        assertThat(transport.asked).hasSize(4);

        service.usage(new ClusterTarget(8L, 42L, "102227938259", "xamops-test-1", "ap-south-1"), cluster(THREE_DAYS));
        assertThat(transport.asked).as("another tenant is never served this tenant's answer").hasSize(8);
        service.usage(TARGET, cluster(DAY));
        assertThat(transport.asked).as("other arguments, other entry").hasSize(12);

        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        assertThat(service.usage(TARGET, cluster(THREE_DAYS))).isNotSameAs(first);
        assertThat(transport.asked).hasSize(16);

        UsageFixtures.FailingProvider broken =
                new UsageFixtures.FailingProvider(ClusterAccessException.Kind.CLUSTER_UNREACHABLE);
        AutomationUsageService brokenCluster = service(true, transport, inventory(), List.of(broken), nanos::get);
        brokenCluster.usage(TARGET, cluster(THREE_DAYS));
        brokenCluster.usage(TARGET, cluster(THREE_DAYS));
        assertThat(broken.opened).as("a broken cluster is not re-dialled for every chart").isEqualTo(1);
    }

    @Test
    @DisplayName("the cache is bounded: expired entries are swept, and a full cache admits nothing new")
    void theCacheIsBounded() {
        AtomicLong nanos = new AtomicLong(0);
        ResponseCache<String> cache = new ResponseCache<>(Duration.ofSeconds(60), nanos::get);

        for (int i = 0; i < ResponseCache.MAX_ENTRIES + 10; i++) {
            cache.put("7:42:ap-south-1:custom@" + i, "answer " + i);
        }
        assertThat(cache.size()).isEqualTo(ResponseCache.MAX_ENTRIES);
        assertThat(cache.get("7:42:ap-south-1:custom@" + (ResponseCache.MAX_ENTRIES + 5)))
                .as("not admitted: uncached is only slower, never wrong")
                .isNull();
        assertThat(cache.get("7:42:ap-south-1:custom@0")).isEqualTo("answer 0");

        nanos.addAndGet(Duration.ofSeconds(60).toNanos());
        cache.put("7:42:ap-south-1:fresh", "fresh");
        assertThat(cache.size()).as("the full cache swept its expired entries to admit this one").isEqualTo(1);
        assertThat(cache.get("7:42:ap-south-1:fresh")).isEqualTo("fresh");
        assertThat(cache.get("7:42:ap-south-1:custom@0")).as("expired at the TTL").isNull();
    }

    // ------------------------------------------------------------------ helpers

    private static AutomationUsageService.UsageRequest cluster(UsageWindow window) {
        return new AutomationUsageService.UsageRequest(AutomationUsageService.Scope.CLUSTER, PromQl.Resource.CPU,
                window, null);
    }

    private static AutomationUsageService.UsageRequest workload(UsageWindow window, WorkloadRef ref) {
        return new AutomationUsageService.UsageRequest(AutomationUsageService.Scope.WORKLOAD, PromQl.Resource.CPU,
                window, ref);
    }

    private static List<ClusterAccessProvider> opening() {
        return List.of(new UsageFixtures.OpeningProvider());
    }

    private static AutomationUsageService service(boolean enabled, PrometheusProxyTransport transport,
                                                  ClusterWorkloadInventory inventory,
                                                  List<ClusterAccessProvider> providers, LongSupplier nanoClock) {
        return new AutomationUsageService(new WorkloadAutoscalingProperties(enabled, false),
                new PrometheusRangeReader(transport, METRICS), providers, inventory,
                Clock.fixed(NOW, ZoneOffset.UTC), nanoClock);
    }

    private static List<Double> values(List<UsageSeriesDto.Point> points) {
        List<Double> values = new ArrayList<>();
        points.forEach(point -> values.add(point.v()));
        return values;
    }

    /**
     * The label selector inside {@code metric{…}} of a query. It ends at the first {@code "}}: the
     * last matcher's value is always quoted, while the {@code {5}} inside a pod rule is not.
     */
    private static String selectorOf(String query, String metric) {
        int open = query.indexOf(metric + "{") + metric.length() + 1;
        return query.substring(open, query.indexOf("\"}", open) + 1);
    }

    /** The engine's instant-query transport, recording what it is asked and answering "no samples". */
    private static final class InstantRecorder extends PrometheusProxyTransport {

        private final List<String> queries = new ArrayList<>();

        private InstantRecorder() {
            super(METRICS);
        }

        @Override
        public String instantQuery(KubernetesClient client, String promql) {
            queries.add(promql);
            return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}";
        }
    }
}
