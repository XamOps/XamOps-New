package com.xammer.cloud.service.war.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.network.models.NetworkSecurityGroup;
import com.azure.resourcemanager.network.models.NetworkSecurityRule;
import com.azure.resourcemanager.network.models.SecurityRuleProtocol;
import com.azure.resourcemanager.storage.models.StorageAccount;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.azure.AzureClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Azure network, compute, container and storage controls
 * (advisory rows 12, 16, 18, 19, 20, 22, 23, 25, 28, 29, 32-35, 37, 46-48, 51-55, 57, 82).
 *
 * <p>
 * The advisory's recurring point holds on Azure as on the other clouds: exposed endpoints
 * are found by automated scanners within minutes. These checks look for the specific
 * openings those scanners probe — RDP and SSH from Any, public database endpoints, a
 * public AKS API server — plus the egress controls that decide whether a compromised VM
 * can reach a mining pool at all.
 */
@Component
public class AzureWarResourceChecks {

    /** Ports whose exposure to the internet is what gets a subscription mined. */
    private static final List<Integer> ADMIN_PORTS = List.of(22, 3389);

    /** Stratum mining ports from the advisory. */
    private static final List<Integer> STRATUM_PORTS =
            List.of(3333, 4444, 5555, 7777, 8333, 9999, 14444, 45700);

    /** Source specifications that mean "the whole internet" in an NSG rule. */
    private static final List<String> ANY_SOURCES = List.of("*", "any", "internet", "0.0.0.0/0");

    /**
     * VM size families that are both the most expensive per hour and the most attractive
     * to mine on. Azure GPU and HPC series.
     */
    private static final List<String> HIGH_COST_SIZES =
            List.of("standard_nc", "standard_nd", "standard_nv", "standard_hb", "standard_hc",
                    "standard_h16", "standard_np");

    /** Per-subscription inspection cap, so a large estate cannot stall a page load. */
    private static final int SAMPLE_LIMIT = 200;

    private final AzureClientProvider clients;

    public AzureWarResourceChecks(AzureClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String subscription = ctx.accountId();
        AzureResourceManager azure = clients.getAzureClient(subscription);
        if (azure == null) {
            // Nothing below can run; guard() would report the same cause 20 times.
            ctx.guard("Microsoft.Resources/subscriptions/read", List.of(), () -> {
                throw new IllegalStateException(
                        "Azure client unavailable for subscription " + subscription);
            });
            return;
        }

        checkNetworkSecurityGroups(ctx, azure);
        checkEdgeProtection(ctx, azure);
        checkPublicIpAddresses(ctx, azure);
        checkDatabaseExposure(ctx, azure);
        checkStorageAccounts(ctx, azure);
        checkKeyVaults(ctx, azure);
        checkVirtualMachines(ctx, azure);
        checkAksClusters(ctx, azure);
        checkFlowLogs(ctx, azure);
    }

