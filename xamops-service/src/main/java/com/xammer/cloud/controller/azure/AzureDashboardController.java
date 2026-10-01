package com.xammer.cloud.controller.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.ForecastAccuracyRecord;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.ForecastAccuracyService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.azure.AzureBillingDataIngestionService;
import com.xammer.cloud.service.azure.AzureDashboardService;
import com.xammer.cloud.service.azure.AzureForecastingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/xamops/azure")
public class AzureDashboardController {

    private static final Logger log = LoggerFactory.getLogger(AzureDashboardController.class);

    @Autowired
    private AzureDashboardService azureDashboardService;

    @Autowired
    private AzureForecastingService azureForecastingService;

    @Autowired
    private RedisCacheService redisCache;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private ForecastAccuracyService forecastAccuracyService;

    /**
     * Get Azure dashboard data from the database snapshot.
     *
     * <p>
     * The snapshot is written by the daily scheduled refresh (04:30 IST, configurable via
     * {@code xamops.azure.dashboard-refresh-cron}) and by a first refresh triggered when a
     * subscription is connected. With {@code force=false} — which is what the UI sends on load and
     * on its 60-second poll — this reads that snapshot and makes no Azure API calls. The response
     * carries {@code snapshotAgeMinutes} and {@code stale} so the UI can say how old the figures
     * are instead of implying they are current.
     *
     * <p>
     * <b>{@code force=true} is honoured.</b> The previous version of this comment said the
     * parameter was "ignored for security (only scheduled jobs can refresh)", which was simply not
     * true — it was passed straight through then and now, and it triggers a full subscription-wide
     * ARM enumeration plus inline cost ingestion. Documenting it as ignored was worse than not
     * documenting it: anyone auditing this endpoint would have concluded there was no expensive
     * path to protect. There is, it is reachable by any authenticated caller, and it is currently
     * unthrottled and unlocked. Rate limiting it is tracked separately; this comment at least
     * stops the code lying about it.
     *
     * Example: GET /api/xamops/azure/dashboard-data?accountId=subscription-id
     */
    @GetMapping("/dashboard-data")
    public ResponseEntity<AzureDashboardData> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean force) {
        try {
            log.info("📊 Azure Dashboard request - Subscription: {} (force={})", accountId, force);

            AzureDashboardData data = azureDashboardService.getDashboardData(accountId, force);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error fetching Azure dashboard data for accountId: {}", accountId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Manually trigger a full Azure dashboard refresh (billing ingestion + DB cache update).
     * Use this to force-refresh data without waiting for the 4:30 AM nightly job.
     *
     * Example: POST /api/xamops/azure/dashboard/refresh?accountId=subscription-id
     */
    @PostMapping("/dashboard/refresh")
    public ResponseEntity<Map<String, Object>> refreshDashboard(@RequestParam String accountId) {
        log.info("🔄 Manual Azure dashboard refresh requested for subscription: {}", accountId);
        try {
            AzureDashboardData data = azureDashboardService.refreshAndCacheDashboard(accountId);
            if (data != null) {
                return ResponseEntity.ok(Map.of(
                        "status", "success",
                        "message", "Azure dashboard refreshed successfully",
                        "accountId", accountId,
                        "lastUpdated", data.getLastUpdated() != null ? data.getLastUpdated() : "just now"
                ));
            } else {
                return ResponseEntity.status(500).body(Map.of(
                        "status", "error",
                        "message", "Refresh failed — check server logs for details",
                        "accountId", accountId
                ));
            }
        } catch (Exception e) {
            log.error("❌ Manual Azure dashboard refresh failed for subscription: {}", accountId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error",
                    "message", e.getMessage(),
                    "accountId", accountId
            ));
        }
    }

    /**
     * Returns ML-powered cost forecast for an Azure subscription.
     * Uses the Prophet API via AzureForecastingService with Redis-cached cost history.
     *
     * Example: GET /api/xamops/azure/forecast/cost?accountId=subscription-id&periods=30
     */
    @GetMapping("/forecast/cost")
    public ResponseEntity<Map<String, Object>> getForecastCost(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "30") int periods,
            @RequestParam(required = false) String serviceName) {
        log.info("🔮 Azure forecast request for subscription: {} periods={} service={}", accountId,
                periods, serviceName);
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            AzureForecastingService.Forecast result = azureForecastingService
                    .getAzureCostForecast(account.getAzureSubscriptionId(), periods, serviceName)
                    .get(30_000L, TimeUnit.MILLISECONDS);

            List<ForecastDto> forecastList = result == null ? null : result.points();
            if (forecastList == null || forecastList.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "no_data", "forecast", Collections.emptyMap()));
            }

            // Recorded for accuracy tracking, fire-and-forget, as the AWS and GCP forecast endpoints
            // do. Without it the Azure cost page could show a forecast but never say how good the
            // previous ones turned out to be, and a forecast with no track record is a number the
            // reader has no basis to trust or to discount.
            //
            // Only the daily fit is recorded. Accuracy scores a prediction against the actual cost for
            // its target date, so recording a monthly point — which the fallback path produces — would
            // compare a month's spend against one day's and report an error of several thousand
            // percent. A missing badge is honest; a confidently wrong one is not.
            if (result.daily()) {
                try {
                    forecastAccuracyService.recordPredictions(
                            ForecastAccuracyRecord.PROVIDER_AZURE,
                            account.getAzureSubscriptionId(),
                            serviceName,
                            forecastList);
                } catch (Exception trackEx) {
                    log.warn("Accuracy tracking skipped for Azure subscription {}: {}", accountId,
                            trackEx.getMessage());
                }
            }

            Map<String, Object> forecast = new HashMap<>();
            forecast.put("dates",       forecastList.stream().map(ForecastDto::getDs).collect(Collectors.toList()));
            forecast.put("predictions", forecastList.stream().mapToDouble(ForecastDto::getYhat).boxed().collect(Collectors.toList()));
            forecast.put("upperBounds", forecastList.stream().mapToDouble(ForecastDto::getYhatUpper).boxed().collect(Collectors.toList()));
            forecast.put("lowerBounds", forecastList.stream().mapToDouble(ForecastDto::getYhatLower).boxed().collect(Collectors.toList()));

            Map<String, Object> body = new HashMap<>();
            body.put("status", "success");
            body.put("forecast", forecast);
            // So the chart can label its own x-axis honestly. The monthly fallback returns months, not
            // days, and captioning those "next 30 days" is what the previous version did.
            body.put("granularity", result.daily() ? "DAILY" : "MONTHLY");
            body.put("service", serviceName == null || serviceName.isBlank()
                    ? AzureForecastingService.ALL_SERVICES : serviceName);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("❌ Azure forecast error for {}: {}", accountId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "forecast", Collections.emptyMap()));
        }
    }

    /**
     * Meter categories with enough daily history to be forecast individually.
     *
     * <p>Populates the forecast's service selector, which the AWS cost page has always had and the
     * Azure one could not: its forecast read a subscription-wide monthly total, so there was nothing
     * to select between.
     */
    @GetMapping("/forecast/services")
    public ResponseEntity<List<String>> getForecastableServices(@RequestParam String accountId) {
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));
            return ResponseEntity.ok(
                    azureForecastingService.forecastableServices(account.getAzureSubscriptionId()));
        } catch (Exception e) {
            log.debug("Azure forecastable services unavailable for {}: {}", accountId, e.getMessage());
            // An empty selector is a smaller loss than a failed request the chart would report as an
            // error; the subscription-wide forecast still renders.
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Returns monthly cost breakdown for a specific service (for the trend drawer).
     * Reads from Redis cache populated by the billing ingestion job.
     *
     * Example: GET /api/xamops/azure/costs/service-trend?accountId=subscription-id&serviceName=Virtual+Machines
     */
    @GetMapping("/costs/service-trend")
    public ResponseEntity<Map<String, Object>> getServiceTrend(
            @RequestParam String accountId,
            @RequestParam String serviceName) {
        log.info("📈 Azure service trend request for subscription: {} service={}", accountId, serviceName);
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            String cacheKey = AzureBillingDataIngestionService.AZURE_SERVICE_MONTHLY_CACHE_PREFIX
                    + account.getAzureSubscriptionId();

            Optional<Map<String, Map<String, Double>>> cacheData = redisCache.get(cacheKey,
                    new TypeReference<Map<String, Map<String, Double>>>() {});

            Map<String, Double> serviceData = cacheData
                    .map(m -> m.getOrDefault(serviceName, new HashMap<>()))
                    .orElse(new HashMap<>());

            // Sorted via AzureMonthLabel rather than a default-locale parse. The old version fell
            // back to YearMonth.now() on a parse failure, so a label spelled by a different locale
            // silently sorted as the current month and scrambled the trend chart's order.
            List<String> labels = serviceData.keySet().stream()
                    .sorted(Comparator.comparing(
                            label -> com.xammer.cloud.service.azure.AzureMonthLabel.parse(label)
                                    .orElse(YearMonth.of(9999, 12))))
                    .collect(Collectors.toList());

            List<Double> costs = labels.stream()
                    .map(serviceData::get)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of("labels", labels, "costs", costs));
        } catch (Exception e) {
            log.error("❌ Azure service trend error for {}: {}", accountId, e.getMessage());
            return ResponseEntity.ok(Map.of("labels", Collections.emptyList(), "costs", Collections.emptyList()));
        }
    }
}
