package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.util.Ec2InstanceTypes;
import com.xammer.cloud.util.RemoteCommandInputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.Optional;

/**
 * Applies EC2 rightsizing recommendations.
 *
 * <p>Workflow: preflight checks -&gt; stop instance -&gt; wait for stopped -&gt; modify
 * instance type -&gt; start instance. Only operates on accounts with
 * {@code accessType = "read-write"}.
 *
 * <p>The preflight checks exist because {@code ModifyInstanceAttribute} will happily
 * accept a target type the instance cannot boot on. Specifically it rejects:
 * <ul>
 *   <li>cross-architecture targets (x86 -&gt; arm64), which need an AMI rebuild;</li>
 *   <li>instance-store backed instances, whose ephemeral data is destroyed by a stop;</li>
 *   <li>Auto Scaling Group members, which the ASG replaces rather than resizes;</li>
 *   <li>target types not offered in the instance's Availability Zone;</li>
 *   <li>instances not currently in a state that can be stopped.</li>
 * </ul>
 */
@Service
public class RightsizingApplyService {

    private static final Logger logger = LoggerFactory.getLogger(RightsizingApplyService.class);

    private static final String ASG_TAG_KEY = "aws:autoscaling:groupName";

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;

    public RightsizingApplyService(AwsClientProvider awsClientProvider,
                                    CloudAccountRepository cloudAccountRepository) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Validates access and preconditions, then kicks off the async stop-modify-start
     * workflow.
     *
     * <p>All validation that can be done synchronously happens here so the caller gets a
     * real error instead of a {@code 202} followed by a silent background failure.
     *
     * @param accountId          AWS account ID (12-digit)
     * @param instanceId         EC2 instance ID (e.g. i-1234567890abcdef0)
     * @param targetInstanceType Target instance type (e.g. t3.medium)
     * @param region             AWS region (e.g. us-east-1)
     * @throws IllegalArgumentException if the account is unknown, lacks read-write access,
     *                                  or the resize is not safely applicable
     */
    public void applyEc2Rightsizing(String accountId, String instanceId,
                                     String targetInstanceType, String region) {
        String validatedInstanceId = RemoteCommandInputValidator.requireValidInstanceId(instanceId);
        String validatedRegion = RemoteCommandInputValidator.requireValidRegion(region);
        String validatedTargetType = requireValidInstanceType(targetInstanceType);

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        if (!"read-write".equals(account.getAccessType())) {
            throw new IllegalArgumentException(
                    "Account " + accountId + " does not have read-write access. Rightsizing is not permitted.");
        }

        Ec2Client ec2 = awsClientProvider.getEc2Client(account, validatedRegion);
        Instance instance = describeInstance(ec2, validatedInstanceId);
        runPreflightChecks(ec2, instance, validatedTargetType, validatedRegion);

        // Kick off asynchronously so the HTTP request returns immediately
        performRightsizingAsync(account, validatedInstanceId, validatedTargetType, validatedRegion);
    }

