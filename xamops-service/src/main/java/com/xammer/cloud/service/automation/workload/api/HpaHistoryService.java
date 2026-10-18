package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.HorizontalAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.HpaView;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.HpaHistoryDto;
import com.xammer.cloud.service.automation.workload.api.dto.UsageSeriesDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.usage.PromQl;
import com.xammer.cloud.service.automation.workload.usage.PrometheusRangeReader;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One HPA's history from kube-state-metrics (FEAT-004): replicas running against its minimum and
 * maximum, CPU utilisation against its target, how long it spent at its maximum, and every change of
 * the running replica count.
 *
 * <h2>Two resolutions over one span</h2>
 *
 * The five chart series are at the window's step (24 h at 10 min, 72 h at 30 min, 7 d at 1 h). The
 * time at the maximum and the changes come from 60-second samples of the same span — at most 10,081
 * points for 7 days, under Prometheus' 11,000-point limit — so a stretch at the ceiling or a scale
 * event shorter than a chart step is still counted. Every metric name and label was checked live on
 * xamops-test-1 (kube-state-metrics 2.20.0).
 *
 * <h2>An unknown HPA is a 404, a quiet one is not</h2>
 *
 * When Prometheus holds no sample for the HPA in the window, the inventory decides: an HPA the
 * cluster has (kube-state-metrics missing, or created a moment ago) answers {@code 200} with every
 * point null; one the cluster does not have is {@link UnknownTargetException} ({@code 404}).
 *
 * <p>Gated by {@code xamops.automation.horizontal.enabled}: switched off, no cluster call is made and
 * the answer is {@code ENGINE_DISABLED}. Cached for 60 s per {@code clientId:clusterConfigId:args}.
 */
