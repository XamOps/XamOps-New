package com.xammer.cloud.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.onboarding.PermissionManifestService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP-8 parity (design-delta §3.3): assert the rendered write policy is condition/action-equivalent
 * to the ACTUAL shipped {@code xamops-automation-consolidation-policy.json} on the invariant fields —
 * the terminate action set, the cluster-name {@code StringEquals} + {@code Null} nodegroup-name
 * guard — while asserting the deliberate tightening (scoped terminate ARN, not {@code "*"}) and the
 * dropped {@code ec2:DescribeInstances}. This is NOT a whole-document equality (which would fail on
 * the intentional differences).
 */
class PermissionManifestParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Locate the shipped IAM JSON relative to the module (sibling xamops-autoscaler module). */
    private static Path shippedPolicyPath() {
        Path moduleRoot = Path.of(System.getProperty("user.dir")); // xamops-service when surefire runs
        Path candidate = moduleRoot.resolve(
                "../xamops-autoscaler/manifests/iam/xamops-automation-consolidation-policy.json").normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Fallback: search upward for the worktree root.
        Path p = moduleRoot;
        for (int i = 0; i < 4 && p != null; i++) {
            Path c = p.resolve("xamops-autoscaler/manifests/iam/xamops-automation-consolidation-policy.json");
            if (Files.exists(c)) {
                return c;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("shipped consolidation policy not found from " + moduleRoot);
    }

    private JsonNode shipped() throws Exception {
        return JSON.readTree(Files.readString(shippedPolicyPath()));
    }

    private static List<String> actionsOf(JsonNode statement) {
        List<String> out = new ArrayList<>();
        JsonNode action = statement.get("Action");
        if (action.isArray()) {
            action.forEach(a -> out.add(a.asText()));
        } else {
            out.add(action.asText());
        }
        return out;
    }

    private static JsonNode terminateStatementShipped(JsonNode policy) {
        for (JsonNode s : policy.get("Statement")) {
            if (actionsOf(s).contains("autoscaling:TerminateInstanceInAutoScalingGroup")) {
                return s;
            }
        }
        throw new IllegalStateException("no terminate statement in shipped policy");
    }

    @Test
    void shippedPolicy_terminateGuard_matchesRenderedGuard() throws Exception {
        JsonNode shippedTerminate = terminateStatementShipped(shipped());

        // Invariant: the terminate condition set (cluster-name StringEquals + Null nodegroup-name).
        JsonNode cond = shippedTerminate.get("Condition");
        assertThat(cond.get("StringEquals").has("aws:ResourceTag/eks:cluster-name")).isTrue();
        assertThat(cond.get("Null").get("aws:ResourceTag/eks:nodegroup-name").asText()).isEqualTo("false");

        // The rendered policy applies the SAME guard, with the templated cluster name.
        PermissionManifestService svc = new PermissionManifestService();
        @SuppressWarnings("unchecked")
        Map<String, Object> rendered = svc.writePolicy("xamops-test-1", "ap-south-1", "102227938259", true);
        JsonNode renderedNode = JSON.valueToTree(rendered);
        JsonNode renderedTerminate = terminateStatementShipped(renderedNode);

        assertThat(actionsOf(renderedTerminate))
                .containsExactly("autoscaling:TerminateInstanceInAutoScalingGroup");
        assertThat(renderedTerminate.get("Condition").get("StringEquals")
                .get("aws:ResourceTag/eks:cluster-name").asText()).isEqualTo("xamops-test-1");
        assertThat(renderedTerminate.get("Condition").get("Null")
                .get("aws:ResourceTag/eks:nodegroup-name").asText()).isEqualTo("false");
    }

    @Test
    void renderedPolicy_tightensTerminateArn_notStar() throws Exception {
        // Shipped uses Resource "*"; the rendered policy tightens to the scoped ARN (deliberate).
        JsonNode shippedTerminate = terminateStatementShipped(shipped());
        assertThat(shippedTerminate.get("Resource").asText()).isEqualTo("*");

        PermissionManifestService svc = new PermissionManifestService();
        Map<String, Object> rendered = svc.writePolicy("xamops-test-1", "ap-south-1", "102227938259", true);
        JsonNode renderedTerminate = terminateStatementShipped(JSON.valueToTree(rendered));
        assertThat(renderedTerminate.get("Resource").asText())
                .isEqualTo("arn:aws:autoscaling:ap-south-1:102227938259:autoScalingGroup:*:autoScalingGroupName/*")
                .isNotEqualTo("*");
    }

    @Test
    void renderedPolicy_dropsUnusedEc2DescribeInstances_shippedStillHasIt() throws Exception {
        // The shipped dev JSON carries the unused grant; the rendered policy must not.
        List<String> shippedActions = new ArrayList<>();
        shipped().get("Statement").forEach(s -> shippedActions.addAll(actionsOf(s)));
        assertThat(shippedActions).contains("ec2:DescribeInstances");

        PermissionManifestService svc = new PermissionManifestService();
        Map<String, Object> rendered = svc.writePolicy("xamops-test-1", "ap-south-1", "102227938259", true);
        List<String> renderedActions = new ArrayList<>();
        JSON.valueToTree(rendered).get("Statement").forEach(s -> renderedActions.addAll(actionsOf(s)));
        assertThat(renderedActions).doesNotContain("ec2:DescribeInstances");
        assertThat(renderedActions).hasSize(4);
    }
}
