package com.xammer.cloud.service.war.aws;

import com.xammer.cloud.dto.war.WarCheckResult;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.war.WarScanContext;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.accessanalyzer.AccessAnalyzerClient;
import software.amazon.awssdk.services.accessanalyzer.model.AnalyzerSummary;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Trail;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.controltower.ControlTowerClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.AttachedPolicy;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.User;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.PolicyType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Identity, access and governance controls (advisory rows 1-10 and 58-61).
 *
 * <p>
 * This is the highest-value group for the mining threat model. Every published
 * crypto-mining incident on AWS starts here: a leaked long-lived access key, an
 * unprotected root user, or an over-broad role attached to compute. Network exposure
 * and missing detection make it worse, but credentials are the front door.
 */
@Component
public class AwsWarIdentityChecks {

    /** Access keys older than this are treated as standing credential risk. */
    private static final Duration KEY_AGE_LIMIT = Duration.ofDays(90);

    /** IAM users/roles idle this long are unmanaged access nobody is watching. */
    private static final Duration IDLE_LIMIT = Duration.ofDays(90);

    /** Managed policies that hand over the account. */
    private static final List<String> ADMIN_POLICIES = List.of("AdministratorAccess", "PowerUserAccess");

    /** Trust-policy principals that mean "this role is attached to compute, not a human". */
    private static final List<String> COMPUTE_PRINCIPALS = List.of(
            "ec2.amazonaws.com", "lambda.amazonaws.com", "ecs-tasks.amazonaws.com", "eks.amazonaws.com");

    private final AwsClientProvider clients;

    public AwsWarIdentityChecks(AwsClientProvider clients) {
        this.clients = clients;
    }

    public void run(WarScanContext ctx) {
        String globalRegion = ctx.regions().isEmpty() ? "us-east-1" : ctx.regions().get(0);

        checkRootUser(ctx);
        checkMfaAndAdminIdentities(ctx);
        checkAccessKeys(ctx);
        checkServiceRolePrivilege(ctx);
        checkAccessAnalyzer(ctx, globalRegion);
        checkOrganizationGuardrails(ctx, globalRegion);
        checkIamAuditTrail(ctx, globalRegion);
        checkConfigCoverage(ctx, globalRegion);
        checkControlTower(ctx, globalRegion);
    }

    /** AWS-3: root MFA enabled and no root access keys. */
    private void checkRootUser(WarScanContext ctx) {
        ctx.guard("iam:GetAccountSummary", List.of("AWS-3"), () -> {
            IamClient iam = clients.getIamClient(ctx.account());
            var summary = iam.getAccountSummary().summaryMap();
            int mfaEnabled = summary.getOrDefault(
                    software.amazon.awssdk.services.iam.model.SummaryKeyType.ACCOUNT_MFA_ENABLED, 0);
            int rootKeys = summary.getOrDefault(
                    software.amazon.awssdk.services.iam.model.SummaryKeyType.ACCOUNT_ACCESS_KEYS_PRESENT, 0);

            List<String> evidence = new ArrayList<>();
            if (mfaEnabled == 0) {
                evidence.add("Root user has no MFA device — a stolen root password is unopposed");
            }
            if (rootKeys > 0) {
                evidence.add("Root user has active access keys — these should not exist at all");
            }
            ctx.put("AWS-3", WarCheckResult.of(evidence,
                    "Root user is not locked down",
                    "Root user has MFA and no access keys"));
        });
    }

