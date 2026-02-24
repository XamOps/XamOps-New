package com.xammer.cloud.service.gcp;

import com.google.cloud.compute.v1.*;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GcpAutoSpottingService {

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final SpotOptimizationEventRepository eventRepository;
    private final DailySpotSavingsRepository savingsRepository;
    private final GcpPricingService pricingService;
    private final GcpRestTemplateService restTemplateService;

    public GcpAutoSpottingService(GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            SpotOptimizationEventRepository eventRepository,
            DailySpotSavingsRepository savingsRepository,
            GcpPricingService pricingService,
            GcpRestTemplateService restTemplateService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
        this.pricingService = pricingService;
        this.restTemplateService = restTemplateService;
    }

    private CloudAccount resolveAccount(String accountId) {
        log.debug("🔍 Resolving GCP account for identifier: {}", accountId);
        // Try looking up by provider account ID (Project ID) first
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .or(() -> {
                    try {
                        // Fallback to DB ID if numeric
                        return cloudAccountRepository.findById(Long.parseLong(accountId));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElseThrow(() -> {
                    log.error("❌ GCP account not found for identifier: {}", accountId);
                    return new RuntimeException("GCP account not found for identifier: " + accountId);
                });
    }

    /**
     * Enhanced DTO with cost information for GCP MIGs
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GcpAutoSpottingGroupDto {
        public String name;
        public String region;
        public boolean isEnabled;
        public int instanceCount;
        public double currentHourlyCost;
        public double potentialHourlyCost;
        public double actualHourlySavings;
        public double potentialHourlySavings;
        public int spotInstanceCount;
        public int ondemandInstanceCount;
        public String machineType; // Added to track machine type
    }

    public List<GcpAutoSpottingGroupDto> listMigsAllRegionsWithCosts(String accountId) {
        CloudAccount account = resolveAccount(accountId);
        String gcpProjectId = account.getGcpProjectId();

        List<GcpAutoSpottingGroupDto> migs = listMigsAllRegions(accountId, gcpProjectId);

        for (GcpAutoSpottingGroupDto mig : migs) {
            try {
                // Enrich with real cost data using GCP Pricing Service
                enrichMigCostData(mig);
            } catch (Exception e) {
                log.warn("Failed to enrich cost data for MIG {}: {}", mig.name, e.getMessage());
            }
        }

        return migs;
    }

    private void enrichMigCostData(GcpAutoSpottingGroupDto mig) {
        // Use real GCP pricing based on machine type and region
        String machineType = mig.machineType != null ? mig.machineType : "n1-standard-1"; // fallback
        String region = extractRegionFromZone(mig.region);

        try {
            GcpPricingService.PricingInfo pricing = pricingService.getMachineTypePricing(machineType, region);

            double onDemandPricePerInstance = pricing.getOnDemandPrice();
            double spotPricePerInstance = pricing.getSpotPrice();

            // Calculate current hourly cost
            mig.currentHourlyCost = (mig.ondemandInstanceCount * onDemandPricePerInstance)
                                  + (mig.spotInstanceCount * spotPricePerInstance);

            // Calculate potential hourly cost if all instances were spot
            mig.potentialHourlyCost = mig.instanceCount * spotPricePerInstance;

            // Calculate actual hourly savings (only if spot is enabled)
            if (mig.isEnabled && mig.spotInstanceCount > 0) {
                mig.actualHourlySavings = mig.spotInstanceCount * (onDemandPricePerInstance - spotPricePerInstance);
            } else {
                mig.actualHourlySavings = 0.0;
            }

            // Calculate potential hourly savings (only for instances still on on-demand)
            mig.potentialHourlySavings = mig.ondemandInstanceCount * (onDemandPricePerInstance - spotPricePerInstance);

            log.debug("Enriched cost data for MIG {}: machineType={}, onDemand=₹{}/hr, spot=₹{}/hr, savings=₹{}/hr",
                    mig.name, machineType,
                    String.format("%.2f", onDemandPricePerInstance),
                    String.format("%.2f", spotPricePerInstance),
                    String.format("%.2f", mig.potentialHourlySavings));

        } catch (Exception e) {
            log.warn("Failed to get pricing for machine type {}: {}. Using defaults.", machineType, e.getMessage());

            // Fallback to conservative estimates
            double fallbackOdPrice = 0.05;
            double fallbackSpotPrice = 0.015;

            mig.currentHourlyCost = (mig.ondemandInstanceCount * fallbackOdPrice) + (mig.spotInstanceCount * fallbackSpotPrice);
            mig.potentialHourlyCost = mig.instanceCount * fallbackSpotPrice;
            mig.actualHourlySavings = mig.isEnabled ? (mig.spotInstanceCount * (fallbackOdPrice - fallbackSpotPrice)) : 0.0;
            mig.potentialHourlySavings = mig.ondemandInstanceCount * (fallbackOdPrice - fallbackSpotPrice);
        }
    }

    /**
     * Extract region from zone (e.g., "us-central1-a" -> "us-central1")
     */
    private String extractRegionFromZone(String zone) {
        if (zone == null) return "us-central1";

        // Zone format: {region}-{zone-letter} (e.g., us-central1-a)
        // Region format: {region} (e.g., us-central1)
        int lastDash = zone.lastIndexOf('-');
        if (lastDash > 0 && zone.substring(lastDash + 1).length() == 1) {
            // It's a zone, extract region
            return zone.substring(0, lastDash);
        }

        // Already a region
        return zone;
    }

    public List<GcpAutoSpottingGroupDto> listMigsAllRegions(String accountId, String gcpProjectId) {
        List<GcpAutoSpottingGroupDto> allMigs = new ArrayList<>();

        Optional<InstanceGroupManagersClient> clientOpt = gcpClientProvider
                .getInstanceGroupManagersClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return Collections.emptyList();

        try (InstanceGroupManagersClient client = clientOpt.get()) {
            InstanceGroupManagersClient.AggregatedListPagedResponse response = client.aggregatedList(gcpProjectId);

            for (Map.Entry<String, InstanceGroupManagersScopedList> entry : response.iterateAll()) {
                if (entry.getValue().getInstanceGroupManagersList() != null) {
                    for (InstanceGroupManager igm : entry.getValue().getInstanceGroupManagersList()) {
                        allMigs.add(mapToDto(igm, gcpProjectId));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list MIGs for project {}: {}", gcpProjectId, e.getMessage(), e);
        }

        return allMigs;
    }

    /**
     * IMPROVED: Map InstanceGroupManager to DTO with accurate spot detection
     * Fetches the actual instance template to check if it's configured for SPOT/Preemptible
     */
    private GcpAutoSpottingGroupDto mapToDto(InstanceGroupManager igm, String gcpProjectId) {
        int instanceCount = igm.getTargetSize();

        // Extract location (zone or region)
        String location = igm.hasZone() ? igm.getZone() : (igm.hasRegion() ? igm.getRegion() : "global");
        if (location != null && location.contains("/")) {
            location = location.substring(location.lastIndexOf('/') + 1);
        }

        // IMPROVED: Check actual instance template configuration instead of name-based detection
        boolean isSpotEnabled = false;
        String machineType = "n1-standard-1"; // default fallback

        try {
            String templateUri = igm.getInstanceTemplate();
            if (templateUri != null && !templateUri.isBlank()) {
                String templateName = templateUri.substring(templateUri.lastIndexOf('/') + 1);
                String templateRegion = getTemplateRegion(templateUri);

                // Fetch the actual instance template
                InstanceTemplate template = fetchInstanceTemplate(gcpProjectId, templateRegion, templateName);

                if (template != null && template.hasProperties()) {
                    // Check scheduling configuration for SPOT/Preemptible
                    if (template.getProperties().hasScheduling()) {
                        Scheduling scheduling = template.getProperties().getScheduling();

                        // Check if it's SPOT or Preemptible
                        isSpotEnabled = "SPOT".equals(scheduling.getProvisioningModel()) ||
                                       (scheduling.hasPreemptible() && scheduling.getPreemptible());
                    }

                    // Extract machine type
                    if (template.getProperties().hasMachineType()) {
                        String machineTypeUri = template.getProperties().getMachineType();
                        machineType = machineTypeUri.substring(machineTypeUri.lastIndexOf('/') + 1);
                    }
                }

                log.debug("MIG {}: template={}, isSpot={}, machineType={}",
                         igm.getName(), templateName, isSpotEnabled, machineType);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch instance template for MIG {}: {}. Falling back to name-based detection.",
                    igm.getName(), e.getMessage());

            // Fallback: name-based detection (old behavior)
            isSpotEnabled = igm.getName().toLowerCase().contains("spot") ||
                           igm.getName().toLowerCase().contains("preempt");
        }

        return GcpAutoSpottingGroupDto.builder()
                .name(igm.getName())
                .region(location)
                .isEnabled(isSpotEnabled)
                .instanceCount(instanceCount)
                .ondemandInstanceCount(isSpotEnabled ? 0 : instanceCount)
                .spotInstanceCount(isSpotEnabled ? instanceCount : 0)
                .machineType(machineType)
                .build();
    }

    /**
     * Fetch instance template (handles both regional and global templates)
     */
    private InstanceTemplate fetchInstanceTemplate(String projectId, String region, String templateName) {
        try {
            if (region != null) {
                // Regional template
                Optional<RegionInstanceTemplatesClient> clientOpt = gcpClientProvider
                        .getRegionInstanceTemplatesClient(projectId);
                if (clientOpt.isPresent()) {
                    try (RegionInstanceTemplatesClient client = clientOpt.get()) {
                        return client.get(projectId, region, templateName);
                    }
                }
            } else {
                // Global template
                Optional<InstanceTemplatesClient> clientOpt = gcpClientProvider
                        .getInstanceTemplatesClient(projectId);
                if (clientOpt.isPresent()) {
                    try (InstanceTemplatesClient client = clientOpt.get()) {
                        return client.get(projectId, templateName);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch template {}: {}", templateName, e.getMessage());
        }

        return null;
    }

    public HistoryResponse getHistoryData(String accountId, String range) {
        CloudAccount account = resolveAccount(accountId);

        LocalDate startDate;
        switch (range) {
            case "last24h":
                startDate = LocalDate.now().minusDays(1);
                break;
            case "last30d":
                startDate = LocalDate.now().minusDays(30);
                break;
            case "last7d":
            default:
                startDate = LocalDate.now().minusDays(7);
                break;
        }

        List<DailySpotSavings> findings = savingsRepository.findByAccountIdAndDateAfterOrderByDateAsc(account.getId(),
                startDate);

        HistoryResponse response = new HistoryResponse();
        List<HistoryResponse.DataPoint> dataPoints = new ArrayList<>();

        double totalSaved = 0;
        for (DailySpotSavings saving : findings) {
            HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
            dp.setTimestamp(saving.getDate().toString());
            dp.setActualHourlySavings(saving.getAmountSaved());
            // Mock potential as we don't store it daily for now, or use a multiplier
            dp.setPotentialHourlySavings(saving.getAmountSaved() * 0.2);
            dataPoints.add(dp);
            totalSaved += saving.getAmountSaved();
        }

        // If no data, provide at least today's point (even if zero) to avoid empty
        // graph issues
        if (dataPoints.isEmpty()) {
            HistoryResponse.DataPoint today = new HistoryResponse.DataPoint();
            today.setTimestamp(LocalDate.now().toString());
            today.setActualHourlySavings(0.0);
            today.setPotentialHourlySavings(0.0);
            dataPoints.add(today);
        }

        response.setDataPoints(dataPoints);

        HistoryResponse.HistorySummary summary = new HistoryResponse.HistorySummary();
        summary.setTotalActualSavings(totalSaved);
        summary.setAverageHourlySavings(dataPoints.size() > 0 ? totalSaved / dataPoints.size() : 0);
        response.setSummary(summary);

        return response;
    }

    public List<SpotOptimizationEvent> getEvents(String accountId, int limit) {
        CloudAccount account = resolveAccount(accountId);
        return eventRepository.findByAccountIdOrderByEventTimeDesc(account.getId())
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<SpotInstanceDto> getSpotInstances(String accountId) {
        CloudAccount account = resolveAccount(accountId);

        String gcpProjectId = account.getGcpProjectId();
        List<SpotInstanceDto> instances = new ArrayList<>();

        Optional<InstancesClient> clientOpt = gcpClientProvider.getInstancesClient(gcpProjectId);
        if (clientOpt.isEmpty())
            return Collections.emptyList();

        try (InstancesClient client = clientOpt.get()) {
            InstancesClient.AggregatedListPagedResponse response = client.aggregatedList(gcpProjectId);
            for (Map.Entry<String, InstancesScopedList> entry : response.iterateAll()) {
                if (entry.getValue().getInstancesList() != null) {
                    for (com.google.cloud.compute.v1.Instance instance : entry.getValue().getInstancesList()) {
                        String lifecycle = "on-demand";

                        // Check if it's preemptible or spot
                        if (instance.hasScheduling()) {
                            Scheduling scheduling = instance.getScheduling();
                            if ((scheduling.hasPreemptible() && scheduling.getPreemptible()) ||
                                    "SPOT".equals(scheduling.getProvisioningModel())) {
                                lifecycle = "spot";
                            }
                        }

                        String zoneUrl = instance.getZone();
                        String zone = zoneUrl.substring(zoneUrl.lastIndexOf('/') + 1);

                        instances.add(SpotInstanceDto.builder()
                                .instanceId(String.valueOf(instance.getId()))
                                .instanceType(instance.getMachineType()
                                        .substring(instance.getMachineType().lastIndexOf('/') + 1))
                                .availabilityZone(zone)
                                .lifecycle(lifecycle)
                                .state(instance.getStatus())
                                .region(zone.contains("-") ? zone.substring(0, zone.lastIndexOf('-')) : zone)
                                .privateIpAddress(instance.getNetworkInterfacesCount() > 0
                                        ? instance.getNetworkInterfaces(0).getNetworkIP()
                                        : null)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list spot instances for project {}: {}", gcpProjectId, e.getMessage(), e);
        }

        return instances;
    }

    public void enableAutoSpotting(String accountId, String region, String igmName) {
        CloudAccount account = resolveAccount(accountId);
        String projectId = account.getGcpProjectId();
        log.info("🚀 [GCP DEBUG] Starting enableAutoSpotting for {} in project {}", igmName, projectId);

        try {
            log.info("🔍 [GCP DEBUG] Step 1: Getting MIG details for location: {}", region);
            InstanceGroupManager igm = getMig(projectId, region, igmName);
            String currentTemplateUri = igm.getInstanceTemplate();
            log.info("🔍 [GCP DEBUG] Step 2: Found current template URI: {}", currentTemplateUri);

            if (currentTemplateUri == null || currentTemplateUri.isBlank()) {
                throw new RuntimeException("MIG " + igmName + " has no instance template");
            }

            String currentTemplateName = currentTemplateUri.substring(currentTemplateUri.lastIndexOf('/') + 1);
            String templateRegion = getTemplateRegion(currentTemplateUri);
            InstanceTemplate currentTemplate;

            if (templateRegion != null) {
                log.info("🔍 [GCP DEBUG] Step 3: Fetching REGIONAL template: {} in {}", currentTemplateName,
                        templateRegion);
                Optional<RegionInstanceTemplatesClient> clientOpt = gcpClientProvider
                        .getRegionInstanceTemplatesClient(projectId);
                if (clientOpt.isEmpty())
                    throw new RuntimeException("Failed to get RegionInstanceTemplatesClient");
                try (RegionInstanceTemplatesClient client = clientOpt.get()) {
                    currentTemplate = client.get(projectId, templateRegion, currentTemplateName);
                }
            } else {
                log.info("🔍 [GCP DEBUG] Step 3: Fetching GLOBAL template: {}", currentTemplateName);
                Optional<InstanceTemplatesClient> clientOpt = gcpClientProvider.getInstanceTemplatesClient(projectId);
                if (clientOpt.isEmpty())
                    throw new RuntimeException("Failed to get InstanceTemplatesClient");
                try (InstanceTemplatesClient client = clientOpt.get()) {
                    currentTemplate = client.get(projectId, currentTemplateName);
                }
            }
            log.info("🔍 [GCP DEBUG] Step 4: Successfully fetched current template properties");

            // Check if already spot
            if (currentTemplate.getProperties().hasScheduling() &&
                    "SPOT".equals(currentTemplate.getProperties().getScheduling().getProvisioningModel())) {
                log.info("MIG {} is already using a SPOT template.", igmName);
                return;
            }

            String newTemplateName = currentTemplateName.replace("-spot-", "-") + "-spot-"
                    + System.currentTimeMillis() / 1000;
            if (newTemplateName.length() > 63) { // Limit name length for GCP
                newTemplateName = "spot-" + System.currentTimeMillis() / 1000;
            }

            // SOLUTION: Use GCP REST API directly (no gcloud required)
            // This works in production without any CLI dependencies
            log.info("🔧 [GCP] Creating SPOT template using REST API (SaaS-ready, no gcloud required)");

            try {
                // Step 1: Get current template via REST API
                log.info("🔍 [GCP] Step 1: Fetching current template: {}", currentTemplateName);
                com.google.gson.JsonObject sourceTemplate = restTemplateService.getInstanceTemplate(
                    projectId, templateRegion, currentTemplateName);

                // Step 2: Create new SPOT template via REST API
                log.info("🔧 [GCP] Step 2: Creating SPOT template: {}", newTemplateName);
                restTemplateService.createSpotTemplate(
                    projectId, templateRegion, newTemplateName, sourceTemplate, true);
                log.info("✅ [GCP] Created SPOT template: {}", newTemplateName);

                // Step 3: Update MIG to use new template
                String newTemplateUrl;
                if (templateRegion != null) {
                    newTemplateUrl = String.format("projects/%s/regions/%s/instanceTemplates/%s",
                        projectId, templateRegion, newTemplateName);
                } else {
                    newTemplateUrl = String.format("projects/%s/global/instanceTemplates/%s",
                        projectId, newTemplateName);
                }

                log.info("🔧 [GCP] Step 3: Setting MIG template to: {}", newTemplateName);
                restTemplateService.setMigTemplate(projectId, region, igmName, newTemplateUrl);
                log.info("✅ [GCP] MIG template updated");

                // Step 4: Perform rolling update (Spot.io-style orchestrated replacement)
                log.info("🔄 [GCP] Step 4: Starting rolling update (zero-downtime replacement)");
                restTemplateService.startRollingUpdate(projectId, region, igmName, newTemplateUrl);
                log.info("✅ [GCP] Rolling update initiated. Instances being replaced with SPOT VMs gradually.");

                // Step 5: Clean up old template (if it was a managed template)
                if (currentTemplateName.contains("-spot-") || currentTemplateName.contains("-od-")) {
                    log.info("🗑️ [GCP] Step 5: Cleaning up old template: {}", currentTemplateName);
                    restTemplateService.deleteTemplate(projectId, templateRegion, currentTemplateName);
                }

                log.info("✅ [GCP] Successfully enabled SPOT for MIG: {}", igmName);

                // Record savings event
                try {
                    int instanceCount = igm.getTargetSize();
                    String machineTypeFromTemplate = currentTemplate.getProperties().getMachineType();
                    // Extract just the machine type name (e.g., "n1-standard-1" from full URL)
                    String machineTypeName = machineTypeFromTemplate != null && machineTypeFromTemplate.contains("/")
                        ? machineTypeFromTemplate.substring(machineTypeFromTemplate.lastIndexOf('/') + 1)
                        : "n1-standard-1";

                    GcpPricingService.PricingInfo pricing = pricingService.getMachineTypePricing(
                        machineTypeName, extractRegionFromZone(region));
                    double savingsPerInstance = pricing.getOnDemandPrice() - pricing.getSpotPrice();
                    recordSavingsEvent(account, igmName, region, instanceCount, savingsPerInstance, true);
                } catch (Exception e) {
                    log.warn("⚠️ [GCP] Could not record savings event: {}", e.getMessage());
                }

            } catch (Exception e) {
                log.error("❌ [GCP] Failed to enable SPOT: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to enable Spot: " + e.getMessage());
            }

        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (e instanceof com.google.api.gax.rpc.ApiException) {
                com.google.api.gax.rpc.ApiException apiEx = (com.google.api.gax.rpc.ApiException) e;
                errorMsg = String.format("%s | Reason: %s | Code: %s",
                        apiEx.getMessage(), apiEx.getReason(), apiEx.getStatusCode().getCode());
            }
            log.error("❌ [GCP DEBUG] Failed at some step in enableAutoSpotting: {}", errorMsg, e);
            throw new RuntimeException("Failed to enable Spot: " + errorMsg);
        }
    }

    public void disableAutoSpotting(String accountId, String region, String igmName) {
        CloudAccount account = resolveAccount(accountId);
        String projectId = account.getGcpProjectId();
        log.info("Disabling AutoSpotting (Spot) for MIG {} in project {}", igmName, projectId);

        try {
            InstanceGroupManager igm = getMig(projectId, region, igmName);
            String currentTemplateUri = igm.getInstanceTemplate();
            if (currentTemplateUri == null || currentTemplateUri.isBlank()) {
                throw new RuntimeException("MIG " + igmName + " has no instance template");
            }

            String currentTemplateName = currentTemplateUri.substring(currentTemplateUri.lastIndexOf('/') + 1);
            String templateRegion = getTemplateRegion(currentTemplateUri);
            InstanceTemplate currentTemplate;

            if (templateRegion != null) {
                Optional<RegionInstanceTemplatesClient> clientOpt = gcpClientProvider
                        .getRegionInstanceTemplatesClient(projectId);
                if (clientOpt.isEmpty())
                    throw new RuntimeException("Failed to get RegionInstanceTemplatesClient");
                try (RegionInstanceTemplatesClient client = clientOpt.get()) {
                    currentTemplate = client.get(projectId, templateRegion, currentTemplateName);
                }
            } else {
                Optional<InstanceTemplatesClient> clientOpt = gcpClientProvider.getInstanceTemplatesClient(projectId);
                if (clientOpt.isEmpty())
                    throw new RuntimeException("Failed to get InstanceTemplatesClient");
                try (InstanceTemplatesClient client = clientOpt.get()) {
                    currentTemplate = client.get(projectId, currentTemplateName);
                }
            }

            if (!currentTemplate.getProperties().hasScheduling() ||
                    !"SPOT".equals(currentTemplate.getProperties().getScheduling().getProvisioningModel())) {
                log.info("MIG {} is already using a non-SPOT template.", igmName);
                return;
            }

            String newTemplateName = currentTemplateName.replace("-spot-", "-od-") + "-"
                    + System.currentTimeMillis() / 1000;
            if (!newTemplateName.contains("-od-")) {
                newTemplateName = currentTemplateName + "-od-" + System.currentTimeMillis() / 1000;
            }

            // SOLUTION: Use GCP REST API directly (no gcloud required)
            log.info("🔧 [GCP] Creating ON-DEMAND template using REST API");

            try {
                // Step 1: Get current template via REST API
                log.info("🔍 [GCP] Step 1: Fetching current template: {}", currentTemplateName);
                com.google.gson.JsonObject sourceTemplate = restTemplateService.getInstanceTemplate(
                    projectId, templateRegion, currentTemplateName);

                // Step 2: Create new ON-DEMAND template via REST API
                log.info("🔧 [GCP] Step 2: Creating ON-DEMAND template: {}", newTemplateName);
                restTemplateService.createSpotTemplate(
                    projectId, templateRegion, newTemplateName, sourceTemplate, false);
                log.info("✅ [GCP] Created ON-DEMAND template: {}", newTemplateName);

                // Step 3: Update MIG to use new template
                String newTemplateUrl;
                if (templateRegion != null) {
                    newTemplateUrl = String.format("projects/%s/regions/%s/instanceTemplates/%s",
                        projectId, templateRegion, newTemplateName);
                } else {
                    newTemplateUrl = String.format("projects/%s/global/instanceTemplates/%s",
                        projectId, newTemplateName);
                }

                log.info("🔧 [GCP] Step 3: Setting MIG template to: {}", newTemplateName);
                restTemplateService.setMigTemplate(projectId, region, igmName, newTemplateUrl);
                log.info("✅ [GCP] MIG template updated");

                // Step 4: Perform rolling update (Spot.io-style orchestrated replacement)
                log.info("🔄 [GCP] Step 4: Starting rolling update (zero-downtime replacement)");
                restTemplateService.startRollingUpdate(projectId, region, igmName, newTemplateUrl);
                log.info("✅ [GCP] Rolling update initiated. Instances being replaced with ON-DEMAND VMs gradually.");

                // Step 5: Clean up old template (if it was a managed template)
                if (currentTemplateName.contains("-spot-") || currentTemplateName.contains("-od-")) {
                    log.info("🗑️ [GCP] Step 5: Cleaning up old template: {}", currentTemplateName);
                    restTemplateService.deleteTemplate(projectId, templateRegion, currentTemplateName);
                }

                log.info("✅ [GCP] Successfully disabled SPOT for MIG: {}", igmName);

            } catch (Exception e) {
                log.error("❌ [GCP] Failed to disable SPOT: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to disable Spot: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to disable Spot for MIG {}: {}", igmName, e.getMessage(), e);
            throw new RuntimeException("Failed to disable Spot: " + e.getMessage());
        }
    }

    private String getTemplateRegion(String templateUri) {
        if (templateUri != null && templateUri.contains("/regions/")) {
            String[] parts = templateUri.split("/");
            for (int i = 0; i < parts.length; i++) {
                if ("regions".equals(parts[i]) && i + 1 < parts.length) {
                    return parts[i + 1];
                }
            }
        }
        return null;
    }

    private InstanceGroupManager getMig(String projectId, String location, String igmName) throws Exception {
        log.info("🔍 [GCP DEBUG] getMig - projectId: {}, location: {}, igmName: {}", projectId, location, igmName);
        if (location != null && location.split("-").length == 3) { // It's a zone like us-central1-a
            Optional<InstanceGroupManagersClient> clientOpt = gcpClientProvider
                    .getInstanceGroupManagersClient(projectId);
            if (clientOpt.isPresent()) {
                try (InstanceGroupManagersClient client = clientOpt.get()) {
                    log.info("🔍 [GCP DEBUG] Calling zonal client.get({}, {}, {})", projectId, location, igmName);
                    return client.get(projectId, location, igmName);
                }
            }
        } else if (location != null) { // It's a region
            Optional<RegionInstanceGroupManagersClient> clientOpt = gcpClientProvider
                    .getRegionInstanceGroupManagersClient(projectId);
            if (clientOpt.isPresent()) {
                try (RegionInstanceGroupManagersClient client = clientOpt.get()) {
                    log.info("🔍 [GCP DEBUG] Calling regional client.get({}, {}, {})", projectId, location, igmName);
                    return client.get(projectId, location, igmName);
                }
            }
        }
        throw new RuntimeException(
                "MIG " + igmName + " not found in location " + location + " (projectId: " + projectId + ")");
    }

    private void updateMigTemplate(String projectId, String location, String igmName, String templateUri)
            throws Exception {
        InstanceGroupManager updateIgm = InstanceGroupManager.newBuilder()
                .setInstanceTemplate(templateUri)
                .build();

        if (location.split("-").length == 3) {
            Optional<InstanceGroupManagersClient> clientOpt = gcpClientProvider
                    .getInstanceGroupManagersClient(projectId);
            if (clientOpt.isPresent()) {
                try (InstanceGroupManagersClient client = clientOpt.get()) {
                    PatchInstanceGroupManagerRequest patchRequest = PatchInstanceGroupManagerRequest.newBuilder()
                            .setProject(projectId)
                            .setZone(location)
                            .setInstanceGroupManager(igmName)
                            .setInstanceGroupManagerResource(updateIgm)
                            .build();
                    client.patchAsync(patchRequest).get();
                }
            }
        } else {
            Optional<RegionInstanceGroupManagersClient> clientOpt = gcpClientProvider
                    .getRegionInstanceGroupManagersClient(projectId);
            if (clientOpt.isPresent()) {
                try (RegionInstanceGroupManagersClient client = clientOpt.get()) {
                    PatchRegionInstanceGroupManagerRequest patchRequest = PatchRegionInstanceGroupManagerRequest
                            .newBuilder()
                            .setProject(projectId)
                            .setRegion(location)
                            .setInstanceGroupManager(igmName)
                            .setInstanceGroupManagerResource(updateIgm)
                            .build();
                    client.patchAsync(patchRequest).get();
                }
            }
        }
    }

    /**
     * Record savings event when AutoSpotting is enabled/disabled
     */
    private void recordSavingsEvent(CloudAccount account, String migName, String region,
                                    int instanceCount, double hourlySavingsPerInstance, boolean enabled) {
        try {
            // Record optimization event
            SpotOptimizationEvent event = new SpotOptimizationEvent();
            event.setAccount(account);
            event.setAsgName(migName);
            event.setRegion(region);
            event.setStatus(enabled ? "ENABLED" : "DISABLED");
            event.setHourlySavings(instanceCount * hourlySavingsPerInstance);
            event.setDetails(String.format("%s AutoSpotting for MIG %s with %d instance(s)",
                enabled ? "Enabled" : "Disabled", migName, instanceCount));
            event.setEventTime(java.time.Instant.now());

            eventRepository.save(event);
            log.info("📊 [GCP] Recorded savings event for MIG: {} (₹{}/hr)", migName,
                String.format("%.2f", event.getHourlySavings()));

            // Update daily savings
            updateDailySavings(account, event.getHourlySavings());

        } catch (Exception e) {
            log.warn("⚠️ [GCP] Failed to record savings event: {}", e.getMessage());
        }
    }

    /**
     * Update daily savings aggregation
     */
    private void updateDailySavings(CloudAccount account, double savings) {
        try {
            java.time.LocalDate today = java.time.LocalDate.now();

            // Find or create today's savings record
            java.util.Optional<DailySpotSavings> existingOpt =
                savingsRepository.findByAccountIdAndDate(account.getId(), today);

            DailySpotSavings dailySavings;
            if (existingOpt.isPresent()) {
                dailySavings = existingOpt.get();
                dailySavings.setAmountSaved(dailySavings.getAmountSaved() + savings);
                dailySavings.setSwapCount(dailySavings.getSwapCount() + 1);
            } else {
                dailySavings = new DailySpotSavings(account, today);
                dailySavings.setAmountSaved(savings);
                dailySavings.setSwapCount(1);
            }

            savingsRepository.save(dailySavings);
            log.info("📈 [GCP] Updated daily savings: ₹{} total for {}",
                String.format("%.2f", dailySavings.getAmountSaved()), today);

        } catch (Exception e) {
            log.warn("⚠️ [GCP] Failed to update daily savings: {}", e.getMessage());
        }
    }
}
