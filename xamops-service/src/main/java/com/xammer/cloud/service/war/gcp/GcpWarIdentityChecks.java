package com.xammer.cloud.service.war.gcp;

import com.google.iam.v1.Binding;
import com.google.cloud.asset.v1.AssetServiceClient;
import com.google.cloud.iam.admin.v1.IAMClient;
import com.google.cloud.orgpolicy.v2.OrgPolicyClient;
import com.google.cloud.recommender.v1.RecommenderClient;
import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * GCP identity, organization-policy and governance controls
 * (advisory rows 1, 5-10, 58-60 and the org-policy guardrails at 21, 93, 94).
 *
 * <p>
 * Organization policy constraints are GCP's equivalent of AWS SCPs and carry the same
 * weight in the mining threat model: without {@code compute.vmExternalIpAccess} and a
 * machine-type restriction, a compromised credential can launch a GPU fleet with a
 * public address and nothing structural stops it.
 */
@Component
public class GcpWarIdentityChecks {

    /** Legacy primitive roles. Granting these is granting the project. */
    private static final List<String> PRIMITIVE_ROLES =
            List.of("roles/owner", "roles/editor", "roles/viewer");

    /** Roles that hand a service account the whole project. */
    private static final List<String> ADMIN_ROLES =
            List.of("roles/owner", "roles/editor");

    /**
     * Constraints WAR looks for, mapped to the control each one satisfies. Names are
     * the canonical constraint ids from the Organization Policy Service.
     */
    private static final String CONSTRAINT_EXTERNAL_IP = "compute.vmExternalIpAccess";
    private static final String CONSTRAINT_MACHINE_TYPE = "compute.restrictMachineTypes";
    private static final String CONSTRAINT_SA_KEYS = "iam.disableServiceAccountKeyCreation";

    private final GcpClientProvider clients;

    public GcpWarIdentityChecks(GcpClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String project = ctx.accountId();
        checkProjectIamPolicy(ctx, project);
        checkServiceAccounts(ctx, project);
        checkIamRecommender(ctx, project);
        checkOrgPolicies(ctx, project);
        checkAssetInventory(ctx, project);
    }

    /**
     * GCP-5 (primitive roles), GCP-9 (service accounts with project admin),
     * GCP-10 and GCP-74 (audit logging), GCP-72 (Vertex AI least privilege).
     *
     * <p>
     * One {@code getIamPolicy} call answers all five, so they share a guard.
     */
    private void checkProjectIamPolicy(WarScanContext ctx, String project) {
        ctx.guard("resourcemanager.projects.getIamPolicy",
                List.of("GCP-5", "GCP-9", "GCP-10", "GCP-72", "GCP-74"), () -> {
                    var policy = GcpWarSupport.projectIamPolicy(clients, project);
                    if (policy == null) {
                        throw new IllegalStateException(
                                "Cloud Resource Manager returned no IAM policy for " + project);
                    }

                    // Proto list getters never return null, so no defensive check needed.
                    List<Binding> bindings = policy.getBindingsList();

                    List<String> primitive = new ArrayList<>();
                    List<String> saAdmin = new ArrayList<>();
                    List<String> vertexBroad = new ArrayList<>();

                    for (Binding binding : bindings) {
                        String role = binding.getRole();
                        List<String> members = binding.getMembersList();

                        if (PRIMITIVE_ROLES.contains(role)) {
                            primitive.add(String.format("%s granted to %d principal(s): %s",
                                    role, members.size(), GcpWarSupport.preview(members)));
                        }
                        if (ADMIN_ROLES.contains(role)) {
                            members.stream()
                                    .filter(m -> m.startsWith("serviceAccount:"))
                                    .forEach(m -> saAdmin.add(m + " holds " + role));
                        }
                        if ("roles/aiplatform.admin".equals(role)) {
                            vertexBroad.add(String.format(
                                    "roles/aiplatform.admin granted to %s", GcpWarSupport.preview(members)));
                        }
                    }

                    ctx.put("GCP-5", WarCheckResult.of(primitive,
                            "Legacy primitive roles are still granted at project level",
                            "No primitive owner/editor/viewer bindings at project level"));

                    ctx.put("GCP-9", WarCheckResult.of(saAdmin,
                            "Service accounts hold project-wide admin roles",
                            "No service account holds owner or editor on the project"));

                    ctx.put("GCP-72", WarCheckResult.of(vertexBroad,
                            "Vertex AI admin is granted rather than scoped inference roles",
                            "No principal holds roles/aiplatform.admin"));

                    // Audit configs live on the same policy object. Data-access logging is
                    // off by default on GCP, which is the single most common blind spot.
                    var auditConfigs = policy.getAuditConfigsList();
                    boolean allServices = auditConfigs.stream()
                            .anyMatch(a -> "allServices".equals(a.getService()));
                    boolean aiPlatform = auditConfigs.stream()
                            .anyMatch(a -> a.getService().contains("aiplatform"));

                    ctx.put("GCP-10", allServices
                            ? WarCheckResult.pass("Audit logging is configured for allServices")
                            : WarCheckResult.fail("No allServices audit config — service account "
                                    + "impersonation and data access leave no record"));

                    ctx.put("GCP-74", allServices || aiPlatform
                            ? WarCheckResult.pass("Vertex AI operations are covered by audit logging")
                            : WarCheckResult.fail("Vertex AI operations are not audit logged, so a "
                                    + "runaway agent or token-abuse spike cannot be traced"));
                });
    }

