package com.xammer.cloud.permissions;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.xammer.cloud.permissions.XamOpsFeature.*;

/**
 * Static knowledge about AWS: how an SDK client maps to an IAM service prefix,
 * and which XamOps features stop working when that service is denied.
 *
 * Kept as a plain lookup table rather than something derived at runtime because
 * the mapping is not mechanical — DocumentDB and Neptune are authorised under
 * {@code rds:}, OpenSearch under {@code es:}, Step Functions under {@code states:}.
 * Getting the prefix wrong would put a wrong action name in front of the customer,
 * which is worse than saying nothing.
 */
public final class AwsPermissionCatalog {

    private AwsPermissionCatalog() {
    }

    /** SDK client class simple name -> IAM service prefix used in policy actions. */
    private static final Map<String, String> CLIENT_TO_IAM_PREFIX = new HashMap<>();

    /** IAM service prefix -> label to show a human. */
    private static final Map<String, String> PREFIX_LABELS = new HashMap<>();

    /** IAM service prefix -> XamOps features that go blank without it. */
    private static final Map<String, Set<XamOpsFeature>> PREFIX_FEATURES = new HashMap<>();

    static {
        // ---- client class -> IAM prefix -------------------------------------
        map("AcmClient", "acm", "Certificate Manager");
        map("AmplifyClient", "amplify", "Amplify");
        map("ApiGatewayClient", "apigateway", "API Gateway");
        map("AthenaClient", "athena", "Athena");
        map("AutoScalingClient", "autoscaling", "EC2 Auto Scaling");
        map("BedrockClient", "bedrock", "Bedrock");
        map("BudgetsClient", "budgets", "Budgets");
        map("CloudFormationClient", "cloudformation", "CloudFormation");
        map("CloudFrontClient", "cloudfront", "CloudFront");
        map("CloudTrailClient", "cloudtrail", "CloudTrail");
        map("CloudWatchClient", "cloudwatch", "CloudWatch");
        map("CloudWatchLogsClient", "logs", "CloudWatch Logs");
        map("CodeBuildClient", "codebuild", "CodeBuild");
        map("CodeCommitClient", "codecommit", "CodeCommit");
        map("CodePipelineClient", "codepipeline", "CodePipeline");
        map("CognitoIdentityProviderClient", "cognito-idp", "Cognito");
        map("ComputeOptimizerClient", "compute-optimizer", "Compute Optimizer");
        map("ConfigClient", "config", "AWS Config");
        map("ControlTowerClient", "controltower", "Control Tower");
        map("CostExplorerClient", "ce", "Cost Explorer");
        map("DataZoneClient", "datazone", "DataZone");
        map("DocDbClient", "rds", "DocumentDB");
        map("DynamoDbClient", "dynamodb", "DynamoDB");
        map("Ec2Client", "ec2", "EC2");
        map("EcrClient", "ecr", "ECR");
        map("EcsClient", "ecs", "ECS");
        map("EfsClient", "elasticfilesystem", "EFS");
        map("EksClient", "eks", "EKS");
        map("ElastiCacheClient", "elasticache", "ElastiCache");
        map("ElasticBeanstalkClient", "elasticbeanstalk", "Elastic Beanstalk");
        map("ElasticLoadBalancingV2Client", "elasticloadbalancing", "Load Balancing");
        map("EventBridgeClient", "events", "EventBridge");
        map("GlueClient", "glue", "Glue");
        map("IamClient", "iam", "IAM");
        map("KafkaClient", "kafka", "MSK");
        map("KinesisClient", "kinesis", "Kinesis");
        map("KmsClient", "kms", "KMS");
        map("LambdaClient", "lambda", "Lambda");
        map("LightsailClient", "lightsail", "Lightsail");
        map("MemoryDbClient", "memorydb", "MemoryDB");
        map("NeptuneClient", "rds", "Neptune");
        map("OpenSearchClient", "es", "OpenSearch");
        map("OrganizationsClient", "organizations", "Organizations");
        map("PinpointClient", "mobiletargeting", "Pinpoint");
        map("PricingClient", "pricing", "Pricing");
        map("RdsClient", "rds", "RDS");
        map("RedshiftClient", "redshift", "Redshift");
        map("ResourceExplorer2Client", "resource-explorer-2", "Resource Explorer");
        map("Route53Client", "route53", "Route 53");
        map("S3Client", "s3", "S3");
        map("SageMakerClient", "sagemaker", "SageMaker");
        map("SavingsplansClient", "savingsplans", "Savings Plans");
        map("SecretsManagerClient", "secretsmanager", "Secrets Manager");
        map("SecurityHubClient", "securityhub", "Security Hub");
        map("ServiceQuotasClient", "servicequotas", "Service Quotas");
        map("SfnClient", "states", "Step Functions");
        map("ShieldClient", "shield", "Shield");
        map("SnsClient", "sns", "SNS");
        map("SqsClient", "sqs", "SQS");
        map("SsmClient", "ssm", "Systems Manager");
        map("StsClient", "sts", "STS");
        map("TextractClient", "textract", "Textract");
        map("Wafv2Client", "wafv2", "WAF");

        // ---- IAM prefix -> impacted features --------------------------------
        features("ce", FINOPS, COST, WASTE, RESERVATIONS, DASHBOARD, BILLOPS);
        features("budgets", FINOPS, ALERTS);
        features("savingsplans", RESERVATIONS, FINOPS);
        features("pricing", RIGHTSIZING, EBS_RIGHTSIZING, FINOPS);
        features("compute-optimizer", RIGHTSIZING, EBS_RIGHTSIZING, WASTE);
        features("ec2", CLOUD_LIST, DASHBOARD, WASTE, RIGHTSIZING, EBS_RIGHTSIZING,
                CLOUD_MAP, CLOUDSITTER, SPOT_AUTOMATION, PERFORMANCE);
        features("autoscaling", SPOT_AUTOMATION, CLOUD_LIST, CLOUDSITTER);
        // KUBERNETES belongs here because EksService.fetchNodeNames discovers node names
        // from cloudwatch:ListMetrics. Without it a query scoped to KUBERNETES drops the
        // cloudwatch denial and the CloudK8s page reports no gap while showing no nodes.
        features("cloudwatch", PERFORMANCE, ALERTS, RIGHTSIZING, WASTE, DASHBOARD, DBOPS, OBSERVABILITY,
                KUBERNETES);
        features("logs", OBSERVABILITY, ALERTS, SRE);
        features("rds", CLOUD_LIST, DBOPS, CLOUDSITTER, WASTE);
        features("dynamodb", CLOUD_LIST, DBOPS);
        features("elasticache", CLOUD_LIST, DBOPS);
        features("redshift", CLOUD_LIST, DBOPS, DATAOPS);
        features("memorydb", CLOUD_LIST, DBOPS);
        features("es", CLOUD_LIST, DBOPS, OBSERVABILITY);
        features("s3", CLOUD_LIST, SECURITY, WASTE, MEDIAOPS);
        features("lambda", CLOUD_LIST, WASTE, PERFORMANCE);
        features("eks", KUBERNETES, CLOUD_LIST);
        features("ecs", CLOUD_LIST, KUBERNETES);
        features("ecr", CLOUD_LIST, KUBERNETES);
        features("elasticloadbalancing", CLOUD_LIST, CLOUD_MAP, WASTE);
        features("elasticfilesystem", CLOUD_LIST, WASTE);
        features("elasticbeanstalk", CLOUD_LIST);
        features("apigateway", CLOUD_LIST, CLOUD_MAP);
        features("iam", SECURITY, CLOUD_LIST, COMPLIANCE);
        features("securityhub", SECURITY);
        features("config", SECURITY, COMPLIANCE);
        features("cloudtrail", SECURITY, SRE, COMPLIANCE);
        features("kms", SECURITY, CLOUD_LIST);
        features("secretsmanager", SECURITY, CLOUD_LIST);
        features("acm", SECURITY, CLOUD_LIST);
        features("wafv2", SECURITY, CLOUD_LIST);
        features("shield", SECURITY);
        features("cloudfront", CLOUD_LIST, CLOUD_MAP, SECURITY);
        features("route53", CLOUD_MAP, CLOUD_LIST);
        features("organizations", BILLOPS, DASHBOARD, FINOPS);
        features("servicequotas", DASHBOARD, CLOUD_LIST);
        // Resource Explorer backs the shared inventory plane, so losing it degrades
        // every page that reads resource identity — not just the cloud list. Losing it
        // is not fatal (the reconciler falls back to per-service describes) but it is
        // worth naming, because the fallback is ~1000 calls instead of ~5.
        features("resource-explorer-2", CLOUD_LIST, DASHBOARD, CLOUD_MAP, WASTE,
                RIGHTSIZING, SECURITY, PERFORMANCE);
        features("glue", DATAOPS);
        features("athena", DATAOPS, FINOPS);
        features("datazone", DATAOPS);
        features("kinesis", DATAOPS, CLOUD_LIST);
        features("kafka", DATAOPS, CLOUD_LIST);
        features("sagemaker", AIOPS, CLOUD_LIST);
        features("bedrock", AIOPS);
        features("textract", AIOPS);
        features("codepipeline", CICD);
        features("codebuild", CICD);
        features("codecommit", CICD);
        features("ssm", CLOUD_LIST, CICD);
        features("states", CLOUD_LIST);
        features("sns", CLOUD_LIST, ALERTS);
        features("sqs", CLOUD_LIST);
        features("events", CLOUD_LIST, CLOUDSITTER);
        features("cloudformation", CLOUD_LIST, SPOT_AUTOMATION);
        features("lightsail", CLOUD_LIST, BILLOPS);
        features("cognito-idp", CLOUD_LIST, SECURITY);
        features("amplify", CLOUD_LIST);
        features("controltower", COMPLIANCE, SECURITY);
        features("mobiletargeting", CLOUD_LIST);
    }

