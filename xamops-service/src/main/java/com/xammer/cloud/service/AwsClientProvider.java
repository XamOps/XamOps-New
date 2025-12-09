package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.amplify.AmplifyClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codecommit.CodeCommitClient;
import software.amazon.awssdk.services.codepipeline.CodePipelineClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.controltower.ControlTowerClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticbeanstalk.ElasticBeanstalkClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.shield.ShieldClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.wafv2.Wafv2Client;

@Service
public class AwsClientProvider {

    private static final Logger logger = LoggerFactory.getLogger(AwsClientProvider.class);

    private final StsClient stsClient;

    public AwsClientProvider(StsClient stsClient) {
        this.stsClient = stsClient;
        logger.info("AwsClientProvider initialized");
    }

    public AwsCredentialsProvider getCredentialsProvider(CloudAccount account) {
        String roleArn = account.getRoleArn();
        String externalId = account.getExternalId();
        String roleSessionName = "xamops-session-" + account.getAwsAccountId();

        logger.debug("Creating credentials provider for account {} with roleArn={}",
                account.getAwsAccountId(), roleArn);

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(req -> req
                        .roleArn(roleArn)
                        .externalId(externalId)
                        .roleSessionName(roleSessionName))
                .build();
    }

    private <BuilderT extends AwsClientBuilder<BuilderT, ClientT>, ClientT> ClientT getClient(
            Class<ClientT> clientClass, BuilderT builder, CloudAccount account, String region) {
        logger.debug("Creating {} for account {} in region {}",
                clientClass.getSimpleName(), account.getAwsAccountId(), region);
        return builder
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    // ================= AutoSpotting-specific clients =================

    /**
     * Creates an AutoScalingClient for the customer account using the AutoSpotting
     * cross-account role.
     * This is used to list, enable, and disable AutoSpotting on ASGs in the
     * customer account.
     */
    public AutoScalingClient getAutoSpottingAsgClient(String customerAccountId, String region) {
        logger.info("Creating AutoSpotting ASG client for customerAccountId={} region={}",
                customerAccountId, region);

        String roleArn = String.format("arn:aws:iam::%s:role/AutoSpotting-Execution-Role", customerAccountId);
        logger.debug("Assuming role: {}", roleArn);

        try {
            AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName("XamOps-AutoSpotting-Action")
                    .externalId(customerAccountId)
                    .build();

            AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);
            logger.info("Successfully assumed AutoSpotting role for account {}", customerAccountId);

            return AutoScalingClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsSessionCredentials.create(
                                    roleResponse.credentials().accessKeyId(),
                                    roleResponse.credentials().secretAccessKey(),
                                    roleResponse.credentials().sessionToken())))
                    .build();
        } catch (Exception e) {
            logger.error("Failed to assume AutoSpotting role for account {} in region {}: {}",
                    customerAccountId, region, e.getMessage(), e);
            throw new RuntimeException("Failed to create AutoSpotting ASG client: " + e.getMessage(), e);
        }
    }

    public DynamoDbClient getHostDynamoDbClient() {
        String hostRoleArn = "arn:aws:iam::982534352845:role/XamOps-AutoSpotting-HostRole";

        logger.info("Creating HOST DynamoDB client in region ap-south-1 using role {}", hostRoleArn);

        try {
            // Assume host role
            AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                    .roleArn(hostRoleArn)
                    .roleSessionName("XamOps-AutoSpotting-HostDynamo")
                    .build();

            AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);
            logger.info("Successfully assumed host role {} for DynamoDB", hostRoleArn);

            AwsSessionCredentials tempCreds = AwsSessionCredentials.create(
                    roleResponse.credentials().accessKeyId(),
                    roleResponse.credentials().secretAccessKey(),
                    roleResponse.credentials().sessionToken());

            DynamoDbClient client = DynamoDbClient.builder()
                    .region(Region.AP_SOUTH_1)
                    .credentialsProvider(StaticCredentialsProvider.create(tempCreds))
                    .build();

            logger.info("Successfully created HOST DynamoDB client in ap-south-1");
            return client;
        } catch (Exception e) {
            logger.error("Failed to create HOST DynamoDB client via role {}: {}", hostRoleArn, e.getMessage(), e);
            throw new RuntimeException("Failed to create HOST DynamoDB client: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a CloudWatch client for the HOST/main account to read AutoSpotting
     * metrics.
     */
    public CloudWatchClient getHostCloudWatchClient(String region) {
        logger.info("Creating HOST CloudWatch client in region {}", region);

        try {
            String hostAccount = getHostAccount();
            logger.info("Host account ID: {} for CloudWatch in region {}", hostAccount, region);

            CloudWatchClient client = CloudWatchClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();

            logger.info("Successfully created HOST CloudWatch client");
            return client;
        } catch (Exception e) {
            logger.error("Failed to create HOST CloudWatch client: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create HOST CloudWatch client: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the AWS Account ID of the host/main account where XamOps runs.
     */
    public String getHostAccount() {
        try {
            String account = stsClient.getCallerIdentity().account();
            logger.debug("Retrieved host account ID: {}", account);
            return account;
        } catch (Exception e) {
            logger.error("Failed to determine Host Account ID: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to determine Host Account ID", e);
        }
    }

    // ================= Standard client methods =================

    public EksClient getEksClientForTokenGeneration(CloudAccount account) {
        return EksClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .build();
    }

    public Ec2Client getEc2Client(CloudAccount account, String region) {
        return getClient(Ec2Client.class, Ec2Client.builder(), account, region);
    }

    public IamClient getIamClient(CloudAccount account) {
        return getClient(IamClient.class, IamClient.builder(), account, "aws-global");
    }

    public EksClient getEksClient(CloudAccount account, String region) {
        return getClient(EksClient.class, EksClient.builder(), account, region);
    }

    public CostExplorerClient getCostExplorerClient(CloudAccount account) {
        return getClient(CostExplorerClient.class, CostExplorerClient.builder(), account, "us-east-1");
    }

    public ComputeOptimizerClient getComputeOptimizerClient(CloudAccount account, String region) {
        return getClient(ComputeOptimizerClient.class, ComputeOptimizerClient.builder(), account, region);
    }

    public CloudWatchClient getCloudWatchClient(CloudAccount account, String region) {
        return getClient(CloudWatchClient.class, CloudWatchClient.builder(), account, region);
    }

    public ServiceQuotasClient getServiceQuotasClient(CloudAccount account, String region) {
        return getClient(ServiceQuotasClient.class, ServiceQuotasClient.builder(), account, region);
    }

    public BudgetsClient getBudgetsClient(CloudAccount account) {
        return getClient(BudgetsClient.class, BudgetsClient.builder(), account, "us-east-1");
    }

    public EcsClient getEcsClient(CloudAccount account, String region) {
        return getClient(EcsClient.class, EcsClient.builder(), account, region);
    }

    public LambdaClient getLambdaClient(CloudAccount account, String region) {
        return getClient(LambdaClient.class, LambdaClient.builder(), account, region);
    }

    public RdsClient getRdsClient(CloudAccount account, String region) {
        return getClient(RdsClient.class, RdsClient.builder(), account, region);
    }

    public S3Client getS3Client(CloudAccount account, String region) {
        return getClient(S3Client.class, S3Client.builder(), account, region);
    }

    public ElasticLoadBalancingV2Client getElbv2Client(CloudAccount account, String region) {
        return getClient(ElasticLoadBalancingV2Client.class, ElasticLoadBalancingV2Client.builder(), account, region);
    }

    public AutoScalingClient getAutoScalingClient(CloudAccount account, String region) {
        return getClient(AutoScalingClient.class, AutoScalingClient.builder(), account, region);
    }

    public ElastiCacheClient getElastiCacheClient(CloudAccount account, String region) {
        return getClient(ElastiCacheClient.class, ElastiCacheClient.builder(), account, region);
    }

    public DynamoDbClient getDynamoDbClient(CloudAccount account, String region) {
        return getClient(DynamoDbClient.class, DynamoDbClient.builder(), account, region);
    }

    public EcrClient getEcrClient(CloudAccount account, String region) {
        return getClient(EcrClient.class, EcrClient.builder(), account, region);
    }

    public Route53Client getRoute53Client(CloudAccount account) {
        return getClient(Route53Client.class, Route53Client.builder(), account, "aws-global");
    }

    public CloudTrailClient getCloudTrailClient(CloudAccount account, String region) {
        return getClient(CloudTrailClient.class, CloudTrailClient.builder(), account, region);
    }

    public AcmClient getAcmClient(CloudAccount account, String region) {
        return getClient(AcmClient.class, AcmClient.builder(), account, region);
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(CloudAccount account, String region) {
        return getClient(CloudWatchLogsClient.class, CloudWatchLogsClient.builder(), account, region);
    }

    public SnsClient getSnsClient(CloudAccount account, String region) {
        return getClient(SnsClient.class, SnsClient.builder(), account, region);
    }

    public SqsClient getSqsClient(CloudAccount account, String region) {
        return getClient(SqsClient.class, SqsClient.builder(), account, region);
    }

    public PricingClient getPricingClient() {
        return PricingClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    public StsClient getStsClient(CloudAccount account, String region) {
        return getClient(StsClient.class, StsClient.builder(), account, region);
    }

    public KmsClient getKmsClient(CloudAccount account, String region) {
        return getClient(KmsClient.class, KmsClient.builder(), account, region);
    }

    public LightsailClient getLightsailClient(CloudAccount account, String region) {
        return getClient(LightsailClient.class, LightsailClient.builder(), account, region);
    }

    public AmplifyClient getAmplifyClient(CloudAccount account, String region) {
        return getClient(AmplifyClient.class, AmplifyClient.builder(), account, region);
    }

    public ConfigClient getConfigClient(CloudAccount account, String region) {
        return getClient(ConfigClient.class, ConfigClient.builder(), account, region);
    }

    public SecurityHubClient getSecurityHubClient(CloudAccount account, String region) {
        return getClient(SecurityHubClient.class, SecurityHubClient.builder(), account, region);
    }

    public GlueClient getGlueClient(CloudAccount account, String region) {
        return getClient(GlueClient.class, GlueClient.builder(), account, region);
    }

    public AthenaClient getAthenaClient(CloudAccount account, String region) {
        return getClient(AthenaClient.class, AthenaClient.builder(), account, region);
    }

    public CognitoIdentityProviderClient getCognitoIdentityProviderClient(CloudAccount account, String region) {
        return getClient(CognitoIdentityProviderClient.class, CognitoIdentityProviderClient.builder(), account, region);
    }

    public Wafv2Client getWafv2Client(CloudAccount account, String region) {
        return getClient(Wafv2Client.class, Wafv2Client.builder(), account, region);
    }

    public CloudFrontClient getCloudFrontClient(CloudAccount account) {
        return getClient(CloudFrontClient.class, CloudFrontClient.builder(), account, "aws-global");
    }

    public BedrockClient getBedrockClient(CloudAccount account, String region) {
        return getClient(BedrockClient.class, BedrockClient.builder(), account, region);
    }

    public SageMakerClient getSageMakerClient(CloudAccount account, String region) {
        return getClient(SageMakerClient.class, SageMakerClient.builder(), account, region);
    }

    public EfsClient getEfsClient(CloudAccount account, String region) {
        return getClient(EfsClient.class, EfsClient.builder(), account, region);
    }

    public SsmClient getSsmClient(CloudAccount account, String region) {
        return getClient(SsmClient.class, SsmClient.builder(), account, region);
    }

    public SfnClient getSfnClient(CloudAccount account, String region) {
        return getClient(SfnClient.class, SfnClient.builder(), account, region);
    }

    public PinpointClient getPinpointClient(CloudAccount account, String region) {
        return getClient(PinpointClient.class, PinpointClient.builder(), account, region);
    }

    public DataZoneClient getDataZoneClient(CloudAccount account, String region) {
        return getClient(DataZoneClient.class, DataZoneClient.builder(), account, region);
    }

    public TextractClient getTextractClient(CloudAccount account, String region) {
        return getClient(TextractClient.class, TextractClient.builder(), account, region);
    }

    public EventBridgeClient getEventBridgeClient(CloudAccount account, String region) {
        return getClient(EventBridgeClient.class, EventBridgeClient.builder(), account, region);
    }

    public KinesisClient getKinesisClient(CloudAccount account, String region) {
        return getClient(KinesisClient.class, KinesisClient.builder(), account, region);
    }

    public CodePipelineClient getCodePipelineClient(CloudAccount account, String region) {
        return getClient(CodePipelineClient.class, CodePipelineClient.builder(), account, region);
    }

    public CodeBuildClient getCodeBuildClient(CloudAccount account, String region) {
        return getClient(CodeBuildClient.class, CodeBuildClient.builder(), account, region);
    }

    public CodeCommitClient getCodeCommitClient(CloudAccount account, String region) {
        return getClient(CodeCommitClient.class, CodeCommitClient.builder(), account, region);
    }

    public ShieldClient getShieldClient(CloudAccount account) {
        return getClient(ShieldClient.class, ShieldClient.builder(), account, "us-east-1");
    }

    public OrganizationsClient getOrganizationsClient(CloudAccount account) {
        return getClient(OrganizationsClient.class, OrganizationsClient.builder(), account, "us-east-1");
    }

    public ControlTowerClient getControlTowerClient(CloudAccount account, String region) {
        return getClient(ControlTowerClient.class, ControlTowerClient.builder(), account, region);
    }

    public ElasticBeanstalkClient getElasticBeanstalkClient(CloudAccount account, String region) {
        return getClient(ElasticBeanstalkClient.class, ElasticBeanstalkClient.builder(), account, region);
    }

    public ApiGatewayClient getApiGatewayClient(CloudAccount account, String region) {
        return getClient(ApiGatewayClient.class, ApiGatewayClient.builder(), account, region);
    }
}