@Service
public class HpaHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(HpaHistoryService.class);

    static final int DETAIL_STEP_SECONDS = 60;
    static final int MAX_CHANGES = 200;
    static final int MAX_RUNS = 1000;
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private static final String SOURCE_CLUSTER = "cluster";
    private static final String SOURCE_PROMETHEUS = "prometheus";

    private final HorizontalAutoscalingProperties properties;
    private final PrometheusRangeReader reader;
    private final ClusterOpener opener;
    private final ClusterWorkloadInventory inventory;
    private final Clock clock;
    private final ResponseCache<HpaHistoryDto> cache;
    private final SourceWarnings warnings;

    @Autowired
    public HpaHistoryService(HorizontalAutoscalingProperties properties,
                             PrometheusRangeReader reader,
                             List<ClusterAccessProvider> accessProviders,
                             ClusterWorkloadInventory inventory) {
        this(properties, reader, accessProviders, inventory, Clock.systemUTC(), System::nanoTime);
    }

    HpaHistoryService(HorizontalAutoscalingProperties properties,
                      PrometheusRangeReader reader,
                      List<ClusterAccessProvider> accessProviders,
                      ClusterWorkloadInventory inventory,
                      Clock clock,
                      LongSupplier nanoClock) {
        this.properties = properties;
        this.reader = reader;
        this.opener = new ClusterOpener(accessProviders);
        this.inventory = inventory;
        this.clock = clock;
        this.cache = new ResponseCache<>(CACHE_TTL, nanoClock);
        this.warnings = new SourceWarnings(nanoClock);
    }

    /**
     * @param window a fixed window: {@code 24h}, {@code 72h} or {@code 168h}
     * @throws UnknownTargetException when neither Prometheus nor the cluster knows this HPA
     */
    public HpaHistoryDto history(ClusterTarget target, String namespace, String name, UsageWindow window) {
        if (window.isCustom()) {
            throw new IllegalArgumentException("HPA history is read over a fixed window");
        }
        PrometheusRangeReader.Grid grid = window.grid(clock.instant());
        if (!properties.isEnabled()) {
            return unavailable(namespace, name, window, grid, AutomationUsageService.ENGINE_DISABLED,
                    "HPA automation is switched off in this environment, so XamOps reads no HPA history"
                            + " from the cluster.");
        }
        String key = target.clientId() + ":" + target.clusterConfigId() + ":" + target.region() + ":hpa:"
                + namespace + "/" + name + ":" + window.cacheKey();
        HpaHistoryDto cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        HpaHistoryDto response = read(target, namespace, name, window, grid);
        cache.put(key, response);
        return response;
    }

    private HpaHistoryDto read(ClusterTarget target, String namespace, String name, UsageWindow window,
                               PrometheusRangeReader.Grid grid) {
        PromQl.HpaSelector hpa = new PromQl.HpaSelector(namespace, name);
        PrometheusRangeReader.Grid detail = grid.withStep(DETAIL_STEP_SECONDS);
        List<SeriesSpec> specs = List.of(
                new SeriesSpec("currentReplicas", "Replicas running", "replicas",
                        PromQl.seriesHpaCurrentReplicas(hpa)),
                new SeriesSpec("minReplicas", "Minimum", "replicas", PromQl.seriesHpaMinReplicas(hpa)),
                new SeriesSpec("maxReplicas", "Maximum", "replicas", PromQl.seriesHpaMaxReplicas(hpa)),
                new SeriesSpec("cpuUtilization", "CPU used", "percent", PromQl.seriesHpaCpuUtilization(hpa)),
                new SeriesSpec("cpuTarget", "Target", "percent", PromQl.seriesHpaCpuTarget(hpa)));
        String atMaxQuery = PromQl.seriesHpaAtMax(hpa);

        try (ClusterAccess access = opener.open(target)) {
            KubernetesClient client = access.client();
            List<Double[]> values = new ArrayList<>();
            for (SeriesSpec spec : specs) {
                values.add(SeriesPoints.first(reader.read(client, spec.query(), grid), grid));
            }
            Double[] atMax = SeriesPoints.first(reader.read(client, atMaxQuery, detail), detail);
            Double[] current = SeriesPoints.first(reader.read(client, specs.get(0).query(), detail), detail);

            boolean sampled = SeriesPoints.anySample(atMax) || SeriesPoints.anySample(current)
                    || values.stream().anyMatch(SeriesPoints::anySample);
            if (!sampled && !known(client, namespace, name)) {
                throw new UnknownTargetException("name", "This cluster has no HPA " + namespace + "/" + name
                        + ", and Prometheus holds no history for one.");
            }

            List<HpaHistoryDto.Series> series = new ArrayList<>();
            Instant firstSampleAt = earliest(SeriesPoints.firstSample(atMax, detail),
                    SeriesPoints.firstSample(current, detail));
            for (int i = 0; i < specs.size(); i++) {
                SeriesSpec spec = specs.get(i);
                series.add(new HpaHistoryDto.Series(spec.key(), spec.label(), spec.unit(),
                        SeriesPoints.points(values.get(i), grid), spec.query()));
                firstSampleAt = earliest(firstSampleAt, SeriesPoints.firstSample(values.get(i), grid));
            }
            Changes changes = changes(current, detail);
            return new HpaHistoryDto(AutomationUsageService.SCHEMA_VERSION, namespace, name, window.label(),
                    grid.stepSeconds(), grid.start(), grid.end(), source(), series,
                    atMax(atMax, detail, atMaxQuery), changes.changes(), changes.truncated(), firstSampleAt,
                    null);
        } catch (ClusterAccessException e) {
            warn(target, SOURCE_CLUSTER, e.reasonCode(), e.getMessage());
            return unavailable(namespace, name, window, grid, e.reasonCode(), e.getMessage());
        } catch (PrometheusRangeReader.RangeQueryException e) {
            if (e.code() == PrometheusRangeReader.RangeQueryException.Code.METRICS_UNREADABLE) {
                logger.error("HPA history for {} on {} is unreadable: {} The body starts: {}",
                        namespace + "/" + name, target.describe(), e.getMessage(), e.bodyPrefix());
            } else {
                warn(target, SOURCE_PROMETHEUS, e.code().name(), e.getMessage());
            }
            return unavailable(namespace, name, window, grid, e.code().name(), e.getMessage());
        }
    }

    /** Whether the cluster's inventory has this HPA, attached to a workload the engine analyses. */
    private boolean known(KubernetesClient client, String namespace, String name) throws ClusterAccessException {
        return ClusterOpener.readInventory(inventory, client).workloads().stream()
                .map(ClusterWorkloadInventory.Workload::hpa)
                .filter(Objects::nonNull)
                .anyMatch((HpaView view) -> namespace.equals(view.namespace()) && name.equals(view.name()));
    }

    /**
     * Minutes at the maximum, from 60-second {@code current >= bool max} samples: a 1 is a minute at
     * the maximum, a 0 a minute below it, a missing sample a minute not measured. A run ends at the
     * first minute that is not at the maximum, measured or not.
     */
    static HpaHistoryDto.AtMax atMax(Double[] values, PrometheusRangeReader.Grid detail, String query) {
        int measured = 0;
        int atMax = 0;
        List<HpaHistoryDto.Run> runs = new ArrayList<>();
        Long runFrom = null;
        for (int i = 0; i < values.length; i++) {
            Double value = values[i];
            boolean atMaximum = value != null && value >= 0.5;
            if (value != null) {
                measured++;
            }
            if (atMaximum) {
                atMax++;
                if (runFrom == null) {
                    runFrom = detail.at(i);
                }
            } else if (runFrom != null) {
                runs.add(new HpaHistoryDto.Run(Instant.ofEpochSecond(runFrom), Instant.ofEpochSecond(detail.at(i))));
                runFrom = null;
            }
        }
        Instant since = null;
        if (runFrom != null) {
            since = Instant.ofEpochSecond(runFrom);
            runs.add(new HpaHistoryDto.Run(since, null));
        }
        boolean truncated = runs.size() > MAX_RUNS;
        if (truncated) {
            runs = runs.subList(runs.size() - MAX_RUNS, runs.size());
        }
        Double share = measured == 0 ? null : SeriesPoints.round4(atMax / (double) measured);
        return new HpaHistoryDto.AtMax(share, atMax, measured, runs, truncated, since, detail.stepSeconds(),
                query);
    }

    /** Every move of the running replica count, oldest first, keeping the newest {@link #MAX_CHANGES}. */
    static Changes changes(Double[] current, PrometheusRangeReader.Grid detail) {
        List<HpaHistoryDto.Change> changes = new ArrayList<>();
        Integer previous = null;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == null) {
                continue;
            }
            int replicas = (int) Math.round(current[i]);
            if (previous != null && replicas != previous) {
                changes.add(new HpaHistoryDto.Change(Instant.ofEpochSecond(detail.at(i)), previous, replicas));
            }
            previous = replicas;
        }
        boolean truncated = changes.size() > MAX_CHANGES;
        if (truncated) {
            changes = changes.subList(changes.size() - MAX_CHANGES, changes.size());
        }
        return new Changes(changes, truncated);
    }

    private HpaHistoryDto unavailable(String namespace, String name, UsageWindow window,
                                      PrometheusRangeReader.Grid grid, String code, String message) {
        return new HpaHistoryDto(AutomationUsageService.SCHEMA_VERSION, namespace, name, window.label(),
                grid.stepSeconds(), grid.start(), grid.end(), source(), List.of(), null, List.of(), false, null,
                new UsageSeriesDto.Unavailable(code, message));
    }

    private UsageSeriesDto.Source source() {
        return UsageSeriesDto.Source.prometheusProxy(reader.describeTarget());
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private void warn(ClusterTarget target, String source, String code, String message) {
        if (warnings.shouldWarn(target.clusterConfigId(), source)) {
            logger.warn("HPA history unavailable for {} ({}): {}: {}", target.describe(), source, code, message);
        } else {
            logger.debug("HPA history unavailable for {} ({}): {}: {}", target.describe(), source, code, message);
        }
    }

    private record SeriesSpec(String key, String label, String unit, String query) {
    }

    record Changes(List<HpaHistoryDto.Change> changes, boolean truncated) {
    }
}
