package com.xammer.cloud.onboarding;

import com.xammer.cloud.service.onboarding.OnboardingFeatureCatalog;
import com.xammer.cloud.service.onboarding.PermissionManifestService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BP-8 (design §5.3/§5.4, design-delta §3): the rendered write policy has EXACTLY four actions with
 * the unused {@code ec2:DescribeInstances} dropped, the scoped terminate ARN (not {@code "*"}), and
 * the cluster-name + {@code Null eks:nodegroup-name} managed-nodegroup guard. The node-removal
 * predicate is a single function.
 */
class PermissionManifestServiceTest {

    private final PermissionManifestService svc = new PermissionManifestService();

    @Test
    void nodeRemovalPredicate_onlyNone_plusConsolidation_plusPfComputePass() {
        List<String> consolidation = List.of(OnboardingFeatureCatalog.NODE_CONSOLIDATION);
        assertThat(svc.nodeRemoval("NONE", consolidation, true)).isTrue();
        assertThat(svc.nodeRemoval("NONE", consolidation, false)).isFalse();        // PF-COMPUTE not PASS
        assertThat(svc.nodeRemoval("KARPENTER", consolidation, true)).isFalse();     // scaler present
        assertThat(svc.nodeRemoval("NONE", List.of(OnboardingFeatureCatalog.WORKLOAD_AUTOSCALING), true)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void writePolicy_hasExactlyFourActions_scopedTerminateArn_nullGuard_noEc2() {
        Map<String, Object> policy = svc.writePolicy("xamops-test-1", "ap-south-1", "102227938259", true);
        assertThat(policy).isNotNull();
        List<Map<String, Object>> statements = (List<Map<String, Object>>) policy.get("Statement");

        // Collect every granted action across statements.
        List<String> actions = statements.stream()
                .flatMap(s -> ((List<String>) s.get("Action")).stream())
                .toList();
        assertThat(actions).containsExactlyInAnyOrder(
                "autoscaling:DescribeAutoScalingInstances",
                "autoscaling:DescribeAutoScalingGroups",
                "eks:DescribeNodegroup",
                "autoscaling:TerminateInstanceInAutoScalingGroup");
        assertThat(actions).doesNotContain("ec2:DescribeInstances");

        // Terminate statement: scoped ARN + both conditions.
        Map<String, Object> terminate = statements.stream()
                .filter(s -> ((List<String>) s.get("Action")).contains("autoscaling:TerminateInstanceInAutoScalingGroup"))
                .findFirst().orElseThrow();
        assertThat((String) terminate.get("Resource"))
                .isEqualTo("arn:aws:autoscaling:ap-south-1:102227938259:autoScalingGroup:*:autoScalingGroupName/*");
        assertThat((String) terminate.get("Resource")).isNotEqualTo("*");

        Map<String, Object> condition = (Map<String, Object>) terminate.get("Condition");
        Map<String, Object> stringEquals = (Map<String, Object>) condition.get("StringEquals");
        assertThat(stringEquals).containsEntry("aws:ResourceTag/eks:cluster-name", "xamops-test-1");
        Map<String, Object> nullCond = (Map<String, Object>) condition.get("Null");
        assertThat(nullCond).containsEntry("aws:ResourceTag/eks:nodegroup-name", "false");
    }

    @Test
    void writePolicy_isNullWhenNodeRemovalFalse() {
        assertThat(svc.writePolicy("c", "r", "a", false)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void manifest_automation_identityOnlyWriteRole_whenNoRemoval() {
        Map<String, Object> m = svc.manifest("AUTOMATION", "c", "ap-south-1", "102227938259",
                "POD_IDENTITY", "KARPENTER", List.of(OnboardingFeatureCatalog.NODE_CONSOLIDATION),
                false, List.of(), List.of());
        List<Map<String, Object>> aws = (List<Map<String, Object>>) m.get("aws");
        assertThat(aws).hasSize(1);
        assertThat(aws.get(0)).containsEntry("role", "write");
        assertThat(aws.get(0).get("policy")).isNull();
        assertThat((String) m.get("hash")).matches("[0-9a-f]{64}");
    }

    @Test
    void manifest_hashChangesWithNodeRemoval() {
        Map<String, Object> on = svc.manifest("AUTOMATION", "c", "ap-south-1", "102227938259",
                "POD_IDENTITY", "NONE", List.of(OnboardingFeatureCatalog.NODE_CONSOLIDATION), true, List.of(), List.of());
        Map<String, Object> off = svc.manifest("AUTOMATION", "c", "ap-south-1", "102227938259",
                "POD_IDENTITY", "NONE", List.of(OnboardingFeatureCatalog.NODE_CONSOLIDATION), false, List.of(), List.of());
        assertThat(on.get("hash")).isNotEqualTo(off.get("hash"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readPolicy_hasTwoScopedActions() {
        Map<String, Object> policy = svc.readPolicy("c", "ap-south-1", "102227938259");
        List<Map<String, Object>> statements = (List<Map<String, Object>>) policy.get("Statement");
        List<String> actions = statements.stream()
                .flatMap(s -> ((List<String>) s.get("Action")).stream())
                .toList();
        assertThat(actions).containsExactlyInAnyOrder("eks:ListNodegroups", "eks:DescribeNodegroup");
    }
}
