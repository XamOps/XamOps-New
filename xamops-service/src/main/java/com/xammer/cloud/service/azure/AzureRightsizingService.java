package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureRightsizingRecommendation;
import com.xammer.cloud.dto.azure.AzureRightsizingResponse;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.SnapshotFreshnessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for fetching Azure Advisor Cost recommendations (rightsizing, shutdown suggestions, etc.)
 *
 * Uses Azure Advisor REST API:
 *   GET https://management.azure.com/subscriptions/{subId}/providers/Microsoft.Advisor/recommendations
 *       ?api-version=2023-01-01&$filter=Category eq 'Cost'
 *
 * Returns AzureRightsizingResponse with a status field so the frontend can distinguish
 * between permission errors (403), no data (200 + empty), and API errors.
 */
@Service
public class AzureRightsizingService {

    private static final Logger log = LoggerFactory.getLogger(AzureRightsizingService.class);
    private static final String CACHE_PREFIX = "azure:rightsizing:";
    private static final String DB_CACHE_PREFIX = "AZURE_RIGHTSIZING::";
    private static final int CACHE_TTL_MINUTES = 60;
    private static final String ARM_BASE = "https://management.azure.com";

    // Metric-based VM rightsizing (free Azure Monitor). A VM whose CPU peaked below
    // this over 14 days is over-provisioned and a downsize candidate.
    private static final double METRIC_CPU_THRESHOLD = 40.0;

    /** Lookback window for the CPU signal, in days. */
    private static final int LOOKBACK_DAYS = 14;

    /** Minimum daily datapoints required before a resize is recommended. */
    private static final int MIN_METRIC_DAYS = 7;

    /** Currency for all amounts produced by this service. */
    private static final String BASE_CURRENCY = "USD";

    /**
     * Derives the next-smaller SKU in the same family by halving the size suffix.
     *
     * <p>Replaces a 15-entry hardcoded map that covered only B/D/E/F v5 and v2 sizes: no
     * v3/v4/v6, no AMD ({@code Dasv5}, {@code Easv5}), no memory- or GPU-optimised
     * families. Anything unlisted produced a "Smaller SKU" placeholder with zero savings.
     *
     * <p>Azure size names follow {@code Standard_<family><vCPUs><modifiers>[_<version>]},
     * so the step down is a numeric halving with everything else preserved. That keeps the
     * family, the storage/AMD modifiers and the version intact.
     *
     * @param size current size, e.g. {@code Standard_D8as_v5}
     * @return the next smaller size, or empty when there is no smaller step
     */
    /**
     * Converts an amount into {@link #BASE_CURRENCY} so every row in the response is comparable.
     *
     * <p>Leaves the amount untouched for an unsupported currency rather than guessing a rate,
     * and logs it — a wrong conversion is worse than an unconverted figure, because it looks
     * plausible.
     */
    private double toBaseCurrency(double amount, String fromCurrency) {
        if (amount == 0.0) {
            return 0.0;
        }
        try {
            return currencyConversionService.convert(amount, fromCurrency, BASE_CURRENCY);
        } catch (IllegalArgumentException e) {
            log.warn("Azure Advisor reported savings in {}, which cannot be converted to {}. "
                    + "Leaving the amount as-is; it is not comparable with the other rows.",
                    fromCurrency, BASE_CURRENCY);
            return amount;
        }
    }

    static Optional<String> nextSmallerSku(String size) {
        if (size == null || !size.startsWith("Standard_")) {
            return Optional.empty();
        }
        Matcher m = VM_SIZE_PATTERN.matcher(size);
        if (!m.matches()) {
            return Optional.empty();
        }
        String family = m.group(1);
        int vcpus = Integer.parseInt(m.group(2));
        String modifiers = m.group(3) == null ? "" : m.group(3);
        String version = m.group(4) == null ? "" : m.group(4);

        // Azure sizes step in powers of two; 2 vCPUs is the smallest step that has a smaller
        // neighbour in the general-purpose families.
        if (vcpus <= 2) {
            return Optional.empty();
        }

        int smaller = vcpus / 2;

        // Halving arithmetically is not enough: Azure does not publish every result. There is no
        // Standard_D1s_v5 or Standard_E1s_v5, and an odd vCPU count halves to a size that never
        // existed. Emitting one produced a recommendation whose resize call fails at apply time,
        // so only vCPU counts Azure actually offers are returned.
        if (!AZURE_VCPU_STEPS.contains(smaller)) {
            return Optional.empty();
        }
        // The v-series general-purpose and memory-optimised families start at 2 vCPUs.
        if (smaller < 2) {
            return Optional.empty();
        }
        return Optional.of("Standard_" + family + smaller + modifiers + version);
    }

