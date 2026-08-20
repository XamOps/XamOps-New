package com.xammer.cloud.service.inventory;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates Resource Explorer's type tokens ({@code ec2:instance}) into the display
 * strings XamOps already uses ({@code "EC2 Instance"}).
 *
 * <p>
 * This class exists because consumers filter on the display string by <em>equality</em>,
 * not by pattern: {@code XamOpsRightsizingService} keeps only {@code "EC2 Instance"},
 * {@code CloudMapService} keeps only {@code "VPC"}, and the frontend groups the cloud
 * list by the same value. If the inventory plane invented its own vocabulary those
 * pages would render empty with no error anywhere — the worst kind of regression. So
 * every string below is copied verbatim from the {@code new ResourceDto(...)} calls in
 * {@code CloudListService}, and changing one here without changing it there breaks a
 * page silently.
 *
 * <p>
 * Unmapped types are not dropped. {@link #displayTypeFor} falls back to a readable
 * label derived from the type token, so a service AWS launches next month still shows
 * up in the cloud list — the same reasoning behind Azure's
 * {@code fetchGenericResources} catch-all. The alternative, filtering to a known list,
 * is how inventories quietly go stale.
 */
public final class AwsResourceTypeMapper {

    private static final Map<String, String> TYPE_TO_DISPLAY = new HashMap<>();

    /** Types deliberately excluded from the cloud list. */
    private static final Map<String, Boolean> SUPPRESSED = new HashMap<>();

    static {
        // ── Compute ──────────────────────────────────────────────────────────
        map("ec2:instance", "EC2 Instance");
        map("ec2:volume", "EBS Volume");
        map("ec2:snapshot", "EBS Snapshot");
        map("ec2:image", "AMI");
        map("ec2:network-interface", "Network Interface");
        map("ec2:elastic-ip", "Elastic IP");
        map("ec2:security-group", "Security Group");
        map("ec2:vpc", "VPC");
        map("ec2:subnet", "Subnet");
        map("ec2:internet-gateway", "Internet Gateway");
        map("ec2:natgateway", "NAT Gateway");
        map("ec2:route-table", "Route Table");
        map("ec2:network-acl", "Network ACL");
        map("ec2:vpc-endpoint", "VPC Endpoint");
        map("ec2:transit-gateway", "Transit Gateway");
        map("ec2:launch-template", "Launch Template");
        map("lambda:function", "Lambda Function");
        map("autoscaling:autoScalingGroup", "Auto Scaling Group");
        map("lightsail:Instance", "Lightsail Instance");
        map("elasticbeanstalk:environment", "Elastic Beanstalk Environment");

        // ── Containers ───────────────────────────────────────────────────────
        map("eks:cluster", "EKS Cluster");
        map("ecs:cluster", "ECS Cluster");
        map("ecs:service", "ECS Service");
        map("ecs:task-definition", "ECS Task Definition");
        map("ecr:repository", "ECR Repository");

        // ── Storage ──────────────────────────────────────────────────────────
        map("s3:bucket", "S3 Bucket");
        map("elasticfilesystem:file-system", "EFS File System");
        map("fsx:file-system", "FSx File System");
        map("backup:backup-vault", "Backup Vault");

        // ── Databases ────────────────────────────────────────────────────────
        map("rds:db", "RDS Instance");
        map("rds:cluster", "RDS Cluster (Aurora)");
        map("rds:snapshot", "RDS Snapshot");
        map("rds:cluster-snapshot", "RDS Cluster Snapshot");
        map("dynamodb:table", "DynamoDB Table");
        map("elasticache:cluster", "ElastiCache Cluster");
        map("elasticache:replicationgroup", "ElastiCache Replication Group");
        map("memorydb:cluster", "MemoryDB Cluster");
        map("redshift:cluster", "Redshift Cluster");
        map("neptune:db", "Neptune Cluster");
        map("docdb:db", "DocumentDB Cluster");
        map("es:domain", "OpenSearch Domain");
        map("opensearch:domain", "OpenSearch Domain");
        map("kafka:cluster", "MSK Cluster");

        // ── Networking / delivery ────────────────────────────────────────────
        // ELB v2 types carry a subtype suffix in live data — the real tokens are
        // "loadbalancer/app", "loadbalancer/net" and so on, not a bare "loadbalancer".
        // All must collapse to the single "Load Balancer" label, because CloudMapService
        // and the waste checks filter on that exact string; leaving the suffixed variants
        // unmapped would humanise them into separate groups and empty those pages.
        map("elasticloadbalancing:loadbalancer", "Load Balancer");
        map("elasticloadbalancing:loadbalancer/app", "Load Balancer");
        map("elasticloadbalancing:loadbalancer/net", "Load Balancer");
        map("elasticloadbalancing:loadbalancer/gwy", "Load Balancer");
        map("elasticloadbalancing:targetgroup", "Target Group");
        map("elasticloadbalancing:listener/net", "Load Balancer Listener");
        map("elasticloadbalancing:listener-rule/app", "Load Balancer Listener Rule");
        map("elasticloadbalancing:listener-rule/net", "Load Balancer Listener Rule");
        map("cloudfront:distribution", "CloudFront");
        map("route53:hostedzone", "Route 53 Zone");
        map("apigateway:restapis", "API Gateway");
        map("apigateway:apis", "API Gateway");
        map("globalaccelerator:accelerator", "Global Accelerator");

        // ── Messaging / integration ──────────────────────────────────────────
        map("sns:topic", "SNS Topic");
        map("sqs:queue", "SQS Queue");
        map("events:event-bus", "EventBridge Bus");
        map("events:rule", "EventBridge Rule");
        map("states:stateMachine", "AWS Step Functions");
        map("kinesis:stream", "Kinesis Stream");
        map("firehose:deliverystream", "Kinesis Firehose");

        // ── Security / identity ──────────────────────────────────────────────
        map("kms:key", "KMS");
        map("secretsmanager:secret", "Secrets Manager Secret");
        map("acm:certificate", "Certificate Manager");
        map("wafv2:webacl", "WAF");
        map("cognito-idp:userpool", "Amazon Cognito");
        map("iam:role", "IAM Role");
        map("iam:user", "IAM User");
        map("iam:policy", "IAM Policy");
        map("iam:instance-profile", "IAM Instance Profile");

        // ── Types seen in live account data but previously unmapped ──────────
        // Each of these appeared in a real Resource Explorer response and would
        // otherwise have been auto-humanised. Naming them explicitly keeps the
        // cloud list wording consistent with the rest of the product.
        map("ec2:key-pair", "EC2 Key Pair");
        map("ec2:dhcp-options", "DHCP Options Set");
        map("ec2:network-acl", "Network ACL");
        map("ec2:route-table", "Route Table");
        map("ec2:image", "AMI");
        map("events:rule", "EventBridge Rule");
        map("cloudformation:stack", "CloudFormation Stack");
        map("elasticloadbalancing:listener/app", "Load Balancer Listener");
        map("elasticache:user", "ElastiCache User");
        map("memorydb:user", "MemoryDB User");
        map("memorydb:acl", "MemoryDB ACL");
        map("memorydb:parametergroup", "MemoryDB Parameter Group");
        map("athena:datacatalog", "Athena Data Catalog");
        map("xray:sampling-rule", "X-Ray Sampling Rule");
        map("apprunner:autoscalingconfiguration", "App Runner Autoscaling Config");
        map("ec2:vpc-flow-log", "VPC Flow Log");
        map("ssm:parameter", "SSM Parameter");
        map("ssm:session", "SSM Session");
        map("iam:group", "IAM Group");
        map("iam:mfa", "IAM MFA Device");
        map("budgets:budget", "Budget");
        map("events:api-destination", "EventBridge API Destination");
        map("events:connection", "EventBridge Connection");
        map("cloudformation:stackset", "CloudFormation StackSet");
        map("rds:subgrp", "RDS Subnet Group");
        map("rds:pg", "RDS Parameter Group");
        map("rds:og", "RDS Option Group");
        map("rds:secgrp", "RDS Security Group");
        map("cloudtrail:trail", "CloudTrail");
        map("config:config-rule", "AWS Config");

        // ── Observability ────────────────────────────────────────────────────
        map("logs:log-group", "CloudWatch Log Group");
        map("cloudwatch:alarm", "CloudWatch Alarm");
        map("ssm:managed-instance", "SSM Managed Instance");

        // ── Data / analytics / AI ────────────────────────────────────────────
        map("glue:database", "AWS Glue");
        map("glue:table", "AWS Glue Table");
        map("athena:workgroup", "Amazon Athena");
        map("datazone:domain", "DataZone Domain");
        map("sagemaker:notebook-instance", "SageMaker");
        map("sagemaker:endpoint", "SageMaker Endpoint");
        map("bedrock:provisioned-model", "Bedrock Provisioned Model");

        // ── Developer tooling ────────────────────────────────────────────────
        map("codecommit:repository", "CodeCommit Repository");
        map("codebuild:project", "CodeBuild Project");
        map("codepipeline:pipeline", "CodePipeline");
        map("amplify:apps", "Amplify App");
        map("cloudformation:stack", "CloudFormation Stack");
        map("mobiletargeting:apps", "Pinpoint Application");

        // ── Suppressed ───────────────────────────────────────────────────────
        //
        // These are real Resource Explorer types, excluded because they are sub-components
        // or bookkeeping rather than things a user thinks of as a resource. The counts in
        // brackets are from a real account of ~1900 resources: left in, they would have
        // been the single largest entry in the cloud list, pushing EC2 instances (9) and
        // S3 buckets (12) far down the page. The current describe-based list shows none of
        // them, so including them would read as a regression rather than better coverage.

        // Individual rules inside a security group [414]. The group itself is listed.
        SUPPRESSED.put("ec2:security-group-rule", Boolean.TRUE);
        // Resource Explorer's own per-region indexes [17] — our plumbing, not their estate.
        SUPPRESSED.put("resource-explorer-2:index", Boolean.TRUE);
        SUPPRESSED.put("resource-explorer-2:view", Boolean.TRUE);
        // Every published Lambda version [17] duplicates its function.
        SUPPRESSED.put("lambda:function/version", Boolean.TRUE);
        // SSM associations and per-instance compliance rows are operational records.
        SUPPRESSED.put("ssm:association", Boolean.TRUE);
        // CloudFormation emits a stackset-target row per managed resource, which
        // double-counts everything the stack created.
        SUPPRESSED.put("cloudformation:stackset-target", Boolean.TRUE);
    }

    /**
     * State strings for types whose state never varies, copied from the describe path.
     *
     * <p>
     * Resource Explorer reports no state at all — only ARN, type, region and tags — so
     * every row the reconciler wrote had a null state and the UI rendered "Unknown" where
     * it used to say "Available". For most types that is recoverable without any API call,
     * because the describe path did not learn the state either: it hardcoded a literal.
     * {@code CloudListService} passes {@code "Available"} for an S3 bucket and
     * {@code "Active"} for a Lambda function, on the reasoning that existing and being
     * available are the same thing for those services.
     *
     * <p>
     * The literals are reproduced exactly, including the inconsistent casing between
     * {@code "Available"}, {@code "Active"} and Internet Gateway's lowercase
     * {@code "available"}. Normalising them here would be tidier and wrong: the frontend
     * maps state strings to badge colours, so a changed string is a changed colour.
     *
     * <p>
     * Types genuinely carrying variable state — EC2 instances, EBS volumes, RDS instances —
     * are deliberately absent. Guessing "running" for a stopped instance would be worse
     * than "Unknown"; those are filled in by a live describe during reconciliation.
     */
    private static final Map<String, String> STATIC_STATE = new HashMap<>();

    static {
        // Existence implies availability.
        STATIC_STATE.put("s3:bucket", "Available");
        STATIC_STATE.put("ec2:security-group", "Available");
        STATIC_STATE.put("route53:hostedzone", "Available");
        STATIC_STATE.put("ecr:repository", "Available");
        STATIC_STATE.put("codebuild:project", "Available");
        STATIC_STATE.put("codecommit:repository", "Available");
        STATIC_STATE.put("amplify:apps", "Available");
        // Lowercase in the describe path, matching EC2's own API vocabulary.
        STATIC_STATE.put("ec2:internet-gateway", "available");
        // Existence implies the thing is running/serving.
        STATIC_STATE.put("lambda:function", "Active");
        STATIC_STATE.put("sns:topic", "Active");
        STATIC_STATE.put("sqs:queue", "Active");
        STATIC_STATE.put("apigateway:restapis", "Active");
        STATIC_STATE.put("apigateway:apis", "Active");
        STATIC_STATE.put("autoscaling:autoScalingGroup", "Active");
        STATIC_STATE.put("cloudtrail:trail", "Active");
        STATIC_STATE.put("logs:log-group", "Active");
        STATIC_STATE.put("codepipeline:pipeline", "Active");
        STATIC_STATE.put("events:event-bus", "Active");
        STATIC_STATE.put("events:rule", "Active");
        STATIC_STATE.put("kinesis:stream", "Active");
        STATIC_STATE.put("es:domain", "Active");
        STATIC_STATE.put("opensearch:domain", "Active");
        STATIC_STATE.put("mobiletargeting:apps", "Active");
        STATIC_STATE.put("secretsmanager:secret", "Active");

        // Types the describe path also could not vary, but which read more naturally with
        // their own API's vocabulary than a generic "Active".
        STATIC_STATE.put("ec2:vpc", "available");
        STATIC_STATE.put("ec2:subnet", "available");
        STATIC_STATE.put("ec2:snapshot", "completed");
        STATIC_STATE.put("ec2:natgateway", "available");
        STATIC_STATE.put("elasticloadbalancing:loadbalancer", "active");
        STATIC_STATE.put("elasticloadbalancing:loadbalancer/app", "active");
        STATIC_STATE.put("elasticloadbalancing:loadbalancer/net", "active");
        STATIC_STATE.put("elasticloadbalancing:loadbalancer/gwy", "active");

        // ── Types with no state concept in AWS at all ────────────────────────
        //
        // These have no status field to read: an IAM role either exists or it does not,
        // and neither does a route table, a key pair or a parameter group. They are also
        // types the old describe path never listed, so nothing regressed here — they became
        // visible only because Resource Explorer covers far more than the 57 hand-written
        // fetchers did. Labelling them is therefore not an invention, it is the only
        // sensible rendering; leaving them null would fill the cloud list with "Unknown"
        // for resources whose state is not in question.
        for (String alwaysActive : new String[] {
                "iam:role", "iam:policy", "iam:user", "iam:group", "iam:instance-profile",
                "iam:mfa", "iam:oidc-provider", "iam:saml-provider",
                "xray:sampling-rule", "ssm:parameter", "budgets:budget",
                "events:api-destination", "events:connection", "config:config-rule",
                "ecs:task-definition", "glue:database", "glue:table",
                "backup:backup-vault", "cloudformation:stackset",
                "apprunner:autoscalingconfiguration", "ec2:vpc-flow-log",
                "wafv2:webacl", "cognito-idp:userpool", "sagemaker:endpoint" }) {
            STATIC_STATE.put(alwaysActive, "Active");
        }
        for (String alwaysAvailable : new String[] {
                "ec2:route-table", "ec2:network-acl", "ec2:dhcp-options", "ec2:key-pair",
                "ec2:launch-template", "ec2:transit-gateway",
                "elasticloadbalancing:targetgroup",
                "elasticloadbalancing:listener/app", "elasticloadbalancing:listener/net",
                "elasticloadbalancing:listener-rule/app",
                "elasticloadbalancing:listener-rule/net",
                "memorydb:parametergroup", "memorydb:acl", "memorydb:user",
                "elasticache:user", "rds:subgrp", "rds:pg", "rds:og", "rds:secgrp",
                "athena:datacatalog", "codecommit:repository",
                "elasticfilesystem:access-point", "kms:key" }) {
            STATIC_STATE.put(alwaysAvailable, "Available");
        }
    }

    private AwsResourceTypeMapper() {
    }

    /**
     * The state to show for a type that carries no live state, or null when the type needs
     * a real lookup.
     *
     * <p>
     * Null is meaningful: it tells the reconciler this type is worth spending a describe
     * call on, and tells the UI to show "Unknown" rather than something invented.
     */
    public static String staticStateFor(String nativeType) {
        return nativeType == null ? null : STATIC_STATE.get(nativeType);
    }

    private static void map(String nativeType, String displayType) {
        TYPE_TO_DISPLAY.put(nativeType, displayType);
    }

    /** True when this type should not appear in the inventory at all. */
    public static boolean isSuppressed(String nativeType) {
        return nativeType != null && SUPPRESSED.containsKey(nativeType);
    }

    /**
     * The UI-facing group label for a Resource Explorer type token.
     *
     * <p>
     * Falls back to a humanised form of the token rather than null or "Other", so an
     * unmapped type is visible and obviously unmapped instead of invisible.
     */
    public static String displayTypeFor(String nativeType) {
        if (nativeType == null || nativeType.isBlank()) {
            return "Unknown";
        }
        String mapped = TYPE_TO_DISPLAY.get(nativeType);
        if (mapped != null) {
            return mapped;
        }
        return humanise(nativeType);
    }

    /**
     * Turns {@code "someservice:widget-thing"} into {@code "Someservice Widget Thing"}.
     * Only reached for types AWS added after this mapping was written.
     */
    private static String humanise(String nativeType) {
        String[] parts = nativeType.split(":", 2);
        String service = parts[0];
        String type = parts.length > 1 ? parts[1] : "";

        StringBuilder sb = new StringBuilder(titleCase(service));
        if (!type.isBlank()) {
            for (String word : type.split("[-_/]")) {
                if (!word.isBlank()) {
                    sb.append(' ').append(titleCase(word));
                }
            }
        }
        return sb.toString();
    }

    private static String titleCase(String word) {
        if (word.isBlank()) {
            return word;
        }
        // Preserve tokens that are already meaningfully cased or acronyms (EC2, RDS, S3).
        if (word.length() <= 3 || !word.equals(word.toLowerCase())) {
            return word.toUpperCase().equals(word) ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /**
     * Best-effort short id from an ARN, matching what {@code ResourceDto.id} carries
     * today so existing frontend deep links keep working.
     *
     * <p>
     * ARN tails come in three shapes — {@code .../i-abc}, {@code .../type/i-abc} and
     * {@code :type:i-abc} — plus S3's bare {@code arn:aws:s3:::bucket}. Anything
     * unrecognised returns the full ARN, which is still a usable unique id.
     */
    public static String resourceIdFromArn(String arn) {
        if (arn == null || arn.isBlank()) {
            return arn;
        }
        int lastSlash = arn.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < arn.length() - 1) {
            return arn.substring(lastSlash + 1);
        }
        // No slash, so the resource part is colon-delimited. In that form AWS writes it
        // as "type:id" (arn:aws:rds:...:db:my-database, arn:aws:lambda:...:function:my-fn),
        // so the last segment is the identifier. Taking the whole tail instead would
        // yield "db:my-database", which does not match the dbInstanceIdentifier that
        // CloudListService puts in ResourceDto.id today — and every frontend deep link
        // built from that id would break.
        int lastColon = arn.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < arn.length() - 1) {
            return arn.substring(lastColon + 1);
        }
        return arn;
    }
}
