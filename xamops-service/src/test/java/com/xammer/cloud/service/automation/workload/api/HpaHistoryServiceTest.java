package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.api.dto.HpaHistoriesDto;
import com.xammer.cloud.service.automation.workload.api.dto.HpaHistoryDto;
import com.xammer.cloud.service.automation.workload.api.dto.UsageSeriesDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.usage.PromQl;
import com.xammer.cloud.service.automation.workload.usage.PrometheusRangeReader;
import com.xammer.cloud.service.automation.workload.usage.UsageUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.END;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.FakeTransport;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.METRICS;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.NOW;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.TARGET;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.deployment;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.inventory;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.matrix;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.phpApache;
import static com.xammer.cloud.service.automation.workload.api.UsageFixtures.series;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * One HPA's history against recorded-shape kube-state-metrics matrices: the series at the window's
 * step, and the 60-second derivations (time at the maximum, replica changes) checked minute by
 * minute.
 */
class HpaHistoryServiceTest {

    private static final UsageWindow DAY = UsageWindow.fixed("24h").orElseThrow();
    private static final long START = END - 24 * 3600;
    private static final int DETAIL_POINTS = 1441;
    private static final PromQl.HpaSelector PHP_APACHE = new PromQl.HpaSelector("team-b", "php-apache");

    @Test
    @DisplayName("five series at the window step, and time at the maximum and changes from 60 s samples")
    void historyAndItsDerivations() {
        String[] atMax = new String[DETAIL_POINTS];
        String[] current = new String[DETAIL_POINTS];
        for (int i = 0; i < DETAIL_POINTS; i++) {
            atMax[i] = i < 10 ? "1" : i < 20 ? "0" : i < 30 ? null : "1";
            current[i] = i >= 100 && i < 110 ? "8" : i >= 200 && i < 210 ? null : "5";
        }
        FakeTransport transport = new FakeTransport()
                .on(">= bool", 60, matrix(series(Map.of(), START, 60, atMax)))
                .on("kube_horizontalpodautoscaler_status_current_replicas", 60,
                        matrix(series(Map.of(), START, 60, current)))
                .on("kube_horizontalpodautoscaler_status_current_replicas", 600,
                        matrix(series(Map.of(), START, 600, "5", "5", null, "8")))
                .on("kube_horizontalpodautoscaler_spec_min_replicas", 600, matrix(series(Map.of(), START, 600, "1")))
                .on("kube_horizontalpodautoscaler_spec_max_replicas", 600,
                        matrix(series(Map.of(), START, 600, "5", "5", "5", "8")))
                .on("kube_horizontalpodautoscaler_status_target_metric", 600,
                        matrix(series(Map.of(), START, 600, "59", "63")))
                .on("kube_horizontalpodautoscaler_spec_target_metric", 600,
                        matrix(series(Map.of(), START, 600, "50", "50")));

        HpaHistoryDto history = service(true, transport, inventory(), opening(), () -> 0L)
                .history(TARGET, "team-b", "php-apache", DAY);

        assertThat(history.unavailable()).isNull();
        assertThat(history.namespace()).isEqualTo("team-b");
        assertThat(history.name()).isEqualTo("php-apache");
        assertThat(history.window()).isEqualTo("24h");
        assertThat(history.stepSeconds()).isEqualTo(600);
        assertThat(history.start()).isEqualTo(Instant.ofEpochSecond(START));
        assertThat(history.end()).isEqualTo(Instant.ofEpochSecond(END));
        assertThat(history.source()).isEqualTo(new UsageSeriesDto.Source("PROMETHEUS_PROXY",
                "monitoring/prometheus-server:80"));

        assertThat(history.series()).extracting(HpaHistoryDto.Series::key)
                .containsExactly("currentReplicas", "minReplicas", "maxReplicas", "cpuUtilization", "cpuTarget");
        assertThat(history.series()).extracting(HpaHistoryDto.Series::label)
                .containsExactly("Replicas running", "Minimum", "Maximum", "CPU used", "Target");
        assertThat(history.series()).extracting(HpaHistoryDto.Series::unit)
                .containsExactly("replicas", "replicas", "replicas", "percent", "percent");
        assertThat(history.series()).extracting(HpaHistoryDto.Series::query).containsExactly(
                PromQl.seriesHpaCurrentReplicas(PHP_APACHE), PromQl.seriesHpaMinReplicas(PHP_APACHE),
                PromQl.seriesHpaMaxReplicas(PHP_APACHE), PromQl.seriesHpaCpuUtilization(PHP_APACHE),
                PromQl.seriesHpaCpuTarget(PHP_APACHE));
        List<UsageSeriesDto.Point> replicas = history.series().get(0).points();
        assertThat(replicas).hasSize(145);
        assertThat(replicas.subList(0, 5)).extracting(UsageSeriesDto.Point::v).containsExactly(5.0, 5.0, null, 8.0, null);

        HpaHistoryDto.AtMax at = history.atMax();
        assertThat(at.stepSeconds()).isEqualTo(60);
        assertThat(at.query()).isEqualTo(PromQl.seriesHpaAtMax(PHP_APACHE));
        assertThat(at.minutesMeasured()).as("a missing minute is not measured").isEqualTo(DETAIL_POINTS - 10);
        assertThat(at.minutesAtMax()).isEqualTo(10 + DETAIL_POINTS - 30);
        assertThat(at.share()).isEqualTo(SeriesPoints.round4((10.0 + DETAIL_POINTS - 30) / (DETAIL_POINTS - 10)));
        assertThat(at.runs()).containsExactly(
                new HpaHistoryDto.Run(minute(0), minute(10)),
                new HpaHistoryDto.Run(minute(30), null));
        assertThat(at.since()).as("at its maximum at the end, since minute 30").isEqualTo(minute(30));
        assertThat(at.runsTruncated()).isFalse();

        assertThat(history.changes()).containsExactly(
                new HpaHistoryDto.Change(minute(100), 5, 8),
                new HpaHistoryDto.Change(minute(110), 8, 5));
        assertThat(history.changesTruncated()).isFalse();
        assertThat(history.firstSampleAt()).isEqualTo(Instant.ofEpochSecond(START));

        assertThat(transport.asked).extracting(UsageFixtures.Asked::step)
                .containsExactly(600, 600, 600, 600, 600, 60, 60);
        assertThat(transport.asked).allSatisfy(asked -> {
            assertThat(asked.start()).isEqualTo(Instant.ofEpochSecond(START));
            assertThat(asked.end()).isEqualTo(Instant.ofEpochSecond(END));
        });
    }

