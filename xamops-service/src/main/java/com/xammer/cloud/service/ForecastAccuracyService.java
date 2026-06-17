package com.xammer.cloud.service;

import com.xammer.cloud.domain.ForecastAccuracyRecord;
import com.xammer.cloud.dto.CostForecastDto;
import com.xammer.cloud.dto.ForecastAccuracyMetrics;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.repository.ForecastAccuracyRepository;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tracks ML forecast accuracy by persisting each predicted day and later comparing
 * it against the realised (actual) cost. Powers the "is our model actually any good?"
 * question and lets us measure whether model changes (lookback, seasonality, etc.)
 * improve real-world accuracy.
 *
 * Recording is fire-and-forget (@Async). Evaluation runs from a daily scheduler.
 */
@Service
public class ForecastAccuracyService {

    private static final Logger log = LoggerFactory.getLogger(ForecastAccuracyService.class);

    /** Records older than this (post target date) with no actual are marked SKIPPED. */
    private static final int SKIP_AFTER_DAYS = 35;
    /** Safety cap on how far back we ask the actuals API to go in one evaluation. */
    private static final int MAX_ACTUALS_LOOKBACK = 200;
    /** Treat costs below this as "zero" for percentage-error purposes. */
    private static final double ZERO_THRESHOLD = 0.01;

    private final ForecastAccuracyRepository repository;
    private final CostService costService;
    private final GcpCostService gcpCostService;

    public ForecastAccuracyService(ForecastAccuracyRepository repository,
                                   CostService costService,
                                   GcpCostService gcpCostService) {
        this.repository = repository;
        this.costService = costService;
        this.gcpCostService = gcpCostService;
    }

    // ============================================================
    // Recording
    // ============================================================

    /** Normalize a service name; null/blank/"ALL" all collapse to the sentinel "ALL". */
    public static String normalizeService(String serviceName) {
        if (serviceName == null) return "ALL";
        String s = serviceName.trim();
        if (s.isEmpty() || "ALL".equalsIgnoreCase(s)) return "ALL";
        return s;
    }

    /**
     * Record predictions from an AWS-style CostForecastDto (parallel date/prediction lists).
     */
    @Async("awsTaskExecutor")
    public void recordCostForecast(String provider, String accountId, String serviceName, CostForecastDto forecast) {
        if (forecast == null || forecast.getDates() == null || forecast.getDates().isEmpty()) {
            return;
        }
        List<ForecastDto> points = new ArrayList<>();
        List<String> dates = forecast.getDates();
        List<Double> preds = nullSafe(forecast.getPredictions());
        List<Double> lowers = nullSafe(forecast.getLowerBounds());
        List<Double> uppers = nullSafe(forecast.getUpperBounds());
        for (int i = 0; i < dates.size(); i++) {
            ForecastDto p = new ForecastDto();
            p.setDs(dates.get(i));
            p.setYhat(i < preds.size() ? preds.get(i) : 0.0);
            p.setYhatLower(i < lowers.size() ? lowers.get(i) : 0.0);
            p.setYhatUpper(i < uppers.size() ? uppers.get(i) : 0.0);
            points.add(p);
        }
        recordPredictions(provider, accountId, serviceName, points);
    }

    /**
     * Record a list of ForecastDto predictions. Idempotent per (provider, account,
     * service, forecastDate, targetDate). Only future days are stored.
     */
    @Async("awsTaskExecutor")
    @Transactional
    public void recordPredictions(String provider, String accountId, String serviceName, List<ForecastDto> points) {
        try {
            if (provider == null || accountId == null || points == null || points.isEmpty()) {
                return;
            }
            String svc = normalizeService(serviceName);
            LocalDate today = LocalDate.now();
            LocalDate forecastDate = today;

            // Existing target dates for this batch → skip duplicates (idempotent).
            Set<LocalDate> existing = new HashSet<>(
                    repository.findExistingTargetDates(provider, accountId, svc, forecastDate));

            List<ForecastAccuracyRecord> toSave = new ArrayList<>();
            for (ForecastDto p : points) {
                LocalDate targetDate = parseDate(p.getDs());
                if (targetDate == null) {
                    continue; // unparseable date — skip safely
                }
                // Only track genuine future predictions; today's actual isn't final yet.
                if (!targetDate.isAfter(today)) {
                    continue;
                }
                if (existing.contains(targetDate)) {
                    continue;
                }
                double yhat = sanitize(p.getYhat());
                double lower = sanitize(p.getYhatLower());
                double upper = sanitize(p.getYhatUpper());
                // Guard ordering: lower <= yhat <= upper
                if (lower > upper) {
                    double tmp = lower; lower = upper; upper = tmp;
                }
                toSave.add(new ForecastAccuracyRecord(
                        provider, accountId, svc, forecastDate, targetDate, yhat, lower, upper));
                existing.add(targetDate);
            }

            if (!toSave.isEmpty()) {
                repository.saveAll(toSave);
                log.info("📝 Recorded {} forecast predictions for accuracy tracking ({}/{}/{})",
                        toSave.size(), provider, accountId, svc);
            }
        } catch (Exception e) {
            // Never let accuracy bookkeeping break the forecast response path.
            log.warn("⚠️ Failed to record forecast predictions for {}/{}: {}",
                    provider, accountId, e.getMessage());
        }
    }

