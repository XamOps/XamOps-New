package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.domain.XpAgent;
import com.xammer.cloud.xampulse.dto.XpPeers;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.repository.XpAgentRepository;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.MetricSpec;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
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
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.Pattern;

/**
 * Fleet twin: is this host different from the hosts that should behave like it? Peers are the other instances of its
 * Auto Scaling group, else instances of the same type whose Name tag follows the same pattern (web-1, web-2...). Each
 * signal is compared with a robust z-score (median and MAD, so one bad peer cannot hide an outlier) and a minimum
 * absolute difference, so tiny differences between idle hosts are never called outliers.
 */
@Service("xpPeerOutlierService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class PeerOutlierService {

    static final int MAX_PEERS = 50;
    /** Modified z-score cut-off (Iglewicz and Hoaglin). */
    static final double Z_OUTLIER = 3.5;
    private static final Pattern TRAILING_ID = Pattern.compile("[-_.]?(\\d+|[0-9a-f]{5,})$");
    private static final String WHOLE_DISK = "nvme[0-9]+n[0-9]+|xvd[a-z]+|sd[a-z]+|vd[a-z]+";
    private static final String PHYSICAL_NIC = "eth[0-9]+|ens[0-9]+|enp[0-9a-z]+|eno[0-9]+|enX[0-9]+";

    /** One compared signal: how to read it per instance, how to scale it, and the smallest difference that matters. */
    record Signal(String key, String label, String unit, MetricSpec spec, DoubleUnaryOperator scale, double floor) {}

    static final List<Signal> SIGNALS = List.of(
            new Signal("cpu", "CPU busy", "%", spec("system.cpu.utilization", "avg", "state", "=", "idle"), v -> (1 - v) * 100, 5),
            new Signal("memory", "Memory used", "%", spec("system.memory.utilization", "avg", "state", "=", "used"), v -> v * 100, 5),
            new Signal("steal", "CPU steal", "%", spec("system.cpu.utilization", "avg", "state", "=", "steal"), v -> v * 100, 2),
            new Signal("cpu_pressure", "CPU pressure", "%", spec("xp.pressure.stall_time", "rate", "resource", "=", "cpu"), v -> v * 100, 2),
            new Signal("disk_io", "Disk I/O", "B/s", spec("system.disk.io", "rate", "device", "=~", WHOLE_DISK), v -> v, 1 << 20),
            new Signal("network", "Network", "B/s", spec("system.network.io", "rate", "device", "=~", PHYSICAL_NIC), v -> v, 1 << 20));

    private static MetricSpec spec(String metric, String agg, String k, String op, String v) {
        List<MetricSpec.LabelFilter> f = new ArrayList<>(List.of(new MetricSpec.LabelFilter(k, op, v)));
        if (metric.equals("xp.pressure.stall_time")) {
            f.add(new MetricSpec.LabelFilter("level", "=", "some"));
        }
        return new MetricSpec(metric, agg, List.of("instance_id"), f);
    }

    private final XamPulseAccessGuard guard;
    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final XpAgentRepository agents;
    private final Ec2FactsService ec2;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public PeerOutlierService(XamPulseAccessGuard guard, QueryScopeFactory scopes, ClickHouseExecutor executor,
                              XpAgentRepository agents, Ec2FactsService ec2) {
        this.guard = guard;
        this.scopes = scopes;
        this.executor = executor;
        this.agents = agents;
        this.ec2 = ec2;
    }

    public XpPeers peers(ClientUserDetails user, Long accountId, String instanceId, Instant from, Instant to) {
        CloudAccount account = guard.require(user, accountId);
        scopes.build(user, List.of(accountId), from, to); // validates the range for this caller
        XpAgent me = agents.findByCloudAccountIdAndInstanceId(accountId, instanceId).orElseThrow(XamPulseException::notFound);
        String region = me.getRegion();
        List<String> sameRegion = agents.findByCloudAccountId(accountId).stream()
                .filter(a -> "ACTIVE".equals(a.getState()) && Objects.equals(region, a.getRegion()))
                .map(XpAgent::getInstanceId).toList();
        if (region == null || sameRegion.size() < 2) {
            return new XpPeers(instanceId, "NONE", null, List.of(), List.of(), "No other host with an agent in this region to compare with.");
        }
        Map<String, Ec2FactsService.PeerKey> keys = ec2.peerKeys(account, region, sameRegion);
        if (!keys.containsKey(instanceId)) {
            return new XpPeers(instanceId, "NONE", null, List.of(), List.of(),
                    "EC2 instance details could not be read, so peers are not known (the connection role needs ec2:DescribeInstances).");
        }
        Choice c = choose(keys.get(instanceId), keys.values());
        if (c.peers().isEmpty()) {
            return new XpPeers(instanceId, "NONE", null, List.of(), List.of(),
                    "No other host shares its Auto Scaling group, or its instance type and name pattern.");
        }
        List<String> ids = new ArrayList<>();
        ids.add(instanceId);
        c.peers().forEach(p -> ids.add(p.instanceId()));
        QueryScope scope = new QueryScope(List.of(accountId), from, to, List.of(), ids, List.of(), List.of());
        Map<String, Map<String, Double>> values = new LinkedHashMap<>();
        for (Signal s : SIGNALS) {
            Map<String, Double> byInstance = new HashMap<>();
            for (Map<String, Object> r : executor.query(builder.alertMetric(scope, s.spec()))) {
                Double v = Rows.dOrNull(r, "v");
                if (v != null && Double.isFinite(v)) {
                    byInstance.put(Rows.s(r, "g0"), s.scale().applyAsDouble(v));
                }
            }
            values.put(s.key(), byInstance);
        }
        Map<String, Double> errors = new HashMap<>();
        double minutes = Math.max(1, Duration.between(from, to).toMinutes());
        for (Map<String, Object> r : executor.query(builder.errorVolume(scope))) {
            errors.put(Rows.s(r, "instance_id"), Rows.l(r, "errors") / minutes);
        }
        List<XpPeers.Metric> metrics = new ArrayList<>();
        for (Signal s : SIGNALS) {
            metrics.add(compare(s.key(), s.label(), s.unit(), instanceId, values.get(s.key()), c.peers(), s.floor()));
        }
        metrics.add(compare("errors", "Errors and fatals", "/min", instanceId, errors, c.peers(), 1));
        List<XpPeers.Peer> peers = c.peers().stream().map(p -> new XpPeers.Peer(p.instanceId(), p.name())).toList();
        return new XpPeers(instanceId, c.basis(), c.label(), peers, metrics, null);
    }

    record Choice(String basis, String label, List<Ec2FactsService.PeerKey> peers) {}

    /** "web-3" and "web-12" share "web"; "api-7f3a9c" shares "api". Null when the name has no pattern. */
    static String namePattern(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String p = TRAILING_ID.matcher(name.trim().toLowerCase(Locale.ROOT)).replaceFirst("");
        return p.isEmpty() ? null : p;
    }

    /** Auto Scaling group first (the strongest twin), else same type and name pattern. Peers exclude the host itself. */
    static Choice choose(Ec2FactsService.PeerKey me, java.util.Collection<Ec2FactsService.PeerKey> all) {
        if (me.autoScalingGroup() != null) {
            List<Ec2FactsService.PeerKey> asg = all.stream()
                    .filter(k -> !k.instanceId().equals(me.instanceId()) && me.autoScalingGroup().equals(k.autoScalingGroup()))
                    .sorted(Comparator.comparing(Ec2FactsService.PeerKey::instanceId)).limit(MAX_PEERS).toList();
            if (!asg.isEmpty()) {
                return new Choice("ASG", "Auto Scaling group " + me.autoScalingGroup(), asg);
            }
        }
        String pattern = namePattern(me.name());
        if (me.instanceType() != null && pattern != null) {
            List<Ec2FactsService.PeerKey> twins = all.stream()
                    .filter(k -> !k.instanceId().equals(me.instanceId()) && me.instanceType().equals(k.instanceType())
                            && pattern.equals(namePattern(k.name())))
                    .sorted(Comparator.comparing(Ec2FactsService.PeerKey::instanceId)).limit(MAX_PEERS).toList();
            if (!twins.isEmpty()) {
                return new Choice("TYPE_NAME", me.instanceType() + " named " + pattern + "-*", twins);
            }
        }
        return new Choice("NONE", null, List.of());
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /**
     * Robust comparison: z = (value - median of peers) / max(1.4826 * MAD, floor / 3). An outlier needs |z| >= 3.5 and a
     * difference of at least {@code floor}. With exactly two peers the spread is their distance instead of the MAD,
     * and the verdict is marked indicative.
     */
    static XpPeers.Metric compare(String key, String label, String unit, String me, Map<String, Double> values,
                                  List<Ec2FactsService.PeerKey> peers, double floor) {
        Map<String, Double> peerValues = new LinkedHashMap<>();
        for (Ec2FactsService.PeerKey p : peers) {
            Double v = values.get(p.instanceId());
            if (v != null) {
                peerValues.put(p.instanceId(), v);
            }
        }
        Double value = values.get(me);
        double[] pv = peerValues.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        Double med = pv.length == 0 ? null : median(pv);
        Double min = pv.length == 0 ? null : pv[0];
        Double max = pv.length == 0 ? null : pv[pv.length - 1];
        if (value == null) {
            return new XpPeers.Metric(key, label, unit, null, med, min, max, null, "NO_DATA", false, peerValues);
        }
        if (pv.length < 2) {
            return new XpPeers.Metric(key, label, unit, value, med, min, max, null, "TOO_FEW", false, peerValues);
        }
        double spread;
        if (pv.length == 2) {
            spread = Math.abs(pv[1] - pv[0]);
        } else {
            double m = med;
            double[] dev = Arrays.stream(pv).map(x -> Math.abs(x - m)).sorted().toArray();
            spread = 1.4826 * median(dev);
        }
        double d = value - med;
        double z = d / Math.max(spread, floor / 3);
        String verdict = Math.abs(z) >= Z_OUTLIER && Math.abs(d) >= floor ? (d > 0 ? "HIGHER" : "LOWER") : "IN_LINE";
        return new XpPeers.Metric(key, label, unit, value, med, min, max, Math.round(z * 10) / 10.0, verdict, pv.length == 2, peerValues);
    }
}
