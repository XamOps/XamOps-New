package com.xammer.cloud.service.security;

import java.util.Map;
import java.util.Optional;

/**
 * XamOps-derived findings, built from CloudTrail lifecycle events.
 *
 * <p>
 * These exist because Security Hub, GuardDuty and Config are opt-in billed services in the
 * <em>customer's</em> account. A customer who has not enabled them produces no AWS-native
 * findings at all, so a pipeline that only passes those through shows them an empty Security
 * Center forever. These rules need nothing enabled beyond the onboarding stack.
 *
 * <p>
 * <b>Why this list is short.</b> Every rule here fires on an event that already reaches the
 * queue under the existing {@code xamops-inventory-lifecycle} rule, whose allow-list is
 * prefixes {@code Create, Run, Delete, Terminate, Allocate, Release, Register, Deregister,
 * Provision, Deprovision, Import}. That allow-list is the pipeline's cost control — without
 * it every API call in the account invokes the forwarder — so it is not widened here. The
 * consequence is that the "made public later" events which would be most valuable
 * ({@code PutBucketPolicy}, {@code PutBucketAcl}, {@code PutPublicAccessBlock},
 * {@code AuthorizeSecurityGroupIngress}, {@code ModifyDBInstance}) are invisible to us
 * today. Catching those needs a separate rule listing exact event names, which is a customer
 * stack update.
 *
 * <p>
 * What we can catch without that change is the {@code Delete*} family, and it is a
 * surprisingly good set: removing Block Public Access, deleting a bucket policy, destroying
 * a CloudTrail trail, turning off a GuardDuty detector or a Config recorder are all
 * high-signal and all match the {@code Delete} prefix.
 *
 * <p>
 * <b>Known limitation.</b> A detector finding has no observable remediation. Nothing tells us
 * that Block Public Access was switched back on, because {@code PutPublicAccessBlock} is not
 * forwarded, so these stay {@code ACTIVE} until a user resolves them. That is deliberate:
 * auto-resolving a real exposure because time passed would be the worse failure. The
 * remediation text says so explicitly.
 */
public final class SecurityFindingDetector {

    /** One derived finding: what to call it, how bad it is, and what to do. */
    public static final class Rule {
        public final String title;
        public final String severity;
        public final String description;
        public final String remediation;
        public final String resourceType;

        Rule(String title, String severity, String resourceType, String description, String remediation) {
            this.title = title;
            this.severity = severity;
            this.resourceType = resourceType;
            this.description = description;
            this.remediation = remediation;
        }
    }

    private static final String MANUAL_RESOLVE =
            " XamOps cannot observe the fix for this finding — the AWS event that would confirm it "
            + "is not forwarded — so mark it resolved once you have verified the change.";

