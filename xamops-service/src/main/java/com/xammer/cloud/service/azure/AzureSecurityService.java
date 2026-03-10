package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.azure.AzureSecurityAlert;
import com.xammer.cloud.dto.azure.AzureSecurityAssessment;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
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
import java.util.List;
import java.util.Optional;

/**
 * Service for fetching Azure Security data from Microsoft Defender for Cloud.
 *
 * Uses three Microsoft.Security REST APIs:
 *   - Secure Scores:  .../providers/Microsoft.Security/secureScores?api-version=2020-01-01-preview
 *   - Security Alerts: .../providers/Microsoft.Security/alerts?api-version=2021-01-01
 *   - Assessments:    .../providers/Microsoft.Security/assessments?api-version=2021-06-01
 *
 * Requires "Security Reader" role on the subscription.
 * On 403, returns PERMISSION_ERROR status so the frontend can show an actionable message.
 */
@Service
public class AzureSecurityService {

    private static final Logger log = LoggerFactory.getLogger(AzureSecurityService.class);
    private static final String CACHE_PREFIX = "azure:security:";
    private static final String DB_CACHE_PREFIX = "AZURE_SECURITY::";
    private static final int CACHE_TTL_MINUTES = 60;
    private static final String BASE_URL = "https://management.azure.com/subscriptions/";
    private static final String SCOPE = "https://management.azure.com/.default";

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureSecurityService(AzureClientProvider clientProvider,
                                RedisCacheService redisCache,
                                CachedDataRepository cachedDataRepository) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
    }

    /**
     * Returns Azure security data (score, alerts, assessments) for the given subscription.
     * Cache strategy: Redis (60-min TTL) → DB snapshot → Azure API.
     * Pass forceRefresh=true to bypass all caches.
     */
    public AzureSecurityResponse getSecurityData(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;
        String dbKey = DB_CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            // 1. Try Redis
            Optional<AzureSecurityResponse> cached = redisCache.get(cacheKey, AzureSecurityResponse.class);
            if (cached.isPresent()) {
                log.info("Returning Redis-cached security response for {}", subscriptionId);
                try {
                    return objectMapper.convertValue(cached.get(), AzureSecurityResponse.class);
                } catch (Exception e) {
                    log.warn("Redis deserialization failed, trying DB: {}", e.getMessage());
                }
            }

            // 2. Try DB snapshot
            try {
                Optional<CachedData> dbSnapshot = cachedDataRepository.findById(dbKey);
                if (dbSnapshot.isPresent()) {
                    AzureSecurityResponse response = objectMapper.readValue(
                            dbSnapshot.get().getJsonData(), AzureSecurityResponse.class);
                    log.info("Returning DB-cached security response for {} — re-warming Redis", subscriptionId);
                    redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
                    return response;
                }
            } catch (Exception e) {
                log.warn("DB snapshot load failed for security {}: {}", subscriptionId, e.getMessage());
            }
        }

        AzureSecurityResponse response = fetchFromDefender(subscriptionId);
        redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
        saveToDb(dbKey, response);
        return response;
    }

    private void saveToDb(String dbKey, AzureSecurityResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            CachedData entity = cachedDataRepository.findById(dbKey).orElse(new CachedData());
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            log.info("✅ DB snapshot saved for Azure security: {}", dbKey);
        } catch (Exception e) {
            log.warn("Failed to save DB snapshot for Azure security {}: {}", dbKey, e.getMessage());
        }
    }

    private AzureSecurityResponse fetchFromDefender(String subscriptionId) {
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext().addScopes(SCOPE);
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            HttpClient httpClient = HttpClient.newHttpClient();

            double secureScore = fetchSecureScore(subscriptionId, bearerToken, httpClient);
            List<AzureSecurityAlert> alerts = fetchAlerts(subscriptionId, bearerToken, httpClient);
            List<AzureSecurityAssessment> assessments = fetchAssessments(subscriptionId, bearerToken, httpClient);

            boolean hasData = secureScore > 0 || !alerts.isEmpty() || !assessments.isEmpty();
            AzureSecurityResponse.Status status = hasData
                    ? AzureSecurityResponse.Status.OK
                    : AzureSecurityResponse.Status.NO_DATA;

            log.info("Azure Security: score={}, alerts={}, assessments(unhealthy)={} for {}",
                    secureScore, alerts.size(), assessments.size(), subscriptionId);

            return new AzureSecurityResponse(status, secureScore, 100, alerts, assessments);

        } catch (PermissionException e) {
            log.warn("SP lacks Security Reader role for subscription {}. " +
                    "Assign 'Security Reader' role to enable security data.", subscriptionId);
            return new AzureSecurityResponse(AzureSecurityResponse.Status.PERMISSION_ERROR,
                    0, 100, new ArrayList<>(), new ArrayList<>());
        } catch (Exception e) {
            log.error("Failed to fetch Azure security data for {}: {}", subscriptionId, e.getMessage(), e);
            return new AzureSecurityResponse(AzureSecurityResponse.Status.API_ERROR,
                    0, 100, new ArrayList<>(), new ArrayList<>());
        }
    }

    // ── Secure Score ──────────────────────────────────────────────────────────

    private double fetchSecureScore(String subscriptionId, String bearerToken,
                                    HttpClient httpClient) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/secureScores"
                + "?api-version=2020-01-01-preview";

        String body = doGet(uri, bearerToken, httpClient, subscriptionId, "secureScores");
        if (body == null) return 0;

        JsonNode root = objectMapper.readTree(body);
        JsonNode value = root.path("value");
        if (!value.isArray() || value.isEmpty()) return 0;

        // Take the first score entry (typically "ascScore" — the overall subscription score)
        JsonNode scoreNode = value.get(0).path("properties").path("score");
        return scoreNode.path("current").asDouble(0);
    }

    // ── Security Alerts ───────────────────────────────────────────────────────

    private List<AzureSecurityAlert> fetchAlerts(String subscriptionId, String bearerToken,
                                                  HttpClient httpClient) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/alerts"
                + "?api-version=2021-01-01";

        String body = doGet(uri, bearerToken, httpClient, subscriptionId, "alerts");
        if (body == null) return new ArrayList<>();

        return parseAlerts(body);
    }

    private List<AzureSecurityAlert> parseAlerts(String json) throws Exception {
        List<AzureSecurityAlert> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");
        if (!value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                JsonNode props = node.path("properties");
                AzureSecurityAlert alert = new AzureSecurityAlert();
                alert.setAlertId(node.path("name").asText(""));
                alert.setAlertName(props.path("alertDisplayName").asText(
                        props.path("alertType").asText("")));
                alert.setSeverity(props.path("severity").asText("Medium"));
                alert.setStatus(props.path("status").asText("Active"));
                alert.setDescription(props.path("description").asText(""));
                alert.setTimeGenerated(props.path("timeGeneratedUtc").asText(
                        props.path("startTimeUtc").asText("")));

                // Extract resource name from resourceIdentifiers array
                JsonNode identifiers = props.path("resourceIdentifiers");
                String resourceId = "";
                if (identifiers.isArray() && !identifiers.isEmpty()) {
                    resourceId = identifiers.get(0).path("azureResourceId").asText(
                            identifiers.get(0).path("workspaceId").asText(""));
                }
                alert.setResourceName(resourceId.isEmpty() ? "—"
                        : resourceId.substring(resourceId.lastIndexOf('/') + 1));

                result.add(alert);
            } catch (Exception e) {
                log.warn("Failed to parse security alert item: {}", e.getMessage());
            }
        }

        return result;
    }

    // ── Security Assessments ──────────────────────────────────────────────────

    private List<AzureSecurityAssessment> fetchAssessments(String subscriptionId, String bearerToken,
                                                            HttpClient httpClient) throws Exception {
        String uri = BASE_URL + subscriptionId
                + "/providers/Microsoft.Security/assessments"
                + "?api-version=2021-06-01";

        String body = doGet(uri, bearerToken, httpClient, subscriptionId, "assessments");
        if (body == null) return new ArrayList<>();

        return parseAssessments(body);
    }

    private List<AzureSecurityAssessment> parseAssessments(String json) throws Exception {
        List<AzureSecurityAssessment> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");
        if (!value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                JsonNode props = node.path("properties");
                String statusCode = props.path("status").path("code").asText("NotApplicable");

                // Only include Unhealthy assessments (actionable findings)
                if (!"Unhealthy".equalsIgnoreCase(statusCode)) continue;

                AzureSecurityAssessment assessment = new AzureSecurityAssessment();
                assessment.setAssessmentId(node.path("name").asText(""));
                assessment.setDisplayName(props.path("displayName").asText(""));
                assessment.setStatusCode(statusCode);

                JsonNode metadata = props.path("metadata");
                assessment.setSeverity(metadata.path("severity").asText("Medium"));
                assessment.setRemediation(metadata.path("remediationDescription").asText(""));

                // Category from metadata.categories array (take first element)
                JsonNode categories = metadata.path("categories");
                String category = (categories.isArray() && !categories.isEmpty())
                        ? categories.get(0).asText("Other")
                        : "Other";
                assessment.setCategory(category);

                // Resource: prefer resourceDetails.id; fall back to stripping the security suffix
                // from the assessment's own top-level id (always present and reliable)
                String resourceId = props.path("resourceDetails").path("id").asText("");

                if (resourceId.isEmpty()) {
                    String fullId = node.path("id").asText("");
                    int secIdx = fullId.toLowerCase().indexOf("/providers/microsoft.security/assessments/");
                    if (secIdx > 0) {
                        resourceId = fullId.substring(0, secIdx);
                    }
                }

                assessment.setResourceId(resourceId);

                if (resourceId.isEmpty()) {
                    assessment.setResourceName("—");
                } else {
                    String lastName = resourceId.substring(resourceId.lastIndexOf('/') + 1);
                    boolean isGuid = lastName.matches(
                            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
                    assessment.setResourceName(isGuid ? "Subscription" : lastName);
                }

                result.add(assessment);
            } catch (Exception e) {
                log.warn("Failed to parse security assessment item: {}", e.getMessage());
            }
        }

        return result;
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    /**
     * Executes a GET request and returns the response body, or null on 404.
     * Throws PermissionException on 403, RuntimeException on other non-200 responses.
     */
    private String doGet(String uri, String bearerToken, HttpClient httpClient,
                         String subscriptionId, String resourceType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        log.info("Azure Security {} API status: {} for {}", resourceType, status, subscriptionId);

        if (status == 403) {
            throw new PermissionException("403 fetching " + resourceType + " for " + subscriptionId);
        }
        if (status == 404) {
            log.warn("Azure Security {} not found for {} (feature may not be enabled)", resourceType, subscriptionId);
            return null;
        }
        if (status != 200) {
            log.warn("Azure Security {} returned {} for {}: {}", resourceType, status, subscriptionId,
                    response.body().substring(0, Math.min(200, response.body().length())));
            return null;
        }

        return response.body();
    }

    // ── Inner exception ───────────────────────────────────────────────────────

    private static class PermissionException extends RuntimeException {
        PermissionException(String message) { super(message); }
    }
}
