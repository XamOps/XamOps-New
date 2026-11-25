package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.xampulse.dto.XpEc2Facts;
import com.xammer.cloud.xampulse.dto.XpExposure;
import com.xammer.cloud.xampulse.dto.XpHostLimits;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService;
import com.xammer.cloud.xampulse.service.aws.Ec2FactsService.IngressRule;
import com.xammer.cloud.xampulse.service.query.ClickHouseExecutor;
import com.xammer.cloud.xampulse.service.query.QueryScope;
import com.xammer.cloud.xampulse.service.query.QueryScopeFactory;
import com.xammer.cloud.xampulse.service.query.Rows;
import com.xammer.cloud.xampulse.service.query.TenantScopedQueryBuilder;
import com.xammer.cloud.xampulse.service.query.XamPulseAccessGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exposure check: each socket the agent sees listening, matched against the instance's security group inbound rules.
 * Facts only: which rule lets which source reach which listening port. Rules for ports nothing listens on are
 * reported too (open but unused). Network ACLs, load balancers and host firewalls are not modelled and are said so.
 */
@Service("xpExposureService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class ExposureService {

    static final Duration FRESH = Duration.ofMinutes(15);
    private static final Pattern SG_ID = Pattern.compile("\\((sg-[0-9a-f]+)\\)");
    private static final Pattern LITERAL = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F:]*:[0-9a-fA-F:.]*)$");

    /** Services that should never face the internet: databases, caches, admin and cluster ports. */
    static final Map<Integer, String> SENSITIVE = Map.ofEntries(
            Map.entry(1433, "SQL Server"), Map.entry(1521, "Oracle"), Map.entry(2375, "Docker API (plain)"), Map.entry(2376, "Docker API"),
            Map.entry(2379, "etcd"), Map.entry(3306, "MySQL"), Map.entry(3389, "RDP"), Map.entry(5432, "PostgreSQL"),
            Map.entry(5601, "Kibana"), Map.entry(5900, "VNC"), Map.entry(5984, "CouchDB"), Map.entry(6379, "Redis"),
            Map.entry(8086, "InfluxDB"), Map.entry(8500, "Consul"), Map.entry(9090, "Prometheus"), Map.entry(9092, "Kafka"),
            Map.entry(9200, "Elasticsearch"), Map.entry(9300, "Elasticsearch transport"), Map.entry(10250, "kubelet"),
            Map.entry(11211, "Memcached"), Map.entry(15672, "RabbitMQ management"), Map.entry(27017, "MongoDB"));
    static final Map<Integer, String> COMMON = Map.ofEntries(
            Map.entry(22, "SSH"), Map.entry(25, "SMTP"), Map.entry(53, "DNS"), Map.entry(80, "HTTP"), Map.entry(123, "NTP"),
            Map.entry(443, "HTTPS"), Map.entry(8080, "HTTP (alt)"), Map.entry(8443, "HTTPS (alt)"), Map.entry(9100, "node exporter"),
            Map.entry(13133, "collector health"));
    private static final Set<Integer> WEB = Set.of(80, 443);

    private final XamPulseAccessGuard guard;
    private final QueryScopeFactory scopes;
    private final ClickHouseExecutor executor;
    private final Ec2FactsService ec2;
    private final TenantScopedQueryBuilder builder = new TenantScopedQueryBuilder();

    public ExposureService(XamPulseAccessGuard guard, QueryScopeFactory scopes, ClickHouseExecutor executor, Ec2FactsService ec2) {
        this.guard = guard;
        this.scopes = scopes;
        this.executor = executor;
        this.ec2 = ec2;
    }

    record Listening(String protocol, int port, String bind, String address) {}

    public XpExposure exposure(ClientUserDetails user, Long accountId, String instanceId) {
        CloudAccount account = guard.require(user, accountId);
        if (account.getAwsAccountId() == null || (account.getProvider() != null && !"AWS".equalsIgnoreCase(account.getProvider()))) {
            throw XamPulseException.notFound();
        }
        Instant now = Instant.now();
        scopes.build(user, List.of(accountId), now.minus(FRESH), now);
        XpEc2Facts f = ec2.facts(account, instanceId, null);
        List<String> groupIds = new ArrayList<>();
        for (String g : f.securityGroups() == null ? List.<String>of() : f.securityGroups()) {
            Matcher m = SG_ID.matcher(g);
            if (m.find()) {
                groupIds.add(m.group(1));
            }
        }
        List<IngressRule> rules = ec2.ingress(account, f.region(), groupIds);
        QueryScope scope = new QueryScope(List.of(accountId), now.minus(FRESH), now, List.of(), List.of(instanceId), List.of(), List.of());
        List<Listening> socks = latest(executor.query(builder.latestMetrics(scope, now.minus(FRESH).getEpochSecond(), List.of("xp.net.listening"))));
        return evaluate(instanceId, f.publicIp(), f.securityGroups(), socks, rules, !socks.isEmpty());
    }

    /** Sockets of the latest scrape only: one closed ten minutes ago is not listening any more. */
    static List<Listening> latest(List<Map<String, Object>> rows) {
        long newest = rows.stream().mapToLong(r -> Rows.l(r, "last_ts")).max().orElse(0);
        List<Listening> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (Rows.l(r, "last_ts") < newest - 120) {
                continue;
            }
            Map<String, String> l = ThrottleRadarService.labels(Rows.s(r, "labels_key"));
            try {
                out.add(new Listening(l.getOrDefault("protocol", "tcp"), Integer.parseInt(l.getOrDefault("port", "0")),
                        l.getOrDefault("bind", "address"), l.getOrDefault("address", "")));
            } catch (NumberFormatException e) {
                // a malformed label is skipped, never guessed
            }
        }
        return out;
    }

    enum Source { INTERNET, PUBLIC_RANGE, PRIVATE }

    static Source classify(String source) {
        if (source == null) {
            return Source.PRIVATE;
        }
        if ("0.0.0.0/0".equals(source) || "::/0".equals(source)) {
            return Source.INTERNET;
        }
        if (source.startsWith("sg-") || source.startsWith("pl-")) {
            return Source.PRIVATE;
        }
        String host = source.contains("/") ? source.substring(0, source.indexOf('/')) : source;
        if (!LITERAL.matcher(host).matches()) {
            return Source.PRIVATE; // never resolve a name: only address literals are classified
        }
        try {
            InetAddress a = InetAddress.getByName(host); // a literal address: no DNS lookup
            if (a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || host.toLowerCase().startsWith("fd") || host.toLowerCase().startsWith("fc") || cgnat(a)) {
                return Source.PRIVATE;
            }
            return Source.PUBLIC_RANGE;
        } catch (Exception e) {
            return Source.PRIVATE;
        }
    }

    private static boolean cgnat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    static boolean covers(IngressRule r, String protocol, int port) {
        boolean proto = "all".equals(r.protocol()) || r.protocol().equals(protocol);
        boolean ports = r.fromPort() == null || (port >= r.fromPort() && port <= r.toPort());
        return proto && ports;
    }

    static String service(int port) {
        String s = SENSITIVE.get(port);
        return s != null ? s : COMMON.get(port);
    }

    static XpExposure evaluate(String instanceId, String publicIp, List<String> groups, List<Listening> socks, List<IngressRule> rules, boolean measured) {
        boolean pub = publicIp != null && !publicIp.isBlank();
        List<XpExposure.Socket> out = new ArrayList<>();
        List<XpHostLimits.Finding> findings = new ArrayList<>();
        Set<String> seenPorts = new TreeSet<>();
        List<Listening> sorted = new ArrayList<>(socks);
        sorted.sort(Comparator.comparingInt(Listening::port).thenComparing(Listening::protocol));
        Map<String, Listening> dedup = new LinkedHashMap<>();
        for (Listening s : sorted) {
            // the same port on 0.0.0.0 and :: is one service; the widest bind wins
            String k = s.protocol() + "|" + s.port();
            Listening prev = dedup.get(k);
            if (prev == null || rankBind(s.bind()) > rankBind(prev.bind())) {
                dedup.put(k, s);
            }
        }
        for (Listening s : dedup.values()) {
            seenPorts.add(s.protocol() + "|" + s.port());
            String name = service(s.port());
            if ("loopback".equals(s.bind())) {
                out.add(new XpExposure.Socket(s.protocol(), s.port(), s.bind(), s.address(), name, "LOCAL", null));
                continue;
            }
            Source widest = null;
            String via = null;
            for (IngressRule r : rules) {
                if (!covers(r, s.protocol(), s.port())) {
                    continue;
                }
                for (String src : r.sources()) {
                    Source c = classify(src);
                    if (widest == null || c.ordinal() < widest.ordinal()) {
                        widest = c;
                        via = r.groupName() + " (" + r.groupId() + ") allows " + src + " on " + ports(r);
                    }
                }
            }
            String exposure = widest == null ? "BLOCKED"
                    : widest == Source.INTERNET ? (pub ? "INTERNET" : "PRIVATE")
                    : widest == Source.PUBLIC_RANGE ? (pub ? "PUBLIC_RANGE" : "PRIVATE") : "PRIVATE";
            out.add(new XpExposure.Socket(s.protocol(), s.port(), s.bind(), s.address(), name, exposure, via));
            String label = (name == null ? "Port " + s.port() : name + " (" + s.port() + ")") + "/" + s.protocol();
            if ("INTERNET".equals(exposure)) {
                if (SENSITIVE.containsKey(s.port())) {
                    findings.add(new XpHostLimits.Finding("CRITICAL", "exposed_sensitive", "AWS", label + " is reachable from the internet",
                            via + ", and it listens on " + (s.bind().equals("all") ? "all interfaces" : s.address()) + " with public IP " + publicIp + ".",
                            "Remove the 0.0.0.0/0 (and ::/0) rule, or limit it to the hosts that need it; bind the service to a private address.",
                            Map.of("port", (double) s.port())));
                } else if (!WEB.contains(s.port())) {
                    findings.add(new XpHostLimits.Finding("WARNING", s.port() == 22 ? "exposed_ssh" : "exposed_port", "AWS",
                            label + " is reachable from the internet", via + ".",
                            s.port() == 22 ? "Use SSM Session Manager instead of SSH, or limit the rule to your own addresses."
                                    : "Limit the rule to the sources that need this port.", Map.of("port", (double) s.port())));
                }
            }
        }
        // Open to the internet but nothing listens: nothing is exposed today, but the next service started on that
        // port would be.
        for (IngressRule r : rules) {
            boolean internet = r.sources().stream().anyMatch(x -> classify(x) == Source.INTERNET);
            if (!internet || !pub) {
                continue;
            }
            if ("all".equals(r.protocol()) && r.fromPort() == null) {
                findings.add(new XpHostLimits.Finding("WARNING", "sg_all_traffic", "AWS",
                        r.groupName() + " allows all traffic from the internet", r.groupName() + " (" + r.groupId() + ") has an inbound rule for every protocol and port from 0.0.0.0/0 or ::/0.",
                        "Replace it with rules for the ports the instance serves.", Map.of()));
                continue;
            }
            if (r.fromPort() != null && r.fromPort().equals(r.toPort()) && measured && !seenPorts.contains(r.protocol() + "|" + r.fromPort())) {
                findings.add(new XpHostLimits.Finding("INFO", "sg_unused_open", "AWS",
                        "Port " + r.fromPort() + "/" + r.protocol() + " is open to the internet but nothing listens on it",
                        r.groupName() + " (" + r.groupId() + ") allows it; nothing on the host listens there now.",
                        "Close it if no service is meant to run there.", Map.of("port", (double) r.fromPort())));
            }
        }
        findings.sort(Comparator.comparingInt((XpHostLimits.Finding x) -> switch (x.severity()) {
            case "CRITICAL" -> 0;
            case "WARNING" -> 1;
            default -> 2;
        }));
        String note = !measured
                ? "The agent on this host does not report listening sockets yet (an agent update adds it), so exposure cannot be checked; only the security group rules are shown."
                : "Network ACLs, load balancers, the host's own firewall and the instance's IPv6 addresses are not included."
                        + (pub ? "" : " The instance has no public IPv4 address, so internet rules do not reach it over IPv4.");
        return new XpExposure(instanceId, publicIp, measured, groups, out, findings, note);
    }

    private static int rankBind(String bind) {
        return switch (bind) {
            case "all" -> 2;
            case "address" -> 1;
            default -> 0;
        };
    }

    private static String ports(IngressRule r) {
        if (r.fromPort() == null) {
            return "all ports";
        }
        return r.fromPort().equals(r.toPort()) ? "port " + r.fromPort() : "ports " + r.fromPort() + "-" + r.toPort();
    }
}
