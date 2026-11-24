package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpChange;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.dto.XpHostSummary;
import com.xammer.cloud.xampulse.dto.XpInvestigation;
import com.xammer.cloud.xampulse.dto.XpInvestigation.Hypothesis;
import com.xammer.cloud.xampulse.dto.XpLogPatternsRequest;
import com.xammer.cloud.xampulse.dto.XpPattern;
import com.xammer.cloud.xampulse.dto.XpPeers;
import com.xammer.cloud.xampulse.dto.XpResourceFilter;
import com.xammer.cloud.xampulse.dto.XpScope;
import com.xammer.cloud.xampulse.dto.XpSeverity;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.HostQueryService;
import com.xammer.cloud.xampulse.service.query.LogPatternService;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Investigate: checks every usual explanation for a host's trouble in a range against what was measured (Throttle
 * Radar, Change Intelligence, the host's own series, error logs and new error patterns, its peers) and says which
 * are supported, which are ruled out and which cannot be decided here. Every statement is a measurement; nothing is
 * called a cause. One failed source makes its hypotheses NOT_MEASURABLE, never hides the others.
 */
@Service("xpInvestigationService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
    static final Set<String> AWS_SIGNALS = Set.of("ena", "ebs", "spot");
    static final Set<String> KERNEL_SIGNALS = Set.of("psi", "conntrack_table");
    /** OS limits that fail requests while CPU looks fine: files, inodes, local ports, accept queues. */
    static final Set<String> OS_LIMIT_SIGNALS = Set.of("file_handles", "inodes", "local_ports", "listen_queue");
    static final Set<String> OS_LIMIT_CODES = Set.of("file_handles_system", "file_handles_process", "inodes", "local_ports", "listen_queue");

    private final XamPulseAccessGuard guard;
    private final HostQueryService hosts;
    private final ThrottleRadarService radar;
    private final ChangeIntelligenceService changes;
    private final PeerOutlierService peers;
    private final LogPatternService patterns;
    private final ClickHouseExecutor executor;
    private final Ec2FactsService ec2;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public InvestigationService(XamPulseAccessGuard guard, HostQueryService hosts, ThrottleRadarService radar,
                                ChangeIntelligenceService changes, PeerOutlierService peers, LogPatternService patterns,
                                ClickHouseExecutor executor, Ec2FactsService ec2) {
        this.guard = guard;
        this.hosts = hosts;
        this.radar = radar;
        this.changes = changes;
        this.peers = peers;
        this.patterns = patterns;
        this.executor = executor;
        this.ec2 = ec2;
    }

    /** What was gathered. Any field is null when its source could not be read. */
    record Evidence(XpHostLimits limits, List<XpChange> changes, XpHostSummary summary, ErrorRates errors,
                    List<XpPattern> newErrorPatterns, XpPeers peers) {}

    /** Error and fatal lines per minute in the range and in the same length of time just before it. */
    record ErrorRates(double now, double before, long rowsNow, long rowsBefore) {}

    public XpInvestigation investigate(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to) {
        CloudAccount account = guard.require(user, accountId);
        XpHostSummary summary = hosts.summary(user, accountId, instanceId, from, to); // 404 when no agent reports
        Optional<XpEc2Facts> facts = quiet("ec2", () -> Optional.ofNullable(ec2.facts(account, instanceId, summary.host().region())));
        Evidence e = new Evidence(
                quiet("radar", () -> radar.limits(user, accountId, instanceId, from, to, facts == null ? Optional.empty() : facts)),
                quiet("changes", () -> changes.changes(user, accountId, instanceId, from, to)),
                summary,
                quiet("errors", () -> errorRates(accountId, instanceId, from, to)),
                quiet("patterns", () -> newErrorPatterns(user, accountId, instanceId, from, to)),
                quiet("peers", () -> peers.peers(user, accountId, instanceId, from, to)));
        List<Hypothesis> hs = evaluate(e, from, to, instanceId);
        return new XpInvestigation(instanceId, from, to, summarise(hs), hs, e.peers());
    }

    private static <T> T quiet(String what, Supplier<T> s) {
        try {
            return s.get();
        } catch (RuntimeException ex) {
            log.warn("XamPulse investigate: {} unavailable ({})", what, ex.getClass().getSimpleName());
            return null;
        }
    }

    private ErrorRates errorRates(Long accountId, String instanceId, Instant from, Instant to) {
        Duration len = Duration.between(from, to);
        double minutes = Math.max(1, len.toMinutes());
        long[] now = errorCounts(new QueryScope(List.of(accountId), from, to, List.of(), List.of(instanceId), List.of(), List.of()));
        long[] before = errorCounts(new QueryScope(List.of(accountId), from.minus(len), from, List.of(), List.of(instanceId), List.of(), List.of()));
        return new ErrorRates(now[0] / minutes, before[0] / minutes, now[1], before[1]);
    }

    private long[] errorCounts(QueryScope scope) {
        long errors = 0;
        long total = 0;
        for (Map<String, Object> r : executor.query(builder.errorVolume(scope))) {
            errors += Rows.l(r, "errors");
            total += Rows.l(r, "total");
        }
        return new long[] {errors, total};
    }

    private List<XpPattern> newErrorPatterns(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to) {
        XpScope scope = new XpScope(List.of(accountId), null, from, to, new XpResourceFilter(List.of(instanceId), null, null));
        return patterns.patterns(user, new XpLogPatternsRequest(scope, "severity:>=error", true, 30)).patterns().stream()
                .filter(p -> p.isNew() && p.severity() != null && p.severity().compareTo(XpSeverity.ERROR) >= 0).limit(5).toList();
    }

    // ------------------------------------------------------------------ evaluation (pure)

    static List<Hypothesis> evaluate(Evidence e, Instant from, Instant to, String instanceId) {
        List<Hypothesis> out = new ArrayList<>();
        out.add(fromRadar(e.limits(), "aws_limit", "AWS capped the instance", AWS_SIGNALS, f -> "AWS".equals(f.source()), "aws", 100, 80));
        out.add(fromRadar(e.limits(), "kernel_pressure", "Tasks stalled waiting for CPU, memory or I/O", KERNEL_SIGNALS,
                f -> f.code().startsWith("psi_") || "conntrack_table".equals(f.code()), "cpu", 85, 65));
        out.add(fromRadar(e.limits(), "os_limit", "The OS ran out of open files, inodes, ports or connection slots", OS_LIMIT_SIGNALS,
                f -> OS_LIMIT_CODES.contains(f.code()), "network", 90, 75));
        out.add(fromRadar(e.limits(), "network_loss", "Packets were lost or delayed on the network", Set.of("tcp_retransmits"),
                f -> "tcp_retransmits".equals(f.code()), "network", 70, 55));
        out.add(saturation(e.summary()));
        out.add(fromRadar(e.limits(), "noisy_neighbor", "The hypervisor took CPU time (steal)", Set.of("steal"),
                f -> "cpu_steal".equals(f.code()), "cpu", 60, 60));
        out.add(containers(e.limits(), e.changes()));
        out.add(change(e.changes()));
        out.add(errors(e.errors(), e.newErrorPatterns(), instanceId));
        out.add(hostSpecific(e.peers()));
        out.add(dataGap(e.changes()));
        out.sort(Comparator.comparingInt((Hypothesis h) -> statusRank(h.status())).thenComparing(Comparator.comparingInt(Hypothesis::weight).reversed()));
        return out;
    }

    private static int statusRank(String s) {
        return switch (s) {
            case "SUPPORTED" -> 0;
            case "NOT_MEASURABLE" -> 2;
            default -> 1;
        };
    }

    private static Hypothesis supported(String code, String title, int weight, List<String> evidence, String section, String logs) {
        String conf = weight >= 75 ? "HIGH" : weight >= 55 ? "MEDIUM" : "LOW";
        return new Hypothesis(code, title, "SUPPORTED", conf, weight, evidence, section, logs);
    }

    private static Hypothesis ruledOut(String code, String title, List<String> evidence, String section) {
        return new Hypothesis(code, title, "RULED_OUT", null, 0, evidence, section, null);
    }

    private static Hypothesis unknown(String code, String title, String why, String section) {
        return new Hypothesis(code, title, "NOT_MEASURABLE", null, 0, List.of(why), section, null);
    }

    static Hypothesis fromRadar(XpHostLimits l, String code, String title, Set<String> signals,
                                java.util.function.Predicate<XpHostLimits.Finding> mine, String section, int critWeight, int warnWeight) {
        if (l == null) {
            return unknown(code, title, "Throttle Radar could not be read.", section);
        }
        List<XpHostLimits.Finding> found = l.findings().stream().filter(mine).toList();
        if (!found.isEmpty()) {
            boolean crit = found.stream().anyMatch(f -> "CRITICAL".equals(f.severity()));
            List<String> ev = found.stream().map(f -> f.title() + evidenceText(f.evidence())).limit(4).toList();
            return supported(code, title, crit ? critWeight : warnWeight, ev, section, null);
        }
        List<XpHostLimits.Signal> sig = l.signals().stream().filter(s -> signals.contains(s.code())).toList();
        List<XpHostLimits.Signal> measured = sig.stream().filter(s -> !"NOT_MEASURED".equals(s.state())).toList();
        if (measured.isEmpty()) {
            String why = sig.stream().map(s -> s.label() + ": " + (s.note() == null ? "not measured" : s.note())).reduce((a, b) -> a + " " + b)
                    .orElse("Not measured on this host.");
            return unknown(code, title, why, section);
        }
        List<String> ev = new ArrayList<>(measured.stream().map(s -> s.label() + ": no limit hit").toList());
        sig.stream().filter(s -> "NOT_MEASURED".equals(s.state())).forEach(s -> ev.add(s.label() + ": not measured" + (s.note() == null ? "" : " (" + s.note() + ")")));
        return ruledOut(code, title, ev, section);
    }

    private static String evidenceText(Map<String, Double> ev) {
        if (ev == null || ev.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(" (");
        ev.entrySet().stream().limit(2).forEach(x -> b.append(b.length() > 2 ? ", " : "").append(x.getKey().replace('_', ' ')).append(' ').append(num(x.getValue())));
        return b.append(')').toString();
    }

    static String num(double v) {
        if (Math.abs(v) >= 100) {
            return String.format(Locale.ROOT, "%,.0f", v);
        }
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    /** CPU, memory or a filesystem at the top of its range for a real share of it (series are percentages). */
    static Hypothesis saturation(XpHostSummary s) {
        String title = "CPU, memory or disk space ran out";
        if (s == null || s.series() == null) {
            return unknown("saturation", title, "The host's series could not be read.", "cpu");
        }
        List<String> ev = new ArrayList<>();
        List<String> fine = new ArrayList<>();
        boolean any = false;
        String section = "cpu";
        Stat cpu = stat(s.series().get("system.cpu.utilization"), 90);
        if (cpu != null) {
            any = true;
            if (cpu.share() >= 0.2) {
                ev.add(String.format(Locale.ROOT, "CPU at or above 90%% for %.0f%% of the range (peak %.0f%%)", cpu.share() * 100, cpu.max()));
            } else {
                fine.add(String.format(Locale.ROOT, "CPU peak %.0f%%", cpu.max()));
            }
        }
        Stat mem = stat(s.series().get("system.memory.utilization"), 90);
        if (mem != null) {
            any = true;
            if (mem.max() >= 95 || mem.share() >= 0.2) {
                ev.add(String.format(Locale.ROOT, "Memory used reached %.0f%%", mem.max()));
                if (ev.size() == 1) {
                    section = "memory";
                }
            } else {
                fine.add(String.format(Locale.ROOT, "memory peak %.0f%%", mem.max()));
            }
        }
        String fullest = null;
        double fullestPct = -1;
        for (Map.Entry<String, List<double[]>> en : s.series().entrySet()) {
            if (en.getKey().startsWith("system.filesystem.utilization{mountpoint=")) {
                Stat fs = stat(en.getValue(), 90);
                if (fs != null && fs.last() > fullestPct) {
                    fullestPct = fs.last();
                    fullest = en.getKey().substring("system.filesystem.utilization{mountpoint=".length(), en.getKey().length() - 1);
                }
            }
        }
        if (fullest != null) {
            any = true;
            if (fullestPct >= 90) {
                ev.add(String.format(Locale.ROOT, "%s is %.0f%% full", fullest, fullestPct));
                if (ev.size() == 1) {
                    section = "storage";
                }
            } else {
                fine.add(String.format(Locale.ROOT, "fullest filesystem %s at %.0f%%", fullest, fullestPct));
            }
        }
        if (!any) {
            return unknown("saturation", title, "No CPU, memory or filesystem data in this range.", "cpu");
        }
        return ev.isEmpty() ? ruledOut("saturation", title, List.of(String.join(", ", fine)), "cpu") : supported("saturation", title, 75, ev, section, null);
    }

    record Stat(double max, double last, double share) {}

    static Stat stat(List<double[]> pts, double high) {
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        double max = Double.NEGATIVE_INFINITY;
        int over = 0;
        for (double[] p : pts) {
            max = Math.max(max, p[1]);
            if (p[1] >= high) {
                over++;
            }
        }
        return new Stat(max, pts.get(pts.size() - 1)[1], (double) over / pts.size());
    }

    static Hypothesis containers(XpHostLimits l, List<XpChange> changes) {
        String title = "Containers hit their limits or restarted";
        List<String> ev = new ArrayList<>();
        if (l != null) {
            l.findings().stream().filter(f -> "container_throttled".equals(f.code())).limit(3).forEach(f -> ev.add(f.title() + evidenceText(f.evidence())));
        }
        if (changes != null) {
            changes.stream().filter(c -> "RESTART".equals(c.kind())).limit(3).forEach(c -> ev.add(c.title() + " at " + HM.format(c.ts())));
        }
        if (!ev.isEmpty()) {
            return supported("container_limit", title, 70, ev, "changes", null);
        }
        if (l == null && changes == null) {
            return unknown("container_limit", title, "Container data could not be read.", "changes");
        }
        boolean measured = l != null && l.signals().stream().anyMatch(s -> "container_cpu".equals(s.code()) && !"NOT_MEASURED".equals(s.state()));
        return measured ? ruledOut("container_limit", title, List.of("No CPU throttling or restarts of containers"), "changes")
                : unknown("container_limit", title, "No containers are reported from this host.", "changes");
    }

    static Hypothesis change(List<XpChange> changes) {
        String title = "Something changed just before";
        if (changes == null) {
            return unknown("change", title, "Changes could not be read.", "changes");
        }
        List<XpChange> real = changes.stream().filter(c -> !"DATA_GAP".equals(c.kind()) && !"AGENT".equals(c.kind())).toList();
        if (real.isEmpty()) {
            return ruledOut("change", title, List.of("No deploys, restarts, reboots, package updates or config changes recorded in this range"), "changes");
        }
        List<XpChange> followed = real.stream().filter(c -> "FOLLOWED_BY_CHANGE".equals(c.verdict()) && c.effects() != null && !c.effects().isEmpty()).toList();
        if (followed.isEmpty()) {
            List<String> ev = new ArrayList<>();
            ev.add(real.size() + (real.size() == 1 ? " change" : " changes") + " in this range, none followed by a clear shift in its measurements");
            real.stream().limit(3).forEach(c -> ev.add(c.title() + " at " + HM.format(c.ts())));
            return ruledOut("change", title, ev, "changes");
        }
        boolean strong = followed.stream().anyMatch(c -> !c.overlapping() && c.effects().stream().anyMatch(x -> "STRONG".equals(x.strength())));
        List<String> ev = new ArrayList<>();
        for (XpChange c : followed.stream().limit(3).toList()) {
            XpChange.Effect x = c.effects().get(0);
            ev.add(c.title() + " at " + HM.format(c.ts()) + ": followed by " + x.label() + " " + num(x.before()) + unit(x.unit()) + " → "
                    + num(x.after()) + unit(x.unit()) + (c.overlapping() ? " (another change within 10 minutes)" : ""));
        }
        return supported("change", title, strong ? 78 : 60, ev, "changes", null);
    }

    private static String unit(String u) {
        return u == null || u.isBlank() ? "" : "%".equals(u) ? "%" : " " + u;
    }

    static Hypothesis errors(ErrorRates r, List<XpPattern> fresh, String instanceId) {
        String title = "The application started failing";
        String query = "instance:" + instanceId + " severity:>=error";
        if (r == null) {
            return unknown("errors", title, "Error logs could not be read.", "activity");
        }
        if (r.rowsNow() == 0 && r.rowsBefore() == 0) {
            return unknown("errors", title, "No logs are collected from this host, so errors cannot be checked.", "activity");
        }
        boolean jump = r.now() >= 1 && r.now() >= 3 * r.before() + 0.5;
        boolean novel = fresh != null && !fresh.isEmpty();
        if (!jump && !novel) {
            return ruledOut("errors", title, List.of(String.format(Locale.ROOT, "Errors steady: %s/min (%s/min just before)", num(r.now()), num(r.before()))), "activity");
        }
        List<String> ev = new ArrayList<>();
        if (jump) {
            ev.add(String.format(Locale.ROOT, "Errors: %s/min, up from %s/min in the same length of time before", num(r.now()), num(r.before())));
        }
        if (novel) {
            fresh.stream().limit(3).forEach(p -> ev.add("New error: " + clip(p.template()) + " (" + p.count() + "×)"));
        }
        return supported("errors", title, jump && novel ? 72 : 55, ev, "activity", query);
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }

    static Hypothesis hostSpecific(XpPeers p) {
        String title = "Only this host is affected";
        if (p == null) {
            return unknown("host_specific", title, "Peers could not be compared.", "peers");
        }
        if (p.peers().isEmpty()) {
            return unknown("host_specific", title, p.note() == null ? "No peers to compare with." : p.note(), "peers");
        }
        String who = p.peers().size() + (p.peers().size() == 1 ? " peer" : " peers") + " (" + p.basisLabel() + ")";
        List<XpPeers.Metric> odd = p.metrics().stream().filter(m -> "HIGHER".equals(m.verdict()) || "LOWER".equals(m.verdict())).toList();
        if (odd.isEmpty()) {
            boolean compared = p.metrics().stream().anyMatch(m -> "IN_LINE".equals(m.verdict()));
            return compared ? ruledOut("host_specific", title, List.of("In line with its " + who + " on every compared signal: a shared cause "
                    + "(traffic, a dependency, a fleet-wide change) is more likely than this host"), "peers")
                    : unknown("host_specific", title, "Too few peers report the same signals.", "peers");
        }
        List<String> ev = new ArrayList<>();
        for (XpPeers.Metric m : odd) {
            ev.add(m.label() + " " + fmt(m.value(), m.unit()) + " vs " + fmt(m.peerMedian(), m.unit()) + " on its " + who
                    + (m.indicative() ? ", indicative" : ""));
        }
        boolean indicative = odd.stream().allMatch(XpPeers.Metric::indicative);
        return supported("host_specific", title, indicative ? 40 : 50, ev, "peers", null);
    }

    private static String fmt(Double v, String unit) {
        if (v == null) {
            return "n/a";
        }
        if ("B/s".equals(unit)) {
            double mb = v / (1024 * 1024);
            return mb >= 1 ? num(mb) + " MB/s" : num(v / 1024) + " KB/s";
        }
        return num(v) + ("%".equals(unit) ? "%" : unit == null ? "" : " " + unit);
    }

    static Hypothesis dataGap(List<XpChange> changes) {
        String title = "The agent stopped reporting";
        if (changes == null) {
            return unknown("data_gap", title, "Changes could not be read.", "changes");
        }
        List<XpChange> gaps = changes.stream().filter(c -> "DATA_GAP".equals(c.kind())).toList();
        if (gaps.isEmpty()) {
            return ruledOut("data_gap", title, List.of("Data arrived throughout the range"), "changes");
        }
        return supported("data_gap", title, 50, gaps.stream().limit(3).map(c -> c.title() + " at " + HM.format(c.ts())).toList(), "changes", null);
    }

    /** One paragraph: the strongest lead with its first piece of evidence, what else was seen, ruled out, unknown. */
    static String summarise(List<Hypothesis> hs) {
        List<Hypothesis> sup = hs.stream().filter(h -> "SUPPORTED".equals(h.status())).toList();
        List<Hypothesis> out = hs.stream().filter(h -> "RULED_OUT".equals(h.status())).toList();
        List<Hypothesis> unk = hs.stream().filter(h -> "NOT_MEASURABLE".equals(h.status())).toList();
        StringBuilder b = new StringBuilder();
        if (sup.isEmpty()) {
            b.append("Nothing stands out in this range.");
        } else {
            Hypothesis top = sup.get(0);
            b.append("Strongest lead: ").append(lower(top.title())).append(". ").append(top.evidence().get(0)).append('.');
            if (sup.size() > 1) {
                b.append(" Also seen: ").append(String.join("; ", sup.subList(1, sup.size()).stream().map(h -> lower(h.title())).toList())).append('.');
            }
        }
        if (!out.isEmpty()) {
            b.append(" Ruled out: ").append(String.join("; ", out.stream().map(h -> lower(h.title())).toList())).append('.');
        }
        if (!unk.isEmpty()) {
            b.append(" Cannot tell here: ").append(String.join("; ", unk.stream().map(h -> lower(h.title())).toList())).append('.');
        }
        return b.toString().replace("..", ".");
    }

    /** "The application..." becomes "the application..."; an acronym ("CPU, ...", "AWS ...") stays as it is. */
    static String lower(String s) {
        if (s.isEmpty() || s.length() > 1 && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
