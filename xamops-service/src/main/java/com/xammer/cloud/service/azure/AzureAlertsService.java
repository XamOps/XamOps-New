package com.xammer.cloud.service.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.azure.AzureAlertsResponse;
import com.xammer.cloud.dto.azure.AzureMonitorAlert;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AlertDigestSeverityGate;
import com.xammer.cloud.service.AlertEmailBuilder;
import com.xammer.cloud.service.AlertNotificationDeduplicator;
import com.xammer.cloud.service.EmailService;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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
    private final EmailService emailService;
    private final AlertEmailBuilder alertEmailBuilder;
    private final AlertNotificationDeduplicator deduplicator;
    private final AlertDigestSeverityGate severityGate;
    private final CloudAccountRepository cloudAccountRepository;
    private final AzureArmClient armClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AzureAlertsService(AzureClientProvider clientProvider,
                               RedisCacheService redisCache,
                               EmailService emailService,
                               AlertEmailBuilder alertEmailBuilder,
                               AlertNotificationDeduplicator deduplicator,
                               AlertDigestSeverityGate severityGate,
                               CloudAccountRepository cloudAccountRepository,
                               AzureArmClient armClient) {
        this.clientProvider = clientProvider;
        this.redisCache = redisCache;
        this.emailService = emailService;
        this.alertEmailBuilder = alertEmailBuilder;
        this.deduplicator = deduplicator;
        this.severityGate = severityGate;
        this.cloudAccountRepository = cloudAccountRepository;
        this.armClient = armClient;
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
        String bearerToken;
        try {
            TokenCredential credential = clientProvider.getCredential(subscriptionId);
            TokenRequestContext tokenCtx = new TokenRequestContext()
                    .addScopes("https://management.azure.com/.default");
            bearerToken = credential.getToken(tokenCtx).block().getToken();
        } catch (Exception e) {
            log.error("Failed to obtain ARM token for Azure Monitor alerts {}: {}",
                    subscriptionId, e.getMessage(), e);
            return new AzureAlertsResponse(
                    AzureAlertsResponse.Status.API_ERROR, new ArrayList<>());
        }

        // Fetch all alerts with nextLink pagination via the shared pooled client.
        String baseUri = "https://management.azure.com/subscriptions/" + subscriptionId
                + "/providers/Microsoft.AlertsManagement/alerts?api-version=2019-05-05-preview";

        List<JsonNode> nodes;
        try {
            nodes = armClient.list(baseUri, bearerToken);
        } catch (AzureArmClient.AzureArmException e) {
            // A 401/403 on the first page is a missing Monitoring Reader role — a distinct,
            // reportable state. Any other failure is API_ERROR.
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("HTTP 401") || message.contains("HTTP 403")
                    || message.contains("denied access")) {
                log.warn("SP may lack Monitoring Reader role for subscription {}. " +
                        "Assign 'Monitoring Reader' role to enable Azure Monitor alerts.",
                        subscriptionId);
                return new AzureAlertsResponse(
                        AzureAlertsResponse.Status.PERMISSION_ERROR, new ArrayList<>());
            }
            log.error("Azure Monitor Alerts API error for {}: {}", subscriptionId, message);
            return new AzureAlertsResponse(
                    AzureAlertsResponse.Status.API_ERROR, new ArrayList<>());
        } catch (Exception e) {
            log.error("Failed to fetch Azure Monitor alerts for {}: {}",
                    subscriptionId, e.getMessage(), e);
            return new AzureAlertsResponse(
                    AzureAlertsResponse.Status.API_ERROR, new ArrayList<>());
        }

        List<AzureMonitorAlert> allAlerts = parseAlerts(nodes);

        log.info("Azure Monitor Alerts: {} alerts for {}", allAlerts.size(), subscriptionId);

        AzureAlertsResponse.Status status = allAlerts.isEmpty()
                ? AzureAlertsResponse.Status.NO_DATA
                : AzureAlertsResponse.Status.OK;

        // No email from here — this is the Alerts page's own read path. Notification is
        // driven by the scheduler through notifyNewAlerts.
        return new AzureAlertsResponse(status, allAlerts);
    }

    private List<AzureMonitorAlert> parseAlerts(List<JsonNode> nodes) {
        List<AzureMonitorAlert> result = new ArrayList<>();

        for (JsonNode node : nodes) {
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

    /**
     * Emails the account about alerts that are new since the last notification.
     *
     * <p>Public and scheduler-driven. It used to be called from inside {@link #getAlerts},
     * which is what the Alerts page requests — so opening that page mailed the customer.
     * Azure had no scheduled alert job at all, meaning a page load was the <em>only</em>
     * thing that ever sent an Azure digest.
     *
     * <p>Sends nothing when the only change is that alerts were resolved.
     */
    public void notifyNewAlerts(String subscriptionId) {
        try {
            AzureAlertsResponse response = getAlerts(subscriptionId, true);
            List<AlertDto> alerts = response != null && response.getAlerts() != null
                    ? toAlertDtos(response.getAlerts())
                    : Collections.emptyList();

            AlertNotificationDeduplicator.AlertDelta delta =
                    deduplicator.detectNewAlerts("azure", subscriptionId, alerts);
            deduplicator.markSeen("azure", subscriptionId, alerts);

            if (!delta.worthSending()) {
                log.info("[AzureAlerts] No new alerts for subscription {} ({} open) — no email",
                        subscriptionId, delta.totalOpen());
                return;
            }

            Optional<CloudAccount> accountOpt = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId);
            String accountName = accountOpt
                    .map(a -> a.getAccountName() != null ? a.getAccountName() : subscriptionId)
                    .orElse(subscriptionId);

            // Threshold applied to the new alerts only; the seen-set above already recorded
            // everything, so lowering the threshold later cannot resurface old findings as new.
            List<AlertDto> notifiable = severityGate.notifiable(accountOpt.orElse(null), delta.newAlerts());
            if (notifiable.isEmpty()) {
                log.info("[AzureAlerts] {} new alert(s) for subscription {} all below the digest "
                        + "severity threshold — no email", delta.newAlerts().size(), subscriptionId);
                return;
            }

            String recipientEmail = accountOpt
                    .map(a -> a.getClient() != null ? a.getClient().getEmail() : null)
                    .orElse(null);
            if (recipientEmail == null || recipientEmail.isBlank()) {
                log.info("[AzureAlerts] {} new alert(s) for subscription {} but no recipient email",
                        notifiable.size(), subscriptionId);
                return;
            }

            String subject = delta.coldStart()
                    ? String.format("[XamOps] Azure Alert Digest — %d alert%s for %s",
                            delta.totalOpen(), delta.totalOpen() == 1 ? "" : "s", accountName)
                    : String.format("[XamOps] %d new Azure alert%s for %s (%d open)",
                            notifiable.size(), notifiable.size() == 1 ? "" : "s",
                            accountName, delta.totalOpen());
            String html = alertEmailBuilder.buildNewAlertsDigestEmail(
                    notifiable, delta.totalOpen(), delta.coldStart(), accountName, subscriptionId);

            emailService.sendHtmlEmail(recipientEmail, subject, html);
            log.info("[AzureAlerts] Sent digest to {} for subscription {} ({} new, {} open)",
                    recipientEmail, subscriptionId, notifiable.size(), delta.totalOpen());
        } catch (Exception e) {
            log.warn("[AzureAlerts] Notification run failed for {}: {}", subscriptionId, e.getMessage());
        }
    }

    private List<AlertDto> toAlertDtos(List<AzureMonitorAlert> rawAlerts) {
        return rawAlerts.stream().map(a -> {
            AlertDto dto = new AlertDto(
                    a.getAlertId(),
                    a.getResourceType(),
                    a.getAlertName(),
                    a.getDescription(),
                    mapSeverityToStatus(a.getSeverityLabel()),
                    0, 0,
                    mapSignalTypeToAlertType(a.getSignalType()),
                    a.getResourceGroup());

            // From the raw label, not from the status above. Azure has five bands and the
            // status mapping folds them into three — Sev1 "Error" comes out as CRITICAL, so a
            // client asking to hear only about criticals would be paged for every Sev1.
            // Normalising the original label keeps Error at HIGH where it belongs.
            dto.setSeverity(a.getSeverityLabel());
            return dto;
        }).collect(java.util.stream.Collectors.toList());
    }

    private String mapSeverityToStatus(String severityLabel) {
        if (severityLabel == null) return "INFO";
        return switch (severityLabel) {
            case "Critical", "Error" -> "CRITICAL";
            case "Warning"           -> "WARNING";
            default                  -> "INFO";
        };
    }

    private String mapSignalTypeToAlertType(String signalType) {
        if (signalType == null) return "ALERT";
        return switch (signalType) {
            case "Metric", "Log"        -> "ANOMALY";
            case "Activity Log", "Health" -> "SECURITY";
            default                     -> "ALERT";
        };
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