    @Test
    @DisplayName("changes keep the newest 200, oldest first, and say that older ones were left out")
    void changesAreCapped() {
        PrometheusRangeReader.Grid detail = new PrometheusRangeReader.Grid(START, START + 300 * 60, 60);
        Double[] flapping = new Double[301];
        for (int i = 0; i < flapping.length; i++) {
            flapping[i] = i % 2 == 0 ? 5.0 : 6.0;
        }

        HpaHistoryService.Changes changes = HpaHistoryService.changes(flapping, detail);

        assertThat(changes.truncated()).isTrue();
        assertThat(changes.changes()).hasSize(200);
        assertThat(changes.changes().get(0)).isEqualTo(new HpaHistoryDto.Change(minute(101), 5, 6));
        assertThat(changes.changes().get(199)).isEqualTo(new HpaHistoryDto.Change(minute(300), 6, 5));
    }

    @Test
    @DisplayName("runs at the maximum keep the newest 1,000, the running one last")
    void runsAreCapped() {
        PrometheusRangeReader.Grid detail = new PrometheusRangeReader.Grid(START, START + 2400 * 60, 60);
        Double[] flapping = new Double[2401];
        for (int i = 0; i < flapping.length; i++) {
            flapping[i] = i % 2 == 0 ? 1.0 : 0.0;
        }

        HpaHistoryDto.AtMax at = HpaHistoryService.atMax(flapping, detail, "q");

        assertThat(at.runsTruncated()).isTrue();
        assertThat(at.runs()).hasSize(1000);
        assertThat(at.runs().get(999)).isEqualTo(new HpaHistoryDto.Run(minute(2400), null));
        assertThat(at.minutesAtMax()).isEqualTo(1201);
        assertThat(at.minutesMeasured()).isEqualTo(2401);
    }

    @Test
    @DisplayName("an HPA neither Prometheus nor the inventory knows is a 404 naming the field")
    void anUnknownHpaIsNotFound() {
        FakeTransport transport = new FakeTransport();
        HpaHistoryService service = service(true, transport, inventory(phpApache()), opening(), () -> 0L);

        UnknownTargetException unknown = assertThrows(UnknownTargetException.class,
                () -> service.history(TARGET, "team-b", "nope", DAY));

        assertThat(unknown.field()).isEqualTo("name");
        assertThat(unknown.getMessage()).contains("team-b/nope");
    }

