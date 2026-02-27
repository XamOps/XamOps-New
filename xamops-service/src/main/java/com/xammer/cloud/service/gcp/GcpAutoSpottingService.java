package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.cloud.compute.v1.*;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.dto.autospotting.SpotInstanceDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.RedisCacheService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;


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
    private final RedisCacheService redisCacheService;

    private static final String CACHE_KEY_MIGS      = "gcp-autospotting-migs-";
    private static final String CACHE_KEY_INSTANCES  = "gcp-autospotting-instances-";
    private static final int    CACHE_TTL_MINUTES    = 240; // 4 hours

    public GcpAutoSpottingService(GcpClientProvider gcpClientProvider,
            CloudAccountRepository cloudAccountRepository,
            SpotOptimizationEventRepository eventRepository,
            DailySpotSavingsRepository savingsRepository,
            GcpPricingService pricingService,
            GcpRestTemplateService restTemplateService,
            RedisCacheService redisCacheService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.eventRepository = eventRepository;
        this.savingsRepository = savingsRepository;
        this.pricingService = pricingService;
        this.restTemplateService = restTemplateService;
        this.redisCacheService = redisCacheService;
    }

    public CloudAccount resolveAccount(String accountId) {
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
        return listMigsAllRegionsWithCosts(accountId, false);
    }

    public List<GcpAutoSpottingGroupDto> listMigsAllRegionsWithCosts(String accountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_MIGS + accountId;

        if (!forceRefresh) {
            java.util.Optional<List<GcpAutoSpottingGroupDto>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<GcpAutoSpottingGroupDto>>() {});
            if (cached.isPresent()) {
                log.info("--- GCP AUTOSPOTTING MIGS: loaded from Redis for accountId={} ---", accountId);
                return cached.get();
            }
        }

        CloudAccount account = resolveAccount(accountId);
        String gcpProjectId = account.getGcpProjectId();

        List<GcpAutoSpottingGroupDto> migs = listMigsAllRegions(accountId, gcpProjectId);

        for (GcpAutoSpottingGroupDto mig : migs) {
            try {
                enrichMigCostData(mig);
            } catch (Exception e) {
                log.warn("Failed to enrich cost data for MIG {}: {}", mig.name, e.getMessage());
            }
        }

        if (!migs.isEmpty()) {
            redisCacheService.put(cacheKey, migs, CACHE_TTL_MINUTES);
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
        log.info("📊 [GCP] Fetching history data for accountId={}, range={}", accountId, range);
        CloudAccount account = resolveAccount(accountId);

        LocalDate startDate;
        LocalDate endDate = LocalDate.now();

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

        // Step 1: Try to get aggregated daily savings
        List<DailySpotSavings> findings = savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(
                account.getId(), startDate, endDate);

        HistoryResponse response = new HistoryResponse();
        List<HistoryResponse.DataPoint> dataPoints = new ArrayList<>();

        double totalSaved = 0;
        for (DailySpotSavings saving : findings) {
            HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
            dp.setTimestamp(saving.getDate().toString());
            dp.setActualHourlySavings(saving.getAmountSaved());
            dp.setPotentialHourlySavings(saving.getAmountSaved() * 0.2);
            dataPoints.add(dp);
            totalSaved += saving.getAmountSaved();
        }

        // Step 2: Fallback to SpotOptimizationEvent if daily savings are empty
        // This allows the graph to show data from day one, even before daily aggregation runs
        if (dataPoints.isEmpty()) {
            try {
                log.info("📊 [GCP] No daily savings found, falling back to SpotOptimizationEvent table");
                java.time.Instant iStart = startDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                java.time.Instant iEnd = endDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();

                // Get all events in the date range (ENABLED, SUCCESS, etc.) that have savings
                List<SpotOptimizationEvent> spotEvents = eventRepository
                        .findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(
                                account.getId(), iStart, iEnd);

                // Group events by date and sum hourly savings
                java.util.Map<LocalDate, Double> savingsByDate = spotEvents.stream()
                        .filter(e -> e.getHourlySavings() != null && e.getHourlySavings() > 0)
                        .collect(java.util.stream.Collectors.groupingBy(
                                e -> e.getEventTime().atZone(java.time.ZoneOffset.UTC).toLocalDate(),
                                java.util.stream.Collectors.summingDouble(SpotOptimizationEvent::getHourlySavings)
                        ));

                // Convert to a sorted list to avoid mutating captured variables inside a lambda
                java.util.List<java.util.Map.Entry<LocalDate, Double>> sortedEntries =
                        new java.util.ArrayList<>(savingsByDate.entrySet());
                sortedEntries.sort(java.util.Map.Entry.comparingByKey());

                for (java.util.Map.Entry<LocalDate, Double> entry : sortedEntries) {
                    HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                    dp.setTimestamp(entry.getKey().toString());
                    dp.setActualHourlySavings(entry.getValue());
                    dp.setPotentialHourlySavings(entry.getValue() * 0.2);
                    dataPoints.add(dp);
                    totalSaved += entry.getValue();
                }

                if (!dataPoints.isEmpty()) {
                    log.info("✅ [GCP] Built {} history data point(s) from SpotOptimizationEvent table", dataPoints.size());
                }
            } catch (Exception ex) {
                log.warn("⚠️ [GCP] Could not build history from SpotOptimizationEvent: {}", ex.getMessage());
            }
        }

        // Step 3: Gap-fill missing dates with $0 entries for proper timeline
        long rangeDays = startDate.until(endDate, java.time.temporal.ChronoUnit.DAYS) + 1;
        if (rangeDays <= 90) {
            java.util.Set<LocalDate> covered = dataPoints.stream()
                    .map(dp -> LocalDate.parse(dp.getTimestamp().substring(0, 10)))
                    .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));

            LocalDate cur = startDate;
            while (!cur.isAfter(endDate)) {
                if (!covered.contains(cur)) {
                    HistoryResponse.DataPoint dp = new HistoryResponse.DataPoint();
                    dp.setTimestamp(cur.toString());
                    dp.setActualHourlySavings(0.0);
                    dp.setPotentialHourlySavings(0.0);
                    dataPoints.add(dp);
                }
                cur = cur.plusDays(1);
            }

            // Sort by timestamp to ensure proper order
            dataPoints.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            log.info("📊 [GCP] Gap-filled history data, total points: {}", dataPoints.size());
        }

        response.setDataPoints(dataPoints);

        HistoryResponse.HistorySummary summary = new HistoryResponse.HistorySummary();
        summary.setTotalActualSavings(totalSaved);
        summary.setAverageHourlySavings(dataPoints.size() > 0 ? totalSaved / dataPoints.size() : 0);

        // Calculate peak savings
        double peakSavings = dataPoints.stream()
                .mapToDouble(dp -> dp.getActualHourlySavings() != null ? dp.getActualHourlySavings() : 0.0)
                .max()
                .orElse(0.0);
        summary.setPeakSavings(peakSavings);

        response.setSummary(summary);

        log.info("✅ [GCP] Returning {} data points, total savings: ₹{}", dataPoints.size(), totalSaved);
        return response;
    }

    /**
     * Get events (actions history) for GCP AutoSpotting
     * Similar to AWS implementation but for GCP MIGs
     */
    public com.xammer.cloud.dto.autospotting.EventsResponse getEvents(
            String accountId, String start, String end, String eventType, String migName) {
        log.info("📋 [GCP] Fetching events from database for accountId={}", accountId);
        log.info("📋 [GCP] Filters - start={}, end={}, eventType={}, migName={}", start, end, eventType, migName);
        
        CloudAccount account = resolveAccount(accountId);
        
        try {
            java.time.Instant startTime = (start != null && !start.isEmpty()) 
                ? java.time.Instant.parse(start) 
                : java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS);
            java.time.Instant endTime = (end != null && !end.isEmpty()) 
                ? java.time.Instant.parse(end) 
                : java.time.Instant.now();
            
            List<SpotOptimizationEvent> events;
            
            // Filter events based on parameters
            if (migName != null && !migName.isEmpty()) {
                events = eventRepository.findByAccountIdAndAsgNameAndEventTimeBetweenOrderByEventTimeDesc(
                    account.getId(), migName, startTime, endTime);
            } else if (eventType != null && !eventType.isEmpty()) {
                String status = mapEventTypeToStatus(eventType);
                events = eventRepository.findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                    account.getId(), startTime, endTime, status);
            } else {
                events = eventRepository.findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(
                    account.getId(), startTime, endTime);
            }
            
            // Transform events to EventRecord format
            List<com.xammer.cloud.dto.autospotting.EventRecord> eventRecords = events.stream().map(event -> {
                com.xammer.cloud.dto.autospotting.EventRecord record = new com.xammer.cloud.dto.autospotting.EventRecord();
                record.setTimestamp(event.getEventTime().toString());
                record.setAccountId(account.getGcpProjectId());
                record.setRegion(event.getRegion());
                record.setAsgName(event.getAsgName()); // MIG name
                record.setEstimatedSavings(event.getHourlySavings());
                
                // Map status to event_type, result, and details
                String evStatus = event.getStatus();
                if ("SUCCESS".equals(evStatus)) {
                    record.setEventType("instance_replacement");
                    record.setResult("Success");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldInstanceType(event.getOldInstanceType());
                    record.setOldLifecycle("on-demand");
                    record.setNewInstanceId(event.getNewInstanceId());
                    record.setNewInstanceType(event.getNewInstanceType());
                    record.setNewLifecycle("spot");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                        : "On-Demand instance replaced with Spot VM. Hourly savings: ₹"
                        + String.format("%.4f", event.getHourlySavings() != null ? event.getHourlySavings() : 0.0));
                } else if ("INTERRUPTED".equals(evStatus)) {
                    record.setEventType("spot_interruption");
                    record.setResult("Interrupted");
                    record.setOldInstanceId(event.getOldInstanceId());
                    record.setOldLifecycle("spot");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                        : "GCP reclaimed this Spot VM. MIG will automatically replace it.");
                } else if ("ENABLED".equals(evStatus)) {
                    record.setEventType("mig_enabled");
                    record.setResult("Enabled");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                        : "AutoSpotting enabled for MIG by user.");
                } else if ("DISABLED".equals(evStatus)) {
                    record.setEventType("mig_disabled");
                    record.setResult("Disabled");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                        : "AutoSpotting disabled for MIG by user.");
                } else if ("FAILED".equals(evStatus)) {
                    record.setEventType("launch_failure");
                    record.setResult("Failed");
                    record.setNewInstanceId(event.getNewInstanceId());
                    record.setNewInstanceType(event.getNewInstanceType());
                    record.setNewLifecycle("on-demand");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                        : "Spot unavailable — On-Demand VM launched as fallback.");
                } else {
                    record.setEventType("launch_failure");
                    record.setResult("Failed");
                    record.setReason(event.getDetails() != null ? event.getDetails()
                        : "Spot launch or replacement failed.");
                }
                
                return record;
            }).collect(Collectors.toList());
            
            // Calculate summary statistics
            int totalReplacements = (int) eventRecords.stream()
                .filter(e -> "instance_replacement".equals(e.getEventType()))
                .count();
            int totalInterruptions = (int) eventRecords.stream()
                .filter(e -> "spot_interruption".equals(e.getEventType()))
                .count();

            // Debug: Log each event's savings
            eventRecords.stream()
                .filter(e -> e.getEstimatedSavings() != null)
                .forEach(e -> log.info("🔍 [GCP Events Debug] Event savings: ₹{}",
                    String.format("%.4f", e.getEstimatedSavings())));

            double totalSavings = eventRecords.stream()
                .filter(e -> e.getEstimatedSavings() != null)
                .mapToDouble(com.xammer.cloud.dto.autospotting.EventRecord::getEstimatedSavings)
                .sum();

            log.info("🔍 [GCP Events Debug] Calculated total savings: ₹{}", String.format("%.4f", totalSavings));
            
            // Build response
            com.xammer.cloud.dto.autospotting.EventsResponse response = new com.xammer.cloud.dto.autospotting.EventsResponse();
            response.setStart(startTime.toString());
            response.setEnd(endTime.toString());
            response.setCount(eventRecords.size());
            response.setEvents(eventRecords);
            
            com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary summary = 
                new com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary();
            summary.setTotalReplacements(totalReplacements);
            summary.setTotalInterruptions(totalInterruptions);
            summary.setTotalEstimatedSavings(totalSavings);
            response.setSummary(summary);
            
            log.info("✅ [GCP] Retrieved {} events with {} replacements, {} interruptions, ₹{} total savings",
                eventRecords.size(), totalReplacements, totalInterruptions, String.format("%.2f", totalSavings));
            
            return response;
            
        } catch (Exception e) {
            log.error("❌ [GCP] Failed to fetch events: {}", e.getMessage(), e);
            // Return empty response on error
            return createEmptyEventsResponse();
        }
    }
    
    /**
     * Map event type to status for filtering
     */
    private String mapEventTypeToStatus(String eventType) {
        switch (eventType) {
            case "instance_replacement":
                return "SUCCESS";
            case "spot_interruption":
                return "INTERRUPTED";
            case "mig_enabled":
            case "asg_enabled":
                return "ENABLED";
            case "mig_disabled":
            case "asg_disabled":
                return "DISABLED";
            case "launch_failure":
                return "FAILED";
            default:
                return "";
        }
    }
    
    /**
     * Create empty events response
     */
    private com.xammer.cloud.dto.autospotting.EventsResponse createEmptyEventsResponse() {
        com.xammer.cloud.dto.autospotting.EventsResponse response = new com.xammer.cloud.dto.autospotting.EventsResponse();
        response.setEvents(Collections.emptyList());
        response.setCount(0);
        
        com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary summary = 
            new com.xammer.cloud.dto.autospotting.EventsResponse.EventsSummary();
        summary.setTotalReplacements(0);
        summary.setTotalInterruptions(0);
        summary.setTotalEstimatedSavings(0.0);
        response.setSummary(summary);
        
        return response;
    }

    public List<SpotInstanceDto> getSpotInstances(String accountId) {
        return getSpotInstances(accountId, false);
    }

    public List<SpotInstanceDto> getSpotInstances(String accountId, boolean forceRefresh) {
        String cacheKey = CACHE_KEY_INSTANCES + accountId;

        if (!forceRefresh) {
            java.util.Optional<List<SpotInstanceDto>> cached = redisCacheService.get(
                    cacheKey, new TypeReference<List<SpotInstanceDto>>() {});
            if (cached.isPresent()) {
                log.info("--- GCP AUTOSPOTTING INSTANCES: loaded from Redis for accountId={} ---", accountId);
                return cached.get();
            }
        }

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

        if (!instances.isEmpty()) {
            redisCacheService.put(cacheKey, instances, CACHE_TTL_MINUTES);
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

                // Evict cache so next fetch reflects the updated MIG state
                redisCacheService.evict(CACHE_KEY_MIGS + accountId);
                redisCacheService.evict(CACHE_KEY_INSTANCES + accountId);

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

                // Evict cache so next fetch reflects the updated MIG state
                redisCacheService.evict(CACHE_KEY_MIGS + accountId);
                redisCacheService.evict(CACHE_KEY_INSTANCES + accountId);

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
            double totalHourlySavings = instanceCount * hourlySavingsPerInstance;
            log.info("🔍 [GCP Event Record Debug] instanceCount={}, savingsPerInstance=₹{}, total=₹{}",
                instanceCount,
                String.format("%.4f", hourlySavingsPerInstance),
                String.format("%.4f", totalHourlySavings));

            // Record optimization event
            SpotOptimizationEvent event = new SpotOptimizationEvent();
            event.setAccount(account);
            event.setAsgName(migName);
            event.setRegion(region);
            event.setStatus(enabled ? "ENABLED" : "DISABLED");
            event.setHourlySavings(totalHourlySavings);
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

    /**
     * Scheduled job to record daily savings for all active spot instances
     * Runs every day at 11:30 PM
     */
    @Scheduled(cron = "0 30 23 * * *")
    public void recordDailySavingsForAllAccounts() {
        log.info("🕐 [GCP] Starting scheduled daily savings recording...");
        
        try {
            // Get all GCP accounts
            List<CloudAccount> gcpAccounts = cloudAccountRepository.findByProvider("GCP");
            
            for (CloudAccount account : gcpAccounts) {
                try {
                    recordDailySavingsForAccount(account);
                } catch (Exception e) {
                    log.error("❌ [GCP] Failed to record daily savings for account {}: {}", 
                        account.getGcpProjectId(), e.getMessage());
                }
            }
            
            log.info("✅ [GCP] Completed scheduled daily savings recording");
        } catch (Exception e) {
            log.error("❌ [GCP] Failed to execute scheduled daily savings job: {}", e.getMessage());
        }
    }
    
    /**
     * Record daily savings for a specific account based on currently running spot instances
     */
    private void recordDailySavingsForAccount(CloudAccount account) {
        String accountId = String.valueOf(account.getId());
        String gcpProjectId = account.getGcpProjectId();
        
        log.info("📊 [GCP] Recording daily savings for account: {}", gcpProjectId);
        
        // Get all MIGs with their current state
        List<GcpAutoSpottingGroupDto> migs = listMigsAllRegionsWithCosts(accountId);
        
        // Calculate total daily savings from all spot instances
        double totalDailySavings = 0.0;
        int totalSpotInstances = 0;
        
        for (GcpAutoSpottingGroupDto mig : migs) {
            if (mig.isEnabled && mig.spotInstanceCount > 0) {
                // Calculate hourly savings per instance
                try {
                    GcpPricingService.PricingInfo pricing = pricingService.getMachineTypePricing(
                        mig.machineType != null ? mig.machineType : "n1-standard-1",
                        mig.region
                    );
                    
                    double hourlySavingsPerInstance = pricing.getOnDemandPrice() - pricing.getSpotPrice();
                    // Daily savings = hourly savings × 24 hours × number of spot instances
                    double dailySavingsForMig = hourlySavingsPerInstance * 24 * mig.spotInstanceCount;
                    
                    totalDailySavings += dailySavingsForMig;
                    totalSpotInstances += mig.spotInstanceCount;
                    
                    log.debug("💰 [GCP] MIG {} has {} spot instances, daily savings: ₹{}", 
                        mig.name, mig.spotInstanceCount, String.format("%.2f", dailySavingsForMig));
                        
                } catch (Exception e) {
                    log.warn("⚠️ [GCP] Failed to calculate savings for MIG {}: {}", mig.name, e.getMessage());
                }
            }
        }
        
        // Only record if there are actual spot instances running
        if (totalSpotInstances > 0 && totalDailySavings > 0) {
            LocalDate today = LocalDate.now();
            
            // Find or create today's savings record
            Optional<DailySpotSavings> existingOpt = 
                savingsRepository.findByAccountIdAndDate(account.getId(), today);
            
            DailySpotSavings dailySavings;
            if (existingOpt.isPresent()) {
                dailySavings = existingOpt.get();
                // For scheduled recording, we SET the amount (not add), as this is the total daily savings
                dailySavings.setAmountSaved(totalDailySavings);
            } else {
                dailySavings = new DailySpotSavings(account, today);
                dailySavings.setAmountSaved(totalDailySavings);
                dailySavings.setSwapCount(0); // No swap event, just daily tracking
            }
            
            savingsRepository.save(dailySavings);
            log.info("💾 [GCP] Recorded daily savings for {}: ₹{} from {} spot instances", 
                gcpProjectId, String.format("%.2f", totalDailySavings), totalSpotInstances);
        } else {
            log.debug("ℹ️ [GCP] No spot instances running for account: {}", gcpProjectId);
        }
    }
}
