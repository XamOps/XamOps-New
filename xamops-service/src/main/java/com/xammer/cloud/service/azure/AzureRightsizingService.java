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

    // Next-smaller SKU within the same family (one tier down ≈ half the vCPUs/cost).
    private static final Map<String, String> VM_DOWNSIZE = Map.ofEntries(
            Map.entry("Standard_B1ms", "Standard_B1s"),
            Map.entry("Standard_B2s",  "Standard_B1s"),
            Map.entry("Standard_B2ms", "Standard_B1ms"),
            Map.entry("Standard_B4ms", "Standard_B2ms"),
            Map.entry("Standard_B8ms", "Standard_B4ms"),
            Map.entry("Standard_D4s_v5",  "Standard_D2s_v5"),
            Map.entry("Standard_D8s_v5",  "Standard_D4s_v5"),
            Map.entry("Standard_D16s_v5", "Standard_D8s_v5"),
            Map.entry("Standard_E4s_v5",  "Standard_E2s_v5"),
            Map.entry("Standard_E8s_v5",  "Standard_E4s_v5"),
            Map.entry("Standard_E16s_v5", "Standard_E8s_v5"),
            Map.entry("Standard_F4s_v2",  "Standard_F2s_v2"),
            Map.entry("Standard_F8s_v2",  "Standard_F4s_v2"),
            Map.entry("Standard_F16s_v2", "Standard_F8s_v2"));

    // Approximate on-demand monthly cost in INR (estimates for savings calc).
    private static final Map<String, Double> VM_MONTHLY_INR = Map.ofEntries(
            Map.entry("Standard_B1s", 1100.0),
            Map.entry("Standard_B1ms", 2200.0),
            Map.entry("Standard_B2s", 4400.0),
            Map.entry("Standard_B2ms", 8800.0),
            Map.entry("Standard_B4ms", 17600.0),
            Map.entry("Standard_B8ms", 35200.0),
            Map.entry("Standard_D2s_v5", 8000.0),
            Map.entry("Standard_D4s_v5", 16000.0),
            Map.entry("Standard_D8s_v5", 32000.0),
            Map.entry("Standard_D16s_v5", 64000.0),
            Map.entry("Standard_E2s_v5", 10500.0),
            Map.entry("Standard_E4s_v5", 21000.0),
            Map.entry("Standard_E8s_v5", 42000.0),
            Map.entry("Standard_E16s_v5", 84000.0),
            Map.entry("Standard_F2s_v2", 7000.0),
            Map.entry("Standard_F4s_v2", 14000.0),
            Map.entry("Standard_F8s_v2", 28000.0),
            Map.entry("Standard_F16s_v2", 56000.0));

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureRightsizingService(AzureClientProvider clientProvider,
                                   RedisCacheService redisCache,
                                   CachedDataRepository cachedDataRepository) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
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
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
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

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            log.info("Azure Advisor API status: {} for {}", httpResponse.statusCode(), subscriptionId);

            if (httpResponse.statusCode() == 403) {
                log.warn("SP may lack access to Azure Advisor for subscription {}. " +
                        "Falling back to metric-based detection only.", subscriptionId);
                advisorPermissionError = true;
            } else if (httpResponse.statusCode() == 200) {
                recommendations.addAll(parseRecommendations(httpResponse.body()));
            } else {
                log.warn("Azure Advisor API returned {} for {}: {}",
                        httpResponse.statusCode(), subscriptionId,
                        httpResponse.body().substring(0, Math.min(200, httpResponse.body().length())));
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
                double maxCpu = cpu[1];
                if (maxCpu >= METRIC_CPU_THRESHOLD) continue; // sufficiently utilized

                AzureRightsizingRecommendation rec = new AzureRightsizingRecommendation();
                rec.setRecommendationId("xamops-metric-" + vm.path("name").asText(""));
                rec.setResourceId(id);
                rec.setResourceName(vm.path("name").asText(""));
                rec.setResourceType("virtualMachines");
                rec.setCategory("Cost");
                rec.setImpact(maxCpu < 10 ? "High" : "Medium");
                rec.setLocation(vm.path("location").asText(""));
                rec.setCurrentSku(size);
                rec.setCurrency("INR");
                rec.setMaxCpuP95(String.format("%.1f%%", maxCpu));
                rec.setProblem(String.format(
                        "VM peaked at only %.1f%% CPU over 14 days — over-provisioned", maxCpu));

                String target = VM_DOWNSIZE.get(size);
                if (target != null) {
                    rec.setTargetSku(target);
                    double cur = VM_MONTHLY_INR.getOrDefault(size, 0.0);
                    double tgt = VM_MONTHLY_INR.getOrDefault(target, 0.0);
                    double savings = (cur > 0 && tgt > 0) ? cur - tgt : 0.0;
                    rec.setMonthlySavings(Math.max(0, Math.round(savings * 100.0) / 100.0));
                    rec.setAnnualSavings(rec.getMonthlySavings() * 12);
                    rec.setSolution("Downsize from " + size + " to " + target
                            + " to cut compute cost while retaining headroom (estimated savings)");
                } else {
                    rec.setTargetSku("Smaller SKU");
                    rec.setMonthlySavings(0.0);
                    rec.setSolution("Consider downsizing " + size + " to a smaller SKU in the same family");
                }
                recs.add(rec);
                existingIds.add(id.toLowerCase());
            } catch (Exception e) {
                log.warn("Error building metric-based recommendation for VM: {}", e.getMessage());
            }
        }
    }

    /** Returns {avgCpu, maxCpu} over the last 14 days, or null if no data. */
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
            return new double[]{avgCount > 0 ? avgSum / avgCount : 0.0, max};
        } catch (Exception e) {
            log.debug("VM CPU metric query failed for {}: {}", resourceId, e.getMessage());
            return null;
        }
    }

    /** Authenticated GET against ARM REST. Returns body on 2xx, else null. */
    private String armGet(String url, String token) {
        try {
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            log.debug("ARM GET {} returned HTTP {}", url, response.statusCode());
            return null;
        } catch (Exception e) {
            log.debug("ARM GET error for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /** Lists all entries in the ARM "value" array, following nextLink pagination. */
    private List<JsonNode> armList(String url, String token) {
        List<JsonNode> out = new ArrayList<>();
        String next = url;
        int pages = 0;
        while (next != null && !next.isBlank() && pages < 20) {
            String body = armGet(next, token);
            if (body == null) break;
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode value = root.path("value");
                if (value.isArray()) {
                    for (JsonNode n : value) out.add(n);
                }
                next = root.path("nextLink").asText(null);
            } catch (Exception e) {
                log.debug("armList parse error for {}: {}", url, e.getMessage());
                break;
            }
            pages++;
        }
        return out;
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

                rec.setMonthlySavings(parseDouble(extProps.path("savingsAmount").asText(null)));
                rec.setAnnualSavings(parseDouble(extProps.path("annualSavingsAmount").asText(null)));
                rec.setCurrency(extProps.path("savingsCurrency").asText("INR"));
                rec.setMaxCpuP95(extProps.path("maxCpuP95").asText(
                        extProps.path("avgCpuUtilization").asText("")));
                rec.setMaxMemoryP95(extProps.path("maxMemoryP95").asText(""));

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