    @Test
    @DisplayName("an HPA the cluster has but Prometheus has no samples for is a 200 with every point null")
    void aKnownHpaWithoutHistoryIsHonestlyEmpty() {
        WorkloadRef ref = new WorkloadRef("team-b", "Deployment", "php-apache", "uid-php-apache");
        ClusterWorkloadInventory.Workload withHpa = deployment("team-b", "php-apache",
                List.of("php-apache-7cd5b774b6-4s8nf"), List.of(PodNameRule.replicaSetPods("php-apache-7cd5b774b6")),
                HpaView.cpuHpa("php-apache", ref, 50, 1, 5), "php-apache");

        HpaHistoryDto history = service(true, new FakeTransport(), inventory(withHpa), opening(), () -> 0L)
                .history(TARGET, "team-b", "php-apache", DAY);

        assertThat(history.unavailable()).isNull();
        assertThat(history.series()).hasSize(5).allSatisfy(series ->
                assertThat(series.points()).hasSize(145).allSatisfy(point -> assertThat(point.v()).isNull()));
        assertThat(history.atMax().share()).as("nothing measured: no share, not 0%").isNull();
        assertThat(history.atMax().minutesMeasured()).isZero();
        assertThat(history.atMax().runs()).isEmpty();
        assertThat(history.atMax().since()).isNull();
        assertThat(history.changes()).isEmpty();
        assertThat(history.firstSampleAt()).isNull();
    }

    @Test
    @DisplayName("with HPA automation off nothing is opened or queried, and the answer says ENGINE_DISABLED")
    void theHorizontalSwitchGatesEveryCall() {
        FakeTransport transport = new FakeTransport();
        UsageFixtures.OpeningProvider provider = new UsageFixtures.OpeningProvider();
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);

        HpaHistoryDto history = service(false, transport, inventory, List.of(provider), () -> 0L)
                .history(TARGET, "team-b", "php-apache", DAY);

        assertThat(history.unavailable().code()).isEqualTo("ENGINE_DISABLED");
        assertThat(history.series()).isEmpty();
        assertThat(history.atMax()).isNull();
        assertThat(provider.opened).isZero();
        assertThat(transport.asked).isEmpty();
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("access and Prometheus failures are 200 answers with a typed code and nothing invented")
    void failuresAreTyped() {
        FakeTransport refused = new FakeTransport();
        refused.failure = new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "Prometheus at monitoring/prometheus-server:80 could not be queried (HTTP 503).");

        HpaHistoryDto noSource = service(true, refused, inventory(), opening(), () -> 0L)
                .history(TARGET, "team-b", "php-apache", DAY);
        HpaHistoryDto noAccess = service(true, new FakeTransport(), inventory(), List.of(), () -> 0L)
                .history(TARGET, "team-b", "php-apache", DAY);

