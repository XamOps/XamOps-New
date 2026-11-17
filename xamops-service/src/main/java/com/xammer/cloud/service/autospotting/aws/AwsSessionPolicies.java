package com.xammer.cloud.service.autospotting.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The inline STS session policies AutoSpotting passes on {@code AssumeRole} (design-aws.md §2.2,
 * §8.2, §8.3). The effective permission of an AutoSpotting session is the intersection of the
 * customer role's policy and the session policy, so a session can never use the role's broader
 * grants even when the customer role is broad.
 *
 * <ul>
 *   <li>{@link Mode#READ} — exactly the §8.2 read actions (groups, EC2, "other", quota reads,
 *       {@code iam:SimulatePrincipalPolicy}, the forwarder-role IAM reads). No write action is
 *       ever present, so a planning-path bug fails with AccessDenied rather than mutating
 *       (defense in depth for AC-PLN-01).</li>
 *   <li>{@link Mode#WRITE} — the read set plus {@code autoscaling:UpdateAutoScalingGroup},
 *       {@code CreateOrUpdateTags}, {@code DeleteTags}, {@code iam:PassRole} (to EC2), and the
 *       three Deny statements of §8.3 (never touch Kubernetes or Beanstalk groups).</li>
 * </ul>
 *
 * <p><strong>Size limit (DR-17).</strong> STS caps an inline session policy at 2,048 plaintext
 * characters. Policies are serialized minified (no whitespace, actions sorted, statements merged per
 * effect/condition). {@link #serialize(Mode)} prefers the exact action lists and, if a policy would
 * exceed the cap, falls back to the per-service {@code Describe*} form of §2.2 (read-only by AWS
 * naming) plus the exact non-describe reads, so the serialized document always fits and still denies
 * every write. {@link #usesDescribeFallback(Mode)} reports which form a policy ships, so the unit
 * test can pin it.
 */
public final class AwsSessionPolicies {

    /** The STS inline session-policy character limit (DR-17). */
    public static final int MAX_POLICY_CHARS = 2048;

    /** The fixed session name every AutoSpotting session uses (§2.2). */
    public static final String SESSION_NAME = "xamops-autospotting";

    private static final String SERVICE_EC2 = "ec2.amazonaws.com";

    /** The read/write session-policy modes (§2.2). */
    public enum Mode {
        READ,
        WRITE
    }

    private final String roleArn;

    /**
     * @param roleArn the customer connection role ARN — the resource of the
     *                {@code iam:SimulatePrincipalPolicy} statement (§8.2, §8.3 self-simulate)
     */
    public AwsSessionPolicies(String roleArn) {
        this.roleArn = Objects.requireNonNull(roleArn, "roleArn");
    }

    // ---- §8.2 action sets (exact lists, sorted deterministically in the output) ----

    /** The exact read actions AutoSpotting needs on {@code *} (§8.2 "Read groups"/"Read EC2"/"Read other"/"Quota reads"). */
    static List<String> readActionsOnStar() {
        List<String> a = new ArrayList<>();
        // Read groups
        a.add("autoscaling:DescribeAutoScalingGroups");
        a.add("autoscaling:DescribeAutoScalingInstances");
        a.add("autoscaling:DescribeScalingActivities");
        a.add("autoscaling:DescribeInstanceRefreshes");
        a.add("autoscaling:DescribeLifecycleHooks");
        a.add("autoscaling:DescribeWarmPool");
        a.add("autoscaling:DescribePolicies");
        a.add("autoscaling:DescribeScheduledActions");
        a.add("autoscaling:DescribeTags");
        a.add("autoscaling:DescribeTrafficSources");
        // Read EC2
        a.add("ec2:DescribeLaunchTemplates");
        a.add("ec2:DescribeLaunchTemplateVersions");
        a.add("ec2:DescribeImages");
        a.add("ec2:DescribeInstanceTypes");
        a.add("ec2:DescribeInstanceTypeOfferings");
        a.add("ec2:DescribeInstances");
        a.add("ec2:DescribeSubnets");
        a.add("ec2:DescribeSpotPriceHistory");
        a.add("ec2:DescribeCapacityReservations");
        a.add("ec2:GetSpotPlacementScores");
        a.add("ec2:DescribeRegions");
        // Read other
        a.add("elasticloadbalancing:DescribeTargetGroupAttributes");
        a.add("elasticloadbalancing:DescribeTargetHealth");
        a.add("elasticloadbalancing:DescribeInstanceHealth");
        a.add("ecs:DescribeCapacityProviders");
        a.add("events:DescribeRule");
        a.add("cloudformation:DescribeStacks");
        a.add("iam:GetInstanceProfile");
        // Quota reads
        a.add("servicequotas:GetServiceQuota");
        a.add("cloudwatch:GetMetricData");
        return a;
    }

    /** The write actions on {@code *} added in WRITE mode (§8.2 "Convert, fallback, revert"/"Marker"). */
    static List<String> writeActionsOnStar() {
        List<String> a = new ArrayList<>();
        a.add("autoscaling:UpdateAutoScalingGroup");
        a.add("autoscaling:CreateOrUpdateTags");
        a.add("autoscaling:DeleteTags");
        // Auto Scaling checks the caller may launch from the template (§8.2 "Launch-template use").
        a.add("ec2:RunInstances");
        a.add("ec2:CreateTags");
        return a;
    }

    /** The per-service {@code Describe*} fallback actions on {@code *} (§2.2 DR-17 fallback (1)). */
    static List<String> describeFallbackActionsOnStar() {
        List<String> a = new ArrayList<>();
        a.add("autoscaling:Describe*");
        a.add("ec2:Describe*");
        a.add("ec2:GetSpotPlacementScores");
        a.add("elasticloadbalancing:Describe*");
        a.add("ecs:DescribeCapacityProviders");
        a.add("events:DescribeRule");
        a.add("cloudformation:DescribeStacks");
        a.add("iam:GetInstanceProfile");
        a.add("servicequotas:GetServiceQuota");
        a.add("cloudwatch:GetMetricData");
        return a;
    }

    /** The tag keys the marker statements are conditioned on (§8.3 {@code aws:TagKeys}). */
    static List<String> markerTagKeys() {
        return List.of("xamops:autospotting:group-id", "spot-enabled");
    }

    /** The resource-tag keys the §8.3 Deny statements key on (never touch managed groups). */
    static List<String> denyManagedTagKeys() {
        return List.of("eks:cluster-name", "elasticbeanstalk:environment-name", "karpenter.sh/discovery");
    }

    /**
     * Serialize the minified session policy for {@code mode}. Prefers the exact action lists; falls
     * back to the per-service {@code Describe*} form if the exact document would exceed
     * {@link #MAX_POLICY_CHARS} (DR-17). The returned document is always at most
     * {@link #MAX_POLICY_CHARS}; if even the fallback would exceed it an {@link IllegalStateException}
     * is thrown (it never does with these lists, but the build must fail rather than ship an
     * over-limit policy).
     */
    public String serialize(Mode mode) {
        String exact = build(mode, false);
        if (exact.length() <= MAX_POLICY_CHARS) {
            return exact;
        }
        String fallback = build(mode, true);
        if (fallback.length() > MAX_POLICY_CHARS) {
            throw new IllegalStateException("AutoSpotting " + mode + " session policy exceeds "
                    + MAX_POLICY_CHARS + " chars even with the Describe* fallback (" + fallback.length() + ")");
        }
        return fallback;
    }

    /** True when the serialized {@code mode} policy ships the {@code Describe*} fallback form (DR-17). */
    public boolean usesDescribeFallback(Mode mode) {
        return build(mode, false).length() > MAX_POLICY_CHARS;
    }

    private String build(Mode mode, boolean describeFallback) {
        List<String> statements = new ArrayList<>();

        // Allow the read actions on "*".
        List<String> readStar = describeFallback ? describeFallbackActionsOnStar() : readActionsOnStar();
        statements.add(allowStatement(readStar, "*"));

        // Simulate self only (the connection role's own ARN).
        statements.add(allowStatement(List.of("iam:SimulatePrincipalPolicy"), roleArn));
        // Forwarder-role id fallback (§8.2 "Read other"): GetRole only on the XamOps forwarder roles.
        statements.add(allowStatement(List.of("iam:GetRole"), "arn:aws:iam::*:role/xamops-as-events-fwd-role*"));

        if (mode == Mode.WRITE) {
            // Write actions on "*".
            statements.add(allowStatement(writeActionsOnStar(), "*"));
            // PassRole to EC2 (instance profile in the launch template), §8.2/§8.3.
            statements.add(passRoleToEc2Statement());
            // Three Deny statements (§8.3), one per managed-group tag, session-policy only.
            for (String tagKey : denyManagedTagKeys()) {
                statements.add(denyUpdateWhenTagPresent(tagKey));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"Version\":\"2012-10-17\",\"Statement\":[");
        for (int i = 0; i < statements.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(statements.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String allowStatement(List<String> actions, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":" + jsonActions(actions)
                + ",\"Resource\":" + jsonString(resource) + "}";
    }

    private static String passRoleToEc2Statement() {
        return "{\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"iam:PassedToService\":" + jsonString(SERVICE_EC2) + "}}}";
    }

    private static String denyUpdateWhenTagPresent(String tagKey) {
        // Deny UpdateAutoScalingGroup unless the resource tag is absent (§8.3 Null condition "false").
        return "{\"Effect\":\"Deny\",\"Action\":\"autoscaling:UpdateAutoScalingGroup\",\"Resource\":\"*\","
                + "\"Condition\":{\"Null\":{" + jsonString("aws:ResourceTag/" + tagKey) + ":\"false\"}}}";
    }

    /** Render an action list minified: a single string when size 1, else a sorted JSON array. */
    private static String jsonActions(List<String> actions) {
        // Sort for a deterministic, minimal document (§2.2 "action names sorted").
        TreeSet<String> sorted = new TreeSet<>(actions);
        if (sorted.size() == 1) {
            return jsonString(sorted.first());
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String a : sorted) {
            if (!first) {
                sb.append(',');
            }
            sb.append(jsonString(a));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