    private static final Map<String, Rule> RULES = Map.ofEntries(
            // ── Public exposure ──────────────────────────────────────────────
            Map.entry("DeletePublicAccessBlock", new Rule(
                    "S3 Block Public Access was removed",
                    SecuritySeverity.CRITICAL,
                    "AwsS3Bucket",
                    "Block Public Access is the control that stops a bucket policy or ACL from "
                            + "making objects world-readable. With it removed, any existing or future "
                            + "policy granting public access takes effect immediately.",
                    "Re-enable Block Public Access on the bucket, then audit its policy and ACLs for "
                            + "grants to AllUsers or AuthenticatedUsers." + MANUAL_RESOLVE)),

            Map.entry("DeleteBucketPolicy", new Rule(
                    "S3 bucket policy was deleted",
                    SecuritySeverity.HIGH,
                    "AwsS3Bucket",
                    "The bucket policy was removed. If that policy carried an explicit deny — a "
                            + "common pattern for enforcing TLS or blocking public reads — the bucket has "
                            + "just lost that protection.",
                    "Review whether the deleted policy enforced encryption in transit or denied public "
                            + "access, and restore an equivalent policy." + MANUAL_RESOLVE)),

            // ── Audit and detection controls ─────────────────────────────────
            Map.entry("DeleteTrail", new Rule(
                    "CloudTrail trail was deleted",
                    SecuritySeverity.CRITICAL,
                    "AwsCloudTrailTrail",
                    "A CloudTrail trail was destroyed. API activity after this point is not being "
                            + "recorded to its destination, which removes the audit record an "
                            + "investigation would depend on. Deleting a trail is also a standard step "
                            + "in covering up unauthorised access.",
                    "Recreate the trail with log file validation enabled and confirm no gap remains in "
                            + "the delivered logs." + MANUAL_RESOLVE)),

            Map.entry("DeleteFlowLogs", new Rule(
                    "VPC Flow Logs were deleted",
                    SecuritySeverity.HIGH,
                    "AwsEc2Vpc",
                    "Flow Logs were removed, so network traffic to and from the affected resources is "
                            + "no longer recorded. This is the data used to establish whether an exposed "
                            + "resource was actually reached.",
                    "Recreate the flow log against the VPC, subnet or interface it covered."
                            + MANUAL_RESOLVE)),

            Map.entry("DeleteDetector", new Rule(
                    "GuardDuty detector was deleted",
                    SecuritySeverity.CRITICAL,
                    "AwsGuardDutyDetector",
                    "GuardDuty threat detection was turned off for this region. No findings will be "
                            + "produced, including the credential-compromise and cryptomining detections "
                            + "that have no equivalent elsewhere.",
                    "Re-enable GuardDuty in the affected region and turn its protection plans back on."
                            + MANUAL_RESOLVE)),

            Map.entry("DeleteConfigurationRecorder", new Rule(
                    "AWS Config recorder was deleted",
                    SecuritySeverity.HIGH,
                    "AwsConfigConfigurationRecorder",
                    "Configuration recording stopped, so resource configuration history and every "
                            + "Config rule evaluation depending on it have ceased.",
                    "Recreate the configuration recorder and confirm the delivery channel is intact."
                            + MANUAL_RESOLVE)),

            Map.entry("DeleteConfigRule", new Rule(
                    "AWS Config rule was deleted",
                    SecuritySeverity.MEDIUM,
                    "AwsConfigConfigRule",
                    "A Config rule was removed. Any drift that rule was detecting is now unmonitored, "
                            + "and its findings will disappear from compliance reporting.",
                    "Recreate the rule if it was part of a required control set." + MANUAL_RESOLVE)),

            // ── Long-lived credentials ───────────────────────────────────────
            Map.entry("CreateAccessKey", new Rule(
                    "Long-lived IAM access key was created",
                    SecuritySeverity.MEDIUM,
                    "AwsIamAccessKey",
                    "A static access key was issued. Unlike a role, it does not expire, and leaked "
                            + "static keys are the most common route to account compromise.",
                    "Confirm the key was expected. Prefer IAM roles or Identity Center short-lived "
                            + "credentials, and if the key is required, schedule rotation."
                            + MANUAL_RESOLVE)),

            Map.entry("CreateLoginProfile", new Rule(
                    "Console password was created for an IAM user",
                    SecuritySeverity.MEDIUM,
                    "AwsIamUser",
                    "An IAM user was given console access. A console login without MFA is a "
                            + "single-factor path into the account.",
                    "Verify the user requires console access and enforce MFA on it." + MANUAL_RESOLVE)),

            Map.entry("CreateUser", new Rule(
                    "IAM user was created",
                    SecuritySeverity.LOW,
                    "AwsIamUser",
                    "A new IAM user was created. Federated access through roles is preferred, since "
                            + "IAM users accumulate long-lived credentials that are rarely rotated.",
                    "Confirm the user is necessary and cannot be replaced by a role."
                            + MANUAL_RESOLVE)));

    private SecurityFindingDetector() {
    }

    /** The rule for an event name, if this event is one we derive a finding from. */
    public static Optional<Rule> ruleFor(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(RULES.get(eventName.trim()));
    }

    /** Event names this detector reacts to; exposed for tests and diagnostics. */
    public static java.util.Set<String> detectedEventNames() {
        return RULES.keySet();
    }
}
