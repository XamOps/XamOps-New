package com.xammer.cloud.service.war.gcp;

import com.google.cloud.compute.v1.Address;
import com.google.cloud.compute.v1.Firewall;
import com.google.cloud.compute.v1.Network;
import com.google.cloud.compute.v1.Subnetwork;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GCP network exposure controls (advisory rows 12-14, 16, 18-20, 23, 25, 27, 92).
 *
 * <p>
 * The advisory's recurring point holds on GCP as it does on AWS: exposed endpoints are
 * found by automated scanners within minutes. These checks look for the specific
 * openings those scanners probe — SSH and RDP from anywhere, public Cloud SQL, and the
 * absence of any egress control that would stop a compromised VM reaching a pool.
 */
@Component
public class GcpWarNetworkChecks {

    /** Ports whose exposure to the internet is what gets a project mined. */
    private static final List<String> ADMIN_PORTS = List.of("22", "3389");

    /** Stratum mining ports from the advisory. */
    private static final List<Integer> STRATUM_PORTS =
            List.of(3333, 4444, 5555, 7777, 8333, 9999, 14444, 45700);

    private static final String OPEN_CIDR = "0.0.0.0/0";

    private final GcpClientProvider clients;

    public GcpWarNetworkChecks(GcpClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String project = ctx.accountId();
        checkFirewallRules(ctx, project);
        checkSubnetPosture(ctx, project);
        checkDefaultNetwork(ctx, project);
        checkCloudArmor(ctx, project);
        checkCloudSqlExposure(ctx, project);
        checkIdleAddresses(ctx, project);
        checkCloudRunIngress(ctx, project);
    }

    /**
     * GCP-16 (SSH/RDP open), GCP-18 (deny-all default), GCP-19 (rule logging),
     * GCP-92 (outbound mining egress).
     *
     * <p>
     * One firewall listing answers all four, so they share a guard.
     */
    private void checkFirewallRules(WarScanContext ctx, String project) {
        ctx.guard("compute.firewalls.list",
                List.of("GCP-16", "GCP-18", "GCP-19", "GCP-92"), () -> {
                    var clientOpt = clients.getFirewallsClient(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("Compute firewalls client unavailable");
                    }

                    try (var firewalls = clientOpt.get()) {
                        List<String> adminOpen = new ArrayList<>();
                        List<String> wideOpen = new ArrayList<>();
                        List<String> unlogged = new ArrayList<>();
                        List<String> openEgress = new ArrayList<>();
                        int scanned = 0;

                        for (Firewall rule : firewalls.list(project).iterateAll()) {
                            scanned++;
                            boolean disabled = rule.hasDisabled() && rule.getDisabled();
                            if (disabled) {
                                continue;
                            }

                            boolean logged = rule.hasLogConfig() && rule.getLogConfig().getEnable();
                            if (!logged) {
                                unlogged.add(rule.getName() + " has rule logging disabled");
                            }

                            boolean ingress = !"EGRESS".equalsIgnoreCase(rule.getDirection());
                            boolean openToWorld = rule.getSourceRangesList().contains(OPEN_CIDR)
                                    || rule.getDestinationRangesList().contains(OPEN_CIDR);
                            if (!openToWorld || !"allow".equalsIgnoreCase(
                                    rule.getAllowedCount() > 0 ? "allow" : "deny")) {
                                continue;
                            }

                            for (var allowed : rule.getAllowedList()) {
                                List<String> ports = allowed.getPortsList();
                                boolean allPorts = ports.isEmpty();

                                if (ingress) {
                                    if (allPorts) {
                                        wideOpen.add(String.format(
                                                "%s allows all %s ports from %s",
                                                rule.getName(), allowed.getIPProtocol(), OPEN_CIDR));
                                    }
                                    for (String port : ports) {
                                        if (ADMIN_PORTS.stream().anyMatch(p -> matchesPort(port, p))) {
                                            adminOpen.add(String.format(
                                                    "%s allows port %s from %s",
                                                    rule.getName(), port, OPEN_CIDR));
                                        }
                                        if (isWideRange(port)) {
                                            wideOpen.add(String.format(
                                                    "%s allows port range %s from %s",
                                                    rule.getName(), port, OPEN_CIDR));
                                        }
                                    }
                                } else {
                                    if (allPorts) {
                                        openEgress.add(String.format(
                                                "%s allows unrestricted egress to %s",
                                                rule.getName(), OPEN_CIDR));
                                    }
                                    for (String port : ports) {
                                        if (STRATUM_PORTS.stream()
                                                .anyMatch(p -> matchesPort(port, String.valueOf(p)))) {
                                            openEgress.add(String.format(
                                                    "%s allows outbound Stratum port %s",
                                                    rule.getName(), port));
                                        }
                                    }
                                }
                            }
                        }

                        ctx.put("GCP-16", WarCheckResult.of(adminOpen,
                                        "SSH or RDP is reachable from the internet",
                                        "No firewall rule exposes SSH or RDP to 0.0.0.0/0")
                                .withScanned(scanned));

                        ctx.put("GCP-18", WarCheckResult.of(wideOpen,
                                        "Firewall rules allow broad inbound ranges from the internet",
                                        "Inbound rules are scoped rather than wide open")
                                .withScanned(scanned));

                        ctx.put("GCP-19", WarCheckResult.of(unlogged,
                                        "Firewall rules are not logging, so rule changes and hits "
                                                + "leave no audit trail",
                                        "All enabled firewall rules have logging on")
                                .withScanned(scanned));

                        ctx.put("GCP-92", WarCheckResult.of(openEgress,
                                        "Compromised VMs have an unrestricted outbound path to mining "
                                                + "pool command-and-control",
                                        "Outbound traffic is scoped rather than open to the internet")
                                .withScanned(scanned));
                    }
                });
    }

