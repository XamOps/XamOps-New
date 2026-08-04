package com.xammer.cloud.service.war.gcp;

import com.google.cloud.compute.v1.Instance;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageRoles;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GCP data protection, detection and crypto-mining controls
 * (advisory rows 28-29, 32-35, 37-38, 40-41, 43-44, 89-91, 93, 96).
 *
 * <p>
 * The mining half of this class asks a different question from the rest of WAR: not
 * "could someone get in?" but "are they already here, and what stops the bill from
 * growing?" SCC's Event Threat Detection carries GCP's cryptomining finding category,
 * which is the most direct signal available that a project is mining for someone else.
 */
@Component
public class GcpWarDetectionChecks {

    /**
     * Per-project bucket inspection cap. Each bucket costs extra metadata reads, so a
     * project with thousands would otherwise turn a page load into a rate-limit event.
     * The sample still proves a systemic gap.
     */
    private static final int BUCKET_SAMPLE_LIMIT = 100;

    /** SCC finding categories that mean mining, in GCP's naming. */
    private static final List<String> MINING_CATEGORIES =
            List.of("cryptomining", "crypto_mining", "malware_bad_domain", "bad_ip");

    private final GcpClientProvider clients;

    public GcpWarDetectionChecks(GcpClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String project = ctx.accountId();
        checkBuckets(ctx, project);
        checkKms(ctx, project);
        checkAuditSinks(ctx, project);
        checkAlertPolicies(ctx, project);
        checkSecurityCommandCenter(ctx, project);
        checkSustainedUtilisation(ctx, project);
        checkBudgets(ctx, project);
    }

    /**
     * GCP-32 (public buckets), GCP-33 (uniform bucket-level access),
     * GCP-34 (versioning), GCP-35 (retention).
     */
    private void checkBuckets(WarScanContext ctx, String project) {
        ctx.guard("storage.buckets.list",
                List.of("GCP-32", "GCP-33", "GCP-34", "GCP-35"), () -> {
                    var clientOpt = clients.getStorageClient(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("Cloud Storage client unavailable");
                    }
                    Storage storage = clientOpt.get();

                    List<Bucket> buckets = new ArrayList<>();
                    for (Bucket bucket : storage.list().iterateAll()) {
                        buckets.add(bucket);
                        if (buckets.size() >= BUCKET_SAMPLE_LIMIT) {
                            break;
                        }
                    }

                    if (buckets.isEmpty()) {
                        List.of("GCP-32", "GCP-33", "GCP-34", "GCP-35").forEach(id ->
                                ctx.put(id, WarCheckResult.notApplicable(
                                        "No Cloud Storage buckets in this project")));
                        return;
                    }

                    List<String> publicBuckets = new ArrayList<>();
                    List<String> noUbla = new ArrayList<>();
                    List<String> unversioned = new ArrayList<>();
                    List<String> noRetention = new ArrayList<>();

                    for (Bucket bucket : buckets) {
                        String name = bucket.getName();

                        boolean ubla = bucket.getIamConfiguration() != null
                                && Boolean.TRUE.equals(
                                        bucket.getIamConfiguration().isUniformBucketLevelAccessEnabled());
                        if (!ubla) {
                            noUbla.add(name + " has uniform bucket-level access off, so legacy ACLs "
                                    + "can still grant access outside IAM");
                        }

                        // allUsers / allAuthenticatedUsers on a bucket is the single most
                        // common cause of accidental data exposure on GCP.
                        var members = ctx.quiet(List.<String>of(), () -> {
                            var policy = storage.getIamPolicy(name);
                            List<String> found = new ArrayList<>();
                            for (var role : List.of(
                                    StorageRoles.legacyObjectReader(),
                                    StorageRoles.legacyBucketReader(),
                                    StorageRoles.objectViewer())) {
                                var bound = policy.getBindings().get(role);
                                if (bound == null) {
                                    continue;
                                }
                                if (bound.contains(com.google.cloud.Identity.allUsers())) {
                                    found.add("allUsers via " + role.getValue());
                                }
                                if (bound.contains(
                                        com.google.cloud.Identity.allAuthenticatedUsers())) {
                                    found.add("allAuthenticatedUsers via " + role.getValue());
                                }
                            }
                            return found;
                        });
                        members.forEach(m -> publicBuckets.add(name + " grants " + m));

                        if (!Boolean.TRUE.equals(bucket.versioningEnabled())) {
                            unversioned.add(name + " has versioning off, so there is no recovery "
                                    + "from overwrite or ransomware");
                        }

                        if (bucket.getRetentionPeriodDuration() == null) {
                            noRetention.add(name + " has no retention policy");
                        }
                    }

                    int scanned = buckets.size();
                    ctx.put("GCP-32", WarCheckResult.of(publicBuckets,
                                    "Buckets grant access to allUsers or allAuthenticatedUsers",
                                    "No bucket is publicly readable").withScanned(scanned));

                    ctx.put("GCP-33", WarCheckResult.of(noUbla,
                                    "Buckets still allow legacy ACLs alongside IAM",
                                    "All sampled buckets enforce uniform bucket-level access")
                            .withScanned(scanned));

                    ctx.put("GCP-34", WarCheckResult.of(unversioned,
                                    "Buckets have object versioning disabled",
                                    "All sampled buckets have versioning enabled")
                            .withScanned(scanned));

                    // Retention is a deliberate choice for log and backup buckets rather
                    // than a blanket requirement, so its absence everywhere is a warning.
                    ctx.put("GCP-35", noRetention.size() == scanned
                            ? WarCheckResult.warn("No bucket has a retention policy — audit logs and "
                                    + "backups have no ransomware-resistant floor")
                                    .withEvidence(noRetention).withScanned(scanned)
                            : WarCheckResult.pass("Retention policies are in use")
                                    .withScanned(scanned));
                });
    }

