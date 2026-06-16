package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DiskPolicyRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.InstanceProfile;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataQuery;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.MetricStat;
import software.amazon.awssdk.services.cloudwatch.model.ScanBy;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.computeoptimizer.model.EBSFinding;
import software.amazon.awssdk.services.computeoptimizer.model.GetEbsVolumeRecommendationsRequest;
import software.amazon.awssdk.services.computeoptimizer.model.GetEbsVolumeRecommendationsResponse;
import software.amazon.awssdk.services.computeoptimizer.model.VolumeConfiguration;
import software.amazon.awssdk.services.computeoptimizer.model.VolumeRecommendation;
import software.amazon.awssdk.services.computeoptimizer.model.VolumeRecommendationOption;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.CommandInvocationStatus;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationRequest;
import software.amazon.awssdk.services.ssm.model.DescribeInstanceInformationResponse;
import software.amazon.awssdk.services.ssm.model.InstanceInformationStringFilter;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationRequest;
import software.amazon.awssdk.services.ssm.model.GetCommandInvocationResponse;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SendCommandResponse;

@Service
@RequiredArgsConstructor
public class EbsDiskRightsizingService {

    private static final Logger logger = LoggerFactory.getLogger(EbsDiskRightsizingService.class);
    private static final String CACHE_KEY_VOLUMES = "ebs-volumes-";
    private static final int CACHE_TTL_MINUTES = 120;

    // GP3 pricing per GB/month (us-east-1 base rate)
    private static final double GP3_PRICE_PER_GB = 0.08;
    private static final double GP2_PRICE_PER_GB = 0.10;
    private static final double IO1_PRICE_PER_GB = 0.125;
    private static final double IO2_PRICE_PER_GB = 0.125;
    private static final double SC1_PRICE_PER_GB = 0.025;
    private static final double ST1_PRICE_PER_GB = 0.045;

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final DiskPolicyRepository diskPolicyRepository;
    private final EbsVolumeActionRepository ebsVolumeActionRepository;
    private final RedisCacheService redisCacheService;
    private final EbsShrinkExecutorService shrinkExecutorService;

    // ─── Volume Scanning ────────────────────────────────────────────────────────

    public List<Map<String, Object>> getVolumes(String accountId, boolean forceRefresh) {
        CloudAccount account = resolveAccount(accountId);

        String cacheKey = CACHE_KEY_VOLUMES + accountId;
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (cached.isPresent()) {
                logger.info("EBS volumes from cache for account {}", accountId);
                return cached.get();
            }
        }

        List<Map<String, Object>> allVolumes = new ArrayList<>();
        List<String> regions = getEnabledRegions(account);
        logger.info("[EBS] Scanning {} regions for account {}: {}", regions.size(), accountId, regions);

        int errorCount = 0;
        for (String region : regions) {
            try {
                List<Map<String, Object>> regionVolumes = describeVolumesInRegion(account, region);
                logger.info("[EBS] Region {} → {} volumes", region, regionVolumes.size());
                allVolumes.addAll(regionVolumes);
            } catch (Exception e) {
                errorCount++;
                logger.error("[EBS] Failed to describe volumes in region {} for account {}: {}", region, accountId, e.getMessage());
            }
        }

