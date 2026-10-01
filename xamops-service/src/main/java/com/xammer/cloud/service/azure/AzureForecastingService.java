package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.ProphetRequestDto;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Azure cost forecasting, at the finest grain the ingestion caches support.
 *
 * <h2>Why this reads the daily series rather than the monthly one</h2>
 * It used to forecast from {@code azure:cost-history}, which holds at most six monthly totals. Three
 * things followed from that, and all three were wrong:
 *
 * <ul>
 *   <li><b>Six points is not a series.</b> Prophet was being asked to fit a trend and a weekly
 *       seasonality — {@code weeklySeasonality(true)} was set — to monthly data, where a week is not
 *       a resolvable feature. The confidence band it returned was wide because the model had almost
 *       nothing to go on, not because Azure spend is unpredictable.</li>
 *   <li><b>The horizon meant the wrong thing.</b> Prophet extends the series at the frequency it
 *       infers from the input, so {@code periods=30} against monthly input forecast thirty
 *       <em>months</em>. The chart was labelled "predictions for next 30 days" and drew two and a half
 *       years.</li>
 *   <li><b>It could not be scored.</b> Forecast accuracy compares a prediction against the actual
 *       cost for its target date. A monthly prediction dated to a month start, compared against one
 *       day's actuals, would report an error of several thousand percent — so recording these at all
 *       would have produced a confidently wrong accuracy badge rather than no badge.</li>
 * </ul>
 *
 * <p>The daily per-service series that billing ingestion already caches for anomaly detection fixes
 * all three. It also makes a per-service forecast possible, which is what the AWS cost page's service
 * selector has always offered and the Azure one could not.
 *
 * <p>The monthly path is kept as a fallback for a subscription whose daily series has not been
 * ingested yet, and {@link Forecast#daily()} says which one produced the answer — so the caller can
 * record daily predictions for scoring and decline to record monthly ones.
 */
@Service
public class AzureForecastingService {

    private static final Logger logger = LoggerFactory.getLogger(AzureForecastingService.class);

    /** Below this many daily points, a daily fit is worse than the monthly fallback. */
    private static final int MIN_DAILY_POINTS = 14;

    private final RedisCacheService redisCache;
    private final RestTemplate restTemplate;
    private final AzureBillingDataIngestionService ingestionService;

    @Value("${forecast.service.url}")
    private String prophetApiUrl;

    // This is the cache key used by AzureBillingDataIngestionService
    private static final String AZURE_COST_HISTORY_CACHE_PREFIX = "azure:cost-history:";

    public AzureForecastingService(RedisCacheService redisCache, RestTemplate restTemplate,
                                   AzureBillingDataIngestionService ingestionService) {
        this.redisCache = redisCache;
        this.restTemplate = restTemplate;
        this.ingestionService = ingestionService;
    }

    /**
     * A forecast and the grain it was produced at.
     *
     * @param points the predictions, one per period
     * @param daily  true when fitted to daily observations, so each point is one day and can be
     *               scored against that day's actual cost. False for the monthly fallback, whose
     *               points are months and must not be recorded for accuracy.
     */
    public record Forecast(List<ForecastDto> points, boolean daily) {

        static final Forecast EMPTY = new Forecast(List.of(), false);
    }

    /** Every service in the subscription. Matches the sentinel the accuracy records use. */
    public static final String ALL_SERVICES = "ALL";

    /**
     * Forecasts a subscription's spend, optionally for one service.
     *
     * @param serviceName a meter category to forecast on its own, or null/"ALL" for the whole
     *                    subscription
     */
    @Async("awsTaskExecutor") // Re-using the existing async executor
    public CompletableFuture<Forecast> getAzureCostForecast(String subscriptionId, int periods,
                                                            String serviceName) {
        String service = serviceName == null || serviceName.isBlank() ? ALL_SERVICES : serviceName;
        logger.info("🔮 Generating Azure cost forecast for subscription {} (service={})",
                subscriptionId, service);

        List<Map<String, Object>> daily = dailySeries(subscriptionId, service);
        if (daily.size() >= MIN_DAILY_POINTS) {
            List<ForecastDto> points = callProphet(subscriptionId, trimOutliers(daily), periods, true);
            if (!points.isEmpty()) {
                return CompletableFuture.completedFuture(new Forecast(points, true));
            }
        }

        // Not enough daily history. A per-service request has nowhere else to go — the monthly cache
        // is a subscription total, and answering a request for one service with the whole
        // subscription's spend would be a different number under the same label.
        if (!ALL_SERVICES.equals(service)) {
            logger.info("❌ No daily history for {} / service {} — {} point(s), need {}",
                    subscriptionId, service, daily.size(), MIN_DAILY_POINTS);
            return CompletableFuture.completedFuture(Forecast.EMPTY);
        }

        List<Map<String, Object>> monthly = monthlySeries(subscriptionId);
        if (monthly.isEmpty()) {
            return CompletableFuture.completedFuture(Forecast.EMPTY);
        }
        logger.info("Falling back to monthly history for {} — {} daily point(s) is below the {} needed",
                subscriptionId, daily.size(), MIN_DAILY_POINTS);
        // Weekly seasonality off: the input is monthly, so a week is not a feature the model can
        // resolve and asking for one only widens the band.
        return CompletableFuture.completedFuture(
                new Forecast(callProphet(subscriptionId, trimOutliers(monthly), periods, false), false));
    }

    /**
     * Daily observations for the subscription, or for one service within it.
     *
     * <p>Today is excluded. The Cost Management query's upper bound is midnight today and the blob
     * export is month-to-date, so today's row is either absent or a fraction of a day — and a partial
     * final observation drags the fitted trend down every time the forecast runs.
     */
    private List<Map<String, Object>> dailySeries(String subscriptionId, String service) {
        Map<String, Map<String, Double>> byService =
                ingestionService.readDailyServiceCosts(subscriptionId).orElse(Map.of());
        if (byService.isEmpty()) {
            return List.of();
        }

        LocalDate today = LocalDate.now();
        // Sorted by the ISO date key, which sorts chronologically as a string by construction.
        TreeMap<String, Double> byDay = new TreeMap<>();
        byService.forEach((name, days) -> {
            if (!ALL_SERVICES.equals(service) && !name.equalsIgnoreCase(service)) {
                return;
            }
            days.forEach((isoDate, cost) -> {
                if (cost == null) {
                    return;
                }
                try {
                    if (!LocalDate.parse(isoDate).isBefore(today)) {
                        return;
                    }
                } catch (Exception e) {
                    return;
                }
                byDay.merge(isoDate, cost, Double::sum);
            });
        });

        List<Map<String, Object>> points = new ArrayList<>(byDay.size());
        byDay.forEach((isoDate, cost) -> points.add(Map.of("ds", isoDate, "y", cost)));
        return points;
    }

    /** The six-month monthly history, forecast points excluded. The fallback path. */
    private List<Map<String, Object>> monthlySeries(String subscriptionId) {
        Optional<AzureDashboardData.CostHistory> costHistoryOpt = redisCache.get(
                AZURE_COST_HISTORY_CACHE_PREFIX + subscriptionId, AzureDashboardData.CostHistory.class);
        if (costHistoryOpt.isEmpty()) {
            logger.warn("❌ No Azure historical cost found in cache for {}. Forecast will be empty.",
                    subscriptionId);
            return List.of();
        }

        AzureDashboardData.CostHistory costHistory = costHistoryOpt.get();
        List<String> labels = costHistory.getLabels();
        List<Double> costs = costHistory.getCosts();
        List<Boolean> isForecast = costHistory.getForecast();

        if (labels == null || costs == null || isForecast == null
                || labels.size() != costs.size() || labels.size() != isForecast.size()) {
            logger.warn("❌ Historical cost data is malformed for {}. Forecast will be empty.",
                    subscriptionId);
            return List.of();
        }

        List<Map<String, Object>> historicalData = IntStream.range(0, labels.size())
                .filter(i -> !Boolean.TRUE.equals(isForecast.get(i))) // Filter OUT projected months
                .mapToObj(i -> Map.<String, Object>of("ds", labels.get(i), "y", costs.get(i)))
                .collect(Collectors.toList());

        if (historicalData.isEmpty()) {
            logger.warn("❌ No historical (non-forecast) data points found for {}. Cannot forecast.",
                    subscriptionId);
        }
        return historicalData;
    }

    /**
     * Drops points outside the interquartile fence.
     *
     * <p>One reservation purchase or an annual support charge lands on a single day and is several
     * times the daily run rate. Left in, Prophet reads it as level rather than as a one-off and
     * forecasts every following day at the purchase price.
     */
    private List<Map<String, Object>> trimOutliers(List<Map<String, Object>> data) {
        if (data.size() <= 4) {
            return data;
        }
        List<Double> sorted = data.stream()
                .map(d -> (Double) d.get("y"))
                .sorted()
                .collect(Collectors.toList());
        double q1 = sorted.get((int) (sorted.size() * 0.25));
        double q3 = sorted.get((int) (sorted.size() * 0.75));
        double iqr = q3 - q1;
        double lower = q1 - 1.5 * iqr;
        double upper = q3 + 1.5 * iqr;

        List<Map<String, Object>> filtered = data.stream()
                .filter(d -> (Double) d.get("y") >= lower && (Double) d.get("y") <= upper)
                .collect(Collectors.toList());
        // Never return an empty series: a fence that excludes everything is a fence computed from a
        // series too flat to have quartiles, not a series of outliers.
        return filtered.isEmpty() ? data : filtered;
    }

    private List<ForecastDto> callProphet(String subscriptionId, List<Map<String, Object>> data,
                                          int periods, boolean daily) {
        ProphetRequestDto prophetRequest = new ProphetRequestDto();
        prophetRequest.setData(data);
        prophetRequest.setPeriods(periods);
        // Only meaningful on daily observations. Azure spend has a real weekday/weekend shape —
        // batch jobs, dev environments on schedules — which is exactly what this captures, and which
        // a monthly series cannot express.
        prophetRequest.setWeeklySeasonality(daily);
        prophetRequest.setYearlySeasonality(false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ProphetRequestDto> entity = new HttpEntity<>(prophetRequest, headers);
        String url = prophetApiUrl + "/forecast/cost";

        try {
            String responseJson = restTemplate.postForObject(url, entity, String.class);
            JsonNode responseNode = new ObjectMapper().readTree(responseJson);

            if (responseNode.has("status") && !"success".equals(responseNode.get("status").asText())) {
                logger.error("❌ Prophet service returned error for Azure subscription {}", subscriptionId);
                return Collections.emptyList();
            }

            JsonNode forecastArray = responseNode.get("forecast");
            if (forecastArray == null || !forecastArray.isArray()) {
                logger.error("❌ Invalid forecast response format for Azure subscription {}", subscriptionId);
                return Collections.emptyList();
            }

            List<ForecastDto> forecast = new ObjectMapper().readValue(
                    forecastArray.toString(), new TypeReference<List<ForecastDto>>() {});
            logger.info("✅ Azure forecast for {} — {} point(s) from {} {} observation(s)",
                    subscriptionId, forecast.size(), data.size(), daily ? "daily" : "monthly");
            return forecast;
        } catch (Exception e) {
            logger.error("❌ Error calling Prophet API for Azure subscription {}: {}", subscriptionId,
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Meter categories with enough daily history to be forecast on their own.
     *
     * <p>Populates the service selector. Offering every service the breakdown knows about would put
     * options in the list that answer with an empty chart, because a service billed for three days
     * cannot be fitted.
     */
    public List<String> forecastableServices(String subscriptionId) {
        Map<String, Map<String, Double>> byService =
                ingestionService.readDailyServiceCosts(subscriptionId).orElse(Map.of());
        return byService.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().size() >= MIN_DAILY_POINTS)
                .map(Map.Entry::getKey)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }
}