    /** GCP-28 (customer-managed keys exist) and GCP-29 (rotation). */
    private void checkKms(WarScanContext ctx, String project) {
        ctx.guard("cloudkms.cryptoKeys.list", List.of("GCP-28", "GCP-29"), () -> {
            var clientOpt = clients.getKeyManagementServiceClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Cloud KMS client unavailable");
            }

            try (var kms = clientOpt.get()) {
                List<String> keys = new ArrayList<>();
                List<String> unrotated = new ArrayList<>();

                // Key rings are per-location; "-" wildcards across all of them.
                var locationName = com.google.cloud.kms.v1.LocationName.of(project, "-");
                for (var keyRing : kms.listKeyRings(locationName).iterateAll()) {
                    for (CryptoKey key : kms.listCryptoKeys(keyRing.getName()).iterateAll()) {
                        keys.add(key.getName());
                        // A symmetric key with no rotation period never rotates.
                        boolean rotates = key.hasRotationPeriod()
                                && key.getRotationPeriod().getSeconds() > 0;
                        if (!rotates) {
                            unrotated.add(shortKeyName(key.getName())
                                    + " has no automatic rotation period");
                        }
                    }
                }

                if (keys.isEmpty()) {
                    ctx.put("GCP-28", WarCheckResult.fail("No customer-managed KMS keys — data is "
                            + "encrypted only with Google-managed keys, so you cannot revoke access "
                            + "independently"));
                    ctx.put("GCP-29", WarCheckResult.notApplicable(
                            "No customer-managed KMS keys to rotate"));
                    return;
                }

                ctx.put("GCP-28", WarCheckResult.pass(keys.size()
                        + " customer-managed KMS key(s) available for CMEK").withScanned(keys.size()));

                ctx.put("GCP-29", WarCheckResult.of(unrotated,
                                "Customer-managed keys are not rotating automatically",
                                "All customer-managed keys rotate automatically")
                        .withScanned(keys.size()));
            }
        });
    }

    /** GCP-37 (data access audit logs) and GCP-38 (centralised export sink). */
    private void checkAuditSinks(WarScanContext ctx, String project) {
        ctx.guard("logging.sinks.list", List.of("GCP-37", "GCP-38"), () -> {
            var clientOpt = clients.getConfigClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Cloud Logging config client unavailable");
            }

            try (var logging = clientOpt.get()) {
                List<String> sinks = new ArrayList<>();
                for (var sink : logging.listSinks("projects/" + project).iterateAll()) {
                    sinks.add(String.format("%s → %s", sink.getName(), sink.getDestination()));
                }

                // _Default and _Required always exist and stay inside the project, so a
                // real export means a sink pointing at storage, BigQuery or Pub/Sub.
                boolean exported = sinks.stream().anyMatch(s -> {
                    String lower = s.toLowerCase(Locale.ROOT);
                    return lower.contains("storage.googleapis.com")
                            || lower.contains("bigquery.googleapis.com")
                            || lower.contains("pubsub.googleapis.com");
                });

                ctx.put("GCP-38", exported
                        ? WarCheckResult.pass("Logs are exported to a centralised sink")
                                .withEvidence(sinks)
                        : WarCheckResult.fail("No log export sink — logs stay in the project and are "
                                + "lost with it").withEvidence(sinks));

                // Data-access logging is decided by the project IAM policy's audit configs,
                // which the identity checks already read; here it is only corroborated.
                ctx.put("GCP-37", exported
                        ? WarCheckResult.warn("Log export exists — confirm Data Access audit logs are "
                                + "explicitly enabled, they are off by default on GCP")
                        : WarCheckResult.fail("Without an export sink, Data Access audit logs cannot "
                                + "be retained for investigation"));
            }
        });
    }

    /** GCP-40 (log-based metric alerts) and GCP-90 (CPU/GPU utilisation alerts). */
    private void checkAlertPolicies(WarScanContext ctx, String project) {
        ctx.guard("monitoring.alertPolicies.list", List.of("GCP-40", "GCP-90"), () -> {
            var clientOpt = clients.getAlertPolicyClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Cloud Monitoring alert policy client unavailable");
            }

            try (var alertClient = clientOpt.get()) {
                List<String> policies = new ArrayList<>();
                boolean cpuAlert = false;
                boolean iamAlert = false;

                for (var policy : alertClient
                        .listAlertPolicies("projects/" + project).iterateAll()) {
                    if (!policy.getEnabled().getValue()) {
                        continue;
                    }
                    String blob = (policy.getDisplayName() + " "
                            + policy.getConditionsList()).toLowerCase(Locale.ROOT);
                    policies.add(policy.getDisplayName());
                    if (blob.contains("cpu") || blob.contains("gpu")
                            || blob.contains("utilization") || blob.contains("utilisation")) {
                        cpuAlert = true;
                    }
                    if (blob.contains("iam") || blob.contains("firewall")
                            || blob.contains("owner") || blob.contains("audit")) {
                        iamAlert = true;
                    }
                }

                ctx.put("GCP-40", iamAlert
                        ? WarCheckResult.pass("Log-based alerts cover IAM, firewall or audit events")
                        : WarCheckResult.fail(policies.isEmpty()
                                ? "No enabled alert policies — nothing will page anyone while an "
                                        + "attack is in progress"
                                : String.format("%d alert policies exist but none watch IAM, "
                                        + "firewall or audit events", policies.size())));

                ctx.put("GCP-90", cpuAlert
                        ? WarCheckResult.pass("Alert policies watch CPU or GPU utilisation")
                        : WarCheckResult.fail("No alert policy watches sustained CPU or GPU "
                                + "utilisation — the flat near-maximum load that mining produces "
                                + "would go unreported"));
            }
        });
    }

    /**
     * GCP-41 (SCC enabled), GCP-43 (Event Threat Detection), GCP-44 (Container Threat
     * Detection), GCP-89 (open cryptomining findings), GCP-91 (flow-log analysis),
     * GCP-95 is left manual because it needs the response pipeline, not just SCC.
     */
    private void checkSecurityCommandCenter(WarScanContext ctx, String project) {
        ctx.guard("securitycenter.findings.list",
                List.of("GCP-41", "GCP-43", "GCP-44", "GCP-89", "GCP-91"), () -> {
                    var clientOpt = clients.getSecurityCenterV2Client(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("Security Command Center client unavailable");
                    }

                    try (var scc = clientOpt.get()) {
                        List<String> mining = new ArrayList<>();
                        List<String> categories = new ArrayList<>();
                        int findings = 0;

                        String parent = String.format("projects/%s/sources/-/locations/global", project);
                        for (var result : scc.listFindings(parent).iterateAll()) {
                            var finding = result.getFinding();
                            if (!"ACTIVE".equalsIgnoreCase(finding.getState().name())) {
                                continue;
                            }
                            findings++;
                            String category = finding.getCategory();
                            categories.add(category);
                            String lower = category.toLowerCase(Locale.ROOT);
                            if (MINING_CATEGORIES.stream().anyMatch(lower::contains)) {
                                mining.add(String.format("%s on %s (severity %s)",
                                        category, finding.getResourceName(),
                                        finding.getSeverity().name()));
                            }
                            if (findings >= 500) {
                                break;
                            }
                        }

                        // Reaching SCC at all proves it is provisioned for the project.
                        ctx.put("GCP-41", WarCheckResult.pass(
                                "Security Command Center is reachable and returning findings "
                                        + "(" + findings + " active)"));

                        boolean threatCategories = categories.stream()
                                .anyMatch(c -> c.toLowerCase(Locale.ROOT).contains("threat")
                                        || MINING_CATEGORIES.stream()
                                                .anyMatch(c.toLowerCase(Locale.ROOT)::contains));
                        ctx.put("GCP-43", threatCategories
                                ? WarCheckResult.pass("Event Threat Detection findings are present")
                                : WarCheckResult.warn("No Event Threat Detection findings seen. This "
                                        + "is either a clean project or the Premium tier detector is "
                                        + "not enabled — confirm the SCC tier"));

                        boolean containerFindings = categories.stream()
                                .anyMatch(c -> c.toLowerCase(Locale.ROOT).contains("container"));
                        ctx.put("GCP-44", containerFindings
                                ? WarCheckResult.pass("Container Threat Detection findings are present")
                                : WarCheckResult.warn("No Container Threat Detection findings seen — "
                                        + "confirm the detector is enabled for your GKE clusters"));

                        ctx.put("GCP-89", mining.isEmpty()
                                ? WarCheckResult.pass("No open cryptomining findings in SCC")
                                : WarCheckResult.fail(String.format(
                                                "SCC has %d open mining-related finding(s) — treat "
                                                        + "this as an active incident, not a "
                                                        + "configuration gap", mining.size()))
                                        .withEvidence(mining));

                        // 91 is about being able to query flow logs after the fact. The
                        // subnet check owns whether they exist; here we note the analysis path.
                        ctx.put("GCP-91", WarCheckResult.warn(
                                "Confirm VPC flow logs are exported to BigQuery and queried for "
                                        + "Stratum ports — SCC alone does not cover pool traffic "
                                        + "analysis"));
                    }
                });
    }

    /**
     * GCP-93 corroboration: VMs already running on high-cost machine families.
     *
     * <p>
     * The org-policy check owns whether a guardrail exists. This adds the observation
     * that expensive machines are already running, which is what makes a missing
     * guardrail urgent rather than theoretical.
     */
    private void checkSustainedUtilisation(WarScanContext ctx, String project) {
        ctx.guardRegion("global", "compute.instances.aggregatedList", List.of(), () -> {
            var clientOpt = clients.getInstancesClient(project);
            if (clientOpt.isEmpty()) {
                return;
            }
            try (var instances = clientOpt.get()) {
                List<String> highCost = new ArrayList<>();
                for (var entry : instances.aggregatedList(project).iterateAll()) {
                    for (Instance vm : entry.getValue().getInstancesList()) {
                        if (!"RUNNING".equalsIgnoreCase(vm.getStatus())) {
                            continue;
                        }
                        if (GcpWarComputeChecks.isHighCostMachineType(vm.getMachineType())) {
                            highCost.add(String.format("%s is a %s instance", vm.getName(),
                                    vm.getMachineType()
                                            .substring(vm.getMachineType().lastIndexOf('/') + 1)));
                        }
                    }
                }
                if (!highCost.isEmpty()) {
                    ctx.merge("GCP-93", WarCheckResult.fail(String.format(
                                    "%d high-cost GPU/accelerator instance(s) are already running — "
                                            + "verify each one is yours", highCost.size()))
                            .withEvidence(highCost));
                }
            }
        });
    }

    /** GCP-96: budget and billing alerts, the last line of defence on spend. */
    private void checkBudgets(WarScanContext ctx, String project) {
        ctx.guard("billingbudgets.budgets.list", List.of("GCP-96"), () -> {
            String billingAccount = clients.getBillingAccountIdForProject(project).orElse(null);
            if (billingAccount == null) {
                throw new IllegalStateException(
                        "No billing account linked to project " + project);
            }
            var clientOpt = clients.getBudgetServiceClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Billing Budgets client unavailable");
            }

            try (var budgets = clientOpt.get()) {
                List<String> names = new ArrayList<>();
                for (var budget : budgets
                        .listBudgets("billingAccounts/" + billingAccount).iterateAll()) {
                    names.add(budget.getDisplayName());
                }
                ctx.put("GCP-96", names.isEmpty()
                        ? WarCheckResult.fail("No budgets configured — mining can run silently until "
                                + "the invoice arrives, and by then the charges are processed")
                        : WarCheckResult.pass(names.size() + " budget(s) watching spend")
                                .withEvidence(names));
            }
        });
    }

    private static String shortKeyName(String fullName) {
        if (fullName == null) {
            return "unknown";
        }
        int idx = fullName.indexOf("/keyRings/");
        return idx > 0 ? fullName.substring(idx + 1) : fullName;
    }
}
