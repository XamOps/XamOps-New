package com.xammer.cloud.service.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Findings derived from what a change actually did, not just which API was called.
 *
 * <p>
 * These cover the case the rest of the pipeline misses: a resource being <em>opened up</em>. A
 * bucket created by {@code CreateBucket} is private, and publicness arrives later through
 * {@code PutBucketPolicy} or {@code PutBucketAcl}; a security group is created empty and opened
 * afterwards; an RDS instance is flipped to publicly accessible long after it exists. On most real
 * estates this is the more common route to exposure than creating something exposed.
 *
 * <p>
 * <b>Why every rule inspects the parameters.</b> The event name alone cannot distinguish routine
 * work from an incident — {@code AuthorizeSecurityGroupIngress} is the same call whether it opens
 * port 443 to an office VPN or port 22 to the entire internet. A detector that fired on the name
 * would alert on both, and a security feature that alerts on normal operations gets muted within a
 * week. So each rule here returns {@link Optional#empty()} unless the parameters show the change
 * was genuinely dangerous, and the noise floor is what makes the alerts worth reading.
 *
 * <p>
 * When the forwarder had to truncate an oversized detail, the field a rule needs may be absent. In
 * that case the rule reports honestly that it could not tell rather than assuming the change was
 * safe — see {@link #truncated(JsonNode)}.
 */
public final class ExposureDetector {

    /** IPv4 and IPv6 "anywhere". The single most important string in this class. */
    private static final Set<String> OPEN_TO_WORLD = Set.of("0.0.0.0/0", "::/0");

    /**
     * Ports where exposure to the internet is an incident rather than a design choice.
     *
     * <p>
     * Separated from generic ports because the severity genuinely differs. A public web port is
     * usually intentional; a public SSH, RDP or database port almost never is, and is what
     * credential-stuffing and ransomware campaigns scan for continuously.
     */
    /**
     * Ordered, not a {@link Set}. {@code Set.of} has deliberately unspecified iteration order, so a
     * rule spanning both 22 and 3389 would name whichever came out first and the title would vary
     * between JVM runs — a finding whose wording changes for no reason is one users stop trusting.
     */
    private static final List<Integer> ADMIN_PORTS = List.of(22, 3389);
    private static final List<Integer> DATABASE_PORTS =
            List.of(1433, 1521, 3306, 5432, 5984, 6379, 9200, 11211, 27017);

    /**
     * Above this many ports, the range is described as a range rather than by the sensitive port it
     * happens to contain.
     *
     * <p>
     * Calling a {@code 0-65535} rule "SSH opened to the internet" is technically true and
     * practically misleading: it understates the problem, and a reader who checks port 22 and finds
     * it unused would reasonably close the finding while everything else stays exposed.
     */
    private static final int WIDE_RANGE_PORTS = 50;

    private static final String MANUAL_RESOLVE =
            " XamOps sees the change that opened this but not the one that closes it, so mark the "
            + "finding resolved once you have verified the fix.";

    /** Event names this detector can produce a finding for. */
    private static final Set<String> HANDLED = Set.of(
            "AuthorizeSecurityGroupIngress", "ModifySecurityGroupRules",
            "PutBucketPolicy", "PutBucketAcl",
            "PutBucketPublicAccessBlock", "PutAccountPublicAccessBlock",
            "ModifyDBInstance", "CreateDBInstance", "ModifyDBCluster",
            "AttachUserPolicy", "AttachRolePolicy", "PutUserPolicy", "PutRolePolicy",
            "UpdateAssumeRolePolicy", "PutKeyPolicy", "DisableKey", "AddPermission",
            "ModifySnapshotAttribute", "ModifyDBSnapshotAttribute", "ModifyImageAttribute");

    private ExposureDetector() {
    }

    public static Set<String> handledEventNames() {
        return HANDLED;
    }

    /**
     * Evaluates one CloudTrail event.
     *
     * @param eventName the CloudTrail event name
     * @param detail    the slimmed detail the forwarder attached, possibly null
     * @return a finding when the change was dangerous, empty when it was routine
     */
    public static Optional<DerivedFinding> detect(String eventName, JsonNode detail) {
        if (eventName == null || detail == null || detail.isMissingNode() || detail.isNull()) {
            return Optional.empty();
        }
        JsonNode params = detail.path("requestParameters");

        switch (eventName) {
            case "AuthorizeSecurityGroupIngress":
            case "ModifySecurityGroupRules":
                return securityGroupOpenedToWorld(eventName, params, detail);
            case "PutBucketPolicy":
                return bucketPolicyAllowsAnyone(params, detail);
            case "PutBucketAcl":
                return bucketAclGrantsPublic(params, detail);
            case "PutBucketPublicAccessBlock":
            case "PutAccountPublicAccessBlock":
                return publicAccessBlockWeakened(eventName, params, detail);
            case "ModifyDBInstance":
            case "CreateDBInstance":
            case "ModifyDBCluster":
                return databaseMadePublic(eventName, params, detail);
            case "AttachUserPolicy":
            case "AttachRolePolicy":
                return adminPolicyAttached(eventName, params);
            case "PutUserPolicy":
            case "PutRolePolicy":
                return inlinePolicyGrantsEverything(eventName, params, detail);
            case "UpdateAssumeRolePolicy":
                return roleTrustsAnyone(params, detail);
            case "PutKeyPolicy":
                return keyPolicyAllowsAnyone(params, detail);
            case "DisableKey":
                return kmsKeyDisabled(params);
            case "AddPermission":
                return resourcePolicyAllowsAnyone(params, detail);
            case "ModifySnapshotAttribute":
            case "ModifyDBSnapshotAttribute":
            case "ModifyImageAttribute":
                return snapshotSharedPublicly(eventName, params, detail);
            default:
                return Optional.empty();
        }
    }

    // ── Network ─────────────────────────────────────────────────────────────

    /**
     * A security group rule opened to {@code 0.0.0.0/0}.
     *
     * <p>
     * Severity is driven by the port range rather than being fixed, because the difference between
     * a public HTTPS port and a public SSH port is the difference between a deployment and an
     * incident. Ranges are handled rather than single ports: {@code fromPort=0, toPort=65535}
     * exposes everything and would slip past an equality check on 22.
     */
    private static Optional<DerivedFinding> securityGroupOpenedToWorld(String eventName,
            JsonNode params, JsonNode detail) {

        String groupId = firstText(params, "groupId", "GroupId");

        for (JsonNode permission : items(params.path("ipPermissions"))) {
            boolean world = false;
            for (JsonNode range : items(permission.path("ipRanges"))) {
                if (OPEN_TO_WORLD.contains(text(range, "cidrIp"))) {
                    world = true;
                }
            }
            for (JsonNode range : items(permission.path("ipv6Ranges"))) {
                if (OPEN_TO_WORLD.contains(text(range, "cidrIpv6"))) {
                    world = true;
                }
            }
            if (!world) {
                continue;
            }

            int from = permission.path("fromPort").asInt(-1);
            int to = permission.path("toPort").asInt(-1);
            String protocol = text(permission, "ipProtocol");

            // "-1" is every protocol on every port. Reported as such rather than as a port range,
            // because "ports -1 to -1" would read as a narrow rule.
            if ("-1".equals(protocol) || (from == -1 && to == -1)) {
                return Optional.of(new DerivedFinding(
                        eventName + ":" + nullSafe(groupId),
                        "All traffic opened to the entire internet",
                        SecuritySeverity.CRITICAL, "AwsEc2SecurityGroup", groupId,
                        "A security group rule now allows every protocol and port from 0.0.0.0/0. "
                                + "Every instance in this group is reachable from anywhere.",
                        "Delete the rule and replace it with the specific ports and source ranges "
                                + "actually required." + MANUAL_RESOLVE));
            }

            List<String> sensitive = sensitivePortsIn(from, to);
            boolean wide = from >= 0 && to >= 0 && (to - from) >= WIDE_RANGE_PORTS;

            if (wide) {
                // Described as a range, with the sensitive ports named as evidence rather than as
                // the headline. Naming one port would understate a rule that exposed thousands.
                return Optional.of(new DerivedFinding(
                        eventName + ":" + nullSafe(groupId) + ":" + from + "-" + to,
                        "A wide port range is open to the entire internet",
                        SecuritySeverity.CRITICAL, "AwsEc2SecurityGroup", groupId,
                        "A security group rule now allows " + portText(from, to)
                                + " from 0.0.0.0/0"
                                + (sensitive.isEmpty()
                                        ? "."
                                        : ", including " + String.join(", ", sensitive) + ".")
                                + " Every service listening on any instance in this group is "
                                + "reachable from anywhere.",
                        "Replace the range with the specific ports required, and restrict the source "
                                + "to a known address range." + MANUAL_RESOLVE));
            }

            if (!sensitive.isEmpty()) {
                return Optional.of(new DerivedFinding(
                        eventName + ":" + nullSafe(groupId) + ":" + from + "-" + to,
                        sensitive.get(0) + " opened to the entire internet",
                        SecuritySeverity.CRITICAL, "AwsEc2SecurityGroup", groupId,
                        "A security group rule now allows " + portText(from, to)
                                + " from 0.0.0.0/0. Ports like this are scanned continuously for "
                                + "weak or reused credentials.",
                        "Restrict the source to a known address range, or remove the rule and use "
                                + "Session Manager or a bastion instead of exposing the port."
                                + MANUAL_RESOLVE));
            }

            return Optional.of(new DerivedFinding(
                    eventName + ":" + nullSafe(groupId) + ":" + from + "-" + to,
                    portText(from, to) + " opened to the entire internet",
                    SecuritySeverity.HIGH, "AwsEc2SecurityGroup", groupId,
                    "A security group rule now allows " + portText(from, to) + " from 0.0.0.0/0. "
                            + "This may be intended for a public service — confirm it is.",
                    "If this port is not meant to be public, restrict the source range."
                            + MANUAL_RESOLVE));
        }

        // No open range found. Either the rule was scoped, which is normal and must not alert, or
        // the detail was truncated and we genuinely cannot tell.
        return truncated(detail)
                ? Optional.of(unverifiable(eventName, "AwsEc2SecurityGroup", groupId,
                        "A security group rule was changed, but the event detail was too large to "
                                + "carry the source ranges, so XamOps could not check whether it "
                                + "opened access to the internet.",
                        "Review the inbound rules on this security group."))
                : Optional.empty();
    }

    /**
     * Sensitive ports covered by a range, admin ports before database ports.
     *
     * <p>
     * Ordered so the first entry is the most recognisable headline, and returned as a list rather
     * than a single value so a wide range can cite everything it exposed as evidence.
     */
    private static List<String> sensitivePortsIn(int from, int to) {
        List<String> found = new java.util.ArrayList<>();
        for (Integer port : ADMIN_PORTS) {
            if (covers(from, to, port)) {
                found.add(port == 22 ? "SSH (port 22)" : "RDP (port 3389)");
            }
        }
        for (Integer port : DATABASE_PORTS) {
            if (covers(from, to, port)) {
                found.add("database port " + port);
            }
        }
        return found;
    }

    private static boolean covers(int from, int to, int port) {
        if (from < 0 || to < 0) {
            return false;
        }
        return from <= port && port <= to;
    }

    private static String portText(int from, int to) {
        if (from < 0 && to < 0) {
            return "all ports";
        }
        return from == to ? ("Port " + from) : ("Ports " + from + "-" + to);
    }

    // ── S3 ──────────────────────────────────────────────────────────────────

    /**
     * A bucket policy that allows an unauthenticated principal.
     *
     * <p>
     * Only {@code Allow} statements count. A policy with {@code "Principal": "*"} inside a
     * {@code Deny} is the opposite of a problem — that is the standard shape for "deny anyone not
     * using TLS" — and flagging it would train people to ignore the alert.
     */
    private static Optional<DerivedFinding> bucketPolicyAllowsAnyone(JsonNode params,
            JsonNode detail) {
        String bucket = firstText(params, "bucketName", "bucket");
        String policy = firstText(params, "bucketPolicy", "policy", "Policy");

        if (policy == null) {
            return truncated(detail)
                    ? Optional.of(unverifiable("PutBucketPolicy", "AwsS3Bucket", bucket,
                            "A bucket policy was replaced, but the event detail was too large to "
                                    + "carry the policy document, so XamOps could not check whether "
                                    + "it grants public access.",
                            "Review the bucket policy for statements allowing Principal \"*\"."))
                    : Optional.empty();
        }
        if (!allowsWildcardPrincipal(policy)) {
            return Optional.empty();
        }
        return Optional.of(new DerivedFinding(
                "PutBucketPolicy:" + nullSafe(bucket),
                "Bucket policy grants access to anyone",
                SecuritySeverity.CRITICAL, "AwsS3Bucket", bucket,
                "The new bucket policy contains an Allow statement with a wildcard principal, so "
                        + "any unauthenticated caller can perform the listed actions unless Block "
                        + "Public Access is still stopping them.",
                "Replace the wildcard principal with the specific accounts, roles or service "
                        + "principals that need access, and confirm Block Public Access is on."
                        + MANUAL_RESOLVE));
    }

    /**
     * Whether a policy document has an {@code Allow} with a wildcard principal.
     *
     * <p>
     * Deliberately a scan over the raw JSON text rather than a parse into a policy model. The
     * document arrives as an escaped string whose shape varies — {@code Principal} may be
     * {@code "*"}, {@code {"AWS":"*"}} or {@code {"AWS":["*"]}} — and the statement list may be an
     * object or an array. A tolerant scan errs toward reporting; a strict parser errs toward
     * missing a real exposure because of a shape it did not expect, which is the worse direction
     * for a security check.
     */
    private static boolean allowsWildcardPrincipal(String policy) {
        String compact = policy.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!compact.contains("\"effect\":\"allow\"")) {
            return false;
        }
        return compact.contains("\"principal\":\"*\"")
                || compact.contains("\"principal\":{\"aws\":\"*\"}")
                || compact.contains("\"principal\":{\"aws\":[\"*\"]}")
                || compact.contains("\"principal\":{\"aws\":\"arn:aws:iam::*:root\"}");
    }

    /** An ACL granting the AllUsers or AuthenticatedUsers group, or a canned public ACL. */
    private static Optional<DerivedFinding> bucketAclGrantsPublic(JsonNode params, JsonNode detail) {
        String bucket = firstText(params, "bucketName", "bucket");
        String canned = firstText(params, "x-amz-acl", "acl", "ACL");

        boolean publicCanned = canned != null
                && (canned.equalsIgnoreCase("public-read")
                        || canned.equalsIgnoreCase("public-read-write")
                        || canned.equalsIgnoreCase("authenticated-read"));

        // AuthenticatedUsers is every AWS account in the world, not "users of this account" — a
        // distinction that catches people out, so it is treated as public.
        boolean publicGrant = params.toString().contains("AllUsers")
                || params.toString().contains("AuthenticatedUsers");

        if (!publicCanned && !publicGrant) {
            return truncated(detail)
                    ? Optional.of(unverifiable("PutBucketAcl", "AwsS3Bucket", bucket,
                            "A bucket ACL was changed, but the event detail was too large to carry "
                                    + "the grants, so XamOps could not check whether it grants "
                                    + "public access.",
                            "Review the bucket ACL for grants to AllUsers or AuthenticatedUsers."))
                    : Optional.empty();
        }
        return Optional.of(new DerivedFinding(
                "PutBucketAcl:" + nullSafe(bucket),
                "Bucket ACL grants public access",
                SecuritySeverity.CRITICAL, "AwsS3Bucket", bucket,
                "The bucket ACL now grants a public group"
                        + (canned == null ? "" : " (" + canned + ")")
                        + ". AuthenticatedUsers means every AWS account, not only your own.",
                "Remove the public grant and use a bucket policy scoped to specific principals. "
                        + "AWS recommends disabling ACLs entirely with Object Ownership set to "
                        + "Bucket owner enforced." + MANUAL_RESOLVE));
    }

    /** Any of the four Block Public Access flags turned off. */
    private static Optional<DerivedFinding> publicAccessBlockWeakened(String eventName,
            JsonNode params, JsonNode detail) {

        String bucket = firstText(params, "bucketName", "bucket");
        JsonNode config = params.path("PublicAccessBlockConfiguration").isMissingNode()
                ? params.path("publicAccessBlockConfiguration")
                : params.path("PublicAccessBlockConfiguration");

        if (config.isMissingNode() || config.isNull()) {
            return truncated(detail)
                    ? Optional.of(unverifiable(eventName, "AwsS3Bucket", bucket,
                            "Block Public Access settings were changed, but the event detail did not "
                                    + "carry the new configuration.",
                            "Confirm all four Block Public Access settings are enabled."))
                    : Optional.empty();
        }

        List<String> disabled = new java.util.ArrayList<>();
        for (String flag : List.of("BlockPublicAcls", "IgnorePublicAcls",
                "BlockPublicPolicy", "RestrictPublicBuckets")) {
            JsonNode value = config.has(flag) ? config.path(flag)
                    : config.path(Character.toLowerCase(flag.charAt(0)) + flag.substring(1));
            if (value.isBoolean() && !value.asBoolean()) {
                disabled.add(flag);
            }
        }
        if (disabled.isEmpty()) {
            return Optional.empty();
        }

        boolean accountWide = "PutAccountPublicAccessBlock".equals(eventName);
        return Optional.of(new DerivedFinding(
                eventName + ":" + nullSafe(bucket),
                accountWide
                        ? "Account-wide Block Public Access weakened"
                        : "Block Public Access weakened on a bucket",
                SecuritySeverity.CRITICAL, "AwsS3Bucket", bucket,
                "These Block Public Access protections were turned off: "
                        + String.join(", ", disabled)
                        + ". Block Public Access is what stops a policy or ACL from taking effect, "
                        + "so with it off any existing public grant applies immediately"
                        + (accountWide ? " across every bucket in the account." : "."),
                "Re-enable the settings above, then audit the "
                        + (accountWide ? "account's buckets" : "bucket") + " for public grants."
                        + MANUAL_RESOLVE));
    }

    // ── Databases ───────────────────────────────────────────────────────────

    private static Optional<DerivedFinding> databaseMadePublic(String eventName, JsonNode params,
            JsonNode detail) {
        String id = firstText(params, "dBInstanceIdentifier", "dBClusterIdentifier");
        JsonNode publicly = params.path("publiclyAccessible");

        if (!publicly.isBoolean()) {
            return Optional.empty();
        }
        if (!publicly.asBoolean()) {
            return Optional.empty();
        }
        return Optional.of(new DerivedFinding(
                eventName + ":" + nullSafe(id),
                "Database made publicly accessible",
                SecuritySeverity.CRITICAL, "AwsRdsDbInstance", id,
                "This database now has a public endpoint. Anyone who can resolve the hostname can "
                        + "attempt to connect, and the only remaining barrier is its security group "
                        + "and credentials.",
                "Set Publicly Accessible to No and reach the database through a bastion, VPN or "
                        + "Session Manager port forward instead." + MANUAL_RESOLVE));
    }

    // ── Identity ────────────────────────────────────────────────────────────

    /**
     * An administrator-equivalent managed policy attached.
     *
     * <p>
     * Only the wildcard-power policies are flagged. Attaching a scoped managed policy is ordinary
     * work and alerting on it would bury the case that matters.
     */
    private static Optional<DerivedFinding> adminPolicyAttached(String eventName, JsonNode params) {
        String policyArn = text(params, "policyArn");
        if (policyArn == null) {
            return Optional.empty();
        }
        boolean admin = policyArn.endsWith("/AdministratorAccess")
                || policyArn.endsWith("/IAMFullAccess")
                || policyArn.endsWith("/PowerUserAccess");
        if (!admin) {
            return Optional.empty();
        }
        String principal = firstText(params, "userName", "roleName");
        return Optional.of(new DerivedFinding(
                eventName + ":" + nullSafe(principal) + ":" + policyArn,
                "Administrator-level policy attached",
                SecuritySeverity.HIGH,
                "AttachUserPolicy".equals(eventName) ? "AwsIamUser" : "AwsIamRole",
                principal,
                policyArn.substring(policyArn.lastIndexOf('/') + 1) + " was attached to "
                        + nullSafe(principal) + ". This grants close to unrestricted control of the "
                        + "account.",
                "Confirm this level of access is required and time-bounded. Prefer a scoped policy "
                        + "granting only the actions needed." + MANUAL_RESOLVE));
    }

    private static Optional<DerivedFinding> inlinePolicyGrantsEverything(String eventName,
            JsonNode params, JsonNode detail) {
        String document = firstText(params, "policyDocument");
        String principal = firstText(params, "userName", "roleName");
        if (document == null) {
            return Optional.empty();
        }
        String compact = document.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean godMode = compact.contains("\"effect\":\"allow\"")
                && (compact.contains("\"action\":\"*\"") || compact.contains("\"action\":[\"*\"]"))
                && (compact.contains("\"resource\":\"*\"") || compact.contains("\"resource\":[\"*\"]"));
        if (!godMode) {
            return Optional.empty();
        }
        return Optional.of(new DerivedFinding(
                eventName + ":" + nullSafe(principal),
                "Inline policy grants every action on every resource",
                SecuritySeverity.HIGH,
                "PutUserPolicy".equals(eventName) ? "AwsIamUser" : "AwsIamRole",
                principal,
                "An inline policy on " + nullSafe(principal) + " allows Action \"*\" on Resource "
                        + "\"*\", which is full administrative access expressed in a place that is "
                        + "easy to miss during a review.",
                "Replace it with the specific actions and resources required." + MANUAL_RESOLVE));
    }

    /** A trust policy any principal can assume — anyone in any AWS account becomes this role. */
    private static Optional<DerivedFinding> roleTrustsAnyone(JsonNode params, JsonNode detail) {
        String role = text(params, "roleName");
        String document = firstText(params, "policyDocument");
        if (document == null || !allowsWildcardPrincipal(document)) {
            return Optional.empty();
        }
        return Optional.of(new DerivedFinding(
                "UpdateAssumeRolePolicy:" + nullSafe(role),
                "Role can be assumed by anyone",
                SecuritySeverity.CRITICAL, "AwsIamRole", role,
                "The trust policy on " + nullSafe(role) + " allows a wildcard principal, so any AWS "
                        + "principal anywhere can assume this role and inherit every permission "
                        + "attached to it.",
                "Restrict the trust policy to the specific accounts, services or federated "
                        + "identities that must assume this role, and add a condition such as "
                        + "sts:ExternalId for third parties." + MANUAL_RESOLVE));
    }

    // ── Encryption ──────────────────────────────────────────────────────────

    private static Optional<DerivedFinding> keyPolicyAllowsAnyone(JsonNode params, JsonNode detail) {
        String keyId = text(params, "keyId");
        String policy = firstText(params, "policy");
        if (policy == null || !allowsWildcardPrincipal(policy)) {
            return Optional.empty();
        }
        return Optional.of(new DerivedFinding(
                "PutKeyPolicy:" + nullSafe(keyId),
                "KMS key policy allows a wildcard principal",
                SecuritySeverity.HIGH, "AwsKmsKey", keyId,
                "The key policy now allows a wildcard principal. A KMS key that anyone can use to "
                        + "decrypt removes the protection every resource encrypted with it relies on.",
                "Scope the key policy to the specific principals that need encrypt or decrypt "
                        + "rights." + MANUAL_RESOLVE));
    }

    /**
     * A KMS key disabled.
     *
     * <p>
     * Unconditional, and reported as availability rather than exposure: nothing encrypted with the
     * key can be decrypted while it is disabled, which surfaces as a widespread outage whose cause
     * is hard to trace back.
     */
    private static Optional<DerivedFinding> kmsKeyDisabled(JsonNode params) {
        String keyId = text(params, "keyId");
        return Optional.of(new DerivedFinding(
                "DisableKey:" + nullSafe(keyId),
                "KMS key was disabled",
                SecuritySeverity.HIGH, "AwsKmsKey", keyId,
                "While this key is disabled, every resource encrypted with it is unreadable. If the "
                        + "key is in use, expect failures across services rather than a single one.",
                "Re-enable the key unless the disable was deliberate and the dependent resources "
                        + "have been migrated to another key." + MANUAL_RESOLVE));
    }

    // ── Resource policies and sharing ───────────────────────────────────────

    /** Lambda, SNS and SQS all grant cross-account access through AddPermission. */
    private static Optional<DerivedFinding> resourcePolicyAllowsAnyone(JsonNode params,
            JsonNode detail) {
        String principal = firstText(params, "principal", "Principal", "awsAccountId");
        if (principal == null || !"*".equals(principal.trim())) {
            return Optional.empty();
        }
        String resource = firstText(params, "functionName", "topicArn", "queueUrl", "label");
        return Optional.of(new DerivedFinding(
                "AddPermission:" + nullSafe(resource),
                "Resource policy grants access to any AWS principal",
                SecuritySeverity.HIGH, "AwsResourcePolicy", resource,
                "A permission was added with principal \"*\", so any AWS account can invoke or "
                        + "publish to this resource.",
                "Replace \"*\" with the specific account or service principal that needs access, "
                        + "and add a source ARN or source account condition." + MANUAL_RESOLVE));
    }

    /**
     * A snapshot or AMI shared with everyone.
     *
     * <p>
     * Worth CRITICAL despite looking obscure: a public snapshot is a complete copy of a disk that
     * anyone can restore into their own account and read at leisure. It leaves no trace in the
     * owning account's data-plane logs, which makes it a quiet exfiltration route.
     */
    private static Optional<DerivedFinding> snapshotSharedPublicly(String eventName, JsonNode params,
            JsonNode detail) {
        String blob = params.toString();
        boolean sharedWithAll = blob.contains("\"all\"") || blob.contains("\"group\":\"all\"");
        if (!sharedWithAll) {
            return Optional.empty();
        }
        String id = firstText(params, "snapshotId", "dBSnapshotIdentifier", "imageId");
        return Optional.of(new DerivedFinding(
                eventName + ":" + nullSafe(id),
                "Snapshot or image shared publicly",
                SecuritySeverity.CRITICAL, "AwsSnapshot", id,
                "This snapshot or image is now shared with all AWS accounts. Anyone can restore it "
                        + "and read every byte it contains, and doing so leaves no record in your "
                        + "account.",
                "Remove the public share immediately and review whether the data it contains has "
                        + "to be treated as disclosed." + MANUAL_RESOLVE));
    }

    // ── Truncation ──────────────────────────────────────────────────────────

    /**
     * Whether the forwarder had to drop oversized request parameters.
     *
     * <p>
     * Matters because "the field is absent" and "the condition is false" look identical otherwise,
     * and a detector that treats a truncated payload as safe reports an account as clean when it
     * has not actually checked. The finding it produces instead is low severity but explicit.
     */
    private static boolean truncated(JsonNode detail) {
        return detail.path("requestParametersTruncated").asBoolean(false);
    }

    private static DerivedFinding unverifiable(String eventName, String resourceType,
            String resourceId, String description, String remediation) {
        return new DerivedFinding(
                eventName + ":truncated:" + nullSafe(resourceId),
                "Could not verify a security-relevant change",
                SecuritySeverity.LOW, resourceType, resourceId, description,
                remediation + MANUAL_RESOLVE);
    }

    // ── JSON helpers ────────────────────────────────────────────────────────

    /**
     * CloudTrail wraps repeated fields as {@code {"items":[...]}} in some services and emits a bare
     * array in others, so both shapes are accepted. Assuming one produces a detector that works for
     * EC2 and silently never fires elsewhere.
     */
    private static Iterable<JsonNode> items(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            return node;
        }
        JsonNode inner = node.path("items");
        return inner.isArray() ? inner : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String nullSafe(String value) {
        return value == null ? "unknown" : value;
    }
}
