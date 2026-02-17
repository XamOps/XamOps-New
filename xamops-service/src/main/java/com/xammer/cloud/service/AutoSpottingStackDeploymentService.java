package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Deploys the autospotting-customer.yaml CloudFormation stack
 * into every available region of a customer account.
 *
 * - First region: CreateIAMRole=true  (creates AutoSpotting-Execution-Role, global)
 * - All others:   CreateIAMRole=false (skips role, stack only creates Lambda+EventBridge)
 */
@Service
public class AutoSpottingStackDeploymentService {

    private static final Logger logger = LoggerFactory.getLogger(AutoSpottingStackDeploymentService.class);

    private final StsClient stsClient;
    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    @Value("${autospotting.customer.stack.name:autospotting-customer}")
    private String stackName;

    @Value("${autospotting.customer.stack.template-url}")
    private String templateUrl;

    @Value("${autospotting.xammer.region:ap-south-1}")
    private String xammerRegion;

    public AutoSpottingStackDeploymentService(StsClient stsClient,
                                               AwsClientProvider awsClientProvider,
                                               CloudAccountRepository cloudAccountRepository) {
        this.stsClient = stsClient;
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Deploys (or updates) the AutoSpotting customer stack in ALL available regions
     * of the given customer account. Safe to call multiple times (idempotent).
     *
     * @param account the customer CloudAccount entity
     * @return list of regions where deployment succeeded
     */
    public List<String> deployToAllRegions(CloudAccount account) {
        logger.info("=== AutoSpotting Stack Multi-Region Deployment ===");
        logger.info("Customer account: {}", account.getAwsAccountId());

        // 1. Get temporary credentials for the customer account via the execution role
        AwsSessionCredentials customerCreds = assumeCustomerRole(account.getAwsAccountId());

        // 2. Discover all opt-in regions in the customer account
        List<String> regions = discoverRegions(account, customerCreds);
        logger.info("Discovered {} regions: {}", regions.size(), regions);

        if (regions.isEmpty()) {
            logger.warn("No regions discovered for account {}", account.getAwsAccountId());
            return regions;
        }

        List<String> succeeded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        boolean firstRegion = true;

        for (String region : regions) {
            try {
                String createIamRole = firstRegion ? "true" : "false";
                deployOrUpdateStack(region, customerCreds, createIamRole);
                succeeded.add(region);
                logger.info("✓ Deployed to {}", region);
                firstRegion = false;
            } catch (Exception e) {
                logger.error("✗ Failed to deploy to {}: {}", region, e.getMessage());
                failed.add(region);
            }
        }

        logger.info("=== Deployment complete: {} succeeded, {} failed ===",
                succeeded.size(), failed.size());
        if (!failed.isEmpty()) {
            logger.warn("Failed regions: {}", failed);
        }
        return succeeded;
    }

    /**
     * Deploys or updates the stack in a single region.
     */
    private void deployOrUpdateStack(String region, AwsSessionCredentials creds, String createIamRole) {
        CloudFormationClient cfn = CloudFormationClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build();

        List<Parameter> params = List.of(
                param("XammerAccountId", stsClient.getCallerIdentity().account()),
                param("XammerRegion", xammerRegion),
                param("EnableAutoSpotting", "true"),
                param("CreateIAMRole", createIamRole)
        );

        List<Capability> capabilities = List.of(Capability.CAPABILITY_NAMED_IAM);

        boolean stackExists = stackExists(cfn, stackName);

        if (stackExists) {
            logger.debug("Updating existing stack in {}", region);
            try {
                cfn.updateStack(UpdateStackRequest.builder()
                        .stackName(stackName)
                        .templateURL(templateUrl)
                        .parameters(params)
                        .capabilities(capabilities)
                        .build());
            } catch (CloudFormationException e) {
                if (e.getMessage().contains("No updates are to be performed")) {
                    logger.debug("Stack in {} already up-to-date, no changes needed", region);
                } else {
                    throw e;
                }
            }
        } else {
            logger.debug("Creating new stack in {}", region);
            cfn.createStack(CreateStackRequest.builder()
                    .stackName(stackName)
                    .templateURL(templateUrl)
                    .parameters(params)
                    .capabilities(capabilities)
                    .onFailure(OnFailure.ROLLBACK)
                    .build());
        }
    }

    /**
     * Deletes the stack from a specific region (e.g. when customer de-registers).
     */
    public void deleteFromRegion(CloudAccount account, String region) {
        logger.info("Deleting AutoSpotting stack from account {} region {}", account.getAwsAccountId(), region);
        AwsSessionCredentials creds = assumeCustomerRole(account.getAwsAccountId());
        CloudFormationClient cfn = CloudFormationClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .build();

        if (stackExists(cfn, stackName)) {
            cfn.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
            logger.info("✓ Stack deletion initiated in {}", region);
        } else {
            logger.info("Stack not found in {} — nothing to delete", region);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private AwsSessionCredentials assumeCustomerRole(String customerAccountId) {
        String roleArn = String.format("arn:aws:iam::%s:role/AutoSpotting-Execution-Role", customerAccountId);
        try {
            AssumeRoleResponse resp = stsClient.assumeRole(AssumeRoleRequest.builder()
                    .roleArn(roleArn)
                    .roleSessionName("XamOps-MultiRegionDeploy")
                    .build());
            return AwsSessionCredentials.create(
                    resp.credentials().accessKeyId(),
                    resp.credentials().secretAccessKey(),
                    resp.credentials().sessionToken());
        } catch (Exception e) {
            // Fallback: use the general cross-account role
            logger.warn("AutoSpotting-Execution-Role not available ({}), trying general XamOps role", e.getMessage());
            CloudAccount account = cloudAccountRepository
                    .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(customerAccountId, customerAccountId, customerAccountId)
                    .orElseThrow(() -> new RuntimeException("Account not found: " + customerAccountId));
            AssumeRoleResponse resp = stsClient.assumeRole(AssumeRoleRequest.builder()
                    .roleArn(account.getRoleArn())
                    .externalId(account.getExternalId())
                    .roleSessionName("XamOps-MultiRegionDeploy")
                    .build());
            return AwsSessionCredentials.create(
                    resp.credentials().accessKeyId(),
                    resp.credentials().secretAccessKey(),
                    resp.credentials().sessionToken());
        }
    }

    private List<String> discoverRegions(CloudAccount account, AwsSessionCredentials creds) {
        try {
            Ec2Client ec2 = Ec2Client.builder()
                    .region(Region.AP_SOUTH_1)
                    .credentialsProvider(StaticCredentialsProvider.create(creds))
                    .build();
            return ec2.describeRegions().regions().stream()
                    .filter(r -> r.optInStatus() == null || !r.optInStatus().equals("not-opted-in"))
                    .map(r -> r.regionName())
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Could not discover regions dynamically ({}), using defaults", e.getMessage());
            return List.of("ap-south-1", "us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1");
        }
    }

    private boolean stackExists(CloudFormationClient cfn, String name) {
        try {
            List<StackStatus> activeStatuses = List.of(
                    StackStatus.CREATE_COMPLETE,
                    StackStatus.UPDATE_COMPLETE,
                    StackStatus.UPDATE_ROLLBACK_COMPLETE,
                    StackStatus.CREATE_IN_PROGRESS,
                    StackStatus.UPDATE_IN_PROGRESS
            );
            return cfn.describeStacks(DescribeStacksRequest.builder().stackName(name).build())
                    .stacks().stream()
                    .anyMatch(s -> activeStatuses.contains(s.stackStatus()));
        } catch (CloudFormationException e) {
            return false; // stack does not exist
        }
    }

    private Parameter param(String key, String value) {
        return Parameter.builder().parameterKey(key).parameterValue(value).build();
    }
}
