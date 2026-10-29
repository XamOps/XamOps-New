package com.xammer.cloud.onboarding;

import com.xammer.cloud.onboarding.CfnTemplate.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.xammer.cloud.onboarding.CfnTemplate.allows;
import static com.xammer.cloud.onboarding.CfnTemplate.allowsAction;
import static com.xammer.cloud.onboarding.CfnTemplate.asMap;
import static com.xammer.cloud.onboarding.CfnTemplate.canonical;
import static com.xammer.cloud.onboarding.CfnTemplate.flatten;
import static com.xammer.cloud.onboarding.CfnTemplate.minifiedLength;
import static com.xammer.cloud.onboarding.CfnTemplate.parseYaml;
import static com.xammer.cloud.onboarding.CfnTemplate.statementsOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for the three served read-write connection templates (prod, uat, local) at template
 * version rw-2026.10.1. Static: the templates are parsed from {@code cloudformation/}, no AWS call
 * is made, so this runs in CI.
 *
 * <p>Pins what an in-place "Update stack" must never change (anything that replaces the role or a
 * managed policy), read parity with each read-only twin, the AutoSpotting least-privilege policy,
 * the scope of every {@code iam:PassRole}, the Spot actions removed as unused, and the IAM size
 * limits.
 */
class ReadWriteTemplateContractTest {

    /** The module directory is the working directory of a surefire run. */
    private static final Path CLOUDFORMATION = Path.of("..", "cloudformation");

    private static final String VERSION = "rw-2026.10.1";
    private static final String ROLE = "XamOpsReadWriteRole";
    private static final String READ_ONLY_ROLE = "XamOpsReadOnlyRole";
    private static final String READ_ONLY_ACCESS = "arn:aws:iam::aws:policy/ReadOnlyAccess";
    private static final String AUTOSPOTTING_POLICY = "XamOpsAutoSpottingPolicy";

    enum Variant {
        PROD("xamops-readwrite-role.yml", "xamops-readonly-role.yml", "XamOpsReadWriteRole", ""),
        UAT("xamops-readwrite-role-uat.yml", "xamops-readonly-role-uat.yml", "XamOpsReadWriteRole-uat", "-uat"),
        LOCAL("xamops-readwrite-role.local.yml", "xamops-readonly-role-local.yml", "XamOpsReadWriteRole-local", "-local");

        final String readWriteFile;
        final String readOnlyFile;
        final String roleName;
        final String suffix;

        Variant(String readWriteFile, String readOnlyFile, String roleName, String suffix) {
            this.readWriteFile = readWriteFile;
            this.readOnlyFile = readOnlyFile;
            this.roleName = roleName;
            this.suffix = suffix;
        }

        CfnTemplate readWrite() {
            return CfnTemplate.load(CLOUDFORMATION.resolve(readWriteFile));
        }

        CfnTemplate readOnly() {
            return CfnTemplate.load(CLOUDFORMATION.resolve(readOnlyFile));
        }
    }

    // ------------------------------------------------------------------ expected values

    private static final String REMOTE_PRINCIPAL_TRUST = """
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Principal:
                  AWS: !Sub 'arn:aws:iam::${XamOpsAccountId}:role/${XamOpsPrincipalRoleName}'
                Action: 'sts:AssumeRole'
                Condition:
                  StringEquals:
                    'sts:ExternalId': !Ref ExternalId
            """;

    private static final String LOCAL_TRUST = """
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Principal:
                  AWS:
                    - !Sub 'arn:aws:iam::${XamOpsAccountId}:role/XamOps-App-EC2-Role'
                    - !Sub 'arn:aws:iam::${XamOpsAccountId}:root'
                Action: 'sts:AssumeRole'
                Condition:
                  StringEquals:
                    'sts:ExternalId': !Ref ExternalId
            """;

    /** ManagedPolicyName is the logical id plus the variant suffix; Description is fixed. */
    private static final Map<String, String> DESCRIPTIONS = orderedMap(
            "XamOpsPolicyReadExtra", "Extra read permissions for XamOps platform monitoring (cost, optimizer, pricing, etc.)",
            "XamOpsPolicyEC2Manage", "EC2 instance lifecycle management for XamOps (start, stop, terminate, volumes, networking)",
            "XamOpsPolicySpots", "Spot Fleet, EC2 Fleet, and Auto Scaling Group management for XamOps AutoSpotting",
            "XamOpsPolicyTagging", "Tagging permissions across all AWS services for XamOps cost allocation and governance",
            "XamOpsPolicyWaste", "Waste remediation permissions for XamOps - clean up idle snapshots, volumes, AMIs, stopped instances, unused log groups",
            "XamOpsPolicyIAMManage", "IAM user and group lifecycle management for XamOps",
            "XamOpsPolicyComputeManage", "RDS, Lambda, ECS, and EKS resource management for XamOps",
            "XamOpsPolicyStorageManage", "S3 storage management permissions for XamOps",
            "XamOpsPolicyCostManage", "Cost management - budgets, cost explorer, alerts, messaging, and service quotas for XamOps");

