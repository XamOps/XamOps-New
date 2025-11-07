package com.xammer.cloud.service.azure;

import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.ProphetRequestDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class AzureForecastingService {

    private static final Logger logger = LoggerFactory.getLogger(AzureForecastingService.class);

    private final RedisCacheService redisCache;
    private final RestTemplate restTemplate;

    @Value("${forecast.service.url}")
    private String prophetApiUrl;

    // This is the cache key used by AzureBillingDataIngestionService
    private static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";

    @Autowired
    public AzureForecastingService(RedisCacheService redisCache, RestTemplate restTemplate) {
        this.redisCache = redisCache;
        this.restTemplate = restTemplate;
    }

    /**
     * Gets historical data from Azure's cache and sends it to the Prophet API.
     */
    @Async("awsTaskExecutor") // Re-using the existing async executor
    public CompletableFuture<List<ForecastDto>> getAzureCostForecast(String subscriptionId, int periods) {
        logger.info("🔮 Generating Azure cost forecast for account: {}", subscriptionId);

        String historyCacheKey = AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId;
        Optional<AzureDashboardData.CostHistory> costHistoryOpt = redisCache.get(historyCacheKey, AzureDashboardData.CostHistory.class);

        if (costHistoryOpt.isEmpty()) {
            logger.warn("❌ No Azure historical cost found in cache for {}. Forecast will be empty.", subscriptionId);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        AzureDashboardData.CostHistory costHistory = costHistoryOpt.get();
        List<String> labels = costHistory.getLabels();
        List<Double> costs = costHistory.getCosts();
        List<Boolean> anomalies = costHistory.getAnomalies();

        if (labels == null || costs == null || anomalies == null || labels.size() != costs.size() || labels.size() != anomalies.size()) {
            logger.warn("❌ Historical cost data is malformed for {}. Forecast will be empty.", subscriptionId);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // --- FIX: Build the data list as List<Map<String, Object>> to match ProphetRequestDto ---
        List<Map<String, Object>> historicalData = IntStream.range(0, labels.size())
            .filter(i -> !Boolean.TRUE.equals(anomalies.get(i))) // Filter OUT forecast data
            .mapToObj(i -> Map.<String, Object>of("ds", labels.get(i), "y", costs.get(i)))
            .collect(Collectors.toList());
        
        if (historicalData.isEmpty()) {
             logger.warn("❌ No historical (non-forecast) data points found for {}. Cannot generate forecast.", subscriptionId);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        // (Optional) IQR Outlier filtering
        List<Double> historicalCosts = historicalData.stream().map(map -> (Double) map.get("y")).collect(Collectors.toList());
        if (historicalCosts.size() > 4) {
            Collections.sort(historicalCosts);
            double q1 = historicalCosts.get((int) (historicalCosts.size() * 0.25));
            double q3 = historicalCosts.get((int) (historicalCosts.size() * 0.75));
            double iqr = q3 - q1;
            double lowerBound = q1 - 1.5 * iqr;
            double upperBound = q3 + 1.5 * iqr;

            List<Map<String, Object>> filteredData = historicalData.stream()
                .filter(d -> (Double)d.get("y") >= lowerBound && (Double)d.get("y") <= upperBound)
                .collect(Collectors.toList());
            logger.info("Filtered {} outliers from Azure historical data for account {}", historicalData.size() - filteredData.size(), subscriptionId);
            historicalData = filteredData; // Use the filtered data
        }

        // --- FIX: Create a SINGLE ProphetRequestDto and set its data ---
        ProphetRequestDto prophetRequest = new ProphetRequestDto();
        prophetRequest.setData(historicalData);
        prophetRequest.setPeriods(periods);
        prophetRequest.setWeeklySeasonality(true); // Set defaults
        prophetRequest.setYearlySeasonality(false); // Set defaults

        // Call Prophet API
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ProphetRequestDto> entity = new HttpEntity<>(prophetRequest, headers); // Send the single request object
        String url = prophetApiUrl + "/predict"; // The API DTO already contains the periods

        try {
            // --- FIX for RestTemplate call ---
            // Use exchange() with ParameterizedTypeReference to correctly handle List<ForecastDto>
            ResponseEntity<List<ForecastDto>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<List<ForecastDto>>() {}
            );
            
            List<ForecastDto> forecast = response.getBody();
            if (forecast != null) {
                logger.info("✅ Successfully generated Azure cost forecast for account {}", subscriptionId);
                return CompletableFuture.completedFuture(forecast);
            }
        } catch (Exception e) {
            logger.error("❌ Error calling Prophet API for Azure account {}: {}", subscriptionId, e.getMessage());
        }
        
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}