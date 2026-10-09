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
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
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
        // findByProviderAccountId returns List to tolerate multi-tenant duplicates; take first match
        return cloudAccountRepository.findByProviderAccountId(accountId)
                .stream().findFirst()
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

        /**
         * Currency of every cost field above. Always USD.
         *
         * <p>Stated explicitly because these figures used to be INR while the rest of the GCP
         * dashboard was USD, and the dashboard summed the two.
         */
        public String currency;

        /** {@link GcpPricingService.PriceSource}, so the UI can distinguish a list price from an estimate. */
        public String priceSource;

        /** Why the price is estimated or missing. Null when it is a plain catalog list price. */
        public String pricingNote;

        /**
         * True when this group could not be priced. The cost fields are zero in that case, and
         * zero means "not counted" rather than "free".
         */
        public boolean savingsUnknown;

        /**
         * True when this MIG backs a GKE node pool (name starts with {@code gke-} or its template
         * carries the {@code goog-gke-node} label). GKE controls these VMs and reverts template
         * changes made outside it; GKE only offers Spot on new node pools. XamOps never converts
         * them, and their potential savings are reported as 0 because they aren't actionable here.
         */
        public boolean gkeManagedPool;

        /**
         * True when the MIG's current template was created by XamOps AutoSpotting (its description
         * carries the XamOps marker). For a GKE pool this is the only case where Disable is allowed:
         * XamOps may undo its own conversion but never touches a native GKE Spot pool.
         */
        public boolean xamopsManagedTemplate;
    }

    /** Label GKE puts on every node-pool instance template. */
    static final String GKE_NODE_LABEL = "goog-gke-node";

    /** Marker that {@link GcpRestTemplateService#createSpotTemplate} writes into the template description. */
    static final String XAMOPS_TEMPLATE_MARKER = "created by XamOps AutoSpotting";

    /** GKE-managed if the MIG name has GKE's {@code gke-} prefix or the template has the GKE node label. */
    static boolean isGkeManaged(String migName, Map<String, String> templateLabels) {
        if (migName != null && migName.startsWith("gke-")) {
            return true;
        }
        return templateLabels != null && templateLabels.containsKey(GKE_NODE_LABEL);
    }

    /** True when the template description carries the XamOps AutoSpotting marker. */
    static boolean isXamOpsCreatedTemplate(String description) {
        return description != null && description.contains(XAMOPS_TEMPLATE_MARKER);
    }

    /** {@link #isGkeManaged} for REST template JSON ({@code properties.labels}). */
    static boolean isGkeManagedJson(String migName, com.google.gson.JsonObject template) {
        Map<String, String> labels = new HashMap<>();
        if (template != null && template.has("properties") && template.get("properties").isJsonObject()) {
            com.google.gson.JsonObject properties = template.getAsJsonObject("properties");
            if (properties.has("labels") && properties.get("labels").isJsonObject()) {
                properties.getAsJsonObject("labels").entrySet().forEach(e -> labels.put(e.getKey(),
                        e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : ""));
            }
        }
        return isGkeManaged(migName, labels);
    }

    /** {@link #isXamOpsCreatedTemplate} for REST template JSON (top-level {@code description}). */
    static boolean isXamOpsCreatedTemplateJson(com.google.gson.JsonObject template) {
        if (template == null || !template.has("description") || !template.get("description").isJsonPrimitive()) {
            return false;
        }
        return isXamOpsCreatedTemplate(template.get("description").getAsString());
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
                enrichMigCostData(mig, gcpProjectId);
            } catch (Exception e) {
                log.warn("Failed to enrich cost data for MIG {}: {}", mig.name, e.getMessage());
                markCostUnknown(mig, "Cost enrichment failed: " + e.getMessage());
            }
            if (mig.gkeManagedPool) {
                // Not actionable from XamOps, so it must not count toward potential savings.
                mig.potentialHourlySavings = 0.0;
            }
        }

        if (!migs.isEmpty()) {
            redisCacheService.put(cacheKey, migs, CACHE_TTL_MINUTES);
        }

        return migs;
    }

    private void enrichMigCostData(GcpAutoSpottingGroupDto mig, String gcpProjectId) {
        String region = extractRegionFromZone(mig.region);

        // Substituting n1-standard-1 for an unknown machine type produced a confident cost for a
        // machine whose shape nobody knew. An unpriced row is the honest answer.
        if (mig.machineType == null || mig.machineType.isBlank()) {
            markCostUnknown(mig, "Machine type unknown, so this group cannot be costed");
            return;
        }
        String machineType = mig.machineType;

        try {
            GcpPricingService.PricingInfo pricing =
                    pricingService.getMachineTypePricing(machineType, region, gcpProjectId);

            if (!pricing.isPriced()) {
                markCostUnknown(mig, "No list price available for " + machineType + " in " + region
                        + (pricing.getNote() == null ? "" : " (" + pricing.getNote() + ")"));
                return;
            }

            mig.priceSource = pricing.getSource().name();
            mig.currency = pricing.getCurrency();
            mig.pricingNote = pricing.getNote();

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

            log.debug("Enriched cost data for MIG {}: machineType={}, onDemand=${}/hr, spot=${}/hr, "
                    + "savings=${}/hr, source={}",
                    mig.name, machineType,
                    String.format("%.4f", onDemandPricePerInstance),
                    String.format("%.4f", spotPricePerInstance),
                    String.format("%.4f", mig.potentialHourlySavings),
                    pricing.getSource());

        } catch (Exception e) {
            // The previous fallback invented $0.05/hr on-demand and $0.015/hr spot for any machine
            // type, which is both unrelated to the machine and denominated differently from the
            // INR the pricing service returned at the time.
            log.warn("Failed to get pricing for machine type {}: {}", machineType, e.getMessage());
            markCostUnknown(mig, "Pricing lookup failed for " + machineType);
        }
    }

    /**
     * Zeroes a group's cost fields and records why.
     *
     * <p>Zero here means "not counted", not "free". {@code savingsUnknown} is what the dashboard
     * reads to keep the row out of the savings total instead of contributing a figure nobody can
     * stand behind.
     */
    private void markCostUnknown(GcpAutoSpottingGroupDto mig, String reason) {
        mig.currentHourlyCost = 0.0;
        mig.potentialHourlyCost = 0.0;
        mig.actualHourlySavings = 0.0;
        mig.potentialHourlySavings = 0.0;
        mig.savingsUnknown = true;
        mig.currency = "USD";
        mig.priceSource = GcpPricingService.PriceSource.UNAVAILABLE.name();
        mig.pricingNote = reason;
        log.debug("[GCP AutoSpotting] {} reported unpriced: {}", mig.name, reason);
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
        // Name-only GKE detection unless the template is fetched below.
        boolean gkeManagedPool = isGkeManaged(igm.getName(), null);
        boolean xamopsManagedTemplate = false;

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

                    gkeManagedPool = isGkeManaged(igm.getName(), template.getProperties().getLabelsMap());
                }
                if (template != null) {
                    xamopsManagedTemplate = isXamOpsCreatedTemplate(template.getDescription());
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
                .gkeManagedPool(gkeManagedPool)
                .xamopsManagedTemplate(xamopsManagedTemplate)
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

        log.info("✅ [GCP] Returning {} data points, total savings: ${}", dataPoints.size(), totalSaved);
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
                        : "On-Demand instance replaced with Spot VM. Hourly savings: $"
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
                .forEach(e -> log.info("🔍 [GCP Events Debug] Event savings: ${}",
                    String.format("%.4f", e.getEstimatedSavings())));

            double totalSavings = eventRecords.stream()
                .filter(e -> e.getEstimatedSavings() != null)
                .mapToDouble(com.xammer.cloud.dto.autospotting.EventRecord::getEstimatedSavings)
                .sum();

            log.info("🔍 [GCP Events Debug] Calculated total savings: ${}", String.format("%.4f", totalSavings));
            
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
            
            log.info("✅ [GCP] Retrieved {} events with {} replacements, {} interruptions, ${} total savings",
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
            // GKE node pools: GKE owns these VMs, so refuse before any template fetch or REST call.
            if (isGkeManaged(igmName, null)) {
                log.warn("[GCP] Refusing to enable Spot on GKE-managed MIG {}", igmName);
                throw AutoSpottingBlockedException.gkeManaged();
            }
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

            // A GKE node-pool template can sit behind a MIG without the gke- prefix.
            if (isGkeManaged(igmName, currentTemplate.getProperties().getLabelsMap())) {
                log.warn("[GCP] Refusing to enable Spot on MIG {}: its template has the {} label", igmName,
                        GKE_NODE_LABEL);
                throw AutoSpottingBlockedException.gkeManaged();
            }

            // ── CRITICAL FIX: Use REST API to check SPOT status ──────────────────────────
            // The Java SDK gRPC client can return empty-string for provisioningModel even
            // when the field is set to "SPOT" via REST. Check via REST JSON instead.
            boolean isAlreadySpot;
            try {
                isAlreadySpot = restTemplateService.isSpotTemplate(projectId, templateRegion, currentTemplateName);
            } catch (Exception e) {
                log.warn("⚠️ [GCP] REST SPOT check failed, falling back to SDK: {}", e.getMessage());
                isAlreadySpot = currentTemplate.getProperties().hasScheduling()
                    && ("SPOT".equals(currentTemplate.getProperties().getScheduling().getProvisioningModel())
                        || currentTemplate.getProperties().getScheduling().getPreemptible());
            }

            if (isAlreadySpot) {
                log.info("ℹ️ [GCP] MIG {} is already using a SPOT template.", igmName);
                return;
            }

            // ── Build a safe spot template name (max 63 chars) ───────────────────────────
            String base = currentTemplateName
                .replace("-od-", "-spot-")
                .replace("-od", "-spot");
            if (!base.contains("-spot-") && !base.endsWith("-spot")) {
                base = base + "-spot";
            }
            String suffix = "-" + (System.currentTimeMillis() / 1000 % 100_000_000);
            String newTemplateName = base + suffix;
            if (newTemplateName.length() > 63) {
                newTemplateName = "spot-" + (System.currentTimeMillis() / 1000 % 100_000_000);
            }

            log.info("🔧 [GCP] Creating SPOT template using REST API: {}", newTemplateName);

            try {
                // Step 1: Get current template JSON via REST API
                log.info("🔍 [GCP] Step 1: Fetching current template: {}", currentTemplateName);
                com.google.gson.JsonObject sourceTemplate = restTemplateService.getInstanceTemplate(
                    projectId, templateRegion, currentTemplateName);

                // Step 2: Create new SPOT template
                log.info("🔧 [GCP] Step 2: Creating SPOT template: {}", newTemplateName);
                restTemplateService.createSpotTemplate(
                    projectId, templateRegion, newTemplateName, sourceTemplate, true);
                log.info("✅ [GCP] Created SPOT template: {}", newTemplateName);

                // Step 3: Build the full self-link URL (GCP REST API requires full URL for instanceTemplate field)
                String newTemplateUrl;
                if (templateRegion != null) {
                    newTemplateUrl = String.format(
                        "https://www.googleapis.com/compute/v1/projects/%s/regions/%s/instanceTemplates/%s",
                        projectId, templateRegion, newTemplateName);
                } else {
                    newTemplateUrl = String.format(
                        "https://www.googleapis.com/compute/v1/projects/%s/global/instanceTemplates/%s",
                        projectId, newTemplateName);
                }

                // Step 4: Rolling update (PATCH MIG with new template + PROACTIVE policy)
                int numZones = igm.hasDistributionPolicy()
                        ? igm.getDistributionPolicy().getZonesCount() : 0;
                log.info("🔄 [GCP] Step 4: Rolling update → SPOT (numZones={})", numZones);
                restTemplateService.startRollingUpdate(projectId, region, igmName, newTemplateUrl, numZones);
                log.info("✅ [GCP] Rolling update initiated. Instances being replaced with SPOT VMs gradually.");

                // The previous template is kept on purpose: it may be customer-owned, and it is
                // the rollback target. Template cleanup is a Phase B concern.
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
                        machineTypeName, extractRegionFromZone(region), projectId);
                    if (!pricing.isPriced()) {
                        // Recording a savings event with a fabricated rate would put a wrong number
                        // into the persisted daily-savings history, where it is never revisited.
                        log.warn("⚠️ [GCP] Spot enabled for {} but {} could not be priced ({}); no "
                                + "savings event recorded", igmName, machineTypeName, pricing.getNote());
                    } else {
                        double savingsPerInstance = pricing.getOnDemandPrice() - pricing.getSpotPrice();
                        recordSavingsEvent(account, igmName, region, instanceCount, savingsPerInstance, true);
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [GCP] Could not record savings event: {}", e.getMessage());
                }

            } catch (AutoSpottingBlockedException e) {
                throw e;
            } catch (Exception e) {
                log.error("❌ [GCP] Failed to enable SPOT: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to enable Spot: " + e.getMessage());
            }

        } catch (AutoSpottingBlockedException e) {
            throw e;
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
        log.info("🔴 [GCP] Disabling AutoSpotting (Spot→OnDemand) for MIG {} in project {}", igmName, projectId);

        // Name of the On-Demand template once its insert has been attempted; logged if a later
        // step fails so the orphan can be found (cleanup is Phase B).
        String insertedTemplateName = null;
        try {
            InstanceGroupManager igm = getMig(projectId, region, igmName);
            String currentTemplateUri = igm.getInstanceTemplate();
            if (currentTemplateUri == null || currentTemplateUri.isBlank()) {
                throw new RuntimeException("MIG " + igmName + " has no instance template");
            }

            String currentTemplateName = currentTemplateUri.substring(currentTemplateUri.lastIndexOf('/') + 1);
            String templateRegion = getTemplateRegion(currentTemplateUri);

            // One REST read gives Spot status, labels and description, and it is the clone source.
            // The REST JSON is used because the gRPC SDK can return an empty provisioningModel
            // for templates created over REST.
            com.google.gson.JsonObject source = restTemplateService.getInstanceTemplate(
                    projectId, templateRegion, currentTemplateName);

            if (!GcpRestTemplateService.isSpotTemplateJson(source)) {
                log.info("ℹ️ [GCP] MIG {} is already using a non-SPOT template, nothing to disable.", igmName);
                return;
            }

            if (isGkeManagedJson(igmName, source)) {
                if (!isXamOpsCreatedTemplateJson(source)) {
                    // A native GKE Spot node pool: GKE owns it, XamOps never touches it.
                    log.warn("[GCP] Refusing to disable Spot on GKE-managed MIG {}: template {} was not "
                            + "created by XamOps", igmName, currentTemplateName);
                    throw AutoSpottingBlockedException.gkeManaged();
                }
                log.warn("[GCP] Reverting XamOps-converted GKE MIG {} to On-Demand without a cordon/drain; "
                        + "GKE may reconcile the node pool", igmName);
            }

            // ── Build a safe on-demand template name (max 63 chars, GCP requirement) ─────
            String base = currentTemplateName
                .replace("-spot-", "-od-")
                .replace("-spot", "-od");
            if (!base.contains("-od-") && !base.endsWith("-od")) {
                base = "od-" + currentTemplateName;
            }
            // Append short epoch suffix (last 8 digits keeps name short)
            String suffix = "-" + (System.currentTimeMillis() / 1000 % 100_000_000);
            String newTemplateName = base + suffix;
            if (newTemplateName.length() > 63) {
                newTemplateName = "od-" + (System.currentTimeMillis() / 1000 % 100_000_000);
            }

            log.info("🔧 [GCP] Creating ON-DEMAND template: {}", newTemplateName);

            // Step 1: Create the ON-DEMAND clone (keeps GPUs, accelerator-aware host maintenance)
            insertedTemplateName = newTemplateName;
            restTemplateService.createSpotTemplate(projectId, templateRegion, newTemplateName, source, false);
            log.info("✅ [GCP] Created ON-DEMAND template: {}", newTemplateName);

            // Step 2: Build the full self-link URL (GCP REST API requires full URL for instanceTemplate field)
            String newTemplateUrl;
            if (templateRegion != null) {
                newTemplateUrl = String.format(
                    "https://www.googleapis.com/compute/v1/projects/%s/regions/%s/instanceTemplates/%s",
                    projectId, templateRegion, newTemplateName);
            } else {
                newTemplateUrl = String.format(
                    "https://www.googleapis.com/compute/v1/projects/%s/global/instanceTemplates/%s",
                    projectId, newTemplateName);
            }

            // Step 3: Rolling update back to ON-DEMAND. A regional MIG with no zone count is
            // refused by startRollingUpdate before any request.
            int numZones = igm.hasDistributionPolicy()
                    ? igm.getDistributionPolicy().getZonesCount() : 0;
            log.info("🔄 [GCP] Step 3: Rolling update → ON-DEMAND (numZones={})", numZones);
            restTemplateService.startRollingUpdate(projectId, region, igmName, newTemplateUrl, numZones);
            log.info("✅ [GCP] Rolling update accepted. Instances will be replaced with ON-DEMAND VMs gradually.");

            // The previous Spot template is kept: it may be customer-owned. Cleanup is Phase B.
            log.info("✅ [GCP] Successfully disabled SPOT for MIG: {}", igmName);

            // Only now, after the rollout PATCH operation completed without error.
            redisCacheService.evict(CACHE_KEY_MIGS + accountId);
            redisCacheService.evict(CACHE_KEY_INSTANCES + accountId);

            try {
                int instanceCount = igm.getTargetSize();
                SpotOptimizationEvent event = new SpotOptimizationEvent();
                event.setAccount(account);
                event.setAsgName(igmName);
                event.setRegion(region);
                event.setStatus("DISABLED");
                event.setHourlySavings(0.0);
                event.setDetails(String.format("Disabled AutoSpotting for MIG %s with %d instance(s)", igmName, instanceCount));
                event.setEventTime(java.time.Instant.now());
                eventRepository.save(event);
            } catch (Exception ev) {
                log.warn("⚠️ [GCP] Could not record disable event: {}", ev.getMessage());
            }

        } catch (AutoSpottingBlockedException e) {
            logOrphanTemplate(insertedTemplateName, igmName);
            throw e;
        } catch (Exception e) {
            logOrphanTemplate(insertedTemplateName, igmName);
            log.error("Failed to disable Spot for MIG {}: {}", igmName, e.getMessage(), e);
            throw new RuntimeException("Failed to disable Spot: " + e.getMessage());
        }
    }

    private void logOrphanTemplate(String templateName, String igmName) {
        if (templateName != null) {
            log.warn("[GCP] Disable failed for MIG {} after inserting template {}; that template may be "
                    + "left unused and can be deleted once the MIG is confirmed not to use it", igmName, templateName);
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
    // ═══════════════════════════════════════════════════════════════════════════
    // MIG CONFIGURATION (mirrors AWS ASGConfig endpoints)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the current configuration of a MIG: update policy, autoscaler settings,
     * machine type, and whether Spot is currently enabled.
     */
    public com.xammer.cloud.dto.gcp.GcpMigConfigDto getMigConfig(String accountId, String region, String migName) {
        CloudAccount account = resolveAccount(accountId);
        String projectId = account.getGcpProjectId();

        com.xammer.cloud.dto.gcp.GcpMigConfigDto config = new com.xammer.cloud.dto.gcp.GcpMigConfigDto();
        config.setMigName(migName);
        config.setRegion(region);

        try {
            InstanceGroupManager igm = getMig(projectId, region, migName);
            config.setTargetSize(igm.getTargetSize());

            // ── Update policy ─────────────────────────────────────────────────
            if (igm.hasUpdatePolicy()) {
                com.google.cloud.compute.v1.InstanceGroupManagerUpdatePolicy policy = igm.getUpdatePolicy();
                if (policy.hasMaxSurge() && policy.getMaxSurge().hasFixed()) {
                    config.setMaxSurge(policy.getMaxSurge().getFixed());
                } else {
                    config.setMaxSurge(1); // sane default
                }
                if (policy.hasMaxUnavailable() && policy.getMaxUnavailable().hasFixed()) {
                    config.setMaxUnavailable(policy.getMaxUnavailable().getFixed());
                } else {
                    config.setMaxUnavailable(0);
                }
                config.setMinReadySeconds(30); // SDK does not expose minReadySeconds; use default
            } else {
                config.setMaxSurge(1);
                config.setMaxUnavailable(0);
                config.setMinReadySeconds(30);
            }

            // ── Machine type (from template) ───────────────────────────────────
            String templateUri = igm.getInstanceTemplate();
            if (templateUri != null && !templateUri.isBlank()) {
                String templateName = templateUri.substring(templateUri.lastIndexOf('/') + 1);
                String templateRegion = getTemplateRegion(templateUri);

                try {
                    // Check Spot status via REST (reliable)
                    boolean isSpot = restTemplateService.isSpotTemplate(projectId, templateRegion, templateName);
                    config.setSpotEnabled(isSpot);
                } catch (Exception e) {
                    log.warn("⚠️ [GCP Config] Could not check spot status: {}", e.getMessage());
                }

                InstanceTemplate tmpl = fetchInstanceTemplate(projectId, templateRegion, templateName);
                if (tmpl != null && tmpl.hasProperties() && tmpl.getProperties().hasMachineType()) {
                    String mt = tmpl.getProperties().getMachineType();
                    config.setMachineType(mt.substring(mt.lastIndexOf('/') + 1));
                }
            }

            // ── Autoscaler ────────────────────────────────────────────────────
            try {
                boolean isZonal = region.split("-").length == 3;
                if (isZonal) {
                    Optional<com.google.cloud.compute.v1.AutoscalersClient> asClientOpt =
                        gcpClientProvider.getAutoscalersClient(projectId);
                    if (asClientOpt.isPresent()) {
                        try (com.google.cloud.compute.v1.AutoscalersClient asClient = asClientOpt.get()) {
                            for (com.google.cloud.compute.v1.Autoscaler as :
                                    asClient.list(projectId, region).iterateAll()) {
                                if (as.getTarget().endsWith("/" + migName)) {
                                    config.setAutoscalerEnabled(true);
                                    if (as.hasAutoscalingPolicy()) {
                                        config.setAutoscalerMinReplicas(as.getAutoscalingPolicy().getMinNumReplicas());
                                        config.setAutoscalerMaxReplicas(as.getAutoscalingPolicy().getMaxNumReplicas());
                                        config.setAutoscalerCoolDownPeriodSec(as.getAutoscalingPolicy().getCoolDownPeriodSec());
                                    }
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    Optional<com.google.cloud.compute.v1.RegionAutoscalersClient> rasClientOpt =
                        gcpClientProvider.getRegionAutoscalersClient(projectId);
                    if (rasClientOpt.isPresent()) {
                        try (com.google.cloud.compute.v1.RegionAutoscalersClient rasClient = rasClientOpt.get()) {
                            for (com.google.cloud.compute.v1.Autoscaler as :
                                    rasClient.list(projectId, region).iterateAll()) {
                                if (as.getTarget().endsWith("/" + migName)) {
                                    config.setAutoscalerEnabled(true);
                                    if (as.hasAutoscalingPolicy()) {
                                        config.setAutoscalerMinReplicas(as.getAutoscalingPolicy().getMinNumReplicas());
                                        config.setAutoscalerMaxReplicas(as.getAutoscalingPolicy().getMaxNumReplicas());
                                        config.setAutoscalerCoolDownPeriodSec(as.getAutoscalingPolicy().getCoolDownPeriodSec());
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ [GCP Config] Could not fetch autoscaler for MIG {}: {}", migName, e.getMessage());
            }

            config.setUpdatePolicySummary(String.format(
                "Rolling update: +%d surge / %d unavailable / %ds ready wait",
                config.getMaxSurge(), config.getMaxUnavailable(), config.getMinReadySeconds()));

        } catch (Exception e) {
            log.error("❌ [GCP Config] Failed to get config for MIG {}: {}", migName, e.getMessage(), e);
            throw new RuntimeException("Failed to get MIG config: " + e.getMessage());
        }

        return config;
    }

    /**
     * Update the update policy of a MIG (maxSurge, maxUnavailable, minReadySeconds).
     * This affects how future Spot conversions roll out — not the current template.
     */
    public com.xammer.cloud.dto.gcp.GcpMigConfigDto updateMigConfig(
            String accountId, String region, String migName,
            com.xammer.cloud.dto.gcp.GcpMigConfigDto update) {

        CloudAccount account = resolveAccount(accountId);
        String projectId = account.getGcpProjectId();
        log.info("🔧 [GCP Config] Updating config for MIG {}: maxSurge={}, maxUnavailable={}, minReadySec={}",
            migName, update.getMaxSurge(), update.getMaxUnavailable(), update.getMinReadySeconds());

        try {
            // GCP constraint (all regions): for regional MIGs, maxSurge.fixed AND maxUnavailable.fixed
            // must each be 0 or >= numZones. Any value between 1 and numZones-1 → 400 BAD_REQUEST.
            // Safe rule: zonal → send user values as-is; regional → omit both, let GCP use defaults.
            boolean isZonal = region.split("-").length == 3;

            // Build PATCH body: update policy + optional target size
            com.google.gson.JsonObject updatePolicy = new com.google.gson.JsonObject();
            updatePolicy.addProperty("type", "PROACTIVE");
            updatePolicy.addProperty("minReadySeconds", update.getMinReadySeconds());

            if (isZonal) {
                com.google.gson.JsonObject maxSurge = new com.google.gson.JsonObject();
                maxSurge.addProperty("fixed", update.getMaxSurge());
                updatePolicy.add("maxSurge", maxSurge);

                com.google.gson.JsonObject maxUnavailable = new com.google.gson.JsonObject();
                maxUnavailable.addProperty("fixed", update.getMaxUnavailable());
                updatePolicy.add("maxUnavailable", maxUnavailable);
            } else {
                // Regional MIG: omit maxSurge/maxUnavailable — GCP uses numZones as default (always valid).
                // Sending any fixed value < numZones causes 400 BAD_REQUEST regardless of region.
                log.info("ℹ️ [GCP Config] Regional MIG {} — omitting maxSurge/maxUnavailable; GCP will apply zone-count defaults", migName);
            }

            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.add("updatePolicy", updatePolicy);

            // Also update targetSize if caller provided a non-zero value
            if (update.getTargetSize() > 0) {
                body.addProperty("targetSize", update.getTargetSize());
                log.info("🔧 [GCP Config] Updating targetSize to {} for MIG {}", update.getTargetSize(), migName);
            }

            restTemplateService.patchMig(projectId, region, migName, body);
            log.info("✅ [GCP Config] MIG {} config saved (updatePolicy + targetSize)", migName);

        } catch (Exception e) {
            log.error("❌ [GCP Config] Failed to update config for MIG {}: {}", migName, e.getMessage(), e);
            throw new RuntimeException("Failed to update MIG config: " + e.getMessage());
        }

        // Return the updated config
        return getMigConfig(accountId, region, migName);
    }

    private void recordSavingsEvent(CloudAccount account, String migName, String region,
                                    int instanceCount, double hourlySavingsPerInstance, boolean enabled) {
        try {
            double totalHourlySavings = instanceCount * hourlySavingsPerInstance;
            log.info("🔍 [GCP Event Record Debug] instanceCount={}, savingsPerInstance=${}, total=${}",
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
            log.info("📊 [GCP] Recorded savings event for MIG: {} (${}/hr)", migName,
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
            log.info("📈 [GCP] Updated daily savings: ${} total for {}",
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
            if (!mig.isEnabled || mig.spotInstanceCount <= 0) {
                continue;
            }

            // A group that could not be priced contributes nothing. It used to be costed as an
            // n1-standard-1 regardless of what it actually was, and the result was written to the
            // persisted savings history where nothing ever corrects it.
            if (mig.savingsUnknown) {
                log.warn("⚠️ [GCP] MIG {} has {} spot instance(s) but no usable price ({}); excluded "
                        + "from today's recorded savings",
                        mig.name, mig.spotInstanceCount, mig.pricingNote);
                continue;
            }

            // actualHourlySavings already accounts for the spot instance count, so multiplying by
            // it again would have squared the figure.
            double dailySavingsForMig = mig.actualHourlySavings * 24;

            totalDailySavings += dailySavingsForMig;
            totalSpotInstances += mig.spotInstanceCount;

            log.debug("💰 [GCP] MIG {} has {} spot instances, daily savings: ${}",
                mig.name, mig.spotInstanceCount, String.format("%.4f", dailySavingsForMig));
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
            log.info("💾 [GCP] Recorded daily savings for {}: ${} from {} spot instances", 
                gcpProjectId, String.format("%.2f", totalDailySavings), totalSpotInstances);
        } else {
            log.debug("ℹ️ [GCP] No spot instances running for account: {}", gcpProjectId);
        }
    }
}