    private static final Map<String, String> LOCAL_DESCRIPTIONS = orderedMap(
            "XamOpsPolicyReadExtra", "[LOCAL] Extra read permissions for XamOps platform monitoring",
            "XamOpsPolicyEC2Manage", "[LOCAL] EC2 instance lifecycle management for XamOps",
            "XamOpsPolicySpots", "[LOCAL] Spot Fleet, EC2 Fleet, and ASG management for XamOps AutoSpotting",
            "XamOpsPolicyTagging", "[LOCAL] Tagging permissions across all AWS services for XamOps",
            "XamOpsPolicyWaste", "[LOCAL] Waste remediation - clean up idle snapshots, volumes, AMIs, stopped instances",
            "XamOpsPolicyIAMManage", "[LOCAL] IAM user and group lifecycle management for XamOps",
            "XamOpsPolicyComputeManage", "[LOCAL] RDS, Lambda, ECS, and EKS resource management for XamOps",
            "XamOpsPolicyStorageManage", "[LOCAL] S3 storage management permissions for XamOps",
            "XamOpsPolicyCostManage", "[LOCAL] Cost management - budgets, alarms, messaging, and service quotas for XamOps");

    private static final List<String> LOCAL_PARAMETERS = List.of(
            "XamOpsAccountId", "ExternalId", "EKSClusterNames", "XammerSQSQueueUrl", "XammerRegion",
            "EnableAutoSpotting", "EnableInventoryEvents", "IncludeSecurityFindings", "XamOpsInventoryQueueUrl",
            "XamOpsInventoryRegion", "XamOpsInventoryTemplateUrl", "CurS3Bucket");

    private static final List<String> REMOTE_PARAMETERS = concat(LOCAL_PARAMETERS,
            "XamOpsPrincipalRoleName", "XamOpsInventoryResourceSuffix");

    private static final List<String> AUTOSPOTTING_READS = List.of(
            "autoscaling:DescribeAutoScalingGroups", "autoscaling:DescribeAutoScalingInstances",
            "autoscaling:DescribeInstanceRefreshes", "autoscaling:DescribeLifecycleHooks",
            "autoscaling:DescribePolicies", "autoscaling:DescribeScalingActivities",
            "autoscaling:DescribeScheduledActions", "autoscaling:DescribeTags",
            "autoscaling:DescribeTrafficSources", "autoscaling:DescribeWarmPool",
            "ce:GetReservationCoverage", "ce:GetReservationUtilization",
            "ce:GetSavingsPlansCoverage", "ce:GetSavingsPlansUtilization",
            "cloudformation:DescribeStacks", "cloudwatch:GetMetricData",
            "ec2:DescribeCapacityReservations", "ec2:DescribeImages", "ec2:DescribeInstanceTypeOfferings",
            "ec2:DescribeInstanceTypes", "ec2:DescribeInstances", "ec2:DescribeLaunchTemplateVersions",
            "ec2:DescribeLaunchTemplates", "ec2:DescribePlacementGroups", "ec2:DescribeRegions",
            "ec2:DescribeSpotPriceHistory", "ec2:DescribeSubnets", "ec2:GetSpotPlacementScores",
            "ecs:DescribeCapacityProviders", "elasticloadbalancing:DescribeInstanceHealth",
            "elasticloadbalancing:DescribeTargetGroupAttributes", "elasticloadbalancing:DescribeTargetHealth",
            "events:DescribeRule", "iam:GetInstanceProfile", "servicequotas:GetServiceQuota",
            "ssm:GetParameters");

