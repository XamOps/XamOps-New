package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.MarketplaceMeterBilling;
import com.xammer.cloud.domain.MarketplaceProductSubscription;
import com.xammer.cloud.repository.MarketplaceMeterBillingRepository;
import com.xammer.cloud.repository.MarketplaceProductSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.marketplaceagreement.MarketplaceAgreementClient;
import software.amazon.awssdk.services.marketplaceagreement.model.AcceptedTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.AgreementViewSummary;
import software.amazon.awssdk.services.marketplaceagreement.model.ByolPricingTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.ConfigurableUpfrontPricingTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.DescribeAgreementRequest;
import software.amazon.awssdk.services.marketplaceagreement.model.DescribeAgreementResponse;
import software.amazon.awssdk.services.marketplaceagreement.model.FixedUpfrontPricingTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.FreeTrialPricingTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.GetAgreementTermsRequest;
import software.amazon.awssdk.services.marketplaceagreement.model.GetAgreementTermsResponse;
import software.amazon.awssdk.services.marketplaceagreement.model.PaymentScheduleTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.ProposalSummary;
import software.amazon.awssdk.services.marketplaceagreement.model.RecurringPaymentTerm;
import software.amazon.awssdk.services.marketplaceagreement.model.Resource;
import software.amazon.awssdk.services.marketplaceagreement.model.SearchAgreementsRequest;
import software.amazon.awssdk.services.marketplaceagreement.model.SearchAgreementsResponse;
import software.amazon.awssdk.services.marketplaceagreement.model.UsageBasedPricingTerm;
import software.amazon.awssdk.services.marketplacecatalog.MarketplaceCatalogClient;
import software.amazon.awssdk.services.marketplacecatalog.model.Change;
import software.amazon.awssdk.services.marketplacecatalog.model.DescribeEntityRequest;
import software.amazon.awssdk.services.marketplacecatalog.model.DescribeEntityResponse;
import software.amazon.awssdk.services.marketplacecatalog.model.Entity;
import software.amazon.awssdk.services.marketplacecatalog.model.EntitySummary;
import software.amazon.awssdk.services.marketplacecatalog.model.Filter;
import software.amazon.awssdk.services.marketplacecatalog.model.ListEntitiesRequest;
import software.amazon.awssdk.services.marketplacecatalog.model.ListEntitiesResponse;
import software.amazon.awssdk.services.marketplacecatalog.model.StartChangeSetRequest;
import software.amazon.awssdk.services.marketplacecatalog.model.StartChangeSetResponse;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MarketOps service: pulls AWS Marketplace subscription data + meter billing
 * from Cost Explorer (BILLING_ENTITY = "AWS Marketplace").
 *
 * Each distinct usage-type prefix seen in billing is treated as one product
 * subscription. No separate Entitlement API SDK is needed.
 */
@Service
@Slf4j
public class MarketOpsService {

    private static final int BILLING_LOOKBACK_MONTHS = 12;
    // Marketplace Agreement API (SearchAgreements) requires "AWSMarketplace"
    private static final String AGREEMENT_CATALOG = "AWSMarketplace";
    // Marketplace Catalog API (DescribeEntity) requires "AWSMarketplace"
    private static final String CATALOG_CATALOG = "AWSMarketplace";
    private static final Pattern REGION_PREFIX_PATTERN = Pattern.compile("^[a-z]{2,4}\\d[-:_]");

    private final StsClient stsClient;
    private final MarketplaceProductSubscriptionRepository subscriptionRepository;
    private final MarketplaceMeterBillingRepository meterBillingRepository;

    public MarketOpsService(StsClient stsClient,
                             MarketplaceProductSubscriptionRepository subscriptionRepository,
                             MarketplaceMeterBillingRepository meterBillingRepository) {
        this.stsClient = stsClient;
        this.subscriptionRepository = subscriptionRepository;
        this.meterBillingRepository = meterBillingRepository;
    }

    /**
     * Returns subscriptions for the given cloud account.
     * Triggers an initial sync from Cost Explorer if no cached data exists.
     */
    public List<MarketplaceProductSubscription> getSubscriptions(CloudAccount account) {
        String accountId = account.getAwsAccountId();
        List<MarketplaceProductSubscription> existing =
                subscriptionRepository.findByCloudAccountId(accountId);
        if (existing.isEmpty()) {
            log.info("No cached subscriptions for account {}. Triggering initial sync.", accountId);
            syncAll(account, false);
            return subscriptionRepository.findByCloudAccountId(accountId);
        }
        return existing;
    }

    /**
     * Queries AWS Cost Explorer with DAILY granularity for a specific product and month.
     * Uses the usage-types stored from the last monthly sync to match billing rows to the product,
     * so that agreement-based product codes (e.g. "prod-xxx") are handled correctly.
     *
     * Returns one entry per calendar day (including zero-cost days) sorted by date ascending.
     */
    public List<Map<String, Object>> getDailyMeterBilling(CloudAccount account, String productCode, String yearMonth) {
        String accountId = account.getAwsAccountId();
        log.info("Fetching daily billing: account={} product={} month={}", accountId, productCode, yearMonth);

        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalStateException("No IAM role configured for account " + accountId);
        }

        // Resolve usage-types linked to this productCode from stored monthly data.
        // This works for both billing-derived slugs and agreement-based product codes.
        Set<String> knownUsageTypes = meterBillingRepository
                .findByCloudAccountIdAndProductCode(accountId, productCode)
                .stream()
                .map(MarketplaceMeterBilling::getUsageType)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        StaticCredentialsProvider creds = assumeRole(account.getRoleArn(), account.getExternalId());

        YearMonth ym = YearMonth.parse(yearMonth);
        String startDate = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String endDate   = ym.plusMonths(1).atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        Map<String, BigDecimal> costByDate     = new LinkedHashMap<>();
        Map<String, Double>     quantityByDate = new LinkedHashMap<>();