    // ============================================================
    // Evaluation (called by scheduler)
    // ============================================================

    /**
     * Evaluate all pending predictions whose target day has closed.
     * Groups by (provider, account, service) to minimise actuals API calls.
     *
     * @return number of records newly evaluated
     */
    @Transactional
    public int evaluatePending() {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.minusDays(1); // only fully-closed days
        List<ForecastAccuracyRecord> pending =
                repository.findByStatusAndTargetDateLessThanEqual(ForecastAccuracyRecord.STATUS_PENDING, cutoff);

        if (pending.isEmpty()) {
            log.info("📊 Forecast accuracy: no pending records to evaluate");
            return 0;
        }

        // Group: provider | accountId | service
        Map<String, List<ForecastAccuracyRecord>> groups = pending.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getProvider() + "|" + r.getAccountId() + "|" + r.getServiceName()));

        int evaluated = 0;
        int skipped = 0;
        List<ForecastAccuracyRecord> dirty = new ArrayList<>();

        for (Map.Entry<String, List<ForecastAccuracyRecord>> entry : groups.entrySet()) {
            List<ForecastAccuracyRecord> recs = entry.getValue();
            ForecastAccuracyRecord sample = recs.get(0);

            // How far back must we fetch actuals for this group?
            LocalDate oldest = recs.stream()
                    .map(ForecastAccuracyRecord::getTargetDate)
                    .min(LocalDate::compareTo)
                    .orElse(today);
            int daysBack = (int) Math.min(MAX_ACTUALS_LOOKBACK,
                    Math.max(7, ChronoUnit.DAYS.between(oldest, today) + 2));

            Map<LocalDate, Double> actuals;
            try {
                actuals = fetchActuals(sample.getProvider(), sample.getAccountId(),
                        sample.getServiceName(), daysBack);
            } catch (Exception e) {
                log.warn("⚠️ Could not fetch actuals for {} — leaving {} records pending: {}",
                        entry.getKey(), recs.size(), e.getMessage());
                continue; // try again next run
            }

            for (ForecastAccuracyRecord r : recs) {
                Double actual = actuals.get(r.getTargetDate());
                if (actual == null) {
                    // No actual yet. Retry until the skip window expires.
                    r.setEvalAttempts(r.getEvalAttempts() + 1);
                    if (r.getTargetDate().isBefore(today.minusDays(SKIP_AFTER_DAYS))) {
                        r.setStatus(ForecastAccuracyRecord.STATUS_SKIPPED);
                        r.setEvaluatedAt(java.time.Instant.now());
                        skipped++;
                    }
                    dirty.add(r);
                    continue;
                }
                applyEvaluation(r, actual);
                dirty.add(r);
                evaluated++;
            }
        }

        if (!dirty.isEmpty()) {
            repository.saveAll(dirty);
        }
        log.info("📊 Forecast accuracy evaluation complete: {} evaluated, {} skipped, {} groups",
                evaluated, skipped, groups.size());
        return evaluated;
    }

    /** Compute and set error metrics for a single record given the actual cost. */
    private void applyEvaluation(ForecastAccuracyRecord r, double actualRaw) {
        double actual = sanitize(actualRaw);
        double predicted = sanitize(r.getPredictedCost());

        r.setActualCost(actual);
        double absErr = Math.abs(predicted - actual);
        r.setAbsoluteError(round(absErr));

        // Percentage error only defined when actual is non-trivial.
        if (actual > ZERO_THRESHOLD) {
            r.setPercentageError(round(absErr / actual * 100.0));
        } else if (predicted <= ZERO_THRESHOLD) {
            // both ~zero → perfect prediction
            r.setPercentageError(0.0);
        } else {
            // actual ~zero but we predicted spend → undefined APE, leave null (excluded from MAPE)
            r.setPercentageError(null);
        }

        // Confidence interval coverage
        r.setWithinConfidence(actual >= r.getPredictedLower() - ZERO_THRESHOLD
                && actual <= r.getPredictedUpper() + ZERO_THRESHOLD);

        r.setStatus(ForecastAccuracyRecord.STATUS_EVALUATED);
        r.setEvaluatedAt(java.time.Instant.now());
    }

    /**
     * Fetch realised daily costs keyed by date. AWS and GCP have true daily data;
     * other providers return empty (records age out to SKIPPED).
     */
    private Map<LocalDate, Double> fetchActuals(String provider, String accountId,
                                                String serviceName, int daysBack) throws Exception {
        boolean allServices = "ALL".equalsIgnoreCase(serviceName);
        String svcFilter = allServices ? null : serviceName;
        Map<LocalDate, Double> out = new HashMap<>();

        if (ForecastAccuracyRecord.PROVIDER_AWS.equals(provider)) {
            HistoricalCostDto hist = costService
                    .getHistoricalCost(accountId, svcFilter, null, daysBack, false)
                    .get(30, TimeUnit.SECONDS);
            if (hist != null && hist.getLabels() != null) {
                List<String> labels = hist.getLabels();
                List<Double> costs = hist.getCosts();
                for (int i = 0; i < labels.size() && i < costs.size(); i++) {
                    LocalDate d = parseDate(labels.get(i));
                    if (d != null && costs.get(i) != null) {
                        out.put(d, costs.get(i));
                    }
                }
            }
        } else if (ForecastAccuracyRecord.PROVIDER_GCP.equals(provider)) {
            List<Map<String, Object>> daily =
                    gcpCostService.getDailyCostsForForecastSync(accountId, svcFilter, daysBack, false);
            if (daily != null) {
                for (Map<String, Object> row : daily) {
                    Object dateObj = row.get("date");
                    Object costObj = row.get("cost");
                    if (dateObj == null || costObj == null) continue;
                    LocalDate d = parseDate(dateObj.toString());
                    if (d == null) continue;
                    double c = ((Number) costObj).doubleValue();
                    out.put(d, c);
                }
            }
        }
        // AZURE: no reliable daily actuals source — return empty (handled gracefully).
        return out;
    }

    // ============================================================
    // Metrics / aggregation
    // ============================================================

    /**
     * Aggregate accuracy metrics. If serviceName is null/blank, aggregates across all
     * services for the account; if "ALL" or specific, scopes to that series.
     */
    @Transactional(readOnly = true)
    public ForecastAccuracyMetrics getMetrics(String provider, String accountId,
                                              String serviceName, int days) {
        String prov = (provider == null || provider.isBlank())
                ? ForecastAccuracyRecord.PROVIDER_AWS : provider.trim().toUpperCase();
        int window = (days <= 0) ? 90 : Math.min(days, 365);
        LocalDate since = LocalDate.now().minusDays(window);

        List<ForecastAccuracyRecord> records;
        String scopeService;
        if (serviceName == null || serviceName.isBlank()) {
            records = repository.findEvaluatedForAccount(prov, accountId, since);
            scopeService = null; // aggregated
        } else {
            scopeService = normalizeService(serviceName);
            records = repository.findEvaluatedForAccountService(prov, accountId, scopeService, since);
        }

        if (records == null || records.isEmpty()) {
            return ForecastAccuracyMetrics.insufficient(prov, accountId, scopeService, 0);
        }

        return computeMetrics(prov, accountId, scopeService, records);
    }

    private ForecastAccuracyMetrics computeMetrics(String provider, String accountId,
                                                   String serviceName,
                                                   List<ForecastAccuracyRecord> evaluated) {
        int evaluatedCount = evaluated.size();

        // MAPE over records with a defined percentage error
        List<Double> apes = evaluated.stream()
                .map(ForecastAccuracyRecord::getPercentageError)
                .filter(Objects::nonNull)
                .filter(v -> !v.isNaN() && !v.isInfinite())
                .collect(Collectors.toList());

        List<Double> aes = evaluated.stream()
                .map(ForecastAccuracyRecord::getAbsoluteError)
                .filter(Objects::nonNull)
                .filter(v -> !v.isNaN() && !v.isInfinite())
                .collect(Collectors.toList());

        long coveredCount = evaluated.stream()
                .filter(r -> Boolean.TRUE.equals(r.getWithinConfidence()))
                .count();

        Double mape = apes.isEmpty() ? null
                : round(apes.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        Double mae = aes.isEmpty() ? null
                : round(aes.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        Double accuracyScore = (mape == null) ? null : round(Math.max(0, 100.0 - mape));
        Double coverage = round((double) coveredCount / evaluatedCount * 100.0);

        // MAPE by horizon bucket
        Map<String, Double> mapeByHorizon = new LinkedHashMap<>();
        mapeByHorizon.put("1-7d", bucketMape(evaluated, 1, 7));
        mapeByHorizon.put("8-30d", bucketMape(evaluated, 8, 30));
        mapeByHorizon.put("31-60d", bucketMape(evaluated, 31, 60));
        mapeByHorizon.put("61-90d", bucketMape(evaluated, 61, 90));
        mapeByHorizon.values().removeIf(Objects::isNull);

        ForecastAccuracyMetrics m = new ForecastAccuracyMetrics();
        m.setProvider(provider);
        m.setAccountId(accountId);
        m.setServiceName(serviceName);
        m.setEvaluatedCount(evaluatedCount);
        m.setPendingCount(0);
        m.setSkippedCount(0);
        m.setMape(mape);
        m.setMae(mae);
        m.setAccuracyScore(accuracyScore);
        m.setConfidenceCoverage(coverage);
        m.setMapeByHorizon(mapeByHorizon);

        // Data quality classification
        if (evaluatedCount < 7) {
            m.setDataQuality("INSUFFICIENT_DATA");
            m.setMessage("Only " + evaluatedCount + " evaluated day(s). Metrics will stabilise "
                    + "as more predicted days are realised.");
        } else if (mape == null) {
            m.setDataQuality("FAIR");
            m.setMessage("Actual costs were near zero on evaluated days, so percentage error is "
                    + "undefined. Showing absolute error and confidence coverage instead.");
        } else if (mape <= 15) {
            m.setDataQuality("GOOD");
            m.setMessage("Forecasts are accurate (MAPE " + mape + "%).");
        } else if (mape <= 30) {
            m.setDataQuality("FAIR");
            m.setMessage("Forecasts are reasonable (MAPE " + mape + "%). Variable workloads "
                    + "reduce accuracy.");
        } else {
            m.setDataQuality("POOR");
            m.setMessage("Forecasts are off (MAPE " + mape + "%). This account's spend is highly "
                    + "variable or had recent anomalies.");
        }
        return m;
    }

    private Double bucketMape(List<ForecastAccuracyRecord> records, int minH, int maxH) {
        List<Double> apes = records.stream()
                .filter(r -> r.getHorizonDays() >= minH && r.getHorizonDays() <= maxH)
                .map(ForecastAccuracyRecord::getPercentageError)
                .filter(Objects::nonNull)
                .filter(v -> !v.isNaN() && !v.isInfinite())
                .collect(Collectors.toList());
        if (apes.isEmpty()) return null;
        return round(apes.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    // ============================================================
    // Retention
    // ============================================================

    @Transactional
    public int cleanupOldRecords(int retentionDays) {
        try {
            int deleted = repository.deleteOldRecords(LocalDate.now().minusDays(retentionDays));
            if (deleted > 0) {
                log.info("🧹 Cleaned up {} old forecast accuracy records (> {} days)", deleted, retentionDays);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("⚠️ Forecast accuracy cleanup failed: {}", e.getMessage());
            return 0;
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static List<Double> nullSafe(List<Double> in) {
        return in == null ? Collections.emptyList() : in;
    }

    private static LocalDate parseDate(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try {
            // Handles "2026-06-17" and "2026-06-17T00:00:00" style by taking the date part.
            if (t.length() >= 10) {
                return LocalDate.parse(t.substring(0, 10));
            }
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Clamp NaN/Infinite/negative to a safe non-negative double. */
    private static double sanitize(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.max(0.0, v);
    }

    private static Double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.round(v * 100.0) / 100.0;
    }
}