    /** Removed from XamOpsPolicySpots: no XamOps code path calls them and Phase B does not need them. */
    private static final List<String> REMOVED_ACTIONS = List.of(
            "ec2:RequestSpotInstances", "ec2:CancelSpotInstanceRequests", "ec2:RequestSpotFleet",
            "ec2:CancelSpotFleetRequests", "ec2:ModifySpotFleetRequest", "ec2:CreateFleet", "ec2:DeleteFleets",
            "ec2:ModifyFleet", "ec2:CreateLaunchTemplate", "ec2:DeleteLaunchTemplate",
            "ec2:DeleteLaunchTemplateVersions", "autoscaling:CreateAutoScalingGroup",
            "autoscaling:SetDesiredCapacity", "autoscaling:TerminateInstanceInAutoScalingGroup",
            "autoscaling:PutScalingPolicy", "autoscaling:DeletePolicy", "autoscaling:PutScheduledUpdateGroupAction",
            "autoscaling:DeleteScheduledAction", "autoscaling:AttachInstances", "autoscaling:DetachInstances",
            "autoscaling:CreateLaunchConfiguration", "autoscaling:DeleteLaunchConfiguration");

    /** Writes other features still use: AutoSpotting, EBS automation and waste cleanup. */
    private static final List<String> KEPT_WRITES = List.of(
            "autoscaling:UpdateAutoScalingGroup", "autoscaling:StartInstanceRefresh",
            "autoscaling:DeleteAutoScalingGroup", "ec2:CreateLaunchTemplateVersion", "ec2:ModifyLaunchTemplate");

    private static final List<String> SPOTS_ACTIONS = concat(KEPT_WRITES,
            "ec2:DescribeSpotInstanceRequests", "ec2:DescribeSpotFleetInstances", "ec2:DescribeSpotFleetRequests",
            "ec2:DescribeSpotPriceHistory", "ec2:DescribeFleets", "ec2:DescribeFleetInstances");

    private static final Object ASG_ARN =
            sub("arn:${AWS::Partition}:autoscaling:*:${AWS::AccountId}:autoScalingGroup:*:autoScalingGroupName/*");

    private static final Object PASS_ROLE_SCOPE = parseYaml(
            "!If [HasPassRoleScope, !Ref AutoSpottingPassRoleArns, !Sub 'arn:${AWS::Partition}:iam::${AWS::AccountId}:role/*']");

    private static final Object PASSED_TO_EC2 = parseYaml("StringEquals: {'iam:PassedToService': 'ec2.amazonaws.com'}");

    /** Production event sinks. A non-production variant must never default to one of them. */
    private static final List<String> PRODUCTION_QUEUES = List.of(
            "https://sqs.ap-south-1.amazonaws.com/605134457560/autospotting-events.fifo",
            "https://sqs.ap-south-1.amazonaws.com/605134457560/xamops-inventory-events.fifo");

    // ------------------------------------------------------------------ 1. update-in-place stability

    @ParameterizedTest
    @EnumSource(Variant.class)
    void nothingThatWouldReplaceTheRoleOrAManagedPolicyChanged(Variant variant) {
        CfnTemplate template = variant.readWrite();

        assertThat(template.type(ROLE)).isEqualTo("AWS::IAM::Role");
        Map<String, Object> role = template.properties(ROLE);
        assertThat(role.get("RoleName")).isEqualTo(variant.roleName);
        assertThat(role).doesNotContainKey("Path");
        String trust = variant == Variant.LOCAL ? LOCAL_TRUST : REMOTE_PRINCIPAL_TRUST;
        assertThat(canonical(role.get("AssumeRolePolicyDocument"))).isEqualTo(canonical(parseYaml(trust)));

        Map<String, String> descriptions = variant == Variant.LOCAL ? LOCAL_DESCRIPTIONS : DESCRIPTIONS;
        List<Object> attached = new ArrayList<>(List.of(READ_ONLY_ACCESS));
        descriptions.forEach((logicalId, description) -> {
            assertThat(template.type(logicalId)).as(logicalId).isEqualTo("AWS::IAM::ManagedPolicy");
            Map<String, Object> policy = template.properties(logicalId);
            assertThat(policy.get("ManagedPolicyName")).as(logicalId).isEqualTo(logicalId + variant.suffix);
            assertThat(policy.get("Description")).as(logicalId).isEqualTo(description);
            attached.add(Map.of("Ref", logicalId));
        });
        assertThat(flatten(role.get("ManagedPolicyArns"))).containsExactlyInAnyOrderElementsOf(attached);

        Map<String, Object> parameters = template.section("Parameters");
        assertThat(parameters).containsKeys(
                (variant == Variant.LOCAL ? LOCAL_PARAMETERS : REMOTE_PARAMETERS).toArray(String[]::new));
        parameters.forEach((name, spec) -> {
            if (!name.equals("XamOpsAccountId") && !name.equals("ExternalId")) {
                assertThat(asMap(spec)).as("%s needs a default so Update stack can fill it in", name)
                        .containsKey("Default");
            }
        });
    }