        try (CostExplorerClient ce = CostExplorerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate).end(endDate).build())
                    .granularity(Granularity.DAILY)
                    .metrics("UnblendedCost", "UsageQuantity")
                    .groupBy(GroupDefinition.builder()
                            .type(GroupDefinitionType.DIMENSION)
                            .key("USAGE_TYPE")
                            .build())
                    .filter(Expression.builder()
                            .and(
                                    Expression.builder()
                                            .dimensions(DimensionValues.builder()
                                                    .key(Dimension.LINKED_ACCOUNT)
                                                    .values(accountId)
                                                    .build())
                                            .build(),
                                    Expression.builder()
                                            .dimensions(DimensionValues.builder()
                                                    .key(Dimension.BILLING_ENTITY)
                                                    .values("AWS Marketplace")
                                                    .build())
                                            .build())
                            .build())
                    .build();

            GetCostAndUsageResponse response = ce.getCostAndUsage(req);

            for (ResultByTime rbt : response.resultsByTime()) {
                String date = rbt.timePeriod().start(); // "2026-03-01"

                for (Group group : rbt.groups()) {
                    String usageType = group.keys().isEmpty() ? "" : group.keys().get(0);

                    // Prefer exact usage-type match (works for agreement product codes).
                    // Fall back to extractProductCode slug matching when no stored types exist.
                    boolean matches = !knownUsageTypes.isEmpty()
                            ? knownUsageTypes.contains(usageType)
                            : productCode.equals(extractProductCode(usageType));

                    if (!matches) continue;

                    double cost = group.metrics().containsKey("UnblendedCost")
                            ? Double.parseDouble(group.metrics().get("UnblendedCost").amount()) : 0.0;
                    double qty  = group.metrics().containsKey("UsageQuantity")
                            ? Double.parseDouble(group.metrics().get("UsageQuantity").amount()) : 0.0;

                    costByDate.merge(date, BigDecimal.valueOf(cost), BigDecimal::add);
                    quantityByDate.merge(date, qty, Double::sum);
                }
            }

        } catch (Exception e) {
            log.error("Daily CE query failed: account={} product={} month={}: {}",
                    accountId, productCode, yearMonth, e.getMessage(), e);
            throw new IllegalStateException(
                    "Failed to query daily billing for " + productCode + ": " + e.getMessage(), e);
        }

        // One entry per calendar day (zero-cost days included so the chart is continuous)
        List<Map<String, Object>> result = new ArrayList<>();
        java.time.LocalDate day    = ym.atDay(1);
        java.time.LocalDate endDay = ym.plusMonths(1).atDay(1);
        while (day.isBefore(endDay)) {
            String d = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date",          d);
            entry.put("costUsd",       costByDate.getOrDefault(d, BigDecimal.ZERO));
            entry.put("usageQuantity", quantityByDate.getOrDefault(d, 0.0));
            result.add(entry);
            day = day.plusDays(1);
        }

        log.info("Daily billing result: {} days in month, {} with non-zero cost — account={} product={} month={}",
                result.size(), costByDate.size(), accountId, productCode, yearMonth);
        return result;
    }

    /**
     * Queries AWS Cost Explorer with DAILY granularity for the entire account
     * (no product filter) for the given calendar month.
     *
     * Used as a fallback when the per-product daily query returns all-zero rows
     * because AWS CE groups all SaaS charges under a shared usage-type slug that
     * cannot be attributed to a specific product.
     *
     * Returns one entry per calendar day (zero-cost days included).
     */
    public List<Map<String, Object>> getDailyMeterBillingAccountLevel(CloudAccount account, String yearMonth) {
        String accountId = account.getAwsAccountId();
        log.info("Fetching account-level daily billing: account={} month={}", accountId, yearMonth);

        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalStateException("No IAM role configured for account " + accountId);
        }

        StaticCredentialsProvider creds = assumeRole(account.getRoleArn(), account.getExternalId());

        YearMonth ym = YearMonth.parse(yearMonth);
        String startDate = ym.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String endDate   = ym.plusMonths(1).atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        Map<String, BigDecimal> costByDate     = new LinkedHashMap<>();
        Map<String, Double>     quantityByDate = new LinkedHashMap<>();

        try (CostExplorerClient ce = CostExplorerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            // No groupBy — read totals directly from rbt.total() for each day
            GetCostAndUsageRequest req = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate).end(endDate).build())
                    .granularity(Granularity.DAILY)
                    .metrics("UnblendedCost", "UsageQuantity")
                    .filter(Expression.builder()
                            .and(
                                    Expression.builder()
                                            .dimensions(DimensionValues.builder()
                                                    .key(Dimension.LINKED_ACCOUNT)
                                                    .values(accountId)
                                                    .build())
                                            .build(),
                                    Expression.builder()
                                            .dimensions(DimensionValues.builder()
                                                    .key(Dimension.BILLING_ENTITY)
                                                    .values("AWS Marketplace")
                                                    .build())
                                            .build())
                            .build())
                    .build();

            GetCostAndUsageResponse response = ce.getCostAndUsage(req);

            for (ResultByTime rbt : response.resultsByTime()) {
                String date = rbt.timePeriod().start(); // "2026-03-01"

                if (rbt.total() != null && !rbt.total().isEmpty()) {
                    double cost = rbt.total().containsKey("UnblendedCost")
                            ? Double.parseDouble(rbt.total().get("UnblendedCost").amount()) : 0.0;
                    double qty  = rbt.total().containsKey("UsageQuantity")
                            ? Double.parseDouble(rbt.total().get("UsageQuantity").amount()) : 0.0;
                    costByDate.merge(date, BigDecimal.valueOf(cost), BigDecimal::add);
                    quantityByDate.merge(date, qty, Double::sum);
                }
            }

        } catch (Exception e) {
            log.error("Account-level daily CE query failed: account={} month={}: {}",
                    accountId, yearMonth, e.getMessage(), e);
            throw new IllegalStateException(
                    "Failed to query account-level daily billing: " + e.getMessage(), e);
        }

        // One entry per calendar day (zero-cost days included so chart is continuous)
        List<Map<String, Object>> result = new ArrayList<>();
        java.time.LocalDate day    = ym.atDay(1);
        java.time.LocalDate endDay = ym.plusMonths(1).atDay(1);
        while (day.isBefore(endDay)) {
            String d = day.format(DateTimeFormatter.ISO_LOCAL_DATE);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date",          d);
            entry.put("costUsd",       costByDate.getOrDefault(d, BigDecimal.ZERO));
            entry.put("usageQuantity", quantityByDate.getOrDefault(d, 0.0));
            result.add(entry);
            day = day.plusDays(1);
        }

        log.info("Account-level daily billing: {} days, {} with non-zero cost — account={} month={}",
                result.size(), costByDate.size(), accountId, yearMonth);
        return result;
    }

    /**
     * Returns monthly meter billing entries for a specific product, newest-first.
     */
    public List<MarketplaceMeterBilling> getMeterBilling(String cloudAccountId, String productCode) {
        return meterBillingRepository
                .findByCloudAccountIdAndProductCode(cloudAccountId, productCode)
                .stream()
                .sorted(Comparator.comparing(MarketplaceMeterBilling::getYearMonth).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Returns all meter billing entries for a given account (all products).
     */
    public List<MarketplaceMeterBilling> getMeterBillingAll(String cloudAccountId) {
        return meterBillingRepository.findByCloudAccountId(cloudAccountId);
    }

    /**
     * Full re-sync: queries Cost Explorer for AWS Marketplace costs, derives
     * subscriptions from distinct products found, persists both in DB.
     *
     * Priority: Agreement API (real product names) > billing-derived (usage-type slugs).
     *
     * @param force when {@code true} the 24-hour freshness cache is bypassed.
     *              Pass {@code true} for user-initiated syncs; {@code false}
     *              for automatic background syncs.
     */
    @Transactional
    public Map<String, Object> syncAll(CloudAccount account, boolean force) {
        String accountId = account.getAwsAccountId();
        log.info("Syncing MarketOps data for account {} (force={})", accountId, force);

        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalStateException("No IAM role configured for account " + accountId);
        }

        // Skip CE API call if data was synced within the last 24 hours (unless force=true)
        if (!force) {
            List<MarketplaceMeterBilling> existingBilling = meterBillingRepository.findByCloudAccountId(accountId);
            if (!existingBilling.isEmpty()) {
                LocalDateTime lastSync = existingBilling.stream()
                        .map(MarketplaceMeterBilling::getSyncedAt)
                        .filter(java.util.Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
                if (lastSync != null && lastSync.isAfter(LocalDateTime.now().minusHours(24))) {
                    log.info("Skipping MarketOps CE sync for {} — data is fresh (last sync: {})", accountId, lastSync);
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("status", "CACHED");
                    summary.put("cloudAccountId", accountId);
                    summary.put("subscriptionsFound", subscriptionRepository.findByCloudAccountId(accountId).size());
                    summary.put("billingMonthsSynced", existingBilling.stream()
                            .map(MarketplaceMeterBilling::getYearMonth).distinct().count());
                    summary.put("lastSyncedAt", lastSync.toString());
                    return summary;
                }
            }
        }

        StaticCredentialsProvider creds = assumeRole(account.getRoleArn(), account.getExternalId());

        // 1. Pull all AWS Marketplace billing from Cost Explorer
        List<MarketplaceMeterBilling> billingEntries = fetchMarketplaceBilling(accountId, creds);

        // 2. Try Agreement API first — these have real product names (e.g. "Uniskai by Profisea Labs")
        List<MarketplaceProductSubscription> agreementSubs = fetchAgreementSubscriptions(accountId, creds);

        List<MarketplaceProductSubscription> subscriptions;
        if (!agreementSubs.isEmpty()) {
            // Agreement API worked: use real product names as the canonical list.
            // Remap billing entries so their productCode matches the agreement product codes,
            // allowing totalCostUsd to be attributed correctly per product.
            remapBillingProductCodes(billingEntries, agreementSubs);
            subscriptions = agreementSubs;
            log.info("Using {} agreement subscription(s) as primary source for account {}",
                    agreementSubs.size(), accountId);
        } else {
            // Agreement API unavailable (likely missing IAM permissions for
            // aws-marketplace:SearchAgreements). Fall back to billing-derived names.
            log.warn("Agreement API returned no data for account {}. " +
                    "Falling back to billing-derived subscription names. " +
                    "To show real product names, add aws-marketplace:SearchAgreements, " +
                    "aws-marketplace:DescribeAgreement and aws-marketplace:DescribeEntity " +
                    "permissions to the assumed role.", accountId);
            subscriptions = deriveSubscriptions(accountId, billingEntries);
        }

        // 3. Replace existing data
        subscriptionRepository.deleteByCloudAccountId(accountId);
        subscriptionRepository.saveAll(subscriptions);

        meterBillingRepository.deleteByCloudAccountId(accountId);
        meterBillingRepository.saveAll(billingEntries);

        log.info("Synced {} subscription(s) and {} billing row(s) for account {}",
                subscriptions.size(), billingEntries.size(), accountId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "SYNCED");
        summary.put("cloudAccountId", accountId);
        summary.put("subscriptionsFound", subscriptions.size());
        summary.put("billingMonthsSynced", billingEntries.stream()
                .map(MarketplaceMeterBilling::getYearMonth).distinct().count());
        summary.put("syncedAt", LocalDateTime.now().toString());
        return summary;
    }

    // ------------------------------------------------------------------
    // Billing product-code remapping
    // ------------------------------------------------------------------

    /**
     * Updates each billing entry's {@code productCode} to match the real agreement
     * product code where we can determine the association.
     *
     * <p>AWS Cost Explorer groups Marketplace billing by usage-type dimension, not by
     * product. Multiple products can share the same usage-type category (e.g. all
     * Bedrock models emit {@code BedrockInputTokens}). This method uses heuristics:
     *
     * <ul>
     *   <li>If there is exactly one <em>non-Bedrock</em> agreement product and a billing
     *       entry's slug is {@code software-usage-contracts}, map it to that product.</li>
     *   <li>If there is exactly one Bedrock-type agreement product, map all Bedrock
     *       token billing to it.</li>
     *   <li>When multiple Bedrock products exist we cannot distinguish per-product costs
     *       from CE data alone, so we assign all token billing to the first Bedrock
     *       product (combined view). The other products will show $0.</li>
     * </ul>
     */
    private void remapBillingProductCodes(
            List<MarketplaceMeterBilling> billingEntries,
            List<MarketplaceProductSubscription> agreementSubs) {

        List<String> contractCodes = agreementSubs.stream()
                .filter(s -> !isBedrockProduct(s.getProductTitle()))
                .map(MarketplaceProductSubscription::getProductCode)
                .collect(Collectors.toList());

        List<String> bedrockCodes = agreementSubs.stream()
                .filter(s -> isBedrockProduct(s.getProductTitle()))
                .map(MarketplaceProductSubscription::getProductCode)
                .collect(Collectors.toList());

        // When multiple Bedrock products exist, assign all token billing to the first one
        String bedrockTarget = bedrockCodes.isEmpty() ? null : bedrockCodes.get(0);

        int remapped = 0;
        for (MarketplaceMeterBilling entry : billingEntries) {
            String slug = extractProductCode(entry.getUsageType());

            if ("software-usage-contracts".equals(slug) && contractCodes.size() == 1) {
                entry.setProductCode(contractCodes.get(0));
                remapped++;
            } else if (isBedrockSlug(slug) && bedrockTarget != null) {
                entry.setProductCode(bedrockTarget);
                remapped++;
            }
            // Otherwise leave the slug-based code; the entry will appear under a
            // billing-derived subscription if no agreement product code matches it.
        }

        log.info("Remapped {}/{} billing entries to agreement product codes for account",
                remapped, billingEntries.size());
    }

    private static boolean isBedrockProduct(String title) {
        if (title == null) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.contains("bedrock") || lower.contains("claude") ||
                lower.contains("mistral") || lower.contains("llama") ||
                lower.contains("titan") || lower.contains("nova") ||
                lower.contains("cohere") || lower.contains("amazon") && lower.contains("ai");
    }

    private static boolean isBedrockSlug(String slug) {
        return slug.contains("bedrock") || slug.contains("token") || slug.contains("inputtoken")
                || slug.contains("outputtoken");
    }

    // ------------------------------------------------------------------
    // Cost Explorer — BILLING_ENTITY = "AWS Marketplace"
    // ------------------------------------------------------------------

    private List<MarketplaceMeterBilling> fetchMarketplaceBilling(
            String accountId, StaticCredentialsProvider creds) {

        List<MarketplaceMeterBilling> result = new ArrayList<>();

        YearMonth endMonth = YearMonth.now();
        YearMonth startMonth = endMonth.minusMonths(BILLING_LOOKBACK_MONTHS);
        String startDate = startMonth.atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);
        String endDate = endMonth.plusMonths(1).atDay(1).format(DateTimeFormatter.ISO_LOCAL_DATE);

        try (CostExplorerClient ce = CostExplorerClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            String nextToken = null;
            do {
                GetCostAndUsageRequest.Builder req = GetCostAndUsageRequest.builder()
                        .timePeriod(DateInterval.builder()
                                .start(startDate)
                                .end(endDate)
                                .build())
                        .granularity(Granularity.MONTHLY)
                        .metrics("UnblendedCost", "UsageQuantity")
                        .groupBy(
                                GroupDefinition.builder()
                                        .type(GroupDefinitionType.DIMENSION)
                                        .key("USAGE_TYPE")
                                        .build())
                        .filter(Expression.builder()
                                .and(
                                        Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                        .key(Dimension.LINKED_ACCOUNT)
                                                        .values(accountId)
                                                        .build())
                                                .build(),
                                        Expression.builder()
                                                .dimensions(DimensionValues.builder()
                                                        .key(Dimension.BILLING_ENTITY)
                                                        .values("AWS Marketplace")
                                                        .build())
                                                .build())
                                .build());

                if (nextToken != null) req.nextPageToken(nextToken);

                GetCostAndUsageResponse response = ce.getCostAndUsage(req.build());

                for (ResultByTime rbt : response.resultsByTime()) {
                    String yearMonth = rbt.timePeriod().start().substring(0, 7);

                    for (Group group : rbt.groups()) {
                        String usageType = group.keys().isEmpty() ? "unknown" : group.keys().get(0);

                        double cost = group.metrics().containsKey("UnblendedCost")
                                ? Double.parseDouble(group.metrics().get("UnblendedCost").amount())
                                : 0.0;
                        double quantity = group.metrics().containsKey("UsageQuantity")
                                ? Double.parseDouble(group.metrics().get("UsageQuantity").amount())
                                : 0.0;

                        // Keep zero-cost marketplace rows too. This allows active
                        // subscriptions that have no current-month charges yet.
                        if (cost < 0.0001 && quantity <= 0.0) continue;

                        MarketplaceMeterBilling entry = new MarketplaceMeterBilling();
                        entry.setCloudAccountId(accountId);
                        entry.setProductCode(extractProductCode(usageType));
                        entry.setYearMonth(yearMonth);
                        entry.setUsageType(usageType);
                        entry.setUsageQuantity(quantity);
                        entry.setCostUsd(BigDecimal.valueOf(cost));
                        entry.setSyncedAt(LocalDateTime.now());
                        result.add(entry);
                    }
                }

                nextToken = response.nextPageToken();
            } while (nextToken != null);

            log.info("Cost Explorer returned {} marketplace row(s) for account {} ({} to {})",
                    result.size(), accountId, startDate, endDate);

        } catch (Exception e) {
            log.error("Cost Explorer call failed for account {}: {}", accountId, e.getMessage(), e);
            throw new IllegalStateException(
                    "Failed to query AWS Marketplace billing for account " + accountId + ": " + e.getMessage(), e);
        }

        return result;
    }

    // ------------------------------------------------------------------
    // Derive product subscriptions from billing rows
    // ------------------------------------------------------------------

    private List<MarketplaceProductSubscription> fetchAgreementSubscriptions(
            String accountId, StaticCredentialsProvider creds) {
        List<MarketplaceProductSubscription> result = new ArrayList<>();
        Map<String, String> titleCache = new HashMap<>();

        try (MarketplaceAgreementClient agreementClient = MarketplaceAgreementClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build();
             MarketplaceCatalogClient catalogClient = MarketplaceCatalogClient.builder()
                     .region(Region.US_EAST_1)
                     .credentialsProvider(creds)
                     .build()) {

            String nextToken = null;
            do {
                SearchAgreementsRequest.Builder req = SearchAgreementsRequest.builder()
                        .catalog(AGREEMENT_CATALOG)
                        .filters(
                                f -> f.name("PartyType").values("Acceptor"),
                                f -> f.name("AgreementType").values("PurchaseAgreement"),
                                f -> f.name("Status").values("ACTIVE"))
                        .maxResults(50);
                if (nextToken != null) req.nextToken(nextToken);

                SearchAgreementsResponse resp = agreementClient.searchAgreements(req.build());
                for (AgreementViewSummary summary : resp.agreementViewSummaries()) {
                    ProposalSummary proposal = summary.proposalSummary();
                    if (proposal == null || !proposal.hasResources()) continue;

                    Resource product = proposal.resources().stream()
                            .filter(r -> r.type() != null && r.type().toLowerCase(Locale.ROOT).contains("product"))
                            .findFirst()
                            .orElse(proposal.resources().get(0));

                    log.info("Agreement {}: resource type={} id={} offerId={}",
                            summary.agreementId(), product.type(), product.id(), proposal.offerId());

                    String productCode = firstNonBlank(product.id(), proposal.offerId(), summary.agreementId());
                    if (productCode == null || productCode.isBlank()) continue;

                    String catalogTitle = titleCache.computeIfAbsent(
                            productCode,
                            key -> resolveListingTitle(catalogClient, key, product.type()));

                    // ── Enrich with DescribeAgreement (start/end dates, proposer/vendor) ──
                    AgreementDetails details = fetchAgreementDetails(
                            agreementClient, summary.agreementId());

                    // ── Enrich with GetAgreementTerms (payment schedule) ──
                    PaymentTermInfo paymentInfo = fetchPaymentTermInfo(
                            agreementClient, summary.agreementId());

                    MarketplaceProductSubscription sub = new MarketplaceProductSubscription();
                    sub.setCloudAccountId(accountId);
                    sub.setProductCode(productCode);
                    sub.setProductTitle(firstNonBlank(catalogTitle, humanReadableTitle(productCode)));
                    sub.setVendorName(firstNonBlank(details.vendorName, resolveVendorFromCatalog(catalogClient, productCode)));
                    sub.setSubscriptionStatus(
                            summary.statusAsString() != null ? summary.statusAsString() : "ACTIVE");
                    sub.setAgreementId(summary.agreementId());
                    sub.setStartDate(details.startDate);
                    sub.setExpiryDate(details.endDate);
                    sub.setOfferType(details.offerType);
                    sub.setAutoRenew(details.autoRenew);
                    sub.setPaymentSchedule(paymentInfo.schedule);
                    sub.setPaymentTermSummary(paymentInfo.summary);
                    sub.setSyncedAt(LocalDateTime.now());
                    result.add(sub);
                }

                nextToken = resp.nextToken();
            } while (nextToken != null && !nextToken.isBlank());

            log.info("Marketplace Agreement returned {} subscription record(s) for account {}", result.size(), accountId);
        } catch (Exception e) {
            log.warn("Marketplace Agreement lookup failed for account {} [{}]: {}. " +
                            "Product names will fall back to billing usage-type labels.",
                    accountId, e.getClass().getSimpleName(), e.getMessage());
        }

        return result;
    }

    // ── Agreement details record ──────────────────────────────────────────────

    private static class AgreementDetails {
        String vendorName;
        LocalDate startDate;
        LocalDate endDate;
        String offerType;
        Boolean autoRenew;
    }

    private static class PaymentTermInfo {
        String schedule;
        String summary;
    }

    /**
     * Calls DescribeAgreement to get start/end dates, proposer (vendor) name,
     * and offer type. Silently returns an empty record on any error.
     */
    private AgreementDetails fetchAgreementDetails(
            MarketplaceAgreementClient client, String agreementId) {
        AgreementDetails details = new AgreementDetails();
        if (agreementId == null || agreementId.isBlank()) return details;
        try {
            DescribeAgreementResponse resp = client.describeAgreement(
                    DescribeAgreementRequest.builder()
                            .agreementId(agreementId)
                            .build());

            // Vendor / proposer name — map known AWS account IDs to display names,
            // skip raw account IDs (they are meaningless to end users)
            if (resp.proposer() != null) {
                String proposerAccountId = resp.proposer().accountId();
                details.vendorName = resolveProposerDisplayName(proposerAccountId);
            }

            // Dates
            if (resp.startTime() != null) {
                details.startDate = resp.startTime()
                        .atZone(ZoneOffset.UTC).toLocalDate();
            }
            if (resp.endTime() != null) {
                details.endDate = resp.endTime()
                        .atZone(ZoneOffset.UTC).toLocalDate();
            }

            // Offer type — derived from agreementType string + offerId prefix heuristic
            // agreementType() returns String directly (no ...AsString() variant needed)
            String agreementType = resp.agreementType();
            if (agreementType != null) {
                if (agreementType.contains("ENTERPRISE") || agreementType.contains("PRIVATE")) {
                    details.offerType = "ENTERPRISE_CONTRACT";
                } else if (resp.proposalSummary() != null
                        && resp.proposalSummary().offerId() != null
                        && resp.proposalSummary().offerId().startsWith("offer-")) {
                    details.offerType = "PRIVATE";
                } else {
                    details.offerType = "PUBLIC";
                }
            }

            // Auto-renew — not directly in DescribeAgreement; check renewal term in GetAgreementTerms
            details.autoRenew = false;

            log.debug("DescribeAgreement {}: vendor={} start={} end={} offerType={}",
                    agreementId, details.vendorName, details.startDate,
                    details.endDate, details.offerType);
        } catch (Exception e) {
            log.warn("DescribeAgreement failed for agreementId={} [{}]: {}",
                    agreementId, e.getClass().getSimpleName(), e.getMessage());
        }
        return details;
    }

    /**
     * Calls GetAgreementTerms to determine payment schedule and build a
     * human-readable payment term summary string.
     */
    private PaymentTermInfo fetchPaymentTermInfo(
            MarketplaceAgreementClient client, String agreementId) {
        PaymentTermInfo info = new PaymentTermInfo();
        if (agreementId == null || agreementId.isBlank()) return info;
        try {
            GetAgreementTermsResponse resp = client.getAgreementTerms(
                    GetAgreementTermsRequest.builder()
                            .agreementId(agreementId)
                            .build());

            if (!resp.hasAcceptedTerms()) return info;

            for (AcceptedTerm term : resp.acceptedTerms()) {
                if (term.fixedUpfrontPricingTerm() != null) {
                    FixedUpfrontPricingTerm t = term.fixedUpfrontPricingTerm();
                    info.schedule = "FIXED_UPFRONT";
                    info.summary = buildUpfrontSummary(t.price(), t.currencyCode(), t.duration());
                    break;
                }
                if (term.recurringPaymentTerm() != null) {
                    RecurringPaymentTerm t = term.recurringPaymentTerm();
                    info.schedule = "RECURRING";
                    info.summary = buildRecurringSummary(t.price(), t.currencyCode(), t.billingPeriod());
                    break;
                }
                if (term.paymentScheduleTerm() != null) {
                    PaymentScheduleTerm t = term.paymentScheduleTerm();
                    info.schedule = "PAYMENT_SCHEDULE";
                    info.summary = t.hasSchedule()
                            ? t.schedule().size() + " scheduled payments"
                            : "Scheduled payments";
                    break;
                }
                if (term.freeTrialPricingTerm() != null) {
                    FreeTrialPricingTerm t = term.freeTrialPricingTerm();
                    info.schedule = "FREE_TRIAL";
                    info.summary = "Free trial" + (t.duration() != null ? " (" + t.duration() + ")" : "");
                    break;
                }
                if (term.usageBasedPricingTerm() != null) {
                    info.schedule = "PAY_AS_YOU_GO";
                    info.summary = "Pay-as-you-go";
                    break;
                }
                if (term.byolPricingTerm() != null) {
                    info.schedule = "BYOL";
                    info.summary = "Bring Your Own License";
                    break;
                }
                if (term.configurableUpfrontPricingTerm() != null) {
                    ConfigurableUpfrontPricingTerm t = term.configurableUpfrontPricingTerm();
                    info.schedule = "CONFIGURABLE_UPFRONT";
                    info.summary = "Configurable upfront"
                            + (t.currencyCode() != null ? " (" + t.currencyCode() + ")" : "");
                    break;
                }
                // RenewalTerm → mark auto-renew true
                if (term.renewalTerm() != null) {
                    // auto-renew is indicated by presence of RenewalTerm — handled separately
                }
            }

            // Second pass: check for RenewalTerm to set autoRenew flag
            boolean hasRenewalTerm = resp.acceptedTerms().stream()
                    .anyMatch(t -> t.renewalTerm() != null);
            if (hasRenewalTerm) {
                // We pass this back through the summary; caller sets autoRenew on the sub
                if (info.summary != null) {
                    info.summary = info.summary; // keep as-is, autoRenew set by caller
                }
            }

            log.debug("GetAgreementTerms {}: schedule={} summary={}",
                    agreementId, info.schedule, info.summary);
        } catch (Exception e) {
            log.warn("GetAgreementTerms failed for agreementId={} [{}]: {}",
                    agreementId, e.getClass().getSimpleName(), e.getMessage());
        }
        return info;
    }

    private String buildUpfrontSummary(String price, String currency, String duration) {
        StringBuilder sb = new StringBuilder();
        if (price != null && !price.isBlank()) {
            try {
                BigDecimal amt = new BigDecimal(price);
                sb.append(String.format("%s %.2f", currency != null ? currency : "USD", amt));
            } catch (NumberFormatException e) {
                sb.append(price);
            }
        }
        if (duration != null && !duration.isBlank()) {
            sb.append(" upfront for ").append(duration);
        } else {
            sb.append(" upfront");
        }
        return sb.toString().trim();
    }

    private String buildRecurringSummary(String price, String currency, String billingPeriod) {
        StringBuilder sb = new StringBuilder();
        if (price != null && !price.isBlank()) {
            try {
                BigDecimal amt = new BigDecimal(price);
                sb.append(String.format("%s %.2f", currency != null ? currency : "USD", amt));
            } catch (NumberFormatException e) {
                sb.append(price);
            }
        }
        if (billingPeriod != null && !billingPeriod.isBlank()) {
            sb.append("/").append(billingPeriod.toLowerCase(Locale.ROOT));
        }
        return sb.toString().trim();
    }

    /**
     * Tries to resolve a vendor/seller name from DescribeEntity JSON.
     * Returns null on failure — caller will leave vendorName blank.
     */
    private String resolveVendorFromCatalog(MarketplaceCatalogClient catalogClient, String entityId) {
        if (entityId == null || entityId.isBlank()) return null;
        try {
            DescribeEntityResponse resp = catalogClient.describeEntity(
                    DescribeEntityRequest.builder()
                            .catalog(CATALOG_CATALOG)
                            .entityId(entityId)
                            .build());
            if (resp.details() != null && !resp.details().isBlank()) {
                return extractVendorName(resp.details());
            }
        } catch (Exception e) {
            log.debug("resolveVendorFromCatalog failed for {}: {}", entityId, e.getMessage());
        }
        return null;
    }

    /**
     * Extracts seller/manufacturer name from DescribeEntity JSON details.
     */
    private String extractVendorName(String detailsJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = CATALOG_OBJECT_MAPPER.readTree(detailsJson);
            // SaaS product shape
            com.fasterxml.jackson.databind.JsonNode desc = root.get("Description");
            if (desc != null) {
                for (String key : new String[]{"Manufacturer", "Seller", "PublisherName", "VendorName"}) {
                    com.fasterxml.jackson.databind.JsonNode node = desc.get(key);
                    if (node != null && !node.asText("").isBlank()) return node.asText();
                }
            }
            // Seller at root level
            com.fasterxml.jackson.databind.JsonNode seller = root.get("Seller");
            if (seller != null && seller.has("Name")) {
                String name = seller.get("Name").asText(null);
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Exception e) {
            log.debug("extractVendorName parse failed: {}", e.getMessage());
        }
        return null;
    }

    // Known AWS-owned account IDs → display names
    private static final Map<String, String> KNOWN_AWS_ACCOUNTS = Map.of(
            "276720623826", "Amazon Web Services",
            "679593333241", "Amazon Web Services",
            "865070037744", "Amazon Web Services",
            "123456789012", "Amazon Web Services"
    );

    /**
     * Maps a proposer account ID to a display name.
     * Returns null (not the raw account ID) when the account is unknown —
     * a raw 12-digit number is meaningless to users.
     */
    private String resolveProposerDisplayName(String accountId) {
        if (accountId == null || accountId.isBlank()) return null;
        // Check known AWS account IDs first
        String known = KNOWN_AWS_ACCOUNTS.get(accountId);
        if (known != null) return known;
        // If it's a pure 12-digit account ID with no display name, return null
        // so the card doesn't show a meaningless number
        if (accountId.matches("\\d{12}")) return null;
        return accountId;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper CATALOG_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Resolves the human-readable product name for a marketplace entity.
     *
     * <p>Strategy 1: {@code DescribeEntity} — works for ANY public marketplace product
     * (buyer or seller). Takes only the entity ID, no type guessing needed.
     * Requires {@code aws-marketplace:DescribeEntity} on the IAM role.
     * The JSON {@code details} field contains {@code Description.ProductTitle}.
     *
     * <p>Strategy 2: {@code ListEntities} fallback — only works for entities owned by
     * the calling account (seller-side). Kept as a fallback for completeness.
     *
     * <p>Returns {@code null} when the name cannot be determined.
     */
    private String resolveListingTitle(MarketplaceCatalogClient catalogClient,
                                       String entityId, String entityType) {
        if (entityId == null || entityId.isBlank()) return null;

        // Strategy 1: DescribeEntity — the correct buyer-side API for product lookups.
        // ListEntities only returns entities owned by the calling account (seller-side),
        // so it cannot resolve names for products listed by other vendors.
        try {
            DescribeEntityRequest descReq = DescribeEntityRequest.builder()
                    .catalog(CATALOG_CATALOG)
                    .entityId(entityId)
                    .build();
            DescribeEntityResponse descResp = catalogClient.describeEntity(descReq);
            if (descResp.details() != null && !descResp.details().isBlank()) {
                String title = extractProductTitle(descResp.details());
                if (title != null && !title.isBlank()) {
                    log.info("DescribeEntity {} → name={}", entityId, title);
                    return title;
                }
            }
        } catch (Exception e) {
            log.warn("DescribeEntity failed for entityId={} [{}]: {}",
                    entityId, e.getClass().getSimpleName(), e.getMessage());
        }

        // Strategy 2: ListEntities fallback (works for seller-owned entities only)
        boolean isUuid = entityId.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        String baseType = (entityType != null && !entityType.isBlank()) ? entityType : "SaaSProduct";
        List<String> typesToTry;
        if (isUuid) {
            typesToTry = new ArrayList<>(Arrays.asList("SaaSProduct", "SaaSProduct@1.0"));
            if (!baseType.startsWith("SaaS")) {
                typesToTry.add(baseType);
                if (!baseType.contains("@")) typesToTry.add(baseType + "@1.0");
            }
        } else {
            typesToTry = baseType.contains("@")
                    ? new ArrayList<>(List.of(baseType))
                    : new ArrayList<>(Arrays.asList(baseType, baseType + "@1.0"));
        }

        for (String type : typesToTry) {
            try {
                ListEntitiesRequest req = ListEntitiesRequest.builder()
                        .catalog(CATALOG_CATALOG)
                        .entityType(type)
                        .filterList(Filter.builder()
                                .name("EntityId")
                                .valueList(entityId)
                                .build())
                        .build();
                ListEntitiesResponse resp = catalogClient.listEntities(req);
                if (resp.hasEntitySummaryList() && !resp.entitySummaryList().isEmpty()) {
                    EntitySummary entitySummary = resp.entitySummaryList().get(0);
                    String name = entitySummary.name();
                    if (name != null && !name.isBlank()) {
                        log.info("ListEntities {} (type={}) → name={}", entityId, type, name);
                        return name;
                    }
                }
            } catch (Exception e) {
                log.warn("ListEntities failed for entityId={} type={} [{}]: {}",
                        entityId, type, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        log.warn("resolveListingTitle: no name found for entityId={}", entityId);
        return null;
    }

    /**
     * Extracts the product title from the JSON {@code details} string returned by
     * {@code DescribeEntity}. Handles both SaaS and AMI/container product shapes.
     */
    private String extractProductTitle(String detailsJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = CATALOG_OBJECT_MAPPER.readTree(detailsJson);
            // SaaS product: {"Description":{"ProductTitle":"..."}}
            com.fasterxml.jackson.databind.JsonNode desc = root.get("Description");
            if (desc != null && desc.has("ProductTitle")) {
                String t = desc.get("ProductTitle").asText(null);
                if (t != null && !t.isBlank()) return t;
            }
            // AMI / container: {"Versions":[{"Details":{"Description":{"ProductTitle":"..."}}}]}
            com.fasterxml.jackson.databind.JsonNode versions = root.get("Versions");
            if (versions != null && versions.isArray() && !versions.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode pt = versions.get(0).at("/Details/Description/ProductTitle");
                if (!pt.isMissingNode()) {
                    String t = pt.asText(null);
                    if (t != null && !t.isBlank()) return t;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse entity details JSON: {}", e.getMessage());
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    /**
     * Groups billing rows by productCode. Creates one subscription per distinct
     * product. Status is ACTIVE if the product has billing in the most recent month,
     * INACTIVE otherwise.
     */
    private List<MarketplaceProductSubscription> deriveSubscriptions(
            String accountId, List<MarketplaceMeterBilling> billingEntries) {

        if (billingEntries.isEmpty()) return Collections.emptyList();

        String latestMonth = billingEntries.stream()
                .map(MarketplaceMeterBilling::getYearMonth)
                .max(Comparator.naturalOrder())
                .orElse("");

        Map<String, List<MarketplaceMeterBilling>> byProduct = billingEntries.stream()
                .collect(Collectors.groupingBy(MarketplaceMeterBilling::getProductCode));

        List<MarketplaceProductSubscription> subs = new ArrayList<>();
        for (Map.Entry<String, List<MarketplaceMeterBilling>> entry : byProduct.entrySet()) {
            String productCode = entry.getKey();
            boolean hasRecentBilling = entry.getValue().stream()
                    .anyMatch(r -> latestMonth.equals(r.getYearMonth()));

            MarketplaceProductSubscription sub = new MarketplaceProductSubscription();
            sub.setCloudAccountId(accountId);
            sub.setProductCode(productCode);
            sub.setProductTitle(humanReadableTitle(productCode));
            sub.setVendorName(null);
            sub.setSubscriptionStatus(hasRecentBilling ? "ACTIVE" : "INACTIVE");
            sub.setExpiryDate(null);
            sub.setSyncedAt(LocalDateTime.now());
            subs.add(sub);
        }

        return subs;
    }

    // ------------------------------------------------------------------
    // Phase 2a — Catalog Browse
    // ------------------------------------------------------------------

    /**
     * Browses public SaaS products on AWS Marketplace using ListEntities.
     * Filters by title search and/or category when provided.
     *
     * Returns: { products: [...], nextToken: "..." }
     */
    public Map<String, Object> browseCatalog(CloudAccount account, String search,
                                              String category, String nextToken) {
        String accountId = account.getAwsAccountId();
        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalStateException("No IAM role configured for account " + accountId);
        }

        StaticCredentialsProvider creds = assumeRole(account.getRoleArn(), account.getExternalId());

        List<Map<String, Object>> products = new ArrayList<>();
        String outNextToken = null;

        // ListEntities for public catalog only supports Visibility and EntityId filters —
        // ProductTitle is not a recognised server-side filter. Title filtering is done
        // client-side after fetching, so we request more items when a search term is provided.
        boolean hasSearch = search != null && !search.isBlank();
        String searchLower = hasSearch ? search.trim().toLowerCase(Locale.ROOT) : null;
        // Fetch extra results when filtering so we still return a full page after client-side filtering
        int fetchSize = hasSearch ? 50 : 20;

        try (MarketplaceCatalogClient catalogClient = MarketplaceCatalogClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            ListEntitiesRequest.Builder reqBuilder = ListEntitiesRequest.builder()
                    .catalog(CATALOG_CATALOG)
                    .entityType("SaaSProduct")
                    .maxResults(fetchSize)
                    .filterList(Filter.builder().name("Visibility").valueList("Public").build());

            if (nextToken != null && !nextToken.isBlank()) {
                reqBuilder.nextToken(nextToken);
            }

            ListEntitiesResponse resp = catalogClient.listEntities(reqBuilder.build());
            outNextToken = resp.nextToken();

            for (EntitySummary es : resp.entitySummaryList()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("entityId", es.entityId());
                p.put("entityArn", es.entityArn());
                p.put("name", es.name() != null ? es.name() : es.entityId());
                p.put("entityType", es.entityType());
                p.put("lastModifiedDate", es.lastModifiedDate() != null ? es.lastModifiedDate().toString() : null);
                p.put("visibility", es.visibility());

                // Enrich with richer details from DescribeEntity (title, description, seller, categories)
                try {
                    DescribeEntityResponse detail = catalogClient.describeEntity(
                            DescribeEntityRequest.builder()
                                    .catalog(CATALOG_CATALOG)
                                    .entityId(es.entityId())
                                    .build());
                    if (detail.details() != null && !detail.details().isBlank()) {
                        enrichCatalogProduct(p, detail.details());
                    }
                } catch (Exception e) {
                    log.debug("DescribeEntity skipped for catalog browse entityId={}: {}", es.entityId(), e.getMessage());
                }

                // Client-side title filter — applied after enrichment so we match on the real product title
                if (searchLower != null) {
                    String name = ((String) p.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
                    String desc = ((String) p.getOrDefault("shortDescription", "")).toLowerCase(Locale.ROOT);
                    String seller = ((String) p.getOrDefault("sellerName", "")).toLowerCase(Locale.ROOT);
                    if (!name.contains(searchLower) && !desc.contains(searchLower) && !seller.contains(searchLower)) {
                        continue; // skip this product
                    }
                }

                products.add(p);
                if (products.size() >= 20) break; // cap page at 20 after filtering
            }

            log.info("Catalog browse: {} products returned for account {} (search={} category={})",
                    products.size(), accountId, search, category);
        } catch (Exception e) {
            log.error("Catalog browse failed for account {}: {}", accountId, e.getMessage(), e);
            throw new IllegalStateException("Failed to browse catalog: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("products", products);
        result.put("nextToken", outNextToken);
        return result;
    }

    private void enrichCatalogProduct(Map<String, Object> product, String detailsJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = CATALOG_OBJECT_MAPPER.readTree(detailsJson);
            com.fasterxml.jackson.databind.JsonNode desc = root.get("Description");
            if (desc != null) {
                String title = desc.has("ProductTitle") ? desc.get("ProductTitle").asText(null) : null;
                String shortDesc = desc.has("ShortDescription") ? desc.get("ShortDescription").asText(null) : null;
                String longDesc = desc.has("LongDescription") ? desc.get("LongDescription").asText(null) : null;
                if (title != null && !title.isBlank()) product.put("name", title);
                if (shortDesc != null && !shortDesc.isBlank()) product.put("shortDescription", shortDesc);
                if (longDesc != null && !longDesc.isBlank()) product.put("longDescription", longDesc);

                // Categories / tags
                com.fasterxml.jackson.databind.JsonNode cats = desc.get("Categories");
                if (cats != null && cats.isArray()) {
                    List<String> catList = new ArrayList<>();
                    cats.forEach(c -> catList.add(c.asText()));
                    product.put("categories", catList);
                }
            }
            // Seller name
            com.fasterxml.jackson.databind.JsonNode seller = root.get("Seller");
            if (seller != null && seller.has("Name")) {
                String sellerName = seller.get("Name").asText(null);
                if (sellerName != null && !sellerName.isBlank()) product.put("sellerName", sellerName);
            }
            // Pricing dimensions summary (first offer if present)
            com.fasterxml.jackson.databind.JsonNode offers = root.get("Offers");
            if (offers != null && offers.isArray() && !offers.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode offer = offers.get(0);
                if (offer.has("Pricing") && offer.get("Pricing").has("Type")) {
                    product.put("pricingType", offer.get("Pricing").get("Type").asText(null));
                }
            }
        } catch (Exception e) {
            log.debug("enrichCatalogProduct parse error: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Phase 2d — Cancel / Terminate Subscription
    // ------------------------------------------------------------------

    /**
     * Cancels an active subscription by submitting a StartChangeSet with
     * change type "UnsubscribeFromProduct" against the agreement entity.
     *
     * Note: AWS may reject this if the agreement has a non-cancellable term.
     * The change set ID is returned so the caller can poll for completion.
     */
    @Transactional
    public Map<String, Object> cancelSubscription(CloudAccount account, Long subscriptionId) {
        String accountId = account.getAwsAccountId();

        MarketplaceProductSubscription sub = subscriptionRepository.findById(subscriptionId)
                .filter(s -> s.getCloudAccountId().equals(accountId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Subscription " + subscriptionId + " not found for account " + accountId));

        if (!"ACTIVE".equals(sub.getSubscriptionStatus())) {
            throw new IllegalStateException("Subscription " + subscriptionId + " is not ACTIVE — cannot cancel.");
        }

        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalStateException("No IAM role configured for account " + accountId);
        }

        StaticCredentialsProvider creds = assumeRole(account.getRoleArn(), account.getExternalId());

        String agreementId = sub.getAgreementId();
        if (agreementId == null || agreementId.isBlank()) {
            throw new IllegalStateException(
                    "Cannot cancel — no agreementId found for subscription " + subscriptionId +
                    ". Please sync first to retrieve the agreement ID.");
        }

        String changeSetId;
        try (MarketplaceCatalogClient catalogClient = MarketplaceCatalogClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds)
                .build()) {

            String detailsJson = String.format(
                    "{\"AgreementId\":\"%s\"}", agreementId);

            StartChangeSetRequest req = StartChangeSetRequest.builder()
                    .catalog(CATALOG_CATALOG)
                    .changeSetName("Cancel-" + sub.getProductCode() + "-" + System.currentTimeMillis())
                    .changeSet(Change.builder()
                            .changeType("CancelAgreement")
                            .entity(Entity.builder()
                                    .type("Agreement@1.0")
                                    .identifier(agreementId)
                                    .build())
                            .details(detailsJson)
                            .build())
                    .build();

            StartChangeSetResponse resp = catalogClient.startChangeSet(req);
            changeSetId = resp.changeSetId();

            log.info("StartChangeSet submitted: changeSetId={} for agreement={} account={}",
                    changeSetId, agreementId, accountId);
        } catch (Exception e) {
            log.error("StartChangeSet (cancel) failed for agreement={} account={}: {}",
                    agreementId, accountId, e.getMessage(), e);
            throw new IllegalStateException("Failed to submit cancellation: " + e.getMessage(), e);
        }

        // Optimistically mark as INACTIVE locally — AWS processes async
        sub.setSubscriptionStatus("INACTIVE");
        sub.setSyncedAt(LocalDateTime.now());
        subscriptionRepository.save(sub);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "CANCELLATION_SUBMITTED");
        result.put("changeSetId", changeSetId);
        result.put("agreementId", agreementId);
        result.put("subscriptionId", subscriptionId);
        result.put("message", "Cancellation request submitted to AWS Marketplace. The agreement will be terminated after processing.");
        return result;
    }

    // ------------------------------------------------------------------
    // Phase 2e — PO Number Management
    // ------------------------------------------------------------------

    /**
     * Attaches or updates a PO number and optional notes on a subscription.
     * Stored in XamOps DB only — not synced to AWS.
     */
    @Transactional
    public Map<String, Object> updatePoNumber(String cloudAccountId, Long subscriptionId,
                                               String poNumber, String poNotes) {
        MarketplaceProductSubscription sub = subscriptionRepository.findById(subscriptionId)
                .filter(s -> s.getCloudAccountId().equals(cloudAccountId))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Subscription " + subscriptionId + " not found for account " + cloudAccountId));

        sub.setPoNumber(poNumber != null ? poNumber.trim() : null);
        sub.setPoNotes(poNotes != null ? poNotes.trim() : null);
        subscriptionRepository.save(sub);

        log.info("Updated PO number for subscription {} (account {}): poNumber={}",
                subscriptionId, cloudAccountId, poNumber);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UPDATED");
        result.put("subscriptionId", subscriptionId);
        result.put("poNumber", sub.getPoNumber());
        result.put("poNotes", sub.getPoNotes());
        return result;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String normalizeUsageType(String usageType) {
        String normalized = usageType.toLowerCase(Locale.ROOT).trim();
        normalized = normalized.replace(':', '-').replace('_', '-');
        normalized = REGION_PREFIX_PATTERN.matcher(normalized).replaceFirst("");
        normalized = normalized.replaceFirst("^global-", "");
        normalized = normalized.replaceFirst("^mp-", "");
        normalized = normalized.replaceFirst("^(use\\d|usw\\d|aps\\d|apn\\d|ape\\d)-", "");
        normalized = normalized.replaceAll("[^a-z0-9-]", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-|-$", "");
        return normalized;
    }

    static String extractProductCode(String usageType) {
        if (usageType == null || usageType.isBlank()) return "unknown";

        String normalized = normalizeUsageType(usageType);
        if (normalized.isBlank()) return "unknown";

        if (normalized.contains("softwareusage") && normalized.contains("contract")) {
            return "software-usage-contracts";
        }
        if (normalized.contains("inputtoken")) {
            return "bedrock-input-token-usage";
        }
        if (normalized.contains("outputtoken")) {
            return "bedrock-output-token-usage";
        }
        if (normalized.contains("token")) {
            return "bedrock-token-usage";
        }

        String[] parts = normalized.split("-", 4);
        if (parts.length >= 2) {
            return (parts[0] + "-" + parts[1]).toLowerCase();
        }
        return normalized;
    }

    /**
     * Converts a product code slug into a human-readable display title.
     * E.g. "uniskai-pro" → "Uniskai Pro"
     */
    static String humanReadableTitle(String productCode) {
        if (productCode == null || productCode.isBlank()) return "Unknown Product";
        if ("software-usage-contracts".equals(productCode)) return "Software Usage Contracts";
        if ("bedrock-input-token-usage".equals(productCode)) return "Bedrock Input Token Usage";
        if ("bedrock-output-token-usage".equals(productCode)) return "Bedrock Output Token Usage";
        if ("bedrock-token-usage".equals(productCode)) return "Bedrock Token Usage";

        // UUID-format product IDs — ListEntities should resolve these to real names.
        // This is the last-resort fallback if ListEntities fails (e.g. private product).
        if (productCode.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return "Marketplace Product (" + productCode.substring(0, 8) + "…)";
        }

        return Arrays.stream(productCode.split("-"))
                .filter(s -> !s.isBlank())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining(" "));
    }

    private StaticCredentialsProvider assumeRole(String roleArn, String externalId) {
        software.amazon.awssdk.services.sts.model.AssumeRoleRequest.Builder reqBuilder =
                software.amazon.awssdk.services.sts.model.AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName("xamops-marketops-sync")
                        .durationSeconds(3600);
        if (externalId != null && !externalId.isBlank()) {
            reqBuilder.externalId(externalId);
        }
        AssumeRoleResponse response = stsClient.assumeRole(reqBuilder.build());
        Credentials c = response.credentials();
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(c.accessKeyId(), c.secretAccessKey(), c.sessionToken()));
    }
}
