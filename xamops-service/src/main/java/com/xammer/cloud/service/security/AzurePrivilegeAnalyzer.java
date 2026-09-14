package com.xammer.cloud.service.security;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.authorization.models.Permission;
import com.azure.resourcemanager.authorization.models.PrincipalType;
import com.azure.resourcemanager.authorization.models.RoleAssignment;
import com.azure.resourcemanager.authorization.models.RoleDefinition;
import com.xammer.cloud.dto.DashboardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether an Azure principal holds more permission than it should.
 *
 * <p>
 * Azure RBAC is the inverse of AWS IAM in the way that matters here. There is no policy document
 * attached to an identity; there are role <em>assignments</em> pointing at role
 * <em>definitions</em>, and the definition is where the permissions live. The previous code read
 * the assignment and stopped, so it recorded that a principal held role
 * {@code /subscriptions/…/providers/Microsoft.Authorization/roleDefinitions/8e3af657-…} and
 * nothing about what that GUID could do. Resolving the definition is what turns that into
 * "Owner, which can do anything in this subscription".
 *
 * <p>
 * Three Azure-specific judgements.
 *
 * <p>
 * <b>Definitions are resolved and cached, not pattern-matched.</b> Elsewhere in this codebase
 * {@code AzureWarGovernanceChecks.roleName} hardcodes the Owner, Contributor and User Access
 * Administrator GUIDs and string-matches them. That works for exactly those three and silently
 * passes every custom role that grants {@code *}. Reading {@code permissions()} catches both,
 * and the built-in GUIDs repeat so heavily across assignments that a cache makes it cheaper than
 * it looks.
 *
 * <p>
 * <b>{@code Microsoft.Authorization/*} is escalation, not just breadth.</b> A principal that can
 * write role assignments can grant itself Owner, so its effective permission is unbounded no
 * matter how narrow the rest of its role appears.
 *
 * <p>
 * <b>Names are best-effort.</b> Resolving a principal GUID to a display name goes through
 * Microsoft Graph, and the Reader-scoped service principal used for onboarding usually lacks
 * {@code Directory.Read.All}. A failure there degrades the label to the GUID and is reported in
 * the note; it never fails the audit, because a scored identity with an ugly name is far more
 * useful than no identity at all.
 *
 * @see IamPrivilegeAnalyzer the AWS equivalent, whose conventions this follows
 */
@Service
public class AzurePrivilegeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(AzurePrivilegeAnalyzer.class);

    // --- Finding codes. Stable; remediation content is keyed off these. ---
    public static final String SUBSCRIPTION_OWNER = "AZ-EXC-001";
    public static final String BROAD_WRITE = "AZ-EXC-002";
    public static final String PRIVILEGE_ESCALATION = "AZ-EXC-003";
    public static final String PROVIDER_WILDCARD = "AZ-EXC-004";
    public static final String DATA_PLANE_WILDCARD = "AZ-EXC-005";
    public static final String NON_HUMAN_OWNER = "AZ-EXC-006";
    public static final String INVERTED_ALLOW = "AZ-EXC-007";

    private static final int MAX_EVIDENCE = 6;

    /**
     * Actions that let a principal grant itself more permission.
     *
     * <p>
     * {@code elevateAccess} is the one people forget: it lets a User Access Administrator take
     * ownership of every subscription in the tenant.
     */
    private static final Set<String> ESCALATION_ACTIONS = Set.of(
            "microsoft.authorization/roleassignments/write",
            "microsoft.authorization/roledefinitions/write",
            "microsoft.authorization/elevateaccess/action",
            "microsoft.authorization/*",
            "microsoft.authorization/*/write");

    /** Built-in roles broad enough to matter on sight, even if the definition read is denied. */
    private static final Map<String, String> BROAD_BUILTIN_ROLES = Map.of(
            "owner", "full control of everything in scope, including the ability to grant access to others",
            "contributor", "permission to create and modify every resource in scope",
            "user access administrator", "control over who has access to everything in scope",
            "role based access control administrator",
                    "control over role assignments, so it can grant itself any other role");

    /**
     * Enriches every supplied identity in place with its resolved role permissions and risk score.
     *
     * <p>
     * Never throws. An audit failure degrades to "unknown" and leaves the inventory intact.
     *
     * @param azure      authenticated client for the subscription
     * @param users      role-assignment principals, whose attachedPolicies are replaced with
     *                   resolved role detail
     * @param roles      role definitions to score in their own right
     * @param truncated  whether the caller stopped listing early, which must degrade the outcome
     * @param namesResolved whether principal GUIDs were successfully resolved to display names
     */
    public PrivilegeAuditOutcome analyze(AzureResourceManager azure,
            List<DashboardData.IamUserDetail> users,
            List<DashboardData.IamRoleDetail> roles,
            List<RoleAssignment> assignments,
            boolean truncated,
            boolean namesResolved) {

        if (users == null || roles == null || assignments == null) {
            return PrivilegeAuditOutcome.degraded("Identity analysis did not run for this subscription.");
        }

        Definitions definitions = new Definitions(azure);

        // Assignments are positionally aligned with users by the caller, but relying on index
        // parallelism across two lists is exactly the kind of coupling that breaks on the next
        // edit. Key on the assignment id instead, which the caller stored as the identity's arn.
        Map<String, RoleAssignment> byId = new LinkedHashMap<>();
        for (RoleAssignment assignment : assignments) {
            if (assignment.id() != null) {
                byId.put(assignment.id(), assignment);
            }
        }

        for (DashboardData.IamUserDetail user : users) {
            try {
                RoleAssignment assignment = byId.get(user.getArn());
                if (assignment == null) {
                    continue;
                }
                scorePrincipal(definitions, user, assignment);
            } catch (Exception e) {
                logger.warn("Azure privilege analysis failed for principal {}: {}",
                        user.getUserId(), e.getMessage());
            }
        }

        for (DashboardData.IamRoleDetail role : roles) {
            try {
                scoreRoleDefinition(definitions, role);
            } catch (Exception e) {
                logger.warn("Azure privilege analysis failed for role {}: {}",
                        role.getRoleName(), e.getMessage());
            }
        }

        return outcome(definitions, truncated, namesResolved);
    }

    private PrivilegeAuditOutcome outcome(Definitions definitions, boolean truncated, boolean namesResolved) {
        List<String> problems = new ArrayList<>();
        if (definitions.denials > 0) {
            problems.add("some role definitions could not be read, so a few assignments show as "
                    + "unassessed rather than clean — grant Microsoft.Authorization/roleDefinitions/read");
        }
        if (truncated) {
            problems.add("this subscription has more role assignments than a single audit pass "
                    + "inspects, so the identities shown are accurate but incomplete");
        }
        if (!namesResolved) {
            problems.add("principal names could not be resolved from Microsoft Entra ID, so identities "
                    + "are labelled by object ID — grant the app registration Directory.Read.All");
        }
        if (problems.isEmpty()) {
            return PrivilegeAuditOutcome.complete();
        }
        return PrivilegeAuditOutcome.degraded(
                Character.toUpperCase(problems.get(0).charAt(0)) + problems.get(0).substring(1)
                        + (problems.size() > 1
                                ? ". Also: " + String.join("; ", problems.subList(1, problems.size()))
                                : "")
                        + ".");
    }

    // ── Principal scoring ───────────────────────────────────────────────────────

    private void scorePrincipal(Definitions definitions, DashboardData.IamUserDetail user,
            RoleAssignment assignment) {

        RoleDefinition definition = definitions.resolve(assignment.roleDefinitionId());

        // An ABAC condition still grants the role, only in narrower circumstances. Downgrading
        // rather than clearing matches the AWS analyzer's treatment of a policy Condition.
        boolean conditional = assignment.condition() != null && !assignment.condition().isBlank();

        String roleName = definition != null ? definition.roleName() : shortRoleId(assignment.roleDefinitionId());
        boolean subscriptionScope = isSubscriptionScope(assignment.scope());

        DashboardData.IamPolicyDetail policy = new DashboardData.IamPolicyDetail(
                roleName,
                assignment.roleDefinitionId(),
                definition != null ? definition.type() : null);
        policy.setDocumentAnalyzed(definition != null);

        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();
        Verdict verdict = definition == null
                ? new Verdict(false, Set.of(), Set.of(), Set.of(), false)
                : inspect(definition);

        policy.setAdminAccess(verdict.admin());
        policy.setWildcardActions(new ArrayList<>(verdict.providerWildcards()));
        policy.setEscalationActions(new ArrayList<>(verdict.escalations()));
        user.setAttachedPolicies(List.of(policy));

        // Name check first, so Owner is caught even when the definition read is denied.
        String reason = BROAD_BUILTIN_ROLES.get(roleName == null ? "" : roleName.toLowerCase(Locale.ROOT));
        boolean isOwner = "owner".equalsIgnoreCase(roleName);
        if (reason != null) {
            risks.add(risk(isOwner ? SUBSCRIPTION_OWNER : BROAD_WRITE,
                    severity(isOwner ? SecuritySeverity.CRITICAL : SecuritySeverity.HIGH, conditional),
                    isOwner ? "Owner" : "Broad built-in role: " + roleName,
                    "Holds the " + roleName + " role at " + describeScope(assignment.scope())
                            + ", which grants " + reason + "."
                            + (conditional ? " An ABAC condition limits when this applies." : ""),
                    roleName, List.of()));
        }

        if (verdict.admin() && reason == null) {
            risks.add(risk(SUBSCRIPTION_OWNER, severity(SecuritySeverity.CRITICAL, conditional),
                    "Unrestricted access",
                    "The " + roleName + " role grants every action (\"*\") at "
                            + describeScope(assignment.scope()) + "."
                            + (conditional ? " An ABAC condition limits when this applies." : ""),
                    roleName, List.of("actions: *")));
        }

        if (!verdict.escalations().isEmpty()) {
            risks.add(risk(PRIVILEGE_ESCALATION, SecuritySeverity.CRITICAL,
                    "Can escalate its own privileges",
                    "Holds authorization actions that allow granting itself additional roles, so its "
                            + "effective access is unbounded regardless of what else is assigned.",
                    roleName, capped(verdict.escalations())));
        }

        if (!verdict.providerWildcards().isEmpty()) {
            risks.add(risk(PROVIDER_WILDCARD, SecuritySeverity.HIGH,
                    "Provider-wide wildcard permissions",
                    "Grants every action across " + verdict.providerWildcards().size()
                            + (verdict.providerWildcards().size() == 1 ? " resource provider"
                                    : " resource providers")
                            + " rather than the specific operations needed.",
                    roleName, capped(verdict.providerWildcards())));
        }

        if (!verdict.dataWildcards().isEmpty()) {
            risks.add(risk(DATA_PLANE_WILDCARD, SecuritySeverity.HIGH,
                    "Unrestricted data access",
                    "Grants wildcard data-plane actions, which reach the contents of storage accounts, "
                            + "key vaults and databases rather than just their configuration.",
                    roleName, capped(verdict.dataWildcards())));
        }

        if (verdict.inverted()) {
            risks.add(risk(INVERTED_ALLOW, SecuritySeverity.HIGH,
                    "Allow-by-exclusion role",
                    "This role allows everything except a named list (notActions). Any operation Azure "
                            + "adds in future is granted automatically.",
                    roleName, List.of()));
        }

        // A non-human credential holding the subscription has no MFA and no session expiry, so a
        // leaked secret is a full compromise. Mirrors the AZURE-9 WAR control.
        PrincipalType type = principalType(assignment);
        boolean nonHuman = type != null && PrincipalType.SERVICE_PRINCIPAL.equals(type);
        if (nonHuman && (isOwner || verdict.admin()) && subscriptionScope) {
            risks.add(risk(NON_HUMAN_OWNER, SecuritySeverity.CRITICAL,
                    "Service principal owns the subscription",
                    "A non-human identity holds subscription-wide administrative access. It has no MFA "
                            + "and no session expiry, so a leaked client secret is a full subscription "
                            + "compromise.",
                    roleName, List.of()));
        }

        apply(user, risks);
    }

    // ── Role-definition scoring ─────────────────────────────────────────────────

    private void scoreRoleDefinition(Definitions definitions, DashboardData.IamRoleDetail role) {
        RoleDefinition definition = definitions.resolve(role.getRoleId());
        if (definition == null) {
            role.setRiskLevel(null);
            return;
        }

        Verdict verdict = inspect(definition);
        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();

        // Azure ships ~700 built-in roles that every subscription has and nobody can change.
        // Flagging Owner as a risky *definition* would put a red card on a shared Microsoft
        // artifact rather than on anything the customer did. Only custom roles are scored here;
        // the risk of holding a broad built-in role is reported on the principal that holds it.
        boolean custom = definition.type() != null
                && definition.type().toLowerCase(Locale.ROOT).contains("custom");
        if (!custom) {
            role.setRiskLevel("NONE");
            role.setRisks(List.of());
            role.setExcessPermissions(false);
            attachPermissions(role, definition);
            return;
        }

        if (verdict.admin()) {
            risks.add(risk(SUBSCRIPTION_OWNER, SecuritySeverity.CRITICAL,
                    "Custom role grants everything",
                    "This custom role grants every action (\"*\"), making anyone assigned it an "
                            + "administrator of its assignable scopes.",
                    definition.roleName(), List.of("actions: *")));
        }
        if (!verdict.escalations().isEmpty()) {
            risks.add(risk(PRIVILEGE_ESCALATION, SecuritySeverity.CRITICAL,
                    "Custom role permits escalation",
                    "This custom role includes authorization actions that let its holders grant "
                            + "themselves further access.",
                    definition.roleName(), capped(verdict.escalations())));
        }
        if (!verdict.providerWildcards().isEmpty()) {
            risks.add(risk(PROVIDER_WILDCARD, SecuritySeverity.HIGH,
                    "Provider-wide wildcard permissions",
                    "This custom role grants every action across "
                            + verdict.providerWildcards().size() + " resource provider(s).",
                    definition.roleName(), capped(verdict.providerWildcards())));
        }
        if (!verdict.dataWildcards().isEmpty()) {
            risks.add(risk(DATA_PLANE_WILDCARD, SecuritySeverity.HIGH,
                    "Unrestricted data access",
                    "This custom role grants wildcard data-plane actions, reaching the contents of "
                            + "storage, key vaults and databases.",
                    definition.roleName(), capped(verdict.dataWildcards())));
        }
        if (verdict.inverted()) {
            risks.add(risk(INVERTED_ALLOW, SecuritySeverity.HIGH,
                    "Allow-by-exclusion role",
                    "This custom role allows everything except a named list (notActions), so future "
                            + "Azure operations are granted automatically.",
                    definition.roleName(), List.of()));
        }

        attachPermissions(role, definition);
        applyRole(role, risks);
    }

    /** Surfaces the role's actual actions so the card can show what it permits. */
    private void attachPermissions(DashboardData.IamRoleDetail role, RoleDefinition definition) {
        List<DashboardData.IamPolicyDetail> entries = new ArrayList<>();
        for (Permission permission : safeSet(definition.permissions())) {
            for (String action : safe(permission.actions())) {
                DashboardData.IamPolicyDetail entry = new DashboardData.IamPolicyDetail(
                        action, null, GcpPrivilegeAnalyzer.PERMISSION_TYPE);
                entry.setDocumentAnalyzed(true);
                entries.add(entry);
                if (entries.size() >= 40) {
                    break;
                }
            }
            if (entries.size() >= 40) {
                break;
            }
        }
        role.setAttachedPolicies(entries);
        if (role.getDescription() == null) {
            role.setDescription(definition.description());
        }
    }

    // ── Permission inspection ───────────────────────────────────────────────────

    private record Verdict(boolean admin, Set<String> providerWildcards, Set<String> escalations,
            Set<String> dataWildcards, boolean inverted) {
    }

    private Verdict inspect(RoleDefinition definition) {
        boolean admin = false;
        boolean inverted = false;
        Set<String> providerWildcards = new LinkedHashSet<>();
        Set<String> escalations = new LinkedHashSet<>();
        Set<String> dataWildcards = new LinkedHashSet<>();

        for (Permission permission : safeSet(definition.permissions())) {
            if (!safe(permission.notActions()).isEmpty()) {
                inverted = true;
            }

            for (String raw : safe(permission.actions())) {
                String action = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if (action.isEmpty()) {
                    continue;
                }
                if ("*".equals(action)) {
                    admin = true;
                    continue;
                }
                if (ESCALATION_ACTIONS.contains(action)) {
                    escalations.add(raw);
                }
                // "Microsoft.Storage/*" is provider-wide; "Microsoft.Storage/x/read" is not.
                if (action.endsWith("/*")) {
                    providerWildcards.add(raw);
                }
            }

            for (String raw : safe(permission.dataActions())) {
                String action = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
                if ("*".equals(action) || action.endsWith("/*")) {
                    dataWildcards.add(raw);
                }
            }
        }

        return new Verdict(admin, providerWildcards, escalations, dataWildcards, inverted);
    }

    /**
     * Resolves and caches role definitions for one audit pass.
     *
     * <p>
     * Built-in role GUIDs repeat across nearly every assignment in a subscription, so without the
     * cache a 200-assignment subscription would make 200 ARM calls to look up the same handful of
     * roles.
     */
    private static final class Definitions {
        private final AzureResourceManager azure;
        private final Map<String, RoleDefinition> cache = new HashMap<>();
        private final Set<String> failed = new LinkedHashSet<>();
        private int denials;

        Definitions(AzureResourceManager azure) {
            this.azure = azure;
        }

        RoleDefinition resolve(String roleDefinitionId) {
            if (roleDefinitionId == null || roleDefinitionId.isBlank() || failed.contains(roleDefinitionId)) {
                return null;
            }
            if (cache.containsKey(roleDefinitionId)) {
                return cache.get(roleDefinitionId);
            }
            try {
                RoleDefinition definition = azure.accessManagement().roleDefinitions()
                        .getById(roleDefinitionId);
                cache.put(roleDefinitionId, definition);
                return definition;
            } catch (Exception e) {
                // Cached as failed so a denied definition is not retried once per assignment.
                failed.add(roleDefinitionId);
                denials++;
                logger.debug("Could not resolve Azure role definition {}: {}",
                        roleDefinitionId, e.getMessage());
                return null;
            }
        }
    }

    // ── Shared collapse logic ───────────────────────────────────────────────────

    private void apply(DashboardData.IamUserDetail identity, List<DashboardData.IamAccessRisk> risks) {
        List<DashboardData.IamAccessRisk> deduped = dedupe(risks);
        identity.setRisks(deduped);
        identity.setRiskLevel(level(deduped, identity.getAttachedPolicies()));
        identity.setRiskScore(score(deduped));
        identity.setExcessPermissions(!deduped.isEmpty()
                && SecuritySeverity.atLeast(worstOf(deduped), SecuritySeverity.HIGH));
    }

    private void applyRole(DashboardData.IamRoleDetail role, List<DashboardData.IamAccessRisk> risks) {
        List<DashboardData.IamAccessRisk> deduped = dedupe(risks);
        role.setRisks(deduped);
        role.setRiskLevel(deduped.isEmpty() ? "NONE" : worstOf(deduped));
        role.setRiskScore(score(deduped));
        role.setExcessPermissions(!deduped.isEmpty()
                && SecuritySeverity.atLeast(worstOf(deduped), SecuritySeverity.HIGH));
    }

    /**
     * Risk level for a principal, distinguishing clean from unaudited.
     *
     * <p>
     * Null rather than NONE when the role definition could not be read: an assignment we could
     * not resolve must render as "Not assessed", not as a green clean bill. Same asymmetry as the
     * AWS analyzer — a missing audit only ever suppresses a clean verdict, never a finding.
     */
    private String level(List<DashboardData.IamAccessRisk> risks,
            List<DashboardData.IamPolicyDetail> policies) {
        if (!risks.isEmpty()) {
            return worstOf(risks);
        }
        boolean anyUnread = policies != null && policies.stream()
                .anyMatch(p -> !p.isDocumentAnalyzed());
        return anyUnread ? null : "NONE";
    }

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

    private String worstOf(List<DashboardData.IamAccessRisk> risks) {
        return SecuritySeverity.worstOf(
                risks.stream().map(DashboardData.IamAccessRisk::getSeverity).toList());
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

    // ── Small helpers ───────────────────────────────────────────────────────────

    /** One severity step down when an ABAC condition narrows the grant. */
    private static String severity(String base, boolean conditional) {
        if (!conditional) {
            return base;
        }
        return switch (base) {
            case SecuritySeverity.CRITICAL -> SecuritySeverity.HIGH;
            case SecuritySeverity.HIGH -> SecuritySeverity.MEDIUM;
            default -> SecuritySeverity.LOW;
        };
    }

    /** {@code principalType} lives on the inner model, not the {@code RoleAssignment} interface. */
    private static PrincipalType principalType(RoleAssignment assignment) {
        try {
            return assignment.innerModel() == null ? null : assignment.innerModel().principalType();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSubscriptionScope(String scope) {
        return scope != null && scope.matches("(?i)/subscriptions/[^/]+");
    }

    /** ARM scopes are long paths; the reader needs to know how wide it is, not the full id. */
    static String describeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "an unknown scope";
        }
        if (isSubscriptionScope(scope)) {
            return "subscription scope";
        }
        if (scope.matches("(?i)/subscriptions/[^/]+/resourcegroups/[^/]+")) {
            return "resource group " + scope.substring(scope.lastIndexOf('/') + 1);
        }
        if (scope.equals("/")) {
            return "tenant root scope";
        }
        int slash = scope.lastIndexOf('/');
        return slash >= 0 ? "resource " + scope.substring(slash + 1) : scope;
    }

    /** Last path segment of a role definition id, for when the definition cannot be read. */
    private static String shortRoleId(String roleDefinitionId) {
        if (roleDefinitionId == null || roleDefinitionId.isBlank()) {
            return "unknown role";
        }
        int slash = roleDefinitionId.lastIndexOf('/');
        return slash >= 0 ? roleDefinitionId.substring(slash + 1) : roleDefinitionId;
    }

    private static DashboardData.IamAccessRisk risk(String code, String severity, String title,
            String description, String sourcePolicy, List<String> evidence) {
        return new DashboardData.IamAccessRisk(code, severity, title, description, sourcePolicy, evidence);
    }

    private static List<String> capped(Set<String> values) {
        return values.stream().limit(MAX_EVIDENCE).toList();
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static <T> Set<T> safeSet(Set<T> set) {
        return set == null ? Collections.emptySet() : set;
    }
}
