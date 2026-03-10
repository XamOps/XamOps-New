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
import java.util.List;
import java.util.Optional;

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
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            // Azure Advisor Cost recommendations API
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
                        "Assign 'Advisor Reviews Reader' or 'Reader' role to enable rightsizing recommendations.",
                        subscriptionId);
                return new AzureRightsizingResponse(AzureRightsizingResponse.Status.PERMISSION_ERROR,
                        new ArrayList<>());
            }

            if (httpResponse.statusCode() != 200) {
                log.warn("Azure Advisor API returned {} for {}: {}",
                        httpResponse.statusCode(), subscriptionId,
                        httpResponse.body().substring(0, Math.min(200, httpResponse.body().length())));
                return new AzureRightsizingResponse(AzureRightsizingResponse.Status.API_ERROR,
                        new ArrayList<>());
            }

            List<AzureRightsizingRecommendation> recommendations = parseRecommendations(httpResponse.body());
            // Sort by monthly savings descending (highest savings first)
            recommendations.sort(Comparator.comparingDouble(AzureRightsizingRecommendation::getMonthlySavings).reversed());
            log.info("Azure Advisor returned {} Cost recommendations for {}", recommendations.size(), subscriptionId);

            AzureRightsizingResponse.Status status = recommendations.isEmpty()
                    ? AzureRightsizingResponse.Status.NO_DATA
                    : AzureRightsizingResponse.Status.OK;
            return new AzureRightsizingResponse(status, recommendations);

        } catch (Exception e) {
            log.error("Failed to fetch Azure Advisor recommendations for {}: {}", subscriptionId, e.getMessage(), e);
            return new AzureRightsizingResponse(AzureRightsizingResponse.Status.API_ERROR, new ArrayList<>());
        }
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

                // Prefer impactedField for type (e.g. "Microsoft.Compute/virtualMachines" → "virtualMachines")
                // More reliable than parsing resourceId which is subscription-level for savings plan recommendations
                if (!impactedField.isEmpty()) {
                    int slash = impactedField.lastIndexOf('/');
                    rec.setResourceType(slash >= 0 ? impactedField.substring(slash + 1) : impactedField);
                } else {
                    rec.setResourceType(extractResourceType(resourceId));
                }

                if (!resourceId.isEmpty()) {
                    rec.setResourceName(resourceId.substring(resourceId.lastIndexOf('/') + 1));
                    rec.setLocation(extractLocation(resourceId));
                } else {
                    rec.setResourceName(impactedValue);
                    rec.setLocation("");
                }

                JsonNode extProps = node.path("properties").path("extendedProperties");
                rec.setCurrentSku(extProps.path("currentSku").asText(
                        extProps.path("vmSize").asText("")));
                rec.setTargetSku(extProps.path("targetSku").asText(
                        extProps.path("recommendedSku").asText("")));

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

    /**
     * Location not reliably encoded in resourceId — omit rather than guess.
     */
    private String extractLocation(String resourceId) {
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