    /**
     * Fetches the instance, failing loudly if it does not exist.
     */
    private Instance describeInstance(Ec2Client ec2, String instanceId) {
        DescribeInstancesResponse response = ec2.describeInstances(
                DescribeInstancesRequest.builder().instanceIds(instanceId).build());
        Optional<Instance> found = response.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .findFirst();
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Instance not found: " + instanceId);
        }
        return found.get();
    }

    /**
     * Rejects resizes that would destroy data, fail to boot, or be undone by an ASG.
     *
     * @throws IllegalArgumentException with a user-facing explanation on the first failure
     */
    private void runPreflightChecks(Ec2Client ec2, Instance instance,
                                     String targetInstanceType, String region) {
        String instanceId = instance.instanceId();
        String currentType = instance.instanceTypeAsString();

        if (targetInstanceType.equalsIgnoreCase(currentType)) {
            throw new IllegalArgumentException(
                    "Instance " + instanceId + " is already type " + currentType + "; nothing to apply.");
        }

        // 1. Architecture change requires an AMI built for the target architecture.
        if (Ec2InstanceTypes.isArchitectureChange(currentType, targetInstanceType)) {
            throw new IllegalArgumentException(String.format(
                    "Cannot resize %s from %s (%s) to %s (%s): changing CPU architecture requires an AMI "
                            + "built for the target architecture. Rebuild the image and relaunch instead of resizing.",
                    instanceId, currentType, Ec2InstanceTypes.architectureOf(currentType),
                    targetInstanceType, Ec2InstanceTypes.architectureOf(targetInstanceType)));
        }

        // 2. Instance-store root or attached ephemeral volumes do not survive a stop.
        if (instance.rootDeviceTypeAsString() != null
                && instance.rootDeviceTypeAsString().toLowerCase().contains("instance-store")) {
            throw new IllegalArgumentException("Cannot resize " + instanceId
                    + ": it is instance-store backed, and stopping it destroys the root volume.");
        }
        boolean hasEphemeral = instance.blockDeviceMappings().stream()
                .anyMatch(bdm -> bdm.ebs() == null);
        if (hasEphemeral) {
            throw new IllegalArgumentException("Cannot resize " + instanceId
                    + ": it has instance-store (ephemeral) volumes whose data is lost on stop.");
        }

        // 3. ASG members get replaced, not resized. Change the launch template instead.
        Optional<String> asgName = instance.tags().stream()
                .filter(t -> ASG_TAG_KEY.equals(t.key()))
                .map(Tag::value)
                .findFirst();
        if (asgName.isPresent()) {
            throw new IllegalArgumentException(String.format(
                    "Cannot resize %s: it is managed by Auto Scaling Group '%s'. Update the group's launch "
                            + "template instead, otherwise the ASG will replace the resized instance.",
                    instanceId, asgName.get()));
        }

        // 4. Only running or stopped instances can be transitioned.
        String state = instance.state() != null ? instance.state().nameAsString() : "unknown";
        if (!"running".equalsIgnoreCase(state) && !"stopped".equalsIgnoreCase(state)) {
            throw new IllegalArgumentException("Cannot resize " + instanceId
                    + ": instance state is '" + state + "'. It must be running or stopped.");
        }

        // 5. The target type must actually be offered in this instance's AZ.
        String availabilityZone = instance.placement() != null ? instance.placement().availabilityZone() : null;
        if (availabilityZone != null && !isTypeOfferedInZone(ec2, targetInstanceType, availabilityZone)) {
            throw new IllegalArgumentException(String.format(
                    "Instance type %s is not offered in availability zone %s (region %s).",
                    targetInstanceType, availabilityZone, region));
        }

        logger.info("[Rightsizing] Preflight passed for {}: {} -> {} in {}",
                instanceId, currentType, targetInstanceType, region);
    }

    /**
     * Checks {@code DescribeInstanceTypeOfferings} for the instance's AZ.
     *
     * <p>Returns true when the check itself fails, so a transient API error or a missing
     * {@code ec2:DescribeInstanceTypeOfferings} permission does not block an otherwise
     * valid resize. AWS re-validates the type at modify time.
     */
    private boolean isTypeOfferedInZone(Ec2Client ec2, String instanceType, String availabilityZone) {
        try {
            DescribeInstanceTypeOfferingsResponse offerings = ec2.describeInstanceTypeOfferings(
                    DescribeInstanceTypeOfferingsRequest.builder()
                            .locationType(LocationType.AVAILABILITY_ZONE)
                            .filters(
                                    Filter.builder().name("location").values(availabilityZone).build(),
                                    Filter.builder().name("instance-type").values(instanceType).build())
                            .build());
            return !offerings.instanceTypeOfferings().isEmpty();
        } catch (Exception e) {
            logger.warn("[Rightsizing] Could not verify {} availability in {}: {}. Allowing the resize; "
                    + "AWS will reject it at modify time if unsupported.",
                    instanceType, availabilityZone, e.getMessage());
            return true;
        }
    }

    /**
     * Validates the shape of an instance type name before it reaches the AWS API.
     */
    private String requireValidInstanceType(String instanceType) {
        if (instanceType == null || !instanceType.matches("^[a-z0-9]+\\.[a-z0-9]+$")) {
            throw new IllegalArgumentException("Invalid instance type: " + instanceType);
        }
        return instanceType;
    }

    /**
     * Asynchronously stops the instance, modifies its type, then restarts it.
     * Runs in a background thread — the caller returns 202 Accepted before this finishes.
     *
     * <p>On failure after the stop succeeded, the instance is restarted on its original
     * type so it is not left powered off.
     */
    @Async("awsTaskExecutor")
    public void performRightsizingAsync(CloudAccount account, String instanceId,
                                         String targetInstanceType, String region) {
        String accountId = account.getAwsAccountId();
        logger.info("[Rightsizing] Starting EC2 rightsizing: instance={} targetType={} region={} account={}",
                instanceId, targetInstanceType, region, accountId);

        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        boolean stopped = false;
        String originalType = null;

        try {
            originalType = describeInstance(ec2, instanceId).instanceTypeAsString();

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
            stopped = true;
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
            if (stopped) {
                restartAfterFailure(ec2, instanceId, originalType);
            }
        }
    }

    /**
     * Best-effort recovery: bring the instance back up so a failed resize does not leave
     * it powered off. Logs loudly if this also fails, since that needs an operator.
     */
    private void restartAfterFailure(Ec2Client ec2, String instanceId, String originalType) {
        try {
            logger.warn("[Rightsizing] Attempting to restart {} after failed resize (original type {})",
                    instanceId, originalType);
            ec2.startInstances(StartInstancesRequest.builder().instanceIds(instanceId).build());
            logger.warn("[Rightsizing] Instance {} restarted after failed resize", instanceId);
        } catch (Exception recoveryFailure) {
            logger.error("[Rightsizing] MANUAL INTERVENTION REQUIRED: instance {} is stopped and could not be "
                    + "restarted: {}", instanceId, recoveryFailure.getMessage(), recoveryFailure);
        }
    }
}
