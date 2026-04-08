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
import software.amazon.awssdk.services.marketplaceagreement.model.AgreementViewSummary;
import software.amazon.awssdk.services.marketplaceagreement.model.ProposalSummary;
import software.amazon.awssdk.services.marketplaceagreement.model.Resource;
import software.amazon.awssdk.services.marketplaceagreement.model.SearchAgreementsRequest;
import software.amazon.awssdk.services.marketplaceagreement.model.SearchAgreementsResponse;
import software.amazon.awssdk.services.marketplacecatalog.MarketplaceCatalogClient;
import software.amazon.awssdk.services.marketplacecatalog.model.DescribeEntityRequest;
import software.amazon.awssdk.services.marketplacecatalog.model.DescribeEntityResponse;
import software.amazon.awssdk.services.marketplacecatalog.model.EntitySummary;
import software.amazon.awssdk.services.marketplacecatalog.model.Filter;
import software.amazon.awssdk.services.marketplacecatalog.model.ListEntitiesRequest;
import software.amazon.awssdk.services.marketplacecatalog.model.ListEntitiesResponse;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

                    // Title resolution priority:
                    //   1. DescribeEntity — buyer-side API, works for any public product
                    //      including UUID-format SaaS product IDs. Returns name from
                    //      Description.ProductTitle in the entity details JSON.
                    //   2. ListEntities fallback — seller-side, for prod-xxx codes.
                    //   3. humanReadableTitle — last resort slug/placeholder.
                    String catalogTitle = titleCache.computeIfAbsent(
                            productCode,
                            key -> resolveListingTitle(catalogClient, key, product.type()));

                    MarketplaceProductSubscription sub = new MarketplaceProductSubscription();
                    sub.setCloudAccountId(accountId);
                    sub.setProductCode(productCode);
                    sub.setProductTitle(firstNonBlank(catalogTitle, humanReadableTitle(productCode)));
                    sub.setVendorName(null);
                    sub.setSubscriptionStatus(
                            summary.statusAsString() != null ? summary.statusAsString() : "ACTIVE");
                    sub.setExpiryDate(null);
                    sub.setSyncedAt(LocalDateTime.now());
                    result.add(sub);
                }

                nextToken = resp.nextToken();
            } while (nextToken != null && !nextToken.isBlank());

            log.info("Marketplace Agreement returned {} subscription record(s) for account {}", result.size(), accountId);
        } catch (Exception e) {
            // Common causes: missing IAM permissions (aws-marketplace:SearchAgreements,
            // aws-marketplace:DescribeAgreement) on the assumed role, or the API not
            // being accessible in the selected region.
            log.warn("Marketplace Agreement lookup failed for account {} [{}]: {}. " +
                            "Product names will fall back to billing usage-type labels.",
                    accountId, e.getClass().getSimpleName(), e.getMessage());
        }

        return result;
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
