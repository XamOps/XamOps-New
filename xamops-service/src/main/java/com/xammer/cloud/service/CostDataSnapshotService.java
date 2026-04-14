package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostDto;
import com.xammer.cloud.dto.DetailedCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Snapshots historical AWS cost data into cached_data with absolute month keys
 * so that past months remain accessible after Billing Conductor is applied.
 *
 * Key scheme (permanent, no TTL):
 *   AWS_MONTHLY_TOTAL::{accountId}::{YYYY-MM}          → Double total cost
 *   AWS_MONTHLY_BREAKDOWN_SVC::{accountId}::{YYYY-MM}  → List<CostDto> by service
 *   AWS_MONTHLY_BREAKDOWN_RGN::{accountId}::{YYYY-MM}  → List<CostDto> by region
 *   AWS_MONTHLY_SVC_REGION::{accountId}::{YYYY-MM}     → List<DetailedCostDto> svc×region
 *   AWS_DAILY_COST::{accountId}::{YYYY-MM}             → List<DailyCostEntry> all days
 *   AWS_MONTHLY_SVC_USAGE::{accountId}::{YYYY-MM}      → List<SvcUsageEntry> service×usageType
 *   AWS_CF_USAGE::{accountId}::{YYYY-MM}               → List<CfUsageEntry> CloudFront region×usageType
 */
