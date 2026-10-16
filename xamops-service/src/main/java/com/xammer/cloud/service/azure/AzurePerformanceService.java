package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzurePerformanceInsight;
import com.xammer.cloud.dto.azure.AzurePerformanceResponse;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for fetching Azure Advisor Performance recommendations.
 *
 * Fetches ALL Advisor recommendations (no server-side OData filter) and
 * filters by category == 'Performance' in code to avoid URI encoding issues
 * and to support nextLink pagination.
 *
 * Requires "Advisor Reviews Reader" or "Reader" role.
 * On 403, returns PERMISSION_ERROR so the frontend can show an actionable message.
 */
@Service
public class AzurePerformanceService {

    private static final Logger log = LoggerFactory.getLogger(AzurePerformanceService.class);
    private static final String CACHE_PREFIX = "azure:performance:";
    private static final String DB_CACHE_PREFIX = "AZURE_PERFORMANCE::";
    private static final int CACHE_TTL_MINUTES = 60;

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final AzureArmClient armClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzurePerformanceService(AzureClientProvider clientProvider,
                                    RedisCacheService redisCache,
                                    CachedDataRepository cachedDataRepository,
                                    AzureArmClient armClient) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.armClient = armClient;
    }

    /**
     * Returns Azure Advisor Performance insights for the given subscription.
     * Cache strategy: Redis (60-min TTL) → DB snapshot → Azure API.
     * Pass forceRefresh=true to bypass all caches.
     */
    public AzurePerformanceResponse getPerformanceInsights(String subscriptionId,
                                                            boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;
        String dbKey = DB_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            // 1. Try Redis
            Optional<AzurePerformanceResponse> cached =
                    redisCache.get(cacheKey, AzurePerformanceResponse.class);
            if (cached.isPresent()) {
                log.info("Returning Redis-cached performance response for {}", subscriptionId);
                try {
                    return objectMapper.convertValue(cached.get(), AzurePerformanceResponse.class);
                } catch (Exception e) {
                    log.warn("Redis deserialization failed, trying DB: {}", e.getMessage());
                }
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    AzurePerformanceResponse response = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(), AzurePerformanceResponse.class);
                    log.info("Returning DB-cached performance response for {} — re-warming Redis", subscriptionId);
                    redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
                    return response;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for performance {}: {}", subscriptionId, e.getMessage());
            }
        }

        AzurePerformanceResponse response = fetchFromAdvisor(subscriptionId);
        redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
        saveToDb(dbKey, response);
        return response;
    }

    private void saveToDb(String dbKey, AzurePerformanceResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            log.info("✅ DB snapshot saved for Azure performance: {}", dbKey);
        } catch (Exception e) {
            log.warn("Failed to save DB snapshot for Azure performance {}: {}", dbKey, e.getMessage());
        }
    }

    private AzurePerformanceResponse fetchFromAdvisor(String subscriptionId) {
        String bearerToken;
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            bearerToken = credential.getToken(tokenCtx).block().getToken();
        } catch (Exception e) {
            log.error("Failed to obtain ARM token for Azure Advisor Performance {}: {}",
                    subscriptionId, e.getMessage(), e);
            return new AzurePerformanceResponse(
                    AzurePerformanceResponse.Status.API_ERROR, 100, new ArrayList<>());
        }

        // Fetch all Advisor recommendations (no OData $filter) — filter by category in code.
        // This avoids URI encoding issues with single-quoted OData string literals and
        // ensures we follow nextLink pagination correctly. The shared client supplies the
        // pooled, timeout-configured HttpClient and follows nextLink through list().
        String baseUri = "https://management.azure.com/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Advisor/recommendations?api-version=2023-01-01";

        List<JsonNode> nodes;
        try {
            nodes = armClient.list(baseUri, bearerToken);
        } catch (AzureArmClient.AzureArmException e) {
            // A 401/403 on the first page means the service principal cannot read Advisor —
            // a distinct, reportable state rather than "no data". Any other failure is API_ERROR.
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("HTTP 401") || message.contains("HTTP 403")
                    || message.contains("denied access")) {
                log.warn("SP may lack access to Azure Advisor for subscription {}. " +
                        "Assign 'Advisor Reviews Reader' or 'Reader' role to enable performance insights.",
                        subscriptionId);
                return new AzurePerformanceResponse(
                        AzurePerformanceResponse.Status.PERMISSION_ERROR, 100, new ArrayList<>());
            }
            log.error("Azure Advisor Performance API error for {}: {}", subscriptionId, message);
            return new AzurePerformanceResponse(
                    AzurePerformanceResponse.Status.API_ERROR, 100, new ArrayList<>());
        } catch (Exception e) {
            log.error("Failed to fetch Azure Advisor Performance insights for {}: {}",
                    subscriptionId, e.getMessage(), e);
            return new AzurePerformanceResponse(
                    AzurePerformanceResponse.Status.API_ERROR, 100, new ArrayList<>());
        }

        List<AzurePerformanceInsight> allInsights = parseInsights(nodes);

        log.info("Azure Advisor Performance: {} insights for {}", allInsights.size(), subscriptionId);

        int score = computeScore(allInsights);
        AzurePerformanceResponse.Status status = allInsights.isEmpty()
                ? AzurePerformanceResponse.Status.NO_DATA
                : AzurePerformanceResponse.Status.OK;

        return new AzurePerformanceResponse(status, score, allInsights);
    }

    private List<AzurePerformanceInsight> parseInsights(List<JsonNode> nodes) {
        List<AzurePerformanceInsight> result = new ArrayList<>();
        Set<String> seenCategories = new HashSet<>();

        for (JsonNode node : nodes) {
            try {
                // Filter by Performance category in code (case-insensitive)
                String category = node.path("properties").path("category").asText("");
                seenCategories.add(category);
                if (!"Performance".equalsIgnoreCase(category)) continue;

                AzurePerformanceInsight insight = new AzurePerformanceInsight();

                insight.setInsightId(node.path("name").asText(""));
                insight.setSeverity(node.path("properties").path("impact").asText("Medium"));

                JsonNode shortDesc = node.path("properties").path("shortDescription");
                insight.setDescription(shortDesc.path("problem").asText(""));
                insight.setRecommendation(shortDesc.path("solution").asText(""));

                // Resource type from impactedField (e.g. "Microsoft.Compute/virtualMachines" → "virtualMachines")
                String impactedField = node.path("properties").path("impactedField").asText("");
                String impactedValue = node.path("properties").path("impactedValue").asText("");
                if (!impactedField.isEmpty()) {
                    int slash = impactedField.lastIndexOf('/');
                    insight.setResourceType(slash >= 0 ? impactedField.substring(slash + 1) : impactedField);
                } else {
                    insight.setResourceType("");
                }

                // Resource name from resourceMetadata.resourceId or impactedValue fallback
                JsonNode resourceMeta = node.path("properties").path("resourceMetadata");
                String resourceId = resourceMeta.path("resourceId").asText(
                        resourceMeta.path("source").asText(""));

                if (!resourceId.isEmpty()) {
                    insight.setResourceName(resourceId.substring(resourceId.lastIndexOf('/') + 1));
                } else {
                    insight.setResourceName(impactedValue);
                }

                insight.setRegion("");

                result.add(insight);
            } catch (Exception e) {
                log.warn("Failed to parse Advisor performance insight item: {}", e.getMessage());
            }
        }

        // Diagnostic: log all categories seen when no Performance items were found
        if (result.isEmpty() && !seenCategories.isEmpty()) {
            log.warn("No 'Performance' category insights for this subscription. " +
                    "Categories seen: {}", seenCategories);
        }

        return result;
    }

    /**
     * Computes performance score: 100 - (High×10) - (Medium×5) - (Low×2), minimum 0.
     */
    private int computeScore(List<AzurePerformanceInsight> insights) {
        int deduction = 0;
        for (AzurePerformanceInsight insight : insights) {
            switch (insight.getSeverity()) {
                case "High":   deduction += 10; break;
                case "Medium": deduction += 5;  break;
                case "Low":    deduction += 2;  break;
            }
        }
        return Math.max(0, 100 - deduction);
    }
}