    /**
     * vCPU counts Azure publishes for the mainstream families.
     *
     * <p>Sizes step in powers of two from 2, with 96 and 128 at the top end. A halving that
     * lands outside this set does not exist as a purchasable size, so no recommendation is made
     * rather than one that cannot be applied.
     */
    private static final java.util.Set<Integer> AZURE_VCPU_STEPS =
            java.util.Set.of(2, 4, 8, 16, 20, 32, 48, 64, 80, 96, 128);

    /**
     * Matches an Azure VM size: family letters, vCPU count, optional modifier letters
     * ({@code s}, {@code a}, {@code m}, {@code d}...), optional {@code _v<n>} version.
     */
    private static final Pattern VM_SIZE_PATTERN =
            Pattern.compile("^Standard_([A-Za-z]+?)(\\d+)([a-z]*)(_v\\d+)?$");

    // VM prices come from AzureRetailPricingService (the Azure Retail Prices API), not a
    // hardcoded table. The previous VM_MONTHLY_INR map had no region dimension, covered
    // only 18 sizes, and drifted from real list prices with no way to refresh it.

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final com.xammer.cloud.service.CurrencyConversionService currencyConversionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SnapshotFreshnessService snapshotFreshnessService;
    private final AzureRetailPricingService retailPricingService;
    private final AzureArmClient armClient;