    // ------------------------------------------------------------------ 2. read parity

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theReadWriteRoleGrantsEverythingItsReadOnlyTwinGrants(Variant variant) {
        CfnTemplate readWrite = variant.readWrite();
        CfnTemplate readOnly = variant.readOnly();

        assertThat(flatten(readWrite.properties(ROLE).get("ManagedPolicyArns"))).contains(READ_ONLY_ACCESS);
        assertThat(flatten(readOnly.properties(READ_ONLY_ROLE).get("ManagedPolicyArns"))).contains(READ_ONLY_ACCESS);

        List<Statement> granted = readWrite.roleStatements(ROLE);
        List<String> gaps = new ArrayList<>();
        for (Statement statement : readOnly.roleStatements(READ_ONLY_ROLE)) {
            assertThat(statement.body()).as(statement.toString()).doesNotContainKeys("NotAction", "NotResource");
            if (!"Allow".equals(statement.body().get("Effect"))) {
                continue;
            }
            for (String action : statement.actions()) {
                for (Object resource : statement.resources()) {
                    if (!allows(granted, action, resource, statement.condition())) {
                        gaps.add(statement + ": " + action + " on " + canonical(resource));
                    }
                }
            }
        }
        assertThat(gaps).as("read-only grants the read-write role lacks").isEmpty();
    }

    // ------------------------------------------------------------------ 3. AutoSpotting policy

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theAutoSpottingPolicyGrantsExactlyWhatSpotAutomationNeeds(Variant variant) {
        CfnTemplate template = variant.readWrite();

        assertThat(template.type(AUTOSPOTTING_POLICY)).isEqualTo("AWS::IAM::Policy");
        Map<String, Object> policy = template.properties(AUTOSPOTTING_POLICY);
        assertThat(policy.get("PolicyName")).isEqualTo("xamops-autospotting");
        assertThat(flatten(policy.get("Roles"))).containsExactly(Map.of("Ref", ROLE));

        List<Statement> statements = statementsOf(policy.get("PolicyDocument"), AUTOSPOTTING_POLICY);
        assertThat(statements).extracting(Statement::sid).containsExactly(
                "XamOpsAutoSpottingRead", "XamOpsAutoSpottingForwarderRoleLookup", "XamOpsAutoSpottingSimulateSelf",
                "XamOpsAutoSpottingUpdateGroups", "XamOpsAutoSpottingMarkerTags", "XamOpsAutoSpottingLaunchTemplateUse",
                "XamOpsAutoSpottingTagOnLaunch", "XamOpsAutoSpottingPassRoleToEc2");
        assertThat(statements).allMatch(Statement::isAllow);

        assertStatement(statements, "XamOpsAutoSpottingRead", AUTOSPOTTING_READS, "*", null);
        assertStatement(statements, "XamOpsAutoSpottingForwarderRoleLookup", List.of("iam:GetRole"),
                sub("arn:${AWS::Partition}:iam::${AWS::AccountId}:role/xamops-as-events-fwd-role*"), null);
        assertStatement(statements, "XamOpsAutoSpottingSimulateSelf", List.of("iam:SimulatePrincipalPolicy"),
                Map.of("Fn::GetAtt", "XamOpsReadWriteRole.Arn"), null);
        assertStatement(statements, "XamOpsAutoSpottingUpdateGroups",
                List.of("autoscaling:UpdateAutoScalingGroup", "autoscaling:StartInstanceRefresh"), ASG_ARN, null);
        assertStatement(statements, "XamOpsAutoSpottingMarkerTags",
                List.of("autoscaling:CreateOrUpdateTags", "autoscaling:DeleteTags"), ASG_ARN,
                parseYaml("'ForAllValues:StringEquals': {'aws:TagKeys': ['spot-enabled', 'xamops:autospotting:group-id']}"));
        assertStatement(statements, "XamOpsAutoSpottingLaunchTemplateUse", List.of("ec2:RunInstances"), "*",
                parseYaml("ArnLike: {'ec2:LaunchTemplate': !Sub 'arn:${AWS::Partition}:ec2:*:${AWS::AccountId}:launch-template/*'}"));
        assertStatement(statements, "XamOpsAutoSpottingTagOnLaunch", List.of("ec2:CreateTags"),
                sub("arn:${AWS::Partition}:ec2:*:${AWS::AccountId}:*/*"),
                parseYaml("StringEquals: {'ec2:CreateAction': 'RunInstances'}"));
        assertStatement(statements, "XamOpsAutoSpottingPassRoleToEc2", List.of("iam:PassRole"),
                PASS_ROLE_SCOPE, PASSED_TO_EC2);

        for (String read : AUTOSPOTTING_READS) {
            assertThat(allows(statements, read, "*")).as(read).isTrue();
        }
    }

