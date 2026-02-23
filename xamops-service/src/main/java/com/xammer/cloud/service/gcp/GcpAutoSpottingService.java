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

    public GcpAutoSpottingService(GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            SpotOptimizationEventRepository eventRepository,
            DailySpotSavingsRepository savingsRepository) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
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
    }

    public List<GcpAutoSpottingGroupDto> listMigsAllRegionsWithCosts(String accountId) {
        resolveAccount(accountId);
        List<GcpAutoSpottingGroupDto> migs = listMigsAllRegions(accountId);

        for (GcpAutoSpottingGroupDto mig : migs) {
            try {
                // Enrich with cost data (simplified for now)
                enrichMigCostData(mig);
            } catch (Exception e) {
                log.warn("Failed to enrich cost data for MIG {}: {}", mig.name, e.getMessage());
            }
        }

        return migs;
    }

    private void enrichMigCostData(GcpAutoSpottingGroupDto mig) {
        // Mocked cost data for now - in a real implementation, we'd fetch prices from
        // Cloud Catalog API
        // or a local database of GCP prices.
        double avgOdPrice = 0.05; // $0.05/hr
        double avgSpotPrice = 0.015; // $0.015/hr (approx 70% savings)

        mig.currentHourlyCost = (mig.ondemandInstanceCount * avgOdPrice) + (mig.spotInstanceCount * avgSpotPrice);
        mig.potentialHourlyCost = mig.instanceCount * avgSpotPrice;

        if (mig.isEnabled) {
            mig.actualHourlySavings = (mig.spotInstanceCount * (avgOdPrice - avgSpotPrice));
        } else {
            mig.actualHourlySavings = 0.0;
        }

        mig.potentialHourlySavings = mig.instanceCount * (avgOdPrice - avgSpotPrice);
    }

    public List<GcpAutoSpottingGroupDto> listMigsAllRegions(String accountId) {
        CloudAccount account = resolveAccount(accountId);

        String gcpProjectId = account.getGcpProjectId();
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
                        allMigs.add(mapToDto(igm));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list MIGs for project {}: {}", gcpProjectId, e.getMessage(), e);
        }

        return allMigs;
    }

    private GcpAutoSpottingGroupDto mapToDto(InstanceGroupManager igm) {
        int instanceCount = igm.getTargetSize();

        // Check if current template/MIG configuration uses SPOT
        // This is a bit complex as we'd need to fetch the instance template
        // For simplicity, we check if the name contains 'spot' or 'preempt'
        boolean isSpotEnabled = igm.getName().toLowerCase().contains("spot") ||
                igm.getName().toLowerCase().contains("preempt");

        String location = igm.hasZone() ? igm.getZone() : (igm.hasRegion() ? igm.getRegion() : "global");
        if (location != null && location.contains("/")) {
            location = location.substring(location.lastIndexOf('/') + 1);
        }

        return GcpAutoSpottingGroupDto.builder()
                .name(igm.getName())
                .region(location)
                .isEnabled(isSpotEnabled)
                .instanceCount(instanceCount)
                .ondemandInstanceCount(isSpotEnabled ? 0 : instanceCount)
                .spotInstanceCount(isSpotEnabled ? instanceCount : 0)
                .build();
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

            log.info("🔍 [GCP DEBUG] Step 5: Building new SPOT template: {}", newTemplateName);

            // For SPOT instances, we MUST set onHostMaintenance to TERMINATE
            // and it's recommended to set automaticRestart to false.
            InstanceTemplate.Builder templateBuilder = InstanceTemplate.newBuilder(currentTemplate)
                    .setName(newTemplateName);

            templateBuilder.clearSelfLink(); // Clear output-only fields
            templateBuilder.clearCreationTimestamp();
            templateBuilder.clearId();

            if (currentTemplate.hasProperties()) {
                InstanceProperties.Builder propsBuilder = currentTemplate.getProperties().toBuilder();
                Scheduling.Builder schedBuilder = propsBuilder.hasScheduling()
                        ? propsBuilder.getScheduling().toBuilder()
                        : Scheduling.newBuilder();

                schedBuilder.setProvisioningModel("SPOT")
                        .setPreemptible(true)
                        .setOnHostMaintenance("TERMINATE")
                        .setAutomaticRestart(false);

                propsBuilder.setScheduling(schedBuilder.build());
                templateBuilder.setProperties(propsBuilder.build());
            }

            InstanceTemplate newTemplate = templateBuilder.build();

            String newTemplateUri;
            if (templateRegion != null) {
                log.info("🔍 [GCP DEBUG] Step 6: Inserting REGIONAL SPOT template in {}", templateRegion);
                Optional<RegionInstanceTemplatesClient> clientOpt = gcpClientProvider
                        .getRegionInstanceTemplatesClient(projectId);
                try (RegionInstanceTemplatesClient client = clientOpt.get()) {
                    InsertRegionInstanceTemplateRequest request = InsertRegionInstanceTemplateRequest.newBuilder()
                            .setProject(projectId)
                            .setRegion(templateRegion)
                            .setInstanceTemplateResource(newTemplate)
                            .build();
                    client.insertAsync(request).get();
                    newTemplateUri = String.format("projects/%s/regions/%s/instanceTemplates/%s", projectId,
                            templateRegion, newTemplateName);
                }
            } else {
                log.info("🔍 [GCP DEBUG] Step 6: Inserting GLOBAL SPOT template");
                Optional<InstanceTemplatesClient> clientOpt = gcpClientProvider.getInstanceTemplatesClient(projectId);
                try (InstanceTemplatesClient client = clientOpt.get()) {
                    InsertInstanceTemplateRequest request = InsertInstanceTemplateRequest.newBuilder()
                            .setProject(projectId)
                            .setInstanceTemplateResource(newTemplate)
                            .build();
                    client.insertAsync(request).get();
                    newTemplateUri = String.format("projects/%s/global/instanceTemplates/%s", projectId,
                            newTemplateName);
                }
            }
            log.info("🔍 [GCP DEBUG] Step 7: Successfully created new template: {}", newTemplateUri);

            log.info("🔍 [GCP DEBUG] Step 8: Updating MIG {} to use new template", igmName);
            updateMigTemplate(projectId, region, igmName, newTemplateUri);
            log.info("✅ [GCP DEBUG] Successfully updated MIG {} to use SPOT template {}", igmName, newTemplateName);

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

            InstanceTemplate newTemplate = InstanceTemplate.newBuilder(currentTemplate)
                    .setName(newTemplateName)
                    .setSelfLink("")
                    .setProperties(currentTemplate.getProperties().toBuilder()
                            .setScheduling(currentTemplate.getProperties().getScheduling().toBuilder()
                                    .setProvisioningModel("STANDARD")
                                    .setPreemptible(false)
                                    .build())
                            .build())
                    .build();

            String newTemplateUri;
            if (templateRegion != null) {
                Optional<RegionInstanceTemplatesClient> clientOpt = gcpClientProvider
                        .getRegionInstanceTemplatesClient(projectId);
                try (RegionInstanceTemplatesClient client = clientOpt.get()) {
                    InsertRegionInstanceTemplateRequest request = InsertRegionInstanceTemplateRequest.newBuilder()
                            .setProject(projectId)
                            .setRegion(templateRegion)
                            .setInstanceTemplateResource(newTemplate)
                            .build();
                    client.insertAsync(request).get();
                    newTemplateUri = String.format("projects/%s/regions/%s/instanceTemplates/%s", projectId,
                            templateRegion, newTemplateName);
                }
            } else {
                Optional<InstanceTemplatesClient> clientOpt = gcpClientProvider.getInstanceTemplatesClient(projectId);
                try (InstanceTemplatesClient client = clientOpt.get()) {
                    InsertInstanceTemplateRequest request = InsertInstanceTemplateRequest.newBuilder()
                            .setProject(projectId)
                            .setInstanceTemplateResource(newTemplate)
                            .build();
                    client.insertAsync(request).get();
                    newTemplateUri = String.format("projects/%s/global/instanceTemplates/%s", projectId,
                            newTemplateName);
                }
            }

            updateMigTemplate(projectId, region, igmName, newTemplateUri);
            log.info("Successfully updated MIG {} to use ON-DEMAND template {}", igmName, newTemplateName);

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
}
