package com.xammer.cloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostOptimizationHubRecommendationDetailDto;
import com.xammer.cloud.dto.CostOptimizationHubRecommendationDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.services.costoptimizationhub.CostOptimizationHubClient;
import software.amazon.awssdk.services.costoptimizationhub.model.GetPreferencesRequest;
import software.amazon.awssdk.services.costoptimizationhub.model.GetRecommendationRequest;
import software.amazon.awssdk.services.costoptimizationhub.model.GetRecommendationResponse;
import software.amazon.awssdk.services.costoptimizationhub.model.ListRecommendationsRequest;
import software.amazon.awssdk.services.costoptimizationhub.model.ListRecommendationsResponse;
import software.amazon.awssdk.services.costoptimizationhub.model.Recommendation;
import software.amazon.awssdk.services.costoptimizationhub.model.ResourceDetails;
import software.amazon.awssdk.services.costoptimizationhub.model.Tag;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads AWS Cost Optimization Hub recommendations for a customer account via the live SDK API
 * (assumed-role, us-east-1-pinned client from {@link AwsClientProvider}).
 *
 * <p>The Hub is a global Billing &amp; Cost Management service and may be disabled for an account.
 * Every method degrades gracefully: a disabled Hub, a throttled/timed-out call, or any
 * {@code SdkException} yields an empty list / empty detail / {@code enabled=false} rather than a
 * thrown exception, so the AWS Rightsizing page always returns HTTP 200. Caching mirrors
 * {@link OptimizationService}'s rightsizing pattern (Redis first, DB snapshot fallback).
 */
@Service
public class CostOptimizationHubService {

    private static final Logger logger = LoggerFactory.getLogger(CostOptimizationHubService.class);

    /** Redis TTL in minutes, matching the rightsizing caches in {@link OptimizationService}. */
    private static final int CACHE_TTL_MINUTES = 60;
    /** Safety cap on pagination so a runaway feed cannot exhaust memory. */
    private static final int MAX_PAGES = 20;
    /** Recursion guard for the union flattening. */
    private static final int MAX_FLATTEN_DEPTH = 4;

    private final CloudAccountRepository cloudAccountRepository;
    private final AwsClientProvider awsClientProvider;
    private final RedisCacheService redisCache;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final SnapshotFreshnessService snapshotFreshnessService;