    public AzureRightsizingService(AzureClientProvider clientProvider,
                                   RedisCacheService redisCache,
                                   CachedDataRepository cachedDataRepository,
                                   SnapshotFreshnessService snapshotFreshnessService,
                                   AzureRetailPricingService retailPricingService,
                                   AzureArmClient armClient,
                                   com.xammer.cloud.service.CurrencyConversionService currencyConversionService) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.snapshotFreshnessService = snapshotFreshnessService;
        this.retailPricingService = retailPricingService;
        this.armClient = armClient;
        this.currencyConversionService = currencyConversionService;
    }

    /**
     * Returns Azure Advisor Cost recommendations for the given subscription.
     * Cache strategy: Redis (60-min TTL) → DB snapshot → Azure API.
     * Pass forceRefresh=true to bypass all caches.
     */
    public AzureRightsizingResponse getRecommendations(String subscriptionId,
                                                       boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;
        String dbKey = DB_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            // 1. Try Redis
            Optional<AzureRightsizingResponse> cached = redisCache.get(cacheKey, AzureRightsizingResponse.class);
            if (cached.isPresent()) {
                log.info("Returning Redis-cached rightsizing response for {}", subscriptionId);
                try {
                    return objectMapper.convertValue(cached.get(), AzureRightsizingResponse.class);
                } catch (Exception e) {
                    log.warn("Redis deserialization failed, trying DB: {}", e.getMessage());
                }
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
                if (dbSnapshot.isPresent()) {
                    AzureRightsizingResponse response = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(), AzureRightsizingResponse.class);
                    log.info("Returning DB-cached rightsizing response for {} — re-warming Redis", subscriptionId);
                    redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
                    return response;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for rightsizing {}: {}", subscriptionId, e.getMessage());
            }
        }

        AzureRightsizingResponse response = fetchFromAdvisor(subscriptionId);
        redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
        saveToDb(dbKey, response);
        return response;
    }

    private void saveToDb(String dbKey, AzureRightsizingResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            log.info("✅ DB snapshot saved for Azure rightsizing: {}", dbKey);
        } catch (Exception e) {
            log.warn("Failed to save DB snapshot for Azure rightsizing {}: {}", dbKey, e.getMessage());
        }
    }

    private AzureRightsizingResponse fetchFromAdvisor(String subscriptionId) {
        String bearerToken;
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            bearerToken = credential.getToken(tokenCtx).block().getToken();
        } catch (Exception e) {
            log.error("Failed to obtain ARM token for Azure rightsizing {}: {}", subscriptionId, e.getMessage());
            return new AzureRightsizingResponse(AzureRightsizingResponse.Status.API_ERROR, new ArrayList<>());
        }

        List<AzureRightsizingRecommendation> recommendations = new ArrayList<>();
        boolean advisorPermissionError = false;

        // 1. Azure Advisor Cost recommendations (provider-native)
        try {
            String uri = "https://management.azure.com/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.Advisor/recommendations"
                    + "?api-version=2023-01-01&$filter=Category%20eq%20'Cost'";

            // Uses the shared client: one pooled HttpClient with connect and request
            // timeouts, rather than a fresh untimed client per call.
            Optional<String> body = armClient.get(uri, bearerToken);
            if (body.isPresent()) {
                recommendations.addAll(parseRecommendations(body.get()));
            } else {
                log.info("Azure Advisor returned no recommendations for {}", subscriptionId);
            }
        } catch (AzureArmClient.AzureArmException e) {
            // A 401/403 here means the service principal cannot read Advisor. That is a
            // distinct, reportable state rather than "no recommendations".
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("HTTP 401") || message.contains("HTTP 403") || message.contains("denied access")) {
                log.warn("Service principal lacks access to Azure Advisor for subscription {}. "
                        + "Falling back to metric-based detection only.", subscriptionId);
                advisorPermissionError = true;
            } else {
                log.error("Failed to fetch Azure Advisor recommendations for {}: {}",
                        subscriptionId, message);
            }
        } catch (Exception e) {
            log.error("Failed to fetch Azure Advisor recommendations for {}: {}", subscriptionId, e.getMessage(), e);
        }

        // 2. Metric-based VM rightsizing (free Azure Monitor) — finds over-provisioned
        //    VMs that Advisor missed, and adds CPU-utilization evidence.
        try {
            addMetricBasedVmRecommendations(subscriptionId, bearerToken, recommendations);
        } catch (Exception e) {
            log.warn("Metric-based VM rightsizing failed for {}: {}", subscriptionId, e.getMessage());
        }

        recommendations.sort(Comparator.comparingDouble(AzureRightsizingRecommendation::getMonthlySavings).reversed());
        int beforeDedupe = recommendations.size();
        recommendations = keepBestCommitmentPerResource(recommendations);
        if (recommendations.size() != beforeDedupe) {
            log.info("Azure rightsizing: collapsed {} mutually exclusive commitment options for {}",
                    beforeDedupe - recommendations.size(), subscriptionId);
        }
        log.info("Azure rightsizing: {} total recommendations for {}", recommendations.size(), subscriptionId);

        AzureRightsizingResponse.Status status;
        if (!recommendations.isEmpty()) {
            status = AzureRightsizingResponse.Status.OK;
        } else if (advisorPermissionError) {
            status = AzureRightsizingResponse.Status.PERMISSION_ERROR;
        } else {
            status = AzureRightsizingResponse.Status.NO_DATA;
        }
        return new AzureRightsizingResponse(status, recommendations);
    }

    /**
     * Collapses mutually exclusive commitment options so each resource contributes once.
     *
     * <p>Azure Advisor returns a separate recommendation per commitment term, so the same VM
     * arrives as "Reserved (1yr)" and "Reserved (3yr)", and a subscription arrives as both a
     * 1-year and a 3-year Savings Plan. You can only buy one of each pair, but the UI summed
     * them all: in UAT a single {@code Standard_D4s_v5} contributed ₹252 for 3 years and ₹158
     * for 1 year, and the six rows totalled ₹1,058 — roughly double what is achievable.
     *
     * <p>Keeps the highest-saving option per resource-and-commitment-family. Resize
     * recommendations are left alone: a resize and a reservation are not alternatives in the
     * same sense, and resizes are already deduplicated upstream by resource id.
     *
     * <p>The input must already be sorted by savings descending.
     */
    private List<AzureRightsizingRecommendation> keepBestCommitmentPerResource(
            List<AzureRightsizingRecommendation> sortedBySavingsDesc) {

        Map<String, AzureRightsizingRecommendation> bestByKey = new java.util.LinkedHashMap<>();
        List<AzureRightsizingRecommendation> passthrough = new ArrayList<>();

        for (AzureRightsizingRecommendation rec : sortedBySavingsDesc) {
            String family = commitmentFamily(rec.getTargetSku());
            if (family == null) {
                // Not a commitment purchase — keep as-is.
                passthrough.add(rec);
                continue;
            }
            // Group by resource plus commitment family, so a VM's 1yr and 3yr reservations
            // collide but its reservation and a separate savings plan do not.
            String key = (rec.getResourceName() == null ? "" : rec.getResourceName().toLowerCase())
                    + "|" + (rec.getCurrentSku() == null ? "" : rec.getCurrentSku().toLowerCase())
                    + "|" + family;
            bestByKey.putIfAbsent(key, rec);
        }

        List<AzureRightsizingRecommendation> result = new ArrayList<>(passthrough);
        result.addAll(bestByKey.values());
        result.sort(Comparator.comparingDouble(AzureRightsizingRecommendation::getMonthlySavings).reversed());
        return result;
    }

    /**
     * Classifies a target SKU as a commitment purchase.
     *
     * @return {@code "reserved"}, {@code "savings-plan"}, or null when the target is a resize
     */
    private String commitmentFamily(String targetSku) {
        if (targetSku == null || targetSku.isBlank()) {
            return null;
        }
        String sku = targetSku.toLowerCase();
        if (sku.startsWith("reserved")) {
            return "reserved";
        }
        if (sku.startsWith("savings plan")) {
            return "savings-plan";
        }
        return null;
    }

    /**
     * Detects over-provisioned VMs via Azure Monitor CPU metrics (free) and adds
     * downsize recommendations for any VM not already covered by Advisor.
     * Deallocated VMs return no CPU data and are naturally skipped.
     */
    private void addMetricBasedVmRecommendations(String subscriptionId, String token,
                                                 List<AzureRightsizingRecommendation> recs) {
        Set<String> existingIds = recs.stream()
                .map(r -> r.getResourceId() == null ? "" : r.getResourceId().toLowerCase())
                .collect(Collectors.toSet());

        String url = ARM_BASE + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Compute/virtualMachines?api-version=2023-07-01";

        for (JsonNode vm : armList(url, token)) {
            try {
                String id = vm.path("id").asText("");
                if (id.isEmpty() || existingIds.contains(id.toLowerCase())) continue;

                String size = vm.path("properties").path("hardwareProfile").path("vmSize").asText("");
                if (size.isEmpty()) continue;

                double[] cpu = queryVmCpu(id, token);
                if (cpu == null) continue; // no data (likely deallocated) — skip
                double avgCpu = cpu[0];
                double maxCpu = cpu[1];
                int dataPoints = (int) cpu[2];
                if (maxCpu >= METRIC_CPU_THRESHOLD) continue; // sufficiently utilized

                // Require real observation coverage. queryVmCpu returns as soon as it sees a
                // single non-null datapoint, so without this a VM created yesterday would be
                // downsized on one day of data.
                if (dataPoints < MIN_METRIC_DAYS) {
                    log.debug("Skipping VM {}: only {} of {} days of CPU data", id, dataPoints, LOOKBACK_DAYS);
                    continue;
                }

                // B-series VMs bank CPU credits, so a low average is expected behaviour rather
                // than over-provisioning. Downsizing one can exhaust its credit balance.
                if (size.startsWith("Standard_B")) {
                    log.debug("Skipping burstable VM {} ({}): low CPU is expected for B-series", id, size);
                    continue;
                }

                AzureRightsizingRecommendation rec = new AzureRightsizingRecommendation();
                rec.setRecommendationId("xamops-metric-" + vm.path("name").asText(""));
                rec.setResourceId(id);
                rec.setResourceName(vm.path("name").asText(""));
                rec.setResourceType("virtualMachines");
                rec.setCategory("Cost");
                rec.setImpact(maxCpu < 10 ? "High" : "Medium");
                rec.setLocation(vm.path("location").asText(""));
                rec.setCurrentSku(size);
                rec.setCurrency(BASE_CURRENCY);
                // This is the true Maximum from Azure Monitor, not a p95. Report both fields
                // honestly rather than storing a maximum in a field labelled p95.
                rec.setMaxCpuObserved(String.format("%.1f%%", maxCpu));
                rec.setAvgCpuObserved(String.format("%.1f%%", avgCpu));
                rec.setProblem(String.format(
                        "VM peaked at only %.1f%% CPU (avg %.1f%%) over %d days of data — over-provisioned",
                        maxCpu, avgCpu, dataPoints));

                String target = nextSmallerSku(size).orElse(null);
                String location = vm.path("location").asText("");
                if (target == null) {
                    // Without a known target we cannot quantify a saving. Emit the finding but
                    // do not attach a fabricated zero that pads the recommendation count.
                    rec.setTargetSku("Smaller SKU in the same family");
                    rec.setMonthlySavings(0.0);
                    rec.setSavingsUnknown(true);
                    rec.setSolution("Consider downsizing " + size + " to a smaller SKU in the same family");
                    recs.add(rec);
                    existingIds.add(id.toLowerCase());
                    continue;
                }

                rec.setTargetSku(target);
                Optional<Double> currentPrice = retailPricingService.getVmMonthlyPriceUsd(size, location);
                Optional<Double> targetPrice = retailPricingService.getVmMonthlyPriceUsd(target, location);

                if (currentPrice.isPresent() && targetPrice.isPresent()) {
                    double savings = Math.max(0.0, currentPrice.get() - targetPrice.get());
                    rec.setMonthlySavings(Math.round(savings * 100.0) / 100.0);
                    rec.setAnnualSavings(rec.getMonthlySavings() * 12);
                    rec.setSolution(String.format(
                            "Downsize from %s to %s to cut compute cost while retaining headroom. "
                                    + "Based on list prices; excludes any Reserved Instance or Savings Plan "
                                    + "discount already applied.", size, target));
                } else {
                    // Price lookup failed: report the finding without inventing a number.
                    rec.setMonthlySavings(0.0);
                    rec.setSavingsUnknown(true);
                    rec.setSolution(String.format(
                            "Downsize from %s to %s. Savings could not be calculated — "
                                    + "the Azure Retail Prices lookup did not return a rate for this size and region.",
                            size, target));
                }
                recs.add(rec);
                existingIds.add(id.toLowerCase());
            } catch (Exception e) {
                log.warn("Error building metric-based recommendation for VM: {}", e.getMessage());
            }
        }
    }

    /**
      * Returns {@code {avgCpu, maxCpu, dataPointCount}} over the lookback window, or null if
      * no data. The count lets callers reject readings based on too few observations.
      */
    private double[] queryVmCpu(String resourceId, String token) {
        try {
            String url = ARM_BASE + resourceId
                    + "/providers/Microsoft.Insights/metrics"
                    + "?metricnames=Percentage%20CPU"
                    + "&timespan=P14D"
                    + "&interval=P1D"
                    + "&aggregation=Average,Maximum"
                    + "&api-version=2018-01-01";

            String body = armGet(url, token);
            if (body == null) return null;

            JsonNode root = objectMapper.readTree(body);
            JsonNode valueArr = root.path("value");
            if (!valueArr.isArray() || valueArr.isEmpty()) return null;
            JsonNode timeSeries = valueArr.get(0).path("timeseries");
            if (!timeSeries.isArray() || timeSeries.isEmpty()) return null;
            JsonNode dataArr = timeSeries.get(0).path("data");
            if (!dataArr.isArray() || dataArr.isEmpty()) return null;

            double avgSum = 0.0;
            int avgCount = 0;
            double max = 0.0;
            boolean any = false;
            for (JsonNode point : dataArr) {
                JsonNode avg = point.path("average");
                if (!avg.isMissingNode() && !avg.isNull()) {
                    avgSum += avg.asDouble();
                    avgCount++;
                    any = true;
                }
                JsonNode mx = point.path("maximum");
                if (!mx.isMissingNode() && !mx.isNull()) {
                    max = Math.max(max, mx.asDouble());
                    any = true;
                }
            }
            if (!any) return null;
            // Third element is the datapoint count, so callers can require real coverage
            // before acting on the peak.
            return new double[]{avgCount > 0 ? avgSum / avgCount : 0.0, max, avgCount};
        } catch (Exception e) {
            log.debug("VM CPU metric query failed for {}: {}", resourceId, e.getMessage());
            return null;
        }
    }

    /**
     * Authenticated ARM GET, delegating to the shared {@link AzureArmClient}.
     *
     * <p>The previous local implementation created a new {@link java.net.http.HttpClient}
     * per call with no timeouts, and mapped 403/429/5xx to a silent null at debug level.
     */
    private String armGet(String url, String token) {
        return armClient.getQuietly(url, token).orElse(null);
    }

    /** Lists every ARM {@code value} entry, following {@code nextLink} to the end. */
    private List<JsonNode> armList(String url, String token) {
        return armClient.list(url, token);
    }

    private List<AzureRightsizingRecommendation> parseRecommendations(String json) throws Exception {
        List<AzureRightsizingRecommendation> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");

        if (value.isMissingNode() || !value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                AzureRightsizingRecommendation rec = new AzureRightsizingRecommendation();

                rec.setRecommendationId(node.path("name").asText(""));
                rec.setCategory(node.path("properties").path("category").asText("Cost"));
                rec.setImpact(node.path("properties").path("impact").asText("Medium"));

                JsonNode shortDesc = node.path("properties").path("shortDescription");
                rec.setProblem(shortDesc.path("problem").asText(""));
                rec.setSolution(shortDesc.path("solution").asText(""));

                String impactedField = node.path("properties").path("impactedField").asText("");
                String impactedValue = node.path("properties").path("impactedValue").asText("");

                JsonNode resourceMeta = node.path("properties").path("resourceMetadata");
                String resourceId = resourceMeta.path("resourceId").asText(
                        resourceMeta.path("source").asText(""));
                rec.setResourceId(resourceId);

                JsonNode extProps = node.path("properties").path("extendedProperties");

                // Detect subscription-level recommendations (RI / Savings Plan):
                // their resourceId ends with the subscription GUID, not a real resource name
                String lastSegment = resourceId.isEmpty() ? ""
                        : resourceId.substring(resourceId.lastIndexOf('/') + 1);
                boolean isSubscriptionLevel = lastSegment.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

                // Helper: a real Azure VM SKU starts with Standard_, Basic_, or Premium_
                String rawSku = extProps.path("sku").asText(extProps.path("vmSize").asText(""));
                boolean isVmSku = rawSku.matches("(?i)(Standard|Basic|Premium)_.*");

                // ── Resource Type ─────────────────────────────────────────────
                if (!impactedField.isEmpty()
                        && !impactedField.equalsIgnoreCase("subscriptions")
                        && !impactedField.equalsIgnoreCase("microsoft.billing/billingAccounts")
                        && !impactedField.toLowerCase().contains("savingsplan")) {
                    int slash = impactedField.lastIndexOf('/');
                    rec.setResourceType(slash >= 0 ? impactedField.substring(slash + 1) : impactedField);
                } else {
                    // Subscription-level: derive type from extendedProperties, ignoring "subscriptions"
                    String extResType = extProps.path("resourceType").asText("");
                    if (!extResType.isEmpty()
                            && !extResType.equalsIgnoreCase("subscriptions")
                            && !extResType.equalsIgnoreCase("subscription")) {
                        int slash = extResType.lastIndexOf('/');
                        rec.setResourceType(slash >= 0 ? extResType.substring(slash + 1) : extResType);
                    } else if (isVmSku) {
                        rec.setResourceType("virtualMachines");
                    } else if (!rawSku.isEmpty()) {
                        rec.setResourceType("savingsPlan");
                    } else {
                        rec.setResourceType(extractResourceType(resourceId));
                    }
                }

                // ── Resource Name ─────────────────────────────────────────────
                if (!isSubscriptionLevel && !lastSegment.isEmpty()) {
                    // Specific resource: use the last path segment (actual resource name)
                    rec.setResourceName(lastSegment);
                    rec.setLocation("");
                } else {
                    // Subscription-level RI or Savings Plan recommendation
                    String region = extProps.path("region").asText(extProps.path("location").asText(""));
                    String scope  = extProps.path("scope").asText("");
                    String term   = extProps.path("term").asText("");
                    String termLabel = "P1Y".equals(term) ? "1yr" : "P3Y".equals(term) ? "3yr" : term;
                    if (isVmSku) {
                        // Reserved Instance: "Standard_B2s (eastus)"
                        rec.setResourceName(rawSku + (!region.isEmpty() ? " (" + region + ")" : ""));
                    } else {
                        // Savings Plan: "Savings Plan (1yr, Subscription)"
                        StringBuilder planName = new StringBuilder("Savings Plan");
                        if (!termLabel.isEmpty() || !scope.isEmpty()) {
                            planName.append(" (");
                            if (!termLabel.isEmpty()) planName.append(termLabel);
                            if (!termLabel.isEmpty() && !scope.isEmpty()) planName.append(", ");
                            if (!scope.isEmpty()) planName.append(scope);
                            planName.append(")");
                        }
                        rec.setResourceName(planName.toString());
                    }
                    rec.setLocation(region);
                }

                // ── Current / Target SKU ──────────────────────────────────────
                // VM rightsizing:  currentSku / targetSku in extendedProperties
                // RI:              sku = VM SKU → currentSku; build targetSku from qty + term
                // Savings Plan:    no current/target SKU — leave both blank
                String currentSku = extProps.path("currentSku").asText(extProps.path("vmSize").asText(""));
                if (currentSku.isEmpty() && isVmSku) {
                    currentSku = rawSku; // RI recommendation: VM SKU being reserved
                }
                rec.setCurrentSku(currentSku);

                String targetSku = extProps.path("targetSku").asText(
                        extProps.path("recommendedSku").asText(""));
                if (targetSku.isEmpty()) {
                    String term  = extProps.path("term").asText("");
                    String scope = extProps.path("scope").asText("");
                    String termLabel = "P1Y".equals(term) ? "1yr" : "P3Y".equals(term) ? "3yr" : term;
                    if (!termLabel.isEmpty()) {
                        // Format quantity: JSON number → integer string (strip trailing .0)
                        String qtyRaw = extProps.path("recommendedQuantityNormalized").asText(
                                extProps.path("recommendedQuantity").asText(""));
                        String qtyLabel = "";
                        if (!qtyRaw.isEmpty()) {
                            try {
                                double qtyNum = Double.parseDouble(qtyRaw);
                                qtyLabel = " ×" + (qtyNum == Math.floor(qtyNum)
                                        ? String.valueOf((int) qtyNum)
                                        : String.valueOf(qtyNum));
                            } catch (NumberFormatException ignored) {
                                qtyLabel = " ×" + qtyRaw;
                            }
                        }
                        String scopePart = !scope.isEmpty() ? ", " + scope : "";
                        if (isVmSku) {
                            targetSku = "Reserved" + qtyLabel + " (" + termLabel + scopePart + ")";
                        } else if (!rawSku.isEmpty()) {
                            // Savings Plan
                            targetSku = "Savings Plan (" + termLabel + scopePart + ")";
                        }
                    }
                }
                rec.setTargetSku(targetSku);

                // Advisor reports savings in the subscription's billing currency, which for an
                // India-billed subscription is INR. The metric-based rows produced elsewhere in
                // this service are always USD, and both sets are then sorted together, deduped by
                // "highest saving", and summed by the UI — so a mixed list made the ordering and
                // the total meaningless. Normalise to the service's base currency here so every
                // row downstream is comparable.
                String advisorCurrency = extProps.path("savingsCurrency").asText(BASE_CURRENCY);
                double monthly = parseDouble(extProps.path("savingsAmount").asText(null));
                double annual = parseDouble(extProps.path("annualSavingsAmount").asText(null));

                rec.setMonthlySavings(toBaseCurrency(monthly, advisorCurrency));
                rec.setAnnualSavings(toBaseCurrency(annual, advisorCurrency));
                rec.setCurrency(BASE_CURRENCY);
                rec.setMaxCpuObserved(extProps.path("maxCpuP95").asText(
                        extProps.path("avgCpuUtilization").asText("")));
                rec.setMaxMemoryObserved(extProps.path("maxMemoryP95").asText(""));

                result.add(rec);
            } catch (Exception e) {
                log.warn("Failed to parse Advisor recommendation item: {}", e.getMessage());
            }
        }

        return result;
    }

    /**
     * Extracts resource type from resourceId.
     * e.g. ".../Microsoft.Compute/virtualMachines/vm-name" → "virtualMachines"
     */
    private String extractResourceType(String resourceId) {
        if (resourceId == null || resourceId.isEmpty()) return "";
        String[] parts = resourceId.split("/");
        for (int i = 0; i < parts.length - 2; i++) {
            if ("providers".equalsIgnoreCase(parts[i])) {
                return parts.length > i + 2 ? parts[i + 2] : "";
            }
        }
        return "";
    }

    private double parseDouble(String value) {
        if (value == null || value.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