    @Test
    void theAutoSpottingPolicyAndTheSpotsPolicyAreIdenticalInEveryVariant() {
        List<CfnTemplate> templates = Arrays.stream(Variant.values()).map(Variant::readWrite).toList();
        String autoSpotting = canonical(templates.get(0).resource(AUTOSPOTTING_POLICY));
        String spots = canonical(templates.get(0).properties("XamOpsPolicySpots").get("PolicyDocument"));
        String rightsizingPassRole = canonical(
                statement(templates.get(0).roleStatements(ROLE), "XamOpsRightsizingPassRoleToEc2").body());

        for (CfnTemplate template : templates) {
            assertThat(canonical(template.resource(AUTOSPOTTING_POLICY))).as(template.name()).isEqualTo(autoSpotting);
            assertThat(canonical(template.properties("XamOpsPolicySpots").get("PolicyDocument")))
                    .as(template.name()).isEqualTo(spots);
            assertThat(canonical(statement(template.roleStatements(ROLE), "XamOpsRightsizingPassRoleToEc2").body()))
                    .as(template.name()).isEqualTo(rightsizingPassRole);
        }
    }

    // ------------------------------------------------------------------ 4. iam:PassRole

    @ParameterizedTest
    @EnumSource(Variant.class)
    void everyPassRoleIsConditionedOnTheServiceAndNeverOnEveryRole(Variant variant) {
        List<Statement> statements = variant.readWrite().roleStatements(ROLE);

        List<Statement> passRole = statements.stream()
                .filter(s -> "Allow".equals(s.body().get("Effect")) && s.matchesAction("iam:PassRole"))
                .toList();
        assertThat(passRole).extracting(Statement::sid).contains(
                "XamOpsRightsizingPassRoleToEc2", "XamOpsAutoSpottingPassRoleToEc2", "HandTheWorkToCloudFormation");
        for (Statement statement : passRole) {
            assertThat(statement.condition()).as(statement.toString()).isNotNull();
            assertThat(conditionKeys(statement.condition())).as(statement.toString()).contains("iam:PassedToService");
            assertThat(statement.resources()).as(statement.toString()).isNotEmpty().doesNotContain("*");
        }

        for (String sid : List.of("XamOpsRightsizingPassRoleToEc2", "XamOpsAutoSpottingPassRoleToEc2")) {
            assertStatement(statements, sid, List.of("iam:PassRole"), PASS_ROLE_SCOPE, PASSED_TO_EC2);
        }
    }

    // ------------------------------------------------------------------ 5. removed Spot actions

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theUnusedSpotActionsAreGoneAndTheUsedOnesStay(Variant variant) {
        CfnTemplate template = variant.readWrite();
        List<Statement> statements = template.roleStatements(ROLE);

        for (String removed : REMOVED_ACTIONS) {
            assertThat(allowsAction(statements, removed)).as(removed).isFalse();
        }
        for (String kept : KEPT_WRITES) {
            assertThat(allows(statements, kept, "*")).as(kept).isTrue();
        }
        List<String> spotsActions = statementsOf(template.properties("XamOpsPolicySpots").get("PolicyDocument"), "spots")
                .stream().flatMap(s -> s.actions().stream()).toList();
        assertThat(spotsActions).containsExactlyInAnyOrderElementsOf(SPOTS_ACTIONS);
    }

    // ------------------------------------------------------------------ 6. version marker