    /** AWS-4 (MFA) and AWS-5 (least privilege) — both need the same user/policy walk. */
    private void checkMfaAndAdminIdentities(WarScanContext ctx) {
        ctx.guard("iam:ListUsers", List.of("AWS-4", "AWS-5", "AWS-8"), () -> {
            IamClient iam = clients.getIamClient(ctx.account());
            List<User> users = iam.listUsersPaginator().users().stream().toList();

            List<String> noMfa = new ArrayList<>();
            List<String> adminIdentities = new ArrayList<>();
            List<String> idle = new ArrayList<>();

            for (User user : users) {
                boolean hasConsole = ctx.quiet(false, () -> {
                    try {
                        iam.getLoginProfile(r -> r.userName(user.userName()));
                        return true;
                    } catch (NoSuchEntityException e) {
                        return false;
                    }
                });

                if (hasConsole) {
                    boolean mfa = ctx.quiet(true, () -> !iam
                            .listMFADevices(r -> r.userName(user.userName())).mfaDevices().isEmpty());
                    if (!mfa) {
                        noMfa.add(user.userName() + " — console access, no MFA device");
                    }
                }

                List<AttachedPolicy> attached = ctx.quiet(List.of(), () -> iam
                        .listAttachedUserPolicies(r -> r.userName(user.userName())).attachedPolicies());
                for (AttachedPolicy p : attached) {
                    if (ADMIN_POLICIES.contains(p.policyName())) {
                        adminIdentities.add("User " + user.userName() + " has " + p.policyName());
                    }
                }

                if (user.passwordLastUsed() != null
                        && user.passwordLastUsed().isBefore(Instant.now().minus(IDLE_LIMIT))) {
                    idle.add(user.userName() + " — no console sign-in in over 90 days");
                }
            }

            // Roles carrying admin are as dangerous as users carrying admin.
            List<Role> roles = ctx.quiet(List.of(), () -> iam.listRolesPaginator().roles().stream().toList());
            for (Role role : roles) {
                if (role.path() != null && role.path().startsWith("/aws-service-role/")) {
                    continue; // AWS-managed service-linked roles are not customer attack surface
                }
                List<AttachedPolicy> attached = ctx.quiet(List.of(), () -> iam
                        .listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies());
                for (AttachedPolicy p : attached) {
                    if (ADMIN_POLICIES.contains(p.policyName())) {
                        adminIdentities.add("Role " + role.roleName() + " has " + p.policyName());
                    }
                }
            }

            ctx.put("AWS-4", WarCheckResult.of(noMfa,
                            "MFA is not enforced on every console identity",
                            "All console identities have an MFA device")
                    .withScanned(users.size()));

            ctx.put("AWS-5", WarCheckResult.of(adminIdentities,
                    "Broad administrative policies are attached to identities",
                    "No identity carries AdministratorAccess or PowerUserAccess"));

            ctx.put("AWS-8", WarCheckResult.of(idle,
                            "Idle IAM identities still hold standing permissions",
                            "No IAM user has been idle beyond 90 days")
                    .withScanned(users.size()));
        });
    }

    /** AWS-7: long-lived access keys, the credential that mining bots actually find. */
    private void checkAccessKeys(WarScanContext ctx) {
        ctx.guard("iam:ListAccessKeys", List.of("AWS-7"), () -> {
            IamClient iam = clients.getIamClient(ctx.account());
            List<String> stale = new ArrayList<>();
            int keyCount = 0;

            for (User user : iam.listUsersPaginator().users()) {
                List<AccessKeyMetadata> keys = ctx.quiet(List.of(), () -> iam
                        .listAccessKeys(r -> r.userName(user.userName())).accessKeyMetadata());
                for (AccessKeyMetadata key : keys) {
                    keyCount++;
                    if (!"Active".equalsIgnoreCase(key.statusAsString())) {
                        continue;
                    }
                    Duration age = Duration.between(key.createDate(), Instant.now());
                    if (age.compareTo(KEY_AGE_LIMIT) > 0) {
                        stale.add(String.format("%s / %s — active key %d days old",
                                user.userName(), key.accessKeyId(), age.toDays()));
                    }
                }
            }

            ctx.put("AWS-7", WarCheckResult.of(stale,
                            "Long-lived IAM access keys are active",
                            keyCount == 0
                                    ? "No IAM user access keys exist — workloads use roles"
                                    : "All active access keys are under 90 days old")
                    .withScanned(keyCount));
        });
    }

