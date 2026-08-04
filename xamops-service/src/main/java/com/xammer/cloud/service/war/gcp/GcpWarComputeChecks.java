package com.xammer.cloud.service.war.gcp;

import com.google.cloud.compute.v1.Instance;
import com.google.container.v1.Cluster;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GCP compute and GKE controls (advisory rows 45-56, plus 22 and 60).
 *
 * <p>
 * Once an attacker has any foothold, this group decides whether they get one VM or the
 * whole fleet. The default Compute Engine service account is the GCP analogue of an
 * over-broad EC2 instance profile: it is attached automatically and, unless scoped,
 * carries editor on the project.
 */
@Component
public class GcpWarComputeChecks {

    /**
     * Machine families that are both the most expensive per hour and the most
     * attractive to mine on.
     */
    private static final List<String> HIGH_COST_FAMILIES =
            List.of("a2", "a3", "g2", "n1-highmem-96", "ct5", "tpu");

    private final GcpClientProvider clients;

    public GcpWarComputeChecks(GcpClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String project = ctx.accountId();
        checkInstanceHardening(ctx, project);
        checkGkeHardening(ctx, project);
        checkPatchDeployments(ctx, project);
    }

    /**
     * GCP-45 (OS Login), GCP-46 (default service account), GCP-47 (project-wide SSH
     * keys), GCP-48 (Shielded VM), GCP-49 (serial port), GCP-60 (labels).
     *
     * <p>
     * One aggregated instance listing answers all six.
     */
    private void checkInstanceHardening(WarScanContext ctx, String project) {
        ctx.guard("compute.instances.list",
                List.of("GCP-45", "GCP-46", "GCP-47", "GCP-48", "GCP-49", "GCP-60"), () -> {
                    var clientOpt = clients.getInstancesClient(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("Compute instances client unavailable");
                    }

                    try (var instances = clientOpt.get()) {
                        List<String> noOsLogin = new ArrayList<>();
                        List<String> defaultSa = new ArrayList<>();
                        List<String> projectSshKeys = new ArrayList<>();
                        List<String> notShielded = new ArrayList<>();
                        List<String> serialPort = new ArrayList<>();
                        List<String> unlabelled = new ArrayList<>();
                        int scanned = 0;

                        for (var entry : instances.aggregatedList(project).iterateAll()) {
                            for (Instance vm : entry.getValue().getInstancesList()) {
                                if (!"RUNNING".equalsIgnoreCase(vm.getStatus())) {
                                    continue;
                                }
                                scanned++;
                                Map<String, String> metadata = metadataOf(vm);
                                String where = vm.getName() + " (" + shortZone(vm.getZone()) + ")";

                                if (!"true".equalsIgnoreCase(metadata.get("enable-oslogin"))) {
                                    noOsLogin.add(where + " does not enforce OS Login, so SSH access "
                                            + "is not tied to IAM identities");
                                }
                                if (!"true".equalsIgnoreCase(
                                        metadata.get("block-project-ssh-keys"))) {
                                    projectSshKeys.add(where + " accepts project-wide SSH keys");
                                }
                                if ("true".equalsIgnoreCase(metadata.get("serial-port-enable"))) {
                                    serialPort.add(where + " has interactive serial port access on");
                                }

                                // The default compute SA ends in -compute@developer.gserviceaccount.com
                                // and, with cloud-platform scope, can act as project editor.
                                boolean usesDefaultSa = vm.getServiceAccountsList().stream()
                                        .anyMatch(sa -> sa.getEmail() != null
                                                && sa.getEmail().endsWith("-compute@developer"
                                                        + ".gserviceaccount.com"));
                                boolean fullScope = vm.getServiceAccountsList().stream()
                                        .anyMatch(sa -> sa.getScopesList().stream()
                                                .anyMatch(s -> s.contains("cloud-platform")));
                                if (usesDefaultSa) {
                                    defaultSa.add(String.format("%s runs as the default compute "
                                            + "service account%s", where,
                                            fullScope ? " with cloud-platform scope" : ""));
                                }

                                var shielded = vm.getShieldedInstanceConfig();
                                boolean hardened = shielded != null
                                        && shielded.getEnableSecureBoot()
                                        && shielded.getEnableVtpm()
                                        && shielded.getEnableIntegrityMonitoring();
                                if (!hardened) {
                                    notShielded.add(where + " is missing one or more Shielded VM "
                                            + "features (secure boot, vTPM, integrity monitoring)");
                                }

                                if (vm.getLabelsMap().isEmpty()) {
                                    unlabelled.add(where + " has no labels, so it cannot be "
                                            + "attributed to a team or workload");
                                }
                            }
                        }

                        if (scanned == 0) {
                            List.of("GCP-45", "GCP-46", "GCP-47", "GCP-48", "GCP-49", "GCP-60")
                                    .forEach(id -> ctx.put(id, WarCheckResult.notApplicable(
                                            "No running Compute Engine instances in this project")));
                            return;
                        }

                        ctx.put("GCP-45", WarCheckResult.of(noOsLogin,
                                        "OS Login is not enforced on every VM",
                                        "All running VMs enforce OS Login").withScanned(scanned));

                        ctx.put("GCP-46", WarCheckResult.of(defaultSa,
                                        "VMs run as the default compute service account",
                                        "No VM uses the default compute service account")
                                .withScanned(scanned));

                        ctx.put("GCP-47", WarCheckResult.of(projectSshKeys,
                                        "VMs accept project-wide SSH keys, so one shared key reaches "
                                                + "the whole fleet",
                                        "All VMs block project-wide SSH keys").withScanned(scanned));

                        ctx.put("GCP-48", WarCheckResult.of(notShielded,
                                        "Shielded VM features are incomplete",
                                        "All VMs have secure boot, vTPM and integrity monitoring")
                                .withScanned(scanned));

                        ctx.put("GCP-49", WarCheckResult.of(serialPort,
                                        "Interactive serial port access is enabled",
                                        "Serial port access is disabled on all VMs")
                                .withScanned(scanned));

                        // Labels are governance rather than a breach path, so their absence
                        // is a warning: it makes shadow compute unattributable, not reachable.
                        ctx.put("GCP-60", unlabelled.isEmpty()
                                ? WarCheckResult.pass("All running VMs carry labels")
                                        .withScanned(scanned)
                                : WarCheckResult.warn("Unlabelled resources cannot be attributed to a "
                                        + "team, so anomalous spend has no owner")
                                        .withEvidence(unlabelled).withScanned(scanned));
                    }
                });
    }

