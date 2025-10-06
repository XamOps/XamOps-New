package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.amplify.AmplifyClient;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.config.ConfigClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.servicequotas.ServiceQuotasClient;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.wafv2.Wafv2Client;

import java.util.UUID;

@Service
public class AwsClientProvider {

    private final StsClient stsClient;

    public AwsClientProvider(StsClient stsClient) {
        this.stsClient = stsClient;
    }

    public AwsCredentialsProvider getCredentialsProvider(CloudAccount account) {
        String roleArn = account.getRoleArn();
        String externalId = account.getExternalId();
        String roleSessionName = "xamops-session-" + account.getAwsAccountId();

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(req -> req
                        .roleArn(roleArn)
                        .externalId(externalId)
                        .roleSessionName(roleSessionName)
                )
                .build();
    }

    public EksClient getEksClientForTokenGeneration(CloudAccount account) {
        // This client uses the application's host credentials to generate a token
        // on behalf of the user's role.
        return EksClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
                .build();
    }

    public Ec2Client getEc2Client(CloudAccount account, String region) {
        return Ec2Client.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public IamClient getIamClient(CloudAccount account) {
        return IamClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.AWS_GLOBAL)
                .build();
    }

    public EksClient getEksClient(CloudAccount account, String region) {
        return EksClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public CostExplorerClient getCostExplorerClient(CloudAccount account) {
        return CostExplorerClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.US_EAST_1)
                .build();
    }

    /**
     * NEW: Provides a client for AWS Compute Optimizer.
     * This client is used to fetch rightsizing recommendations.
     *
     * @param account The cloud account to get a client for.
     * @param region  The AWS region to target.
     * @return A configured ComputeOptimizerClient.
     */
    public ComputeOptimizerClient getComputeOptimizerClient(CloudAccount account, String region) {
        return ComputeOptimizerClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public CloudWatchClient getCloudWatchClient(CloudAccount account, String region) {
        return CloudWatchClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public ServiceQuotasClient getServiceQuotasClient(CloudAccount account, String region) {
        return ServiceQuotasClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public BudgetsClient getBudgetsClient(CloudAccount account) {
        return BudgetsClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.US_EAST_1)
                .build();
    }

    public EcsClient getEcsClient(CloudAccount account, String region) {
        return EcsClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public LambdaClient getLambdaClient(CloudAccount account, String region) {
        return LambdaClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public RdsClient getRdsClient(CloudAccount account, String region) {
        return RdsClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public S3Client getS3Client(CloudAccount account, String region) {
        return S3Client.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public ElasticLoadBalancingV2Client getElbv2Client(CloudAccount account, String region) {
        return ElasticLoadBalancingV2Client.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public AutoScalingClient getAutoScalingClient(CloudAccount account, String region) {
        return AutoScalingClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public ElastiCacheClient getElastiCacheClient(CloudAccount account, String region) {
        return ElastiCacheClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public DynamoDbClient getDynamoDbClient(CloudAccount account, String region) {
        return DynamoDbClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public EcrClient getEcrClient(CloudAccount account, String region) {
        return EcrClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public Route53Client getRoute53Client(CloudAccount account) {
        return Route53Client.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.AWS_GLOBAL).build();
    }

    public CloudTrailClient getCloudTrailClient(CloudAccount account, String region) {
        return CloudTrailClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public AcmClient getAcmClient(CloudAccount account, String region) {
        return AcmClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public CloudWatchLogsClient getCloudWatchLogsClient(CloudAccount account, String region) {
        return CloudWatchLogsClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public SnsClient getSnsClient(CloudAccount account, String region) {
        return SnsClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public SqsClient getSqsClient(CloudAccount account, String region) {
        return SqsClient.builder().credentialsProvider(getCredentialsProvider(account)).region(Region.of(region)).build();
    }

    public PricingClient getPricingClient() {
        return PricingClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create()) // Use default credentials for Pricing service
                .region(Region.US_EAST_1) // Pricing service is a global service accessible via us-east-1
                .build();
    }



    public StsClient getStsClient(CloudAccount account, String region) {
        return StsClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public KmsClient getKmsClient(CloudAccount account, String region) {
        return KmsClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(software.amazon.awssdk.regions.Region.of(region))
                .build();
    }

    public LightsailClient getLightsailClient(CloudAccount account, String region) {
        return LightsailClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public AmplifyClient getAmplifyClient(CloudAccount account, String region) {
        return AmplifyClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public ConfigClient getConfigClient(CloudAccount account, String region) {
        return ConfigClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public SecurityHubClient getSecurityHubClient(CloudAccount account, String region) {
        return SecurityHubClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public GlueClient getGlueClient(CloudAccount account, String region) {
        return GlueClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public AthenaClient getAthenaClient(CloudAccount account, String region) {
        return AthenaClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public CognitoIdentityProviderClient getCognitoIdentityProviderClient(CloudAccount account, String region) {
        return CognitoIdentityProviderClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public Wafv2Client getWafv2Client(CloudAccount account, String region) {
        return Wafv2Client.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public CloudFrontClient getCloudFrontClient(CloudAccount account) {
        return CloudFrontClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.AWS_GLOBAL) // CloudFront is a global service
                .build();
    }

    public BedrockClient getBedrockClient(CloudAccount account, String region) {
        return BedrockClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public SageMakerClient getSageMakerClient(CloudAccount account, String region) {
        return SageMakerClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }


    public EfsClient getEfsClient(CloudAccount account, String region) {
        return EfsClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }

    public SsmClient getSsmClient(CloudAccount account, String region) {
        return SsmClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }
    public SfnClient getSfnClient(CloudAccount account, String region) {
        return SfnClient.builder()
                .credentialsProvider(getCredentialsProvider(account))
                .region(Region.of(region))
                .build();
    }
}