        assertThat(noSource.unavailable().code()).isEqualTo("NO_METRICS_SOURCE");
        assertThat(noSource.series()).isEmpty();
        assertThat(noSource.atMax()).isNull();
        assertThat(noSource.changes()).isEmpty();
        assertThat(noAccess.unavailable().code()).isEqualTo("CLUSTER_ACCESS_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("an answer is reused for 60 s, then read again")
    void answersAreCachedBriefly() {
        AtomicLong nanos = new AtomicLong(0);
        FakeTransport transport = new FakeTransport()
                .on("kube_horizontalpodautoscaler_spec_max_replicas", matrix(series(Map.of(), START, 600, "5")));
        HpaHistoryService service = service(true, transport, inventory(), opening(), nanos::get);

        HpaHistoryDto first = service.history(TARGET, "team-b", "php-apache", DAY);
        assertThat(service.history(TARGET, "team-b", "php-apache", DAY)).isSameAs(first);
        assertThat(transport.asked).hasSize(7);

        nanos.addAndGet(Duration.ofSeconds(60).toNanos());
        service.history(TARGET, "team-b", "php-apache", DAY);
        assertThat(transport.asked).hasSize(14);
    }

    // ------------------------------------------------------------------ the HPA list's one read

    private static final PromQl.HpaSelector WORKER = new PromQl.HpaSelector("production", "worker-hpa");

    @Test
    @DisplayName("the list's read opens the cluster once and sends seven queries in all, each HPA keeping its own series")
    void aBatchReadsEveryHpaAtOnce() {
        FakeTransport transport = new FakeTransport()
                .on(">= bool", 60, matrix(
                        series(hpa("team-b", "php-apache"), START, 60, "1", "1"),
                        series(hpa("production", "worker-hpa"), START, 60, "0", "0")))
                .on("kube_horizontalpodautoscaler_status_current_replicas", 60, matrix(
                        series(hpa("team-b", "php-apache"), START, 60, "5", "5"),
                        series(hpa("production", "worker-hpa"), START, 60, "3", "4")))
                .on("kube_horizontalpodautoscaler_status_current_replicas", 600, matrix(
                        series(hpa("production", "worker-hpa"), START, 600, "3"),
                        // Matches both matchers, but nobody asked for team-b/worker-hpa.
                        series(hpa("team-b", "worker-hpa"), START, 600, "9"),
                        series(hpa("team-b", "php-apache"), START, 600, "5")))
                .on("kube_horizontalpodautoscaler_spec_max_replicas", 600, matrix(
                        series(hpa("team-b", "php-apache"), START, 600, "5"),
                        series(hpa("production", "worker-hpa"), START, 600, "10")));
        UsageFixtures.OpeningProvider provider = new UsageFixtures.OpeningProvider();

        HpaHistoriesDto batch = service(true, transport, inventory(), List.of(provider), () -> 0L)
                .histories(TARGET, List.of(PHP_APACHE, WORKER, PHP_APACHE), DAY);

        assertThat(provider.opened).as("one connection for the whole list").isEqualTo(1);
        PromQl.HpaBatchSelector both = new PromQl.HpaBatchSelector(List.of(PHP_APACHE, WORKER));
        assertThat(transport.queries()).containsExactly(
                PromQl.seriesHpasCurrentReplicas(both), PromQl.seriesHpasMinReplicas(both),
                PromQl.seriesHpasMaxReplicas(both), PromQl.seriesHpasCpuUtilization(both),
                PromQl.seriesHpasCpuTarget(both), PromQl.seriesHpasAtMax(both), PromQl.seriesHpasCurrentReplicas(both));
        assertThat(transport.asked).extracting(UsageFixtures.Asked::step)
                .containsExactly(600, 600, 600, 600, 600, 60, 60);
        assertThat(batch.window()).isEqualTo("24h");
        assertThat(batch.missing()).isEmpty();
        assertThat(batch.histories()).extracting(HpaHistoryDto::name)
                .as("in the order asked, a repeat read once")
                .containsExactly("php-apache", "worker-hpa");
        HpaHistoryDto php = batch.histories().get(0);
        HpaHistoryDto worker = batch.histories().get(1);
        assertThat(php.namespace()).isEqualTo("team-b");
        assertThat(php.series().get(0).points().get(0).v()).isEqualTo(5.0);
        assertThat(worker.series().get(0).points().get(0).v())
                .as("its own series, never the unasked team-b/worker-hpa")
                .isEqualTo(3.0);
        assertThat(worker.series().get(2).points().get(0).v()).isEqualTo(10.0);
        assertThat(worker.series().get(1).points()).as("no answer is every point null, never 0")
                .hasSize(145).allSatisfy(point -> assertThat(point.v()).isNull());
        assertThat(php.atMax().minutesAtMax()).isEqualTo(2);
        assertThat(worker.atMax().minutesAtMax()).isZero();
        assertThat(worker.atMax().minutesMeasured()).isEqualTo(2);
        assertThat(worker.changes()).containsExactly(new HpaHistoryDto.Change(minute(1), 3, 4));
        assertThat(php.series().get(0).query())
                .as("each series names the query that produced it")
                .isEqualTo(PromQl.seriesHpasCurrentReplicas(both));
        assertThat(php.atMax().query()).isEqualTo(PromQl.seriesHpasAtMax(both));
    }

    @Test
    @DisplayName("each HPA's entry in the list's read is what the one-HPA read answers for it, but for the query text")
    void aBatchEntryIsTheOneHpaAnswer() {
        String[] atMax = new String[DETAIL_POINTS];
        String[] current = new String[DETAIL_POINTS];
        for (int i = 0; i < DETAIL_POINTS; i++) {
            atMax[i] = i < 10 ? "1" : i < 20 ? "0" : i < 30 ? null : "1";
            current[i] = i >= 100 && i < 110 ? "8" : i >= 200 && i < 210 ? null : "5";
        }
        java.util.function.Supplier<FakeTransport> transport = () -> new FakeTransport()
                .on(">= bool", 60, matrix(series(hpa("team-b", "php-apache"), START, 60, atMax)))
                .on("kube_horizontalpodautoscaler_status_current_replicas", 60,
                        matrix(series(hpa("team-b", "php-apache"), START, 60, current)))
                .on("kube_horizontalpodautoscaler_status_current_replicas", 600,
                        matrix(series(hpa("team-b", "php-apache"), START, 600, "5", "5", null, "8")))
                .on("kube_horizontalpodautoscaler_spec_min_replicas", 600,
                        matrix(series(hpa("team-b", "php-apache"), START, 600, "1")))
                .on("kube_horizontalpodautoscaler_spec_max_replicas", 600,
                        matrix(series(hpa("team-b", "php-apache"), START, 600, "5", "5", "5", "8")))
                .on("kube_horizontalpodautoscaler_status_target_metric", 600,
                        matrix(series(hpa("team-b", "php-apache"), START, 600, "59", "63")))
                .on("kube_horizontalpodautoscaler_spec_target_metric", 600,
                        matrix(series(hpa("team-b", "php-apache"), START, 600, "50", "50")));

        HpaHistoryDto one = service(true, transport.get(), inventory(), opening(), () -> 0L)
                .history(TARGET, "team-b", "php-apache", DAY);
        HpaHistoryDto inBatch = service(true, transport.get(), inventory(), opening(), () -> 0L)
                .histories(TARGET, List.of(PHP_APACHE), DAY).histories().get(0);

        assertThat(one.atMax().minutesAtMax()).isPositive();
        assertThat(withoutQueries(inBatch)).isEqualTo(withoutQueries(one));
    }

    @Test
    @DisplayName("in the list's read an HPA nobody knows is listed as missing, and a known quiet one is all nulls")
    void aBatchListsTheHpasNobodyKnows() {
        WorkloadRef ref = new WorkloadRef("team-b", "Deployment", "php-apache", "uid-php-apache");
        ClusterWorkloadInventory.Workload withHpa = deployment("team-b", "php-apache",
                List.of("php-apache-7cd5b774b6-4s8nf"), List.of(PodNameRule.replicaSetPods("php-apache-7cd5b774b6")),
                HpaView.cpuHpa("php-apache", ref, 50, 1, 5), "php-apache");
        FakeTransport transport = new FakeTransport()
                .on("kube_horizontalpodautoscaler_spec_max_replicas", 600,
                        matrix(series(hpa("production", "worker-hpa"), START, 600, "10")));
        PromQl.HpaSelector gone = new PromQl.HpaSelector("team-c", "gone");

        HpaHistoriesDto batch = service(true, transport, inventory(withHpa), opening(), () -> 0L)
                .histories(TARGET, List.of(PHP_APACHE, WORKER, gone), DAY);

        assertThat(batch.histories()).extracting(HpaHistoryDto::name).containsExactly("php-apache", "worker-hpa");
        HpaHistoryDto quiet = batch.histories().get(0);
        assertThat(quiet.unavailable()).isNull();
        assertThat(quiet.series()).hasSize(5).allSatisfy(series ->
                assertThat(series.points()).allSatisfy(point -> assertThat(point.v()).isNull()));
        assertThat(quiet.atMax().share()).isNull();
        assertThat(batch.missing()).containsExactly(new HpaHistoriesDto.HpaRef("team-c", "gone"));
    }

    @Test
    @DisplayName("a failure in the list's read answers every HPA with the same typed unavailable, nothing invented")
    void aBatchFailureIsEveryHpasUnavailable() {
        FakeTransport refused = new FakeTransport();
        refused.failure = new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "Prometheus at monitoring/prometheus-server:80 could not be queried (HTTP 503).");

        HpaHistoriesDto noSource = service(true, refused, inventory(), opening(), () -> 0L)
                .histories(TARGET, List.of(PHP_APACHE, WORKER), DAY);
        HpaHistoriesDto noAccess = service(true, new FakeTransport(), inventory(), List.of(), () -> 0L)
                .histories(TARGET, List.of(PHP_APACHE, WORKER), DAY);

        assertThat(noSource.histories()).extracting(HpaHistoryDto::name).containsExactly("php-apache", "worker-hpa");
        assertThat(noSource.histories()).allSatisfy(history -> {
            assertThat(history.unavailable().code()).isEqualTo("NO_METRICS_SOURCE");
            assertThat(history.series()).isEmpty();
            assertThat(history.atMax()).isNull();
        });
        assertThat(noSource.missing()).isEmpty();
        assertThat(noAccess.histories()).extracting(history -> history.unavailable().code())
                .containsExactly("CLUSTER_ACCESS_NOT_CONFIGURED", "CLUSTER_ACCESS_NOT_CONFIGURED");
    }