    /** GCP-12 (flow logs) and GCP-13 (Private Google Access). */
    private void checkSubnetPosture(WarScanContext ctx, String project) {
        ctx.guard("compute.subnetworks.list", List.of("GCP-12", "GCP-13"), () -> {
            var clientOpt = clients.getSubnetworksClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Compute subnetworks client unavailable");
            }

            try (var subnetworks = clientOpt.get()) {
                List<String> noFlowLogs = new ArrayList<>();
                List<String> noPrivateAccess = new ArrayList<>();
                int scanned = 0;

                // aggregatedList walks every region in one pass.
                for (var entry : subnetworks.aggregatedList(project).iterateAll()) {
                    for (Subnetwork subnet : entry.getValue().getSubnetworksList()) {
                        scanned++;
                        String where = subnet.getName() + " (" + shortRegion(subnet.getRegion()) + ")";
                        if (!(subnet.hasEnableFlowLogs() && subnet.getEnableFlowLogs())) {
                            noFlowLogs.add(where + " has flow logs disabled");
                        }
                        if (!(subnet.hasPrivateIpGoogleAccess() && subnet.getPrivateIpGoogleAccess())) {
                            noPrivateAccess.add(where + " has Private Google Access off");
                        }
                    }
                }

                if (scanned == 0) {
                    ctx.put("GCP-12", WarCheckResult.notApplicable("No subnetworks in this project"));
                    ctx.put("GCP-13", WarCheckResult.notApplicable("No subnetworks in this project"));
                    return;
                }

                ctx.put("GCP-12", WarCheckResult.of(noFlowLogs,
                                "Subnets are running without flow logs — no network forensics and no "
                                        + "way to spot mining pool traffic after the fact",
                                "Every subnet has flow logs enabled")
                        .withScanned(scanned));

                ctx.put("GCP-13", WarCheckResult.of(noPrivateAccess,
                                "Subnets lack Private Google Access, so API traffic and any "
                                        + "exfiltration path traverse the public internet",
                                "All subnets use Private Google Access")
                        .withScanned(scanned));
            }
        });
    }

    /** GCP-14: the default network ships with permissive rules. */
    private void checkDefaultNetwork(WarScanContext ctx, String project) {
        ctx.guard("compute.networks.list", List.of("GCP-14"), () -> {
            var clientOpt = clients.getNetworksClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Compute networks client unavailable");
            }
            try (var networks = clientOpt.get()) {
                List<String> defaults = new ArrayList<>();
                for (Network network : networks.list(project).iterateAll()) {
                    if ("default".equals(network.getName())) {
                        defaults.add("The default network still exists and ships with permissive "
                                + "rules for SSH, RDP and ICMP");
                    }
                }
                ctx.put("GCP-14", WarCheckResult.of(defaults,
                        "The auto-created default network is still present",
                        "No default network — VPCs are deliberately configured"));
            }
        });
    }

    /** GCP-20: Cloud Armor in front of internet-facing load balancers. */
    private void checkCloudArmor(WarScanContext ctx, String project) {
        ctx.guard("compute.securityPolicies.list", List.of("GCP-20"), () -> {
            var clientOpt = clients.getSecurityPoliciesClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Compute security policies client unavailable");
            }
            try (var policies = clientOpt.get()) {
                int count = 0;
                List<String> names = new ArrayList<>();
                for (var policy : policies.list(project).iterateAll()) {
                    count++;
                    names.add(policy.getName());
                }
                ctx.put("GCP-20", count > 0
                        ? WarCheckResult.pass(count + " Cloud Armor policy/policies protect edge "
                                + "endpoints").withEvidence(names)
                        : WarCheckResult.fail("No Cloud Armor security policy — attack traffic "
                                + "reaching an autoscaled backend becomes a compute billing spike"));
            }
        });
    }

    /** GCP-23: Cloud SQL must not be publicly addressable. */
    private void checkCloudSqlExposure(WarScanContext ctx, String project) {
        ctx.guard("cloudsql.instances.list", List.of("GCP-23"), () -> {
            var clientOpt = clients.getSqlAdminClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Cloud SQL Admin client unavailable");
            }

            var sqlAdmin = clientOpt.get();
            // The Cloud SQL Admin REST client throws a checked IOException, which a
            // Runnable cannot propagate. Rethrowing unchecked lets guard() record the
            // real cause against the control instead of swallowing it.
            List<com.google.api.services.sqladmin.model.DatabaseInstance> instances;
            try {
                instances = sqlAdmin.instances().list(project).execute().getItems();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }

            if (instances == null || instances.isEmpty()) {
                ctx.put("GCP-23", WarCheckResult.notApplicable(
                        "No Cloud SQL instances in this project"));
                return;
            }

            List<String> exposed = new ArrayList<>();
            for (var instance : instances) {
                var settings = instance.getSettings();
                var ipConfig = settings == null ? null : settings.getIpConfiguration();
                boolean publicIp = ipConfig == null
                        || ipConfig.getIpv4Enabled() == null
                        || Boolean.TRUE.equals(ipConfig.getIpv4Enabled());
                if (!publicIp) {
                    continue;
                }
                // A public IP behind an empty authorized-network list is still reachable
                // only via Cloud SQL auth; open 0.0.0.0/0 is the real finding.
                boolean openToWorld = ipConfig != null && ipConfig.getAuthorizedNetworks() != null
                        && ipConfig.getAuthorizedNetworks().stream()
                                .anyMatch(n -> OPEN_CIDR.equals(n.getValue()));
                exposed.add(String.format("%s has a public IP%s",
                        instance.getName(),
                        openToWorld ? " authorized from 0.0.0.0/0" : ""));
            }

            ctx.put("GCP-23", WarCheckResult.of(exposed,
                            "Cloud SQL instances are publicly addressable",
                            "No Cloud SQL instance has a public IP")
                    .withScanned(instances.size()));
        });
    }

    /** GCP-25: reserved-but-unattached external addresses. */
    private void checkIdleAddresses(WarScanContext ctx, String project) {
        ctx.guard("compute.addresses.list", List.of("GCP-25"), () -> {
            var clientOpt = clients.getAddressesClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Compute addresses client unavailable");
            }
            try (var addresses = clientOpt.get()) {
                List<String> idle = new ArrayList<>();
                for (var entry : addresses.aggregatedList(project).iterateAll()) {
                    for (Address address : entry.getValue().getAddressesList()) {
                        // RESERVED means allocated and billing but attached to nothing.
                        if ("RESERVED".equalsIgnoreCase(address.getStatus())) {
                            idle.add(String.format("%s (%s) is reserved but unattached",
                                    address.getName(), address.getAddress()));
                        }
                    }
                }
                ctx.put("GCP-25", WarCheckResult.of(idle,
                        "External static IPs are reserved but unattached — ungoverned surface that "
                                + "also bills",
                        "No unattached external static IPs"));
            }
        });
    }

    /** GCP-27: Cloud Run services that accept unauthenticated internet traffic. */
    private void checkCloudRunIngress(WarScanContext ctx, String project) {
        ctx.guard("run.services.list", List.of("GCP-27"), () -> {
            var clientOpt = clients.getCloudRunServicesClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Cloud Run client unavailable");
            }
            try (var run = clientOpt.get()) {
                List<String> open = new ArrayList<>();
                int scanned = 0;
                // "-" as the location wildcard lists services across all regions.
                String parent = String.format("projects/%s/locations/-", project);
                for (var service : run.listServices(parent).iterateAll()) {
                    scanned++;
                    String ingress = service.getIngress() == null
                            ? ""
                            : service.getIngress().name();
                    if (ingress.toUpperCase(Locale.ROOT).contains("ALL")) {
                        open.add(String.format("%s accepts ingress from all sources (%s)",
                                service.getName(), ingress));
                    }
                }

                if (scanned == 0) {
                    ctx.put("GCP-27", WarCheckResult.notApplicable(
                            "No Cloud Run services in this project"));
                    return;
                }
                ctx.put("GCP-27", WarCheckResult.of(open,
                                "Cloud Run services accept traffic from the whole internet",
                                "Cloud Run ingress is restricted to internal or load-balancer traffic")
                        .withScanned(scanned));
            }
        });
    }

    /** GCP firewall ports are strings that may be a single port or a {@code from-to} range. */
    private static boolean matchesPort(String spec, String target) {
        if (spec.equals(target)) {
            return true;
        }
        int dash = spec.indexOf('-');
        if (dash <= 0) {
            return false;
        }
        try {
            int from = Integer.parseInt(spec.substring(0, dash).trim());
            int to = Integer.parseInt(spec.substring(dash + 1).trim());
            int want = Integer.parseInt(target);
            return want >= from && want <= to;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isWideRange(String spec) {
        int dash = spec.indexOf('-');
        if (dash <= 0) {
            return false;
        }
        try {
            int from = Integer.parseInt(spec.substring(0, dash).trim());
            int to = Integer.parseInt(spec.substring(dash + 1).trim());
            return to - from > 100;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Region URLs come back fully qualified; only the trailing name is useful in evidence. */
    private static String shortRegion(String regionUrl) {
        if (regionUrl == null || regionUrl.isBlank()) {
            return "unknown";
        }
        int slash = regionUrl.lastIndexOf('/');
        return slash >= 0 ? regionUrl.substring(slash + 1) : regionUrl;
    }
}
