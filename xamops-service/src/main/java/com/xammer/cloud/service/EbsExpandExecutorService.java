package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EbsExpandExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(EbsExpandExecutorService.class);
    private static final int MODIFICATION_POLL_INTERVAL_MS = 15_000;
    private static final int MODIFICATION_TIMEOUT_MINUTES = 15;
    private static final java.util.regex.Pattern VALID_DEVICE =
            java.util.regex.Pattern.compile("^/dev/[a-zA-Z0-9]{1,15}$");
    private static final java.util.regex.Pattern VALID_INSTANCE_ID =
            java.util.regex.Pattern.compile("^i-[0-9a-f]{8,17}$");

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final EbsVolumeActionRepository ebsVolumeActionRepository;
    private final RedisCacheService redisCacheService;
    private final EbsAsgHelper ebsAsgHelper;

    /**
     * Executes the EBS expand workflow asynchronously.
     *
     * @param asgName null for non-ASG volumes; non-null triggers the 4-step ASG workflow:
     *                launch template update → ModifyVolume → poll → SSM resize → StartInstanceRefresh
     */
    @Async
    public void executeExpandWorkflow(Long actionId, Long accountId, String volumeId, String region,
                                      String instanceId, String device, int targetSizeGb, String asgName) {
        EbsVolumeAction action = ebsVolumeActionRepository.findById(actionId).orElse(null);
        if (action == null) return;
        CloudAccount account = cloudAccountRepository.findById(accountId).orElse(null);
        if (account == null) return;

        try {
            if (device != null && !VALID_DEVICE.matcher(device).matches())
                throw new IllegalArgumentException("Invalid device name: " + device);
            if (instanceId != null && !VALID_INSTANCE_ID.matcher(instanceId).matches())
                throw new IllegalArgumentException("Invalid instance ID: " + instanceId);

            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            SsmClient ssm = awsClientProvider.getSsmClient(account, region);

            boolean isAsg = asgName != null && !asgName.isBlank();
            int totalSteps = isAsg ? 4 : 3;

            if (isAsg) {
                updateActionStatus(action,
                        "Step 1/" + totalSteps + ": Updating launch template default version");
                ebsAsgHelper.updateLaunchTemplateVolumeSize(ec2,
                        awsClientProvider.getAutoScalingClient(account, region),
                        asgName, device, targetSizeGb);
            }

            int step = isAsg ? 2 : 1;
            updateActionStatus(action,
                    "Step " + step + "/" + totalSteps + ": Resizing EBS volume to " + targetSizeGb + " GB");
            ec2.modifyVolume(ModifyVolumeRequest.builder()
                    .volumeId(volumeId)
                    .size(targetSizeGb)
                    .build());

            step++;
            updateActionStatus(action,
                    "Step " + step + "/" + totalSteps + ": Waiting for volume modification to complete");
            pollUntilModificationDone(ec2, volumeId);

            step++;
            updateActionStatus(action,
                    "Step " + step + "/" + totalSteps + ": Extending filesystem via SSM");
            runSsmResize(ssm, instanceId, device);

            if (isAsg) {
                updateActionStatus(action, "Step 4/4: Starting ASG instance refresh (minHealthy=90%)");
                ebsAsgHelper.startInstanceRefresh(
                        awsClientProvider.getAutoScalingClient(account, region), asgName);
            }

            action.setStatus("SUCCESS");
            action.setErrorMessage("Volume expanded to " + targetSizeGb + " GB. Filesystem resized online.");
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
            redisCacheService.evict("ebs-volumes-" + accountId);
            logger.info("[Expand] {} → {}GB completed (asg={})", volumeId, targetSizeGb, asgName);

        } catch (Exception e) {
            logger.error("[Expand] Workflow failed for {}: {}", volumeId, e.getMessage(), e);
            action.setStatus("FAILED");
            action.setErrorMessage(e.getMessage());
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
        }
    }

    /**
     * Polls until the volume modification reaches a terminal state.
     * Checks state BEFORE sleeping so that tests returning COMPLETED immediately do not wait.
     */
    private void pollUntilModificationDone(Ec2Client ec2, String volumeId) throws InterruptedException {
        int maxPolls = (MODIFICATION_TIMEOUT_MINUTES * 60_000) / MODIFICATION_POLL_INTERVAL_MS;
        for (int i = 0; i < maxPolls; i++) {
            DescribeVolumesModificationsResponse resp = ec2.describeVolumesModifications(
                    DescribeVolumesModificationsRequest.builder().volumeIds(volumeId).build());
            if (resp.volumesModifications().isEmpty()) return;
            VolumeModificationState state = resp.volumesModifications().get(0).modificationState();
            if (state == VolumeModificationState.COMPLETED || state == VolumeModificationState.OPTIMIZING) return;
            if (state == VolumeModificationState.FAILED) {
                throw new RuntimeException("Volume modification failed for " + volumeId);
            }
            // Sleep is AFTER the state check — if already terminal, we return without waiting
            Thread.sleep(MODIFICATION_POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Volume modification timed out after " + MODIFICATION_TIMEOUT_MINUTES + " minutes");
    }

    /**
     * Sends an SSM shell script to grow the partition and filesystem online.
     * Supports ext2/ext3/ext4 (resize2fs) and xfs (xfs_growfs).
     * Checks status BEFORE sleeping so that tests returning SUCCESS immediately do not wait.
     *
     * Security: Validates device path to prevent command injection via shell metacharacters.
     */
    private void runSsmResize(SsmClient ssm, String instanceId, String device) throws InterruptedException {
        if (device == null || !VALID_DEVICE.matcher(device).matches())
            throw new IllegalArgumentException("Invalid device path: " + device);

        // Pass device as a shell variable (validated above) to avoid interpolation issues
        String script = String.join("\n",
                "set -e",
                "DEVICE=" + device,
                "PART=$(df --output=source \"$DEVICE\" 2>/dev/null | tail -1 || lsblk -no NAME \"$DEVICE\" | tail -1 | xargs -I{} echo /dev/{})",
                "DISK=$(lsblk -no PKNAME \"$PART\" 2>/dev/null || echo '')",
                "PARTNUM=$(echo \"$PART\" | grep -o '[0-9]*$')",
                "if [ -n \"$DISK\" ] && [ -n \"$PARTNUM\" ]; then growpart /dev/$DISK $PARTNUM || true; fi",
                "FSTYPE=$(blkid -s TYPE -o value \"$PART\" 2>/dev/null || echo 'unknown')",
                "case \"$FSTYPE\" in",
                "  ext4|ext3|ext2) resize2fs \"$PART\" ;;",
                "  xfs)            xfs_growfs \"$PART\" ;;",
                "  *) echo \"No filesystem resize needed for $FSTYPE\" ;;",
                "esac"
        );

        SendCommandResponse cmdResp = ssm.sendCommand(SendCommandRequest.builder()
                .instanceIds(instanceId)
                .documentName("AWS-RunShellScript")
                .parameters(Map.of("commands", List.of(script)))
                .build());
        String commandId = cmdResp.command().commandId();

        for (int i = 0; i < 60; i++) {
            GetCommandInvocationResponse invResp = ssm.getCommandInvocation(
                    GetCommandInvocationRequest.builder()
                            .commandId(commandId).instanceId(instanceId).build());
            CommandInvocationStatus status = invResp.status();
            // Check status BEFORE sleeping — terminal states return immediately
            if (status == CommandInvocationStatus.SUCCESS) return;
            if (status == CommandInvocationStatus.FAILED
                    || status == CommandInvocationStatus.CANCELLED
                    || status == CommandInvocationStatus.TIMED_OUT) {
                throw new RuntimeException("SSM resize command failed [" + status + "]: "
                        + invResp.standardErrorContent());
            }
            Thread.sleep(10_000);
        }
        throw new RuntimeException("SSM resize command timed out after 10 minutes");
    }

    private void updateActionStatus(EbsVolumeAction action, String stepText) {
        action.setStatus("IN_PROGRESS");
        action.setErrorMessage(stepText);
        ebsVolumeActionRepository.save(action);
        logger.info("[Expand] Action {}: {}", action.getId(), stepText);
    }
}