    /**
     * GCP-22 (node external IPs), GCP-51 (Workload Identity), GCP-52 (Binary
     * Authorization), GCP-53 (audit logging), GCP-54 (Autopilot), GCP-55 (private
     * clusters), GCP-56 (intra-node visibility and network policy).
     */
    private void checkGkeHardening(WarScanContext ctx, String project) {
        ctx.guard("container.clusters.list",
                List.of("GCP-22", "GCP-51", "GCP-52", "GCP-53", "GCP-54", "GCP-55", "GCP-56"),
                () -> {
                    var clientOpt = clients.getClusterManagerClient(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("GKE cluster manager client unavailable");
                    }

                    try (var gke = clientOpt.get()) {
                        var response = gke.listClusters(
                                String.format("projects/%s/locations/-", project));
                        List<Cluster> clusters = response.getClustersList();

                        if (clusters.isEmpty()) {
                            List.of("GCP-22", "GCP-51", "GCP-52", "GCP-53", "GCP-54", "GCP-55",
                                            "GCP-56")
                                    .forEach(id -> ctx.put(id, WarCheckResult.notApplicable(
                                            "No GKE clusters in this project")));
                            return;
                        }

                        List<String> publicNodes = new ArrayList<>();
                        List<String> noWorkloadIdentity = new ArrayList<>();
                        List<String> noBinAuthz = new ArrayList<>();
                        List<String> noAuditLogs = new ArrayList<>();
                        List<String> notAutopilot = new ArrayList<>();
                        List<String> publicEndpoint = new ArrayList<>();
                        List<String> noNetworkPolicy = new ArrayList<>();

                        for (Cluster cluster : clusters) {
                            String name = cluster.getName() + " (" + cluster.getLocation() + ")";

                            var privateConfig = cluster.getPrivateClusterConfig();
                            boolean privateNodes = privateConfig != null
                                    && privateConfig.getEnablePrivateNodes();
                            if (!privateNodes) {
                                publicNodes.add(name + " has nodes with external IPs");
                            }
                            boolean privateEndpoint = privateConfig != null
                                    && privateConfig.getEnablePrivateEndpoint();
                            if (!privateEndpoint) {
                                publicEndpoint.add(name + " exposes a public control plane endpoint");
                            }

                            boolean workloadIdentity = cluster.hasWorkloadIdentityConfig()
                                    && !cluster.getWorkloadIdentityConfig()
                                            .getWorkloadPool().isBlank();
                            if (!workloadIdentity) {
                                noWorkloadIdentity.add(name + " has no Workload Identity pool, so "
                                        + "pods borrow the node service account");
                            }

                            boolean binAuthz = cluster.hasBinaryAuthorization()
                                    && (cluster.getBinaryAuthorization().getEnabled()
                                        || cluster.getBinaryAuthorization()
                                                .getEvaluationModeValue() > 1);
                            if (!binAuthz) {
                                noBinAuthz.add(name + " does not enforce Binary Authorization, so "
                                        + "unsigned images can be deployed");
                            }

                            // GKE audit logging rides on the cluster's logging config; the
                            // legacy "none" value means nothing reaches Cloud Logging.
                            String loggingService = cluster.getLoggingService();
                            boolean logging = loggingService != null
                                    && !loggingService.isBlank()
                                    && !"none".equalsIgnoreCase(loggingService);
                            if (!logging) {
                                noAuditLogs.add(name + " has cluster logging disabled");
                            }

                            boolean autopilot = cluster.hasAutopilot()
                                    && cluster.getAutopilot().getEnabled();
                            if (!autopilot) {
                                notAutopilot.add(name + " is a Standard cluster — node hardening "
                                        + "and patching are your responsibility");
                            }

                            boolean networkPolicy = cluster.hasNetworkPolicy()
                                    && cluster.getNetworkPolicy().getEnabled();
                            boolean intraNode = cluster.hasNetworkConfig()
                                    && cluster.getNetworkConfig().getEnableIntraNodeVisibility();
                            if (!networkPolicy || !intraNode) {
                                noNetworkPolicy.add(String.format(
                                        "%s is missing %s", name,
                                        !networkPolicy && !intraNode
                                                ? "network policy and intra-node visibility"
                                                : !networkPolicy
                                                        ? "network policy enforcement"
                                                        : "intra-node visibility"));
                            }
                        }

                        int total = clusters.size();
                        ctx.put("GCP-22", WarCheckResult.of(publicNodes,
                                        "GKE nodes have external IPs and are directly reachable",
                                        "All GKE nodes are private").withScanned(total));
                        ctx.put("GCP-51", WarCheckResult.of(noWorkloadIdentity,
                                        "Clusters cannot use Workload Identity, so pods inherit broad "
                                                + "node permissions",
                                        "All clusters use Workload Identity").withScanned(total));
                        ctx.put("GCP-52", WarCheckResult.of(noBinAuthz,
                                        "Binary Authorization is not enforced",
                                        "All clusters enforce Binary Authorization").withScanned(total));
                        ctx.put("GCP-53", WarCheckResult.of(noAuditLogs,
                                        "GKE cluster logging is disabled",
                                        "All clusters ship logs to Cloud Logging").withScanned(total));
                        ctx.put("GCP-55", WarCheckResult.of(publicEndpoint,
                                        "The GKE control plane is reachable from the internet",
                                        "All GKE control planes are private").withScanned(total));
                        ctx.put("GCP-56", WarCheckResult.of(noNetworkPolicy,
                                        "Pod-to-pod traffic is unrestricted, so a compromised pod can "
                                                + "move laterally",
                                        "Network policy and intra-node visibility are enabled")
                                .withScanned(total));

                        // Autopilot is a strong default rather than a requirement — a
                        // well-managed Standard cluster is a legitimate choice.
                        ctx.put("GCP-54", notAutopilot.isEmpty()
                                ? WarCheckResult.pass("All clusters run Autopilot")
                                : WarCheckResult.warn("Standard clusters require you to patch and "
                                        + "harden nodes yourself").withEvidence(notAutopilot)
                                        .withScanned(total));
                    }
                });
    }