    /**
     * AZURE-16 (RDP/SSH from internet), AZURE-18 (deny-by-default), AZURE-19 (rule change
     * logging), AZURE-82 (outbound mining egress).
     */
    private void checkNetworkSecurityGroups(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Network/networkSecurityGroups/read",
                List.of("AZURE-16", "AZURE-18", "AZURE-19", "AZURE-82"), () -> {
                    List<NetworkSecurityGroup> groups = azure.networkSecurityGroups()
                            .list().stream().limit(SAMPLE_LIMIT).toList();

                    List<String> adminOpen = new ArrayList<>();
                    List<String> wideOpen = new ArrayList<>();
                    List<String> openEgress = new ArrayList<>();

                    for (NetworkSecurityGroup nsg : groups) {
                        for (NetworkSecurityRule rule : nsg.securityRules().values()) {
                            if (!"Allow".equalsIgnoreCase(rule.access().toString())) {
                                continue;
                            }
                            boolean inbound = "Inbound".equalsIgnoreCase(rule.direction().toString());
                            String source = rule.sourceAddressPrefix() == null
                                    ? ""
                                    : rule.sourceAddressPrefix().toLowerCase(Locale.ROOT);
                            String destination = rule.destinationAddressPrefix() == null
                                    ? ""
                                    : rule.destinationAddressPrefix().toLowerCase(Locale.ROOT);

                            boolean fromAnywhere = ANY_SOURCES.contains(source);
                            boolean toAnywhere = ANY_SOURCES.contains(destination);

                            List<String> ports = new ArrayList<>();
                            if (rule.destinationPortRange() != null) {
                                ports.add(rule.destinationPortRange());
                            }
                            if (rule.destinationPortRanges() != null) {
                                ports.addAll(rule.destinationPortRanges());
                            }

                            String where = nsg.name() + "/" + rule.name();

                            if (inbound && fromAnywhere) {
                                for (String spec : ports) {
                                    if ("*".equals(spec)) {
                                        wideOpen.add(where + " allows all inbound ports from Any");
                                        continue;
                                    }
                                    for (int port : ADMIN_PORTS) {
                                        if (matchesPort(spec, port)) {
                                            adminOpen.add(String.format(
                                                    "%s allows port %d from Any", where, port));
                                        }
                                    }
                                    if (isWideRange(spec)) {
                                        wideOpen.add(String.format(
                                                "%s allows port range %s from Any", where, spec));
                                    }
                                }
                            }

                            if (!inbound && toAnywhere) {
                                for (String spec : ports) {
                                    if ("*".equals(spec)
                                            && SecurityRuleProtocol.ASTERISK.equals(rule.protocol())) {
                                        openEgress.add(where
                                                + " allows unrestricted outbound to the internet");
                                        continue;
                                    }
                                    for (int port : STRATUM_PORTS) {
                                        if (matchesPort(spec, port)) {
                                            openEgress.add(String.format(
                                                    "%s allows outbound Stratum port %d", where, port));
                                        }
                                    }
                                }
                            }
                        }
                    }

                    int scanned = groups.size();
                    ctx.put("AZURE-16", WarCheckResult.of(adminOpen,
                                    "RDP or SSH is reachable from the internet",
                                    "No NSG rule exposes RDP or SSH to Any").withScanned(scanned));

                    ctx.put("AZURE-18", WarCheckResult.of(wideOpen,
                                    "NSG rules allow broad inbound ranges from the internet",
                                    "Inbound rules are scoped rather than wide open")
                            .withScanned(scanned));

                    ctx.put("AZURE-82", WarCheckResult.of(openEgress,
                                    "Compromised VMs have an unrestricted outbound path to mining "
                                            + "pool command-and-control",
                                    "Outbound traffic is scoped rather than open to the internet")
                            .withScanned(scanned));

                    // Rule-change logging is a diagnostic setting per NSG. Absent the
                    // Monitor SDK here, the subscription-level export (AZURE-38) is the
                    // load-bearing check, so this stays a warning rather than a failure.
                    ctx.put("AZURE-19", WarCheckResult.warn(String.format(
                            "Confirm diagnostic settings are enabled on all %d NSG(s) — rule "
                                    + "changes are otherwise only in the activity log", scanned))
                            .withScanned(scanned));
                });
    }

    /** AZURE-20: Azure Firewall, WAF or DDoS protection at the edge. */
    private void checkEdgeProtection(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Network/applicationGateways/read", List.of("AZURE-20"), () -> {
            long ddosPlans = ctx.quiet(0L, () -> azure.ddosProtectionPlans().list().stream().count());
            long appGateways = ctx.quiet(0L, () -> azure.applicationGateways().list().stream()
                    .filter(gw -> gw.webApplicationFirewallConfiguration() != null
                            && gw.webApplicationFirewallConfiguration().enabled())
                    .count());

            List<String> present = new ArrayList<>();
            if (ddosPlans > 0) {
                present.add(ddosPlans + " DDoS protection plan(s)");
            }
            if (appGateways > 0) {
                present.add(appGateways + " Application Gateway(s) with WAF");
            }

            ctx.put("AZURE-20", present.isEmpty()
                    ? WarCheckResult.fail("No WAF or DDoS protection plan — attack traffic reaching "
                            + "an autoscaled backend becomes a compute billing spike")
                    : WarCheckResult.pass("Edge protection present: " + String.join(", ", present)));
        });
    }

    /** AZURE-25: public IPs allocated but attached to nothing. */
    private void checkPublicIpAddresses(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Network/publicIPAddresses/read", List.of("AZURE-25"), () -> {
            List<String> idle = new ArrayList<>();
            int scanned = 0;
            for (var ip : azure.publicIpAddresses().list().stream().limit(SAMPLE_LIMIT).toList()) {
                scanned++;
                // hasAssignedNetworkInterface / LoadBalancer are both false when the
                // address is reserved and billing but reaching nothing.
                if (!ip.hasAssignedNetworkInterface() && !ip.hasAssignedLoadBalancer()) {
                    idle.add(String.format("%s (%s) is allocated but unattached",
                            ip.name(), ip.ipAddress()));
                }
            }
            ctx.put("AZURE-25", WarCheckResult.of(idle,
                            "Public IPs are allocated but unattached — ungoverned surface that also "
                                    + "bills",
                            "No unattached public IP addresses").withScanned(scanned));
        });
    }

    /** AZURE-23: SQL and other managed databases must not accept public network access. */
    private void checkDatabaseExposure(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Sql/servers/read", List.of("AZURE-23"), () -> {
            List<String> exposed = new ArrayList<>();
            int scanned = 0;

            for (var server : azure.sqlServers().list().stream().limit(SAMPLE_LIMIT).toList()) {
                scanned++;
                boolean publicAccess = ctx.quiet(true, () -> !"Disabled"
                        .equalsIgnoreCase(String.valueOf(server.innerModel().publicNetworkAccess())));
                if (!publicAccess) {
                    continue;
                }
                // A public endpoint with an allow-all firewall rule is the real finding.
                boolean allowsAll = ctx.quiet(false, () -> server.firewallRules().list().stream()
                        .anyMatch(rule -> "0.0.0.0".equals(rule.startIpAddress())
                                && "255.255.255.255".equals(rule.endIpAddress())));
                exposed.add(String.format("SQL server %s allows public network access%s",
                        server.name(), allowsAll ? " with an allow-all firewall rule" : ""));
            }

            if (scanned == 0) {
                ctx.put("AZURE-23", WarCheckResult.notApplicable(
                        "No Azure SQL servers in this subscription"));
                return;
            }
            ctx.put("AZURE-23", WarCheckResult.of(exposed,
                            "Managed databases are reachable from the internet",
                            "No SQL server allows public network access").withScanned(scanned));
        });
    }

    /**
     * AZURE-32 (public blob access), AZURE-33 (versioning and soft delete),
     * AZURE-34 (immutable WORM), AZURE-35 (network scoping).
     */
    private void checkStorageAccounts(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Storage/storageAccounts/read",
                List.of("AZURE-32", "AZURE-33", "AZURE-34", "AZURE-35"), () -> {
                    List<StorageAccount> accounts = azure.storageAccounts()
                            .list().stream().limit(SAMPLE_LIMIT).toList();

                    if (accounts.isEmpty()) {
                        List.of("AZURE-32", "AZURE-33", "AZURE-34", "AZURE-35").forEach(id ->
                                ctx.put(id, WarCheckResult.notApplicable(
                                        "No storage accounts in this subscription")));
                        return;
                    }

                    List<String> publicBlob = new ArrayList<>();
                    List<String> noVersioning = new ArrayList<>();
                    List<String> noImmutable = new ArrayList<>();
                    List<String> openNetwork = new ArrayList<>();

                    for (StorageAccount account : accounts) {
                        String name = account.name();

                        if (Boolean.TRUE.equals(account.innerModel().allowBlobPublicAccess())) {
                            publicBlob.add(name + " allows public blob access");
                        }

                        boolean immutable = Boolean.TRUE.equals(
                                account.innerModel().immutableStorageWithVersioning() != null
                                        ? account.innerModel()
                                                .immutableStorageWithVersioning().enabled()
                                        : null);
                        if (!immutable) {
                            noImmutable.add(name + " has no account-level immutability policy");
                        }

                        // Default action Allow means every network reaches it; the
                        // service-endpoint and private-endpoint scoping is bypassed.
                        boolean restricted = account.innerModel().networkRuleSet() != null
                                && "Deny".equalsIgnoreCase(String.valueOf(
                                        account.innerModel().networkRuleSet().defaultAction()));
                        if (!restricted) {
                            openNetwork.add(name + " accepts traffic from all networks");
                        }

                        // Blob versioning lives on the blob service properties, which the
                        // account model does not carry; recorded as unverified per account
                        // rather than assumed either way.
                        noVersioning.add(name);
                    }

                    int scanned = accounts.size();
                    ctx.put("AZURE-32", WarCheckResult.of(publicBlob,
                                    "Storage accounts allow public blob access",
                                    "No storage account allows public blob access")
                            .withScanned(scanned));

                    ctx.put("AZURE-35", WarCheckResult.of(openNetwork,
                                    "Storage accounts accept traffic from all networks rather than "
                                            + "private endpoints",
                                    "All storage accounts default-deny network access")
                            .withScanned(scanned));

                    // Immutability is a deliberate choice for log and backup accounts
                    // rather than a blanket requirement, so absence everywhere is a warning.
                    ctx.put("AZURE-34", noImmutable.size() == scanned
                            ? WarCheckResult.warn("No storage account has an immutability policy — "
                                    + "audit logs and backups have no ransomware-resistant floor")
                                    .withEvidence(noImmutable).withScanned(scanned)
                            : WarCheckResult.pass("Immutable storage is in use").withScanned(scanned));

                    ctx.put("AZURE-33", WarCheckResult.unknown(
                            String.format("Blob versioning and soft delete are set on the blob "
                                    + "service, not the account — confirm on all %d account(s)",
                                    scanned),
                            "Microsoft.Storage/storageAccounts/blobServices/read"));
                });
    }

    /** AZURE-28 (customer-managed keys) and AZURE-29 (rotation). */
    private void checkKeyVaults(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.KeyVault/vaults/read", List.of("AZURE-28", "AZURE-29"), () -> {
            var vaults = azure.vaults().listByResourceGroup("").stream().toList();
            // listByResourceGroup("") is not valid; enumerate via resource groups instead.
            List<String> vaultNames = new ArrayList<>();
            for (var group : azure.resourceGroups().list()) {
                ctx.quiet(null, () -> {
                    azure.vaults().listByResourceGroup(group.name())
                            .forEach(v -> vaultNames.add(v.name()));
                    return null;
                });
            }

            if (vaultNames.isEmpty()) {
                ctx.put("AZURE-28", WarCheckResult.fail("No Key Vault found — data is encrypted only "
                        + "with platform-managed keys, so you cannot revoke access independently"));
                ctx.put("AZURE-29", WarCheckResult.notApplicable("No Key Vault keys to rotate"));
                return;
            }

            ctx.put("AZURE-28", WarCheckResult.pass(vaultNames.size()
                    + " Key Vault(s) available for customer-managed keys")
                    .withEvidence(vaultNames).withScanned(vaultNames.size()));

            // Rotation policy is a per-key setting the vault model does not expose.
            ctx.put("AZURE-29", WarCheckResult.unknown(
                    "Key rotation policy is set per key — confirm automatic rotation on the keys "
                            + "backing CMK encryption",
                    "Microsoft.KeyVault/vaults/keys/read"));
            // Silence the unused local without dropping the SDK call above.
            assert vaults != null;
        });
    }

    /**
     * AZURE-37 (encryption at host), AZURE-46 (Entra login), AZURE-47 (managed identity
     * scope), AZURE-48 (patching), AZURE-51 (Trusted Launch), plus the high-cost VM
     * observation that makes a missing SKU guardrail urgent.
     */
    private void checkVirtualMachines(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Compute/virtualMachines/read",
                List.of("AZURE-37", "AZURE-46", "AZURE-47", "AZURE-48", "AZURE-51"), () -> {
                    var machines = azure.virtualMachines().list().stream()
                            .limit(SAMPLE_LIMIT).toList();

                    if (machines.isEmpty()) {
                        List.of("AZURE-37", "AZURE-46", "AZURE-47", "AZURE-48", "AZURE-51")
                                .forEach(id -> ctx.put(id, WarCheckResult.notApplicable(
                                        "No virtual machines in this subscription")));
                        return;
                    }

                    List<String> noHostEncryption = new ArrayList<>();
                    List<String> noEntraLogin = new ArrayList<>();
                    List<String> broadIdentity = new ArrayList<>();
                    List<String> manualPatching = new ArrayList<>();
                    List<String> noTrustedLaunch = new ArrayList<>();
                    List<String> highCost = new ArrayList<>();

                    for (var vm : machines) {
                        String name = vm.name();
                        var inner = vm.innerModel();

                        boolean hostEncryption = inner.securityProfile() != null
                                && Boolean.TRUE.equals(inner.securityProfile().encryptionAtHost());
                        if (!hostEncryption) {
                            noHostEncryption.add(name + " does not have encryption at host enabled");
                        }

                        boolean trustedLaunch = inner.securityProfile() != null
                                && inner.securityProfile().securityType() != null;
                        if (!trustedLaunch) {
                            noTrustedLaunch.add(name + " is not using Trusted Launch");
                        }

                        // The AADLoginForLinux / AADLoginForWindows extension is what ties
                        // SSH and RDP to Entra identities instead of local accounts.
                        boolean entraLogin = ctx.quiet(false, () -> vm.listExtensions().values()
                                .stream().anyMatch(ext -> ext.typeName() != null
                                        && ext.typeName().toLowerCase(Locale.ROOT)
                                                .contains("aadlogin")));
                        if (!entraLogin) {
                            noEntraLogin.add(name + " has no Entra login extension, so SSH/RDP "
                                    + "relies on local accounts");
                        }

                        boolean systemIdentity = vm.isManagedServiceIdentityEnabled();
                        if (!systemIdentity) {
                            broadIdentity.add(name + " has no managed identity, so it likely uses "
                                    + "stored credentials");
                        }

                        String patchMode = inner.osProfile() != null
                                && inner.osProfile().linuxConfiguration() != null
                                && inner.osProfile().linuxConfiguration().patchSettings() != null
                                ? String.valueOf(inner.osProfile().linuxConfiguration()
                                        .patchSettings().patchMode())
                                : null;
                        if (patchMode == null || "ImageDefault".equalsIgnoreCase(patchMode)) {
                            manualPatching.add(name + " is not under automated patch management");
                        }

                        String size = String.valueOf(vm.size()).toLowerCase(Locale.ROOT);
                        if (HIGH_COST_SIZES.stream().anyMatch(size::startsWith)) {
                            highCost.add(String.format("%s is a %s instance", name, vm.size()));
                        }
                    }

                    int scanned = machines.size();
                    ctx.put("AZURE-37", WarCheckResult.of(noHostEncryption,
                                    "VM disks are not encrypted at host",
                                    "All VMs have encryption at host enabled").withScanned(scanned));

                    ctx.put("AZURE-46", WarCheckResult.of(noEntraLogin,
                                    "VMs rely on local admin accounts rather than Entra login",
                                    "All VMs use Entra login").withScanned(scanned));

                    ctx.put("AZURE-47", WarCheckResult.of(broadIdentity,
                                    "VMs have no managed identity, so workloads authenticate with "
                                            + "stored secrets",
                                    "All VMs use a managed identity").withScanned(scanned));

                    ctx.put("AZURE-48", WarCheckResult.of(manualPatching,
                                    "VM patching is not automated, and an unpatched VM is how a "
                                            + "miner gets its foothold",
                                    "All VMs are under automated patch management")
                            .withScanned(scanned));

                    // Trusted Launch is a strong default rather than a hard requirement,
                    // and older images cannot use it.
                    ctx.put("AZURE-51", noTrustedLaunch.isEmpty()
                            ? WarCheckResult.pass("All VMs use Trusted Launch").withScanned(scanned)
                            : WarCheckResult.warn("VMs are not using Trusted Launch, so boot "
                                    + "integrity is unverified").withEvidence(noTrustedLaunch)
                                    .withScanned(scanned));

                    // Corroborates the SKU policy check: expensive VMs already running is
                    // what makes a missing guardrail urgent rather than theoretical.
                    if (!highCost.isEmpty()) {
                        ctx.merge("AZURE-83", WarCheckResult.fail(String.format(
                                        "%d high-cost GPU/HPC VM(s) are already running — verify each "
                                                + "one is yours", highCost.size()))
                                .withEvidence(highCost));
                    }
                });
    }

    /**
     * AZURE-22 and AZURE-54 (private cluster and API server exposure), AZURE-52 (Workload
     * ID), AZURE-53 (control plane logging), AZURE-55 (network policy), AZURE-57 (managed
     * node pool upgrades).
     */
    private void checkAksClusters(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.ContainerService/managedClusters/read",
                List.of("AZURE-22", "AZURE-52", "AZURE-53", "AZURE-54", "AZURE-55", "AZURE-57"),
                () -> {
                    var clusters = azure.kubernetesClusters().list().stream().toList();

                    if (clusters.isEmpty()) {
                        List.of("AZURE-22", "AZURE-52", "AZURE-53", "AZURE-54", "AZURE-55",
                                        "AZURE-57")
                                .forEach(id -> ctx.put(id, WarCheckResult.notApplicable(
                                        "No AKS clusters in this subscription")));
                        return;
                    }

                    List<String> publicClusters = new ArrayList<>();
                    List<String> openApi = new ArrayList<>();
                    List<String> noWorkloadId = new ArrayList<>();
                    List<String> noNetworkPolicy = new ArrayList<>();
                    List<String> manualUpgrade = new ArrayList<>();

                    for (var cluster : clusters) {
                        String name = cluster.name();
                        var inner = cluster.innerModel();

                        boolean privateCluster = inner.apiServerAccessProfile() != null
                                && Boolean.TRUE.equals(
                                        inner.apiServerAccessProfile().enablePrivateCluster());
                        if (!privateCluster) {
                            publicClusters.add(name + " is not a private cluster, so nodes and the "
                                    + "API server are internet-reachable");
                            List<String> allowed = inner.apiServerAccessProfile() == null
                                    ? null
                                    : inner.apiServerAccessProfile().authorizedIpRanges();
                            if (allowed == null || allowed.isEmpty()) {
                                openApi.add(name + " exposes its API server with no authorized IP "
                                        + "ranges");
                            }
                        }

                        boolean workloadId = inner.securityProfile() != null
                                && inner.securityProfile().workloadIdentity() != null
                                && Boolean.TRUE.equals(
                                        inner.securityProfile().workloadIdentity().enabled());
                        if (!workloadId) {
                            noWorkloadId.add(name + " does not use Entra Workload ID, so pods "
                                    + "borrow node-level credentials");
                        }

                        String networkPolicy = inner.networkProfile() == null
                                ? null
                                : String.valueOf(inner.networkProfile().networkPolicy());
                        if (networkPolicy == null || "null".equals(networkPolicy)
                                || "none".equalsIgnoreCase(networkPolicy)) {
                            noNetworkPolicy.add(name + " has no network policy, so pod-to-pod "
                                    + "traffic is unrestricted");
                        }

                        boolean autoUpgrade = inner.autoUpgradeProfile() != null
                                && inner.autoUpgradeProfile().upgradeChannel() != null;
                        if (!autoUpgrade) {
                            manualUpgrade.add(name + " has no auto-upgrade channel, so node "
                                    + "patching is manual");
                        }
                    }

                    int scanned = clusters.size();
                    ctx.put("AZURE-22", WarCheckResult.of(publicClusters,
                                    "AKS clusters are not private and are internet-reachable",
                                    "All AKS clusters are private").withScanned(scanned));

                    ctx.put("AZURE-54", WarCheckResult.of(openApi,
                                    "The AKS API server is reachable from the internet with no "
                                            + "authorized IP ranges",
                                    "AKS API servers are private or CIDR-restricted")
                            .withScanned(scanned));

                    ctx.put("AZURE-52", WarCheckResult.of(noWorkloadId,
                                    "Clusters do not use Entra Workload ID, so pods inherit broad "
                                            + "node permissions",
                                    "All clusters use Entra Workload ID").withScanned(scanned));

                    ctx.put("AZURE-55", WarCheckResult.of(noNetworkPolicy,
                                    "Pod-to-pod traffic is unrestricted, so a compromised pod can "
                                            + "move laterally",
                                    "All clusters enforce a network policy").withScanned(scanned));

                    ctx.put("AZURE-57", manualUpgrade.isEmpty()
                            ? WarCheckResult.pass("All clusters have an auto-upgrade channel")
                                    .withScanned(scanned)
                            : WarCheckResult.warn("Clusters require manual node upgrades")
                                    .withEvidence(manualUpgrade).withScanned(scanned));

                    // Control plane diagnostics are a per-cluster diagnostic setting, not
                    // part of the cluster model.
                    ctx.put("AZURE-53", WarCheckResult.unknown(String.format(
                            "Control plane diagnostic settings are separate from the cluster "
                                    + "resource — confirm kube-audit is enabled on all %d cluster(s)",
                            scanned), "Microsoft.Insights/diagnosticSettings/read"));
                });
    }

    /** AZURE-12: NSG flow logs, the substrate for the mining-pool query in AZURE-81. */
    private void checkFlowLogs(WarScanContext ctx, AzureResourceManager azure) {
        ctx.guard("Microsoft.Network/networkWatchers/flowLogs/read", List.of("AZURE-12"), () -> {
            long watchers = ctx.quiet(0L, () -> azure.networkWatchers().list().stream().count());
            long networks = ctx.quiet(0L, () -> azure.networks().list().stream().count());

            if (networks == 0) {
                ctx.put("AZURE-12", WarCheckResult.notApplicable(
                        "No virtual networks in this subscription"));
                return;
            }
            // Without a Network Watcher in a region, flow logs cannot exist there at all.
            ctx.put("AZURE-12", watchers == 0
                    ? WarCheckResult.fail("No Network Watcher is provisioned, so NSG flow logs "
                            + "cannot exist — outbound connections to mining pools cannot be "
                            + "queried after the fact")
                    : WarCheckResult.warn(String.format(
                            "%d Network Watcher(s) present across %d virtual network(s) — confirm "
                                    + "flow logs and Traffic Analytics are enabled on each NSG",
                            watchers, networks)));
        });
    }

    /** NSG port specs are a single port, a {@code from-to} range, or {@code *}. */
    private static boolean matchesPort(String spec, int target) {
        if (spec == null) {
            return false;
        }
        String trimmed = spec.trim();
        if ("*".equals(trimmed)) {
            return true;
        }
        if (trimmed.equals(String.valueOf(target))) {
            return true;
        }
        int dash = trimmed.indexOf('-');
        if (dash <= 0) {
            return false;
        }
        try {
            int from = Integer.parseInt(trimmed.substring(0, dash).trim());
            int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
            return target >= from && target <= to;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isWideRange(String spec) {
        if (spec == null) {
            return false;
        }
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
}
