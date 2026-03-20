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
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SendCommandResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Separate bean for the async shrink workflow.
 * Required because Spring AOP cannot proxy @Async calls made within the same bean.
 */
@Service
@RequiredArgsConstructor
public class EbsShrinkExecutorService {

    private static final Logger logger = LoggerFactory.getLogger(EbsShrinkExecutorService.class);

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final EbsVolumeActionRepository ebsVolumeActionRepository;
    private final RedisCacheService redisCacheService;

    @Async
    public void executeShrinkWorkflow(Long actionId, Long accountId, String volumeId, String region,
                                       String instanceId, String mountPoint, int targetSizeGb) {
        EbsVolumeAction action = ebsVolumeActionRepository.findById(actionId).orElse(null);
        if (action == null) return;

        CloudAccount account = cloudAccountRepository.findById(accountId).orElse(null);
        if (account == null) return;

        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
            SsmClient ssm = awsClientProvider.getSsmClient(account, region);

            updateActionStatus(action, "IN_PROGRESS", "Step 1/7: Pre-flight check via SSM");

            // Step 1: Pre-flight — check actual disk usage
            String dfOutput = runSsmCommand(ssm, instanceId,
                    "df -BG " + mountPoint + " | tail -1 | awk '{print $3}' | tr -d 'G'");
            int usedGb = parseIntSafe(dfOutput.trim(), -1);
            if (usedGb < 0) {
                throw new RuntimeException("Could not determine disk usage via SSM. Ensure SSM Agent is running on instance " + instanceId);
            }
            if (usedGb >= targetSizeGb) {
                throw new RuntimeException("Actual used space (" + usedGb + "GB) >= target size (" + targetSizeGb + "GB). Cannot shrink.");
            }
            logger.info("[Shrink] Volume {} used: {}GB, target: {}GB — safe to proceed", volumeId, usedGb, targetSizeGb);

            // Step 2: Create snapshot for rollback
            updateActionStatus(action, "IN_PROGRESS", "Step 2/7: Creating snapshot for rollback");
            CreateSnapshotResponse snapResp = ec2.createSnapshot(CreateSnapshotRequest.builder()
                    .volumeId(volumeId)
                    .description("XamOps pre-shrink backup of " + volumeId)
                    .build());
            String snapshotId = snapResp.snapshotId();
            logger.info("[Shrink] Snapshot {} created for rollback", snapshotId);

            // Step 3: Create new smaller volume in same AZ
            updateActionStatus(action, "IN_PROGRESS", "Step 3/7: Creating new " + targetSizeGb + "GB volume");
            DescribeVolumesResponse volDesc = ec2.describeVolumes(
                    DescribeVolumesRequest.builder().volumeIds(volumeId).build());
            String availabilityZone = volDesc.volumes().get(0).availabilityZone();
            String volumeType = volDesc.volumes().get(0).volumeTypeAsString();

            CreateVolumeResponse newVolResp = ec2.createVolume(CreateVolumeRequest.builder()
                    .availabilityZone(availabilityZone)
                    .size(targetSizeGb)
                    .volumeType(VolumeType.fromValue(volumeType))
                    .encrypted(volDesc.volumes().get(0).encrypted())
                    .tagSpecifications(TagSpecification.builder()
                            .resourceType(ResourceType.VOLUME)
                            .tags(Tag.builder().key("Name").value("xamops-shrink-target-" + volumeId).build(),
                                  Tag.builder().key("XamOps-Shrink-Source").value(volumeId).build())
                            .build())
                    .build());
            String newVolumeId = newVolResp.volumeId();
            waitForVolumeState(ec2, newVolumeId, "available", 120);

            // Step 4: Attach new volume to instance
            updateActionStatus(action, "IN_PROGRESS", "Step 4/7: Attaching new volume to instance");
            ec2.attachVolume(AttachVolumeRequest.builder()
                    .volumeId(newVolumeId)
                    .instanceId(instanceId)
                    .device("/dev/xvdz")
                    .build());
            waitForVolumeState(ec2, newVolumeId, "in-use", 60);

            // Step 5: Format and mount new volume
            updateActionStatus(action, "IN_PROGRESS", "Step 5/7: Formatting and mounting new volume");
            runSsmCommand(ssm, instanceId, String.join("\n",
                    "mkfs.ext4 -F /dev/xvdz",
                    "mkdir -p /mnt/xamops_new_vol",
                    "mount /dev/xvdz /mnt/xamops_new_vol"
            ));

            // Step 6: rsync Phase 1 — live copy, no downtime
            updateActionStatus(action, "IN_PROGRESS", "Step 6/7: Copying data (this may take a while)");
            runSsmCommand(ssm, instanceId,
                    "rsync -axHAX --progress " + mountPoint + "/ /mnt/xamops_new_vol/ 2>&1 | tail -5");

            // Step 7: Final delta sync + swap (~5 seconds downtime)
            updateActionStatus(action, "IN_PROGRESS", "Step 7/7: Final sync and volume swap");
            String escapedMount = mountPoint.replace("/", "\\/");
            runSsmCommand(ssm, instanceId, String.join("\n",
                    "rsync -axHAX --delete " + mountPoint + "/ /mnt/xamops_new_vol/",
                    "umount " + mountPoint,
                    "umount /mnt/xamops_new_vol",
                    "mount /dev/xvdz " + mountPoint,
                    "NEW_UUID=$(blkid -s UUID -o value /dev/xvdz)",
                    "sed -i '/" + escapedMount + "/d' /etc/fstab",
                    "echo \"UUID=$NEW_UUID " + mountPoint + " ext4 defaults,nofail 0 2\" >> /etc/fstab",
                    "rm -rf /mnt/xamops_new_vol"
            ));

            // Detach old volume and tag for 7-day hold
            ec2.detachVolume(DetachVolumeRequest.builder().volumeId(volumeId).build());
            waitForVolumeState(ec2, volumeId, "available", 60);
            ec2.createTags(CreateTagsRequest.builder()
                    .resources(volumeId)
                    .tags(Tag.builder().key("XamOps-PendingDeletion").value("true").build(),
                          Tag.builder().key("XamOps-DeleteAfter")
                              .value(Instant.now().plus(7, ChronoUnit.DAYS).toString()).build())
                    .build());

            action.setStatus("SUCCESS");
            action.setErrorMessage("Snapshot " + snapshotId + " retained as rollback. Old volume " + volumeId + " detached (7-day hold).");
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
            redisCacheService.evict("ebs-volumes-" + accountId);
            logger.info("[Shrink] {} → {} ({}GB) completed", volumeId, newVolumeId, targetSizeGb);

        } catch (Exception e) {
            logger.error("[Shrink] Workflow failed for {}: {}", volumeId, e.getMessage(), e);
            action.setStatus("FAILED");
            action.setErrorMessage(e.getMessage());
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
        }
    }

    private String runSsmCommand(SsmClient ssm, String instanceId, String script) throws InterruptedException {
        SendCommandResponse cmdResp = ssm.sendCommand(SendCommandRequest.builder()
                .instanceIds(instanceId)
                .documentName("AWS-RunShellScript")
                .parameters(Map.of("commands", List.of(script)))
                .build());
        String commandId = cmdResp.command().commandId();

        for (int i = 0; i < 540; i++) {
            Thread.sleep(10_000);
            GetCommandInvocationResponse invResp = ssm.getCommandInvocation(
                    GetCommandInvocationRequest.builder()
                            .commandId(commandId).instanceId(instanceId).build());
            CommandInvocationStatus status = invResp.status();
            if (status == CommandInvocationStatus.SUCCESS) return invResp.standardOutputContent();
            if (status == CommandInvocationStatus.FAILED
                    || status == CommandInvocationStatus.CANCELLED
                    || status == CommandInvocationStatus.TIMED_OUT) {
                throw new RuntimeException("SSM command failed [" + status + "]: " + invResp.standardErrorContent());
            }
        }
        throw new RuntimeException("SSM command timed out after 90 minutes");
    }

    private void waitForVolumeState(Ec2Client ec2, String volumeId, String targetState, int maxSeconds)
            throws InterruptedException {
        for (int i = 0; i < maxSeconds; i += 5) {
            Thread.sleep(5_000);
            DescribeVolumesResponse resp = ec2.describeVolumes(
                    DescribeVolumesRequest.builder().volumeIds(volumeId).build());
            if (!resp.volumes().isEmpty() && targetState.equals(resp.volumes().get(0).stateAsString())) return;
        }
        throw new RuntimeException("Volume " + volumeId + " did not reach '" + targetState + "' within " + maxSeconds + "s");
    }

    private void updateActionStatus(EbsVolumeAction action, String status, String message) {
        action.setStatus(status);
        action.setErrorMessage(message);
        ebsVolumeActionRepository.save(action);
        logger.info("[Shrink] Action {}: {}", action.getId(), message);
    }

    private int parseIntSafe(String s, int defaultVal) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defaultVal; }
    }
}