    /** GCP-50: VM patching under OS Config management. */
    private void checkPatchDeployments(WarScanContext ctx, String project) {
        ctx.guard("osconfig.patchDeployments.list", List.of("GCP-50"), () -> {
            var clientOpt = clients.getOsConfigClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("OS Config client unavailable");
            }
            try (var osConfig = clientOpt.get()) {
                List<String> deployments = new ArrayList<>();
                for (var deployment : osConfig
                        .listPatchDeployments("projects/" + project).iterateAll()) {
                    deployments.add(deployment.getName());
                }
                ctx.put("GCP-50", deployments.isEmpty()
                        ? WarCheckResult.fail("No OS Config patch deployments — VM patching is "
                                + "manual, and an unpatched VM is how a miner gets its foothold")
                        : WarCheckResult.pass(deployments.size()
                                + " patch deployment(s) manage VM patching")
                                .withEvidence(deployments));
            }
        });
    }

    /** Instance metadata comes back as a key/value list rather than a map. */
    private static Map<String, String> metadataOf(Instance vm) {
        if (!vm.hasMetadata()) {
            return Map.of();
        }
        return vm.getMetadata().getItemsList().stream()
                .filter(i -> i.getKey() != null && i.getValue() != null)
                .collect(Collectors.toMap(
                        i -> i.getKey().toLowerCase(Locale.ROOT),
                        i -> i.getValue(),
                        (a, b) -> a));
    }

    private static String shortZone(String zoneUrl) {
        if (zoneUrl == null || zoneUrl.isBlank()) {
            return "unknown";
        }
        int slash = zoneUrl.lastIndexOf('/');
        return slash >= 0 ? zoneUrl.substring(slash + 1) : zoneUrl;
    }

    /** Exposed for the mining checks, which flag high-cost families without a guardrail. */
    static boolean isHighCostMachineType(String machineTypeUrl) {
        if (machineTypeUrl == null) {
            return false;
        }
        String type = machineTypeUrl.substring(machineTypeUrl.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);
        return HIGH_COST_FAMILIES.stream().anyMatch(type::startsWith);
    }
}