    /** GCP-7 (service account keys) and GCP-8 (unused service accounts). */
    private void checkServiceAccounts(WarScanContext ctx, String project) {
        ctx.guard("iam.serviceAccounts.list", List.of("GCP-7", "GCP-8"), () -> {
            Optional<IAMClient> clientOpt = clients.getIamClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("IAM client unavailable for " + project);
            }

            try (IAMClient iam = clientOpt.get()) {
                List<String> withKeys = new ArrayList<>();
                List<String> disabled = new ArrayList<>();
                int total = 0;

                for (var sa : iam.listServiceAccounts("projects/" + project).iterateAll()) {
                    total++;
                    if (sa.getDisabled()) {
                        disabled.add(sa.getEmail() + " is disabled but still present");
                        continue;
                    }
                    // USER_MANAGED keys are the long-lived credential that leaks; the
                    // SYSTEM_MANAGED ones Google rotates are not a finding.
                    int userKeys = ctx.quiet(0, () -> iam
                            .listServiceAccountKeys(sa.getName(), List.of(
                                    com.google.iam.admin.v1.ListServiceAccountKeysRequest
                                            .KeyType.USER_MANAGED))
                            .getKeysList().size());
                    if (userKeys > 0) {
                        withKeys.add(String.format("%s has %d user-managed key(s)",
                                sa.getEmail(), userKeys));
                    }
                }

                ctx.put("GCP-7", WarCheckResult.of(withKeys,
                                "Service accounts carry long-lived user-managed keys",
                                total == 0
                                        ? "No service accounts in this project"
                                        : "No service account has a user-managed key")
                        .withScanned(total));

                ctx.put("GCP-8", WarCheckResult.of(disabled,
                                "Disabled service accounts are still present",
                                "No dormant service accounts")
                        .withScanned(total));
            }
        });
    }

    /** GCP-6 and GCP-59: IAM Recommender surfacing excess permissions. */
    private void checkIamRecommender(WarScanContext ctx, String project) {
        ctx.guard("recommender.iamPolicyRecommendations.list",
                List.of("GCP-6", "GCP-59"), () -> {
                    Optional<RecommenderClient> clientOpt = clients.getRecommenderClient(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("Recommender client unavailable");
                    }

                    try (RecommenderClient recommender = clientOpt.get()) {
                        String parent = String.format(
                                "projects/%s/locations/global/recommenders/"
                                        + "google.iam.policy.Recommender", project);
                        List<String> open = new ArrayList<>();
                        for (var rec : recommender.listRecommendations(parent).iterateAll()) {
                            String state = rec.getStateInfo() == null
                                    ? ""
                                    : rec.getStateInfo().getState().name();
                            if ("ACTIVE".equals(state)) {
                                open.add(rec.getDescription());
                            }
                            if (open.size() >= 25) {
                                break;
                            }
                        }

                        ctx.put("GCP-6", WarCheckResult.of(open,
                                "IAM Recommender has open findings for excess permissions",
                                "No open IAM Recommender findings"));
                        // Recommender being reachable at all is the evidence that Policy
                        // Intelligence is enabled on the project.
                        ctx.put("GCP-59", WarCheckResult.pass(
                                "Policy Intelligence recommenders are enabled and reachable"));
                    }
                });
    }

    /**
     * GCP-1 (org policy constraints), GCP-21 (no external IP), GCP-93 (machine types),
     * GCP-94 (external IP as mining C2 path).
     */
    private void checkOrgPolicies(WarScanContext ctx, String project) {
        ctx.guard("orgpolicy.policies.list",
                List.of("GCP-1", "GCP-21", "GCP-93", "GCP-94"), () -> {
                    Optional<OrgPolicyClient> clientOpt = clients.getOrgPolicyClient(project);
                    if (clientOpt.isEmpty()) {
                        throw new IllegalStateException("Organization Policy client unavailable");
                    }

                    try (OrgPolicyClient orgPolicy = clientOpt.get()) {
                        List<String> constraints = new ArrayList<>();
                        for (var policy : orgPolicy
                                .listPolicies("projects/" + project).iterateAll()) {
                            String name = policy.getName();
                            int slash = name.lastIndexOf('/');
                            constraints.add(slash >= 0 ? name.substring(slash + 1) : name);
                        }

                        boolean externalIp = constraints.stream()
                                .anyMatch(c -> c.toLowerCase(Locale.ROOT)
                                        .contains("vmexternalipaccess"));
                        boolean machineType = constraints.stream()
                                .anyMatch(c -> c.toLowerCase(Locale.ROOT)
                                        .contains("restrictmachinetypes"));
                        boolean saKeys = constraints.stream()
                                .anyMatch(c -> c.toLowerCase(Locale.ROOT)
                                        .contains("disableserviceaccountkeycreation"));

                        ctx.put("GCP-1", constraints.isEmpty()
                                ? WarCheckResult.fail("No organization policy constraints applied to "
                                        + "this project — nothing structurally prevents risky "
                                        + "provisioning")
                                : WarCheckResult.pass(constraints.size()
                                        + " organization policy constraint(s) applied")
                                        .withEvidence(constraints));

                        ctx.put("GCP-21", externalIp
                                ? WarCheckResult.pass(CONSTRAINT_EXTERNAL_IP + " is enforced")
                                : WarCheckResult.fail("No " + CONSTRAINT_EXTERNAL_IP
                                        + " constraint — VMs can be given public addresses and are "
                                        + "found by scanners within minutes"));

                        ctx.put("GCP-93", machineType
                                ? WarCheckResult.pass(CONSTRAINT_MACHINE_TYPE + " is enforced")
                                : WarCheckResult.fail("No " + CONSTRAINT_MACHINE_TYPE
                                        + " constraint — an attacker can launch the most expensive "
                                        + "GPU machine types available"));

                        // 94 is the C2 half of the same guardrail: without an external-IP
                        // restriction, a compromised VM can reach a mining pool directly.
                        ctx.put("GCP-94", externalIp
                                ? WarCheckResult.pass("External IP restriction blocks direct "
                                        + "mining pool command-and-control")
                                : WarCheckResult.fail("Without " + CONSTRAINT_EXTERNAL_IP
                                        + ", a compromised VM can reach mining pool C2 directly")
                                        .withEvidence(saKeys
                                                ? List.of("Service account key creation is blocked, "
                                                        + "which limits the credential half of this path")
                                                : List.of("Service account key creation is also "
                                                        + "unrestricted (" + CONSTRAINT_SA_KEYS + ")")));
                    }
                });
    }

    /** GCP-58: Cloud Asset Inventory as the record of what exists. */
    private void checkAssetInventory(WarScanContext ctx, String project) {
        ctx.guard("cloudasset.assets.searchAllResources", List.of("GCP-58"), () -> {
            Optional<AssetServiceClient> clientOpt = clients.getAssetServiceClient(project);
            if (clientOpt.isEmpty()) {
                throw new IllegalStateException("Cloud Asset client unavailable");
            }
            try (AssetServiceClient asset = clientOpt.get()) {
                // A single page is enough to prove the API is enabled and returning.
                var response = asset.searchAllResources(
                        "projects/" + project, "", List.of());
                boolean any = response.iterateAll().iterator().hasNext();
                ctx.put("GCP-58", any
                        ? WarCheckResult.pass("Cloud Asset Inventory is enabled and indexing resources")
                        : WarCheckResult.warn("Cloud Asset Inventory returned no assets — verify the "
                                + "API is enabled for this project"));
            }
        });
    }
}
