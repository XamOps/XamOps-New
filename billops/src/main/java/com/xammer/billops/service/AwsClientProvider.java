package com.xammer.billops.service;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.permissions.AwsDenialClassifier;
import com.xammer.cloud.permissions.AwsPermissionCatalog;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import com.xammer.cloud.permissions.PermissionGapRegistry;
import com.xammer.cloud.permissions.PermissionGaps;
import com.xammer.cloud.permissions.XamOpsExecutionAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import jakarta.annotation.PostConstruct;

@Service
public class AwsClientProvider {

    private static final Logger logger = LoggerFactory.getLogger(AwsClientProvider.class);

    private final AwsPermissionInterceptor permissionInterceptor;
    private final PermissionGapRegistry permissionGapRegistry;

    public AwsClientProvider(AwsPermissionInterceptor permissionInterceptor,
            PermissionGapRegistry permissionGapRegistry) {
        this.permissionInterceptor = permissionInterceptor;
        this.permissionGapRegistry = permissionGapRegistry;
    }

    @PostConstruct
    public void init() {
        logger.info("✅ AwsClientProvider initialized with permission tracking");
    }

    @SuppressWarnings("unchecked")
    private <T> T getClient(CloudAccount account, String region, Class<T> clientClass) {
        StsClient stsClient = StsClient.builder().region(Region.of("us-east-1")).build();

        AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                .roleArn(account.getRoleArn())
                .roleSessionName("xamops-session-" + System.currentTimeMillis())
                .externalId(account.getExternalId())
                .build();

        AssumeRoleResponse assumeRoleResponse;
        try {
            assumeRoleResponse = stsClient.assumeRole(assumeRoleRequest);
        } catch (RuntimeException e) {
            // Unlike XamOps, credentials here are resolved eagerly — a deleted role or a
            // trust policy that no longer trusts us fails before any client exists, so
            // the SDK interceptor never sees it. Record it here or BillOps just shows
            // an empty invoice with no reason given.
            recordAssumeRoleFailure(account, e);
            throw e;
        }
        Credentials credentials = assumeRoleResponse.credentials();

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                        credentials.accessKeyId(),
                        credentials.secretAccessKey(),
                        credentials.sessionToken()));

        if (clientClass.equals(CostExplorerClient.class)) {
            return (T) CostExplorerClient.builder().region(Region.of(region)).credentialsProvider(credentialsProvider)
                    .overrideConfiguration(trackingConfig(account, "CostExplorerClient", region))
                    .build();
        }
        if (clientClass.equals(Ec2Client.class)) {
            return (T) Ec2Client.builder().region(Region.of(region)).credentialsProvider(credentialsProvider)
                    .overrideConfiguration(trackingConfig(account, "Ec2Client", region)).build();
        }
        if (clientClass.equals(ElasticLoadBalancingV2Client.class)) {
            return (T) ElasticLoadBalancingV2Client.builder().region(Region.of(region))
                    .credentialsProvider(credentialsProvider)
                    .overrideConfiguration(trackingConfig(account, "ElasticLoadBalancingV2Client", region)).build();
        }
        if (clientClass.equals(RdsClient.class)) {
            return (T) RdsClient.builder().region(Region.of(region)).credentialsProvider(credentialsProvider)
                    .overrideConfiguration(trackingConfig(account, "RdsClient", region)).build();
        }
        if (clientClass.equals(S3Client.class)) {
            return (T) S3Client.builder().credentialsProvider(credentialsProvider)
                    .overrideConfiguration(trackingConfig(account, "S3Client", region)).build();
        }
        if (clientClass.equals(LightsailClient.class)) {
            return (T) LightsailClient.builder().region(Region.of(region)).credentialsProvider(credentialsProvider)
                    .overrideConfiguration(trackingConfig(account, "LightsailClient", region)).build();
        }

        throw new IllegalArgumentException("Unsupported AWS client type: " + clientClass.getName());
    }

    /**
     * Stamps the account onto the client and attaches the shared permission
     * interceptor, so a denial recorded here shows up in the same banner XamOps uses.
     */
    private ClientOverrideConfiguration trackingConfig(CloudAccount account, String clientSimpleName, String region) {
        ClientOverrideConfiguration.Builder builder = ClientOverrideConfiguration.builder();
        String accountId = account != null ? account.getAwsAccountId() : null;
        if (accountId == null) {
            return builder.build();
        }
        return builder
                .putExecutionAttribute(XamOpsExecutionAttributes.ACCOUNT_ID, accountId)
                .putExecutionAttribute(XamOpsExecutionAttributes.IAM_SERVICE_PREFIX,
                        AwsPermissionCatalog.iamPrefixForClient(clientSimpleName, null))
                .putExecutionAttribute(XamOpsExecutionAttributes.REGION, region)
                .addExecutionInterceptor(permissionInterceptor)
                .build();
    }

    private void recordAssumeRoleFailure(CloudAccount account, RuntimeException failure) {
        try {
            if (account == null || account.getAwsAccountId() == null) {
                return;
            }
            AwsDenialClassifier.Result denial = AwsDenialClassifier.classify(failure);
            if (denial == null) {
                return; // A timeout or throttle, not a permissions problem.
            }
            permissionGapRegistry.record(PermissionGaps.roleUnreachable(
                    account.getAwsAccountId(), denial.getErrorCode(), denial.getMessage()));
        } catch (Exception e) {
            logger.debug("Could not record assume-role failure for permission tracking: {}", e.getMessage());
        }
    }

    public CostExplorerClient getCostExplorerClient(CloudAccount account) {
        return getClient(account, "us-east-1", CostExplorerClient.class);
    }

    public Ec2Client getEc2Client(CloudAccount account, String region) {
        return getClient(account, region, Ec2Client.class);
    }

    public ElasticLoadBalancingV2Client getElbClient(CloudAccount account, String region) {
        return getClient(account, region, ElasticLoadBalancingV2Client.class);
    }

    public RdsClient getRdsClient(CloudAccount account, String region) {
        return getClient(account, region, RdsClient.class);
    }

    public S3Client getS3Client(CloudAccount account) {
        return getClient(account, "us-east-1", S3Client.class);
    }

    public LightsailClient getLightsailClient(CloudAccount account, String region) {
        return getClient(account, region, LightsailClient.class);
    }
}