@Service
public class CostDataSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(CostDataSnapshotService.class);

    // Snapshot job starts from this month (inclusive)
    private static final YearMonth SNAPSHOT_START = YearMonth.of(2025, 1);

    private final CloudAccountRepository cloudAccountRepository;
    private final CachedDataRepository cachedDataRepository;
    private final AwsClientProvider awsClientProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public CostDataSnapshotService(CloudAccountRepository cloudAccountRepository,
                                   CachedDataRepository cachedDataRepository,
                                   AwsClientProvider awsClientProvider,
                                   ObjectMapper objectMapper) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.awsClientProvider = awsClientProvider;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Auto-run on startup — fills any missing historical snapshots
    // -------------------------------------------------------------------------

    /**
     * Automatically runs snapshotAllHistoricalMonths() on application startup
     * in a background thread. Already-snapshotted months are skipped (idempotent).
     * This ensures historical data is preserved even if the manual endpoint is never called.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoSnapshotOnStartup() {
        Thread thread = new Thread(() -> {
            logger.info("[Snapshot] Auto-snapshot triggered on startup — filling missing historical months...");
            try {
                Map<String, String> result = snapshotAllHistoricalMonths();
                long failed = result.values().stream().filter(v -> v.contains("failed")).count();
                logger.info("[Snapshot] Auto-snapshot complete — {} accounts processed, {} with failures",
                        result.size(), failed);
            } catch (Exception e) {
                logger.error("[Snapshot] Auto-snapshot startup job failed: {}", e.getMessage());
            }
        }, "cost-snapshot-startup");
        thread.setDaemon(true);
        thread.start();
    }

    // -------------------------------------------------------------------------
    // Public key helpers — used by CostService read logic
    // -------------------------------------------------------------------------

    public static String keyMonthlyTotal(String accountId, YearMonth ym) {
        return "AWS_MONTHLY_TOTAL::" + accountId + "::" + ym;
    }

    public static String keyMonthlyBreakdownSvc(String accountId, YearMonth ym) {
        return "AWS_MONTHLY_BREAKDOWN_SVC::" + accountId + "::" + ym;
    }

    public static String keyMonthlyBreakdownRgn(String accountId, YearMonth ym) {
        return "AWS_MONTHLY_BREAKDOWN_RGN::" + accountId + "::" + ym;
    }

    public static String keyMonthlySvcRegion(String accountId, YearMonth ym) {
        return "AWS_MONTHLY_SVC_REGION::" + accountId + "::" + ym;
    }

    public static String keyDailyCost(String accountId, YearMonth ym) {
        return "AWS_DAILY_COST::" + accountId + "::" + ym;
    }

    public static String keySvcUsage(String accountId, YearMonth ym) {
        return "AWS_MONTHLY_SVC_USAGE::" + accountId + "::" + ym;
    }

    public static String keyCfUsage(String accountId, YearMonth ym) {
        return "AWS_CF_USAGE::" + accountId + "::" + ym;
    }

    /**
     * Returns true when yearMonth is strictly before the current calendar month,
     * meaning its data is final and safe to read from snapshot (no CE API needed).
     */
    public static boolean isClosedMonth(YearMonth yearMonth) {
        return yearMonth.isBefore(YearMonth.now());
    }

    /**
     * Returns true if all 7 snapshot key types are present for the given account + month.
     * Uses CF_USAGE as the sentinel (it is saved last).
     */
    public boolean isSnapshotComplete(String accountId, YearMonth ym) {
        return cachedDataRepository.existsById(keyCfUsage(accountId, ym));
    }

    // -------------------------------------------------------------------------
    // Snapshot single month for a single account
    // -------------------------------------------------------------------------

    /**
     * Fetches and persists all 7 snapshot key types for the given account + month.
     * Skips gracefully if the account has no CE credentials.
     * Does NOT overwrite an existing snapshot unless overwrite=true.
     */
    public void snapshotMonthForAccount(CloudAccount account, YearMonth ym, boolean overwrite) {
        String accountId = account.getAwsAccountId();
        if (accountId == null || accountId.isBlank()) return;

        String keyTotal = keyMonthlyTotal(accountId, ym);

        // Skip only if ALL 7 key types are present (CF_USAGE is saved last, so it's the sentinel)
        if (!overwrite && cachedDataRepository.existsById(keyCfUsage(accountId, ym))) {
            logger.debug("[Snapshot] All 7 key types present, skipping: {} / {}", accountId, ym);
            return;
        }

        logger.info("[Snapshot] Snapshotting {} for account {}", ym, accountId);

        try {
            CostExplorerClient ce = awsClientProvider.getCostExplorerClient(account);

            LocalDate start = ym.atDay(1);
            LocalDate end   = ym.atEndOfMonth().plusDays(1); // CE end is exclusive

            // ── 1. Monthly total ────────────────────────────────────────────
            double total = fetchMonthlyTotal(ce, start, end);
            persist(keyTotal, total);

            // ── 2. Service breakdown ────────────────────────────────────────
            List<CostDto> svcBreakdown = fetchBreakdown(ce, start, end, "SERVICE");
            persist(keyMonthlyBreakdownSvc(accountId, ym), svcBreakdown);

            // ── 3. Region breakdown ─────────────────────────────────────────
            List<CostDto> rgnBreakdown = fetchBreakdown(ce, start, end, "REGION");
            persist(keyMonthlyBreakdownRgn(accountId, ym), rgnBreakdown);

            // ── 4. Service × Region breakdown ───────────────────────────────
            List<DetailedCostDto> svcRgn = fetchSvcRegionBreakdown(ce, start, end);
            persist(keyMonthlySvcRegion(accountId, ym), svcRgn);

            // ── 5. Daily cost for every day in the month ────────────────────
            List<DailyCostEntry> daily = fetchDailyCost(ce, start, end);
            persist(keyDailyCost(accountId, ym), daily);

            // ── 6. Service × UsageType breakdown ────────────────────────────
            List<SvcUsageEntry> svcUsage = fetchSvcUsageBreakdown(ce, start, end);
            persist(keySvcUsage(accountId, ym), svcUsage);

            // ── 7. CloudFront region × UsageType breakdown ───────────────────
            List<CfUsageEntry> cfUsage = fetchCfUsageBreakdown(ce, start, end);
            persist(keyCfUsage(accountId, ym), cfUsage);

            logger.info("[Snapshot] ✅ Done {} / {} — total=${}", accountId, ym,
                    String.format("%.2f", total));

        } catch (Exception e) {
            logger.error("[Snapshot] ❌ Failed {} / {}: {}", accountId, ym, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Bulk pre-extraction (one-time admin trigger before April 1st)
    // -------------------------------------------------------------------------

    /**
     * Snapshots all months from SNAPSHOT_START up to (and including) last month
     * for every active AWS account.
     *
     * Returns a summary map: accountId → "OK" | error message
     */
    public Map<String, String> snapshotAllHistoricalMonths() {
        List<CloudAccount> accounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "AWS".equals(a.getProvider()) && a.getAwsAccountId() != null)
                .collect(Collectors.toList());

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth ym = SNAPSHOT_START; !ym.isAfter(lastMonth); ym = ym.plusMonths(1)) {
            months.add(ym);
        }

        logger.info("[Snapshot] Starting bulk pre-extraction: {} accounts × {} months",
                accounts.size(), months.size());

        Map<String, String> summary = new LinkedHashMap<>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger skipCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (CloudAccount account : accounts) {
            String accountId = account.getAwsAccountId();
            int accountSuccess = 0;
            int accountFail = 0;
            boolean accountPermanentlyFailed = false;

            for (YearMonth ym : months) {
                // Use CF_USAGE (last saved) as sentinel — skip only if all 7 types are present
                if (cachedDataRepository.existsById(keyCfUsage(accountId, ym))) {
                    skipCount.incrementAndGet();
                    continue;
                }
                try {
                    snapshotMonthForAccount(account, ym, false);
                    successCount.incrementAndGet();
                    accountSuccess++;
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    // Permanent errors: CE not enabled, IAM/STS access denied, historical data limit.
                    // No point retrying remaining months — they will all fail the same way.
                    boolean permanent = msg.contains("not enabled for cost explorer")
                            || msg.contains("historical data beyond")
                            || msg.contains("not authorized to perform")
                            || msg.contains("AccessDenied")
                            || msg.contains("Status Code: 403");
                    failCount.incrementAndGet();
                    accountFail++;
                    logger.warn("[Snapshot] Failed {} / {}: {}", accountId, ym, msg);
                    if (permanent) {
                        logger.warn("[Snapshot] Permanent error for account {} — skipping remaining {} months to avoid redundant CE calls",
                                accountId, months.size() - months.indexOf(ym) - 1);
                        accountPermanentlyFailed = true;
                        break; // skip all remaining months for this account
                    }
                }
            }
            summary.put(accountId, accountFail == 0
                    ? "OK (" + accountSuccess + " months snapshotted)"
                    : accountSuccess + " OK, " + accountFail + " failed" + (accountPermanentlyFailed ? " (permanent error — skipped remaining months)" : ""));
        }

        logger.info("[Snapshot] Bulk extraction complete — success={}, skipped={}, failed={}",
                successCount.get(), skipCount.get(), failCount.get());
        return summary;
    }

    /**
     * Snapshots the CURRENT month's final numbers.
     * Called by the end-of-month scheduler (11 PM last day of month).
     */
    public void snapshotCurrentMonthFinal() {
        YearMonth current = YearMonth.now();
        List<CloudAccount> accounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "AWS".equals(a.getProvider()) && a.getAwsAccountId() != null)
                .collect(Collectors.toList());

        logger.info("[Snapshot] End-of-month final snapshot for {} — {} accounts",
                current, accounts.size());

        for (CloudAccount account : accounts) {
            try {
                snapshotMonthForAccount(account, current, true); // overwrite = true (final numbers)
            } catch (Exception e) {
                logger.error("[Snapshot] End-of-month failed for {}: {}", account.getAwsAccountId(), e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot status report — single bulk DB query, no N+1
    // -------------------------------------------------------------------------

    /**
     * Returns a per-account, per-month snapshot coverage report.
     * Uses a single bulk findAllById query (no N+1).
     *
     * Per month a "complete" entry means all 7 key types are present.
     * "partial" means some key types are missing for that month.
     * "missing" means none of the 7 key types are present.
     */
    public Map<String, Object> getSnapshotStatusReport() {
        List<CloudAccount> accounts = cloudAccountRepository.findAll().stream()
                .filter(a -> "AWS".equals(a.getProvider()) && a.getAwsAccountId() != null)
                .collect(Collectors.toList());

        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth ym = SNAPSHOT_START; !ym.isAfter(lastMonth); ym = ym.plusMonths(1)) {
            months.add(ym);
        }

        // Build full set of expected keys for bulk fetch
        List<String> allExpectedKeys = new ArrayList<>();
        for (CloudAccount account : accounts) {
            String accountId = account.getAwsAccountId();
            for (YearMonth ym : months) {
                allExpectedKeys.add(keyMonthlyTotal(accountId, ym));
                allExpectedKeys.add(keyMonthlyBreakdownSvc(accountId, ym));
                allExpectedKeys.add(keyMonthlyBreakdownRgn(accountId, ym));
                allExpectedKeys.add(keyMonthlySvcRegion(accountId, ym));
                allExpectedKeys.add(keyDailyCost(accountId, ym));
                allExpectedKeys.add(keySvcUsage(accountId, ym));
                allExpectedKeys.add(keyCfUsage(accountId, ym));
            }
        }

        // Single bulk DB query — IN clause with all expected keys
        Set<String> existingKeys = new HashSet<>();
        cachedDataRepository.findAllById(allExpectedKeys)
                .forEach(row -> existingKeys.add(row.getCacheKey()));

        List<Map<String, Object>> accountStatuses = new ArrayList<>();
        int grandTotal = 0;
        int grandSaved = 0;
        int grandMissing = 0;

        for (CloudAccount account : accounts) {
            String accountId = account.getAwsAccountId();
            List<String> complete = new ArrayList<>();
            List<String> missing  = new ArrayList<>();
            Map<String, List<String>> partial = new LinkedHashMap<>();

            for (YearMonth ym : months) {
                String ymStr    = ym.toString();
                boolean hasTotal    = existingKeys.contains(keyMonthlyTotal(accountId, ym));
                boolean hasSvc      = existingKeys.contains(keyMonthlyBreakdownSvc(accountId, ym));
                boolean hasRgn      = existingKeys.contains(keyMonthlyBreakdownRgn(accountId, ym));
                boolean hasSvcRgn   = existingKeys.contains(keyMonthlySvcRegion(accountId, ym));
                boolean hasDaily    = existingKeys.contains(keyDailyCost(accountId, ym));
                boolean hasSvcUsage = existingKeys.contains(keySvcUsage(accountId, ym));
                boolean hasCfUsage  = existingKeys.contains(keyCfUsage(accountId, ym));

                boolean allPresent  = hasTotal && hasSvc && hasRgn && hasSvcRgn && hasDaily && hasSvcUsage && hasCfUsage;
                boolean nonePresent = !hasTotal && !hasSvc && !hasRgn && !hasSvcRgn && !hasDaily && !hasSvcUsage && !hasCfUsage;

                grandTotal++;
                if (allPresent) {
                    complete.add(ymStr);
                    grandSaved++;
                } else if (nonePresent) {
                    missing.add(ymStr);
                    grandMissing++;
                } else {
                    List<String> missingKeys = new ArrayList<>();
                    if (!hasTotal)    missingKeys.add("MONTHLY_TOTAL");
                    if (!hasSvc)      missingKeys.add("BREAKDOWN_SVC");
                    if (!hasRgn)      missingKeys.add("BREAKDOWN_RGN");
                    if (!hasSvcRgn)   missingKeys.add("SVC_REGION");
                    if (!hasDaily)    missingKeys.add("DAILY_COST");
                    if (!hasSvcUsage) missingKeys.add("SVC_USAGE");
                    if (!hasCfUsage)  missingKeys.add("CF_USAGE");
                    partial.put(ymStr, missingKeys);
                    grandSaved++;   // has at least TOTAL — counts as attempted
                }
            }

            String acctStatus;
            if (missing.isEmpty() && partial.isEmpty())          acctStatus = "COMPLETE";
            else if (missing.isEmpty())                          acctStatus = "PARTIAL_KEYS";
            else if (complete.isEmpty() && partial.isEmpty())    acctStatus = "NOT_STARTED";
            else                                                 acctStatus = "INCOMPLETE";

            Map<String, Object> acct = new LinkedHashMap<>();
            acct.put("accountId",      accountId);
            acct.put("accountName",    account.getAccountName());
            acct.put("expectedMonths", months.size());
            acct.put("completeMonths", complete.size());
            acct.put("partialMonths",  partial.size());
            acct.put("missingMonths",  missing.size());
            acct.put("status",         acctStatus);
            acct.put("complete",       complete);
            if (!partial.isEmpty()) acct.put("partial", partial);
            acct.put("missing",        missing);
            accountStatuses.add(acct);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("snapshotRange",    SNAPSHOT_START.toString() + " → " + lastMonth.toString());
        report.put("keyTypesPerMonth", 7);
        report.put("accountsChecked",  accounts.size());
        report.put("totalMonthSlots",  grandTotal);
        report.put("savedSlots",       grandSaved);
        report.put("missingSlots",     grandMissing);
        report.put("overallStatus",    grandMissing == 0 ? "COMPLETE" : "INCOMPLETE");
        report.put("accounts",         accountStatuses);
        return report;
    }

    // -------------------------------------------------------------------------
    // Read helpers — used by CostService to serve closed-month data
    // -------------------------------------------------------------------------

    public Optional<Double> readMonthlyTotal(String accountId, YearMonth ym) {
        return readFromDb(keyMonthlyTotal(accountId, ym), Double.class);
    }

    public Optional<List<CostDto>> readBreakdownSvc(String accountId, YearMonth ym) {
        return readListFromDb(keyMonthlyBreakdownSvc(accountId, ym), new TypeReference<List<CostDto>>() {});
    }

    public Optional<List<CostDto>> readBreakdownRgn(String accountId, YearMonth ym) {
        return readListFromDb(keyMonthlyBreakdownRgn(accountId, ym), new TypeReference<List<CostDto>>() {});
    }

    public Optional<List<DetailedCostDto>> readSvcRegion(String accountId, YearMonth ym) {
        return readListFromDb(keyMonthlySvcRegion(accountId, ym), new TypeReference<List<DetailedCostDto>>() {});
    }

    public Optional<List<DailyCostEntry>> readDailyCost(String accountId, YearMonth ym) {
        return readListFromDb(keyDailyCost(accountId, ym), new TypeReference<List<DailyCostEntry>>() {});
    }

    public Optional<List<SvcUsageEntry>> readSvcUsage(String accountId, YearMonth ym) {
        return readListFromDb(keySvcUsage(accountId, ym), new TypeReference<List<SvcUsageEntry>>() {});
    }

    public Optional<List<CfUsageEntry>> readCfUsage(String accountId, YearMonth ym) {
        return readListFromDb(keyCfUsage(accountId, ym), new TypeReference<List<CfUsageEntry>>() {});
    }

    // -------------------------------------------------------------------------
    // CE API fetch helpers
    // -------------------------------------------------------------------------

    private double fetchMonthlyTotal(CostExplorerClient ce, LocalDate start, LocalDate end) {
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost")
                .build();

        return ce.getCostAndUsage(request).resultsByTime().stream()
                .map(r -> r.total().get("UnblendedCost").amount())
                .mapToDouble(Double::parseDouble)
                .sum();
    }

    private List<CostDto> fetchBreakdown(CostExplorerClient ce, LocalDate start, LocalDate end,
                                         String dimension) {
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost")
                .groupBy(GroupDefinition.builder()
                        .type(GroupDefinitionType.DIMENSION)
                        .key(dimension)
                        .build())
                .build();

        Map<String, Double> costMap = new HashMap<>();
        ce.getCostAndUsage(request).resultsByTime().forEach(tp ->
                tp.groups().forEach(g -> {
                    String name = g.keys().get(0);
                    if (name == null || name.isBlank()) name = "Unknown";
                    double cost = Double.parseDouble(g.metrics().get("UnblendedCost").amount());
                    costMap.merge(name, cost, (a, b) -> a + b);
                }));

        return costMap.entrySet().stream()
                .map(e -> new CostDto(e.getKey(), e.getValue()))
                .filter(c -> c.getAmount() > 0.001)
                .sorted((a, b) -> Double.compare(b.getAmount(), a.getAmount()))
                .collect(Collectors.toList());
    }

    private List<DetailedCostDto> fetchSvcRegionBreakdown(CostExplorerClient ce,
                                                           LocalDate start, LocalDate end) {
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost")
                .groupBy(
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build(),
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build())
                .build();

        return ce.getCostAndUsage(request).resultsByTime().stream()
                .flatMap(tp -> tp.groups().stream())
                .map(g -> new DetailedCostDto(
                        g.keys().get(0),
                        g.keys().get(1),
                        Double.parseDouble(g.metrics().get("UnblendedCost").amount())))
                .filter(d -> d.getCost() > 0.001)
                .sorted((a, b) -> Double.compare(b.getCost(), a.getCost()))
                .collect(Collectors.toList());
    }

    private List<DailyCostEntry> fetchDailyCost(CostExplorerClient ce, LocalDate start, LocalDate end) {
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.DAILY)
                .metrics("UnblendedCost")
                .build();

        return ce.getCostAndUsage(request).resultsByTime().stream()
                .map(r -> new DailyCostEntry(
                        r.timePeriod().start(),
                        Double.parseDouble(r.total().get("UnblendedCost").amount())))
                .collect(Collectors.toList());
    }

    private List<CfUsageEntry> fetchCfUsageBreakdown(CostExplorerClient ce, LocalDate start, LocalDate end) {
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metrics("UsageQuantity", "BlendedCost")
                .filter(Expression.builder()
                        .dimensions(DimensionValues.builder()
                                .key("SERVICE")
                                .values("Amazon CloudFront")
                                .build())
                        .build())
                .groupBy(
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build(),
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build())
                .build();

        return ce.getCostAndUsage(request).resultsByTime().stream()
                .flatMap(tp -> tp.groups().stream())
                .map(g -> new CfUsageEntry(
                        g.keys().get(0),  // region
                        g.keys().get(1),  // usageType
                        Double.parseDouble(g.metrics().get("UsageQuantity").amount()),
                        g.metrics().get("UsageQuantity").unit(),
                        Double.parseDouble(g.metrics().get("BlendedCost").amount())))
                .filter(e -> e.getCost() >= 0)
                .sorted((a, b) -> Double.compare(b.getCost(), a.getCost()))
                .collect(Collectors.toList());
    }

    private List<SvcUsageEntry> fetchSvcUsageBreakdown(CostExplorerClient ce, LocalDate start, LocalDate end) {
        GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                .timePeriod(DateInterval.builder().start(start.toString()).end(end.toString()).build())
                .granularity(Granularity.MONTHLY)
                .metrics("UnblendedCost", "UsageQuantity")
                .groupBy(
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("SERVICE").build(),
                        GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build())
                .build();

        return ce.getCostAndUsage(request).resultsByTime().stream()
                .flatMap(tp -> tp.groups().stream())
                .map(g -> {
                    MetricValue usageMetric = g.metrics().get("UsageQuantity");
                    double quantity = usageMetric != null ? Double.parseDouble(usageMetric.amount()) : 0.0;
                    String unit = usageMetric != null ? usageMetric.unit() : "";
                    return new SvcUsageEntry(
                            g.keys().get(0),  // service
                            g.keys().get(1),  // usageType
                            Double.parseDouble(g.metrics().get("UnblendedCost").amount()),
                            quantity,
                            unit);
                })
                .filter(e -> e.getCost() > 0.001)
                .sorted((a, b) -> Double.compare(b.getCost(), a.getCost()))
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // DB persistence helpers
    // -------------------------------------------------------------------------

    private void persist(String key, Object value) {
        try {
            CachedData row = cachedDataRepository.findById(key).orElse(new CachedData());
            row.setCacheKey(key);
            row.setJsonData(objectMapper.writeValueAsString(value));
            row.setLastUpdated(LocalDateTime.now());
            cachedDataRepository.save(row);
        } catch (Exception e) {
            logger.warn("[Snapshot] Failed to persist {}: {}", key, e.getMessage());
        }
    }

    private <T> Optional<T> readFromDb(String key, Class<T> type) {
        try {
            return cachedDataRepository.findById(key)
                    .map(row -> {
                        try {
                            return objectMapper.readValue(row.getJsonData(), type);
                        } catch (Exception e) {
                            logger.warn("[Snapshot] Deserialize failed for {}: {}", key, e.getMessage());
                            return null;
                        }
                    });
        } catch (Exception e) {
            logger.warn("[Snapshot] DB read failed for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private <T> Optional<T> readListFromDb(String key, TypeReference<T> typeRef) {
        try {
            return cachedDataRepository.findById(key)
                    .map(row -> {
                        try {
                            return objectMapper.<T>readValue(row.getJsonData(), typeRef);
                        } catch (Exception e) {
                            logger.warn("[Snapshot] Deserialize failed for {}: {}", key, e.getMessage());
                            return null;
                        }
                    });
        } catch (Exception e) {
            logger.warn("[Snapshot] DB read failed for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Inner DTOs
    // -------------------------------------------------------------------------

    public static class DailyCostEntry {
        private String date;
        private double amount;

        public DailyCostEntry() {}

        public DailyCostEntry(String date, double amount) {
            this.date = date;
            this.amount = amount;
        }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getAmount() { return amount; }
        public void setAmount(double amount) { this.amount = amount; }
    }

    public static class SvcUsageEntry {
        private String service;
        private String usageType;
        private double cost;
        private double quantity;
        private String unit;

        public SvcUsageEntry() {}

        public SvcUsageEntry(String service, String usageType, double cost) {
            this.service = service;
            this.usageType = usageType;
            this.cost = cost;
        }

        public SvcUsageEntry(String service, String usageType, double cost, double quantity, String unit) {
            this.service = service;
            this.usageType = usageType;
            this.cost = cost;
            this.quantity = quantity;
            this.unit = unit;
        }

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getUsageType() { return usageType; }
        public void setUsageType(String usageType) { this.usageType = usageType; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
        public double getQuantity() { return quantity; }
        public void setQuantity(double quantity) { this.quantity = quantity; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
    }

    public static class CfUsageEntry {
        private String region;
        private String usageType;
        private double quantity;
        private String unit;
        private double cost;

        public CfUsageEntry() {}

        public CfUsageEntry(String region, String usageType, double quantity, String unit, double cost) {
            this.region = region;
            this.usageType = usageType;
            this.quantity = quantity;
            this.unit = unit;
            this.cost = cost;
        }

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getUsageType() { return usageType; }
        public void setUsageType(String usageType) { this.usageType = usageType; }
        public double getQuantity() { return quantity; }
        public void setQuantity(double quantity) { this.quantity = quantity; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
    }
}