    @ParameterizedTest
    @EnumSource(Variant.class)
    void theTemplateCarriesItsVersionAndThePassRoleScopeParameter(Variant variant) {
        CfnTemplate template = variant.readWrite();

        assertThat(asMap(template.section("Metadata").get("XamOps")).get("TemplateVersion")).isEqualTo(VERSION);
        assertThat(asMap(template.section("Outputs").get("TemplateVersion")).get("Value")).isEqualTo(VERSION);

        Map<String, Object> scope = asMap(template.section("Parameters").get("AutoSpottingPassRoleArns"));
        assertThat(scope.get("Type")).isEqualTo("CommaDelimitedList");
        assertThat(scope.get("Default")).isEqualTo("");
        assertThat(canonical(template.section("Conditions").get("HasPassRoleScope")))
                .isEqualTo(canonical(parseYaml("!Not [!Equals [!Join ['', !Ref AutoSpottingPassRoleArns], '']]")));
    }

    // ------------------------------------------------------------------ 7. IAM size limits

    @ParameterizedTest
    @EnumSource(Variant.class)
    void thePoliciesFitIamSizeLimits(Variant variant) {
        CfnTemplate template = variant.readWrite();
        Map<String, Object> role = template.properties(ROLE);

        int inline = 0;
        for (Object entry : (List<?>) role.get("Policies")) {
            int largestBranch = 0;
            for (Object branch : flatten(entry)) {
                largestBranch = Math.max(largestBranch, minifiedLength(asMap(branch).get("PolicyDocument")));
            }
            inline += largestBranch;
        }
        Map<String, Object> roleRef = Map.of("Ref", ROLE);
        for (String id : template.section("Resources").keySet()) {
            if ("AWS::IAM::Policy".equals(template.type(id))
                    && flatten(template.properties(id).get("Roles")).contains(roleRef)) {
                inline += minifiedLength(template.properties(id).get("PolicyDocument"));
            }
        }
        assertThat(inline).as("inline policies of the role, IAM limit 10,240").isLessThanOrEqualTo(10_240);

        List<Object> attached = flatten(role.get("ManagedPolicyArns"));
        assertThat(attached).as("managed policies attached, default quota 10").hasSizeLessThanOrEqualTo(10);
        for (Object arn : attached) {
            if (arn instanceof Map<?, ?> ref && ref.get("Ref") instanceof String id) {
                assertThat(minifiedLength(template.properties(id).get("PolicyDocument")))
                        .as("%s, IAM limit 6,144", id).isLessThanOrEqualTo(6_144);
            }
        }
    }

    // ------------------------------------------------------------------ non-production defaults

    @ParameterizedTest
    @EnumSource(value = Variant.class, names = {"UAT", "LOCAL"})
    void aNonProductionVariantNeverDefaultsToAProductionQueue(Variant variant) {
        Map<String, Object> parameters = variant.readWrite().section("Parameters");

        parameters.forEach((name, spec) -> assertThat(PRODUCTION_QUEUES).as(name)
                .doesNotContain(String.valueOf(asMap(spec).get("Default"))));
        // No AutoSpotting queue of its own yet: blank, with forwarding off.
        assertThat(asMap(parameters.get("XammerSQSQueueUrl")).get("Default")).isEqualTo("");
        assertThat(asMap(parameters.get("EnableAutoSpotting")).get("Default")).isEqualTo("false");
        // Its own inventory queue, the one its app reads and passes in the quick-create link.
        assertThat(asMap(parameters.get("XamOpsInventoryQueueUrl")).get("Default")).isEqualTo(
                "https://sqs.ap-south-1.amazonaws.com/605134457560/xamops-inventory-events" + variant.suffix + ".fifo");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertStatement(List<Statement> statements, String sid, List<String> actions,
            Object resource, Object condition) {
        Statement statement = statement(statements, sid);
        assertThat(statement.isAllow()).as(sid).isTrue();
        assertThat(statement.actions()).as(sid).containsExactlyInAnyOrderElementsOf(actions);
        assertThat(canonical(statement.body().get("Resource"))).as(sid).isEqualTo(canonical(resource));
        assertThat(canonical(statement.condition())).as(sid).isEqualTo(canonical(condition));
    }

    private static Statement statement(List<Statement> statements, String sid) {
        List<Statement> matches = statements.stream().filter(s -> sid.equals(s.sid())).toList();
        assertThat(matches).as("statements with Sid %s", sid).hasSize(1);
        return matches.get(0);
    }

    private static List<String> conditionKeys(Object condition) {
        List<String> keys = new ArrayList<>();
        asMap(condition).values().forEach(block -> keys.addAll(asMap(block).keySet()));
        return keys;
    }

    private static Object sub(String text) {
        return Map.of("Fn::Sub", text);
    }

    private static List<String> concat(List<String> list, String... more) {
        List<String> out = new ArrayList<>(list);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    private static Map<String, String> orderedMap(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
