package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.azure.AzureSecurityAlert;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.service.azure.AzureCostAnomalyService;
import com.xammer.cloud.service.azure.AzureSecurityService;
import com.xammer.cloud.service.security.SecuritySeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts Azure cost anomalies and Defender security alerts into the shape the shared dashboard
 * Alerts &amp; Anomalies card consumes.
 *
 * <p>The shared {@code SummaryCardsSection} (AWS, GCP, Azure) fetches
 * {@code {apiBase}/cloudguard/alerts} and expects a bare array of items shaped like
 * {@code {type, service, usage, severity, ...}}. AWS exposes {@code /api/xamops/cloudguard/alerts}
 * and GCP exposes {@code /api/xamops/gcp/cloudguard/alerts}; Azure only had {@code /azure/alerts},
 * which 404s the card's request and returns the {@code AzureAlertsResponse} wrapper shape even
 * when hit directly. The card's {@code .catch} then renders "0 / 0 / 0 / 0".
 *
 * <p>This is a read path: {@code forceRefresh} defaults to {@code false} and cache warming happens
 * on the onboarding/scheduled refresh paths, not here.
 */
@RestController
@RequestMapping("/api/xamops/azure/cloudguard")
public class AzureCloudGuardAlertsController {

    private static final Logger log = LoggerFactory.getLogger(AzureCloudGuardAlertsController.class);

    private final AzureCostAnomalyService azureCostAnomalyService;
    private final AzureSecurityService azureSecurityService;

    public AzureCloudGuardAlertsController(AzureCostAnomalyService azureCostAnomalyService,
            AzureSecurityService azureSecurityService) {
        this.azureCostAnomalyService = azureCostAnomalyService;
        this.azureSecurityService = azureSecurityService;
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<Map<String, Object>>> getAlerts(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        log.info("Azure CloudGuard alerts request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);

        List<Map<String, Object>> alerts = new ArrayList<>();

        // Cost anomalies → ANOMALY items. The card's TopList reads `usage` and `service`.
        try {
            AzureCostAnomalyService.AnomalyOutcome outcome =
                    azureCostAnomalyService.detect(accountId, forceRefresh);
            if (outcome != null && outcome.anomalies() != null) {
                for (DashboardData.CostAnomaly anomaly : outcome.anomalies()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "ANOMALY");
                    item.put("service", anomaly.getService());
                    item.put("usage", anomaly.getUnexpectedSpend());
                    item.put("description", "Unexpected spend detected for " + anomaly.getService());
                    item.put("severity", SecuritySeverity.MEDIUM);
                    item.put("currencySymbol", "$");
                    alerts.add(item);
                }
            }
        } catch (Exception e) {
            // A failed anomaly read must not drop the security alerts below. The card shows the
            // partial, honest result rather than an all-clear.
            log.error("Azure cost anomaly read failed for {}: {}", accountId, e.getMessage(), e);
        }

        // Defender security alerts → SECURITY items.
        try {
            AzureSecurityResponse security = azureSecurityService.getSecurityData(accountId, forceRefresh);
            if (security != null && security.getAlerts() != null) {
                for (AzureSecurityAlert alert : security.getAlerts()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "SECURITY");
                    item.put("service", alert.getResourceName());
                    item.put("name", alert.getAlertName());
                    item.put("description", alert.getDescription());
                    item.put("severity", SecuritySeverity.normalize(alert.getSeverity()));
                    alerts.add(item);
                }
            }
        } catch (Exception e) {
            // Partial result over a false all-clear: keep whatever anomalies were collected above.
            log.error("Azure Defender alert read failed for {}: {}", accountId, e.getMessage(), e);
        }

        return ResponseEntity.ok(alerts);
    }
}
