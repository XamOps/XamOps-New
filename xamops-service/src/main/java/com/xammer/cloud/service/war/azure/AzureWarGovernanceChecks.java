package com.xammer.cloud.service.war.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.azure.AzureClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Azure identity, policy and governance controls
 * (advisory rows 1, 5, 9, 21, 38, 41, 42, 43, 59, 60, 61, 79, 83, 84, 86).
 *
 * <p>
 * Azure Policy is the tenant's structural guardrail — the equivalent of an AWS SCP or a
 * GCP organization policy constraint. Without the deny-public-IP and restrict-SKU
 * assignments, a compromised credential can stand up a GPU fleet with public addresses
 * and nothing prevents it, which is why several mining controls resolve to a policy check
 * rather than a resource scan.
 */
@Component
public class AzureWarGovernanceChecks {

    private static final String API_POLICY = "?api-version=2022-06-01";
    private static final String API_DEFENDER = "?api-version=2023-01-01";
    private static final String API_ALERTS = "?api-version=2018-03-01";
    private static final String API_DIAGNOSTIC = "?api-version=2021-05-01-preview";

    /**
     * Defender for Cloud plans that matter for the mining and ransomware paths.
     * VirtualMachines carries the cryptomining detections; the rest close the
     * lateral-movement and data routes.
     */
    private static final List<String> DEFENDER_PLANS = List.of(
            "VirtualMachines", "StorageAccounts", "Containers", "SqlServers", "KeyVaults");

    /** Policy definition fragments that indicate the guardrail is present. */
    private static final List<String> POLICY_PUBLIC_IP =
            List.of("publicipaddress", "public ip", "networkinterface");
    private static final List<String> POLICY_SKU =
            List.of("vmsize", "sku", "virtualmachinesize");
    private static final List<String> POLICY_TAG = List.of("tag");

    private final AzureClientProvider clients;
    private final AzureWarRest rest;

    public AzureWarGovernanceChecks(AzureClientProvider clients, AzureWarRest rest) {
        this.clients = clients;
        this.rest = rest;
    }

    public void run(WarScanContext ctx) {
        String subscription = ctx.accountId();
        checkRoleAssignments(ctx, subscription);
        checkPolicyAssignments(ctx, subscription);
        checkDefenderPlans(ctx, subscription);
        checkAlertRules(ctx, subscription);
        checkActivityLogExport(ctx, subscription);
        checkResourceInventory(ctx, subscription);
        checkBudgets(ctx, subscription);
        checkCryptominingAlerts(ctx, subscription);
    }