    public CostOptimizationHubService(CloudAccountRepository cloudAccountRepository,
                                      AwsClientProvider awsClientProvider,
                                      RedisCacheService redisCache,
                                      CachedDataRepository cachedDataRepository,
                                      ObjectMapper objectMapper,
                                      SnapshotFreshnessService snapshotFreshnessService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.awsClientProvider = awsClientProvider;
        this.redisCache = redisCache;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.snapshotFreshnessService = snapshotFreshnessService;
    }

    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found in database: " + accountId);
        }
        return accounts.get(0);
    }

    // ── List ─────────────────────────────────────────────────────────────────

    /**
     * All Cost Optimization Hub recommendations for the account, sorted by estimated monthly
     * savings descending. Redis-first with a DB snapshot fallback; {@code forceRefresh} bypasses
     * both. Returns an empty list on a disabled Hub or any AWS error — never throws.
     */
    public List<CostOptimizationHubRecommendationDto> getRecommendations(String accountId, boolean forceRefresh) {
        String cacheKey = "cohRecommendations-" + accountId;
        String dbKey = "coh-recommendations-" + accountId;

        if (!forceRefresh) {
            Optional<List<CostOptimizationHubRecommendationDto>> cached =
                    redisCache.get(cacheKey, new TypeReference<>() {
                    });
            if (cached.isPresent()) {
                logger.info("✅ Redis cache hit for Cost Optimization Hub recommendations for account {}", accountId);
                return cached.get();
            }
            try {
                Optional<CachedData> dbCache = snapshotFreshnessService.ifFresh(
                        cachedDataRepository.findById(dbKey), dbKey);
                if (dbCache.isPresent()) {
                    List<CostOptimizationHubRecommendationDto> recs = objectMapper.readValue(
                            dbCache.get().getJsonData(),
                            new TypeReference<List<CostOptimizationHubRecommendationDto>>() {
                            });
                    if (!recs.isEmpty()) {
                        logger.info("✅ Serving {} COH recs from DB — repopulating Redis for account {}",
                                recs.size(), accountId);
                        redisCache.put(cacheKey, recs, CACHE_TTL_MINUTES);
                        return recs;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to read Cost Optimization Hub data from DB cache: {}", e.getMessage());
            }
        }

        List<CostOptimizationHubRecommendationDto> recommendations = new ArrayList<>();
        try {
            CloudAccount account = getAccount(accountId);
            CostOptimizationHubClient client = awsClientProvider.getCostOptimizationHubClient(account);

            String nextToken = null;
            int pages = 0;
            do {
                ListRecommendationsRequest request = ListRecommendationsRequest.builder()
                        .includeAllRecommendations(true)
                        .nextToken(nextToken)
                        .build();
                ListRecommendationsResponse response = client.listRecommendations(request);
                if (response.hasItems()) {
                    for (Recommendation item : response.items()) {
                        recommendations.add(toDto(item, accountId));
                    }
                }
                nextToken = response.nextToken();
                pages++;
            } while (nextToken != null && !nextToken.isBlank() && pages < MAX_PAGES);

            recommendations.sort(Comparator.comparing(
                    CostOptimizationHubRecommendationDto::getEstimatedMonthlySavings,
                    Comparator.nullsLast(Comparator.reverseOrder())));

            redisCache.put(cacheKey, recommendations, CACHE_TTL_MINUTES);
            try {
                CachedData snapshot = cachedDataRepository.findById(dbKey).orElse(new CachedData());
                snapshot.setCacheKey(dbKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(recommendations));
                snapshot.setLastUpdated(LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception e) {
                logger.warn("Failed to save Cost Optimization Hub snapshot to DB for account {}: {}",
                        accountId, e.getMessage());
            }
        } catch (Exception e) {
            // Hub disabled, access denied, throttled, or timed out — degrade to empty.
            logger.warn("Cost Optimization Hub recommendations unavailable for account {}: {}",
                    accountId, e.getMessage());
            return Collections.emptyList();
        }
        return recommendations;
    }

    // ── Detail ─────────────────────────────────────────────────────────────────

    /**
     * Full current-vs-recommended drill-down for a single recommendation. Returns {@code null} on
     * any AWS error so the controller can answer 200 with an empty body rather than 500.
     */
    public CostOptimizationHubRecommendationDetailDto getRecommendationDetail(String accountId,
                                                                             String recommendationId) {
        try {
            CloudAccount account = getAccount(accountId);
            CostOptimizationHubClient client = awsClientProvider.getCostOptimizationHubClient(account);
            GetRecommendationResponse response = client.getRecommendation(
                    GetRecommendationRequest.builder().recommendationId(recommendationId).build());
            return toDetailDto(response, accountId);
        } catch (Exception e) {
            logger.warn("Cost Optimization Hub detail unavailable for recommendation {} (account {}): {}",
                    recommendationId, accountId, e.getMessage());
            return null;
        }
    }

    // ── Enablement ───────────────────────────────────────────────────────────

    /**
     * Whether Cost Optimization Hub is enabled for the account. The API exposes no explicit
     * enabled flag, so enablement is inferred: a successful {@code GetPreferences} call means the
     * account is enrolled; any exception (access denied / not found / throttling / timeout) means
     * it is not reachable and is reported as not enabled.
     */
    public boolean isEnabled(String accountId) {
        try {
            CloudAccount account = getAccount(accountId);
            CostOptimizationHubClient client = awsClientProvider.getCostOptimizationHubClient(account);
            client.getPreferences(GetPreferencesRequest.builder().build());
            return true;
        } catch (Exception e) {
            logger.info("Cost Optimization Hub not enabled/reachable for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }

    // ── Mapping ────────────────────────────────────────────────────────────────

    private CostOptimizationHubRecommendationDto toDto(Recommendation item, String accountId) {
        CostOptimizationHubRecommendationDto dto = new CostOptimizationHubRecommendationDto();
        populateCommon(dto, item.recommendationId(), item.accountId(), accountId,
                item.actionType(), item.currentResourceType(), item.currentResourceSummary(),
                item.recommendedResourceType(), item.recommendedResourceSummary(),
                item.resourceArn(), item.resourceId(), item.region(),
                item.estimatedMonthlySavings(), item.estimatedSavingsPercentage(), item.estimatedMonthlyCost(),
                item.implementationEffort(), item.restartNeeded(), item.rollbackPossible(),
                item.currencyCode(), item.lastRefreshTimestamp(), tagsToMap(item.tags()));
        return dto;
    }

    private CostOptimizationHubRecommendationDetailDto toDetailDto(GetRecommendationResponse r, String accountId) {
        CostOptimizationHubRecommendationDetailDto dto = new CostOptimizationHubRecommendationDetailDto();
        populateCommon(dto, r.recommendationId(), r.accountId(), accountId,
                r.actionTypeAsString(), r.currentResourceTypeAsString(), null,
                r.recommendedResourceTypeAsString(), null,
                r.resourceArn(), r.resourceId(), r.region(),
                r.estimatedMonthlySavings(), r.estimatedSavingsPercentage(), r.estimatedMonthlyCost(),
                r.implementationEffortAsString(), r.restartNeeded(), r.rollbackPossible(),
                r.currencyCode(), r.lastRefreshTimestamp(), tagsToMap(r.tags()));

        dto.setRecommendationLookbackPeriodInDays(r.recommendationLookbackPeriodInDays());
        dto.setCostCalculationLookbackPeriodInDays(r.costCalculationLookbackPeriodInDays());
        dto.setEstimatedSavingsOverCostCalculationLookbackPeriod(
                r.estimatedSavingsOverCostCalculationLookbackPeriod());
        dto.setCurrentResourceDetails(flattenResourceDetails(r.currentResourceDetails()));
        dto.setRecommendedResourceDetails(flattenResourceDetails(r.recommendedResourceDetails()));
        return dto;
    }

    @SuppressWarnings("java:S107") // deliberate single funnel for the shared list fields
    private void populateCommon(CostOptimizationHubRecommendationDto dto,
                                String recommendationId, String itemAccountId, String fallbackAccountId,
                                String actionType, String currentResourceType, String currentResourceSummary,
                                String recommendedResourceType, String recommendedResourceSummary,
                                String resourceArn, String resourceId, String region,
                                Double estimatedMonthlySavings, Double estimatedSavingsPercentage,
                                Double estimatedMonthlyCost, String implementationEffort,
                                Boolean restartNeeded, Boolean rollbackPossible, String currencyCode,
                                Instant lastRefreshTimestamp, Map<String, String> tags) {
        dto.setRecommendationId(recommendationId);
        dto.setAccountId(itemAccountId != null ? itemAccountId : fallbackAccountId);
        dto.setActionType(actionType);
        dto.setCurrentResourceType(currentResourceType);
        dto.setCurrentResourceSummary(currentResourceSummary);
        dto.setRecommendedResourceType(recommendedResourceType);
        dto.setRecommendedResourceSummary(recommendedResourceSummary);
        dto.setResourceArn(resourceArn);
        dto.setResourceId(resourceId);
        dto.setRegion(region);
        dto.setEstimatedMonthlySavings(estimatedMonthlySavings);
        dto.setEstimatedSavingsPercentage(estimatedSavingsPercentage);
        dto.setEstimatedMonthlyCost(estimatedMonthlyCost);
        dto.setImplementationEffort(implementationEffort);
        dto.setRestartNeeded(restartNeeded);
        dto.setRollbackPossible(rollbackPossible);
        dto.setCurrencyCode(currencyCode);
        dto.setLastRefreshTimestamp(lastRefreshTimestamp != null ? lastRefreshTimestamp.toString() : null);
        dto.setTags(tags);
    }

    private Map<String, String> tagsToMap(List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Tag tag : tags) {
            if (tag.key() != null) {
                map.put(tag.key(), tag.value());
            }
        }
        return map;
    }

    /**
     * Flattens the active member of a {@link ResourceDetails} union into an ordered
     * {@code key -> value} map. {@code ResourceDetails} is a 25-member union; rather than switch
     * on every member type, the active member is flattened generically through the SDK's
     * {@link SdkPojo#sdkFields()} reflection so any current or future member type populates the
     * drill-down without code changes.
     */
    private Map<String, String> flattenResourceDetails(ResourceDetails details) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (details == null) {
            return flat;
        }
        flattenPojo(details, "", flat, 0);
        return flat;
    }

    private void flattenPojo(SdkPojo pojo, String prefix, Map<String, String> out, int depth) {
        if (pojo == null || depth > MAX_FLATTEN_DEPTH) {
            return;
        }
        for (SdkField<?> field : pojo.sdkFields()) {
            Object value = field.getValueOrDefault(pojo);
            if (value == null) {
                continue;
            }
            String key = prefix.isEmpty() ? field.memberName() : prefix + "." + field.memberName();
            if (value instanceof SdkPojo nested) {
                flattenPojo(nested, key, out, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                if (!map.isEmpty()) {
                    out.put(key, map.toString());
                }
            } else if (value instanceof List<?> list) {
                if (!list.isEmpty()) {
                    out.put(key, list.toString());
                }
            } else {
                out.put(key, String.valueOf(value));
            }
        }
    }
}
