package com.xammer.cloud.controller.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
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

    /**
     * ✅ Get Azure dashboard data from DATABASE CACHE
     *
     * Data is pre-cached by nightly scheduled job (4:30 AM IST).
     * This endpoint ALWAYS returns cached data - no live API calls.
     * force parameter is ignored for security (only scheduled jobs can refresh).
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
            @RequestParam(defaultValue = "30") int periods) {
        log.info("🔮 Azure forecast request for subscription: {} periods={}", accountId, periods);
        try {
            CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            List<ForecastDto> forecastList = azureForecastingService
                    .getAzureCostForecast(account.getAzureSubscriptionId(), periods)
                    .get(30_000L, TimeUnit.MILLISECONDS);

            if (forecastList == null || forecastList.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "no_data", "forecast", Collections.emptyMap()));
            }

            Map<String, Object> forecast = new HashMap<>();
            forecast.put("dates",       forecastList.stream().map(ForecastDto::getDs).collect(Collectors.toList()));
            forecast.put("predictions", forecastList.stream().mapToDouble(ForecastDto::getYhat).boxed().collect(Collectors.toList()));
            forecast.put("upperBounds", forecastList.stream().mapToDouble(ForecastDto::getYhatUpper).boxed().collect(Collectors.toList()));
            forecast.put("lowerBounds", forecastList.stream().mapToDouble(ForecastDto::getYhatLower).boxed().collect(Collectors.toList()));

            return ResponseEntity.ok(Map.of("status", "success", "forecast", forecast));
        } catch (Exception e) {
            log.error("❌ Azure forecast error for {}: {}", accountId, e.getMessage());
            return ResponseEntity.ok(Map.of("status", "error", "forecast", Collections.emptyMap()));
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

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM yyyy");
            List<String> labels = serviceData.keySet().stream()
                    .sorted(Comparator.comparing(label -> {
                        try { return YearMonth.parse(label, fmt); }
                        catch (Exception e) { return YearMonth.now(); }
                    }))
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
