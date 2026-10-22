package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.access.ClusterAccess;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessException;
import com.xammer.cloud.service.automation.workload.access.ClusterAccessProvider;
import com.xammer.cloud.service.automation.workload.access.ClusterTarget;
import com.xammer.cloud.service.automation.workload.api.dto.SparklinesDto;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The read-only usage history behind the Automation charts (FEAT-004): CPU or memory over a window
 * for the cluster, for every namespace, or for one workload, and a sparklines batch with one series
 * pair per workload.
 *
 * <h2>The charts read the pods the engine reads</h2>
 *
 * For {@code scope=workload} and the sparklines, the pod selection is the engine's own: the
 * inventory resolves each workload's pods through owner references and yields its
 * {@code PodNameRule}s, and {@link #selectorFor} builds the very {@link PromQl.Selector} the engine's
 * usage query is built from. A chart therefore never shows a pod the suggestion was not computed
 * from, and {@code php-apache} never claims {@code php-apache-canary}'s pods.
 *
 * <h2>Nothing is invented</h2>
 *
 * A point Prometheus did not answer for is {@code null}. A source failure is a {@code 200} with
 * {@code unavailable {code, message}} and no series. With the vertical engine switched off — the
 * default in every profile but UAT — no cluster call is made at all and the answer is
 * {@code ENGINE_DISABLED}.
 *
 * <h2>Bounded and briefly cached</h2>
 *
 * Fixed windows are at most 169 points per series; the controller caps a custom window at 600. Each
 * answer is cached for {@link #CACHE_TTL} per {@code clientId:clusterConfigId:args}, failures
 * included, so a broken cluster is not re-dialled for every chart on a page.
 */
@Service
public class AutomationUsageService {

    private static final Logger logger = LoggerFactory.getLogger(AutomationUsageService.class);

    public static final int SCHEMA_VERSION = 1;
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /** The sparklines' only window, at one point per hour (design-delta §5.3). */
    static final String SPARKLINE_WINDOW = "72h";
    static final int SPARKLINE_STEP_SECONDS = 3600;

    static final String ENGINE_DISABLED = "ENGINE_DISABLED";
    private static final String SOURCE_CLUSTER = "cluster";
    private static final String SOURCE_PROMETHEUS = "prometheus";

    /** What a usage read covers. On the wire in lower case. */
    public enum Scope {
        CLUSTER, NAMESPACE, WORKLOAD;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One validated usage read.
     *
     * @param workload the workload for {@link Scope#WORKLOAD}; null for the other scopes
     */
    public record UsageRequest(Scope scope, PromQl.Resource resource, UsageWindow window, WorkloadRef workload) {

        public UsageRequest {
            if (scope == null || resource == null || window == null) {
                throw new IllegalArgumentException("scope, resource and window are mandatory");
            }
            if ((scope == Scope.WORKLOAD) != (workload != null)) {
                throw new IllegalArgumentException("a workload is named for scope=workload and only then");
            }
            if (window.isCustom() && scope != Scope.WORKLOAD) {
                throw new IllegalArgumentException("a custom window is for scope=workload only");
            }
        }

        String cacheKey() {
            return "usage:" + scope.wire() + ":" + resource.ksm() + ":" + window.cacheKey()
                    + (workload == null ? "" : ":" + workload.namespace() + "/" + workload.kind() + "/"
                    + workload.name());
        }
    }

    private final WorkloadAutoscalingProperties properties;
    private final PrometheusRangeReader reader;
    private final ClusterOpener opener;
    private final ClusterWorkloadInventory inventory;
    private final Clock clock;
    private final ResponseCache<UsageSeriesDto> usageCache;
    private final ResponseCache<SparklinesDto> sparklineCache;
    private final SourceWarnings warnings;

    @Autowired
    public AutomationUsageService(WorkloadAutoscalingProperties properties,
                                  PrometheusRangeReader reader,
                                  List<ClusterAccessProvider> accessProviders,
                                  ClusterWorkloadInventory inventory) {
        this(properties, reader, accessProviders, inventory, Clock.systemUTC(), System::nanoTime);
    }

    /**
     * @param clock     where "now" comes from: the end of every fixed window
     * @param nanoClock a monotonic nanosecond source; the seam the cache and the WARN limit are
     *                  tested through
     */
    AutomationUsageService(WorkloadAutoscalingProperties properties,
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
        this.usageCache = new ResponseCache<>(CACHE_TTL, nanoClock);
        this.sparklineCache = new ResponseCache<>(CACHE_TTL, nanoClock);
        this.warnings = new SourceWarnings(nanoClock);
    }

    /**
     * @throws UnknownTargetException for {@code scope=workload} naming a workload the inventory does
     *                                not have (404 {@code field: name})
     */
    public UsageSeriesDto usage(ClusterTarget target, UsageRequest request) {
        PrometheusRangeReader.Grid grid = request.window().grid(clock.instant());
        if (!properties.isEnabled()) {
            return unavailable(request, grid, ENGINE_DISABLED, "Workload autoscaling is switched off in"
                    + " this environment, so XamOps reads no usage history from the cluster.");
        }
        String key = cacheKey(target, request.cacheKey());
        UsageSeriesDto cached = usageCache.get(key);
        if (cached != null) {
            return cached;
        }
        UsageSeriesDto response = read(target, request, grid);
        usageCache.put(key, response);
        return response;
    }

    /** Every analysed workload's busiest-pod use and request, 72 h at 1 h. */
    public SparklinesDto sparklines(ClusterTarget target, PromQl.Resource resource) {
        UsageWindow window = new UsageWindow(SPARKLINE_WINDOW, 72, null, null, SPARKLINE_STEP_SECONDS);
        PrometheusRangeReader.Grid grid = window.grid(clock.instant());
        if (!properties.isEnabled()) {
            return sparklines(resource, grid, null, List.of(), new UsageSeriesDto.Unavailable(ENGINE_DISABLED,
                    "Workload autoscaling is switched off in this environment, so XamOps reads no usage"
                            + " history from the cluster."));
        }
        String key = cacheKey(target, "sparklines:" + resource.ksm() + ":" + window.cacheKey());
        SparklinesDto cached = sparklineCache.get(key);
        if (cached != null) {
            return cached;
        }
        SparklinesDto response = readSparklines(target, resource, grid);
        sparklineCache.put(key, response);
        return response;
    }

    // ------------------------------------------------------------------ usage

    private UsageSeriesDto read(ClusterTarget target, UsageRequest request, PrometheusRangeReader.Grid grid) {
        try (ClusterAccess access = opener.open(target)) {
            KubernetesClient client = access.client();
            return switch (request.scope()) {
                case CLUSTER -> cluster(client, request, grid);
                case NAMESPACE -> namespaces(client, request, grid);
                case WORKLOAD -> workload(client, request, grid);
            };
        } catch (ClusterAccessException e) {
            warn(target, SOURCE_CLUSTER, e.reasonCode(), e.getMessage());
            return unavailable(request, grid, e.reasonCode(), e.getMessage());
        } catch (PrometheusRangeReader.RangeQueryException e) {
            report(target, e);
            return unavailable(request, grid, e.code().name(), e.getMessage());
        }
    }

    private UsageSeriesDto cluster(KubernetesClient client, UsageRequest request, PrometheusRangeReader.Grid grid)
            throws PrometheusRangeReader.RangeQueryException {
        PromQl.Resource resource = request.resource();
        String usedQuery = PromQl.seriesClusterUsed(resource);
        String requestQuery = PromQl.seriesClusterRequest(resource);
        String allocatableQuery = PromQl.seriesClusterAllocatable(resource);
        String capacityQuery = PromQl.seriesClusterCapacity(resource);
        Double[] used = SeriesPoints.first(reader.read(client, usedQuery, grid), grid);
        Double[] requested = SeriesPoints.first(reader.read(client, requestQuery, grid), grid);
        Double[] allocatable = SeriesPoints.first(reader.read(client, allocatableQuery, grid), grid);
        Double[] capacity = SeriesPoints.first(reader.read(client, capacityQuery, grid), grid);
        return measured(request, grid, List.of(
                series("used", "Used", used, usedQuery, grid),
                series("request", "Requested", requested, requestQuery, grid),
                series("allocatable", "Allocatable", allocatable, allocatableQuery, grid),
                series("capacity", "Provisioned", capacity, capacityQuery, grid)),
                null, coverage(List.<Double[]>of(used), grid));
    }

    private UsageSeriesDto namespaces(KubernetesClient client, UsageRequest request,
                                      PrometheusRangeReader.Grid grid)
            throws PrometheusRangeReader.RangeQueryException {
        String usedQuery = PromQl.seriesNamespaceUsed(request.resource());
        String requestQuery = PromQl.seriesNamespaceRequest(request.resource());
        Map<String, Double[]> used = byNamespace(reader.read(client, usedQuery, grid));
        Map<String, Double[]> requested = byNamespace(reader.read(client, requestQuery, grid));

        TreeSet<String> names = new TreeSet<>(used.keySet());
        names.addAll(requested.keySet());
        List<UsageSeriesDto.NamespaceSeries> namespaces = new ArrayList<>();
        for (String namespace : names) {
            Double[] usedValues = used.getOrDefault(namespace, new Double[grid.points()]);
            Double[] requestValues = requested.getOrDefault(namespace, new Double[grid.points()]);
            namespaces.add(new UsageSeriesDto.NamespaceSeries(namespace, SeriesPoints.last(requestValues),
                    SeriesPoints.mean(usedValues), List.of(
                    series("used", "Used", usedValues, usedQuery, grid),
                    series("request", "Requested", requestValues, requestQuery, grid))));
        }
        return measured(request, grid, List.of(), namespaces, coverage(used.values(), grid));
    }

    private UsageSeriesDto workload(KubernetesClient client, UsageRequest request, PrometheusRangeReader.Grid grid)
            throws ClusterAccessException, PrometheusRangeReader.RangeQueryException {
        WorkloadRef ref = request.workload();
        ClusterWorkloadInventory.Workload workload = ClusterOpener.readInventory(inventory, client).workloads()
                .stream()
                .filter(candidate -> sameWorkload(candidate.ref(), ref))
                .findFirst()
                .orElseThrow(() -> new UnknownTargetException("name", "This cluster has no " + ref.kind()
                        + " " + ref.describe() + " that XamOps analyses."));
        if (workload.pods().isEmpty()) {
            // The engine's selector needs the pods' names, exactly as the engine does: with none
            // running there is nothing to select by, which the engine reports the same way.
            return unavailable(request, grid, ReasonCode.INSUFFICIENT_HISTORY.name(), ref.describe()
                    + " has no running pod right now, so XamOps has no pods to read its history from.");
        }
        PromQl.Selector selector = selectorFor(workload);
        PromQl.Resource resource = request.resource();
        String usedQuery = PromQl.seriesWorkloadUsed(selector, resource);
        String requestQuery = PromQl.seriesWorkloadRequest(selector, resource);
        String limitQuery = PromQl.seriesWorkloadLimit(selector, resource);
        Double[] used = SeriesPoints.first(reader.read(client, usedQuery, grid), grid);
        Double[] requested = SeriesPoints.first(reader.read(client, requestQuery, grid), grid);
        Double[] limit = SeriesPoints.first(reader.read(client, limitQuery, grid), grid);
        return measured(request, grid, List.of(
                series("used", "Used", used, usedQuery, grid),
                series("request", "Request now", requested, requestQuery, grid),
                series("limit", "Limit", limit, limitQuery, grid)),
                null, coverage(List.<Double[]>of(used), grid));
    }

    /**
     * The engine's selector for one workload: what {@code VerticalAutomationReadService} puts in its
     * {@code UsageQuery} and {@code PrometheusUsageHistoryProvider} turns into labels — the live pods'
     * names, the pod-name rules of every retained revision, the template's containers.
     */
    static PromQl.Selector selectorFor(ClusterWorkloadInventory.Workload workload) {
        return new PromQl.Selector(workload.ref().namespace(), workload.pods().names(),
                workload.pods().nameRules(), workload.containerNames());
    }

    private static boolean sameWorkload(WorkloadRef candidate, WorkloadRef wanted) {
        return candidate.namespace().equals(wanted.namespace())
                && candidate.kind().equals(wanted.kind())
                && candidate.name().equals(wanted.name());
    }

    private static Map<String, Double[]> byNamespace(List<PrometheusRangeReader.Series> answered) {
        Map<String, Double[]> byNamespace = new LinkedHashMap<>();
        for (PrometheusRangeReader.Series series : answered) {
            String namespace = series.label("namespace");
            if (namespace != null && !namespace.isBlank()) {
                byNamespace.putIfAbsent(namespace, series.values());
            }
        }
        return byNamespace;
    }

    // ------------------------------------------------------------------ sparklines

    private SparklinesDto readSparklines(ClusterTarget target, PromQl.Resource resource,
                                         PrometheusRangeReader.Grid grid) {
        try (ClusterAccess access = opener.open(target)) {
            KubernetesClient client = access.client();
            List<ClusterWorkloadInventory.Workload> workloads = new ArrayList<>(
                    ClusterOpener.readInventory(inventory, client).workloads());
            workloads.sort(Comparator.comparing((ClusterWorkloadInventory.Workload w) -> w.ref().namespace())
                    .thenComparing(w -> w.ref().kind())
                    .thenComparing(w -> w.ref().name()));
            if (workloads.isEmpty()) {
                return sparklines(resource, grid, null, List.of(), null);
            }
            List<String> namespaces = workloads.stream().map(w -> w.ref().namespace()).distinct().sorted().toList();
            String usedQuery = PromQl.seriesPodContainerUsed(namespaces, resource);
            String requestQuery = PromQl.seriesPodContainerRequest(namespaces, resource);
            List<PrometheusRangeReader.Series> used = reader.read(client, usedQuery, grid);
            List<PrometheusRangeReader.Series> requested = reader.read(client, requestQuery, grid);

            List<SparklinesDto.WorkloadSparkline> rows = new ArrayList<>();
            for (ClusterWorkloadInventory.Workload workload : workloads) {
                Double[] usedValues = new Double[grid.points()];
                Double[] requestValues = new Double[grid.points()];
                if (!workload.pods().isEmpty()) {
                    PromQl.Selector selector = selectorFor(workload);
                    Predicate<String> pods = selector.podNamePredicate();
                    usedValues = busiestPod(used, selector, pods, grid.points());
                    requestValues = busiestPod(requested, selector, pods, grid.points());
                }
                rows.add(new SparklinesDto.WorkloadSparkline(ref(workload.ref()),
                        SeriesPoints.points(usedValues, grid), SeriesPoints.points(requestValues, grid)));
            }
            return sparklines(resource, grid, new SparklinesDto.Queries(usedQuery, requestQuery), rows, null);
        } catch (ClusterAccessException e) {
            warn(target, SOURCE_CLUSTER, e.reasonCode(), e.getMessage());
            return sparklines(resource, grid, null, List.of(),
                    new UsageSeriesDto.Unavailable(e.reasonCode(), e.getMessage()));
        } catch (PrometheusRangeReader.RangeQueryException e) {
            report(target, e);
            return sparklines(resource, grid, null, List.of(),
                    new UsageSeriesDto.Unavailable(e.code().name(), e.getMessage()));
        }
    }

    /**
     * One workload's value per step from a batch of {@code (namespace, pod, container)} series: the
     * series its own selector selects (namespace, pod by the engine's pod matcher, container by its
     * container list), summed per pod, then the busiest pod. Null where no selected pod answered.
     */
    static Double[] busiestPod(List<PrometheusRangeReader.Series> answered, PromQl.Selector selector,
                               Predicate<String> pods, int points) {
        Map<String, Double[]> perPod = new LinkedHashMap<>();
        for (PrometheusRangeReader.Series series : answered) {
            String pod = series.label("pod");
            if (!selector.namespace().equals(series.label("namespace")) || !pods.test(pod)
                    || !selector.selectsContainer(series.label("container"))) {
                continue;
            }
            Double[] sum = perPod.computeIfAbsent(pod, ignored -> new Double[points]);
            Double[] values = series.values();
            for (int i = 0; i < points && i < values.length; i++) {
                if (values[i] != null) {
                    sum[i] = sum[i] == null ? values[i] : sum[i] + values[i];
                }
            }
        }
        Double[] busiest = new Double[points];
        for (Double[] pod : perPod.values()) {
            for (int i = 0; i < points; i++) {
                if (pod[i] != null && (busiest[i] == null || pod[i] > busiest[i])) {
                    busiest[i] = pod[i];
                }
            }
        }
        return busiest;
    }

    // ------------------------------------------------------------------ shapes

    private UsageSeriesDto measured(UsageRequest request, PrometheusRangeReader.Grid grid,
                                    List<UsageSeriesDto.Series> series,
                                    List<UsageSeriesDto.NamespaceSeries> namespaces,
                                    UsageSeriesDto.Coverage coverage) {
        return new UsageSeriesDto(SCHEMA_VERSION, request.scope().wire(), request.resource().ksm(),
                unit(request.resource()), ref(request.workload()), request.window().label(),
                grid.stepSeconds(), grid.start(), grid.end(), source(), series, namespaces, coverage, null);
    }

    private UsageSeriesDto unavailable(UsageRequest request, PrometheusRangeReader.Grid grid, String code,
                                       String message) {
        return new UsageSeriesDto(SCHEMA_VERSION, request.scope().wire(), request.resource().ksm(),
                unit(request.resource()), ref(request.workload()), request.window().label(),
                grid.stepSeconds(), grid.start(), grid.end(), source(), List.of(),
                request.scope() == Scope.NAMESPACE ? List.of() : null, null,
                new UsageSeriesDto.Unavailable(code, message));
    }

    private SparklinesDto sparklines(PromQl.Resource resource, PrometheusRangeReader.Grid grid,
                                     SparklinesDto.Queries queries, List<SparklinesDto.WorkloadSparkline> rows,
                                     UsageSeriesDto.Unavailable unavailable) {
        return new SparklinesDto(SCHEMA_VERSION, resource.ksm(), unit(resource), SPARKLINE_WINDOW,
                grid.stepSeconds(), grid.start(), grid.end(), source(), queries, rows, unavailable);
    }

    private static UsageSeriesDto.Series series(String kind, String label, Double[] values, String query,
                                                PrometheusRangeReader.Grid grid) {
        return new UsageSeriesDto.Series(kind, kind, label, SeriesPoints.points(values, grid), query);
    }

    /** A step counts as covered when any of the {@code used} series has a sample there. */
    static UsageSeriesDto.Coverage coverage(Collection<Double[]> used, PrometheusRangeReader.Grid grid) {
        int covered = 0;
        Long first = null;
        for (int i = 0; i < grid.points(); i++) {
            for (Double[] values : used) {
                if (i < values.length && values[i] != null) {
                    covered++;
                    if (first == null) {
                        first = grid.at(i);
                    }
                    break;
                }
            }
        }
        return new UsageSeriesDto.Coverage(first == null ? null : Instant.ofEpochSecond(first),
                SeriesPoints.round4(covered / (double) grid.points()));
    }

    private UsageSeriesDto.Source source() {
        return UsageSeriesDto.Source.prometheusProxy(reader.describeTarget());
    }

    private static UsageSeriesDto.Ref ref(WorkloadRef ref) {
        return ref == null ? null : new UsageSeriesDto.Ref(ref.namespace(), ref.kind(), ref.name());
    }

    private static String unit(PromQl.Resource resource) {
        return resource == PromQl.Resource.CPU ? "cores" : "bytes";
    }

    private static String cacheKey(ClusterTarget target, String args) {
        return target.clientId() + ":" + target.clusterConfigId() + ":" + target.region() + ":" + args;
    }

    // ------------------------------------------------------------------ logging

    private void report(ClusterTarget target, PrometheusRangeReader.RangeQueryException e) {
        if (e.code() == PrometheusRangeReader.RangeQueryException.Code.METRICS_UNREADABLE) {
            logger.error("Usage history for {} is unreadable: {} The body starts: {}", target.describe(),
                    e.getMessage(), e.bodyPrefix());
            return;
        }
        warn(target, SOURCE_PROMETHEUS, e.code().name(), e.getMessage());
    }

    private void warn(ClusterTarget target, String source, String code, String message) {
        if (warnings.shouldWarn(target.clusterConfigId(), source)) {
            logger.warn("Usage history unavailable for {} ({}): {}: {}", target.describe(), source, code, message);
        } else {
            logger.debug("Usage history unavailable for {} ({}): {}: {}", target.describe(), source, code,
                    message);
        }
    }
}
