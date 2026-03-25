package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;

/**
 * Service that applies EC2 rightsizing recommendations.
 * Performs: stop instance → wait for stopped → modify instance type → start instance.
 * Only operates on accounts with accessType = "read-write".
 */
@Service
public class RightsizingApplyService {

    private static final Logger logger = LoggerFactory.getLogger(RightsizingApplyService.class);

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public RightsizingApplyService(AwsClientProvider awsClientProvider,
                                    CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Validates access and kicks off the async stop → modify → start workflow.
     *
     * @param accountId          AWS account ID (12-digit)
     * @param instanceId         EC2 instance ID (e.g. i-1234567890abcdef0)
     * @param targetInstanceType Target instance type (e.g. t3.medium)
     * @param region             AWS region (e.g. us-east-1)
     * @throws IllegalArgumentException if account not found or not read-write
     */
    public void applyEc2Rightsizing(String accountId, String instanceId,
                                     String targetInstanceType, String region) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        if (!"read-write".equals(account.getAccessType())) {
            throw new IllegalArgumentException(
                    "Account " + accountId + " does not have read-write access. Rightsizing is not permitted.");
        }

        // Kick off asynchronously so the HTTP request returns immediately
        performRightsizingAsync(account, instanceId, targetInstanceType, region);
    }

    /**
     * Asynchronously stops the instance, modifies its type, then restarts it.
     * Runs in a background thread — the caller returns 202 Accepted before this finishes.
     */
    @Async("awsTaskExecutor")
    public void performRightsizingAsync(CloudAccount account, String instanceId,
                                         String targetInstanceType, String region) {
        String accountId = account.getAwsAccountId();
        logger.info("[Rightsizing] Starting EC2 rightsizing: instance={} targetType={} region={} account={}",
                instanceId, targetInstanceType, region, accountId);

        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

            // Step 1: Stop the instance
            logger.info("[Rightsizing] Stopping instance {}", instanceId);
            ec2.stopInstances(StopInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());

            // Step 2: Wait until fully stopped
            logger.info("[Rightsizing] Waiting for instance {} to reach stopped state...", instanceId);
            ec2.waiter().waitUntilInstanceStopped(
                    DescribeInstancesRequest.builder()
                            .instanceIds(instanceId)
                            .build());
            logger.info("[Rightsizing] Instance {} is now stopped", instanceId);

            // Step 3: Modify instance type
            logger.info("[Rightsizing] Modifying instance {} type to {}", instanceId, targetInstanceType);
            ec2.modifyInstanceAttribute(ModifyInstanceAttributeRequest.builder()
                    .instanceId(instanceId)
                    .instanceType(AttributeValue.builder().value(targetInstanceType).build())
                    .build());

            // Step 4: Start the instance
            logger.info("[Rightsizing] Starting instance {} with new type {}", instanceId, targetInstanceType);
            ec2.startInstances(StartInstancesRequest.builder()
                    .instanceIds(instanceId)
                    .build());

            logger.info("[Rightsizing] SUCCESS: Instance {} is now starting with type {} in account {}",
                    instanceId, targetInstanceType, accountId);

        } catch (Exception e) {
            logger.error("[Rightsizing] FAILED for instance {} (account={} region={}): {}",
                    instanceId, accountId, region, e.getMessage(), e);
        }
    }
}
