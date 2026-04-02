package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import software.amazon.awssdk.services.marketplacecatalog.model.DescribeEntityResponse;
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
    private static final String AWS_MARKETPLACE_CATALOG = "aws.marketplace";
    private static final Pattern REGION_PREFIX_PATTERN = Pattern.compile("^[a-z]{2,4}\\d[-:_]");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
            syncAll(account);
            return subscriptionRepository.findByCloudAccountId(accountId);
        }
        return existing;
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
     */
    @Transactional
    public Map<String, Object> syncAll(CloudAccount account) {
        String accountId = account.getAwsAccountId();
        log.info("Syncing MarketOps data for account {}", accountId);

        if (account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            throw new IllegalStateException("No IAM role configured for account " + accountId);
        }

        StaticCredentialsProvider creds = assumeRole(account.getRoleArn(), account.getExternalId());

        // 1. Pull all AWS Marketplace billing from Cost Explorer
        List<MarketplaceMeterBilling> billingEntries = fetchMarketplaceBilling(accountId, creds);

        // 2. Derive unique subscriptions from billing data
        List<MarketplaceProductSubscription> subscriptions =
                deriveSubscriptions(accountId, billingEntries);
        // 2b. Enrich from Marketplace agreements (real subscription records)
        subscriptions = mergeAgreementSubscriptions(
                subscriptions,
                fetchAgreementSubscriptions(accountId, creds));

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

    private List<MarketplaceProductSubscription> mergeAgreementSubscriptions(
            List<MarketplaceProductSubscription> billingDerived,
            List<MarketplaceProductSubscription> agreementDerived) {
        if (agreementDerived.isEmpty()) return billingDerived;

        Map<String, MarketplaceProductSubscription> merged = new LinkedHashMap<>();
        for (MarketplaceProductSubscription sub : billingDerived) {
            merged.put(sub.getProductCode(), sub);
        }
        for (MarketplaceProductSubscription sub : agreementDerived) {
            merged.putIfAbsent(sub.getProductCode(), sub);
        }
        return new ArrayList<>(merged.values());
    }

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
                        .catalog(AWS_MARKETPLACE_CATALOG)
                        .filters(
                                f -> f.name("AgreementType").values("PurchaseAgreement"),
                                f -> f.name("AcceptorAccountId").values(accountId),
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

                    String productCode = firstNonBlank(product.id(), proposal.offerId(), summary.agreementId());
                    if (productCode == null || productCode.isBlank()) continue;

                    String productTitle = titleCache.computeIfAbsent(
                            productCode,
                            key -> resolveCatalogTitle(catalogClient, key));

                    MarketplaceProductSubscription sub = new MarketplaceProductSubscription();
                    sub.setCloudAccountId(accountId);
                    sub.setProductCode(productCode);
                    sub.setProductTitle(firstNonBlank(productTitle, humanReadableTitle(productCode)));
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
            log.warn("Marketplace Agreement lookup failed for account {}: {}", accountId, e.getMessage());
        }

        return result;
    }

    private String resolveCatalogTitle(MarketplaceCatalogClient catalogClient, String entityId) {
        try {
            DescribeEntityResponse response = catalogClient.describeEntity(r -> r
                    .catalog(AWS_MARKETPLACE_CATALOG)
                    .entityId(entityId));
            String title = extractTitleFromJson(response.details());
            return firstNonBlank(title, response.entityIdentifier(), entityId);
        } catch (Exception e) {
            return entityId;
        }
    }

    private String extractTitleFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            String direct = firstNonBlank(
                    textOrNull(root.get("ProductTitle")),
                    textOrNull(root.get("productTitle")),
                    textOrNull(root.get("Name")),
                    textOrNull(root.get("name")),
                    textOrNull(root.get("Title")),
                    textOrNull(root.get("title")));
            if (direct != null) return direct;
            return findTitleRecursive(root);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String findTitleRecursive(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                if (("title".equals(key) || "name".equals(key) || "producttitle".equals(key)) && field.getValue().isTextual()) {
                    return field.getValue().asText();
                }
                String nested = findTitleRecursive(field.getValue());
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String nested = findTitleRecursive(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
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
