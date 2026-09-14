package com.xammer.cloud.service.security;

import com.google.cloud.iam.admin.v1.IAMClient;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.iam.v1.Binding;
import com.google.iam.v1.GetIamPolicyRequest;
import com.google.iam.v1.GetPolicyOptions;
import com.google.iam.v1.Policy;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.gcp.GcpClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a GCP principal holds more permission than it should.
 *
 * <p>
 * The GCP Identity tab was in worse shape than the AWS one before this existed. It listed
 * service accounts and custom roles as two disconnected lists, and it never fetched the project
 * IAM policy — so no principal had a single role attached, human users did not appear at all,
 * and every service account rendered as an identity with no permissions. The page was
 * structurally incapable of showing an over-privileged identity.
 *
 * <p>
 * The fix is the project IAM policy, which is the only place GCP records who can do what.
 * Bindings are role → members, the inverse of what a per-identity view needs, so this class
 * pivots them into member → roles and creates the missing principals as it goes.
 *
 * <p>
 * Two GCP-specific judgements worth knowing.
 *
 * <p>
 * <b>Primitive roles are the finding, not a heuristic.</b> {@code roles/owner} and
 * {@code roles/editor} predate GCP's granular roles and grant thousands of permissions across
 * every service in the project. Google's own guidance is that they should not be used outside
 * development, so they are reported directly rather than inferred from a permission count.
 *
 * <p>
 * <b>Impersonation is escalation.</b> {@code roles/iam.serviceAccountTokenCreator} lets a
 * principal mint tokens for any service account in the project, which means its effective
 * permission is the union of every service account's — including ones holding owner. It is a
 * CRITICAL finding even though the role itself looks narrow, for the same reason
 * {@code iam:PassRole} is on the AWS side.
 *
 * @see IamPrivilegeAnalyzer the AWS equivalent, whose conventions this follows
 */