    private static void map(String clientSimpleName, String iamPrefix, String label) {
        CLIENT_TO_IAM_PREFIX.put(clientSimpleName, iamPrefix);
        // First client to claim a prefix wins the label, so "RDS" is not renamed
        // to "Neptune" just because Neptune was registered later.
        PREFIX_LABELS.putIfAbsent(iamPrefix, label);
    }

    private static void features(String iamPrefix, XamOpsFeature... impacted) {
        PREFIX_FEATURES.put(iamPrefix, Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(impacted))));
    }

    /**
     * IAM prefix for an SDK client class. Falls back to a lowercased service name
     * so an unmapped client still produces something recognisable rather than nothing.
     */
    public static String iamPrefixForClient(String clientSimpleName, String sdkServiceName) {
        String mapped = CLIENT_TO_IAM_PREFIX.get(clientSimpleName);
        if (mapped != null) {
            return mapped;
        }
        if (sdkServiceName != null && !sdkServiceName.isBlank()) {
            return sdkServiceName.replace(" ", "").toLowerCase(Locale.ROOT);
        }
        return "aws";
    }

    public static String labelFor(String iamPrefix) {
        String label = PREFIX_LABELS.get(iamPrefix);
        return label != null ? label : iamPrefix.toUpperCase(Locale.ROOT);
    }

    /**
     * Features blanked out by losing this action. STS is special — losing
     * {@code sts:AssumeRole} means the whole account is unreachable, not one page.
     */
    public static Set<XamOpsFeature> featuresFor(String iamPrefix) {
        if ("sts".equals(iamPrefix)) {
            return Collections.unmodifiableSet(EnumSet.allOf(XamOpsFeature.class));
        }
        Set<XamOpsFeature> impacted = PREFIX_FEATURES.get(iamPrefix);
        return impacted != null ? impacted : Collections.emptySet();
    }

    public static List<String> featureLabels(Set<XamOpsFeature> impacted) {
        Set<String> labels = new LinkedHashSet<>();
        for (XamOpsFeature feature : impacted) {
            labels.add(feature.getLabel());
        }
        return List.copyOf(labels);
    }
}
