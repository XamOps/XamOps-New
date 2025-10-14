package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.service.gcp.GcpCostService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
        // Fetch 3x the forecast periods (or max 180 days) for better accuracy
        int historicalDays = Math.min(periods * 3, 180);

        return costService.getHistoricalCost(accountId, "ALL".equalsIgnoreCase(serviceName) ? null : serviceName, null, historicalDays, false)
                .thenCompose(historicalCostData -> {
                    // Validate sufficient historical data
                    if (historicalCostData == null || historicalCostData.getLabels() == null || historicalCostData.getLabels().size() < 14) {
                        log.warn("❌ Not enough historical cost data for AWS account {} to generate a forecast (found {} days).",
                                accountId,
                                historicalCostData != null && historicalCostData.getLabels() != null ? historicalCostData.getLabels().size() : 0);
                        return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                    }

                    // Format historical data for Prophet
                    List<Map<String, Object>> formattedData = new ArrayList<>();
                    for (int i = 0; i < historicalCostData.getLabels().size(); i++) {
                        Map<String, Object> point = new HashMap<>();
                        point.put("ds", historicalCostData.getLabels().get(i));
                        point.put("y", historicalCostData.getCosts().get(i));
                        formattedData.add(point);
                    }

                    // ✅ Apply improved outlier filtering
                    List<Map<String, Object>> cleanedData = removeOutliers(formattedData);

                    // ✅ NEW: For very sparse data (< 20% non-zero), filter out zero days entirely
                    long nonZeroCount = cleanedData.stream()
                            .filter(point -> ((Number) point.get("y")).doubleValue() > 0.01)
                            .count();

                    if (nonZeroCount < cleanedData.size() * 0.2 && nonZeroCount >= 14) {
                        log.info("📊 Sparse data detected ({} non-zero out of {}) - using only non-zero cost days for forecast",
                                nonZeroCount, cleanedData.size());
                        cleanedData = removeZeroDays(cleanedData);
                    }

                    try {
                        // Prepare HTTP request to Python Prophet service
                        HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);

                        Map<String, Object> requestBody = new HashMap<>();
                        requestBody.put("data", cleanedData);
                        requestBody.put("periods", periods);
                        requestBody.put("weekly_seasonality", true);
                        requestBody.put("yearly_seasonality", false);

                        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                        log.info("📡 Calling Prophet forecast service with {} cleaned data points for {} periods",
                                cleanedData.size(), periods);

                        // Call Python Flask service
                        String forecastJson = restTemplate.postForObject("http://localhost:5002/forecast/cost", entity, String.class);

                        // ✅ FIX: Parse JSON response with "forecast" wrapper
                        JsonNode responseNode = objectMapper.readTree(forecastJson);

                        // Check for success status
                        if (responseNode.has("status") && !"success".equals(responseNode.get("status").asText())) {
                            log.error("❌ Prophet service returned error status");
                            return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                        }

                        // Extract forecast array
                        JsonNode forecastArray = responseNode.get("forecast");
                        if (forecastArray == null || !forecastArray.isArray()) {
                            log.error("❌ Invalid forecast response format - missing 'forecast' array");
                            return CompletableFuture.completedFuture(new ArrayList<ForecastDto>());
                        }

                        // Deserialize forecast array to List<ForecastDto>
                        List<ForecastDto> forecast = objectMapper.readValue(
                                forecastArray.toString(),
                                new TypeReference<List<ForecastDto>>() {});

                        log.info("✅ Forecast generated successfully: {} predictions", forecast.size());

                        return CompletableFuture.completedFuture(forecast);

                    } catch (Exception e) {
                        log.error("❌ Error calling Python forecast service for AWS account {}", accountId, e);
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
     * Generate GCP cost forecast with improved outlier preprocessing
     */
    public CompletableFuture<List<ForecastDto>> getGcpCostForecast(String gcpProjectId, String serviceName, int periods) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("🔮 Starting GCP cost forecast for project: {}, service: {}, periods: {}",
                    gcpProjectId, serviceName, periods);

            List<Map<String, Object>> dailyCosts = gcpCostService.getDailyCostsForForecast(gcpProjectId, serviceName, 90).join();

            if (dailyCosts.size() < 14) {
                log.warn("❌ Not enough historical data for GCP cost forecast. Found {} days.", dailyCosts.size());
                return new ArrayList<ForecastDto>();
            }

            // Apply outlier filtering to GCP data
            List<Map<String, Object>> cleanedData = removeOutliers(dailyCosts);

            // ✅ NEW: For sparse GCP data, filter out zero days
            long nonZeroCount = cleanedData.stream()
                    .filter(point -> ((Number) point.get("cost")).doubleValue() > 0.01)
                    .count();

            if (nonZeroCount < cleanedData.size() * 0.2 && nonZeroCount >= 14) {
                log.info("📊 Sparse GCP data detected - using only non-zero cost days");
                cleanedData = removeZeroDays(cleanedData);
            }

            // Convert to CSV format for Python script
            StringBuilder csvData = new StringBuilder("ds,y\n");
            cleanedData.forEach(costEntry ->
                    csvData.append(costEntry.get("date"))
                            .append(",")
                            .append(costEntry.get("cost"))
                            .append("\n"));

            try {
                ProcessBuilder pb = new ProcessBuilder("python", "forcasting/forecast_service.py", String.valueOf(periods));
                Process p = pb.start();

                // Send CSV data to Python process
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()))) {
                    writer.write(csvData.toString());
                }

                // Read forecast results
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String outputJson = reader.lines().collect(Collectors.joining("\n"));

                    if (p.waitFor() != 0) {
                        try (BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                            log.error("❌ Python script error: {}", errReader.lines().collect(Collectors.joining("\n")));
                        }
                        return new ArrayList<ForecastDto>();
                    }

                    log.info("✅ GCP forecast generated successfully");
                    return new ObjectMapper().readValue(outputJson, new TypeReference<List<ForecastDto>>() {});
                }
            } catch (Exception e) {
                log.error("❌ Error executing Python forecast script for GCP", e);
                Thread.currentThread().interrupt();
                return new ArrayList<ForecastDto>();
            }
        });
    }
}
