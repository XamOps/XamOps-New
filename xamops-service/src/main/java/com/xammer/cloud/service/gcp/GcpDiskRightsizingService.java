package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.compute.v1.Disk;
import com.google.cloud.compute.v1.DisksClient;
import com.google.cloud.compute.v1.DisksResizeRequest;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DiskPolicy;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DiskPolicyRepository;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class GcpDiskRightsizingService {

    private static final Logger logger = LoggerFactory.getLogger(GcpDiskRightsizingService.class);

    private static final String CACHE_KEY_DISKS  = "gcp-disks-";
    private static final int    CACHE_TTL_MINUTES = 120;

    // GCP persistent disk pricing: USD/GB/month (approximate, us-central1)
    private static final Map<String, Double> PD_COST_PER_GB = Map.of(
            "pd-standard", 0.040,
            "pd-balanced",  0.100,
            "pd-ssd",       0.170,
            "pd-extreme",   0.125
    );
    private static final double DEFAULT_PD_COST_PER_GB = 0.100;

    // Defaults used only when no DiskPolicy is configured for the account.
    private static final double DEFAULT_SHRINK_THRESHOLD_PCT = 25.0;
    private static final double DEFAULT_EXPAND_THRESHOLD_PCT = 80.0;
    private static final int    DEFAULT_MIN_DISK_SIZE_GB     = 10;
    private static final int    DEFAULT_DESIRED_UTILIZATION  = 70;

    /**
     * GCP persistent disks cannot be shrunk in place: {@code disks.resize} only grows a
     * disk. Realizing a shrink means creating a smaller disk, migrating the data, and
     * swapping it in.
     */
    private static final String SHRINK_UNSUPPORTED_MESSAGE =
            "GCP persistent disks cannot be shrunk in place. Create a smaller disk, "
                    + "migrate the data, then detach and delete the original.";

    private final CloudAccountRepository    cloudAccountRepository;
    private final DiskPolicyRepository      diskPolicyRepository;
    private final EbsVolumeActionRepository ebsVolumeActionRepository;
    private final RedisCacheService         redisCacheService;
    private final GcpClientProvider         gcpClientProvider;

    // ── Public API ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getDisks(String accountId, boolean forceRefresh) {
        CloudAccount account = resolveAccount(accountId);
        String projectId = account.getGcpProjectId();
        if (projectId == null) throw new IllegalArgumentException("No GCP project ID for account " + accountId);

        String cacheKey = CACHE_KEY_DISKS + accountId;
        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<Map<String, Object>>>() {});
            if (cached.isPresent()) {
                logger.info("[gcp-disk] cache hit for account {}", accountId);
                return cached.get();
            }
        }

        List<Map<String, Object>> disks = listAllDisks(account, projectId);
        redisCacheService.put(cacheKey, disks, CACHE_TTL_MINUTES);
        return disks;
    }

    public List<Map<String, Object>> getRecommendations(String accountId, boolean forceRefresh) {
        return getDisks(accountId, forceRefresh).stream()
                .filter(d -> !"NONE".equals(d.get("recommendation")))
                .collect(Collectors.toList());
    }

    public Map<String, Object> applyAction(String accountId, String diskName, String zone,
                                            String actionType, Integer targetSizeGb) {
        CloudAccount account = resolveAccount(accountId);
        String projectId = account.getGcpProjectId();

        EbsVolumeAction action = new EbsVolumeAction();
        action.setAccount(account);
        action.setVolumeId(diskName);
        action.setRegion(zone);
        action.setActionType(actionType);
        action.setStatus("IN_PROGRESS");
        action.setInitiatedAt(Instant.now());
        ebsVolumeActionRepository.save(action);

        try {
            switch (actionType) {
                case "EXPAND":
                    return applyResize(projectId, zone, diskName, targetSizeGb, action, account);
                case "SHRINK":
                    // The Compute Engine disks.resize API only grows a disk. Routing SHRINK
                    // here previously guaranteed a FAILED action while the recommendation
                    // still advertised savings. Fail fast with the actual remediation path.
                    action.setStatus("FAILED");
                    action.setErrorMessage(SHRINK_UNSUPPORTED_MESSAGE);
                    action.setCompletedAt(Instant.now());
                    ebsVolumeActionRepository.save(action);
                    return Map.of("success", false, "error", SHRINK_UNSUPPORTED_MESSAGE);
                case "DELETE_UNATTACHED":
                    return applyDelete(projectId, zone, diskName, action, account);
                default:
                    throw new IllegalArgumentException("Unsupported action: " + actionType);
            }
        } catch (Exception e) {
            action.setStatus("FAILED");
            action.setErrorMessage(e.getMessage());
            action.setCompletedAt(Instant.now());
            ebsVolumeActionRepository.save(action);
            redisCacheService.evict(CACHE_KEY_DISKS + accountId);
            logger.error("[gcp-disk] action {} failed for disk {}: {}", actionType, diskName, e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public List<DiskPolicy> getPolicies(String accountId) {
        CloudAccount account = resolveAccount(accountId);
        List<DiskPolicy> policies = diskPolicyRepository.findByAccountId(account.getId()).stream()
                .filter(p -> "GCP".equals(p.getCloudProvider()))
                .collect(Collectors.toList());
        if (policies.isEmpty()) {
            // Persist the default so that a later savePolicy updates a real row instead of
            // inserting a second one, and so the engine and the UI read the same values.
            DiskPolicy def = new DiskPolicy();
            def.setAccount(account);
            def.setPolicyName("Default GCP Disk Policy");
            def.setDesiredUtilization(DEFAULT_DESIRED_UTILIZATION);
            def.setMinDiskSizeGb(DEFAULT_MIN_DISK_SIZE_GB);
            def.setMaxDiskCount(100);
            def.setPolicyType("STANDARD");
            def.setCloudProvider("GCP");
            def.setDefault(true);
            return List.of(diskPolicyRepository.save(def));
        }
        return policies;
    }

    public List<EbsVolumeAction> getActionHistory(String accountId) {
        return ebsVolumeActionRepository.findByAccountIdOrderByInitiatedAtDesc(resolveAccount(accountId).getId());
    }

    public Map<String, Object> savePolicy(String accountId, DiskPolicy policy) {
        CloudAccount account = resolveAccount(accountId);
        policy.setAccount(account);
        policy.setCloudProvider("GCP");
        diskPolicyRepository.save(policy);
        redisCacheService.evict(CACHE_KEY_DISKS + accountId);
        return Map.of("success", true);
    }

    // ── Disk listing ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> listAllDisks(CloudAccount account, String projectId) {
        Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(projectId);
        if (clientOpt.isEmpty()) {
            logger.warn("[gcp-disk] could not build DisksClient for project {}", projectId);
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        // Resolve the policy once per scan rather than per disk.
        EffectiveDiskPolicy policy = resolveEffectivePolicy(account);
        try (DisksClient client = clientOpt.get()) {
            StreamSupport.stream(client.aggregatedList(projectId).iterateAll().spliterator(), false)
                    .flatMap(entry -> entry.getValue().getDisksList().stream())
                    .forEach(disk -> {
                        Map<String, Object> dto = buildDiskDto(disk);
                        enrichFromAgent(account, disk, dto);
                        applyRecommendation(dto, policy);
                        result.add(dto);
                    });
        } catch (Exception e) {
            logger.error("[gcp-disk] failed to list disks for project {}: {}", projectId, e.getMessage(), e);
        }

        logger.info("[gcp-disk] listed {} disks for project {}", result.size(), projectId);
        return result;
    }

    /**
     * Whether a disk was provisioned by GKE for a PersistentVolume. GKE names these
     * {@code pvc-<uuid>} and labels them {@code goog-gke-volume}; such a disk reports no users
     * while its pod is rescheduled, so an unattached check alone is not safe.
     */
    private boolean isGkeVolume(String diskName, Map<String, String> labels) {
        if (diskName != null && diskName.toLowerCase().startsWith("pvc-")) {
            return true;
        }
        if (labels != null) {
            for (String key : labels.keySet()) {
                if (key == null) continue;
                String k = key.toLowerCase();
                if (k.startsWith("goog-gke-") || k.startsWith("kubernetes.io/") || k.startsWith("k8s-")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> buildDiskDto(Disk disk) {
        String zoneUrl = disk.getZone();
        String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);
        String region = zone.lastIndexOf('-') > 0 ? zone.substring(0, zone.lastIndexOf('-')) : zone;

        String typeUrl = disk.getType();
        String diskType = typeUrl.substring(typeUrl.lastIndexOf('/') + 1);

        boolean attached = !disk.getUsersList().isEmpty();
        String instanceName = null;
        if (attached) {
            String userUrl = disk.getUsersList().get(0);
            instanceName = userUrl.substring(userUrl.lastIndexOf('/') + 1);
        }

        long sizeGb = disk.getSizeGb();
        // USD, matching every other amount produced by the optimization services.
        double monthlyCostUsd = sizeGb * PD_COST_PER_GB.getOrDefault(diskType, DEFAULT_PD_COST_PER_GB);

        Map<String, Object> dto = new LinkedHashMap<>();
        // GKE-provisioned PersistentVolume disks must never be offered for deletion.
        dto.put("gkeVolume",            isGkeVolume(disk.getName(), disk.getLabelsMap()));
        dto.put("diskName",             disk.getName());
        dto.put("zone",                 zone);
        dto.put("region",               region);
        dto.put("diskType",             diskType);
        dto.put("sizeGb",               (int) sizeGb);
        dto.put("status",               disk.getStatus());
        dto.put("attached",             attached);
        dto.put("instanceName",         instanceName);
        dto.put("monthlyCost",          Math.round(monthlyCostUsd * 100.0) / 100.0);
        dto.put("recommendation",       "NONE");
        dto.put("recommendationReason", "");
        dto.put("estimatedSavings",     0.0);
        dto.put("recommendedSizeGb",    (int) sizeGb);
        return dto;
    }

    // ── Agent enrichment ──────────────────────────────────────────────────────

    private void enrichFromAgent(CloudAccount account, Disk disk, Map<String, Object> dto) {
        String projectId = account.getGcpProjectId();
        String instanceName = (String) dto.get("instanceName");
        if (instanceName == null || projectId == null) return;

        String cacheKey = "gcp-disk-agent:" + projectId + ":" + instanceName;
        Optional<List<Map<String, Object>>> agentData = redisCacheService.get(
                cacheKey, new TypeReference<List<Map<String, Object>>>() {});
        logger.debug("[gcp-disk] agent key={} found={}", cacheKey, agentData.isPresent());

        if (agentData.isEmpty()) {
            String seenKey = "gcp-disk-agent-seen:" + projectId + ":" + instanceName;
            Optional<Boolean> seen = redisCacheService.get(seenKey, new TypeReference<Boolean>() {});
            if (seen.isPresent()) {
                dto.put("agentDeployed", true);
            }
            return;
        }

        // Match the mount belonging to this disk by device name. There is deliberately no
        // fallback to the root filesystem: attributing the boot disk's usedPercent to a
        // data disk produced confident-looking SHRINK/EXPAND sizing for the wrong volume.
        String diskName = disk.getName();
        Map<String, Object> bestMount = null;
        for (Map<String, Object> mount : agentData.get()) {
            String device = (String) mount.get("device");
            String baseDevice = device != null ? device.replaceAll("^/dev/", "").replaceAll("[0-9]+$", "") : "";
            if (!baseDevice.isEmpty() && (diskName.contains(baseDevice) || baseDevice.contains(diskName))) {
                bestMount = mount;
                break;
            }
        }

        if (bestMount == null) {
            // Agent is reporting, but no mount could be attributed to this specific disk.
            dto.put("agentDeployed", true);
            logger.debug("[gcp-disk] no mount matched disk {}; leaving utilization unknown", diskName);
        } else {
            Number usedPct = (Number) bestMount.get("usedPercent");
            if (usedPct != null) {
                int sizeGb = (int) dto.get("sizeGb");
                double up = usedPct.doubleValue();
                dto.put("agentDeployed", true);
                dto.put("usedPercent", Math.round(up * 10.0) / 10.0);
                dto.put("usedGb",  Math.round(sizeGb * up / 100.0 * 100.0) / 100.0);
                dto.put("freeGb",  Math.round(sizeGb * (1 - up / 100.0) * 100.0) / 100.0);
            }
        }
    }

    // ── Recommendation engine ─────────────────────────────────────────────────

    /**
     * Derives a recommendation for a single disk.
     *
     * <p>All amounts are USD. Thresholds come from the account's {@link DiskPolicy} when
     * one is configured — previously the policy was persisted and displayed but the engine
     * used hardcoded constants plus a magic {@code 0.65} target that contradicted the
     * policy's advertised 70% utilization, so editing the policy changed nothing.
     *
     * @param dto    mutable disk row to annotate
     * @param policy effective policy thresholds for this account
     */
    private void applyRecommendation(Map<String, Object> dto, EffectiveDiskPolicy policy) {
        boolean attached = Boolean.TRUE.equals(dto.get("attached"));
        int sizeGb = (int) dto.get("sizeGb");
        String diskType = (String) dto.get("diskType");
        double costPerGbUsd = PD_COST_PER_GB.getOrDefault(diskType, DEFAULT_PD_COST_PER_GB);

        if (!attached) {
            // A GKE PersistentVolume disk shows no users while its pod is rescheduled, so
            // deleting it destroys live data. This path previously had no guard of any kind.
            if (Boolean.TRUE.equals(dto.get("gkeVolume"))) {
                dto.put("recommendation", "NONE");
                dto.put("recommendationReason", "Managed by GKE — backs a PersistentVolume and "
                        + "shows no users while its pod is rescheduled");
                dto.put("estimatedSavings", 0.0);
                return;
            }
            dto.put("recommendation", "DELETE_UNATTACHED");
            dto.put("recommendationReason", "Disk is not attached to any instance");
            dto.put("estimatedSavings", (double) dto.get("monthlyCost"));
            return;
        }

        if (!dto.containsKey("usedPercent") || dto.get("usedPercent") == null) return;

        double usedPct = ((Number) dto.get("usedPercent")).doubleValue();

        if (usedPct > policy.expandThresholdPct()) {
            int targetSize = policy.targetSizeGb(sizeGb, usedPct);
            dto.put("recommendation", "EXPAND");
            dto.put("recommendedSizeGb", targetSize);
            dto.put("recommendationReason", String.format(
                    "Disk is %.0f%% full — expand to %d GB to avoid capacity issues", usedPct, targetSize));
            // An expand increases cost. Report the delta as a negative "saving" so it can
            // never be added into a savings total as if it were a gain.
            double costIncrease = (targetSize - sizeGb) * costPerGbUsd;
            dto.put("estimatedSavings", -Math.round(costIncrease * 100.0) / 100.0);
            dto.put("estimatedMonthlyCostIncrease", Math.round(costIncrease * 100.0) / 100.0);
        } else if (usedPct < policy.shrinkThresholdPct() && sizeGb > policy.minDiskSizeGb()) {
            int targetSize = policy.targetSizeGb(sizeGb, usedPct);
            if (targetSize < sizeGb) {
                double saving = (sizeGb - targetSize) * costPerGbUsd;
                dto.put("recommendation", "SHRINK");
                dto.put("recommendedSizeGb", targetSize);
                dto.put("recommendationReason", String.format(
                        "Only %.0f%% used — a %d GB disk would suffice (currently %d GB). %s",
                        usedPct, targetSize, sizeGb, SHRINK_UNSUPPORTED_MESSAGE));
                dto.put("estimatedSavings", Math.round(saving * 100.0) / 100.0);
                // GCP cannot shrink in place, so this saving is only realizable by creating a
                // smaller disk and migrating. Flag it so totals can exclude un-actionable
                // savings and the UI can avoid offering a one-click Apply.
                dto.put("actionable", false);
                dto.put("requiresMigration", true);
            }
        }
    }

    /**
     * Effective disk thresholds for an account, resolved from its {@link DiskPolicy} with
     * fallbacks to the service defaults.
     *
     * @param shrinkThresholdPct utilization below which a disk is over-provisioned
     * @param expandThresholdPct utilization above which a disk needs headroom
     * @param minDiskSizeGb      floor for any recommended size
     * @param desiredUtilization utilization the recommended size should achieve
     */
    private record EffectiveDiskPolicy(
            double shrinkThresholdPct,
            double expandThresholdPct,
            int minDiskSizeGb,
            int desiredUtilization) {

        /**
         * Size needed for the current used bytes to land at {@link #desiredUtilization}.
         */
        int targetSizeGb(int currentSizeGb, double usedPct) {
            double usedGb = currentSizeGb * usedPct / 100.0;
            double targetUtilisation = desiredUtilization / 100.0;
            return Math.max(minDiskSizeGb, (int) Math.ceil(usedGb / targetUtilisation));
        }
    }

    /**
     * Loads the account's GCP disk policy, falling back to service defaults. Reads the
     * default-flagged policy when several exist.
     */
    private EffectiveDiskPolicy resolveEffectivePolicy(CloudAccount account) {
        try {
            List<DiskPolicy> policies = diskPolicyRepository.findByAccountId(account.getId()).stream()
                    .filter(p -> "GCP".equals(p.getCloudProvider()))
                    .collect(Collectors.toList());
            DiskPolicy policy = policies.stream()
                    .filter(DiskPolicy::isDefault)
                    .findFirst()
                    .orElse(policies.isEmpty() ? null : policies.get(0));
            if (policy == null) {
                return defaultPolicy();
            }
            int desired = policy.getDesiredUtilization() > 0
                    ? policy.getDesiredUtilization() : DEFAULT_DESIRED_UTILIZATION;
            int minSize = policy.getMinDiskSizeGb() > 0
                    ? policy.getMinDiskSizeGb() : DEFAULT_MIN_DISK_SIZE_GB;
            // Shrink when utilization is well under target; expand when it approaches full.
            double shrinkThreshold = Math.max(1.0, desired * 0.5);
            return new EffectiveDiskPolicy(
                    shrinkThreshold, DEFAULT_EXPAND_THRESHOLD_PCT, minSize, desired);
        } catch (Exception e) {
            logger.warn("[gcp-disk] Could not load disk policy for account {}; using defaults: {}",
                    account.getId(), e.getMessage());
            return defaultPolicy();
        }
    }

    private EffectiveDiskPolicy defaultPolicy() {
        return new EffectiveDiskPolicy(DEFAULT_SHRINK_THRESHOLD_PCT, DEFAULT_EXPAND_THRESHOLD_PCT,
                DEFAULT_MIN_DISK_SIZE_GB, DEFAULT_DESIRED_UTILIZATION);
    }

    // ── Apply actions ─────────────────────────────────────────────────────────

    private Map<String, Object> applyResize(String projectId, String zone, String diskName,
                                              Integer targetSizeGb, EbsVolumeAction action,
                                              CloudAccount account) throws Exception {
        if (targetSizeGb == null || targetSizeGb < DEFAULT_MIN_DISK_SIZE_GB)
            throw new IllegalArgumentException("targetSizeGb must be >= " + DEFAULT_MIN_DISK_SIZE_GB);

        Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(projectId);
        if (clientOpt.isEmpty()) throw new RuntimeException("Could not create GCP DisksClient");

        try (DisksClient client = clientOpt.get()) {
            DisksResizeRequest resizeReq = DisksResizeRequest.newBuilder()
                    .setSizeGb(targetSizeGb)
                    .build();
            client.resizeAsync(projectId, zone, diskName, resizeReq).get();
        }

        action.setNewSizeGb(targetSizeGb);
        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);
        redisCacheService.evict(CACHE_KEY_DISKS + account.getId());
        return Map.of("success", true, "message",
                "Disk " + diskName + " resized to " + targetSizeGb + " GB");
    }

    private Map<String, Object> applyDelete(String projectId, String zone, String diskName,
                                              EbsVolumeAction action, CloudAccount account) throws Exception {
        Optional<DisksClient> clientOpt = gcpClientProvider.getDisksClient(projectId);
        if (clientOpt.isEmpty()) throw new RuntimeException("Could not create GCP DisksClient");

        try (DisksClient client = clientOpt.get()) {
            client.deleteAsync(projectId, zone, diskName).get();
        }

        action.setStatus("SUCCESS");
        action.setCompletedAt(Instant.now());
        ebsVolumeActionRepository.save(action);
        redisCacheService.evict(CACHE_KEY_DISKS + account.getId());
        return Map.of("success", true, "message", "Disk " + diskName + " deleted");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CloudAccount resolveAccount(String accountId) {
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
    }
}
