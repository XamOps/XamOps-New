package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.azure.AzureAlertsResponse;
import com.xammer.cloud.dto.azure.AzureMonitorAlert;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for fetching Azure Monitor alerts via the AlertsManagement REST API.
 *
 * GET https://management.azure.com/subscriptions/{subId}/providers/Microsoft.AlertsManagement/alerts
 *     ?api-version=2019-05-05-preview
 *
 * Requires "Monitoring Reader" role on the subscription.
 * On 403, returns PERMISSION_ERROR so the frontend can show an actionable message.
 */
@Service
public class AzureAlertsService {

    private static final Logger log = LoggerFactory.getLogger(AzureAlertsService.class);
    private static final String CACHE_PREFIX = "azure:alerts:";
    private static final int CACHE_TTL_MINUTES = 60;

    private final AzureClientProvider clientProvider;
    private final RedisCacheService redisCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureAlertsService(AzureClientProvider clientProvider,
                               RedisCacheService redisCache) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
    }

    /**
     * Returns Azure Monitor alerts for the given subscription.
     * Uses Redis cache (60-min TTL). Pass forceRefresh=true to bypass cache.
     */
    public AzureAlertsResponse getAlerts(String subscriptionId, boolean forceRefresh) {
        String cacheKey = CACHE_PREFIX + subscriptionId;

        if (!forceRefresh) {
            Optional<AzureAlertsResponse> cached =
                    redisCache.get(cacheKey, AzureAlertsResponse.class);
            if (cached.isPresent()) {
                log.info("Returning cached alerts response for {}", subscriptionId);
                try {
                    return objectMapper.convertValue(cached.get(), AzureAlertsResponse.class);
                } catch (Exception e) {
                    log.warn("Cache deserialization failed, re-fetching: {}", e.getMessage());
                }
            }
        }

        AzureAlertsResponse response = fetchFromMonitor(subscriptionId);
        redisCache.put(cacheKey, response, CACHE_TTL_MINUTES);
        return response;
    }

    private AzureAlertsResponse fetchFromMonitor(String subscriptionId) {
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            String bearerToken = credential.getToken(tokenCtx).block().getToken();

            // Fetch all alerts with nextLink pagination
            String baseUri = "https://management.azure.com/subscriptions/" + subscriptionId
                    + "/providers/Microsoft.AlertsManagement/alerts?api-version=2019-05-05-preview";

            HttpClient httpClient = HttpClient.newHttpClient();
            List<AzureMonitorAlert> allAlerts = new ArrayList<>();
            String currentUrl = baseUri;
            boolean permissionError = false;

            while (currentUrl != null) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(currentUrl))
                        .header("Authorization", "Bearer " + bearerToken)
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> httpResponse = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                int httpStatus = httpResponse.statusCode();
                log.info("Azure Monitor Alerts API status: {} for {}", httpStatus, subscriptionId);

                if (httpStatus == 403) {
                    permissionError = true;
                    break;
                }

                if (httpStatus != 200) {
                    log.warn("Azure Monitor Alerts API returned {} for {}: {}",
                            httpStatus, subscriptionId,
                            httpResponse.body().substring(0, Math.min(200, httpResponse.body().length())));
                    return new AzureAlertsResponse(
                            AzureAlertsResponse.Status.API_ERROR, new ArrayList<>());
                }

                List<AzureMonitorAlert> page = parseAlerts(httpResponse.body());
                allAlerts.addAll(page);

                // Follow nextLink for pagination
                JsonNode root = objectMapper.readTree(httpResponse.body());
                String nextLink = root.path("nextLink").asText(null);
                currentUrl = (nextLink != null && !nextLink.isEmpty()) ? nextLink : null;
            }

            if (permissionError) {
                log.warn("SP may lack Monitoring Reader role for subscription {}. " +
                        "Assign 'Monitoring Reader' role to enable Azure Monitor alerts.",
                        subscriptionId);
                return new AzureAlertsResponse(
                        AzureAlertsResponse.Status.PERMISSION_ERROR, new ArrayList<>());
            }

            log.info("Azure Monitor Alerts: {} alerts for {}", allAlerts.size(), subscriptionId);

            AzureAlertsResponse.Status status = allAlerts.isEmpty()
                    ? AzureAlertsResponse.Status.NO_DATA
                    : AzureAlertsResponse.Status.OK;

            return new AzureAlertsResponse(status, allAlerts);

        } catch (Exception e) {
            log.error("Failed to fetch Azure Monitor alerts for {}: {}",
                    subscriptionId, e.getMessage(), e);
            return new AzureAlertsResponse(
                    AzureAlertsResponse.Status.API_ERROR, new ArrayList<>());
        }
    }

    private List<AzureMonitorAlert> parseAlerts(String json) throws Exception {
        List<AzureMonitorAlert> result = new ArrayList<>();
        JsonNode root = objectMapper.readTree(json);
        JsonNode value = root.path("value");

        if (value.isMissingNode() || !value.isArray()) return result;

        for (JsonNode node : value) {
            try {
                AzureMonitorAlert alert = new AzureMonitorAlert();

                alert.setAlertId(node.path("name").asText(""));

                JsonNode essentials = node.path("properties").path("essentials");

                // Alert name: last segment of the alertRule resource ID
                String alertRule = essentials.path("alertRule").asText("");
                if (!alertRule.isEmpty()) {
                    int slash = alertRule.lastIndexOf('/');
                    alert.setAlertName(slash >= 0 ? alertRule.substring(slash + 1) : alertRule);
                } else {
                    alert.setAlertName(alert.getAlertId());
                }

                alert.setDescription(essentials.path("description").asText(""));

                String severity = essentials.path("severity").asText("Sev3");
                alert.setSeverity(severity);
                alert.setSeverityLabel(mapSeverityLabel(severity));

                alert.setAlertState(essentials.path("alertState").asText(""));
                alert.setMonitorCondition(essentials.path("monitorCondition").asText(""));
                alert.setSignalType(essentials.path("signalType").asText(""));
                alert.setResourceName(essentials.path("targetResourceName").asText(""));
                alert.setResourceGroup(essentials.path("targetResourceGroup").asText(""));

                // Resource type: last segment of targetResourceType
                String resourceType = essentials.path("targetResourceType").asText("");
                if (!resourceType.isEmpty()) {
                    int slash = resourceType.lastIndexOf('/');
                    alert.setResourceType(slash >= 0 ? resourceType.substring(slash + 1) : resourceType);
                } else {
                    alert.setResourceType("");
                }

                alert.setStartDateTime(essentials.path("startDateTime").asText(""));

                result.add(alert);
            } catch (Exception e) {
                log.warn("Failed to parse Azure Monitor alert item: {}", e.getMessage());
            }
        }

        return result;
    }

    private String mapSeverityLabel(String severity) {
        switch (severity) {
            case "Sev0": return "Critical";
            case "Sev1": return "Error";
            case "Sev2": return "Warning";
            case "Sev3": return "Informational";
            case "Sev4": return "Verbose";
            default:     return severity;
        }
    }
}
