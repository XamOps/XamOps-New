package com.xammer.cloud.service.marketplace;

import com.xammer.cloud.domain.BillingHistorySnapshot;
import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.repository.BillingHistorySnapshotRepository;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import com.xammer.cloud.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarketplaceBillingExtractionService {

    // Fixed date range: March 2025 – March 2026 (temp: within 14-month CE limit)
    // TODO: change EXTRACTION_START back to "2024-12-01" after enabling
    // "Multi-year data" in Cost Explorer on the payer account.
    static final String EXTRACTION_START = "2025-03-01";
    static final String EXTRACTION_END = "2026-04-01"; // exclusive — Cost Explorer end date

    /** Per-month S3 key: billing-archive/{accountId}/YYYY-MM/billing.csv */
    static String monthlyS3Key(String accountId, int year, int month) {
        return String.format("billing-archive/%s/%04d-%02d/billing.csv", accountId, year, month);
    }

    static String monthlyS3Key(String accountId, String yearMonth) {
        return String.format("billing-archive/%s/%s/billing.csv", accountId, yearMonth);
    }

    private final StsClient stsClient;
    private final MarketplacePayerAccountRepository payerAccountRepository;
    private final MarketplaceAccountRepository marketplaceAccountRepository;
    private final BillingHistorySnapshotRepository snapshotRepository;
    private final FileStorageService fileStorageService;

    public MarketplaceBillingExtractionService(StsClient stsClient,
            MarketplacePayerAccountRepository payerAccountRepository,
            MarketplaceAccountRepository marketplaceAccountRepository,
            BillingHistorySnapshotRepository snapshotRepository,
            FileStorageService fileStorageService) {
        this.stsClient = stsClient;
        this.payerAccountRepository = payerAccountRepository;
        this.marketplaceAccountRepository = marketplaceAccountRepository;
        this.snapshotRepository = snapshotRepository;
        this.fileStorageService = fileStorageService;
    }

    /**
     * Extract billing data for ALL marketplace accounts under the given payer.
     * Pulls USAGE_TYPE-level data from Cost Explorer, saves per-month CSVs to S3
     * and rows to DB.
     */
    public Map<String, String> extractAll(Long payerAccountId) {
        MarketplacePayerAccount payerAccount = payerAccountRepository.findById(payerAccountId)
                .orElseThrow(() -> new RuntimeException("Payer account not found: " + payerAccountId));

        List<MarketplaceAccount> allAccounts = marketplaceAccountRepository
                .findByPayerAccount(payerAccount);

        if (allAccounts.isEmpty()) {
            log.warn("No marketplace accounts found for payer {}. Run discovery first.",
                    payerAccount.getPayerAwsAccountId());
            return Collections.emptyMap();
        }

        log.info("Starting billing extraction for {} marketplace accounts ({} – {})",
                allAccounts.size(), EXTRACTION_START, EXTRACTION_END);

        // Assume the payer role once — it can read costs for all linked accounts
        StaticCredentialsProvider tempCreds = assumeRole(
                payerAccount.getRoleArn(), payerAccount.getExternalId());

        List<String> accountIds = allAccounts.stream()
                .map(MarketplaceAccount::getAwsAccountId)
                .collect(Collectors.toList());

        // Pull all data in one Cost Explorer call (GroupBy: LINKED_ACCOUNT +
        // USAGE_TYPE)
        Map<String, List<CostRow>> dataByAccount = fetchFromCostExplorer(tempCreds, accountIds);

        // Supplementary queries to resolve TimedStorage-ByteHrs ambiguity (S3 vs EFS vs
        // ECR).
        // CE groups S3, EFS, and ECR storage under the same usage-type key, so we use
        // separate SERVICE-filtered queries to learn how much belongs to each service.
        // CE service name for ECR is "Amazon EC2 Container Registry (ECR)" — the
        // "(ECR)"
        // suffix is required. Other variants are kept as fallbacks for accounts
        // where CE may return an older or alternate name.
        List<String> ecrServiceNames = List.of("Amazon EC2 Container Registry (ECR)",
                "Amazon EC2 Container Registry",
                "Amazon Elastic Container Registry",
                "Amazon Elastic Container Registry (ECR)");
        Map<String, Map<String, Map<String, Double>>> efsServiceCosts = fetchServiceRegionalCosts(tempCreds, accountIds,
                List.of("Amazon Elastic File System"));
        Map<String, Map<String, Map<String, Double>>> ecrServiceCosts = fetchServiceRegionalCosts(tempCreds, accountIds,
                ecrServiceNames);
        // Usage-quantity queries: actual GB-months per TimedStorage usage-type key,
        // broken out by service. Used to split quantities correctly in
        // resolveTimedStorage.
        Map<String, Map<String, Map<String, Double>>> efsUsageQty = fetchServiceUsageQuantities(tempCreds, accountIds,
                List.of("Amazon Elastic File System"));
        Map<String, Map<String, Map<String, Double>>> ecrUsageQty = fetchServiceUsageQuantities(tempCreds, accountIds,
                ecrServiceNames);

        double markupPercent = payerAccount.getMarkupPercent();

        // Process each account: save to DB + upload per-month CSVs to S3
        Map<String, String> results = new LinkedHashMap<>();
        for (MarketplaceAccount account : allAccounts) {
            String accountId = account.getAwsAccountId();
            List<CostRow> rows = dataByAccount.getOrDefault(accountId, Collections.emptyList());

            if (rows.isEmpty()) {
                log.warn("No cost data returned for account {} ({})", accountId, account.getAccountName());
                results.put(accountId, "NO_DATA");
                continue;
            }

            try {
                // Split TimedStorage rows into S3/EFS/ECR before markup
                List<CostRow> resolvedRows = resolveTimedStorage(rows,
                        efsServiceCosts.getOrDefault(accountId, Collections.emptyMap()),
                        ecrServiceCosts.getOrDefault(accountId, Collections.emptyMap()),
                        efsUsageQty.getOrDefault(accountId, Collections.emptyMap()),
                        ecrUsageQty.getOrDefault(accountId, Collections.emptyMap()));

                // Apply markup so CSV totals match the true customer bill
                List<CostRow> markedUpRows = applyMarkup(resolvedRows, markupPercent);

                saveToDb(accountId, account.getAccountName(), markedUpRows);
                List<String> keys = uploadMonthlyCSVsToS3(accountId, account.getAccountName(), markedUpRows);

                account.setBillingExtracted(true);
                marketplaceAccountRepository.save(account);

                log.info("✅ Extracted {} rows ({} months) for account {}",
                        rows.size(), keys.size(), accountId);
                results.put(accountId, "SUCCESS:" + keys.size() + " months uploaded");

            } catch (Exception e) {
                log.error("❌ Failed to extract billing for account {}: {}", accountId, e.getMessage());
                results.put(accountId, "ERROR:" + e.getMessage());
            }
        }

        log.info("Extraction complete. Results: {}", results);
        return results;
    }

    /**
     * Extract billing for a single account (used by on-demand share flow).
     * Uploads one CSV per month: billing-archive/{accountId}/YYYY-MM/billing.csv
     * Returns list of S3 keys uploaded.
     */
    public List<String> extractForAccount(String awsAccountId, MarketplacePayerAccount payerAccount) {
        StaticCredentialsProvider tempCreds = assumeRole(
                payerAccount.getRoleArn(), payerAccount.getExternalId());

        Map<String, List<CostRow>> data = fetchFromCostExplorer(tempCreds, List.of(awsAccountId));
        List<CostRow> rows = data.getOrDefault(awsAccountId, Collections.emptyList());

        String accountName = marketplaceAccountRepository.findByAwsAccountId(awsAccountId)
                .map(MarketplaceAccount::getAccountName)
                .orElse(awsAccountId);

        if (rows.isEmpty()) {
            log.warn("No cost data for account {} in range {} to {}", awsAccountId,
                    EXTRACTION_START, EXTRACTION_END);
            return Collections.emptyList();
        }

        // Supplementary queries to resolve TimedStorage-ByteHrs ambiguity (S3 vs EFS vs
        // ECR)
        // CE service name for ECR is "Amazon EC2 Container Registry (ECR)" — the
        // "(ECR)"
        // suffix is required. Other variants are kept as fallbacks for accounts
        // where CE may return an older or alternate name.
        List<String> ecrServiceNames = List.of("Amazon EC2 Container Registry (ECR)",
                "Amazon EC2 Container Registry",
                "Amazon Elastic Container Registry",
                "Amazon Elastic Container Registry (ECR)");
        Map<String, Map<String, Map<String, Double>>> efsServiceCosts = fetchServiceRegionalCosts(tempCreds,
                List.of(awsAccountId),
                List.of("Amazon Elastic File System"));
        Map<String, Map<String, Map<String, Double>>> ecrServiceCosts = fetchServiceRegionalCosts(tempCreds,
                List.of(awsAccountId),
                ecrServiceNames);
        // Actual usage quantities per usage-type key, broken out by service
        Map<String, Map<String, Map<String, Double>>> efsUsageQty = fetchServiceUsageQuantities(tempCreds,
                List.of(awsAccountId),
                List.of("Amazon Elastic File System"));
        Map<String, Map<String, Map<String, Double>>> ecrUsageQty = fetchServiceUsageQuantities(tempCreds,
                List.of(awsAccountId),
                ecrServiceNames);

        List<CostRow> resolvedRows = resolveTimedStorage(rows,
                efsServiceCosts.getOrDefault(awsAccountId, Collections.emptyMap()),
                ecrServiceCosts.getOrDefault(awsAccountId, Collections.emptyMap()),
                efsUsageQty.getOrDefault(awsAccountId, Collections.emptyMap()),
                ecrUsageQty.getOrDefault(awsAccountId, Collections.emptyMap()));

        List<CostRow> markedUpRows = applyMarkup(resolvedRows, payerAccount.getMarkupPercent());

        saveToDb(awsAccountId, accountName, markedUpRows);
        return uploadMonthlyCSVsToS3(awsAccountId, accountName, markedUpRows);
    }

    /**
     * Returns distinct year-month strings (e.g. ["2025-02", "2025-03"]) for an
     * account.
     */
    public List<String> getAvailableMonths(String accountId) {
        return snapshotRepository.findByAwsAccountIdOrderByYearAscMonthAsc(accountId)
                .stream()
                .map(s -> String.format("%04d-%02d", s.getYear(), s.getMonth()))
                .distinct()
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private Map<String, List<CostRow>> fetchFromCostExplorer(StaticCredentialsProvider creds,
            List<String> accountIds) {
        Map<String, List<CostRow>> result = new HashMap<>();

        try (CostExplorerClient ceClient = CostExplorerClient.builder()
                .region(Region.US_EAST_1) // Cost Explorer only in us-east-1
                .credentialsProvider(creds)
                .build()) {

            String nextPageToken = null;
            int pageCount = 0;

            do {
                GetCostAndUsageRequest.Builder reqBuilder = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder()
                                .start(EXTRACTION_START)
                                .end(EXTRACTION_END)
                                .build())
                        .granularity(Granularity.MONTHLY)
                        // Fetch cost, amortized cost (for RIs), and usage quantity
                        .metrics("UnblendedCost", "AmortizedCost", "UsageQuantity")
                        // GroupBy: LINKED_ACCOUNT + USAGE_TYPE (max 2 dimensions allowed)
                        .groupBy(
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("LINKED_ACCOUNT")
                                        .build(),
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("USAGE_TYPE")
                                        .build())
                        .filter(Expression.builder()
                                .dimensions(DimensionValues.builder()
                                        .key(Dimension.LINKED_ACCOUNT)
                                        .values(accountIds)
                                        .build())
                                .build());

                if (nextPageToken != null) {
                    reqBuilder.nextPageToken(nextPageToken);
                }

                GetCostAndUsageResponse response = ceClient.getCostAndUsage(reqBuilder.build());
                pageCount++;

                for (ResultByTime rbt : response.resultsByTime()) {
                    String monthStart = rbt.timePeriod().start(); // e.g. "2025-02-01"
                    int year = Integer.parseInt(monthStart.substring(0, 4));
                    int month = Integer.parseInt(monthStart.substring(5, 7));

                    for (Group group : rbt.groups()) {
                        String accountId = group.keys().get(0);
                        String usageType = group.keys().get(1);

                        // Use AmortizedCost if available (for RIs), otherwise UnblendedCost
                        double cost = 0.0;
                        if (group.metrics().containsKey("AmortizedCost")) {
                            cost = Double.parseDouble(group.metrics().get("AmortizedCost").amount());
                        }
                        if (cost == 0.0 && group.metrics().containsKey("UnblendedCost")) {
                            cost = Double.parseDouble(group.metrics().get("UnblendedCost").amount());
                        }

                        double quantity = 0.0;
                        if (group.metrics().containsKey("UsageQuantity")) {
                            quantity = Double.parseDouble(group.metrics().get("UsageQuantity").amount());
                        }

                        if (cost < 0.0001)
                            continue; // skip zero-cost rows

                        result.computeIfAbsent(accountId, k -> new ArrayList<>())
                                .add(new CostRow(year, month, usageType, quantity, cost));
                    }
                }

                nextPageToken = response.nextPageToken();

            } while (nextPageToken != null);

            log.info("Cost Explorer returned data across {} page(s) for {} accounts",
                    pageCount, accountIds.size());
        }

        return result;
    }

    /**
     * Fetches per-account, per-month, per-usageType usage quantities for specific
     * AWS services. Used to obtain the exact EFS and ECR storage quantities for
     * each TimedStorage-ByteHrs usage type key, so that quantity splitting in
     * resolveTimedStorage uses real values rather than a cost-proportional
     * approximation.
     *
     * @param serviceNames CE service dimension values, e.g. "Amazon Elastic File
     *                     System"
     * @return accountId → yearMonth ("YYYY-MM") → usageType (e.g.
     *         "APS3-TimedStorage-ByteHrs") → UsageQuantity
     */
    private Map<String, Map<String, Map<String, Double>>> fetchServiceUsageQuantities(
            StaticCredentialsProvider creds,
            List<String> accountIds,
            List<String> serviceNames) {

        Map<String, Map<String, Map<String, Double>>> result = new HashMap<>();

        try (CostExplorerClient ceClient = CostExplorerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            String nextToken = null;
            do {
                GetCostAndUsageRequest.Builder req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder()
                                .start(EXTRACTION_START)
                                .end(EXTRACTION_END)
                                .build())
                        .granularity(Granularity.MONTHLY)
                        .metrics("UsageQuantity")
                        .groupBy(
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("LINKED_ACCOUNT")
                                        .build(),
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("USAGE_TYPE")
                                        .build())
                        .filter(Expression.builder()
                                .and(List.of(
                                        Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                        .key(Dimension.LINKED_ACCOUNT)
                                                        .values(accountIds)
                                                        .build())
                                                .build(),
                                        Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                        .key(Dimension.SERVICE)
                                                        .values(serviceNames)
                                                        .build())
                                                .build()))
                                .build());

                if (nextToken != null)
                    req.nextPageToken(nextToken);

                GetCostAndUsageResponse resp = ceClient.getCostAndUsage(req.build());

                for (ResultByTime rbt : resp.resultsByTime()) {
                    String yearMonth = rbt.timePeriod().start().substring(0, 7);

                    for (Group group : rbt.groups()) {
                        String accountId = group.keys().get(0);
                        String usageType = group.keys().get(1);
                        double qty = Double.parseDouble(
                                group.metrics().get("UsageQuantity").amount());

                        if (qty <= 0)
                            continue;

                        result.computeIfAbsent(accountId, k -> new HashMap<>())
                                .computeIfAbsent(yearMonth, k -> new HashMap<>())
                                .merge(usageType, qty, Double::sum);
                    }
                }

                nextToken = resp.nextPageToken();
            } while (nextToken != null);

            log.info("SERVICE usage-quantity CE query for {} returned data for {} accounts",
                    serviceNames, result.size());
        }

        return result;
    }

    /**
     * Fetches per-account, per-month, per-region costs for specific AWS services.
     * Used to resolve the TimedStorage-ByteHrs ambiguity: CE aggregates S3, EFS,
     * and ECR
     * storage under the same usage-type key, so we need a SERVICE-filtered
     * side-query to
     * learn how much each service actually cost.
     *
     * @param serviceNames CE service dimension values, e.g. "Amazon Elastic File
     *                     System"
     * @return accountId → yearMonth ("YYYY-MM") → regionPrefix ("APS3") →
     *         UnblendedCost
     */
    private Map<String, Map<String, Map<String, Double>>> fetchServiceRegionalCosts(
            StaticCredentialsProvider creds,
            List<String> accountIds,
            List<String> serviceNames) {

        Map<String, Map<String, Map<String, Double>>> result = new HashMap<>();

        try (CostExplorerClient ceClient = CostExplorerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            String nextToken = null;
            do {
                GetCostAndUsageRequest.Builder req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder()
                                .start(EXTRACTION_START)
                                .end(EXTRACTION_END)
                                .build())
                        .granularity(Granularity.MONTHLY)
                        .metrics("UnblendedCost")
                        .groupBy(
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("LINKED_ACCOUNT")
                                        .build(),
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("REGION")
                                        .build())
                        // Filter: LINKED_ACCOUNT IN accountIds AND SERVICE IN serviceNames
                        .filter(Expression.builder()
                                .and(List.of(
                                        Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                        .key(Dimension.LINKED_ACCOUNT)
                                                        .values(accountIds)
                                                        .build())
                                                .build(),
                                        Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                        .key(Dimension.SERVICE)
                                                        .values(serviceNames)
                                                        .build())
                                                .build()))
                                .build());

                if (nextToken != null)
                    req.nextPageToken(nextToken);

                GetCostAndUsageResponse resp = ceClient.getCostAndUsage(req.build());

                for (ResultByTime rbt : resp.resultsByTime()) {
                    String yearMonth = rbt.timePeriod().start().substring(0, 7); // "YYYY-MM"

                    for (Group group : rbt.groups()) {
                        String accountId = group.keys().get(0);
                        String ceRegion = group.keys().get(1); // e.g. "ap-south-1"
                        double cost = Double.parseDouble(
                                group.metrics().get("UnblendedCost").amount());

                        if (cost < 0.0001)
                            continue;

                        // Map "ap-south-1" → "APS3" so we can match against usage-type prefixes
                        String prefix = CE_REGION_TO_PREFIX.getOrDefault(
                                ceRegion, ceRegion.toUpperCase().replace("-", ""));

                        result.computeIfAbsent(accountId, k -> new HashMap<>())
                                .computeIfAbsent(yearMonth, k -> new HashMap<>())
                                .merge(prefix, cost, Double::sum);
                    }
                }

                nextToken = resp.nextPageToken();
            } while (nextToken != null);

            log.info("SERVICE-level CE query for {} returned data for {} accounts (total regions found: {})",
                    serviceNames, result.size(),
                    result.values().stream()
                            .flatMap(m -> m.values().stream())
                            .mapToLong(Map::size).sum());
        }

        return result;
    }

    /**
     * Apply a fixed markup percentage to every row's cost.
     * E.g. markupPercent=5.3 multiplies each row by 1.053.
     * Returns rows unchanged if markupPercent is 0 or negative.
     */
    private List<CostRow> applyMarkup(List<CostRow> rows, double markupPercent) {
        if (markupPercent <= 0)
            return rows;
        double multiplier = 1.0 + markupPercent / 100.0;
        log.info("Applying {}% markup (×{}) to {} rows",
                markupPercent, String.format("%.4f", multiplier), rows.size());
        return rows.stream()
                .map(r -> new CostRow(r.year, r.month, r.usageType,
                        r.usageQuantity, r.costUsd * multiplier))
                .collect(Collectors.toList());
    }

    /**
     * Splits every {@code *-TimedStorage-ByteHrs} row into per-service rows using
     * supplementary SERVICE-level CE cost data for EFS and ECR.
     *
     * <p>
     * CE groups S3, EFS Standard Storage, and ECR image storage under the same
     * {@code [PREFIX]-TimedStorage-ByteHrs} usage-type key. Without a SERVICE
     * dimension
     * in the main query we cannot tell them apart. Here we:
     * <ol>
     * <li>Determine how much EFS cost for the region/month is <em>not</em> already
     * captured by other EFS usage types (ProvisionedTP, ETDataAccess, WarmStorage).
     * That remainder is EFS Standard Storage, which was billed as
     * TimedStorage.</li>
     * <li>Take the full ECR regional cost — ECR has no other usage types.</li>
     * <li>Subtract both from the original TimedStorage cost to get the S3
     * portion.</li>
     * <li>Replace the single aggregated row with up to three synthetic rows whose
     * usage-type prefixes are recognised by {@link #extractService}:
     * {@code [PREFIX]-TimedStorage-ByteHrs} → S3
     * {@code [PREFIX]-AmazonEFS-TimedStorage-ByteHrs} → Amazon Elastic File System
     * {@code [PREFIX]-ECR-TimedStorage-ByteHrs} → Amazon Elastic Container Registry
     * </li>
     * </ol>
     *
     * <p>
     * Usage quantities are derived from actual service-specific CE queries
     * ({@code efsUsageQty} and {@code ecrUsageQty}) rather than a cost-proportional
     * approximation. This is critical because EFS ($0.33/GB) and ECR ($0.10/GB)
     * price per GB very differently from S3 (~$0.023/GB), so splitting quantities
     * by cost would give drastically wrong numbers.
     *
     * @param rows        raw rows from Cost Explorer (pre-markup)
     * @param efsRegional yearMonth → regionPrefix → total EFS UnblendedCost
     *                    (pre-markup)
     * @param ecrRegional yearMonth → regionPrefix → total ECR UnblendedCost
     *                    (pre-markup)
     * @param efsUsageQty yearMonth → usageType → EFS-only UsageQuantity from
     *                    SERVICE-filtered CE query
     * @param ecrUsageQty yearMonth → usageType → ECR-only UsageQuantity from
     *                    SERVICE-filtered CE query
     */
    private List<CostRow> resolveTimedStorage(
            List<CostRow> rows,
            Map<String, Map<String, Double>> efsRegional,
            Map<String, Map<String, Double>> ecrRegional,
            Map<String, Map<String, Double>> efsUsageQty,
            Map<String, Map<String, Double>> ecrUsageQty) {

        if (efsRegional.isEmpty() && ecrRegional.isEmpty()) {
            log.debug("No EFS or ECR regional data available, skipping TimedStorage resolution");
            return rows;
        }

        // Pre-compute known non-TimedStorage EFS costs already in our rows
        // (ProvisionedTP, ETDataAccess, WarmStorage) grouped by yearMonth →
        // regionPrefix → cost.
        // The SERVICE query returns the TOTAL EFS cost; subtracting these gives EFS
        // TimedStorage.
        Map<String, Map<String, Double>> knownEfsCosts = new HashMap<>();
        for (CostRow r : rows) {
            String suffix = stripRegionPrefix(r.usageType);
            if (!suffix.startsWith("ProvisionedTP")
                    && !suffix.startsWith("ETDataAccess")
                    && !suffix.startsWith("WarmStorage")
                    && !suffix.startsWith("AmazonEFS")) {
                continue;
            }
            Matcher m = REGION_STRIP_PATTERN.matcher(r.usageType);
            String prefix = m.matches() ? m.group(1) : "";
            String ym = String.format("%04d-%02d", r.year, r.month);
            knownEfsCosts.computeIfAbsent(ym, k -> new HashMap<>())
                    .merge(prefix, r.costUsd, Double::sum);
        }

        List<CostRow> result = new ArrayList<>(rows.size());
        int timedStorageCount = 0;
        double totalTimedStorageCost = 0.0;
        double totalEfsAllocated = 0.0;
        double totalEcrAllocated = 0.0;

        for (CostRow row : rows) {
            String suffix = stripRegionPrefix(row.usageType);

            // Only split standard TimedStorage rows (skip RabbitMQ-specific ones)
            if (!suffix.startsWith("TimedStorage") || suffix.startsWith("TimedStorage-RabbitMQ")) {
                result.add(row);
                continue;
            }

            timedStorageCount++;
            totalTimedStorageCost += row.costUsd;

            Matcher m = REGION_STRIP_PATTERN.matcher(row.usageType);
            String regionPrefix = m.matches() ? m.group(1) : "";
            String yearMonth = String.format("%04d-%02d", row.year, row.month);

            // EFS TimedStorage (Standard Storage) = EFS total − already-captured EFS items
            double efsTotal = efsRegional.getOrDefault(yearMonth, Collections.emptyMap())
                    .getOrDefault(regionPrefix, 0.0);
            double efsKnown = knownEfsCosts.getOrDefault(yearMonth, Collections.emptyMap())
                    .getOrDefault(regionPrefix, 0.0);
            double efsStorage = Math.max(0.0, efsTotal - efsKnown);

            // ECR TimedStorage = full ECR regional cost (ECR has no other usage types)
            double ecrStorage = ecrRegional.getOrDefault(yearMonth, Collections.emptyMap())
                    .getOrDefault(regionPrefix, 0.0);

            double s3Cost = Math.max(0.0, row.costUsd - efsStorage - ecrStorage);

            // Log warning if we have TimedStorage but no EFS/ECR data to split it
            if (row.costUsd > 0.01 && efsTotal == 0 && ecrStorage == 0) {
                log.warn("TimedStorage row {} (${}) has no matching EFS or ECR data; assuming 100% S3",
                        row.usageType, String.format("%.2f", row.costUsd));
            }

            // Use actual usage quantities from SERVICE-filtered CE queries.
            // Proportional-by-cost split is wrong because EFS ($0.33/GB) and ECR ($0.10/GB)
            // price very differently from S3 (~$0.023/GB), causing large quantity errors.
            double efsQty = efsUsageQty.getOrDefault(yearMonth, Collections.emptyMap())
                    .getOrDefault(row.usageType, 0.0);
            double ecrQty = ecrUsageQty.getOrDefault(yearMonth, Collections.emptyMap())
                    .getOrDefault(row.usageType, 0.0);
            double s3Qty = Math.max(0.0, row.usageQuantity - efsQty - ecrQty);

            // S3 portion — keep original usage type so region extraction still works
            if (s3Cost > 0.0001) {
                result.add(new CostRow(row.year, row.month, row.usageType, s3Qty, s3Cost));
            }

            // EFS Standard Storage — synthetic usage type starting with "AmazonEFS"
            // so extractService() correctly maps it to Amazon Elastic File System
            if (efsStorage > 0.0001) {
                totalEfsAllocated += efsStorage;
                String efsKey = regionPrefix.isEmpty()
                        ? "AmazonEFS-TimedStorage-ByteHrs"
                        : regionPrefix + "-AmazonEFS-TimedStorage-ByteHrs";
                result.add(new CostRow(row.year, row.month, efsKey, efsQty, efsStorage));
            }

            // ECR storage — synthetic usage type starting with "ECR-"
            // so extractService() correctly maps it to Amazon Elastic Container Registry
            if (ecrStorage > 0.0001) {
                totalEcrAllocated += ecrStorage;
                String ecrKey = regionPrefix.isEmpty()
                        ? "ECR-TimedStorage-ByteHrs"
                        : regionPrefix + "-ECR-TimedStorage-ByteHrs";
                result.add(new CostRow(row.year, row.month, ecrKey, ecrQty, ecrStorage));
            }
        }

        log.info("resolveTimedStorage: {} input rows → {} output rows. " +
                "TimedStorage total: ${:.2f}, allocated to EFS: ${:.2f}, ECR: ${:.2f}, S3: ${:.2f}",
                rows.size(), result.size(),
                totalTimedStorageCost, totalEfsAllocated, totalEcrAllocated,
                totalTimedStorageCost - totalEfsAllocated - totalEcrAllocated);
        return result;
    }

    /** Save rows to billing_history_snapshot table (delete existing first) */
    private void saveToDb(String accountId, String accountName, List<CostRow> rows) {
        snapshotRepository.deleteByAwsAccountId(accountId);

        List<BillingHistorySnapshot> snapshots = rows.stream()
                .map(row -> new BillingHistorySnapshot(
                        accountId, accountName,
                        row.year, row.month,
                        row.usageType,
                        BigDecimal.valueOf(row.costUsd),
                        row.usageQuantity))
                .collect(Collectors.toList());

        snapshotRepository.saveAll(snapshots);
        log.info("Saved {} snapshot rows to DB for account {}", snapshots.size(), accountId);
    }

    /**
     * Group rows by month, build one CSV per month, upload each to S3.
     * Returns list of uploaded S3 keys.
     */
    private List<String> uploadMonthlyCSVsToS3(String accountId, String accountName, List<CostRow> rows) {
        // Group by "YYYY-MM"
        Map<String, List<CostRow>> byMonth = rows.stream()
                .collect(Collectors.groupingBy(r -> String.format("%04d-%02d", r.year, r.month)));

        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, List<CostRow>> entry : byMonth.entrySet()) {
            String yearMonth = entry.getKey();
            String csvContent = buildCsv(accountId, accountName, entry.getValue());
            byte[] csvBytes = csvContent.getBytes(StandardCharsets.UTF_8);
            String s3Key = monthlyS3Key(accountId, yearMonth);
            fileStorageService.uploadFileBytes(s3Key, csvBytes, "text/csv");
            keys.add(s3Key);
            log.info("Uploaded {} rows for {}/{} → S3: {}",
                    entry.getValue().size(), accountId, yearMonth, s3Key);
        }
        return keys;
    }

    // ----------------------------------------------------------------
    // Region code → human-readable name (Cost Explorer prefix format)
    // ----------------------------------------------------------------
    private static final Map<String, String> REGION_PREFIX_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("USE1", "US East (N. Virginia)");
        m.put("USE2", "US East (Ohio)");
        m.put("USW1", "US West (N. California)");
        m.put("USW2", "US West (Oregon)");
        m.put("CAC1", "Canada (Central)");
        m.put("CAN1", "Canada (Calgary)");
        m.put("EUW1", "Europe (Ireland)");
        m.put("EUW2", "Europe (London)");
        m.put("EUW3", "Europe (Paris)");
        m.put("EUC1", "Europe (Frankfurt)");
        m.put("EUS1", "Europe (Milan)");
        m.put("EUN1", "Europe (Stockholm)");
        m.put("EUS2", "Europe (Zurich)");
        m.put("EUS3", "Europe (Spain)");
        m.put("APS1", "Asia Pacific (Singapore)");
        m.put("APS2", "Asia Pacific (Sydney)");
        m.put("APS3", "Asia Pacific (Mumbai)");
        m.put("APS4", "Asia Pacific (Seoul)");
        m.put("APS5", "Asia Pacific (Hyderabad)");
        m.put("APE1", "Asia Pacific (Tokyo)");
        m.put("APN1", "Asia Pacific (Tokyo)");
        m.put("APN2", "Asia Pacific (Seoul)");
        m.put("APN3", "Asia Pacific (Osaka)");
        m.put("APE2", "Asia Pacific (Osaka)");
        m.put("SAE1", "South America (São Paulo)");
        m.put("MES1", "Middle East (UAE)");
        m.put("MES2", "Middle East (Bahrain)");
        m.put("AFR1", "Africa (Cape Town)");
        m.put("ILL1", "Israel (Tel Aviv)");
        m.put("MXC1", "Mexico (Central)");
        REGION_PREFIX_MAP = Collections.unmodifiableMap(m);
    }

    // ----------------------------------------------------------------
    // Global prefix (CloudFront) → actual region mapping
    // ----------------------------------------------------------------
    private static final Map<String, String> GLOBAL_PREFIX_TO_REGION;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("IN-", "Asia Pacific (Mumbai)");
        m.put("JP-", "Asia Pacific (Tokyo)");
        m.put("ME-", "Middle East (UAE)");
        m.put("EU-", "Europe (Ireland)");
        m.put("US-", "US East (N. Virginia)");
        m.put("AP-", "Asia Pacific (Singapore)");
        m.put("CA-", "Canada (Central)");
        m.put("SA-", "South America (São Paulo)");
        m.put("AU-", "Asia Pacific (Sydney)");
        m.put("ZA-", "Africa (Cape Town)");
        m.put("BR-", "South America (São Paulo)");
        m.put("AR-", "South America (São Paulo)");
        m.put("GB-", "Europe (London)");
        GLOBAL_PREFIX_TO_REGION = Collections.unmodifiableMap(m);
    }

    // ----------------------------------------------------------------
    // UsageType prefix → AWS service name
    // Matches AWS PDF billing layout: NatGateway and EBS are grouped under EC2
    // ----------------------------------------------------------------
    private static final Map<String, String> SERVICE_PREFIX_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();

        // VPC (PublicIPv4 and VpcEndpoints only — NatGateway belongs under EC2 per PDF)
        m.put("VpcEndpoint", "Amazon Virtual Private Cloud");
        m.put("PublicIPv4", "Amazon Virtual Private Cloud");
        m.put("TransitGateway", "Amazon Virtual Private Cloud");
        m.put("VPN-", "Amazon Virtual Private Cloud");
        m.put("ClientVPN", "Amazon Virtual Private Cloud");
        m.put("BilledBytes", "Amazon Virtual Private Cloud");

        // EC2: instances, NAT gateways, and EBS — all grouped under EC2 in the AWS PDF
        m.put("BoxUsage", "Amazon Elastic Compute Cloud");
        m.put("SpotUsage", "Amazon Elastic Compute Cloud");
        m.put("DedicatedUsage", "Amazon Elastic Compute Cloud");
        m.put("HostBoxUsage", "Amazon Elastic Compute Cloud");
        m.put("EBSOptimized", "Amazon Elastic Compute Cloud");
        m.put("CPUCredits", "Amazon Elastic Compute Cloud");
        m.put("NatGateway", "Amazon Elastic Compute Cloud");
        m.put("RegionalNatGateway", "Amazon Elastic Compute Cloud");
        m.put("EBS:", "Amazon Elastic Compute Cloud");
        m.put("EBS-", "Amazon Elastic Compute Cloud");

        // Lightsail (FIX #2: ensure snapshot mapping works)
        m.put("SnapshotUsage", "Amazon Lightsail");
        m.put("AmazonLightsail", "Amazon Lightsail");
        m.put("Bundle:", "Amazon Lightsail");
        m.put("BundleUsage", "Amazon Lightsail");

        // ELB
        m.put("LoadBalancer", "Elastic Load Balancing");
        m.put("LCU-", "Elastic Load Balancing");
        m.put("LCUUsage", "Elastic Load Balancing");

        // CloudWatch (Logs will remain separate for detail, but documented)
        m.put("CloudWatch:", "Amazon CloudWatch");
        m.put("CW:", "Amazon CloudWatch");
        m.put("DashboardsUsageHour", "Amazon CloudWatch");
        m.put("Logs-", "Amazon CloudWatch Logs");
        m.put("VendedLog", "Amazon CloudWatch Logs");
        m.put("DataProcessing", "Amazon CloudWatch Logs");

        // Data Transfer
        m.put("DataTransfer", "AWS Data Transfer");
        m.put("Bandwidth", "AWS Data Transfer");
        m.put("AWS-Out-Bytes", "AWS Data Transfer");
        m.put("AWS-In-Bytes", "AWS Data Transfer");
        m.put("CloudFront-", "AWS CloudFront");

        // SQS-specific request patterns must come BEFORE the generic "Requests-" S3
        // entry
        // because CE uses "Requests-Tier1" for both S3 and SQS, but FIFO and RBP are
        // SQS-only
        m.put("Requests-FIFO-", "Amazon Simple Queue Service");
        m.put("Requests-RBP", "Amazon Simple Queue Service");

        // S3
        m.put("Requests-", "Amazon Simple Storage Service");
        m.put("TimedStorage-RabbitMQ", "Amazon MQ");
        m.put("TimedStorage", "Amazon Simple Storage Service");
        m.put("IATimedStorage", "Amazon Simple Storage Service");
        m.put("StorageLens", "Amazon Simple Storage Service");
        m.put("Tables-", "Amazon Simple Storage Service");
        m.put("S3-", "Amazon Simple Storage Service");
        m.put("Glacier-", "Amazon S3 Glacier");
        m.put("AmazonGlacier", "Amazon S3 Glacier");
        m.put("Monitoring-Automation", "Amazon Simple Storage Service");

        // RDS
        m.put("RDS:", "Amazon Relational Database Service");
        m.put("RDS-", "Amazon Relational Database Service");
        m.put("Aurora:", "Amazon Relational Database Service");
        m.put("InstanceUsage", "Amazon Relational Database Service");
        m.put("PaidRDSvCPUMonitored", "Amazon Relational Database Service");
        m.put("StorageIOUsage", "Amazon Relational Database Service");
        m.put("StorageUsage", "Amazon Relational Database Service");
        m.put("Multi-AZUsage", "Amazon Relational Database Service");

        // EC2 Reserved Instances are reported as HeavyUsage:<type>
        // RDS HeavyUsage includes db.<...> in the usageType suffix.
        m.put("HeavyUsage", "Amazon Elastic Compute Cloud"); // Will be overridden in extractService for db.*

        // Other
        m.put("ElastiCache:", "Amazon ElastiCache");
        m.put("NodeUsage:", "Amazon ElastiCache");
        m.put("RabbitMQ-", "Amazon MQ");
        m.put("AmazonMQ", "Amazon MQ");
        m.put("AmazonEKS", "Amazon Elastic Kubernetes Service");
        m.put("AmazonECS", "Amazon Elastic Container Service");
        m.put("ECR-", "Amazon Elastic Container Registry");
        m.put("Fargate-", "AWS Fargate");
        m.put("AmazonEMR", "Amazon EMR");
        m.put("EMR-", "Amazon EMR");
        m.put("AWSBatch-", "AWS Batch");
        m.put("Lambda-", "AWS Lambda");
        m.put("Lambda:", "AWS Lambda");
        m.put("Request-ARM", "AWS Lambda");
        m.put("Request-", "AWS Lambda");
        m.put("ApiGateway", "Amazon API Gateway");
        m.put("APIRequest", "AWS Cost Explorer");
        m.put("APS-", "Amazon Managed Service for Prometheus");
        m.put("SNS-", "Amazon Simple Notification Service");
        m.put("DeliveryAttempts", "Amazon Simple Notification Service");
        m.put("OutboundSMS", "Amazon Pinpoint");
        m.put("SQS-", "Amazon Simple Queue Service");
        m.put("SES-", "Amazon Simple Email Service");
        m.put("Recipients", "Amazon Simple Email Service");
        m.put("DynamoDB:", "Amazon DynamoDB");
        m.put("DDB-", "Amazon DynamoDB");
        m.put("ReadRequestUnits", "Amazon DynamoDB");
        m.put("WriteRequestUnits", "Amazon DynamoDB");
        m.put("AmazonRoute53", "Amazon Route 53");
        m.put("DNS-", "Amazon Route 53");
        m.put("HostedZone", "Amazon Route 53");
        m.put("DirectConnect-", "AWS Direct Connect");
        m.put("KMS-", "AWS Key Management Service");
        m.put("AWSSecretsManager", "AWS Secrets Manager");
        m.put("SecretsManager", "AWS Secrets Manager");
        m.put("RequestV2", "AWS WAF");
        m.put("RuleV2", "AWS WAF");
        m.put("WebACLV2", "AWS WAF");
        m.put("WAF-", "AWS WAF");
        m.put("Shield", "AWS Shield");
        m.put("PaidComplianceCheck", "AWS Security Hub");
        m.put("SecurityHubProduct", "AWS Security Hub");
        m.put("OtherProduct", "AWS Security Hub");
        m.put("GuardDuty-", "Amazon GuardDuty");
        m.put("Config-", "AWS Config");
        m.put("ConfigurationItemRecorded", "AWS Config");
        m.put("ConformancePackEvaluations", "AWS Config");
        m.put("PaidDataInventoryEvaluation", "Amazon Macie");
        m.put("CognitoUserPools", "Amazon Cognito");
        m.put("CognitoIdentityPool", "Amazon Cognito");
        m.put("Inspector2-", "Amazon Inspector");
        m.put("AmazonInspector", "Amazon Inspector");
        m.put("ACM-", "AWS Certificate Manager");
        m.put("CloudTrail-", "AWS CloudTrail");
        m.put("DataEventsRecorded", "AWS CloudTrail");
        m.put("InsightsEvents", "AWS CloudTrail");
        m.put("PaidEventsAnalyzed", "AWS CloudTrail");
        m.put("PaidS3DataEventsAnalyzed", "AWS CloudTrail");
        m.put("SSM-", "AWS Systems Manager");
        m.put("JustInTimeAccess", "AWS Systems Manager");
        m.put("Glue-", "AWS Glue");
        m.put("Athena-", "Amazon Athena");
        m.put("DataScannedInTB", "Amazon Athena");
        m.put("SageMaker-", "Amazon SageMaker");
        m.put("Comprehend-", "Amazon Comprehend");
        m.put("Textract-", "Amazon Textract");
        m.put("Rekognition", "Amazon Rekognition");
        m.put("Polly-", "Amazon Polly");
        m.put("Transcribe", "Amazon Transcribe");
        m.put("Translate-", "Amazon Translate");
        m.put("Lex-", "Amazon Lex");
        m.put("Forecast-", "Amazon Forecast");
        m.put("Personalize-", "Amazon Personalize");
        m.put("Redshift:", "Amazon Redshift");
        m.put("OpenSearch", "Amazon OpenSearch Service");
        m.put("ES:", "Amazon OpenSearch Service");
        m.put("MSK:", "Amazon Managed Streaming for Apache Kafka");
        m.put("Kinesis-", "Amazon Kinesis");
        m.put("KDF-", "Amazon Kinesis Data Firehose");
        m.put("CodeBuild-", "AWS CodeBuild");
        m.put("BuildDuration", "AWS CodeBuild");
        m.put("CP-", "AWS CodePipeline");
        m.put("AWSStepFunctions-", "AWS Step Functions");
        m.put("AppSync-", "AWS AppSync");
        m.put("Std-MicrosoftAD", "AWS Directory Service");
        m.put("Enterprise-MicrosoftAD", "AWS Directory Service");
        m.put("SmallBusiness-MicrosoftAD", "AWS Directory Service");
        m.put("Event-", "Amazon EventBridge");
        m.put("ArchivedEvents", "Amazon EventBridge");
        m.put("HostingComputeRequest", "AWS Amplify");
        m.put("DataStorage", "AWS Amplify");
        m.put("AWSIoT-", "AWS IoT Core");
        m.put("AmazonConnect-", "Amazon Connect");
        m.put("WorkSpaces-", "Amazon WorkSpaces");
        m.put("DataSync-", "AWS DataSync");
        m.put("AWSTransfer-", "AWS Transfer Family");
        m.put("AmazonEFS", "Amazon Elastic File System");
        m.put("ProvisionedTP", "Amazon Elastic File System");
        m.put("WarmStorage-ByteHrs-EFS", "AWS Backup");
        m.put("WarmStorage", "Amazon Elastic File System");
        m.put("ETDataAccess", "Amazon Elastic File System");
        m.put("AttachmentsSize", "Amazon WorkMail");
        m.put("TagStorage", "AWS Resource Groups Tagging API");
        m.put("Monitoring", "Amazon DevOps Guru");
        m.put("Llama4", "Amazon Bedrock");
        m.put("Claude", "Amazon Bedrock");
        m.put("Titan", "Amazon Bedrock");
        m.put("Mistral", "Amazon Bedrock");
        m.put("Bedrock", "Amazon Bedrock");
        m.put("MP:", "Amazon Bedrock");
        m.put("Nova", "Amazon Bedrock");
        m.put("Cohere", "Amazon Bedrock");
        m.put("BackupUsage", "AWS Backup");
        m.put("ComputeSP", "AWS Compute Savings Plans");
        SERVICE_PREFIX_MAP = Collections.unmodifiableMap(m);
    }

    // ----------------------------------------------------------------
    // KMS lowercase region format: ap-south-1-KMS-Requests
    // ----------------------------------------------------------------
    private static final Map<String, String> KMS_REGION_MAP;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ap-south-1", "Asia Pacific (Mumbai)");
        m.put("ap-south-2", "Asia Pacific (Hyderabad)");
        m.put("ap-southeast-1", "Asia Pacific (Singapore)");
        m.put("ap-southeast-2", "Asia Pacific (Sydney)");
        m.put("ap-southeast-3", "Asia Pacific (Jakarta)");
        m.put("ap-northeast-1", "Asia Pacific (Tokyo)");
        m.put("ap-northeast-2", "Asia Pacific (Seoul)");
        m.put("ap-northeast-3", "Asia Pacific (Osaka)");
        m.put("us-east-1", "US East (N. Virginia)");
        m.put("us-east-2", "US East (Ohio)");
        m.put("us-west-1", "US West (N. California)");
        m.put("us-west-2", "US West (Oregon)");
        m.put("eu-west-1", "Europe (Ireland)");
        m.put("eu-west-2", "Europe (London)");
        m.put("eu-west-3", "Europe (Paris)");
        m.put("eu-central-1", "Europe (Frankfurt)");
        m.put("eu-north-1", "Europe (Stockholm)");
        m.put("eu-south-1", "Europe (Milan)");
        m.put("eu-south-2", "Europe (Spain)");
        m.put("eu-central-2", "Europe (Zurich)");
        m.put("ca-central-1", "Canada (Central)");
        m.put("ca-west-1", "Canada (Calgary)");
        m.put("sa-east-1", "South America (São Paulo)");
        m.put("me-south-1", "Middle East (Bahrain)");
        m.put("me-central-1", "Middle East (UAE)");
        m.put("af-south-1", "Africa (Cape Town)");
        m.put("il-central-1", "Israel (Tel Aviv)");
        KMS_REGION_MAP = Collections.unmodifiableMap(m);
    }

    // ----------------------------------------------------------------
    // CE REGION dimension value (lowercase, e.g. "ap-south-1") → usage-type prefix
    // ("APS3").
    // Built at class load from KMS_REGION_MAP + REGION_PREFIX_MAP via shared
    // human-readable name.
    // ----------------------------------------------------------------
    private static final Map<String, String> CE_REGION_TO_PREFIX;
    static {
        // Reverse REGION_PREFIX_MAP: human-readable → prefix (first occurrence wins)
        Map<String, String> humanToPrefix = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : REGION_PREFIX_MAP.entrySet()) {
            humanToPrefix.putIfAbsent(e.getValue(), e.getKey());
        }
        Map<String, String> m = new HashMap<>();
        for (Map.Entry<String, String> e : KMS_REGION_MAP.entrySet()) {
            String prefix = humanToPrefix.get(e.getValue());
            if (prefix != null) {
                m.put(e.getKey(), prefix);
            }
        }
        CE_REGION_TO_PREFIX = Collections.unmodifiableMap(m);
    }

    // Matches CE uppercase region prefix: APS3, USE1, EUW2, etc.
    private static final Pattern REGION_STRIP_PATTERN = Pattern.compile("^([A-Z]{2,4}\\d)[-:](.*)$");

    // Matches global 2-letter/3-letter prefixes without a digit: IN-, EU-, US-,
    // AP-, JP-, ME-, etc.
    private static final Pattern GLOBAL_PREFIX_PATTERN = Pattern.compile("^([A-Z]{2,3})-.*");

    /**
     * Strips the CE region prefix (e.g. "APS3") or "Global-" prefix, returns the
     * suffix.
     */
    private static String stripRegionPrefix(String usageType) {
        // Handle Global-prefixed WAF usage types: Global-RuleV2, Global-WebACLV2
        if (usageType.startsWith("Global-")) {
            return usageType.substring(7);
        }
        Matcher m = REGION_STRIP_PATTERN.matcher(usageType);
        return m.matches() ? m.group(2) : usageType;
    }

    /**
     * Returns the human-readable AWS region name from a usage type. (FIXED for
     * global prefixes)
     */
    private static String extractRegion(String usageType) {
        // Global-prefixed WAF usage types
        if (usageType.startsWith("Global-")) {
            return "Global";
        }

        // KMS lowercase format: ap-south-1-KMS-Requests, us-east-1-KMS-Keys
        for (Map.Entry<String, String> entry : KMS_REGION_MAP.entrySet()) {
            if (usageType.startsWith(entry.getKey() + "-")) {
                return entry.getValue();
            }
        }

        // CE uppercase prefix: APS3-BoxUsage:t3.micro
        Matcher m = REGION_STRIP_PATTERN.matcher(usageType);
        if (m.matches()) {
            return REGION_PREFIX_MAP.getOrDefault(m.group(1), m.group(1));
        }

        // === FIX #5: Map global prefixes to actual regions ===
        Matcher globalMatcher = GLOBAL_PREFIX_PATTERN.matcher(usageType);
        if (globalMatcher.matches()) {
            String prefix = globalMatcher.group(1) + "-";
            return GLOBAL_PREFIX_TO_REGION.getOrDefault(prefix, "Global");
        }

        return "Global";
    }

    /** Derives an AWS service name from a usage type string. */
    private static String extractService(String usageType) {
        // KMS lowercase format: ap-south-1-KMS-Requests, ap-south-2-KMS-Keys
        if (usageType.contains("-KMS-") || usageType.contains("-KMS$")) {
            return "AWS Key Management Service";
        }

        String suffix = stripRegionPrefix(usageType);

        // Cross-region data transfer: APS3-APS1-AWS-Out-Bytes
        // After stripping source region (APS3), suffix is APS1-AWS-Out-Bytes
        if (suffix.contains("-AWS-Out-Bytes") || suffix.contains("-AWS-In-Bytes")) {
            return "AWS Data Transfer";
        }

        // EC2 Reserved Instances are reported as HeavyUsage:<instanceType>
        // RDS HeavyUsage entries include db.<...> in the usageType suffix.
        if (suffix.startsWith("HeavyUsage")) {
            if (suffix.contains("db.")) {
                return "Amazon Relational Database Service";
            }
            return "Amazon Elastic Compute Cloud";
        }

        for (Map.Entry<String, String> entry : SERVICE_PREFIX_MAP.entrySet()) {
            if (suffix.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Global 2-letter/3-letter prefix (CloudFront/global data transfer): IN-*,
        // JP-*, ME-*, etc.
        // Checked AFTER SERVICE_PREFIX_MAP so that EU-AWSSecretsManager-*,
        // EU-Bedrock-*, etc.
        // are matched by their own SERVICE_PREFIX_MAP entries first.
        if (GLOBAL_PREFIX_PATTERN.matcher(usageType).matches()) {
            return "AWS CloudFront";
        }

        // Fallback: first token before ':' or '-'
        String[] parts = suffix.split("[-:]", 2);
        return parts[0].isEmpty() ? usageType : parts[0];
    }

    /**
     * Builds a hierarchical CSV matching the AWS Bill PDF layout:
     *
     * Service,Region,Usage Type,Usage Quantity,Cost (USD)
     * Amazon Elastic Compute Cloud,,,,796.60
     * ,Asia Pacific (Hyderabad),,,394.54
     * ,,APS5-NatGateway-Hours,1346.0000,75.38
     * Grand Total,,,,1809.86
     */
    private String buildCsv(String accountId, String accountName, List<CostRow> rows) {
        StringBuilder sb = new StringBuilder();

        // Meta header
        sb.append("Account ID:,").append(escapecsv(accountId)).append('\n');
        sb.append("Account Name:,").append(escapecsv(accountName)).append('\n');
        sb.append('\n');

        // Column header
        sb.append("Service,Region,Usage Type,Usage Quantity,Cost (USD)\n");

        // Group: service → region → rows (sorted within each group by usage type)
        Map<String, Map<String, List<CostRow>>> byServiceRegion = new TreeMap<>();
        for (CostRow row : rows) {
            String service = extractService(row.usageType);
            String region = extractRegion(row.usageType);
            byServiceRegion
                    .computeIfAbsent(service, s -> new TreeMap<>())
                    .computeIfAbsent(region, r -> new ArrayList<>())
                    .add(row);
        }

        double grandTotal = 0.0;

        for (Map.Entry<String, Map<String, List<CostRow>>> svcEntry : byServiceRegion.entrySet()) {
            String serviceName = svcEntry.getKey();
            double serviceTotal = svcEntry.getValue().values().stream()
                    .flatMap(Collection::stream)
                    .mapToDouble(r -> r.costUsd)
                    .sum();

            // Service row
            sb.append(escapecsv(serviceName)).append(",,,,")
                    .append(String.format("%.2f", serviceTotal)).append('\n');

            for (Map.Entry<String, List<CostRow>> regEntry : svcEntry.getValue().entrySet()) {
                String regionName = regEntry.getKey();
                List<CostRow> regionRows = regEntry.getValue();
                double regionTotal = regionRows.stream().mapToDouble(r -> r.costUsd).sum();

                // Region row
                sb.append(',').append(escapecsv(regionName)).append(",,,")
                        .append(String.format("%.2f", regionTotal)).append('\n');

                // Detail rows — sort by usage type
                regionRows.stream()
                        .sorted(Comparator.comparing(r -> r.usageType))
                        .forEach(row -> {
                            sb.append(",,");
                            sb.append(escapecsv(row.usageType)).append(',');
                            sb.append(String.format("%.4f", row.usageQuantity)).append(',');
                            sb.append(String.format("%.6f", row.costUsd)).append('\n');
                        });
            }

            grandTotal += serviceTotal;
        }

        // Grand total row
        sb.append('\n');
        sb.append("Grand Total,,,,").append(String.format("%.2f", grandTotal)).append('\n');

        return sb.toString();
    }

    private String escapecsv(String value) {
        if (value == null)
            return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private StaticCredentialsProvider assumeRole(String roleArn, String externalId) {
        AssumeRoleResponse response = stsClient.assumeRole(r -> r
                .roleArn(roleArn)
                .roleSessionName("xamops-billing-extraction")
                .externalId(externalId)
                .durationSeconds(3600));
        Credentials c = response.credentials();
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(c.accessKeyId(), c.secretAccessKey(), c.sessionToken()));
    }

    /** Simple value object for a single cost row */
    static class CostRow {
        final int year;
        final int month;
        final String usageType;
        final double usageQuantity;
        final double costUsd;

        CostRow(int year, int month, String usageType, double usageQuantity, double costUsd) {
            this.year = year;
            this.month = month;
            this.usageType = usageType != null ? usageType : "";
            this.usageQuantity = usageQuantity;
            this.costUsd = costUsd;
        }
    }
}