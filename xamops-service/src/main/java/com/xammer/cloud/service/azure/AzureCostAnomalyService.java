package com.xammer.cloud.service.azure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * Detects day-over-day cost spikes per Azure service.
 *
 * <p>
 * Azure had no anomaly detection at all. AWS reads Cost Explorer's {@code GetAnomalies}, GCP runs a
 * trailing-average query over its BigQuery billing export, and the Azure dashboard hardcoded
 * {@code setCostAnomalies(Collections.emptyList())} — so an Azure subscription always reported a
 * clean bill of health, which is indistinguishable from one that had been checked and found clean.
 *
 * <p>
 * <strong>Why not an Azure API.</strong> Azure Cost Management surfaces anomaly <em>alerts</em> only
 * through scheduled anomaly alert rules a customer configures themselves, and its anomaly insight is
 * not exposed as a queryable per-service history the way Cost Explorer's is. What Azure does give us
 * is the daily cost export we already ingest. So this ports GCP's approach rather than inventing a
 * different one: same window, same thresholds, same output type, so a spike means the same thing on
 * all three providers.
 *
 * <h2>Method</h2>
 * For each service and each day, compare that day's cost against the mean of the seven days before
 * it. Flag the day when the trailing mean is above {@link #MIN_BASELINE_COST} and the day's cost
 * exceeds {@link #SPIKE_MULTIPLIER} times that mean. The reported {@code unexpectedSpend} is the
 * amount above the trailing mean, not the whole day's cost.
 *
 * <p>
 * Both thresholds exist to stop noise being reported as findings. Without the baseline floor, a
 * service that costs two cents a day and then four triggers a 100% spike; without the multiplier,
 * ordinary weekday/weekend variation does.
 */
@Service
public class AzureCostAnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AzureCostAnomalyService.class);

    private static final String ANOMALY_CACHE_PREFIX = "azure:cost-anomalies:";
    private static final String ANOMALY_DB_PREFIX = "AZURE_COST_ANOMALIES::";
    private static final int CACHE_TTL_MINUTES = 60;

    /** Days of trailing history each day is compared against. Matches the GCP detector. */
    private static final int TRAILING_WINDOW_DAYS = 7;

    /**
     * Days back to scan for anomalies. Longer than the trailing window so a spike still has history
     * behind it at the start of the scan range.
     */
    private static final int LOOKBACK_DAYS = 60;

    /**
     * Minimum trailing average, in the export's currency, for a service to be worth checking.
     *
     * <p>Matches the GCP detector's {@code avg_prev_7_days > 10}. Below this a doubling is cents.
     */
    private static final double MIN_BASELINE_COST = 10.0;

    /** How far above the trailing average a day must be. Matches GCP's {@code * 1.5}. */
    private static final double SPIKE_MULTIPLIER = 1.5;

    /** Cap on reported anomalies, highest unexpected spend first. Matches GCP's {@code LIMIT 20}. */
    private static final int MAX_ANOMALIES = 20;

    /**
     * Days of history needed before the detector can say anything.
     *
     * <p>A trailing mean over fewer than a full window is not a baseline, it is a coincidence. The
     * blob ingestion path reads only the latest month-to-date export, so on the 3rd of a month there
     * genuinely is not enough history — and saying so beats returning an empty list that reads as
     * "no anomalies".
     */
    private static final int MIN_DAYS_FOR_DETECTION = TRAILING_WINDOW_DAYS + 1;

    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;

    /**
     * The Spring-configured mapper, not a bare {@code new ObjectMapper()}.
     *
     * <p>{@link DashboardData.CostAnomaly} carries {@link LocalDate} fields, and a default mapper
     * refuses to serialize {@code java.time} types — the snapshot write would throw at runtime while
     * compiling perfectly. The injected bean has the JSR-310 module registered, and it is the same
     * mapper {@code RedisCacheService} uses, so the Redis and DB copies stay in one format.
     */
    private final ObjectMapper objectMapper;

    public AzureCostAnomalyService(RedisCacheService redisCache,
            CachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * The result of a detection run.
     *
     * <p>
     * Carries a reason because an empty anomaly list has two meanings that a bare {@code List} cannot
     * tell apart: "spend looks normal" and "detection could not run". The second is not an edge case —
     * subscriptions whose billing arrives through the month-to-date blob export have no seven-day
     * baseline for the first week of every month — and rendering it as a clean bill of health is the
     * same class of false-clean this service was written to remove.
     *
     * @param anomalies detected spikes, highest unexpected spend first
     * @param unavailableReason why the list is empty, or null when detection ran normally
     */
    public record AnomalyOutcome(List<DashboardData.CostAnomaly> anomalies, String unavailableReason) {

        static AnomalyOutcome of(List<DashboardData.CostAnomaly> anomalies) {
            return new AnomalyOutcome(anomalies, null);
        }

        static AnomalyOutcome unavailable(String reason) {
            return new AnomalyOutcome(Collections.emptyList(), reason);
        }
    }

    /**
     * Cost anomalies for a subscription, newest-and-largest first.
     *
     * <p>Async to match {@code GcpCostService.getCostAnomalies} so the controllers look alike; the
     * work itself is local arithmetic over a cached series, not a cloud call.
     */
    public CompletableFuture<AnomalyOutcome> getCostAnomalies(String subscriptionId, boolean forceRefresh) {
        return CompletableFuture.supplyAsync(() -> detect(subscriptionId, forceRefresh));
    }

    /** Synchronous variant, for callers already running inside their own executor. */
    public AnomalyOutcome detect(String subscriptionId, boolean forceRefresh) {
        String cacheKey = ANOMALY_CACHE_PREFIX + subscriptionId;
        String dbKey = ANOMALY_DB_PREFIX + subscriptionId;

        if (!forceRefresh) {
            Optional<List<DashboardData.CostAnomaly>> cached = redisCache.get(
                    cacheKey, new TypeReference<List<DashboardData.CostAnomaly>>() {});
            if (cached.isPresent()) {
                log.debug("Returning cached Azure cost anomalies for {}", subscriptionId);
                return AnomalyOutcome.of(cached.get());
            }
        }

        Map<String, Map<String, Double>> dailyByService = loadDailySeries(subscriptionId);
        if (dailyByService.isEmpty()) {
            log.info("No daily per-service cost series for {} — cannot detect anomalies. This "
                    + "subscription's billing ingestion has not produced daily rows yet.", subscriptionId);
            return AnomalyOutcome.unavailable("Cost anomaly detection has no daily cost data for this "
                    + "subscription yet. It becomes available after the Azure cost export has been "
                    + "ingested, so an empty list here does not mean spend is normal.");
        }

        LocalDate today = LocalDate.now();
        LocalDate earliest = today.minusDays(LOOKBACK_DAYS);

        List<DashboardData.CostAnomaly> anomalies = new ArrayList<>();
        int servicesWithEnoughHistory = 0;

        for (Map.Entry<String, Map<String, Double>> entry : dailyByService.entrySet()) {
            String service = entry.getKey() == null || entry.getKey().isBlank()
                    ? "Unknown" : entry.getKey();

            // Gap-filled BEFORE the history gate, so the gate and the detector agree on what a day
            // is. Gating on recorded days while detecting on calendar days skipped a service with
            // five recorded days spread over forty, and admitted a weekday-only service whose
            // trailing window then contained two synthetic zeros — pulling its mean down about 29%
            // and putting an ordinary Monday within reach of the 1.5x threshold.
            TreeMap<LocalDate, Double> series = fillGaps(toSortedSeries(entry.getValue(), earliest));
            if (series.size() < MIN_DAYS_FOR_DETECTION) {
                continue;
            }
            servicesWithEnoughHistory++;
            anomalies.addAll(detectForService(subscriptionId, service, series));
        }

        if (servicesWithEnoughHistory == 0) {
            log.info("Azure anomaly detection for {}: no service has {} days of daily cost history "
                    + "yet, so no baseline exists to compare against.",
                    subscriptionId, MIN_DAYS_FOR_DETECTION);
            return AnomalyOutcome.unavailable("Cost anomaly detection needs "
                    + MIN_DAYS_FOR_DETECTION + " days of daily cost history to establish a baseline, and "
                    + "no service has that much yet. This is expected early in a month for "
                    + "subscriptions billed through a month-to-date cost export.");
        }

        anomalies.sort(Comparator.comparingDouble(
                (DashboardData.CostAnomaly a) -> a.getUnexpectedSpend()).reversed());
        if (anomalies.size() > MAX_ANOMALIES) {
            anomalies = new ArrayList<>(anomalies.subList(0, MAX_ANOMALIES));
        }

        redisCache.put(cacheKey, anomalies, CACHE_TTL_MINUTES);
        saveToDb(dbKey, anomalies);
        log.info("Azure anomaly detection for {}: {} anomalies across {} services with sufficient history",
                subscriptionId, anomalies.size(), servicesWithEnoughHistory);
        return AnomalyOutcome.of(anomalies);
    }

    /**
     * Runs the trailing-average test across one service's series.
     */
    private List<DashboardData.CostAnomaly> detectForService(String subscriptionId, String service,
            TreeMap<LocalDate, Double> series) {

        List<LocalDate> days = new ArrayList<>(series.keySet());
        List<DashboardData.CostAnomaly> found = new ArrayList<>();

        for (int i = TRAILING_WINDOW_DAYS; i < days.size(); i++) {
            LocalDate day = days.get(i);

            double trailingSum = 0.0;
            for (int back = i - TRAILING_WINDOW_DAYS; back < i; back++) {
                trailingSum += series.get(days.get(back));
            }
            double trailingAverage = trailingSum / TRAILING_WINDOW_DAYS;

            if (trailingAverage <= MIN_BASELINE_COST) {
                continue;
            }

            double cost = series.get(day);
            if (cost <= trailingAverage * SPIKE_MULTIPLIER) {
                continue;
            }

            // Only the excess over the baseline is unexpected. Reporting the whole day's cost would
            // overstate every finding by the amount the service normally costs.
            double unexpectedSpend = Math.round((cost - trailingAverage) * 100.0) / 100.0;

            found.add(new DashboardData.CostAnomaly(
                    anomalyId(subscriptionId, service, day),
                    service,
                    unexpectedSpend,
                    day,
                    day,
                    // Carried through rather than discarded, so a consumer can report the
                    // multiple rather than only the excess.
                    Math.round(trailingAverage * 100.0) / 100.0));
        }

        return found;
    }

    /**
     * A stable id for an anomaly.
     *
     * <p>Derived from the subscription, service and date rather than a counter, so the same spike
     * keeps the same id across refreshes. A counter-based id — which is what the GCP detector uses —
     * renumbers every run, so anything downstream that dedupes or acknowledges by id sees a fresh
     * anomaly each time the cache expires.
     */
    private String anomalyId(String subscriptionId, String service, LocalDate day) {
        return "AZURE-ANOMALY-" + subscriptionId + "-"
                + Integer.toHexString(service.hashCode()) + "-" + day;
    }

    /** Parses and sorts one service's day map, dropping anything older than the lookback. */
    private TreeMap<LocalDate, Double> toSortedSeries(Map<String, Double> byDay, LocalDate earliest) {
        TreeMap<LocalDate, Double> series = new TreeMap<>();
        if (byDay == null) {
            return series;
        }
        for (Map.Entry<String, Double> point : byDay.entrySet()) {
            if (point.getKey() == null || point.getValue() == null) {
                continue;
            }
            try {
                LocalDate day = LocalDate.parse(point.getKey());
                if (!day.isBefore(earliest)) {
                    series.put(day, point.getValue());
                }
            } catch (Exception e) {
                log.debug("Skipping unparseable daily cost key '{}'", point.getKey());
            }
        }
        return series;
    }

    /**
     * Inserts explicit zeros for days with no recorded spend, between the first and last day present.
     *
     * <p>A missing day means no charge, not no information. Leaving the gaps in would let a trailing
     * "week" span a month of calendar time, which is not a baseline for a daily figure.
     *
     * <p>Called before the history gate so both work on the same notion of a day.
     */
    private TreeMap<LocalDate, Double> fillGaps(TreeMap<LocalDate, Double> series) {
        if (series.isEmpty()) {
            return series;
        }
        TreeMap<LocalDate, Double> filled = new TreeMap<>();
        LocalDate cursor = series.firstKey();
        LocalDate last = series.lastKey();
        while (!cursor.isAfter(last)) {
            filled.put(cursor, series.getOrDefault(cursor, 0.0));
            cursor = cursor.plusDays(1);
        }
        return filled;
    }

    /** Reads the daily series from Redis, falling back to the DB snapshot. */
    private Map<String, Map<String, Double>> loadDailySeries(String subscriptionId) {
        String cacheKey = AzureBillingDataIngestionService.AZURE_DAILY_SERVICE_COST_CACHE_PREFIX
                + subscriptionId;
        TypeReference<Map<String, Map<String, Double>>> type = new TypeReference<>() {};

        try {
            Optional<Map<String, Map<String, Double>>> fromRedis = redisCache.get(cacheKey, type);
            if (fromRedis.isPresent() && !fromRedis.get().isEmpty()) {
                return fromRedis.get();
            }
        } catch (Exception e) {
            log.debug("Redis miss for Azure daily cost series {}: {}", subscriptionId, e.getMessage());
        }

        try {
            Optional<CachedData> snapshot = cachedDataRepository.findById(
                    AzureBillingDataIngestionService.AZURE_DAILY_SERVICE_COST_DB_PREFIX + subscriptionId);
            if (snapshot.isPresent()) {
                Map<String, Map<String, Double>> fromDb =
                        objectMapper.readValue(snapshot.get().getJsonData(), type);
                if (fromDb != null && !fromDb.isEmpty()) {
                    redisCache.put(cacheKey, fromDb, 60 * 24);
                    return fromDb;
                }
            }
        } catch (Exception e) {
            log.warn("Could not read the Azure daily cost snapshot for {}: {}",
                    subscriptionId, e.getMessage());
        }

        return Collections.emptyMap();
    }

    private void saveToDb(String dbKey, List<DashboardData.CostAnomaly> anomalies) {
        try {
            CachedData entity = cachedDataRepository.findById(dbKey).orElseGet(CachedData::new);
            entity.setCacheKey(dbKey);
            entity.setJsonData(objectMapper.writeValueAsString(anomalies));
            entity.setLastUpdated(java.time.LocalDateTime.now());
            cachedDataRepository.save(entity);
        } catch (Exception e) {
            log.warn("Could not save Azure anomaly snapshot {}: {}", dbKey, e.getMessage());
        }
    }
}