    /** AWS-9 and AWS-47: compute identities must not carry account-wide power. */
    private void checkServiceRolePrivilege(WarScanContext ctx) {
        ctx.guard("iam:ListRoles", List.of("AWS-9", "AWS-47"), () -> {
            IamClient iam = clients.getIamClient(ctx.account());
            List<String> broad = new ArrayList<>();
            List<String> ec2Broad = new ArrayList<>();

            for (Role role : iam.listRolesPaginator().roles()) {
                if (role.path() != null && role.path().startsWith("/aws-service-role/")) {
                    continue;
                }
                String trust = role.assumeRolePolicyDocument() == null
                        ? ""
                        : java.net.URLDecoder.decode(role.assumeRolePolicyDocument(),
                                java.nio.charset.StandardCharsets.UTF_8);

                boolean isComputeRole = COMPUTE_PRINCIPALS.stream().anyMatch(trust::contains);
                if (!isComputeRole) {
                    continue;
                }

                List<AttachedPolicy> attached = ctx.quiet(List.of(), () -> iam
                        .listAttachedRolePolicies(r -> r.roleName(role.roleName())).attachedPolicies());
                for (AttachedPolicy p : attached) {
                    if (ADMIN_POLICIES.contains(p.policyName())) {
                        String detail = String.format("%s (workload role) has %s", role.roleName(), p.policyName());
                        broad.add(detail);
                        if (trust.contains("ec2.amazonaws.com")) {
                            ec2Broad.add(detail);
                        }
                    }
                }
            }

            ctx.put("AWS-9", WarCheckResult.of(broad,
                    "Workload roles carry account-wide permissions",
                    "No compute role carries AdministratorAccess or PowerUserAccess"));
            ctx.put("AWS-47", WarCheckResult.of(ec2Broad,
                    "EC2 instance profiles are over-privileged",
                    "EC2 instance profiles are scoped below account-wide admin"));
        });
    }

    /** AWS-6: IAM Access Analyzer with unused-access findings. */
    private void checkAccessAnalyzer(WarScanContext ctx, String region) {
        ctx.guard("access-analyzer:ListAnalyzers", List.of("AWS-6"), () -> {
            AccessAnalyzerClient client = clients.getAccessAnalyzerClient(ctx.account(), region);
            List<AnalyzerSummary> analyzers = client.listAnalyzers(r -> {
            }).analyzers().stream()
                    .filter(a -> "ACTIVE".equalsIgnoreCase(a.statusAsString()))
                    .toList();

            if (analyzers.isEmpty()) {
                ctx.put("AWS-6", WarCheckResult.fail(
                        "No active IAM Access Analyzer — excess permissions are never reviewed"));
                return;
            }
            boolean unusedAccess = analyzers.stream()
                    .anyMatch(a -> a.typeAsString() != null
                            && a.typeAsString().toUpperCase(Locale.ROOT).contains("UNUSED_ACCESS"));

            ctx.put("AWS-6", unusedAccess
                    ? WarCheckResult.pass("Access Analyzer is active with unused-access findings enabled")
                    : WarCheckResult.warn("Access Analyzer is active but no unused-access analyzer exists, "
                            + "so dormant over-permission is not surfaced")
                            .withEvidence(analyzers.stream()
                                    .map(a -> a.name() + " (" + a.typeAsString() + ")").toList()));
        });
    }

