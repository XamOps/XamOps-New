package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plan-phase-12.md 12.1: each serialized session policy is within the STS 2,048-character limit
 * (DR-17), read mode never carries a write action, and write mode carries the three §8.3 Deny
 * statements. If the exact lists ever overflow, the shipped form is the per-service {@code Describe*}
 * fallback, and this test pins which form ships.
 */
class AwsSessionPoliciesTest {

    private static final String ROLE = "arn:aws:iam::111122223333:role/XamOpsReadWrite";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AwsSessionPolicies policies = new AwsSessionPolicies(ROLE);

    @Test
    void eachSerializedPolicyFitsTheStsLimit() {
        for (AwsSessionPolicies.Mode mode : AwsSessionPolicies.Mode.values()) {
            assertThat(policies.serialize(mode).length())
                    .as("%s policy length", mode).isLessThanOrEqualTo(AwsSessionPolicies.MAX_POLICY_CHARS);
        }
    }

    @Test
    void pinsWhichFormShips() {
        // Whichever form ships, the document is within the limit and still read-only for READ. If the exact
        // lists overflow, the Describe* fallback ships; this assertion records the shipped form so a
        // change is a deliberate one.
        boolean readFallback = policies.usesDescribeFallback(AwsSessionPolicies.Mode.READ);
        boolean writeFallback = policies.usesDescribeFallback(AwsSessionPolicies.Mode.WRITE);
        String read = policies.serialize(AwsSessionPolicies.Mode.READ);
        assertThat(read.contains("autoscaling:Describe*")).isEqualTo(readFallback);
        String write = policies.serialize(AwsSessionPolicies.Mode.WRITE);
        assertThat(write.contains("autoscaling:Describe*")).isEqualTo(writeFallback);
    }

    @Test
    void readModeNeverIncludesAWriteAction() throws Exception {
        List<String> actions = allowedActions(policies.serialize(AwsSessionPolicies.Mode.READ));
        assertThat(actions).isNotEmpty();
        assertThat(actions).noneMatch(a -> a.startsWith("autoscaling:Update") || a.startsWith("autoscaling:CreateOrUpdate")
                || a.startsWith("autoscaling:Delete") || a.equals("iam:PassRole") || a.startsWith("ec2:RunInstances")
                || a.contains("StartInstanceRefresh") || a.contains("SetDesiredCapacity"));
        assertThat(JSON.readTree(policies.serialize(AwsSessionPolicies.Mode.READ)).toString())
                .doesNotContain("\"Deny\"");
    }

    @Test
    void readModeCarriesTheRequiredReads() {
        String read = policies.serialize(AwsSessionPolicies.Mode.READ);
        if (!policies.usesDescribeFallback(AwsSessionPolicies.Mode.READ)) {
            assertThat(read).contains("ec2:GetSpotPlacementScores", "elasticloadbalancing:DescribeTargetHealth",
                    "iam:GetInstanceProfile", "servicequotas:GetServiceQuota", "cloudwatch:GetMetricData",
                    "cloudformation:DescribeStacks", "events:DescribeRule");
        }
        // The simulate statement is on the role's own ARN only, and GetRole only on the forwarder roles.
        assertThat(read).contains("iam:SimulatePrincipalPolicy").contains(ROLE).contains("iam:GetRole")
                .contains("role/xamops-as-events-fwd-role*");
    }

    @Test
    void writeModeAddsTheWriteActionsAndTheThreeDenyStatements() throws Exception {
        String write = policies.serialize(AwsSessionPolicies.Mode.WRITE);
        List<String> actions = allowedActions(write);
        assertThat(actions).contains("autoscaling:UpdateAutoScalingGroup", "autoscaling:CreateOrUpdateTags",
                "autoscaling:DeleteTags", "iam:PassRole");
        assertThat(actions).doesNotContain("autoscaling:StartInstanceRefresh", "autoscaling:SetDesiredCapacity");
        long denies = 0;
        for (JsonNode st : JSON.readTree(write).path("Statement")) {
            if ("Deny".equals(st.path("Effect").asText())) {
                denies++;
            }
        }
        assertThat(denies).isEqualTo(3);
        assertThat(write).contains("aws:ResourceTag/eks:cluster-name",
                "aws:ResourceTag/elasticbeanstalk:environment-name", "aws:ResourceTag/karpenter.sh/discovery");
    }

    @Test
    void passRoleIsLimitedToEc2() {
        assertThat(policies.serialize(AwsSessionPolicies.Mode.WRITE))
                .contains("\"iam:PassedToService\":\"ec2.amazonaws.com\"");
    }

    @Test
    void policyIsMinifiedWithSortedActions() throws Exception {
        String read = policies.serialize(AwsSessionPolicies.Mode.READ);
        assertThat(read).doesNotContain("\n").doesNotContain(": ").doesNotContain(", ");
        for (JsonNode st : JSON.readTree(read).path("Statement")) {
            JsonNode a = st.path("Action");
            if (a.isArray()) {
                List<String> list = new ArrayList<>();
                a.forEach(n -> list.add(n.asText()));
                assertThat(list).isSorted();
            }
        }
        assertThat(AwsSessionPolicies.SESSION_NAME).isEqualTo("xamops-autospotting");
    }

    private static List<String> allowedActions(String policy) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode st : JSON.readTree(policy).path("Statement")) {
            if (!"Allow".equals(st.path("Effect").asText())) {
                continue;
            }
            JsonNode a = st.path("Action");
            if (a.isArray()) {
                a.forEach(n -> out.add(n.asText()));
            } else {
                out.add(a.asText());
            }
        }
        return out;
    }
}