        logger.info("[EBS] Total: {} volumes across {} regions ({} region errors) for account {}",
                allVolumes.size(), regions.size(), errorCount, accountId);
        redisCacheService.put(cacheKey, allVolumes, CACHE_TTL_MINUTES);
        return allVolumes;
    }

    public List<Map<String, Object>> getRecommendations(String accountId, boolean forceRefresh) {
        List<Map<String, Object>> volumes = getVolumes(accountId, forceRefresh);
        return volumes.stream()
                .filter(v -> !"NONE".equals(v.get("recommendation")))
                .collect(Collectors.toList());
    }

    // ─── Apply Actions ───────────────────────────────────────────────────────────

    public Map<String, Object> applyAction(String accountId, String volumeId, String region,
                                            String actionType, String targetType, Integer targetSizeGb) {
        CloudAccount account = resolveAccount(accountId);

        EbsVolumeAction action = new EbsVolumeAction();
        action.setAccount(account);
        action.setVolumeId(volumeId);
        action.setRegion(region);
        action.setActionType(actionType);
        action.setStatus("IN_PROGRESS");
        action.setInitiatedAt(Instant.now());
        ebsVolumeActionRepository.save(action);

        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

            switch (actionType) {
                case "UPGRADE_TYPE":
                    return applyTypeUpgrade(ec2, action, volumeId, targetType);
                case "DELETE_UNATTACHED":
                    return deleteUnattachedVolume(ec2, action, volumeId, region);
                case "EXPAND":
                    return applyResize(ec2, action, volumeId, targetSizeGb, targetType);
                default:
                    action.setStatus("FAILED");
                    action.setErrorMessage("Unknown action type: " + actionType);
                    ebsVolumeActionRepository.save(action);
                    return Map.of("success", false, "error", "Unknown action type: " + actionType);
            }
        } catch (Exception e) {
            logger.error("Failed to apply {} on volume {} in {}: {}", actionType, volumeId, region, e.getMessage(), e);
            action.setStatus("FAILED");
            action.setErrorMessage(e.getMessage());
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
            return Map.of("success", false, "error", e.getMessage());
        } finally {
            redisCacheService.evict(CACHE_KEY_VOLUMES + accountId);
        }
    }

    private Map<String, Object> applyTypeUpgrade(Ec2Client ec2, EbsVolumeAction action,
                                                   String volumeId, String targetType) {
        // Fetch current volume details first
        DescribeVolumesResponse descResp = ec2.describeVolumes(
                DescribeVolumesRequest.builder().volumeIds(volumeId).build());
        if (descResp.volumes().isEmpty()) {
            throw new RuntimeException("Volume not found: " + volumeId);
        }
        Volume vol = descResp.volumes().get(0);
        action.setOldType(vol.volumeTypeAsString());
        action.setNewType(targetType);
        action.setOldSizeGb(vol.size());
        action.setNewSizeGb(vol.size());
        action.setEstimatedSavings(calcMonthlyCost(vol.volumeTypeAsString(), vol.size())
                - calcMonthlyCost(targetType, vol.size()));

        ModifyVolumeRequest modifyReq = ModifyVolumeRequest.builder()
                .volumeId(volumeId)
                .volumeType(VolumeType.fromValue(targetType))
                .build();
        ec2.modifyVolume(modifyReq);

        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);

        logger.info("Type upgrade {} → {} applied to volume {}", action.getOldType(), targetType, volumeId);
        return Map.of("success", true, "message",
                "Volume type changed from " + action.getOldType() + " to " + targetType);
    }

    private Map<String, Object> applyResize(Ec2Client ec2, EbsVolumeAction action,
                                             String volumeId, int targetSizeGb, String targetType) {
        DescribeVolumesResponse descResp = ec2.describeVolumes(
                DescribeVolumesRequest.builder().volumeIds(volumeId).build());
        if (descResp.volumes().isEmpty()) {
            throw new RuntimeException("Volume not found: " + volumeId);
        }
        Volume vol = descResp.volumes().get(0);
        action.setOldType(vol.volumeTypeAsString());
        action.setOldSizeGb(vol.size());
        action.setNewSizeGb(targetSizeGb);
        action.setNewType(targetType != null ? targetType : vol.volumeTypeAsString());

        ModifyVolumeRequest.Builder modifyBuilder = ModifyVolumeRequest.builder()
                .volumeId(volumeId)
                .size(targetSizeGb);
        if (targetType != null) {
            modifyBuilder.volumeType(VolumeType.fromValue(targetType));
        }
        ec2.modifyVolume(modifyBuilder.build());

        action.setEstimatedSavings(calcMonthlyCost(vol.volumeTypeAsString(), vol.size())
                - calcMonthlyCost(action.getNewType(), targetSizeGb));
        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);

        return Map.of("success", true, "message",
                "Volume resized from " + vol.size() + "GB to " + targetSizeGb + "GB");
    }

    private Map<String, Object> deleteUnattachedVolume(Ec2Client ec2, EbsVolumeAction action,
                                                        String volumeId, String region) {
        // Safety: verify volume is truly unattached before deleting
        DescribeVolumesResponse descResp = ec2.describeVolumes(
                DescribeVolumesRequest.builder().volumeIds(volumeId).build());
        if (descResp.volumes().isEmpty()) {
            throw new RuntimeException("Volume not found: " + volumeId);
        }
        Volume vol = descResp.volumes().get(0);
        if (!vol.stateAsString().equals("available")) {
            throw new RuntimeException("Volume " + volumeId + " is not unattached (state=" + vol.stateAsString() + ")");
        }

        action.setOldType(vol.volumeTypeAsString());
        action.setOldSizeGb(vol.size());
        action.setEstimatedSavings(calcMonthlyCost(vol.volumeTypeAsString(), vol.size()));

        ec2.deleteVolume(DeleteVolumeRequest.builder().volumeId(volumeId).build());

        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);

        logger.info("Deleted unattached volume {} ({}GB {}) in {}", volumeId, vol.size(), vol.volumeTypeAsString(), region);
        return Map.of("success", true, "message",
                "Deleted unattached volume " + volumeId + " (" + vol.size() + "GB " + vol.volumeTypeAsString() + ")");
    }

    // ─── Policies ────────────────────────────────────────────────────────────────

    public DiskPolicy createPolicy(String accountId, DiskPolicy req) {
        CloudAccount account = resolveAccount(accountId);

        if (req.isDefault()) {
            diskPolicyRepository.findByAccountIdAndIsDefaultTrue(account.getId())
                    .ifPresent(p -> { p.setDefault(false); diskPolicyRepository.save(p); });
        }

        req.setAccount(account);
        req.setCreatedAt(Instant.now());
        return diskPolicyRepository.save(req);
    }

    public List<DiskPolicy> getPolicies(String accountId) {
        return diskPolicyRepository.findByAccountId(resolveAccount(accountId).getId());
    }

    public DiskPolicy updatePolicy(Long policyId, DiskPolicy req) {
        DiskPolicy existing = diskPolicyRepository.findById(policyId)
                .orElseThrow(() -> new RuntimeException("Policy not found: " + policyId));

        if (req.isDefault() && !existing.isDefault()) {
            diskPolicyRepository.findByAccountIdAndIsDefaultTrue(existing.getAccount().getId())
                    .ifPresent(p -> { p.setDefault(false); diskPolicyRepository.save(p); });
        }

        existing.setPolicyName(req.getPolicyName());
        existing.setDesiredUtilization(req.getDesiredUtilization());
        existing.setMinDiskSizeGb(req.getMinDiskSizeGb());
        existing.setMaxDiskCount(req.getMaxDiskCount());
        existing.setPolicyType(req.getPolicyType());
        existing.setTargetDiskType(req.getTargetDiskType());
        existing.setDefault(req.isDefault());
        existing.setUpdatedAt(Instant.now());
        return diskPolicyRepository.save(existing);
    }

    public void deletePolicy(Long policyId) {
        diskPolicyRepository.deleteById(policyId);
    }

    // ─── Action History ───────────────────────────────────────────────────────────

    public List<EbsVolumeAction> getActionHistory(String accountId) {
        return ebsVolumeActionRepository.findByAccountIdOrderByInitiatedAtDesc(resolveAccount(accountId).getId());
    }

    // ─── ROI Calculator ───────────────────────────────────────────────────────────

    public Map<String, Object> calculateRoi(int provisionedGb, double utilizationPct,
                                             double growthRatePct, String diskType) {
        double currentCostPerMonth = calcMonthlyCost(diskType, provisionedGb);

        // Target size: actual used data + growth headroom to reach 75% utilization
        double usedGb = provisionedGb * (utilizationPct / 100.0);
        double targetUtilization = 0.75;
        double targetGb = Math.max(10, usedGb / targetUtilization * (1 + growthRatePct / 100.0));

        // Always recommend gp3 (cheapest general-purpose type)
        String recommendedType = "gp3";
        double newCostPerMonth = calcMonthlyCost(recommendedType, (int) targetGb);

        double monthlySavings = currentCostPerMonth - newCostPerMonth;
        double firstYearSavings = monthlySavings * 12;
        double fiveYearSavings = monthlySavings * 60;
        double savingsPct = currentCostPerMonth > 0 ? (monthlySavings / currentCostPerMonth) * 100 : 0;

        Map<String, Object> result = new HashMap<>();
        result.put("provisionedGb", provisionedGb);
        result.put("utilizationPct", utilizationPct);
        result.put("targetGb", (int) targetGb);
        result.put("currentType", diskType);
        result.put("recommendedType", recommendedType);
        result.put("currentCostPerMonth", Math.round(currentCostPerMonth * 100.0) / 100.0);
        result.put("newCostPerMonth", Math.round(newCostPerMonth * 100.0) / 100.0);
        result.put("monthlySavings", Math.round(monthlySavings * 100.0) / 100.0);
        result.put("savingsPct", Math.round(savingsPct * 100.0) / 100.0);
        result.put("firstYearSavings", Math.round(firstYearSavings * 100.0) / 100.0);
        result.put("fiveYearSavings", Math.round(fiveYearSavings * 100.0) / 100.0);
        return result;
    }

    // ─── Pending Deletion Management ─────────────────────────────────────────────

    public List<Map<String, Object>> getPendingDeletionVolumes(String accountId) {
        CloudAccount account = resolveAccount(accountId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (String region : getEnabledRegions(account)) {
            try {
                Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
                DescribeVolumesResponse resp = ec2.describeVolumes(DescribeVolumesRequest.builder()
                        .filters(Filter.builder().name("tag:XamOps-PendingDeletion").values("true").build())
                        .build());

                for (Volume vol : resp.volumes()) {
                    Map<String, Object> dto = new HashMap<>();
                    dto.put("volumeId", vol.volumeId());
                    dto.put("region", region);
                    dto.put("volumeType", vol.volumeTypeAsString());
                    dto.put("sizeGb", vol.size());
                    dto.put("state", vol.stateAsString());
                    dto.put("monthlyCost", Math.round(calcMonthlyCost(vol.volumeTypeAsString(), vol.size()) * 100.0) / 100.0);

                    String deleteAfter = vol.tags().stream()
                            .filter(t -> "XamOps-DeleteAfter".equals(t.key()))
                            .map(Tag::value).findFirst().orElse(null);
                    dto.put("deleteAfter", deleteAfter);

                    String sourceVolumeId = vol.tags().stream()
                            .filter(t -> "XamOps-Shrink-Source".equals(t.key()))
                            .map(Tag::value).findFirst().orElse(null);
                    dto.put("sourceVolumeId", sourceVolumeId);

                    result.add(dto);
                }
            } catch (Exception e) {
                logger.warn("[EBS] Failed to scan pending-deletion volumes in region {}: {}", region, e.getMessage());
            }
        }
        return result;
    }

    public Map<String, Object> deletePendingVolume(String accountId, String volumeId, String region) {
        CloudAccount account = resolveAccount(accountId);
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

        DescribeVolumesResponse descResp = ec2.describeVolumes(
                DescribeVolumesRequest.builder().volumeIds(volumeId).build());
        if (descResp.volumes().isEmpty()) {
            return Map.of("success", false, "error", "Volume not found: " + volumeId);
        }
        Volume vol = descResp.volumes().get(0);
        boolean isPendingDeletion = vol.tags().stream()
                .anyMatch(t -> "XamOps-PendingDeletion".equals(t.key()) && "true".equals(t.value()));
        if (!isPendingDeletion) {
            return Map.of("success", false, "error", "Volume is not tagged for pending deletion");
        }
        if (!"available".equals(vol.stateAsString())) {
            return Map.of("success", false, "error", "Volume state is " + vol.stateAsString() + ", must be available to delete");
        }

        ec2.deleteVolume(DeleteVolumeRequest.builder().volumeId(volumeId).build());
        redisCacheService.evict(CACHE_KEY_VOLUMES + accountId);
        logger.info("[EBS] Permanently deleted pending-deletion volume {} in {}", volumeId, region);
        return Map.of("success", true, "message", "Volume " + volumeId + " permanently deleted");
    }

    public Map<String, Object> keepVolume(String accountId, String volumeId, String region) {
        CloudAccount account = resolveAccount(accountId);
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

        ec2.deleteTags(DeleteTagsRequest.builder()
                .resources(volumeId)
                .tags(Tag.builder().key("XamOps-PendingDeletion").build(),
                      Tag.builder().key("XamOps-DeleteAfter").build())
                .build());

        redisCacheService.evict(CACHE_KEY_VOLUMES + accountId);
        logger.info("[EBS] Kept volume {} in {} (removed pending deletion tags)", volumeId, region);
        return Map.of("success", true, "message", "Volume " + volumeId + " restored — pending deletion tags removed");
    }

    // ─── Shrink (Data Volume) ─────────────────────────────────────────────────────

    /**
     * Initiates a data-volume downsize via SSM rsync workflow.
     * Returns immediately after creating the action record — actual work runs async.
     *
     * Prerequisites:
     *  - SSM Agent must be running on the instance
     *  - Volume must be a data volume (non-root) in state "in-use"
     *  - targetSizeGb must be >= actual used bytes on the volume
     */
    public Map<String, Object> initiateDownsize(String accountId, String volumeId, String region,
                                                 String instanceId, String mountPoint, int targetSizeGb) {
        CloudAccount account = resolveAccount(accountId);

        // Validate volume is not root
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        DescribeVolumesResponse descResp = ec2.describeVolumes(
                DescribeVolumesRequest.builder().volumeIds(volumeId).build());
        if (descResp.volumes().isEmpty()) {
            return Map.of("success", false, "error", "Volume not found: " + volumeId);
        }
        Volume vol = descResp.volumes().get(0);
        if (vol.attachments().stream().anyMatch(a ->
                a.device() != null && (a.device().contains("sda1") || a.device().contains("xvda")))) {
            return Map.of("success", false, "error",
                    "Root volume downsize requires instance stop. Use the guided root-volume workflow instead.");
        }
        if (targetSizeGb >= vol.size()) {
            return Map.of("success", false, "error",
                    "Target size must be smaller than current size (" + vol.size() + " GB)");
        }

        EbsVolumeAction action = new EbsVolumeAction();
        action.setAccount(account);
        action.setVolumeId(volumeId);
        action.setRegion(region);
        action.setActionType("SHRINK_DATA_VOLUME");
        action.setOldType(vol.volumeTypeAsString());
        action.setNewType(vol.volumeTypeAsString());
        action.setOldSizeGb(vol.size());
        action.setNewSizeGb(targetSizeGb);
        action.setEstimatedSavings(calcMonthlyCost(vol.volumeTypeAsString(), vol.size())
                - calcMonthlyCost(vol.volumeTypeAsString(), targetSizeGb));
        action.setStatus("PENDING");
        action.setInitiatedAt(Instant.now());
        EbsVolumeAction saved = ebsVolumeActionRepository.save(action);

        // Fire-and-forget async workflow (delegated to separate bean to preserve @Async proxy)
        shrinkExecutorService.executeShrinkWorkflow(saved.getId(), account.getId(), volumeId, region, instanceId, mountPoint, targetSizeGb);

        return Map.of("success", true, "actionId", saved.getId(),
                "message", "Shrink workflow initiated. Track progress in Actions History.");
    }

    // ─── Volume Monitoring (CloudWatch native EBS metrics) ────────────────────────

    /**
     * Fetches native AWS/EBS CloudWatch metrics for a volume (no agent required).
     * Returns raw time-series arrays; the frontend computes derived metrics (latency, throughput/s etc).
     *
     * @param hours  look-back window in hours (1, 3, 12, 24, 72, 168)
     */
    public Map<String, Object> getVolumeMonitoring(String accountId, String volumeId, String region, int hours) {
        CloudAccount account = resolveAccount(accountId);
        CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, region);

        // Choose period to keep datapoints reasonable (≤ ~300 points)
        int period;
        if      (hours <= 1)   period = 60;
        else if (hours <= 3)   period = 60;
        else if (hours <= 12)  period = 300;
        else if (hours <= 24)  period = 600;
        else if (hours <= 72)  period = 1800;
        else                   period = 3600;

        Instant end   = Instant.now();
        Instant start = end.minus(Duration.ofHours(hours));

        Dimension volDim = Dimension.builder().name("VolumeId").value(volumeId).build();

        // metric-id → [metricName, stat]
        String[][] defs = {
            { "readBytes",      "VolumeReadBytes",             "Sum"     },
            { "writeBytes",     "VolumeWriteBytes",            "Sum"     },
            { "readOps",        "VolumeReadOps",               "Sum"     },
            { "writeOps",       "VolumeWriteOps",              "Sum"     },
            { "totalReadTime",  "VolumeTotalReadTime",         "Sum"     },
            { "totalWriteTime", "VolumeTotalWriteTime",        "Sum"     },
            { "queueLength",    "VolumeQueueLength",           "Average" },
            { "idleTime",       "VolumeIdleTime",              "Sum"     },
            { "burstBalance",   "BurstBalance",                "Average" },
            { "consumedOps",    "VolumeConsumedReadWriteOps",  "Sum"     },
        };

        List<MetricDataQuery> queries = new ArrayList<>();
        for (String[] d : defs) {
            queries.add(MetricDataQuery.builder()
                    .id(d[0])
                    .metricStat(MetricStat.builder()
                            .metric(Metric.builder()
                                    .namespace("AWS/EBS")
                                    .metricName(d[1])
                                    .dimensions(volDim)
                                    .build())
                            .period(period)
                            .stat(d[2])
                            .build())
                    .build());
        }

        GetMetricDataResponse resp = cw.getMetricData(GetMetricDataRequest.builder()
                .metricDataQueries(queries)
                .startTime(start)
                .endTime(end)
                .scanBy(ScanBy.TIMESTAMP_ASCENDING)
                .build());

        Map<String, Object> metrics = new HashMap<>();
        for (MetricDataResult mdr : resp.metricDataResults()) {
            List<Map<String, Object>> points = new ArrayList<>();
            List<Instant> timestamps = mdr.timestamps();
            List<Double>  values     = mdr.values();
            for (int i = 0; i < Math.min(timestamps.size(), values.size()); i++) {
                Map<String, Object> p = new HashMap<>();
                p.put("ts", timestamps.get(i).toEpochMilli());
                p.put("v",  values.get(i));
                points.add(p);
            }
            if (!points.isEmpty()) metrics.put(mdr.id(), points);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("volumeId", volumeId);
        result.put("region",   region);
        result.put("period",   period);
        result.put("metrics",  metrics);
        return result;
    }

    // ─── SSM Disk Usage ──────────────────────────────────────────────────────────

    /**
     * Fetches real-time filesystem disk usage via SSM RunCommand (df -BG).
     * Does NOT require CloudWatch agent — only SSM agent (pre-installed on Amazon Linux 2+/Ubuntu).
     * Runs df on all instances in parallel within one SendCommand call, then polls results.
     *
     * @return instanceId → { "/dev/xvda1" → { usedGb, freeGb, usedPercent } }
     */
    /**
     * Returns:
     *   instanceId → { "/dev/xvda1" → { usedGb, freeGb, totalGb, usedPercent } }
     *   "__ssmUnavailable" → [ instanceId, ... ]   (no SSM agent / no IAM permission)
     *   "__ssmAvailable"   → [ instanceId, ... ]   (SSM online, command was sent)
     */
    public Map<String, Object> getDiskUsageViaSSM(String accountId, String region, List<String> instanceIds) {
        CloudAccount account = resolveAccount(accountId);
        Map<String, Object> result = new HashMap<>();
        if (instanceIds == null || instanceIds.isEmpty()) return result;

        List<String> ssmAvailable   = new ArrayList<>();
        List<String> ssmUnavailable = new ArrayList<>(instanceIds); // assume all unavailable until proven otherwise

        try {
            SsmClient ssm = awsClientProvider.getSsmClient(account, region);

            // ── Step 1: find which instances are actually registered with SSM ──────────
            try {
                DescribeInstanceInformationResponse descResp = ssm.describeInstanceInformation(
                        DescribeInstanceInformationRequest.builder()
                                .filters(InstanceInformationStringFilter.builder()
                                        .key("InstanceIds")
                                        .values(instanceIds)
                                        .build())
                                .maxResults(50)
                                .build());

                Set<String> onlineIds = descResp.instanceInformationList().stream()
                        .map(info -> info.instanceId())
                        .collect(Collectors.toSet());

                ssmAvailable   = instanceIds.stream().filter(onlineIds::contains).collect(Collectors.toList());
                ssmUnavailable = instanceIds.stream().filter(id -> !onlineIds.contains(id)).collect(Collectors.toList());

                logger.info("[EBS] SSM check — available: {}, unavailable: {} in region {}",
                        ssmAvailable.size(), ssmUnavailable.size(), region);
            } catch (Exception e) {
                logger.warn("[EBS] DescribeInstanceInformation failed (role may lack ssm:DescribeInstanceInformation): {}", e.getMessage());
                // Fall through: attempt SendCommand on all — some may still respond
                ssmAvailable   = new ArrayList<>(instanceIds);
                ssmUnavailable = new ArrayList<>();
            }

            if (ssmAvailable.isEmpty()) {
                result.put("__ssmUnavailable", ssmUnavailable);
                result.put("__ssmAvailable",   ssmAvailable);
                return result;
            }

            // ── Step 2: run df + NVMe vol-id mapping on SSM-registered instances ──────
            String script =
                "declare -A VMAP; " +
                "for L in /dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_*; do " +
                "  [ -e \"$L\" ] || continue; " +
                "  V=$(basename \"$L\" | sed 's/nvme-Amazon_Elastic_Block_Store_//'); " +
                "  D=$(readlink -f \"$L\"); " +
                "  BASE=$(echo \"$D\" | sed -E 's/p[0-9]+$//'); " +
                "  VMAP[$BASE]=$V; " +
                "done; " +
                "df -BG | grep '^/dev/' | while read DEV TOT USED FREE PCT MP; do " +
                "  BASE=$(echo \"$DEV\" | sed -E 's/p[0-9]+$//'); " +
                "  KEY=${VMAP[$BASE]:-$DEV}; " +
                "  echo \"$KEY ${USED%G} ${TOT%G} ${FREE%G}\"; " +
                "done";

            SendCommandResponse cmdResp = ssm.sendCommand(SendCommandRequest.builder()
                    .instanceIds(ssmAvailable)
                    .documentName("AWS-RunShellScript")
                    .parameters(Map.of("commands", List.of(script)))
                    .timeoutSeconds(30)
                    .build());
            String commandId = cmdResp.command().commandId();

            Thread.sleep(3000); // initial delay for agent pickup

            List<String> pending = new ArrayList<>(ssmAvailable);
            for (int attempt = 0; attempt < 9 && !pending.isEmpty(); attempt++) {
                List<String> stillPending = new ArrayList<>();
                for (String instanceId : pending) {
                    try {
                        GetCommandInvocationResponse invResp = ssm.getCommandInvocation(
                                GetCommandInvocationRequest.builder()
                                        .commandId(commandId)
                                        .instanceId(instanceId)
                                        .build());
                        CommandInvocationStatus status = invResp.status();
                        if (status == CommandInvocationStatus.SUCCESS) {
                            Map<String, Object> parsed = parseDfOutput(invResp.standardOutputContent());
                            if (!parsed.isEmpty()) result.put(instanceId, parsed);
                            logger.debug("[EBS] SSM SUCCESS for {}: {} filesystems found", instanceId, parsed.size());
                        } else if (status == CommandInvocationStatus.FAILED || status == CommandInvocationStatus.TIMED_OUT) {
                            logger.warn("[EBS] SSM command {} for instance {}: stdout={}, stderr={}",
                                    status, instanceId,
                                    invResp.standardOutputContent(),
                                    invResp.standardErrorContent());
                        } else {
                            stillPending.add(instanceId); // IN_PROGRESS / PENDING / DELAYED
                        }
                    } catch (Exception e) {
                        logger.debug("[EBS] SSM poll error for {}: {}", instanceId, e.getMessage());
                    }
                }
                pending = stillPending;
                if (!pending.isEmpty()) Thread.sleep(3000);
            }

            logger.info("[EBS] SSM disk usage complete — {}/{} instances returned data in {}",
                    result.size(), ssmAvailable.size(), region);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("[EBS] SSM disk usage failed for region {}: {}", region, e.getMessage());
            ssmUnavailable = new ArrayList<>(instanceIds);
            ssmAvailable   = new ArrayList<>();
        }

        result.put("__ssmUnavailable", ssmUnavailable);
        result.put("__ssmAvailable",   ssmAvailable);
        return result;
    }

    private Map<String, Object> parseDfOutput(String output) {
        Map<String, Object> deviceMap = new HashMap<>();
        if (output == null) return deviceMap;
        for (String line : output.split("\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;
            try {
                String device = parts[0];
                int usedGb  = Integer.parseInt(parts[1]);
                int totalGb = Integer.parseInt(parts[2]);
                int freeGb  = Integer.parseInt(parts[3]);
                double usedPct = totalGb > 0 ? Math.round((usedGb * 1000.0 / totalGb)) / 10.0 : 0.0;
                Map<String, Object> usage = new HashMap<>();
                usage.put("usedGb", usedGb);
                usage.put("freeGb", freeGb);
                usage.put("totalGb", totalGb);
                usage.put("usedPercent", usedPct);
                deviceMap.put(device, usage);
            } catch (NumberFormatException ignored) {}
        }
        return deviceMap;
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────────

    /**
     * For each instance: looks up its attached IAM instance profile role and attaches
     * AmazonSSMManagedInstanceCore so SSM agent can register.
     * Same approach used by ScaleOps / ScaleFlow.
     */
    public Map<String, Object> attachSsmPolicyToInstances(String accountId, String region, List<String> instanceIds) {
        CloudAccount account = resolveAccount(accountId);
        Ec2Client  ec2 = awsClientProvider.getEc2Client(account, region);
        IamClient  iam = awsClientProvider.getIamClient(account);

        String SSM_POLICY_ARN = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore";

        List<String> fixed    = new ArrayList<>();
        List<String> skipped  = new ArrayList<>();
        List<String> failed   = new ArrayList<>();

        // Get instance-profile associations for all instances at once
        DescribeIamInstanceProfileAssociationsResponse assocResp = ec2.describeIamInstanceProfileAssociations(
                DescribeIamInstanceProfileAssociationsRequest.builder()
                        .filters(Filter.builder().name("instance-id").values(instanceIds).build())
                        .build());

        Map<String, String> instanceToProfileArn = new HashMap<>();
        for (IamInstanceProfileAssociation assoc : assocResp.iamInstanceProfileAssociations()) {
            if ("associated".equals(assoc.stateAsString()) && assoc.iamInstanceProfile() != null) {
                instanceToProfileArn.put(assoc.instanceId(), assoc.iamInstanceProfile().arn());
            }
        }

        for (String instanceId : instanceIds) {
            String profileArn = instanceToProfileArn.get(instanceId);
            if (profileArn == null) {
                logger.warn("[SSM-Fix] Instance {} has no IAM instance profile attached — skipping", instanceId);
                skipped.add(instanceId);
                continue;
            }

            try {
                // ARN format: arn:aws:iam::123456789012:instance-profile/MyProfile
                String profileName = profileArn.substring(profileArn.lastIndexOf('/') + 1);

                InstanceProfile profile = iam.getInstanceProfile(
                        GetInstanceProfileRequest.builder().instanceProfileName(profileName).build())
                        .instanceProfile();

                if (profile.roles().isEmpty()) {
                    logger.warn("[SSM-Fix] Instance profile {} for instance {} has no roles", profileName, instanceId);
                    skipped.add(instanceId);
                    continue;
                }

                String roleName = profile.roles().get(0).roleName();

                iam.attachRolePolicy(AttachRolePolicyRequest.builder()
                        .roleName(roleName)
                        .policyArn(SSM_POLICY_ARN)
                        .build());

                logger.info("[SSM-Fix] Attached AmazonSSMManagedInstanceCore to role {} (instance {})", roleName, instanceId);
                fixed.add(instanceId);

            } catch (NoSuchEntityException e) {
                logger.warn("[SSM-Fix] IAM entity not found for instance {}: {}", instanceId, e.getMessage());
                failed.add(instanceId);
            } catch (Exception e) {
                logger.error("[SSM-Fix] Failed for instance {}: {}", instanceId, e.getMessage());
                failed.add(instanceId);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", !fixed.isEmpty());
        result.put("fixed",   fixed);
        result.put("skipped", skipped);
        result.put("failed",  failed);
        result.put("message", String.format(
                "Attached SSM policy to %d instance role(s). SSM agent will register within ~1 minute.",
                fixed.size()));
        return result;
    }

    /**
     * Returns the sum of estimatedSavings for all actionable EBS recommendations
     * for the given AWS account ID. Reads only from cache — never triggers a live scan.
     */
    public double getTotalPotentialSavings(String awsAccountId) {
        if (awsAccountId == null || awsAccountId.isBlank()) return 0.0;
        Optional<List<Map<String, Object>>> cached = redisCacheService.get(
                CACHE_KEY_VOLUMES + awsAccountId,
                new TypeReference<List<Map<String, Object>>>() {});
        if (cached.isEmpty()) return 0.0;
        return cached.get().stream()
                .filter(v -> !"NONE".equals(v.get("recommendation")))
                .mapToDouble(v -> {
                    Object s = v.get("estimatedSavings");
                    return s instanceof Number ? ((Number) s).doubleValue() : 0.0;
                })
                .sum();
    }

    private CloudAccount resolveAccount(String providerAccountId) {
        return cloudAccountRepository.findByProviderAccountId(providerAccountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + providerAccountId));
    }

    private List<String> getEnabledRegions(CloudAccount account) {
        try {
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, "us-east-1");
            DescribeRegionsResponse resp = ec2.describeRegions(
                    DescribeRegionsRequest.builder().allRegions(false).build());
            return resp.regions().stream()
                    .map(Region::regionName)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.warn("Could not fetch regions dynamically, using defaults: {}", e.getMessage());
            return List.of("us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1");
        }
    }

    private List<Map<String, Object>> describeVolumesInRegion(CloudAccount account, String region) {
        Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);
        List<Map<String, Object>> result = new ArrayList<>();

        String nextToken = null;
        do {
            DescribeVolumesRequest.Builder reqBuilder = DescribeVolumesRequest.builder().maxResults(500);
            if (nextToken != null) reqBuilder.nextToken(nextToken);
            DescribeVolumesResponse resp = ec2.describeVolumes(reqBuilder.build());

            for (Volume vol : resp.volumes()) {
                Map<String, Object> dto = buildVolumeDto(vol, region, account);
                result.add(dto);
            }
            nextToken = resp.nextToken();
        } while (nextToken != null);

        enrichWithDiskMetrics(account, region, result);
        updateRecommendationsWithUsage(result);
        enrichWithComputeOptimizerRecommendations(account, region, result);
        enrichWithAsgNames(ec2, result);
        return result;
    }

    // ─── ASG Name Enrichment ────────────────────────────────────────────────────

    void enrichWithAsgNames(Ec2Client ec2, List<Map<String, Object>> volumes) {
        List<String> instanceIds = volumes.stream()
                .filter(v -> v.get("instanceId") != null)
                .map(v -> (String) v.get("instanceId"))
                .distinct()
                .collect(Collectors.toList());

        if (instanceIds.isEmpty()) {
            return;
        }

        try {
            // AWS SDK v2 DescribeInstances accepts up to 1000 IDs; beyond that the call fails and
            // we degrade silently (consistent with other enrichment methods in this class).
            DescribeInstancesResponse resp = ec2.describeInstances(
                    DescribeInstancesRequest.builder().instanceIds(instanceIds).build());

            Map<String, String> instanceToAsg = new HashMap<>();
            for (Reservation reservation : resp.reservations()) {
                for (Instance instance : reservation.instances()) {
                    instance.tags().stream()
                            .filter(tag -> "aws:autoscaling:groupName".equals(tag.key()))
                            .findFirst()
                            .ifPresent(tag -> instanceToAsg.put(instance.instanceId(), tag.value()));
                }
            }

            for (Map<String, Object> vol : volumes) {
                String instanceId = (String) vol.get("instanceId");
                if (instanceId != null && instanceToAsg.containsKey(instanceId)) {
                    vol.put("asgName", instanceToAsg.get(instanceId));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to enrich volumes with ASG names: {}", e.getMessage());
        }
    }

    /**
     * After disk-usage enrichment, refines recommendations using actual utilization:
     *  - usedPercent > 80  → EXPAND  (disk is nearly full — urgent)
     *  - usedPercent < 25  → SHRINK  (volume is heavily over-provisioned)
     */
    private void updateRecommendationsWithUsage(List<Map<String, Object>> volumes) {
        for (Map<String, Object> vol : volumes) {
            if (!vol.containsKey("usedPercent")) continue;

            double usedPct    = ((Number) vol.get("usedPercent")).doubleValue();
            String  currentRec = (String) vol.get("recommendation");
            String  state      = (String) vol.get("state");
            String  volumeType = (String) vol.get("volumeType");
            int     sizeGb     = ((Number) vol.get("sizeGb")).intValue();
            boolean isRoot     = Boolean.TRUE.equals(vol.get("isRootVolume"));

            if (!"in-use".equals(state)) continue;

            // Near-full → EXPAND (overrides UPGRADE_TYPE; disk full is more urgent)
            if (usedPct > 80) {
                int targetSizeGb = (int) Math.ceil(sizeGb * 1.5);
                vol.put("recommendation",       "EXPAND");
                vol.put("recommendationReason",
                    String.format("Volume is %.0f%% full — expand from %dGB to %dGB to prevent disruption",
                            usedPct, sizeGb, targetSizeGb));
                vol.put("recommendedSizeGb",  targetSizeGb);
                vol.put("estimatedSavings",   0.0);
                continue;
            }

            // Underutilized → SHRINK (root volumes included — migrate to smaller replacement volume)
            if (usedPct < 25 && sizeGb >= 20 && "NONE".equals(currentRec)) {
                double usedGb       = sizeGb * usedPct / 100.0;
                int    targetSizeGb = Math.max(10, (int) Math.ceil(usedGb / 0.40));
                if (targetSizeGb < (int)(sizeGb * 0.70)) {
                    double savings = calcMonthlyCost(volumeType, sizeGb) - calcMonthlyCost(volumeType, targetSizeGb);
                    String reason = isRoot
                        ? String.format("Root volume is only %.0f%% used — migrate to new %dGB volume to save $%.2f/mo (requires instance stop)", usedPct, targetSizeGb, savings)
                        : String.format("Volume is only %.0f%% used — shrink from %dGB to %dGB to save $%.2f/mo", usedPct, sizeGb, targetSizeGb, savings);
                    vol.put("recommendation",      "SHRINK");
                    vol.put("recommendationReason", reason);
                    vol.put("recommendedSizeGb", targetSizeGb);
                    vol.put("estimatedSavings",  Math.round(savings * 100.0) / 100.0);
                }
            }
        }
    }

    /**
     * For attached volumes, resolves disk_used_percent from (in priority order):
     *  1. XamOps disk agent data cached in Redis (no SSM/IAM required)
     *  2. CloudWatch agent metrics (CWAgent namespace)
     * Silently skips if neither source has data for an instance.
     */
    private void enrichWithDiskMetrics(CloudAccount account, String region, List<Map<String, Object>> volumes) {
        Set<String> instanceIds = new HashSet<>();
        for (Map<String, Object> v : volumes) {
            if (v.get("instanceId") != null) instanceIds.add((String) v.get("instanceId"));
        }
        if (instanceIds.isEmpty()) return;

        // 1. Try agent-reported data first (no SSM/IAM needed)
        Set<String> instancesWithoutData = enrichFromDiskAgent(account, volumes, instanceIds);

        // 2. Fall back to CloudWatch for any instances the agent hasn't reported yet
        if (!instancesWithoutData.isEmpty()) {
            Map<String, Map<String, Double>> cwMetrics = fetchDiskMetrics(account, region, instancesWithoutData);
            applyDiskMetrics(volumes, cwMetrics, instancesWithoutData);
        }
    }

    /**
     * Checks Redis for disk agent data for each instance and applies it to the volume DTOs.
     * Returns the set of instanceIds that had NO agent data (so CloudWatch fallback can run for them).
     */
    private Set<String> enrichFromDiskAgent(CloudAccount account, List<Map<String, Object>> volumes,
                                             Set<String> instanceIds) {
        Set<String> noAgentData = new HashSet<>(instanceIds);
        String awsAccountId = account.getAwsAccountId();
        logger.info("[disk-agent-debug] enrichFromDiskAgent called, awsAccountId={}, instanceIds={}", awsAccountId, instanceIds);
        if (awsAccountId == null) {
            logger.warn("[disk-agent-debug] awsAccountId is null — skipping agent enrichment");
            return noAgentData;
        }

        for (Map<String, Object> vol : volumes) {
            String instanceId = (String) vol.get("instanceId");
            String device = (String) vol.get("device");
            if (instanceId == null || device == null) continue;

            String cacheKey = "ebs-disk-agent:" + awsAccountId + ":" + instanceId;
            Optional<List<Map<String, Object>>> agentData = redisCacheService.get(
                    cacheKey, new TypeReference<List<Map<String, Object>>>() {});
            logger.info("[disk-agent-debug] key={} found={}", cacheKey, agentData.isPresent());
            if (agentData.isEmpty()) {
                // Data gap (e.g. EBS resize in progress) — check sentinel to distinguish "never installed" from "temporarily unavailable"
                String seenKey = "ebs-disk-agent-seen:" + awsAccountId + ":" + instanceId;
                Optional<Boolean> seen = redisCacheService.get(seenKey, new TypeReference<Boolean>() {});
                if (seen.isPresent()) {
                    vol.put("agentDeployed", true);
                    logger.info("[disk-agent-debug] sentinel hit for instance={} — marking agentDeployed but no fresh data", instanceId);
                }
                continue;
            }

            String volVolumeId = (String) vol.get("volumeId");
            String baseDevice = device.replaceAll("^/dev/", "");
            boolean isRootVol = Boolean.TRUE.equals(vol.get("isRootVolume"));

            // Find best matching mount from agent data
            Map<String, Object> bestMount = null;
            for (Map<String, Object> mount : agentData.get()) {
                // 1. volumeId match (most reliable when NVMe serial is readable)
                String mountVolumeId = (String) mount.get("volumeId");
                if (volVolumeId != null && !volVolumeId.isEmpty()
                        && mountVolumeId != null && volVolumeId.equals(mountVolumeId)) {
                    bestMount = mount;
                    break;
                }
                // 2. Device prefix match (Xen/xvd* devices)
                String mountDevice = (String) mount.get("device");
                String baseMountDevice = mountDevice != null ? mountDevice.replaceAll("^/dev/", "") : "";
                if (!baseMountDevice.isEmpty() && !baseMountDevice.equals("root") &&
                        (baseMountDevice.startsWith(baseDevice) || baseDevice.startsWith(baseMountDevice))) {
                    bestMount = mount;
                    break;
                }
                // 3. Root volume fallback: use the "/" mount point
                // (handles Ubuntu where root is /dev/root symlink, or NVMe where serial is unreadable)
                String mountPoint = (String) mount.get("mountPoint");
                if (isRootVol && "/".equals(mountPoint) && bestMount == null) {
                    bestMount = mount;
                    // don't break — keep looking for a better volumeId/device match
                }
            }

            if (bestMount != null) {
                Number usedPct = (Number) bestMount.get("usedPercent");
                if (usedPct != null) {
                    int sizeGb = ((Number) vol.get("sizeGb")).intValue();
                    double up = usedPct.doubleValue();
                    vol.put("agentDeployed", true);
                    vol.put("usedPercent", Math.round(up * 10.0) / 10.0);
                    vol.put("usedGb",  Math.round(sizeGb * up / 100.0 * 100.0) / 100.0);
                    vol.put("freeGb",  Math.round(sizeGb * (1 - up / 100.0) * 100.0) / 100.0);
                    noAgentData.remove(instanceId);
                    logger.info("[disk-agent-debug] matched vol={} instance={} usedPct={}", vol.get("volumeId"), instanceId, up);
                }
            }
        }
        return noAgentData;
    }

    /** Applies a CloudWatch metrics map to volumes whose instanceIds are in {@code targetInstances}. */
    private void applyDiskMetrics(List<Map<String, Object>> volumes,
                                   Map<String, Map<String, Double>> diskMetrics,
                                   Set<String> targetInstances) {
        if (diskMetrics.isEmpty()) return;
        for (Map<String, Object> vol : volumes) {
            String instanceId = (String) vol.get("instanceId");
            String device = (String) vol.get("device");
            if (instanceId == null || device == null || !targetInstances.contains(instanceId)) continue;

            Map<String, Double> instanceMetrics = diskMetrics.get(instanceId);
            if (instanceMetrics == null) continue;

            String baseDevice = device.replaceAll("^/dev/", "");
            Double usedPct = null;
            for (Map.Entry<String, Double> entry : instanceMetrics.entrySet()) {
                String cwDevice = entry.getKey();
                if (cwDevice.startsWith(baseDevice) || baseDevice.startsWith(cwDevice)) {
                    usedPct = entry.getValue();
                    break;
                }
            }
            if (usedPct != null) {
                int sizeGb = ((Number) vol.get("sizeGb")).intValue();
                double usedGb = sizeGb * usedPct / 100.0;
                double freeGb = sizeGb - usedGb;
                vol.put("usedPercent", Math.round(usedPct * 10.0) / 10.0);
                vol.put("usedGb",  Math.round(usedGb * 100.0) / 100.0);
                vol.put("freeGb",  Math.round(freeGb * 100.0) / 100.0);
            }
        }
    }

    /**
     * Fetches disk_used_percent metrics from CWAgent namespace for the given instances
     * using a single batched GetMetricData call.
     * Returns: instanceId → { cwDevice → usedPercent }
     */
    private Map<String, Map<String, Double>> fetchDiskMetrics(CloudAccount account, String region, Set<String> instanceIds) {
        Map<String, Map<String, Double>> result = new HashMap<>();
        try {
            CloudWatchClient cw = awsClientProvider.getCloudWatchClient(account, region);

            // ListMetrics results are cached per account+region to avoid repeated pagination
            // (CWAgent metric definitions rarely change — same TTL as the volume cache)
            String listCacheKey = "cwagent-disk-metrics-list:" + account.getAwsAccountId() + ":" + region;
            List<Map<String, Object>> cachedList = redisCacheService
                    .get(listCacheKey, new TypeReference<List<Map<String, Object>>>() {})
                    .orElse(null);

            List<Metric> relevantMetrics = new ArrayList<>();
            if (cachedList != null) {
                // Rebuild Metric objects from cached dimension maps, filtered to requested instances
                for (Map<String, Object> entry : cachedList) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> dims = (List<Map<String, String>>) entry.get("dimensions");
                    if (dims == null) continue;
                    boolean matchesInstance = dims.stream()
                            .anyMatch(d -> "InstanceId".equals(d.get("name")) && instanceIds.contains(d.get("value")));
                    if (!matchesInstance) continue;
                    List<Dimension> dimensionObjs = dims.stream()
                            .map(d -> Dimension.builder().name(d.get("name")).value(d.get("value")).build())
                            .collect(Collectors.toList());
                    relevantMetrics.add(Metric.builder()
                            .namespace("CWAgent").metricName("disk_used_percent")
                            .dimensions(dimensionObjs).build());
                }
            } else {
                // Cold path: run ListMetrics and cache the results
                List<Map<String, Object>> allMetricDims = new ArrayList<>();
                String nextToken = null;
                do {
                    ListMetricsRequest.Builder reqBuilder = ListMetricsRequest.builder()
                            .namespace("CWAgent")
                            .metricName("disk_used_percent")
                            .dimensions(DimensionFilter.builder().name("InstanceId").build());
                    if (nextToken != null) reqBuilder.nextToken(nextToken);
                    ListMetricsResponse listResp = cw.listMetrics(reqBuilder.build());
                    for (Metric m : listResp.metrics()) {
                        // Store dimensions as plain maps for serialisation
                        List<Map<String, String>> dimMaps = m.dimensions().stream()
                                .map(d -> Map.of("name", d.name(), "value", d.value()))
                                .collect(Collectors.toList());
                        allMetricDims.add(Map.of("dimensions", dimMaps));
                        boolean matches = m.dimensions().stream()
                                .anyMatch(d -> "InstanceId".equals(d.name()) && instanceIds.contains(d.value()));
                        if (matches) relevantMetrics.add(m);
                    }
                    nextToken = listResp.nextToken();
                } while (nextToken != null);
                redisCacheService.put(listCacheKey, allMetricDims, CACHE_TTL_MINUTES);
            }

            if (relevantMetrics.isEmpty()) {
                logger.debug("[EBS] No CWAgent disk_used_percent metrics found for instances in {}", region);
                return result;
            }

            // Batch fetch via GetMetricData (up to 500 queries per call)
            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofHours(3));
            Map<String, Metric> idToMetric = new LinkedHashMap<>();
            for (int i = 0; i < relevantMetrics.size(); i++) {
                idToMetric.put("m" + i, relevantMetrics.get(i));
            }

            List<MetricDataQuery> queries = new ArrayList<>();
            for (Map.Entry<String, Metric> e : idToMetric.entrySet()) {
                queries.add(MetricDataQuery.builder()
                        .id(e.getKey())
                        .metricStat(MetricStat.builder()
                                .metric(e.getValue())
                                .period(3600)
                                .stat("Average")
                                .build())
                        .build());
            }

            GetMetricDataResponse dataResp = cw.getMetricData(GetMetricDataRequest.builder()
                    .metricDataQueries(queries)
                    .startTime(start)
                    .endTime(end)
                    .build());

            for (MetricDataResult mdr : dataResp.metricDataResults()) {
                Metric m = idToMetric.get(mdr.id());
                if (m == null || mdr.values().isEmpty()) continue;

                String instanceId = m.dimensions().stream()
                        .filter(d -> "InstanceId".equals(d.name()))
                        .map(d -> d.value()).findFirst().orElse(null);
                String cwDevice = m.dimensions().stream()
                        .filter(d -> "device".equals(d.name()))
                        .map(d -> d.value()).findFirst().orElse(null);

                if (instanceId == null || cwDevice == null) continue;

                double avg = mdr.values().stream().mapToDouble(Double::doubleValue).average().orElse(-1);
                if (avg >= 0) {
                    result.computeIfAbsent(instanceId, k -> new HashMap<>()).put(cwDevice, avg);
                }
            }

            logger.debug("[EBS] CloudWatch disk metrics fetched: {} metrics for {} instances in {}",
                    relevantMetrics.size(), instanceIds.size(), region);
        } catch (Exception e) {
            logger.warn("[EBS] CloudWatch disk metrics unavailable for region {}: {}", region, e.getMessage());
        }
        return result;
    }

    private Map<String, Object> buildVolumeDto(Volume vol, String region, CloudAccount account) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("volumeId", vol.volumeId());
        dto.put("region", region);
        dto.put("volumeType", vol.volumeTypeAsString());
        dto.put("sizeGb", vol.size());
        dto.put("state", vol.stateAsString());
        dto.put("encrypted", vol.encrypted());
        dto.put("multiAttach", vol.multiAttachEnabled());

        // Name tag
        String name = vol.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .findFirst().orElse(null);
        dto.put("volumeName", name);

        // Attachment info
        boolean attached = !vol.attachments().isEmpty();
        dto.put("attached", attached);
        if (attached) {
            VolumeAttachment att = vol.attachments().get(0);
            dto.put("instanceId", att.instanceId());
            dto.put("device", att.device());
            dto.put("isRootVolume", att.device() != null && att.device().contains("xvda") || att.device() != null && att.device().contains("sda"));
        }

        // Cost
        double monthlyCost = calcMonthlyCost(vol.volumeTypeAsString(), vol.size());
        dto.put("monthlyCost", Math.round(monthlyCost * 100.0) / 100.0);

        // Recommendation logic
        buildRecommendation(dto, vol);

        return dto;
    }

    private void buildRecommendation(Map<String, Object> dto, Volume vol) {
        String volumeType = vol.volumeTypeAsString();
        String state = vol.stateAsString();
        int sizeGb = vol.size();

        // 1. Unattached volume → delete
        if ("available".equals(state)) {
            dto.put("recommendation", "DELETE_UNATTACHED");
            dto.put("recommendationReason", "Volume is not attached to any instance");
            dto.put("estimatedSavings", Math.round(calcMonthlyCost(volumeType, sizeGb) * 100.0) / 100.0);
            dto.put("recommendedType", volumeType);
            dto.put("recommendedSizeGb", sizeGb);
            return;
        }

        // 2. gp2 → gp3 upgrade (always beneficial, same or better performance)
        if ("gp2".equals(volumeType)) {
            double savings = (GP2_PRICE_PER_GB - GP3_PRICE_PER_GB) * sizeGb;
            dto.put("recommendation", "UPGRADE_TYPE");
            dto.put("recommendationReason", "gp3 offers same performance with 20% lower cost");
            dto.put("recommendedType", "gp3");
            dto.put("recommendedSizeGb", sizeGb);
            dto.put("estimatedSavings", Math.round(savings * 100.0) / 100.0);
            return;
        }

        // 3. io1 volumes — recommend downgrade to gp3 if IOPS are low
        // (Without CloudWatch data here, we flag io1 volumes under 100GB as likely candidates)
        if ("io1".equals(volumeType) && sizeGb <= 100) {
            double savings = (IO1_PRICE_PER_GB - GP3_PRICE_PER_GB) * sizeGb;
            dto.put("recommendation", "UPGRADE_TYPE");
            dto.put("recommendationReason", "io1 volume may be over-provisioned for workload size; consider gp3");
            dto.put("recommendedType", "gp3");
            dto.put("recommendedSizeGb", sizeGb);
            dto.put("estimatedSavings", Math.round(savings * 100.0) / 100.0);
            return;
        }

        // 4. No recommendation
        dto.put("recommendation", "NONE");
        dto.put("recommendationReason", null);
        dto.put("estimatedSavings", 0.0);
        dto.put("recommendedType", volumeType);
        dto.put("recommendedSizeGb", sizeGb);
    }

    /**
     * Calls AWS Compute Optimizer GetEBSVolumeRecommendations as a fallback to fill in
     * SHRINK/UPGRADE_TYPE recommendations for volumes that don't have CWAgent or SSM disk usage.
     * Only overwrites recommendation=NONE — won't override SSM/CWAgent-derived SHRINK/EXPAND.
     */
    private void enrichWithComputeOptimizerRecommendations(CloudAccount account, String region,
                                                            List<Map<String, Object>> volumes) {
        // Collect volume ARNs for volumes that still have recommendation=NONE (no SSM/CWAgent data)
        List<String> volumeArns = volumes.stream()
                .filter(v -> "NONE".equals(v.get("recommendation")))
                .map(v -> String.format("arn:aws:ec2:%s:%s:volume/%s",
                        region, account.getAwsAccountId(), v.get("volumeId")))
                .collect(Collectors.toList());

        if (volumeArns.isEmpty()) return;

        try {
            ComputeOptimizerClient co = awsClientProvider.getComputeOptimizerClient(account, region);

            // Build a volumeId → dto lookup map
            Map<String, Map<String, Object>> volById = new HashMap<>();
            for (Map<String, Object> v : volumes) {
                volById.put((String) v.get("volumeId"), v);
            }

            // Paginate — CO allows up to 1000 ARNs per call
            int pageSize = 1000;
            for (int i = 0; i < volumeArns.size(); i += pageSize) {
                List<String> batch = volumeArns.subList(i, Math.min(i + pageSize, volumeArns.size()));

                GetEbsVolumeRecommendationsRequest req = GetEbsVolumeRecommendationsRequest.builder()
                        .volumeArns(batch)
                        .build();
                GetEbsVolumeRecommendationsResponse resp = co.getEBSVolumeRecommendations(req);

                for (VolumeRecommendation rec : resp.volumeRecommendations()) {
                    // Extract volumeId from ARN (last segment)
                    String arn = rec.volumeArn();
                    String volId = arn.substring(arn.lastIndexOf('/') + 1);
                    Map<String, Object> dto = volById.get(volId);
                    if (dto == null) continue;

                    EBSFinding finding = rec.finding();
                    if (finding == null || finding == EBSFinding.OPTIMIZED) continue;

                    if (!rec.volumeRecommendationOptions().isEmpty()) {
                        VolumeRecommendationOption opt = rec.volumeRecommendationOptions().get(0);
                        VolumeConfiguration cfg = opt.configuration();
                        if (cfg == null) continue;

                        int currentSizeGb = ((Number) dto.get("sizeGb")).intValue();
                        String currentType = (String) dto.get("volumeType");
                        int recommendedSizeGb  = cfg.volumeSize() != null ? cfg.volumeSize() : currentSizeGb;
                        String recommendedType = cfg.volumeType() != null ? cfg.volumeType() : currentType;

                        double currentCost  = calcMonthlyCost(currentType, currentSizeGb);
                        double recommendedCost = calcMonthlyCost(recommendedType, recommendedSizeGb);
                        double savings = currentCost - recommendedCost;

                        if (recommendedSizeGb < currentSizeGb && savings > 0) {
                            boolean isRoot = Boolean.TRUE.equals(dto.get("isRootVolume"));
                            String reason = isRoot
                                ? String.format("Compute Optimizer: root volume is over-provisioned — migrate to %dGB %s to save $%.2f/mo (requires instance stop)", recommendedSizeGb, recommendedType, savings)
                                : String.format("Compute Optimizer: volume is over-provisioned — shrink from %dGB to %dGB %s to save $%.2f/mo", currentSizeGb, recommendedSizeGb, recommendedType, savings);
                            dto.put("recommendation",      "SHRINK");
                            dto.put("recommendationReason", reason);
                            dto.put("recommendedSizeGb",   recommendedSizeGb);
                            dto.put("recommendedType",     recommendedType);
                            dto.put("estimatedSavings",    Math.round(savings * 100.0) / 100.0);
                        } else if (!recommendedType.equals(currentType) && savings > 0) {
                            String reason = String.format("Compute Optimizer: switch from %s to %s to save $%.2f/mo", currentType, recommendedType, savings);
                            dto.put("recommendation",      "UPGRADE_TYPE");
                            dto.put("recommendationReason", reason);
                            dto.put("recommendedSizeGb",   recommendedSizeGb);
                            dto.put("recommendedType",     recommendedType);
                            dto.put("estimatedSavings",    Math.round(savings * 100.0) / 100.0);
                        }
                    }
                }
            }
            logger.info("[EBS] Compute Optimizer enrichment complete for {} volumes in {}", volumeArns.size(), region);
        } catch (Exception e) {
            logger.warn("[EBS] Compute Optimizer unavailable for region {}: {}", region, e.getMessage());
        }
    }

    private double calcMonthlyCost(String volumeType, int sizeGb) {
        double pricePerGb;
        switch (volumeType) {
            case "gp2":   pricePerGb = GP2_PRICE_PER_GB; break;
            case "gp3":   pricePerGb = GP3_PRICE_PER_GB; break;
            case "io1":   pricePerGb = IO1_PRICE_PER_GB; break;
            case "io2":   pricePerGb = IO2_PRICE_PER_GB; break;
            case "sc1":   pricePerGb = SC1_PRICE_PER_GB; break;
            case "st1":   pricePerGb = ST1_PRICE_PER_GB; break;
            default:      pricePerGb = GP3_PRICE_PER_GB; break;
        }
        return pricePerGb * sizeGb;
    }

}