    /** AZURE-5 (Owner/Contributor sprawl) and AZURE-9 (Owner on managed identities). */
    private void checkRoleAssignments(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Authorization/roleAssignments/read",
                List.of("AZURE-5", "AZURE-9"), () -> {
                    // Role definitions come back as ids, so names are resolved separately.
                    List<JsonNode> assignments = rest.listValues(subscription,
                            "/providers/Microsoft.Authorization/roleAssignments"
                                    + "?api-version=2022-04-01");

                    List<String> privileged = new ArrayList<>();
                    List<String> identityOwners = new ArrayList<>();

                    for (JsonNode assignment : assignments) {
                        JsonNode props = assignment.path("properties");
                        String roleId = props.path("roleDefinitionId").asText("");
                        String principalType = props.path("principalType").asText("");
                        String principalId = props.path("principalId").asText("");
                        String scope = props.path("scope").asText("");

                        String role = roleName(roleId);
                        boolean isPrivileged = "Owner".equals(role) || "Contributor".equals(role)
                                || "User Access Administrator".equals(role);
                        if (!isPrivileged) {
                            continue;
                        }

                        privileged.add(String.format("%s granted to %s %s at %s",
                                role, principalType.isBlank() ? "principal" : principalType,
                                shortId(principalId), shortScope(scope)));

                        // A managed identity or service principal with Owner is a
                        // non-human credential that can take the whole subscription.
                        boolean nonHuman = "ServicePrincipal".equalsIgnoreCase(principalType);
                        boolean subscriptionScope = scope.equals("/subscriptions/" + subscription);
                        if (nonHuman && "Owner".equals(role) && subscriptionScope) {
                            identityOwners.add(String.format(
                                    "Service principal %s holds subscription-level Owner",
                                    shortId(principalId)));
                        }
                    }

                    ctx.put("AZURE-5", WarCheckResult.of(privileged,
                                    "Owner, Contributor or User Access Administrator is granted "
                                            + "broadly rather than scoped",
                                    "No broad privileged role assignments found")
                            .withScanned(assignments.size()));

                    ctx.put("AZURE-9", WarCheckResult.of(identityOwners,
                                    "Service principals hold subscription-level Owner",
                                    "No service principal holds subscription-level Owner")
                            .withScanned(assignments.size()));
                });
    }

    /**
     * AZURE-1 (policy guardrails), AZURE-21 (deny public IP), AZURE-60 (policy
     * initiatives), AZURE-61 (mandatory tagging), AZURE-83 (restrict SKUs),
     * AZURE-84 (deny public IP as mining C2 path).
     *
     * <p>
     * One assignment listing answers all six.
     */
    private void checkPolicyAssignments(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Authorization/policyAssignments/read",
                List.of("AZURE-1", "AZURE-21", "AZURE-60", "AZURE-61", "AZURE-83", "AZURE-84"),
                () -> {
                    List<JsonNode> assignments = rest.listValues(subscription,
                            "/providers/Microsoft.Authorization/policyAssignments" + API_POLICY);

                    List<String> names = new ArrayList<>();
                    int initiatives = 0;
                    boolean denyPublicIp = false;
                    boolean restrictSku = false;
                    boolean tagPolicy = false;

                    for (JsonNode assignment : assignments) {
                        JsonNode props = assignment.path("properties");
                        String display = props.path("displayName").asText(
                                assignment.path("name").asText(""));
                        String definitionId = props.path("policyDefinitionId").asText("")
                                .toLowerCase(Locale.ROOT);
                        names.add(display);

                        if (definitionId.contains("policysetdefinitions")) {
                            initiatives++;
                        }

                        String haystack = (display + " " + definitionId)
                                .toLowerCase(Locale.ROOT);
                        if (POLICY_PUBLIC_IP.stream().anyMatch(haystack::contains)) {
                            denyPublicIp = true;
                        }
                        if (POLICY_SKU.stream().anyMatch(haystack::contains)) {
                            restrictSku = true;
                        }
                        if (POLICY_TAG.stream().anyMatch(haystack::contains)) {
                            tagPolicy = true;
                        }
                    }

                    ctx.put("AZURE-1", assignments.isEmpty()
                            ? WarCheckResult.fail("No Azure Policy assignments on this subscription "
                                    + "— nothing structurally prevents risky provisioning")
                            : WarCheckResult.pass(assignments.size()
                                    + " policy assignment(s) in effect").withEvidence(names));

                    ctx.put("AZURE-60", initiatives > 0
                            ? WarCheckResult.pass(initiatives + " policy initiative(s) assigned")
                            : WarCheckResult.fail("No policy initiatives assigned — drift from a "
                                    + "compliance baseline is only found during an audit"));

                    ctx.put("AZURE-61", tagPolicy
                            ? WarCheckResult.pass("A tagging policy is assigned")
                            : WarCheckResult.fail("No tagging policy — untagged shadow compute "
                                    + "cannot be attributed to a team or spotted in cost reports"));

                    ctx.put("AZURE-21", denyPublicIp
                            ? WarCheckResult.pass("A policy restricts public IP assignment")
                            : WarCheckResult.fail("No policy denies public IP assignment — VMs can "
                                    + "be given public addresses and are found by scanners within "
                                    + "minutes"));

                    ctx.put("AZURE-83", restrictSku
                            ? WarCheckResult.pass("A policy restricts allowed VM SKUs")
                            : WarCheckResult.fail("No policy restricts VM SKUs — an attacker can "
                                    + "launch the most expensive GPU sizes available"));

                    // 84 is the C2 half of the same guardrail: without a public-IP
                    // restriction a compromised VM can reach a mining pool directly.
                    ctx.put("AZURE-84", denyPublicIp
                            ? WarCheckResult.pass("Public IP restriction blocks direct mining pool "
                                    + "command-and-control")
                            : WarCheckResult.fail("Without a public IP restriction, a compromised VM "
                                    + "can reach mining pool C2 directly"));
                });
    }

    /** AZURE-42 (Defender enabled) and AZURE-43 (the individual plans). */
    private void checkDefenderPlans(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Security/pricings/read",
                List.of("AZURE-42", "AZURE-43"), () -> {
                    List<JsonNode> pricings = rest.listValues(subscription,
                            "/providers/Microsoft.Security/pricings" + API_DEFENDER);

                    List<String> standard = new ArrayList<>();
                    List<String> free = new ArrayList<>();
                    for (JsonNode pricing : pricings) {
                        String name = pricing.path("name").asText("");
                        String tier = pricing.path("properties").path("pricingTier").asText("");
                        if ("Standard".equalsIgnoreCase(tier)) {
                            standard.add(name);
                        } else {
                            free.add(name + " is on the Free tier");
                        }
                    }

                    ctx.put("AZURE-42", standard.isEmpty()
                            ? WarCheckResult.fail("No Defender for Cloud plan is on Standard — the "
                                    + "cryptomining and threat detections are not running")
                            : WarCheckResult.pass(standard.size()
                                    + " Defender plan(s) on Standard").withEvidence(standard));

                    List<String> missing = DEFENDER_PLANS.stream()
                            .filter(plan -> standard.stream().noneMatch(s -> s.equalsIgnoreCase(plan)))
                            .map(plan -> "Defender for " + plan + " is not on Standard")
                            .toList();

                    ctx.put("AZURE-43", WarCheckResult.of(missing,
                                    "Defender for Cloud plans are incomplete",
                                    "All key Defender plans are on Standard")
                            .withScanned(pricings.size()));
                });
    }

    /** AZURE-41 (critical event alerts) and AZURE-80 (sustained CPU/GPU alerts). */
    private void checkAlertRules(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Insights/metricAlerts/read",
                List.of("AZURE-41", "AZURE-80"), () -> {
                    List<JsonNode> metricAlerts = rest.listValues(subscription,
                            "/providers/Microsoft.Insights/metricAlerts?api-version=2018-03-01");
                    List<JsonNode> activityAlerts = ctx.quiet(List.<JsonNode>of(), () ->
                            rest.listValues(subscription,
                                    "/providers/Microsoft.Insights/activityLogAlerts" + API_ALERTS));

                    boolean cpuAlert = false;
                    for (JsonNode alert : metricAlerts) {
                        String blob = alert.toString().toLowerCase(Locale.ROOT);
                        if (blob.contains("percentage cpu") || blob.contains("cpu")
                                || blob.contains("gpu")) {
                            cpuAlert = true;
                            break;
                        }
                    }

                    boolean criticalAlert = false;
                    for (JsonNode alert : activityAlerts) {
                        String blob = alert.toString().toLowerCase(Locale.ROOT);
                        if (blob.contains("roleassignment") || blob.contains("networksecuritygroup")
                                || blob.contains("policyassignment") || blob.contains("security")) {
                            criticalAlert = true;
                            break;
                        }
                    }

                    int total = metricAlerts.size() + activityAlerts.size();
                    ctx.put("AZURE-41", criticalAlert
                            ? WarCheckResult.pass("Activity log alerts cover role, NSG or policy "
                                    + "changes")
                            : WarCheckResult.fail(total == 0
                                    ? "No Azure Monitor alert rules — nothing will page anyone while "
                                            + "an attack is in progress"
                                    : String.format("%d alert rule(s) exist but none watch role "
                                            + "assignment, NSG or policy changes", total)));

                    ctx.put("AZURE-80", cpuAlert
                            ? WarCheckResult.pass("A metric alert watches CPU or GPU utilisation")
                            : WarCheckResult.fail("No metric alert watches sustained CPU or GPU "
                                    + "utilisation — the flat near-maximum load that mining produces "
                                    + "would go unreported"));
                });
    }

    /** AZURE-38: a subscription-wide activity log export. */
    private void checkActivityLogExport(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Insights/diagnosticSettings/read", List.of("AZURE-38"), () -> {
            List<JsonNode> settings = rest.listValues(subscription,
                    "https://management.azure.com/subscriptions/" + subscription
                            + "/providers/Microsoft.Insights/diagnosticSettings" + API_DIAGNOSTIC);

            List<String> destinations = new ArrayList<>();
            for (JsonNode setting : settings) {
                JsonNode props = setting.path("properties");
                String workspace = props.path("workspaceId").asText("");
                String storage = props.path("storageAccountId").asText("");
                String hub = props.path("eventHubAuthorizationRuleId").asText("");
                if (!workspace.isBlank()) {
                    destinations.add(setting.path("name").asText("") + " → Log Analytics");
                } else if (!storage.isBlank()) {
                    destinations.add(setting.path("name").asText("") + " → Storage");
                } else if (!hub.isBlank()) {
                    destinations.add(setting.path("name").asText("") + " → Event Hub");
                }
            }

            ctx.put("AZURE-38", destinations.isEmpty()
                    ? WarCheckResult.fail("No subscription activity log export — control-plane "
                            + "activity is retained for only 90 days and cannot be correlated")
                    : WarCheckResult.pass("Activity logs are exported")
                            .withEvidence(destinations));
        });
    }

    /** AZURE-59: Resource Graph as the live inventory. */
    private void checkResourceInventory(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Resources/subscriptions/resources/read",
                List.of("AZURE-59"), () -> {
                    var azure = clients.getAzureClient(subscription);
                    if (azure == null) {
                        throw new IllegalStateException(
                                "Azure client unavailable for subscription " + subscription);
                    }
                    long resources = azure.genericResources().list().stream().limit(500).count();
                    ctx.put("AZURE-59", resources > 0
                            ? WarCheckResult.pass(
                                    "Resource inventory is queryable (" + resources + "+ resources)")
                            : WarCheckResult.warn("No resources returned — verify the service "
                                    + "principal has Reader on the subscription"));
                });
    }

    /** AZURE-86: budgets and cost anomaly alerts, the last line of defence on spend. */
    private void checkBudgets(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Consumption/budgets/read", List.of("AZURE-86"), () -> {
            List<JsonNode> budgets = rest.listValues(subscription,
                    "/providers/Microsoft.Consumption/budgets?api-version=2023-05-01");

            List<String> names = new ArrayList<>();
            budgets.forEach(b -> names.add(b.path("name").asText("")));

            ctx.put("AZURE-86", names.isEmpty()
                    ? WarCheckResult.fail("No budgets configured — mining can run silently until the "
                            + "invoice arrives, and by then the charges are processed")
                    : WarCheckResult.pass(names.size() + " budget(s) watching spend")
                            .withEvidence(names));
        });
    }

    /**
     * AZURE-79: has Defender already reported mining on this subscription?
     *
     * <p>
     * The one check here that reports an active incident rather than a configuration
     * weakness, so it is deliberately loud.
     */
    private void checkCryptominingAlerts(WarScanContext ctx, String subscription) {
        ctx.guard("Microsoft.Security/alerts/read", List.of("AZURE-79"), () -> {
            List<JsonNode> alerts = rest.listValues(subscription,
                    "/providers/Microsoft.Security/alerts?api-version=2022-01-01");

            List<String> mining = new ArrayList<>();
            int active = 0;
            for (JsonNode alert : alerts) {
                JsonNode props = alert.path("properties");
                String status = props.path("status").asText("");
                if (!"Active".equalsIgnoreCase(status)) {
                    continue;
                }
                active++;
                String blob = (props.path("alertDisplayName").asText("") + " "
                        + props.path("alertType").asText("") + " "
                        + props.path("description").asText("")).toLowerCase(Locale.ROOT);
                if (blob.contains("crypto") || blob.contains("mining")
                        || blob.contains("coin") || blob.contains("xmr")) {
                    mining.add(String.format("%s on %s (severity %s)",
                            props.path("alertDisplayName").asText(""),
                            props.path("compromisedEntity").asText("unknown resource"),
                            props.path("severity").asText("")));
                }
            }

            ctx.put("AZURE-79", mining.isEmpty()
                    ? WarCheckResult.pass(String.format(
                            "Defender for Cloud has no open cryptomining alerts (%d active alerts "
                                    + "reviewed)", active))
                    : WarCheckResult.fail(String.format(
                                    "Defender has %d open cryptomining alert(s) — treat this as an "
                                            + "active incident, not a configuration gap", mining.size()))
                            .withEvidence(mining));
        });
    }

    /** Role definition ids end in the well-known GUID; only a few matter here. */
    private static String roleName(String roleDefinitionId) {
        String id = roleDefinitionId == null ? "" : roleDefinitionId.toLowerCase(Locale.ROOT);
        if (id.endsWith("8e3af657a8ff443c8e4d0d1e4e2b0a5b")
                || id.contains("8e3af657-a8ff-443c-8e4d-0d1e4e2b0a5b")) {
            return "Owner";
        }
        if (id.contains("b24988ac-6180-42a0-ab88-20f7382dd24c")) {
            return "Contributor";
        }
        if (id.contains("18d7d88d-d35e-4fb5-a5c3-7773c20a72d9")) {
            return "User Access Administrator";
        }
        return "other";
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 8) {
            return String.valueOf(id);
        }
        return id.substring(0, 8) + "…";
    }

    /** ARM scopes are long paths; the trailing resource is what identifies them. */
    private static String shortScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "unknown scope";
        }
        if (scope.matches("/subscriptions/[^/]+")) {
            return "subscription";
        }
        int slash = scope.lastIndexOf('/');
        return slash >= 0 ? scope.substring(slash + 1) : scope;
    }
}