@Service
public class GcpPrivilegeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(GcpPrivilegeAnalyzer.class);

    // --- Finding codes. Stable; remediation content is keyed off these. ---
    public static final String PROJECT_OWNER = "GCP-EXC-001";
    public static final String PROJECT_EDITOR = "GCP-EXC-002";
    public static final String PUBLIC_BINDING = "GCP-EXC-003";
    public static final String IMPERSONATION = "GCP-EXC-004";
    public static final String SERVICE_ADMIN = "GCP-EXC-005";
    public static final String PROJECT_VIEWER = "GCP-EXC-006";
    public static final String LONG_LIVED_KEY = "GCP-EXC-007";
    public static final String CUSTOM_ROLE_WILDCARD = "GCP-EXC-008";
    public static final String SA_PROJECT_ADMIN = "GCP-EXC-009";

    /**
     * Marks an {@code IamPolicyDetail} that carries a single IAM permission rather than a role.
     *
     * <p>
     * A GCP custom role's "access" genuinely is its permission list, so the role's
     * {@code attachedPolicies} is the right place for them — but the type has to distinguish
     * them from the roles attached to a principal, which live in the same field.
     */
    public static final String PERMISSION_TYPE = "Permission";

    /** IAM policy version 3 — required to see conditional (ABAC) bindings at all. */
    private static final int IAM_POLICY_VERSION = 3;

    private static final int MAX_EVIDENCE = 6;

    /**
     * Roles that let a principal grant itself, or borrow, more permission.
     *
     * <p>
     * Each of these makes the holder's effective access unbounded regardless of what else they
     * have: rewriting the project's IAM policy, creating a role, minting a service-account key,
     * or impersonating a service account that holds more than they do.
     */
    private static final Map<String, String> ESCALATION_ROLES = Map.of(
            "roles/iam.serviceAccountTokenCreator",
                    "can mint access tokens for any service account in the project, inheriting its permissions",
            "roles/iam.serviceAccountUser",
                    "can attach and act as service accounts, inheriting their permissions",
            "roles/iam.serviceAccountKeyAdmin",
                    "can create long-lived keys for any service account",
            "roles/iam.securityAdmin",
                    "can read and modify the project's IAM policy, so it can grant itself any role",
            "roles/iam.roleAdmin",
                    "can create and edit custom roles, so it can widen its own permissions",
            "roles/resourcemanager.projectIamAdmin",
                    "can change the project's IAM policy, so it can grant itself any role",
            "roles/owner",
                    "includes full IAM control, so it can grant any role to anyone");

    /**
     * Permissions that make a custom role an escalation path.
     *
     * <p>
     * A custom role looks harmless by name and can quietly contain {@code setIamPolicy}, which
     * is the whole project.
     */
    private static final Set<String> ESCALATION_PERMISSIONS = Set.of(
            "resourcemanager.projects.setiampolicy",
            "iam.serviceaccounts.setiampolicy",
            "iam.serviceaccounts.actas",
            "iam.serviceaccountkeys.create",
            "iam.roles.update",
            "iam.roles.create");

    private final GcpClientProvider clients;

    public GcpPrivilegeAnalyzer(GcpClientProvider clients) {
        this.clients = clients;
    }

    /**
     * Attaches real roles to the supplied identities, invents the ones the bindings reveal, and
     * scores them all.
     *
     * <p>
     * Mutates both lists in place, and <em>adds</em> to {@code users} — human and group
     * principals exist only in the IAM policy, so they cannot have been passed in. Both lists
     * must therefore be mutable. This mirrors {@link IamPrivilegeAnalyzer}'s in-place contract
     * and carries the same constraint: call once, before the result is cached.
     *
     * <p>
     * Never throws. A failed audit degrades to "unknown" and leaves the inventory intact,
     * because an Identity tab with unscored identities is still useful and an empty one is not.
     *
     * @param projectId GCP project whose IAM policy defines every grant
     * @param users     service accounts already fetched; grows to include user:/group: members
     * @param roles     custom roles already fetched, enriched with permission analysis
     */
    public PrivilegeAuditOutcome analyze(String projectId,
            List<DashboardData.IamUserDetail> users,
            List<DashboardData.IamRoleDetail> roles) {

        if (users == null || roles == null) {
            return PrivilegeAuditOutcome.degraded(
                    "Identity analysis did not run for this project.");
        }

        Policy policy;
        try {
            policy = fetchPolicy(projectId);
        } catch (com.google.api.gax.rpc.PermissionDeniedException e) {
            logger.warn("No permission to read the IAM policy for project {} — identities cannot be "
                    + "scored (needs resourcemanager.projects.getIamPolicy).", projectId);
            return PrivilegeAuditOutcome.degraded(
                    "The service account cannot read this project's IAM policy, so roles and excess-access "
                            + "findings are unavailable. Grant resourcemanager.projects.getIamPolicy "
                            + "(included in roles/iam.securityReviewer) and refresh.");
        } catch (Exception e) {
            logger.error("Failed to read the IAM policy for project {}: {}", projectId, e.getMessage());
            return PrivilegeAuditOutcome.degraded(
                    "This project's IAM policy could not be read, so identities are shown without a "
                            + "risk assessment.");
        }

        if (policy == null) {
            return PrivilegeAuditOutcome.degraded(
                    "Cloud Resource Manager returned no IAM policy for this project.");
        }

        // role -> members inverted into member -> roles, which is what a per-identity view needs.
        Map<String, List<GrantedRole>> byMember = pivot(policy);

        // Public bindings belong to no principal, so they are reported on a synthetic identity
        // rather than dropped. allUsers on any role is the most severe thing in a GCP project
        // and must not be invisible just because it has no member to hang off.
        attachRoles(users, roles, byMember, projectId);

        boolean keysDegraded = scoreServiceAccountKeys(projectId, users);

        for (DashboardData.IamRoleDetail role : roles) {
            scoreCustomRole(role);
        }

        return keysDegraded
                ? PrivilegeAuditOutcome.degraded(
                        "Service-account keys could not be listed, so long-lived credential findings "
                                + "are missing. Grant iam.serviceAccountKeys.list to complete the audit.")
                : PrivilegeAuditOutcome.complete();
    }

    // ── Policy fetch and pivot ──────────────────────────────────────────────────

    private Policy fetchPolicy(String projectId) {
        Optional<ProjectsClient> clientOpt = clients.getProjectsClient(projectId);
        if (clientOpt.isEmpty()) {
            return null;
        }
        try (ProjectsClient projects = clientOpt.get()) {
            // Version 3 rather than the default: without it the API silently omits conditional
            // bindings, so an owner grant behind an IAM Condition would not appear at all.
            return projects.getIamPolicy(GetIamPolicyRequest.newBuilder()
                    .setResource("projects/" + projectId)
                    .setOptions(GetPolicyOptions.newBuilder()
                            .setRequestedPolicyVersion(IAM_POLICY_VERSION)
                            .build())
                    .build());
        }
    }

    /** One role a member holds, plus whether an IAM Condition narrows it. */
    private record GrantedRole(String role, boolean conditional) {
    }

    private Map<String, List<GrantedRole>> pivot(Policy policy) {
        Map<String, List<GrantedRole>> byMember = new LinkedHashMap<>();
        for (Binding binding : policy.getBindingsList()) {
            GrantedRole granted = new GrantedRole(binding.getRole(), binding.hasCondition());
            for (String member : binding.getMembersList()) {
                byMember.computeIfAbsent(member, m -> new ArrayList<>()).add(granted);
            }
        }
        return byMember;
    }

    // ── Identity assembly and scoring ───────────────────────────────────────────

    private void attachRoles(List<DashboardData.IamUserDetail> users,
            List<DashboardData.IamRoleDetail> roles,
            Map<String, List<GrantedRole>> byMember,
            String projectId) {

        // Service accounts arrive with arn = "projects/PID/serviceAccounts/<email>"; bindings
        // key them as "serviceAccount:<email>". Index by email so the two can be joined.
        Map<String, DashboardData.IamUserDetail> existingByEmail = new LinkedHashMap<>();
        for (DashboardData.IamUserDetail user : users) {
            String email = emailFromServiceAccountName(user.getArn());
            if (email != null) {
                existingByEmail.put(email.toLowerCase(Locale.ROOT), user);
            }
        }

        Set<String> customRoleIds = new LinkedHashSet<>();
        for (DashboardData.IamRoleDetail role : roles) {
            if (role.getRoleId() != null) {
                customRoleIds.add(role.getRoleId());
            }
        }

        for (Map.Entry<String, List<GrantedRole>> entry : byMember.entrySet()) {
            String member = entry.getKey();
            List<GrantedRole> granted = entry.getValue();

            String type = memberType(member);
            String identifier = memberIdentifier(member);

            DashboardData.IamUserDetail identity = "serviceAccount".equals(type)
                    ? existingByEmail.get(identifier.toLowerCase(Locale.ROOT))
                    : null;

            if (identity == null) {
                // A principal that exists only in the policy: every human user and group, plus
                // service accounts from other projects that were granted access to this one.
                identity = new DashboardData.IamUserDetail();
                identity.setUserName(identifier);
                identity.setUserId(member);
                identity.setArn(member);
                identity.setGroups(List.of());
                users.add(identity);
            }

            List<DashboardData.IamPolicyDetail> held = new ArrayList<>(
                    identity.getAttachedPolicies() == null ? List.of() : identity.getAttachedPolicies());
            for (GrantedRole g : granted) {
                DashboardData.IamPolicyDetail policy = new DashboardData.IamPolicyDetail(
                        g.role(),
                        g.role(),
                        customRoleIds.contains(g.role()) ? "Custom" : "Predefined");
                policy.setDocumentAnalyzed(true);
                held.add(policy);
            }
            identity.setAttachedPolicies(held);

            scorePrincipal(identity, member, type, granted, projectId);
        }
    }

    private void scorePrincipal(DashboardData.IamUserDetail identity, String member, String type,
            List<GrantedRole> granted, String projectId) {

        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();
        boolean isPublic = "allUsers".equals(member) || "allAuthenticatedUsers".equals(member);
        boolean isServiceAccount = "serviceAccount".equals(type);

        Set<String> serviceAdminRoles = new LinkedHashSet<>();

        for (GrantedRole g : granted) {
            String role = g.role();
            // A Condition still grants the role, only in narrower circumstances. Downgrading
            // rather than clearing follows the AWS analyzer: hiding it would miss real admin
            // grants, flagging it at full severity would punish customers for adding guardrails.
            String worst = g.conditional() ? SecuritySeverity.HIGH : SecuritySeverity.CRITICAL;
            String conditionNote = g.conditional()
                    ? " An IAM Condition limits when this applies, but the grant itself is unrestricted."
                    : "";

            if (isPublic) {
                risks.add(risk(PUBLIC_BINDING, SecuritySeverity.CRITICAL,
                        "Granted to the public",
                        "The special member " + member + " grants " + role + " to anyone on the "
                                + "internet" + ("allAuthenticatedUsers".equals(member)
                                        ? " with any Google account." : ".")
                                + " Remove this binding.",
                        role, List.of(member)));
                continue;
            }

            if ("roles/owner".equals(role)) {
                risks.add(risk(PROJECT_OWNER, worst,
                        "Project owner",
                        "Holds the primitive owner role, which grants every permission in the project "
                                + "including the ability to change IAM." + conditionNote,
                        role, List.of()));
            } else if ("roles/editor".equals(role)) {
                risks.add(risk(PROJECT_EDITOR, g.conditional() ? SecuritySeverity.MEDIUM : SecuritySeverity.HIGH,
                        "Project editor",
                        "Holds the primitive editor role, which grants write access to every service in "
                                + "the project. Replace it with the specific predefined roles in use."
                                + conditionNote,
                        role, List.of()));
            } else if ("roles/viewer".equals(role)) {
                risks.add(risk(PROJECT_VIEWER, SecuritySeverity.LOW,
                        "Project-wide read access",
                        "Holds the primitive viewer role, which can read every resource in the project, "
                                + "including data in storage and databases.",
                        role, List.of()));
            }

            String escalationReason = ESCALATION_ROLES.get(role);
            if (escalationReason != null && !"roles/owner".equals(role)) {
                risks.add(risk(IMPERSONATION, SecuritySeverity.CRITICAL,
                        "Can escalate its own privileges",
                        "Holds " + role + ", which " + escalationReason
                                + ". Its effective access is therefore unbounded." + conditionNote,
                        role, List.of(role)));
            }

            // "roles/<service>.admin" is the granular equivalent of service-wide wildcard on AWS.
            if (role.endsWith(".admin") && !ESCALATION_ROLES.containsKey(role)) {
                serviceAdminRoles.add(role);
            }
        }

        if (!serviceAdminRoles.isEmpty()) {
            risks.add(risk(SERVICE_ADMIN, SecuritySeverity.HIGH,
                    "Service-wide administrator",
                    "Holds full administrative control of " + serviceAdminRoles.size()
                            + (serviceAdminRoles.size() == 1 ? " service" : " services")
                            + " rather than the specific permissions needed.",
                    null, capped(serviceAdminRoles)));
        }

        // A non-human identity holding the project is the highest-value credential in the
        // account: it has no MFA, no session expiry, and its key may sit in a repository.
        boolean holdsProject = risks.stream().anyMatch(r ->
                PROJECT_OWNER.equals(r.getCode()) || PROJECT_EDITOR.equals(r.getCode()));
        if (isServiceAccount && holdsProject) {
            risks.add(risk(SA_PROJECT_ADMIN, SecuritySeverity.CRITICAL,
                    "Service account owns the project",
                    "A non-human identity holds project-wide administrative access. Unlike a user it "
                            + "has no MFA and no session expiry, so a leaked key is a full project "
                            + "compromise.",
                    null, List.of(member)));
        }

        apply(identity, risks);
    }

    /**
     * Flags service accounts whose long-lived keys make their permissions portable.
     *
     * @return true when keys could not be listed, so the finding may be missing
     */
    private boolean scoreServiceAccountKeys(String projectId, List<DashboardData.IamUserDetail> users) {
        Optional<IAMClient> clientOpt = clients.getIamClient(projectId);
        if (clientOpt.isEmpty()) {
            return true;
        }

        boolean degraded = false;
        try (IAMClient iam = clientOpt.get()) {
            for (DashboardData.IamUserDetail user : users) {
                String name = serviceAccountResourceName(user.getArn(), projectId);
                if (name == null) {
                    continue;
                }
                try {
                    // USER_MANAGED only. Google rotates the SYSTEM_MANAGED keys itself, so
                    // reporting those would be noise on every single service account.
                    int keyCount = iam.listServiceAccountKeys(name,
                            List.of(com.google.iam.admin.v1.ListServiceAccountKeysRequest
                                    .KeyType.USER_MANAGED))
                            .getKeysList().size();
                    if (keyCount == 0) {
                        continue;
                    }

                    // Severity tracks what the key unlocks. A long-lived key on a least-privilege
                    // service account is hygiene; the same key on a project owner is the breach.
                    boolean privileged = user.isExcessPermissions();
                    List<DashboardData.IamAccessRisk> risks = new ArrayList<>(
                            user.getRisks() == null ? List.of() : user.getRisks());
                    risks.add(risk(LONG_LIVED_KEY,
                            privileged ? SecuritySeverity.HIGH : SecuritySeverity.MEDIUM,
                            "Long-lived key" + (keyCount > 1 ? "s" : ""),
                            "Has " + keyCount + " user-managed key" + (keyCount == 1 ? "" : "s")
                                    + " that never expire"
                                    + (privileged
                                            ? ", making this identity's privileged access portable to "
                                                    + "anyone who obtains the file."
                                            : ". Prefer workload identity federation or short-lived "
                                                    + "tokens."),
                            null, List.of()));
                    apply(user, risks);
                } catch (Exception e) {
                    logger.debug("Could not list keys for {}: {}", name, e.getMessage());
                    degraded = true;
                }
            }
        } catch (Exception e) {
            logger.warn("Service-account key audit failed for project {}: {}", projectId, e.getMessage());
            return true;
        }
        return degraded;
    }

    /** Custom roles can hide {@code setIamPolicy} or a wildcard behind an innocuous title. */
    private void scoreCustomRole(DashboardData.IamRoleDetail role) {
        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();

        Set<String> wildcards = new LinkedHashSet<>();
        Set<String> escalations = new LinkedHashSet<>();

        for (DashboardData.IamPolicyDetail entry : safe(role.getAttachedPolicies())) {
            if (!PERMISSION_TYPE.equals(entry.getType()) || entry.getPolicyName() == null) {
                continue;
            }
            String permission = entry.getPolicyName();
            if (permission.endsWith(".*") || "*".equals(permission)) {
                wildcards.add(permission);
            }
            if (ESCALATION_PERMISSIONS.contains(permission.toLowerCase(Locale.ROOT))) {
                escalations.add(permission);
            }
        }

        if (!wildcards.isEmpty()) {
            risks.add(risk(CUSTOM_ROLE_WILDCARD, SecuritySeverity.HIGH,
                    "Wildcard permissions",
                    "This custom role grants whole permission groups by wildcard rather than the "
                            + "specific permissions needed.",
                    role.getRoleName(), capped(wildcards)));
        }
        if (!escalations.isEmpty()) {
            risks.add(risk(IMPERSONATION, SecuritySeverity.CRITICAL,
                    "Custom role permits escalation",
                    "This custom role includes permissions that let its holders widen their own "
                            + "access, so anyone granted it is effectively an administrator.",
                    role.getRoleName(), capped(escalations)));
        }

        applyRole(role, risks);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    // ── Shared collapse logic ───────────────────────────────────────────────────

    private void apply(DashboardData.IamUserDetail identity, List<DashboardData.IamAccessRisk> risks) {
        List<DashboardData.IamAccessRisk> deduped = dedupe(risks);
        identity.setRisks(deduped);
        identity.setRiskLevel(worstOf(deduped));
        identity.setRiskScore(score(deduped));
        identity.setExcessPermissions(isExcess(deduped));
    }

    private void applyRole(DashboardData.IamRoleDetail role, List<DashboardData.IamAccessRisk> risks) {
        List<DashboardData.IamAccessRisk> deduped = dedupe(risks);
        role.setRisks(deduped);
        role.setRiskLevel(worstOf(deduped));
        role.setRiskScore(score(deduped));
        role.setExcessPermissions(isExcess(deduped));
    }

    /** One risk per code, most severe instance kept, most severe first. */
    private List<DashboardData.IamAccessRisk> dedupe(List<DashboardData.IamAccessRisk> risks) {
        Map<String, DashboardData.IamAccessRisk> best = new LinkedHashMap<>();
        for (DashboardData.IamAccessRisk r : risks) {
            DashboardData.IamAccessRisk existing = best.get(r.getCode());
            if (existing == null
                    || SecuritySeverity.rank(r.getSeverity()) < SecuritySeverity.rank(existing.getSeverity())) {
                best.put(r.getCode(), r);
            }
        }
        List<DashboardData.IamAccessRisk> out = new ArrayList<>(best.values());
        out.sort((a, b) -> SecuritySeverity.rank(a.getSeverity()) - SecuritySeverity.rank(b.getSeverity()));
        return out;
    }

    /**
     * Worst severity present, or NONE.
     *
     * <p>
     * NONE is safe here in a way it is not on AWS: the project IAM policy is a single document
     * listing every grant, so once it has been read there is no such thing as a partially
     * audited principal. A denied read fails the whole audit up front and never reaches this.
     */
    private String worstOf(List<DashboardData.IamAccessRisk> risks) {
        if (risks.isEmpty()) {
            return "NONE";
        }
        return SecuritySeverity.worstOf(
                risks.stream().map(DashboardData.IamAccessRisk::getSeverity).toList());
    }

    private boolean isExcess(List<DashboardData.IamAccessRisk> risks) {
        return !risks.isEmpty()
                && SecuritySeverity.atLeast(worstOf(risks), SecuritySeverity.HIGH);
    }

    private int score(List<DashboardData.IamAccessRisk> risks) {
        int total = 0;
        for (DashboardData.IamAccessRisk r : risks) {
            switch (SecuritySeverity.normalize(r.getSeverity())) {
                case SecuritySeverity.CRITICAL -> total += 40;
                case SecuritySeverity.HIGH -> total += 20;
                case SecuritySeverity.MEDIUM -> total += 8;
                default -> total += 3;
            }
        }
        return Math.min(total, 100);
    }

    // ── Member parsing ──────────────────────────────────────────────────────────

    /**
     * The prefix GCP uses to say what kind of principal this is.
     *
     * <p>
     * Members are {@code <type>:<identifier>} — {@code user:a@b.com},
     * {@code serviceAccount:x@y.iam.gserviceaccount.com}, {@code group:eng@b.com},
     * {@code domain:b.com} — except {@code allUsers} and {@code allAuthenticatedUsers}, which
     * have no colon at all and are exactly the two that matter most.
     */
    private static String memberType(String member) {
        int colon = member.indexOf(':');
        return colon < 0 ? "special" : member.substring(0, colon);
    }

    private static String memberIdentifier(String member) {
        int colon = member.indexOf(':');
        return colon < 0 ? member : member.substring(colon + 1);
    }

    /** Pulls the email out of {@code projects/PID/serviceAccounts/<email>}. */
    private static String emailFromServiceAccountName(String resourceName) {
        if (resourceName == null || !resourceName.contains("/serviceAccounts/")) {
            return null;
        }
        return resourceName.substring(resourceName.lastIndexOf('/') + 1);
    }

    /**
     * API resource name for a service account belonging to <em>this</em> project, else null.
     *
     * <p>
     * Only identities that came from {@code listServiceAccounts} qualify, which is what the
     * {@code projects/…/serviceAccounts/…} form indicates. A binding may also name a service
     * account owned by a different project — those arrive as the bare
     * {@code serviceAccount:<email>} member string, and synthesising a local resource name for
     * one would query a service account that does not exist here, turning a normal
     * cross-project grant into a spurious "audit degraded" warning.
     */
    private static String serviceAccountResourceName(String arn, String projectId) {
        if (arn == null || !arn.contains("/serviceAccounts/")) {
            return null;
        }
        return arn;
    }

    private static DashboardData.IamAccessRisk risk(String code, String severity, String title,
            String description, String sourcePolicy, List<String> evidence) {
        return new DashboardData.IamAccessRisk(code, severity, title, description, sourcePolicy, evidence);
    }

    private static List<String> capped(Set<String> values) {
        return values.stream().limit(MAX_EVIDENCE).toList();
    }
}