    @Test
    @DisplayName("with HPA automation off the list's read opens nothing and says ENGINE_DISABLED for every HPA")
    void aBatchIsGatedByTheHorizontalSwitch() {
        FakeTransport transport = new FakeTransport();
        UsageFixtures.OpeningProvider provider = new UsageFixtures.OpeningProvider();
        ClusterWorkloadInventory inventory = mock(ClusterWorkloadInventory.class);

        HpaHistoriesDto batch = service(false, transport, inventory, List.of(provider), () -> 0L)
                .histories(TARGET, List.of(PHP_APACHE, WORKER), DAY);

        assertThat(batch.histories()).extracting(history -> history.unavailable().code())
                .containsExactly("ENGINE_DISABLED", "ENGINE_DISABLED");
        assertThat(provider.opened).isZero();
        assertThat(transport.asked).isEmpty();
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("the list's read is reused for 60 s, whatever the order the HPAs were named in, then read again")
    void aBatchIsCachedBriefly() {
        AtomicLong nanos = new AtomicLong(0);
        FakeTransport transport = new FakeTransport()
                .on("kube_horizontalpodautoscaler_spec_max_replicas",
                        matrix(series(hpa("team-b", "php-apache"), START, 600, "5"),
                                series(hpa("production", "worker-hpa"), START, 600, "10")));
        HpaHistoryService service = service(true, transport, inventory(), opening(), nanos::get);

        HpaHistoriesDto first = service.histories(TARGET, List.of(PHP_APACHE, WORKER), DAY);
        assertThat(service.histories(TARGET, List.of(WORKER, PHP_APACHE), DAY)).isSameAs(first);
        assertThat(transport.asked).hasSize(7);

        nanos.addAndGet(Duration.ofSeconds(60).toNanos());
        service.histories(TARGET, List.of(PHP_APACHE, WORKER), DAY);
        assertThat(transport.asked).hasSize(14);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> hpa(String namespace, String name) {
        return Map.of("namespace", namespace, "horizontalpodautoscaler", name);
    }

    /** The answer with every query text left out, for comparing two reads of the same samples. */
    private static HpaHistoryDto withoutQueries(HpaHistoryDto history) {
        HpaHistoryDto.AtMax at = history.atMax();
        return new HpaHistoryDto(history.schemaVersion(), history.namespace(), history.name(), history.window(),
                history.stepSeconds(), history.start(), history.end(), history.source(),
                history.series().stream()
                        .map(series -> new HpaHistoryDto.Series(series.key(), series.label(), series.unit(),
                                series.points(), null))
                        .toList(),
                at == null ? null : new HpaHistoryDto.AtMax(at.share(), at.minutesAtMax(), at.minutesMeasured(),
                        at.runs(), at.runsTruncated(), at.since(), at.stepSeconds(), null),
                history.changes(), history.changesTruncated(), history.firstSampleAt(), history.unavailable());
    }

    private static Instant minute(int index) {
        return Instant.ofEpochSecond(START + index * 60L);
    }

    private static List<ClusterAccessProvider> opening() {
        return List.of(new UsageFixtures.OpeningProvider());
    }

    private static HpaHistoryService service(boolean enabled, FakeTransport transport,
                                             ClusterWorkloadInventory inventory,
                                             List<ClusterAccessProvider> providers, LongSupplier nanoClock) {
        return new HpaHistoryService(new HorizontalAutoscalingProperties(enabled, false),
                new PrometheusRangeReader(transport, METRICS), providers, inventory,
                Clock.fixed(NOW, ZoneOffset.UTC), nanoClock);
    }
}
