package com.xammer.cloud.service;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.GroupLookup;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.ActorType;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * EBS automation on Auto Scaling groups (design-aws.md §5.5, FR-CMP-01, AC-CMP-01).
 *
 * <ul>
 *   <li><b>Template resolution.</b> The launch template comes from {@code group.launchTemplate()}, else from the
 *       {@code MixedInstancesPolicy}'s own template. A group whose overrides carry per-type templates gets
 *       {@link #PER_TYPE_TEMPLATES} and no change.</li>
 *   <li><b>Coordination.</b> When a {@code spot_group} row exists for the group, the template-version and refresh
 *       calls run under the AutoSpotting group lease (component {@code ebs}, fenced, short TTL). The call is
 *       refused with {@code AUTOSPOTTING_OPERATION_IN_PROGRESS}, before any AWS call, while the group is
 *       APPLYING or REVERTING or another holder has the lease.</li>
 *   <li><b>Managed groups.</b> The refresh launches before it terminates: {@code MinHealthyPercentage=100},
 *       {@code MaxHealthyPercentage=110}, {@code SkipMatching=true}, so it never drops in-service capacity on a
 *       Spot group. The refresh id and the new template version go to an audit row
 *       ({@code actor=EBS_AUTOMATION}); the reconciler treats the refresh as DR-6 and defers, and a version bump
 *       is minor context drift, so it never pauses the group.</li>
 *   <li><b>Groups without a row</b> (and calls without an account context) behave exactly as before: no lease,
 *       {@code MinHealthyPercentage=90} (FR-CMP-05).</li>
 * </ul>
 * Revert leaves EBS versions alone: a snapshot stores the version string and revert never writes a version.
 */
@Service
public class EbsAsgHelper {
    private static final Logger logger = LoggerFactory.getLogger(EbsAsgHelper.class);

    /** What a group with per-type launch templates is told. */
    public static final String PER_TYPE_TEMPLATES =
            "EBS automation can't update a group with per-type launch templates.";
    /** The error code of a refusal while AutoSpotting is changing the group. */
    public static final String OPERATION_IN_PROGRESS = "AUTOSPOTTING_OPERATION_IN_PROGRESS";
    private static final String BUSY_MESSAGE = "AutoSpotting is changing this group; try again when it finishes.";
    private static final Duration LEASE_CHECK = Duration.ofSeconds(30);
    /** The states in which XamOps manages the group's capacity mix (design-aws.md §5.5 item 3). */
    private static final java.util.Set<GroupState> MANAGED = java.util.EnumSet.of(GroupState.ACTIVE,
            GroupState.DEGRADED, GroupState.FALLBACK, GroupState.PAUSED, GroupState.LEGACY_UNCONFIRMED);

    private final GroupLookup groupLookup;
    private final LeaseService leaseService;
    private final FencedTx fencedTx;
    private final AutoSpottingAuditWriter auditWriter;

    public EbsAsgHelper(GroupLookup groupLookup, LeaseService leaseService, FencedTx fencedTx,
                        AutoSpottingAuditWriter auditWriter) {
        this.groupLookup = groupLookup;
        this.leaseService = leaseService;
        this.fencedTx = fencedTx;
        this.auditWriter = auditWriter;
    }

    /** The AutoSpotting side of one call: the group row and the lease EBS automation holds on it. */
    private record Held(SpotGroup group, LeaseHandle handle) {
    }

    /** A launch template as the group names it: an id or a name. */
    private record Template(String id, String name) {
    }

public void updateLaunchTemplateVolumeType(Ec2Client ec2, AutoScalingClient asg,
                                               String asgName, String deviceName, String newType) {
        updateLaunchTemplateVolumeType(null, null, ec2, asg, asgName, deviceName, newType);
    }

    public void updateLaunchTemplateVolumeType(Long cloudAccountId, String region, Ec2Client ec2,
                                               AutoScalingClient asg, String asgName, String deviceName,
                                               String newType) {
        Held held = enter(cloudAccountId, region, asgName);
        try {
            Template lt = resolveTemplate(asg, asgName);
            updateVolumeType(held, ec2, lt, asgName, deviceName, newType);
        } finally {
            leave(held);
        }
    }

    private void updateVolumeType(Held held, Ec2Client ec2, Template lt, String asgName, String deviceName,
                                  String newType) {
        String ltId = lt.id();
        LaunchTemplateVersion current = getCurrentLaunchTemplateVersion(ec2, lt);

        List<LaunchTemplateBlockDeviceMappingRequest> newBdms = current.launchTemplateData()
                .blockDeviceMappings().stream()
                .map(bdm -> {
                    if (deviceName.equals(bdm.deviceName()) && bdm.ebs() != null) {
                        return LaunchTemplateBlockDeviceMappingRequest.builder()
                                .deviceName(bdm.deviceName())
                                .ebs(LaunchTemplateEbsBlockDeviceRequest.builder()
                                        .volumeType(VolumeType.fromValue(newType))
                                        .volumeSize(bdm.ebs().volumeSize())
                                        .deleteOnTermination(bdm.ebs().deleteOnTermination())
                                        .encrypted(bdm.ebs().encrypted())
                                        .build())
                                .build();
                    }
                    // Pass-through: preserve virtualName (instance-store), noDevice, and any EBS fields
                    LaunchTemplateBlockDeviceMappingRequest.Builder b =
                            LaunchTemplateBlockDeviceMappingRequest.builder().deviceName(bdm.deviceName());
                    if (bdm.virtualName() != null) b.virtualName(bdm.virtualName());
                    if (bdm.noDevice() != null) b.noDevice(bdm.noDevice());
                    if (bdm.ebs() != null) {
                        b.ebs(LaunchTemplateEbsBlockDeviceRequest.builder()
                                .volumeType(bdm.ebs().volumeType())
                                .volumeSize(bdm.ebs().volumeSize())
                                .deleteOnTermination(bdm.ebs().deleteOnTermination())
                                .encrypted(bdm.ebs().encrypted())
                                .build());
                    }
                    return b.build();
                })
                .collect(Collectors.toList());

        String newVersion = createAndSetDefaultVersion(held, ec2, lt, newBdms);
        logger.info("[ASG] Launch template {} updated to version {} (volumeType={})", ltId, newVersion, newType);
        auditTemplateVersion(held, asgName, lt, newVersion);
    }

    public void updateLaunchTemplateVolumeSize(Ec2Client ec2, AutoScalingClient asg,
                                               String asgName, String deviceName, int newSizeGb) {
        updateLaunchTemplateVolumeSize(null, null, ec2, asg, asgName, deviceName, newSizeGb);
    }

    public void updateLaunchTemplateVolumeSize(Long cloudAccountId, String region, Ec2Client ec2,
                                               AutoScalingClient asg, String asgName, String deviceName,
                                               int newSizeGb) {
        Held held = enter(cloudAccountId, region, asgName);
        try {
            Template lt = resolveTemplate(asg, asgName);
            updateVolumeSize(held, ec2, lt, asgName, deviceName, newSizeGb);
        } finally {
            leave(held);
        }
    }

    private void updateVolumeSize(Held held, Ec2Client ec2, Template lt, String asgName, String deviceName,
                                  int newSizeGb) {
        String ltId = lt.id();
        LaunchTemplateVersion current = getCurrentLaunchTemplateVersion(ec2, lt);

        // Validate: disallow shrinking the volume
        current.launchTemplateData().blockDeviceMappings().stream()
                .filter(bdm -> deviceName.equals(bdm.deviceName()) && bdm.ebs() != null)
                .findFirst()
                .ifPresent(bdm -> {
                    if (bdm.ebs().volumeSize() != null && newSizeGb < bdm.ebs().volumeSize()) {
                        throw new IllegalArgumentException(
                                "Cannot shrink launch template volume on " + deviceName
                                + " from " + bdm.ebs().volumeSize() + "GB to " + newSizeGb + "GB");
                    }
                });

        List<LaunchTemplateBlockDeviceMappingRequest> newBdms = current.launchTemplateData()
                .blockDeviceMappings().stream()
                .map(bdm -> {
                    if (deviceName.equals(bdm.deviceName()) && bdm.ebs() != null) {
                        return LaunchTemplateBlockDeviceMappingRequest.builder()
                                .deviceName(bdm.deviceName())
                                .ebs(LaunchTemplateEbsBlockDeviceRequest.builder()
                                        .volumeType(bdm.ebs().volumeType())
                                        .volumeSize(newSizeGb)
                                        .deleteOnTermination(bdm.ebs().deleteOnTermination())
                                        .encrypted(bdm.ebs().encrypted())
                                        .build())
                                .build();
                    }
                    // Pass-through: preserve virtualName (instance-store), noDevice, and any EBS fields
                    LaunchTemplateBlockDeviceMappingRequest.Builder b =
                            LaunchTemplateBlockDeviceMappingRequest.builder().deviceName(bdm.deviceName());
                    if (bdm.virtualName() != null) b.virtualName(bdm.virtualName());
                    if (bdm.noDevice() != null) b.noDevice(bdm.noDevice());
                    if (bdm.ebs() != null) {
                        b.ebs(LaunchTemplateEbsBlockDeviceRequest.builder()
                                .volumeType(bdm.ebs().volumeType())
                                .volumeSize(bdm.ebs().volumeSize())
                                .deleteOnTermination(bdm.ebs().deleteOnTermination())
                                .encrypted(bdm.ebs().encrypted())
                                .build());
                    }
                    return b.build();
                })
                .collect(Collectors.toList());

        String newVersion = createAndSetDefaultVersion(held, ec2, lt, newBdms);
        logger.info("[ASG] Launch template {} updated to version {} (volumeSize={}GB)", ltId, newVersion, newSizeGb);
        auditTemplateVersion(held, asgName, lt, newVersion);
    }

    public void startInstanceRefresh(AutoScalingClient asg, String asgName) {
        startInstanceRefresh(null, null, asg, asgName);
    }

    /**
     * Start the refresh. A group XamOps manages gets a launch-before-terminate refresh (100 / 110 / SkipMatching)
     * under the group lease; any other group gets today's request, byte for byte.
     */
    public void startInstanceRefresh(Long cloudAccountId, String region, AutoScalingClient asg, String asgName) {
        Held held = enter(cloudAccountId, region, asgName);
        try {
            boolean managed = held != null && MANAGED.contains(held.group().getState());
            RefreshPreferences.Builder prefs = RefreshPreferences.builder();
            if (managed) {
                prefs.minHealthyPercentage(100).maxHealthyPercentage(110).skipMatching(true);
            } else {
                prefs.minHealthyPercentage(90);
            }
            assertHeld(held);
            StartInstanceRefreshResponse resp = asg.startInstanceRefresh(StartInstanceRefreshRequest.builder()
                    .autoScalingGroupName(asgName)
                    .preferences(prefs.build())
                    .build());
            logger.info("[ASG] Instance refresh started for {}", asgName);
            if (held != null) {
                String refreshId = resp == null ? null : resp.instanceRefreshId();
                audit(held, "INSTANCE_REFRESH", "{\"actor\":\"EBS_AUTOMATION\",\"refreshId\":"
                        + quote(refreshId) + ",\"launchBeforeTerminate\":" + managed + "}");
            }
        } finally {
            leave(held);
        }
    }

    // ---- coordination with AutoSpotting (design-aws.md §5.5 item 2) ----

    /** The row and lease for a managed group; null when there is no row (or no account context): today's behavior. */
    private Held enter(Long cloudAccountId, String region, String asgName) {
        if (cloudAccountId == null || region == null || groupLookup == null || leaseService == null) {
            return null;
        }
        Optional<SpotGroup> row = groupLookup.live(Provider.AWS, cloudAccountId, region + "/" + asgName);
        if (row.isEmpty()) {
            return null;
        }
        if (busy(row.get())) {
            throw inProgress();
        }
        Optional<LeaseHandle> lease;
        try {
            lease = leaseService.acquireGroup(row.get().getId(), LeaseService.Component.EBS);
        } catch (RuntimeException e) {
            // The lease store cannot be reached: refuse rather than change a group AutoSpotting may be changing.
            throw inProgress();
        }
        if (lease.isEmpty()) {
            throw inProgress();
        }
        SpotGroup fresh = groupLookup.live(Provider.AWS, cloudAccountId, region + "/" + asgName).orElse(row.get());
        if (busy(fresh)) {
            leaseService.releaseGroup(lease.get());
            throw inProgress();
        }
        return new Held(fresh, lease.get());
    }

    private static boolean busy(SpotGroup g) {
        return g.getState() == GroupState.APPLYING || g.getState() == GroupState.REVERTING;
    }

    private static AutoSpottingApiException inProgress() {
        return new AutoSpottingApiException(HttpStatus.CONFLICT, OPERATION_IN_PROGRESS, BUSY_MESSAGE, true, null, null);
    }

    private void leave(Held held) {
        if (held != null) {
            leaseService.releaseGroup(held.handle());
        }
    }

    private static void assertHeld(Held held) {
        if (held != null) {
            held.handle().assertHeld(LEASE_CHECK);
        }
    }

    private void auditTemplateVersion(Held held, String asgName, Template lt, String newVersion) {
        if (held == null) {
            return;
        }
        audit(held, "TEMPLATE_VERSION", "{\"actor\":\"EBS_AUTOMATION\",\"launchTemplate\":"
                + quote(lt.id() != null ? lt.id() : lt.name()) + ",\"newVersion\":" + quote(newVersion) + "}");
    }

    private void audit(Held held, String action, String details) {
        SpotGroup g = held.group();
        fencedTx.run(held.handle(), () -> auditWriter.write(
                AuditRecord.builder("EBS_AUTOMATION", new Actor(ActorType.SYSTEM, null, "EBS_AUTOMATION", null),
                                action, "SUCCESS")
                        .provider(g.getProvider() == null ? null : g.getProvider().name())
                        .clientId(g.getClientId())
                        .cloudAccountId(g.getCloudAccountId())
                        .groupId(g.getId())
                        .groupKey(g.getGroupKey())
                        .details(details)
                        .build()));
    }

    private static String quote(String v) {
        return v == null ? "null" : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- template resolution (design-aws.md §5.5 item 1) ----

    private Template resolveTemplate(AutoScalingClient asg, String asgName) {
        DescribeAutoScalingGroupsResponse resp = asg.describeAutoScalingGroups(
                DescribeAutoScalingGroupsRequest.builder()
                        .autoScalingGroupNames(asgName)
                        .build());
        if (resp.autoScalingGroups().isEmpty()) {
            throw new RuntimeException("ASG not found: " + asgName);
        }
        AutoScalingGroup group = resp.autoScalingGroups().get(0);
        if (group.launchTemplate() != null) {
            return new Template(group.launchTemplate().launchTemplateId(), group.launchTemplate().launchTemplateName());
        }
        MixedInstancesPolicy mip = group.mixedInstancesPolicy();
        if (mip != null && mip.launchTemplate() != null) {
            boolean perType = mip.launchTemplate().overrides().stream()
                    .anyMatch(o -> o.launchTemplateSpecification() != null);
            if (perType) {
                throw new IllegalStateException(PER_TYPE_TEMPLATES);
            }
            software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification spec =
                    mip.launchTemplate().launchTemplateSpecification();
            if (spec != null) {
                return new Template(spec.launchTemplateId(), spec.launchTemplateName());
            }
        }
        throw new RuntimeException("ASG " + asgName + " does not use a launch template");
    }

    private LaunchTemplateVersion getCurrentLaunchTemplateVersion(Ec2Client ec2, Template lt) {
        DescribeLaunchTemplateVersionsRequest.Builder req = DescribeLaunchTemplateVersionsRequest.builder()
                .versions("$Default");
        if (lt.id() != null) {
            req.launchTemplateId(lt.id());
        } else {
            req.launchTemplateName(lt.name());
        }
        DescribeLaunchTemplateVersionsResponse resp = ec2.describeLaunchTemplateVersions(req.build());
        if (resp.launchTemplateVersions().isEmpty()) {
            throw new RuntimeException("No default version found for launch template "
                    + (lt.id() != null ? lt.id() : lt.name()));
        }
        return resp.launchTemplateVersions().get(0);
    }

    private String createAndSetDefaultVersion(Held held, Ec2Client ec2, Template lt,
                                              List<LaunchTemplateBlockDeviceMappingRequest> bdms) {
        CreateLaunchTemplateVersionRequest.Builder create = CreateLaunchTemplateVersionRequest.builder()
                .sourceVersion("$Default")
                .launchTemplateData(RequestLaunchTemplateData.builder()
                        .blockDeviceMappings(bdms)
                        .build());
        ModifyLaunchTemplateRequest.Builder modify = ModifyLaunchTemplateRequest.builder();
        if (lt.id() != null) {
            create.launchTemplateId(lt.id());
            modify.launchTemplateId(lt.id());
        } else {
            create.launchTemplateName(lt.name());
            modify.launchTemplateName(lt.name());
        }
        assertHeld(held);
        CreateLaunchTemplateVersionResponse newVersionResp = ec2.createLaunchTemplateVersion(create.build());
        String newVersion = String.valueOf(newVersionResp.launchTemplateVersion().versionNumber());
        assertHeld(held);
        ec2.modifyLaunchTemplate(modify.defaultVersion(newVersion).build());
        return newVersion;
    }
}
