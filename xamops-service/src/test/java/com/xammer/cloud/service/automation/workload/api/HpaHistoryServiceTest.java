package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.PodNameRule;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
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

    // ------------------------------------------------------------------ helpers

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