    /** AWS-1 and AWS-61: preventive SCPs and tag policies at the organization level. */
    private void checkOrganizationGuardrails(WarScanContext ctx, String region) {
        ctx.guard("organizations:ListPolicies", List.of("AWS-1", "AWS-61"), () -> {
            OrganizationsClient org = clients.getOrganizationsClient(ctx.account());

            int scpCount = ctx.quiet(-1, () -> org
                    .listPolicies(r -> r.filter(PolicyType.SERVICE_CONTROL_POLICY)).policies().size());
            int tagPolicyCount = ctx.quiet(-1, () -> org
                    .listPolicies(r -> r.filter(PolicyType.TAG_POLICY)).policies().size());

            // The default FullAWSAccess SCP is always present and denies nothing, so
            // "exactly one SCP" means no preventive guardrail has ever been written.
            if (scpCount < 0) {
                ctx.put("AWS-1", WarCheckResult.unknown(
                        "Not the organization management account, or Organizations access is denied",
                        "organizations:ListPolicies"));
            } else if (scpCount <= 1) {
                ctx.put("AWS-1", WarCheckResult.fail(
                        "No preventive SCP beyond the default FullAWSAccess — nothing blocks root "
                                + "actions, disabling CloudTrail, or launching in unapproved Regions"));
            } else {
                ctx.put("AWS-1", WarCheckResult.pass(scpCount + " service control policies are in place"));
            }

            if (tagPolicyCount < 0) {
                ctx.put("AWS-61", WarCheckResult.unknown(
                        "Not the organization management account, or Organizations access is denied",
                        "organizations:ListPolicies"));
            } else {
                ctx.put("AWS-61", tagPolicyCount > 0
                        ? WarCheckResult.pass(tagPolicyCount + " tag policies enforce resource attribution")
                        : WarCheckResult.fail("No tag policies — untagged shadow compute cannot be "
                                + "attributed to a team or spotted in cost reports"));
            }
        });
    }

    /** AWS-10: management events for IAM and STS must be captured somewhere. */
    private void checkIamAuditTrail(WarScanContext ctx, String region) {
        ctx.guard("cloudtrail:DescribeTrails", List.of("AWS-10"), () -> {
            CloudTrailClient trail = clients.getCloudTrailClient(ctx.account(), region);
            List<Trail> trails = trail.describeTrails().trailList();

            boolean covered = trails.stream().anyMatch(t -> {
                boolean multiRegion = Boolean.TRUE.equals(t.isMultiRegionTrail());
                boolean logging = ctx.quiet(false, () -> trail
                        .getTrailStatus(r -> r.name(t.trailARN())).isLogging());
                return multiRegion && logging;
            });

            ctx.put("AWS-10", covered
                    ? WarCheckResult.pass("A multi-region trail is logging IAM and STS management events")
                    : WarCheckResult.fail("No active multi-region trail — AssumeRole calls and permission "
                            + "changes leave no forensic record"));
        });
    }

    /** AWS-59 and AWS-60: Config recording plus conformance packs. */
    private void checkConfigCoverage(WarScanContext ctx, String region) {
        ctx.guard("config:DescribeConfigurationRecorderStatus", List.of("AWS-59", "AWS-60"), () -> {
            ConfigClient config = clients.getConfigClient(ctx.account(), region);

            boolean recording = config.describeConfigurationRecorderStatus().configurationRecordersStatus()
                    .stream().anyMatch(s -> Boolean.TRUE.equals(s.recording()));
            ctx.put("AWS-59", recording
                    ? WarCheckResult.pass("AWS Config is recording resource configuration in " + region)
                    : WarCheckResult.fail("AWS Config is not recording in " + region + " — configuration "
                            + "drift and compliance state are invisible").withRegion(region));

            int packs = ctx.quiet(0, () -> config.describeConformancePacks(r -> {
            }).conformancePackDetails().size());
            ctx.put("AWS-60", packs > 0
                    ? WarCheckResult.pass(packs + " conformance pack(s) deployed")
                    : WarCheckResult.fail("No Config conformance packs — drift from the CIS baseline is "
                            + "only discovered during an audit").withRegion(region));
        });
    }

    /** AWS-58: a governed multi-account landing zone. */
    private void checkControlTower(WarScanContext ctx, String region) {
        ctx.guard("controltower:ListLandingZones", List.of("AWS-58"), () -> {
            ControlTowerClient ct = clients.getControlTowerClient(ctx.account(), region);
            int zones = ct.listLandingZones(r -> {
            }).landingZones().size();
            ctx.put("AWS-58", zones > 0
                    ? WarCheckResult.pass("Control Tower landing zone is deployed")
                    : WarCheckResult.fail("No Control Tower landing zone — mandatory guardrails are not "
                            + "applied automatically to new accounts"));
        });
    }
}
