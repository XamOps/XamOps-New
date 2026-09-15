package com.xammer.cloud.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.DashboardData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.iam.IamClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether an IAM identity holds more permission than it should.
 *
 * <p>
 * This exists because the Identity tab of the Security Center previously showed policy
 * <em>names</em> and nothing else. A name is not a permission: {@code DeveloperAccess} may be
 * scoped to one bucket or may be {@code Action:*} on {@code Resource:*}, and the console gave
 * the reader no way to tell. Nothing in the codebase read a policy <em>document</em> for a user
 * or role, so "which of my people are over-privileged" was unanswerable.
 *
 * <p>
 * Three decisions are worth knowing before changing this class.
 *
 * <p>
 * <b>It mutates the DTOs in place.</b> The caller has already built the user and role lists from
 * paginated IAM calls; returning a parallel structure keyed on ARN would mean a second join and
 * a second chance to mismatch. The trade-off is that this class is only safe to call once per
 * list, before the list is cached.
 *
 * <p>
 * <b>Absence of a finding is not evidence of safety.</b> The read-only onboarding role does not
 * necessarily grant {@code iam:GetPolicyVersion} or {@code iam:GetUserPolicy}, and an account
 * that denies them produces zero findings — identical to a genuinely least-privilege account.
 * Every denial is therefore counted, and {@link PrivilegeAuditOutcome#degraded} tells the UI to say
 * "not audited" instead of rendering a clean page. This mirrors {@link DetectorCoverageService},
 * which exists for the same reason on the findings side.
 *
 * <p>
 * <b>A guarded wildcard is downgraded, not cleared.</b> A statement carrying a {@code Condition}
 * still grants the wildcard, but only in context (an IP range, an MFA assertion, a tag match).
 * Treating those as clean would hide real admin grants; treating them as full admin would drown
 * the customer in false criticals from their own guardrails. They are reported one severity step
 * down, with the reason stated in the finding.
 */
@Service
public class IamPrivilegeAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(IamPrivilegeAnalyzer.class);

    // --- Finding codes. Stable; remediation content is keyed off these. ---
    public static final String FULL_ADMIN = "IAM-EXC-001";
    public static final String SERVICE_WILDCARD = "IAM-EXC-002";
    public static final String PRIVILEGE_ESCALATION = "IAM-EXC-003";
    public static final String INVERTED_ALLOW = "IAM-EXC-004";
    public static final String BROAD_MANAGED_POLICY = "IAM-EXC-005";
    public static final String ADMIN_WITHOUT_MFA = "IAM-EXC-006";
    public static final String BROAD_AND_STALE = "IAM-EXC-007";

    /**
     * Upper bound on unique policy documents fetched per account scan.
     *
     * <p>
     * Each unique managed policy costs two IAM calls ({@code GetPolicy} for the default version,
     * then {@code GetPolicyVersion}), and IAM is a low-TPS global endpoint that throttles hard.
     * An account with hundreds of distinct policies would otherwise turn a dashboard load into a
     * multi-minute stall. Hitting the cap degrades the outcome rather than silently truncating,
     * so a partial audit is never presented as a complete one.
     */
    private static final int MAX_DOCUMENT_FETCHES = 400;

    /** Evidence is for orientation, not an audit log; the full policy is one click away in AWS. */
    private static final int MAX_EVIDENCE = 6;

    private static final long STALE_DAYS = 90;

    /**
     * AWS-managed policies broad enough to matter on sight.
     *
     * <p>
     * Checked by name so they are caught even when document reads are denied — which is exactly
     * the situation where the customer most needs to hear that someone holds
     * {@code AdministratorAccess}.
     */
    private static final Map<String, String> BROAD_AWS_POLICIES = Map.of(
            "administratoraccess", "unrestricted access to every service and resource",
            "poweruseraccess", "full access to every service except IAM and Organizations",
            "iamfullaccess", "full control of IAM, which allows granting itself any other permission",
            "amazons3fullaccess", "full access to every S3 bucket in the account",
            "awsorganizationsfullaccess", "full control of the AWS Organization and its member accounts");

    /**
     * Actions that let a principal grant itself more permission.
     *
     * <p>
     * These are the primitives behind the documented IAM privilege-escalation paths: rewriting a
     * policy, attaching a new one, minting credentials, or assuming a more powerful role. A
     * read-only-looking identity holding any of them is effectively an administrator, which is
     * why they are CRITICAL rather than HIGH even when scoped.
     */
    private static final Set<String> ESCALATION_ACTIONS = Set.of(
            "iam:createpolicyversion",
            "iam:setdefaultpolicyversion",
            "iam:attachuserpolicy",
            "iam:attachgrouppolicy",
            "iam:attachrolepolicy",
            "iam:putuserpolicy",
            "iam:putgrouppolicy",
            "iam:putrolepolicy",
            "iam:createaccesskey",
            "iam:createloginprofile",
            "iam:updateloginprofile",
            "iam:addusertogroup",
            "iam:updateassumerolepolicy",
            "iam:createrole",
            "iam:passrole");

    private final ObjectMapper objectMapper;

    public IamPrivilegeAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Scores every supplied user and role, enriching them in place.
     *
     * <p>
     * Never throws. A permission audit that fails closed would take the whole Identity tab
     * down with it, so every failure path degrades to "unknown" for the affected identity and
     * the scan continues.
     *
     * @param iam   client for the target account, reused from the caller's scan
     * @param users may be null or empty
     * @param roles may be null or empty
     */
    public PrivilegeAuditOutcome analyze(IamClient iam,
            List<DashboardData.IamUserDetail> users,
            List<DashboardData.IamRoleDetail> roles) {

        Scan scan = new Scan(iam);

        if (users != null) {
            for (DashboardData.IamUserDetail user : users) {
                try {
                    scoreUser(scan, user);
                } catch (Exception e) {
                    logger.warn("Privilege analysis failed for user {}: {}", user.getUserName(), e.getMessage());
                    markUnknown(user.getUserName(), e, scan);
                }
            }
        }

        if (roles != null) {
            for (DashboardData.IamRoleDetail role : roles) {
                try {
                    scoreRole(scan, role);
                } catch (Exception e) {
                    logger.warn("Privilege analysis failed for role {}: {}", role.getRoleName(), e.getMessage());
                    markUnknown(role.getRoleName(), e, scan);
                }
            }
        }

        return scan.outcome();
    }

    // ── Identity scoring ────────────────────────────────────────────────────────

    private void scoreUser(Scan scan, DashboardData.IamUserDetail user) {
        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();
        boolean holdsAdmin = false;
        boolean fullyAudited = true;

        for (DashboardData.IamPolicyDetail policy : safe(user.getAttachedPolicies())) {
            PolicyVerdict verdict = inspect(scan, policy, IdentityKind.USER, user.getUserName());
            risks.addAll(verdict.risks());
            holdsAdmin |= verdict.admin();
            fullyAudited &= policy.isDocumentAnalyzed();
        }

        // Layered on top of the policy verdicts rather than folded into them: the same wildcard
        // is a materially different exposure on a password-only console user than on a
        // federated role, and that difference is a property of the identity, not the policy.
        if (holdsAdmin && !user.isMfaEnabled()) {
            risks.add(risk(ADMIN_WITHOUT_MFA, SecuritySeverity.HIGH,
                    "Administrator without MFA",
                    "This user can perform any action in the account and is protected by a password alone. "
                            + "A single credential leak is a full account compromise.",
                    null, List.of()));
        }

        if (holdsAdmin && isStale(user.getPasswordLastUsed())) {
            risks.add(risk(BROAD_AND_STALE, SecuritySeverity.MEDIUM,
                    "Unused administrator credential",
                    "This user holds broad permissions but has not signed in for over " + STALE_DAYS
                            + " days, so the access is a standing risk with no demonstrated need.",
                    null, List.of()));
        }

        apply(risks, fullyAudited,
                user::setRiskLevel, user::setRiskScore,
                user::setRisks, user::setExcessPermissions);
    }

    private void scoreRole(Scan scan, DashboardData.IamRoleDetail role) {
        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();
        boolean fullyAudited = true;

        for (DashboardData.IamPolicyDetail policy : safe(role.getAttachedPolicies())) {
            risks.addAll(inspect(scan, policy, IdentityKind.ROLE, role.getRoleName()).risks());
            fullyAudited &= policy.isDocumentAnalyzed();
        }

        apply(risks, fullyAudited,
                role::setRiskLevel, role::setRiskScore,
                role::setRisks, role::setExcessPermissions);
    }

    /**
     * Collapses a risk list onto an identity.
     *
     * <p>
     * {@code excessPermissions} is deliberately gated at HIGH. It drives the red card in the UI,
     * and a red card that fires on advisory MEDIUMs trains people to ignore red.
     *
     * <p>
     * The {@code fullyAudited} distinction is the important one. An identity with no findings
     * whose documents were all read is "NONE" and renders green; an identity with no findings
     * because every read was denied gets a null level and renders "Not assessed". Collapsing
     * those two into "NONE" would hand a customer a green badge on an identity nobody
     * inspected, which is the single most misleading thing this class could do. Note the
     * asymmetry: a missing audit only ever suppresses a <em>clean</em> verdict. Findings that
     * survived a partial read — an {@code AdministratorAccess} attachment caught by name, for
     * instance — are still reported at full severity.
     *
     * @param fullyAudited whether every attached policy document was successfully read
     */
    private void apply(List<DashboardData.IamAccessRisk> risks,
            boolean fullyAudited,
            java.util.function.Consumer<String> setLevel,
            java.util.function.IntConsumer setScore,
            java.util.function.Consumer<List<DashboardData.IamAccessRisk>> setRisks,
            java.util.function.Consumer<Boolean> setExcess) {

        List<DashboardData.IamAccessRisk> deduped = dedupe(risks);
        deduped.sort((a, b) -> SecuritySeverity.rank(a.getSeverity()) - SecuritySeverity.rank(b.getSeverity()));

        String worst;
        if (!deduped.isEmpty()) {
            worst = SecuritySeverity.worstOf(
                    deduped.stream().map(DashboardData.IamAccessRisk::getSeverity).toList());
        } else if (fullyAudited) {
            worst = "NONE";
        } else {
            // Left null so the UI renders "Not assessed" rather than a clean bill of health.
            worst = null;
        }

        setRisks.accept(deduped);
        setLevel.accept(worst);
        setScore.accept(score(deduped));
        setExcess.accept(worst != null && SecuritySeverity.atLeast(worst, SecuritySeverity.HIGH));
    }

    /**
     * One risk per code per identity, keeping the most severe instance.
     *
     * <p>
     * Without this, a user in three groups that each attach a wildcard policy shows the same
     * finding three times, and the card reads as three problems instead of one.
     */
    private List<DashboardData.IamAccessRisk> dedupe(List<DashboardData.IamAccessRisk> risks) {
        Map<String, DashboardData.IamAccessRisk> best = new java.util.LinkedHashMap<>();
        for (DashboardData.IamAccessRisk r : risks) {
            DashboardData.IamAccessRisk existing = best.get(r.getCode());
            if (existing == null
                    || SecuritySeverity.rank(r.getSeverity()) < SecuritySeverity.rank(existing.getSeverity())) {
                best.put(r.getCode(), r);
            }
        }
        return new ArrayList<>(best.values());
    }

    /** Weighted, saturating. Ranks identities against each other; not a probability. */
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

    // ── Policy inspection ───────────────────────────────────────────────────────

    private record PolicyVerdict(List<DashboardData.IamAccessRisk> risks, boolean admin) {
    }

    private PolicyVerdict inspect(Scan scan, DashboardData.IamPolicyDetail policy,
            IdentityKind kind, String identityName) {

        List<DashboardData.IamAccessRisk> risks = new ArrayList<>();
        boolean admin = false;

        // Where the grant actually lives. Telling someone to detach a policy from a user when it
        // is attached to their group sends them somewhere they cannot fix it.
        String via = policy.getInheritedFrom() != null && !policy.getInheritedFrom().isBlank()
                ? " (inherited from group " + policy.getInheritedFrom() + ")"
                : "";

        // Name check first — it is free and survives a denied document read.
        String bareName = policy.getPolicyName() == null
                ? ""
                : policy.getPolicyName().toLowerCase(Locale.ROOT);
        String reason = BROAD_AWS_POLICIES.get(bareName);
        if (reason != null) {
            boolean isAdminPolicy = bareName.equals("administratoraccess");
            admin = isAdminPolicy || bareName.equals("poweruseraccess") || bareName.equals("iamfullaccess");
            risks.add(risk(BROAD_MANAGED_POLICY,
                    isAdminPolicy ? SecuritySeverity.CRITICAL : SecuritySeverity.HIGH,
                    "Over-broad AWS policy: " + policy.getPolicyName(),
                    "The " + policy.getPolicyName() + " policy" + via + " grants " + reason
                            + ". Replace it with a scoped policy covering only the actions in use.",
                    policyLabel(policy), List.of()));
        }

        Statements statements = scan.documentFor(policy, kind, identityName);
        if (statements == null) {
            policy.setDocumentAnalyzed(false);
            return new PolicyVerdict(risks, admin);
        }

        policy.setDocumentAnalyzed(true);
        Set<String> wildcards = new LinkedHashSet<>();
        Set<String> escalations = new LinkedHashSet<>();

        for (Statement st : statements.allow()) {
            if (st.notAction()) {
                risks.add(risk(INVERTED_ALLOW,
                        st.conditional() ? SecuritySeverity.MEDIUM : SecuritySeverity.HIGH,
                        "Allow-by-exclusion policy",
                        "This policy allows everything except a named list (NotAction). Any service AWS "
                                + "launches in future is granted automatically."
                                + (st.conditional() ? " A condition narrows when this applies." : ""),
                        policyLabel(policy), List.of()));
                continue;
            }

            for (String action : st.actions()) {
                if ("*".equals(action)) {
                    if (st.allResources()) {
                        admin = true;
                        risks.add(risk(FULL_ADMIN,
                                st.conditional() ? SecuritySeverity.HIGH : SecuritySeverity.CRITICAL,
                                "Full administrator access",
                                "Grants every action on every resource (Action \"*\" on Resource \"*\")."
                                        + (st.conditional()
                                                ? " A condition limits when it applies, but the grant itself is unrestricted."
                                                : ""),
                                policy.getPolicyName(), List.of("Action: * / Resource: *")));
                    } else {
                        wildcards.add("* (scoped resources)");
                    }
                    continue;
                }

                if (action.endsWith(":*")) {
                    wildcards.add(action);
                }
                if (ESCALATION_ACTIONS.contains(action) || action.equals("iam:*")) {
                    escalations.add(action);
                }
            }
        }

        if (!wildcards.isEmpty()) {
            risks.add(risk(SERVICE_WILDCARD, SecuritySeverity.HIGH,
                    "Service-wide wildcard permissions",
                    "Grants every action in " + wildcards.size()
                            + (wildcards.size() == 1 ? " service" : " services")
                            + " rather than the specific actions needed.",
                    policyLabel(policy), capped(wildcards)));
        }

        if (!escalations.isEmpty()) {
            risks.add(risk(PRIVILEGE_ESCALATION, SecuritySeverity.CRITICAL,
                    "Can escalate its own privileges",
                    "Holds IAM actions that allow granting itself additional permissions, so its "
                            + "effective access is unbounded regardless of what else is attached.",
                    policyLabel(policy), capped(escalations)));
        }

        policy.setAdminAccess(admin);
        policy.setWildcardActions(new ArrayList<>(wildcards));
        policy.setEscalationActions(new ArrayList<>(escalations));

        return new PolicyVerdict(risks, admin);
    }

    // ── Document fetching and parsing ───────────────────────────────────────────

    private enum IdentityKind {
        USER, ROLE, GROUP
    }

    /**
     * Per-account fetch state.
     *
     * <p>
     * Caches by ARN because attached managed policies repeat heavily across identities — a
     * 60-user account typically shares a handful of policies, so this is the difference between
     * a few dozen IAM calls and a few thousand.
     */
    private final class Scan {
        private final IamClient iam;
        private final Map<String, Statements> byArn = new HashMap<>();
        private int fetches;
        private int denials;
        private boolean capped;

        Scan(IamClient iam) {
            this.iam = iam;
        }

        Statements documentFor(DashboardData.IamPolicyDetail policy, IdentityKind kind, String identityName) {
            boolean inline = policy.getPolicyArn() == null || policy.getPolicyArn().isBlank();

            // An inherited inline policy belongs to the group, not the member: it must be read
            // with GetGroupPolicy against the group name, and cached under the group so it is
            // fetched once no matter how many users are in it.
            if (policy.getInheritedFrom() != null && !policy.getInheritedFrom().isBlank()) {
                kind = IdentityKind.GROUP;
                identityName = policy.getInheritedFrom();
            }

            String cacheKey = inline
                    ? kind + "|" + identityName + "|" + policy.getPolicyName()
                    : policy.getPolicyArn();

            if (byArn.containsKey(cacheKey)) {
                return byArn.get(cacheKey);
            }
            if (fetches >= MAX_DOCUMENT_FETCHES) {
                capped = true;
                return null;
            }
            fetches++;

            Statements parsed = inline
                    ? fetchInline(policy, kind, identityName)
                    : fetchManaged(policy);

            // Negative results are cached too, so a denied policy is not retried for every
            // identity that has it attached.
            byArn.put(cacheKey, parsed);
            return parsed;
        }

        private Statements fetchInline(DashboardData.IamPolicyDetail policy, IdentityKind kind, String identityName) {
            try {
                String raw = switch (kind) {
                    case USER -> iam
                            .getUserPolicy(r -> r.userName(identityName).policyName(policy.getPolicyName()))
                            .policyDocument();
                    case ROLE -> iam
                            .getRolePolicy(r -> r.roleName(identityName).policyName(policy.getPolicyName()))
                            .policyDocument();
                    case GROUP -> iam
                            .getGroupPolicy(r -> r.groupName(identityName).policyName(policy.getPolicyName()))
                            .policyDocument();
                };
                return parse(raw);
            } catch (AwsServiceException e) {
                recordFailure(e, "inline policy " + policy.getPolicyName() + " on " + identityName);
                return null;
            } catch (Exception e) {
                logger.debug("Could not read inline policy {}: {}", policy.getPolicyName(), e.getMessage());
                return null;
            }
        }

        private Statements fetchManaged(DashboardData.IamPolicyDetail policy) {
            try {
                String version = iam.getPolicy(r -> r.policyArn(policy.getPolicyArn())).policy().defaultVersionId();
                fetches++;
                String raw = iam
                        .getPolicyVersion(r -> r.policyArn(policy.getPolicyArn()).versionId(version))
                        .policyVersion().document();
                return parse(raw);
            } catch (AwsServiceException e) {
                recordFailure(e, "managed policy " + policy.getPolicyArn());
                return null;
            } catch (Exception e) {
                logger.debug("Could not read managed policy {}: {}", policy.getPolicyArn(), e.getMessage());
                return null;
            }
        }

        private void recordFailure(AwsServiceException e, String what) {
            if (e.statusCode() == 403 || isAccessDenied(e)) {
                denials++;
                logger.warn("Permission audit could not read {}: access denied. Grant iam:GetPolicy, "
                        + "iam:GetPolicyVersion, iam:GetUserPolicy and iam:GetRolePolicy to audit "
                        + "effective permissions.", what);
            } else {
                logger.warn("Permission audit could not read {}: {}", what, e.getMessage());
            }
        }

        void noteFailure(Exception e) {
            if (e instanceof AwsServiceException ase && (ase.statusCode() == 403 || isAccessDenied(ase))) {
                denials++;
            }
        }

        PrivilegeAuditOutcome outcome() {
            if (denials > 0) {
                return PrivilegeAuditOutcome.degraded("Some policy documents could not be read, so a few identities "
                        + "show as unaudited rather than clean. Update the XamOps onboarding stack to grant "
                        + "iam:GetPolicy, iam:GetPolicyVersion, iam:GetUserPolicy and iam:GetRolePolicy.");
            }
            if (capped) {
                return PrivilegeAuditOutcome.degraded("This account has more distinct IAM policies than a single audit "
                        + "pass inspects (" + MAX_DOCUMENT_FETCHES + "). The identities shown are accurate; "
                        + "some further down the list were not inspected.");
            }
            return PrivilegeAuditOutcome.complete();
        }
    }

    private void markUnknown(String identityName, Exception cause, Scan scan) {
        scan.noteFailure(cause);
        logger.debug("Identity {} left unaudited: {}", identityName, cause.getMessage());
    }

    private static boolean isAccessDenied(AwsServiceException e) {
        return e.awsErrorDetails() != null
                && e.awsErrorDetails().errorCode() != null
                && e.awsErrorDetails().errorCode().toLowerCase(Locale.ROOT).contains("accessdenied");
    }

    private record Statement(List<String> actions, boolean allResources, boolean conditional, boolean notAction) {
    }

    private record Statements(List<Statement> allow) {
    }

    /**
     * Parses an IAM policy document into its Allow statements.
     *
     * <p>
     * IAM documents are URL-encoded on the wire, and every field that can be a string can also
     * be an array — {@code Statement}, {@code Action}, {@code Resource}. Handling only the array
     * form is the classic bug here: it silently skips single-statement policies, which is where
     * blanket {@code Action:*} grants usually live.
     *
     * @return null when the document cannot be understood, never an empty-but-valid result
     */
    private Statements parse(String urlEncoded) {
        if (urlEncoded == null || urlEncoded.isBlank()) {
            return null;
        }
        try {
            String json = URLDecoder.decode(urlEncoded, StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(json);
            JsonNode stmtNode = root.path("Statement");

            List<JsonNode> nodes = new ArrayList<>();
            if (stmtNode.isArray()) {
                stmtNode.forEach(nodes::add);
            } else if (stmtNode.isObject()) {
                nodes.add(stmtNode);
            } else {
                return null;
            }

            List<Statement> allow = new ArrayList<>();
            for (JsonNode st : nodes) {
                // Effect defaults to nothing meaningful; only Allow can over-grant. A Deny
                // statement containing "*" is a guardrail, and flagging it would punish
                // customers for doing the right thing.
                if (!"Allow".equalsIgnoreCase(st.path("Effect").asText(""))) {
                    continue;
                }

                boolean notAction = st.has("NotAction");
                List<String> actions = notAction ? List.of() : lowerStrings(st.path("Action"));
                boolean allResources = containsStar(st.path("Resource"));
                boolean conditional = st.has("Condition") && !st.path("Condition").isEmpty();

                allow.add(new Statement(actions, allResources, conditional, notAction));
            }
            return new Statements(allow);
        } catch (Exception e) {
            logger.debug("Unparseable IAM policy document: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> lowerStrings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isTextual()) {
            out.add(node.asText().toLowerCase(Locale.ROOT));
        } else if (node.isArray()) {
            node.forEach(n -> {
                if (n.isTextual()) {
                    out.add(n.asText().toLowerCase(Locale.ROOT));
                }
            });
        }
        return out;
    }

    private static boolean containsStar(JsonNode node) {
        if (node.isTextual()) {
            return "*".equals(node.asText());
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (n.isTextual() && "*".equals(n.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── Small helpers ───────────────────────────────────────────────────────────

    private static DashboardData.IamAccessRisk risk(String code, String severity, String title,
            String description, String sourcePolicy, List<String> evidence) {
        return new DashboardData.IamAccessRisk(code, severity, title, description, sourcePolicy, evidence);
    }

    private static List<String> capped(Set<String> values) {
        return values.stream().limit(MAX_EVIDENCE).toList();
    }

    /**
     * How a policy is named on the card: bare, or qualified by the group it came from.
     *
     * <p>
     * The qualification is the actionable part. "AdministratorAccess" tells the reader to
     * detach something from this user; "AdministratorAccess (group: Admins)" tells them the
     * grant is shared and that removing it affects everyone in that group.
     */
    private static String policyLabel(DashboardData.IamPolicyDetail policy) {
        if (policy.getInheritedFrom() == null || policy.getInheritedFrom().isBlank()) {
            return policy.getPolicyName();
        }
        return policy.getPolicyName() + " (group: " + policy.getInheritedFrom() + ")";
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Whether a credential has gone unused long enough to be a standing risk.
     *
     * <p>
     * A null or "Never" value counts as stale. AWS reports never-signed-in users that way, and
     * an administrator who has never logged in is the clearest case of access without need.
     */
    private static boolean isStale(String lastUsedIso) {
        if (lastUsedIso == null || lastUsedIso.isBlank() || "Never".equalsIgnoreCase(lastUsedIso)) {
            return true;
        }
        try {
            return Instant.parse(lastUsedIso).isBefore(Instant.now().minus(STALE_DAYS, ChronoUnit.DAYS));
        } catch (Exception e) {
            return false;
        }
    }
}
