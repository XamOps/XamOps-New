package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpChange;
import com.xammer.cloud.xampulse.dto.XpChange.Effect;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.FilterDslParser;
import com.xammer.cloud.xampulse.service.query.MetricSpec;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Change Intelligence: what changed on a host (or in one container) and what the measurements did right after.
 *
 * <p>Changes are read from data XamPulse already has, at no AWS cost: a container's id or image changing under the
 * same name (a deploy or a recreate), container restarts, kernel counters starting again from zero (a reboot), data
 * stopping and resuming, package manager log lines, and the platform's own DEPLOY / CONFIG / EC2_STATE / AGENT
 * events. Alerts and anomalies are symptoms, not changes, and are left out.
 *
 * <p>Effects compare the median of each signal in the 30 minutes before with the 30 minutes after (the change's own
 * minute left out), scaled by how much the signal normally moves (MAD of the "before" minutes). They describe what
 * followed, never what caused it; two changes within 10 minutes on one host are flagged as indistinguishable.
 */
@Service("xpChangeIntelligenceService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ChangeIntelligenceService {

    static final Duration EFFECT_WINDOW = Duration.ofMinutes(30);
    static final Duration OVERLAP = Duration.ofMinutes(10);
    static final Duration HISTORY = Duration.ofHours(24);
    static final int MIN_POINTS = 5;
    static final long GAP_SEC = 300;
    static final int MAX_CHANGES = 200;
    static final int MAX_WITH_EFFECTS = 40;

    /** A change before its effects are known. */
    record Raw(Instant ts, String instanceId, String kind, String title, String detail, String source, String subject) {}

    /** One signal's per-minute values (epoch second -> value) and how it is judged. */
    record Signal(String key, String label, String unit, double floor, TreeMap<Long, Double> points) {}

    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ChangeIntelligenceService(QueryScopeFactory scopes, ClickHouseExecutor executor) {
        this.scopes = scopes;
        this.executor = executor;
    }

    /**
     * Changes in the window, newest first. With an instance, each change also carries its effects; fleet-wide lists
     * do not (they link to the host, where effects are worked out).
     */
    public List<XpChange> changes(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to) {
        scopes.build(user, List.of(accountId), from, to); // authorises the account like every other read
        return changesInScope(accountId, instanceId, null, from, to, Instant.now());
    }

    /** As {@link #changes} for an account the caller already authorised; {@code container} narrows to one container. */
    public List<XpChange> changesInScope(Long accountId, String instanceId, String container, Instant from, Instant to, Instant now) {
        List<String> instances = instanceId == null ? List.of() : List.of(instanceId);
        QueryScope window = new QueryScope(List.of(accountId), from, to, List.of(), instances, List.of(), List.of());
        List<Raw> detected = new ArrayList<>(detect(accountId, window, instances, container));
        detected.sort(Comparator.comparing(Raw::ts).reversed());
        final List<Raw> raw = detected.size() > MAX_CHANGES ? List.copyOf(detected.subList(0, MAX_CHANGES)) : List.copyOf(detected);
        if (instanceId == null) {
            return raw.stream().map(r -> new XpChange(r.ts(), r.instanceId(), r.kind(), r.title(), r.detail(), r.source(), r.subject(),
                    null, overlapping(r, raw), List.of())).toList();
        }
        Map<String, Signal> hostSignals = raw.isEmpty() ? Map.of() : loadSignals(accountId, instanceId, raw, null);
        Map<String, Map<String, Signal>> containerSignals = new HashMap<>();
        List<XpChange> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Raw r = raw.get(i);
            if (i >= MAX_WITH_EFFECTS) {
                out.add(new XpChange(r.ts(), r.instanceId(), r.kind(), r.title(), r.detail(), r.source(), r.subject(), null, overlapping(r, raw), List.of()));
                continue;
            }
            Map<String, Signal> signals = new LinkedHashMap<>(hostSignals);
            if (r.subject() != null) {
                signals.putAll(containerSignals.computeIfAbsent(r.subject(), c -> loadSignals(accountId, instanceId, raw, c)));
            }
            out.add(judge(r, signals.values(), now, overlapping(r, raw)));
        }
        return out;
    }

    // ------------------------------------------------------------------ detection

    List<Raw> detect(Long accountId, QueryScope window, List<String> instances, String container) {
        List<Raw> out = new ArrayList<>();
        List<MetricSpec.LabelFilter> byName = container == null ? List.of() : List.of(new MetricSpec.LabelFilter("container.name", "=", container));
        QueryScope withHistory = new QueryScope(window.accountIds(), window.from().minus(HISTORY), window.to(), List.of(), instances, List.of(), List.of());
        out.addAll(containerSpans(executor.query(builder.seriesSpans(withHistory, "container.cpu.utilization", byName)),
                window.from(), window.to(), withHistory.from()));
        for (Map<String, Object> r : executor.query(builder.counterSteps(window, "container.restarts", byName, Long.MAX_VALUE / 4))) {
            double inc = Rows.d(r, "inc");
            if (inc > 0) {
                Map<String, String> l = ThrottleRadarService.labels(Rows.s(r, "labels_key"));
                String name = l.getOrDefault("container.name", "container");
                long n = Math.round(inc);
                out.add(new Raw(Instant.ofEpochSecond(Rows.l(r, "t")), Rows.s(r, "instance_id"), "RESTART",
                        "Container " + name + (n > 1 ? " restarted " + n + " times" : " restarted"),
                        "Docker restarted the container (restart count +" + n + ").", "AGENT", name));
            }
        }
        if (container != null) {
            return out;
        }
        out.addAll(hostContinuity(executor.query(builder.counterSteps(window, "system.cpu.time",
                List.of(new MetricSpec.LabelFilter("state", "=", "idle")), GAP_SEC))));
        out.addAll(packages(executor.query(builder.search(window, FilterDslParser.parse("attr.xp.change:package"), true, 500, null))));
        for (Map<String, Object> r : executor.query(builder.events(window, List.of("DEPLOY", "CONFIG", "EC2_STATE", "AGENT"), 500))) {
            String resource = Rows.s(r, "resource");
            if (!instances.isEmpty() && !resource.isEmpty() && !instances.contains(resource)) {
                continue;
            }
            out.add(new Raw(Instant.ofEpochMilli(Rows.l(r, "ts_ms")), resource.isEmpty() ? (instances.isEmpty() ? "" : instances.get(0)) : resource,
                    Rows.s(r, "kind"), Rows.s(r, "title"), Rows.s(r, "detail"), "PLATFORM", null));
        }
        return out;
    }

    /** Container deploys, recreates, starts and stops from when each (id, image) of a container name was seen. */
    static List<Raw> containerSpans(List<Map<String, Object>> rows, Instant from, Instant to, Instant historyStart) {
        record Span(String id, String image, long first, long last) {}
        Map<String, List<Span>> byContainer = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, String> l = ThrottleRadarService.labels(Rows.s(r, "labels_key"));
            String name = l.get("container.name");
            String id = l.get("container.id");
            if (name == null || id == null) {
                continue;
            }
            byContainer.computeIfAbsent(Rows.s(r, "instance_id") + "\u0000" + name, k -> new ArrayList<>())
                    .add(new Span(id, l.getOrDefault("container.image.name", ""), Rows.l(r, "first_ts"), Rows.l(r, "last_ts")));
        }
        List<Raw> out = new ArrayList<>();
        long fromS = from.getEpochSecond();
        long toS = to.getEpochSecond();
        for (Map.Entry<String, List<Span>> e : byContainer.entrySet()) {
            String instance = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
            String name = e.getKey().substring(e.getKey().indexOf('\u0000') + 1);
            List<Span> spans = e.getValue();
            spans.sort(Comparator.comparingLong(Span::first));
            for (int i = 0; i < spans.size(); i++) {
                Span s = spans.get(i);
                if (s.first() >= fromS && s.first() < toS) {
                    if (i > 0) {
                        Span prev = spans.get(i - 1);
                        boolean newImage = !prev.image().equals(s.image());
                        out.add(new Raw(Instant.ofEpochSecond(s.first()), instance, newImage ? "DEPLOY" : "RECREATE",
                                newImage ? "Container " + name + " deployed " + s.image() : "Container " + name + " recreated",
                                newImage ? "Was " + prev.image() + " (container " + shortId(prev.id()) + " replaced by " + shortId(s.id()) + ")."
                                        : "Same image " + s.image() + "; container " + shortId(prev.id()) + " replaced by " + shortId(s.id()) + ".",
                                "AGENT", name));
                    } else if (s.first() > historyStart.getEpochSecond() + GAP_SEC) {
                        // Seen for the first time after the history began: a new container, not the edge of the data.
                        out.add(new Raw(Instant.ofEpochSecond(s.first()), instance, "CONTAINER_START", "Container " + name + " started",
                                "Image " + s.image() + ", container " + shortId(s.id()) + ".", "AGENT", name));
                    }
                }
                boolean replaced = i + 1 < spans.size();
                if (!replaced && s.last() >= fromS && s.last() < toS - GAP_SEC) {
                    out.add(new Raw(Instant.ofEpochSecond(s.last() + 60), instance, "CONTAINER_STOP", "Container " + name + " stopped",
                            "No metrics from container " + shortId(s.id()) + " since then.", "AGENT", name));
                }
            }
        }
        return out;
    }

    /** Reboots (kernel CPU time started again from zero) and data gaps, from the idle CPU time counter. */
    static List<Raw> hostContinuity(List<Map<String, Object>> rows) {
        Map<String, Raw> byMinute = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            boolean restarted = Rows.l(r, "restarted") != 0;
            long gap = Rows.l(r, "gap_secs");
            String instance = Rows.s(r, "instance_id");
            long t = Rows.l(r, "t");
            String gapText = gap > GAP_SEC ? (gap / 60) + " minutes without data" : null;
            Raw raw;
            if (restarted) {
                raw = new Raw(Instant.ofEpochSecond(t), instance, "REBOOT", "Host restarted",
                        "The kernel's CPU counters started again from zero" + (gapText == null ? "." : ", after " + gapText + "."), "AGENT", null);
            } else if (gapText != null) {
                raw = new Raw(Instant.ofEpochSecond(t), instance, "DATA_GAP", "Data resumed after " + gapText,
                        "The agent sent nothing for " + gapText + " (agent stopped, network or ingest problem); the host itself did not restart.", "AGENT", null);
            } else {
                continue;
            }
            byMinute.putIfAbsent(instance + "|" + t, raw);
        }
        return new ArrayList<>(byMinute.values());
    }

    private static final Pattern DPKG = Pattern.compile("\\b(install|upgrade|remove|purge)\\s+([^\\s:]+)(?::\\S+)?\\s+(\\S+)\\s+(\\S+)");
    private static final Pattern DNF = Pattern.compile("SUBDEBUG\\s+(Installed|Upgrade|Downgrade|Erase|Reinstall|Obsolete):\\s+(\\S+)");
    /** yum.log on Amazon Linux 2: "Nov 12 10:00:01 Updated: openssl-libs-1:1.0.2k-24.amzn2.0.7.x86_64". */
    private static final Pattern YUM = Pattern.compile("\\b(Installed|Updated|Erased):\\s+(\\S+)");

    /** Package manager log lines (dpkg, dnf/yum), grouped per host and minute into one change. */
    static List<Raw> packages(List<Map<String, Object>> rows) {
        Map<String, List<String>> byMinute = new TreeMap<>();
        Map<String, String> instanceOf = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String body = Rows.s(r, "body");
            String what = null;
            Matcher d = DPKG.matcher(body);
            Matcher n = DNF.matcher(body);
            Matcher y = YUM.matcher(body);
            if (d.find()) {
                String action = d.group(1);
                what = switch (action) {
                    case "upgrade" -> d.group(2) + " " + d.group(3) + " → " + d.group(4);
                    case "install" -> d.group(2) + " " + d.group(4) + " installed";
                    default -> d.group(2) + " removed";
                };
            } else if (n.find()) {
                what = n.group(2) + " (" + n.group(1).toLowerCase(Locale.ROOT) + ")";
            } else if (y.find()) {
                what = y.group(2) + " (" + y.group(1).toLowerCase(Locale.ROOT) + ")";
            }
            if (what == null) {
                continue;
            }
            long minute = Math.floorDiv(Rows.l(r, "ts_ms") / 1000, 60L) * 60L;
            String key = Rows.s(r, "instance_id") + "|" + minute;
            instanceOf.put(key, Rows.s(r, "instance_id"));
            byMinute.computeIfAbsent(key, k -> new ArrayList<>()).add(what);
        }
        List<Raw> out = new ArrayList<>();
        byMinute.forEach((key, items) -> {
            long minute = Long.parseLong(key.substring(key.lastIndexOf('|') + 1));
            List<String> unique = items.stream().distinct().toList();
            String title = unique.size() == 1 ? "Package change: " + unique.get(0) : unique.size() + " package changes";
            String detail = String.join("; ", unique.subList(0, Math.min(10, unique.size()))) + (unique.size() > 10 ? "; and " + (unique.size() - 10) + " more" : "");
            out.add(new Raw(Instant.ofEpochSecond(minute), instanceOf.get(key), "PACKAGE", title, detail, "LOGS", null));
        });
        return out;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.length() > 12 ? id.substring(0, 12) : id;
    }

    static boolean overlapping(Raw r, List<Raw> all) {
        for (Raw o : all) {
            if (o != r && o.instanceId().equals(r.instanceId()) && Math.abs(Duration.between(o.ts(), r.ts()).toSeconds()) <= OVERLAP.toSeconds()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ effects

    private Map<String, Signal> loadSignals(Long accountId, String instanceId, List<Raw> raw, String container) {
        Instant first = raw.stream().map(Raw::ts).min(Instant::compareTo).orElseThrow().minus(EFFECT_WINDOW).minusSeconds(60);
        Instant last = raw.stream().map(Raw::ts).max(Instant::compareTo).orElseThrow().plus(EFFECT_WINDOW).plusSeconds(120);
        QueryScope s = new QueryScope(List.of(accountId), first, last, List.of(), List.of(instanceId), List.of(), List.of());
        TenantScopedQueryBuilder.MetricPlan perMinute = new TenantScopedQueryBuilder.MetricPlan("xp_metrics_1m", 60, 60);
        Map<String, Signal> out = new LinkedHashMap<>();
        if (container == null) {
            out.put("cpu", new Signal("cpu", "CPU", "%", 5, series(s, perMinute,
                    new MetricSpec("system.cpu.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "idle"))), v -> (1 - v) * 100)));
            out.put("memory", new Signal("memory", "Memory", "%", 3, series(s, perMinute,
                    new MetricSpec("system.memory.utilization", "avg", List.of(), List.of(new MetricSpec.LabelFilter("state", "=", "used"))), v -> v * 100)));
            TreeMap<Long, Double> errors = new TreeMap<>();
            for (Map<String, Object> r : executor.query(builder.errorsPerMinute(s, instanceId))) {
                errors.put(Rows.l(r, "t"), Rows.d(r, "v"));
            }
            out.put("errors", new Signal("errors", "Errors per minute", "/min", 1, errors));
        } else {
            List<MetricSpec.LabelFilter> byName = List.of(new MetricSpec.LabelFilter("container.name", "=", container));
            out.put("container_cpu:" + container, new Signal("container_cpu", "CPU of " + container, "% of 1 core", 5,
                    series(s, perMinute, new MetricSpec("container.cpu.utilization", "avg", List.of(), byName), v -> v)));
            out.put("container_memory:" + container, new Signal("container_memory", "Memory of " + container, "bytes", 50 * 1024 * 1024,
                    series(s, perMinute, new MetricSpec("container.memory.usage.total", "avg", List.of(), byName), v -> v)));
        }
        return out;
    }

    private TreeMap<Long, Double> series(QueryScope s, TenantScopedQueryBuilder.MetricPlan plan, MetricSpec spec, java.util.function.DoubleUnaryOperator f) {
        TreeMap<Long, Double> out = new TreeMap<>();
        for (Map<String, Object> r : executor.query(builder.metricSeries(s, spec, plan))) {
            Double v = Rows.dOrNull(r, "v");
            if (v != null) {
                out.put(Rows.l(r, "t"), f.applyAsDouble(v));
            }
        }
        return out;
    }

    /** Pure: the verdict and effects of one change from per-minute signals. */
    static XpChange judge(Raw r, Iterable<Signal> signals, Instant now, boolean overlapping) {
        long t = Math.floorDiv(r.ts().getEpochSecond(), 60L) * 60L;
        if (now.isBefore(r.ts().plus(Duration.ofMinutes(MIN_POINTS)))) {
            return new XpChange(r.ts(), r.instanceId(), r.kind(), r.title(), r.detail(), r.source(), r.subject(), "TOO_RECENT", overlapping, List.of());
        }
        List<Effect> effects = new ArrayList<>();
        boolean enough = false;
        for (Signal s : signals) {
            double[] before = window(s, t - EFFECT_WINDOW.toSeconds(), t, "errors".equals(s.key()));
            double[] after = window(s, t + 60, t + 60 + EFFECT_WINDOW.toSeconds(), "errors".equals(s.key()));
            if (before.length < MIN_POINTS || after.length < MIN_POINTS) {
                continue;
            }
            enough = true;
            Effect e = effect(s, before, after);
            if (e != null) {
                effects.add(e);
            }
        }
        String verdict = !enough ? "NOT_ENOUGH_DATA" : effects.isEmpty() ? "NO_CLEAR_EFFECT" : "FOLLOWED_BY_CHANGE";
        effects.sort(Comparator.comparing((Effect e) -> !"STRONG".equals(e.strength())).thenComparing(e -> -Math.abs(e.relative())));
        return new XpChange(r.ts(), r.instanceId(), r.kind(), r.title(), r.detail(), r.source(), r.subject(), verdict, overlapping, List.copyOf(effects));
    }

    /**
     * Values in [from, to). Error counts are zero when a minute has no row but the signal has data around it (log
     * volume rows exist only for minutes with logs); other signals use only the minutes they reported.
     */
    static double[] window(Signal s, long from, long to, boolean zeroFill) {
        if (zeroFill) {
            if (s.points().isEmpty() || s.points().firstKey() > to || s.points().lastKey() < from) {
                return new double[0];
            }
            List<Double> v = new ArrayList<>();
            for (long m = from; m < to; m += 60) {
                v.add(s.points().getOrDefault(m, 0.0));
            }
            return v.stream().mapToDouble(Double::doubleValue).toArray();
        }
        return s.points().subMap(from, true, to, false).values().stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * STRONG: the after-median moved at least 4 typical deviations, by at least the signal's floor and by 25%.
     * MODERATE: 2.5 deviations, half the floor and 15%. Anything weaker is not reported.
     */
    static Effect effect(Signal s, double[] before, double[] after) {
        double mb = median(before);
        double ma = median(after);
        double delta = ma - mb;
        double spread = Math.max(1.4826 * mad(before, mb), s.floor() / 2);
        double z = delta / spread;
        double relative = delta / Math.max(Math.abs(mb), s.floor());
        String strength = Math.abs(z) >= 4 && Math.abs(delta) >= s.floor() && Math.abs(relative) >= 0.25 ? "STRONG"
                : Math.abs(z) >= 2.5 && Math.abs(delta) >= s.floor() / 2 && Math.abs(relative) >= 0.15 ? "MODERATE" : null;
        return strength == null ? null : new Effect(s.key(), s.label(), s.unit(), mb, ma, relative, strength);
    }

    static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : (c[n / 2 - 1] + c[n / 2]) / 2;
    }

    static double mad(double[] v, double med) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = Math.abs(v[i] - med);
        }
        return median(d);
    }
}
