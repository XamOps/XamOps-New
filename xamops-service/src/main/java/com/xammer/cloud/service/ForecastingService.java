package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ForecastingService {

    private final CostService costService;
    private final GcpCostService gcpCostService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ✅ ADD THESE FIELD DECLARATIONS
    @Value("${forecast.service.url}")
    private String forecastServiceUrl;

    @Value("${app.environment:local}")
    private String environment;

    @Autowired
    public ForecastingService(CostService costService, GcpCostService gcpCostService, RestTemplate restTemplate) {
        this.costService = costService;
        this.gcpCostService = gcpCostService;
        this.restTemplate = restTemplate;
    }

    /**
     * ✅ FIXED: Remove outliers using IQR method with MEAN replacement (not median)
     * Handles sparse cost data (many zero days) by filtering only NON-ZERO values
     */
    private List<Map<String, Object>> removeOutliers(List<Map<String, Object>> data) {
        if (data == null || data.size() < 14) {
            log.debug("Skipping outlier removal - insufficient data: {} points", data != null ? data.size() : 0);
            return data;
        }

        try {
            // ✅ CRITICAL FIX: Filter out zero/near-zero values before calculating statistics
            // This prevents median=0 issue with sparse AWS cost data
            List<Double> nonZeroCosts = data.stream()
                    .map(point -> ((Number) point.get("y")).doubleValue())
                    .filter(cost -> cost > 0.01) // Only consider actual costs > $0.01
                    .sorted()
                    .collect(Collectors.toList());

            // ✅ UPDATED: Handle empty case
            if (nonZeroCosts.isEmpty()) {
                log.warn("⚠️ No non-zero cost data - cannot generate forecast");
                return data;
            }

            // ✅ UPDATED: Lower threshold from 30% to 5% for sparse data handling
            double nonZeroPercentage = (nonZeroCosts.size() * 100.0) / data.size();

            if (nonZeroCosts.size() < data.size() * 0.05) { // Less than 5% non-zero
                log.warn("⚠️ Very sparse cost data: {} non-zero out of {} total ({} %) - forecast may be inaccurate",
                        nonZeroCosts.size(), data.size(), String.format("%.1f", nonZeroPercentage));
                // Continue processing instead of returning early
            } else if (nonZeroCosts.size() < data.size() * 0.2) { // Less than 20% non-zero
                log.info("📊 Processing sparse cost data - will use mean of {} non-zero days ({} %)",
                        nonZeroCosts.size(), String.format("%.1f", nonZeroPercentage));
            }

            int size = nonZeroCosts.size();

            // Calculate Q1 (25th percentile) and Q3 (75th percentile) on NON-ZERO costs
            int q1Index = size / 4;
            int q3Index = (3 * size) / 4;
            double q1 = nonZeroCosts.get(q1Index);
            double q3 = nonZeroCosts.get(q3Index);
            double iqr = q3 - q1;

            // IQR multiplier: 2.0 for lenient filtering (preserves more legitimate high costs)
            double multiplier = 2.0;
            double lowerBound = Math.max(0.01, q1 - (multiplier * iqr)); // Don't go below $0.01
            double upperBound = q3 + (multiplier * iqr);

            // ✅ CRITICAL FIX: Use MEAN of non-zero costs for replacement (not median)
            double meanNonZero = nonZeroCosts.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            log.info("📊 Outlier Detection (Non-Zero Only) - Q1: ${}, Q3: ${}, IQR: ${}, Bounds: [${}, ${}], Mean: ${}",
                    String.format("%.2f", q1),
                    String.format("%.2f", q3),
                    String.format("%.2f", iqr),
                    String.format("%.2f", lowerBound),
                    String.format("%.2f", upperBound),
                    String.format("%.2f", meanNonZero));

            // Filter outliers and replace with mean (not median)
            List<Map<String, Object>> filtered = new ArrayList<>();
            int outlierCount = 0;

            for (Map<String, Object> point : data) {
                double cost = ((Number) point.get("y")).doubleValue();
                Map<String, Object> newPoint = new HashMap<>(point);

                // Only check non-zero costs for outliers
                if (cost > 0.01 && (cost < lowerBound || cost > upperBound)) {
                    newPoint.put("y", meanNonZero); // Replace with mean of non-zero costs
                    outlierCount++;
                    log.debug("🔍 Outlier detected on {}: ${} -> ${} (mean)",
                            point.get("ds"),
                            String.format("%.2f", cost),
                            String.format("%.2f", meanNonZero));
                } else {
                    newPoint.put("y", cost); // Keep original (including zeros)
                }
                filtered.add(newPoint);
            }

            double outlierPercentage = (outlierCount * 100.0) / data.size();
            log.info("✅ Outlier removal complete: {} outliers ({} %) smoothed using IQR+Mean method",
                    outlierCount, String.format("%.1f", outlierPercentage));

            return filtered;

        } catch (Exception e) {
            log.error("❌ Error during outlier removal, using original data", e);
            return data;
        }
    }

    /**
     * ✅ NEW: Remove zero-cost days for sparse datasets
     * For accounts with very sparse spending, only use days with actual costs
     */
    private List<Map<String, Object>> removeZeroDays(List<Map<String, Object>> data) {
        List<Map<String, Object>> nonZeroData = data.stream()
                .filter(point -> ((Number) point.get("y")).doubleValue() > 0.01)
                .collect(Collectors.toList());

        if (nonZeroData.size() < 14) {
            log.warn("⚠️ Only {} non-zero days found after filtering - not enough for forecasting (minimum 14 required)",
                    nonZeroData.size());
            return data; // Return original to let Prophet handle it
        }

        log.info("📊 Filtered sparse data: {} non-zero days (removed {} zero-cost days)",
                nonZeroData.size(), data.size() - nonZeroData.size());

        return nonZeroData;
    }

    /**
     * Generate AWS cost forecast with improved outlier preprocessing
     */
    public CompletableFuture<List<ForecastDto>> getCostForecast(String accountId, String serviceName, int periods) {
        // ✅ IMPROVED: Adaptive lookback for better pattern detection
        // Short-term forecasts need more history to learn patterns properly
        int historicalDays;
        if (periods <= 30) {
            historicalDays = 180;  // 6 months for short-term (better weekly/monthly patterns)
        } else if (periods <= 60) {
            historicalDays = 270;  // 9 months for medium-term (captures quarterly trends)
        } else {
            historicalDays = 365;  // 1 year for long-term (yearly seasonality)
        }
        
        log.info("📅 Using {} days of historical data for {}-day forecast (improved adaptive lookback)", 
                 historicalDays, periods);

        // Normalize serviceName - treat null, empty, or "ALL" the same
        String normalizedService = null;
        if (serviceName != null && !serviceName.trim().isEmpty() && !"ALL".equalsIgnoreCase(serviceName.trim())) {
            normalizedService = serviceName.trim();
        }

        return costService.getHistoricalCost(accountId, normalizedService, null, historicalDays, false)
                .thenCompose(historicalCostData -> {
                    if (historicalCostData == null || historicalCostData.getLabels() == null || historicalCostData.getLabels().size() < 14) {
                        log.warn("❌ Not enough historical cost data for AWS account {} to generate a forecast (found {} days).",
                                accountId,
                                historicalCostData != null && historicalCostData.getLabels() != null ? historicalCostData.getLabels().size() : 0);
                        return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                    }

                    List<Map<String, Object>> formattedData = new ArrayList<>();
                    for (int i = 0; i < historicalCostData.getLabels().size(); i++) {
                        Map<String, Object> point = new HashMap<>();
                        point.put("ds", historicalCostData.getLabels().get(i));
                        point.put("y", historicalCostData.getCosts().get(i));
                        formattedData.add(point);
                    }

                    List<Map<String, Object>> cleanedData = removeOutliers(formattedData);

                    long nonZeroCount = cleanedData.stream()
                            .filter(point -> ((Number) point.get("y")).doubleValue() > 0.01)
                            .count();

                    if (nonZeroCount < cleanedData.size() * 0.2 && nonZeroCount >= 14) {
                        log.info("📊 Sparse data detected ({} non-zero out of {}) - using only non-zero cost days for forecast",
                                nonZeroCount, cleanedData.size());
                        cleanedData = removeZeroDays(cleanedData);
                    }

                    try {
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);

                        // ✅ IMPROVED: Enable yearly seasonality when we have enough data
                        boolean enableYearlySeasonality = historicalDays >= 365;
                        
                        Map<String, Object> requestBody = new HashMap<>();
                        requestBody.put("data", cleanedData);
                        requestBody.put("periods", periods);
                        requestBody.put("weekly_seasonality", true);
                        requestBody.put("yearly_seasonality", enableYearlySeasonality);  // Was always false!

                        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                        // ✅ CHANGED: Use configurable URL instead of hardcoded localhost
                        String forecastUrl = forecastServiceUrl + "/forecast/cost";

                        log.info("📡 [{}] Calling Prophet forecast service at {} with {} cleaned data points for {} periods (yearly_seasonality={})",
                                environment, forecastUrl, cleanedData.size(), periods, enableYearlySeasonality);

                        // ✅ CHANGED: Use forecastUrl variable
                        String forecastJson = restTemplate.postForObject(forecastUrl, entity, String.class);

                        JsonNode responseNode = objectMapper.readTree(forecastJson);

                        if (responseNode.has("status") && !"success".equals(responseNode.get("status").asText())) {
                            log.error("❌ Prophet service returned error status");
                            return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                        }

                        JsonNode forecastArray = responseNode.get("forecast");
                        if (forecastArray == null || !forecastArray.isArray()) {
                            log.error("❌ Invalid forecast response format - missing 'forecast' array");
                            return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                        }

                        List<ForecastDto> forecast = objectMapper.readValue(
                                forecastArray.toString(),
                                new TypeReference<List<ForecastDto>>() {});

                        log.info("✅ [{}] Forecast generated successfully: {} predictions", environment, forecast.size());

                        return CompletableFuture.completedFuture(forecast);

                    } catch (Exception e) {
                        log.error("❌ [{}] Error calling Python forecast service for AWS account {}: {}",
                                environment, accountId, e.getMessage());
                        return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                    }
                })
                .handle((result, ex) -> {
                    if (ex != null) {
                        log.error("❌ Exception in cost forecast pipeline for account {}", accountId, ex);
                        return new ArrayList<ForecastDto>();
                    }
                    return result;
                });
    }

    /**
     * Generate GCP cost forecast using the Prophet Flask service (same as AWS)
     */
    public CompletableFuture<List<ForecastDto>> getGcpCostForecast(String gcpProjectId, String serviceName, int periods) {
        // ✅ IMPROVED: Adaptive lookback for better pattern detection (same as AWS)
        int historicalDays;
        if (periods <= 30) {
            historicalDays = 180;  // 6 months for short-term
        } else if (periods <= 60) {
            historicalDays = 270;  // 9 months for medium-term
        } else {
            historicalDays = 365;  // 1 year for long-term
        }
        
        log.info("📅 GCP: Using {} days of historical data for {}-day forecast", historicalDays, periods);

        return gcpCostService.getDailyCostsForForecast(gcpProjectId, serviceName, historicalDays)
                .thenCompose(dailyCosts -> {
                    if (dailyCosts.size() < 14) {
                        log.warn("❌ Not enough historical data for GCP cost forecast. Found {} days.", dailyCosts.size());
                        return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                    }

                    // Remap GCP keys ("date"/"cost") → Prophet keys ("ds"/"y")
                    List<Map<String, Object>> formattedData = dailyCosts.stream()
                            .map(entry -> {
                                Map<String, Object> point = new HashMap<>();
                                point.put("ds", entry.get("date"));
                                point.put("y", entry.get("cost"));
                                return point;
                            })
                            .collect(Collectors.toList());

                    List<Map<String, Object>> cleanedData = removeOutliers(formattedData);

                    long nonZeroCount = cleanedData.stream()
                            .filter(point -> ((Number) point.get("y")).doubleValue() > 0.01)
                            .count();

                    if (nonZeroCount < cleanedData.size() * 0.2 && nonZeroCount >= 14) {
                        log.info("📊 Sparse GCP data detected - using only non-zero cost days");
                        cleanedData = removeZeroDays(cleanedData);
                    }

                    try {
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);

                        // ✅ IMPROVED: Enable yearly seasonality when we have enough data
                        boolean enableYearlySeasonality = historicalDays >= 365;

                        Map<String, Object> requestBody = new HashMap<>();
                        requestBody.put("data", cleanedData);
                        requestBody.put("periods", periods);
                        requestBody.put("weekly_seasonality", true);
                        requestBody.put("yearly_seasonality", enableYearlySeasonality);

                        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
                        String forecastUrl = forecastServiceUrl + "/forecast/cost";

                        log.info("📡 Calling Prophet forecast service for GCP project {} with {} data points, {} periods (yearly_seasonality={})",
                                gcpProjectId, cleanedData.size(), periods, enableYearlySeasonality);

                        String forecastJson = restTemplate.postForObject(forecastUrl, entity, String.class);
                        JsonNode responseNode = objectMapper.readTree(forecastJson);

                        if (responseNode.has("status") && !"success".equals(responseNode.get("status").asText())) {
                            log.error("❌ Prophet service returned error for GCP project {}", gcpProjectId);
                            return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                        }

                        JsonNode forecastArray = responseNode.get("forecast");
                        if (forecastArray == null || !forecastArray.isArray()) {
                            log.error("❌ Invalid forecast response format for GCP project {}", gcpProjectId);
                            return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                        }

                        List<ForecastDto> forecast = objectMapper.readValue(
                                forecastArray.toString(), new TypeReference<List<ForecastDto>>() {});

                        log.info("✅ GCP forecast generated successfully: {} predictions", forecast.size());
                        return CompletableFuture.completedFuture(forecast);

                    } catch (Exception e) {
                        log.error("❌ Error calling Prophet forecast service for GCP project {}: {}", gcpProjectId, e.getMessage());
                        return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                    }
                });
    }
}
