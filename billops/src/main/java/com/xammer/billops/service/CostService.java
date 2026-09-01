package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AwsCreditDto;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.cloud.domain.CachedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.costexplorer.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CostService {

    private final AwsClientProvider awsClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final CostMockService costMockService;
    private final RedisCacheService redisCacheService;
    private final BillOpsCachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(CostService.class);

    @Value("${aws.costexplorer.mock:false}")
    private boolean useMockData;

    @PostConstruct
    public void init() {
        logger.info("✅ CostService initialized (Mock Mode: {})", useMockData);
    }

    public CostService(AwsClientProvider awsClientProvider,
            CloudAccountRepository cloudAccountRepository,
            CostMockService costMockService,
            RedisCacheService redisCacheService,
            BillOpsCachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.awsClientProvider = awsClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.costMockService = costMockService;
        this.redisCacheService = redisCacheService;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Get AWS Credits for an account.
     * Returns mock data when aws.costexplorer.mock=true.
     */
    public AwsCreditDto getAwsCredits(CloudAccount account) {
        if (useMockData) {
            return costMockService.getMockAwsCredits(account);
        }

        logger.info("Fetching AWS credit data for account: {}", account.getAwsAccountId());

        // NOTE: Real implementation requires AWS Cost Explorer API for credits
        // For now, this returns the mock service data even when mock is disabled
        // TODO: Implement real AWS Credits API integration when available
        return costMockService.getMockAwsCredits(account);
    }

    /**
     * Fetches the total AWS Marketplace charge (BILLING_ENTITY = "AWS Marketplace")
     * for the given account & month, in USD. These are billed by
     * "Amazon Web Services, Inc." and must be added to the invoice directly WITHOUT
     * any discount. Queried org-wide (no LINKED_ACCOUNT filter) using the account's
     * CE client — same scope as the standard consumption total — so
     * (standard = total - marketplace) reconciles with the AWS console split.
     * Marketplace charges are not modified by Billing Conductor, so live CE is
     * accurate even for closed months.
     */
    public double getMarketplaceCharge(CloudAccount account, Integer year, Integer month, boolean forceRefresh) {
        if (useMockData) {
            return 0.0;
        }
        String accountId = account.getAwsAccountId();
        String cacheKey = String.format("BILLOPS_MARKETPLACE::%s::%s::%s", accountId, year, month);

        if (!forceRefresh) {
            Optional<Double> cached = redisCacheService.get(cacheKey, new TypeReference<Double>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    Double v = objectMapper.readValue(dbCached.get().getJsonData(), Double.class);
                    if (v != null) {
                        redisCacheService.put(cacheKey, v, 60 * 24);
                        return v;
                    }
                }
            } catch (Exception e) {
                logger.debug("Marketplace DB cache miss for {}: {}", cacheKey, e.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            YearMonth ym = YearMonth.of(year, month);
            LocalDate start = ym.atDay(1);
            LocalDate end = ym.atEndOfMonth();

            Expression marketplaceFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.BILLING_ENTITY)
                            .values("AWS Marketplace")
                            .build())
                    .build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start.toString()).end(end.plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .filter(marketplaceFilter)
                    .build();

            GetCostAndUsageResponse response = client.getCostAndUsage(request);
            double total = 0.0;
            if (response != null && response.hasResultsByTime()) {
                total = response.resultsByTime().stream()
                        .filter(r -> r.total() != null && r.total().containsKey("UnblendedCost"))
                        .mapToDouble(r -> Double.parseDouble(r.total().get("UnblendedCost").amount()))
                        .sum();
            }

            logger.info("[CostService] Marketplace charge for {} {}/{}: ${}",
                    accountId, year, month, String.format("%.2f", total));

            redisCacheService.put(cacheKey, total, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(total));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save marketplace DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return total;
        } catch (Exception e) {
            logger.warn("Failed to fetch marketplace charge for {} {}/{}: {}", accountId, year, month, e.getMessage());
            return 0.0;
        }
    }

    public List<Map<String, Object>> getCostHistory(CloudAccount account, Integer year, Integer month) {
        return getCostHistory(account, year, month, false);
    }

    public List<Map<String, Object>> getCostHistory(CloudAccount account, Integer year, Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostHistory(account, year, month);
        }

        String accountId = account.getAwsAccountId();
        String cacheKey = String.format("BILLOPS_COST_HISTORY::%s::%s::%s", accountId, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        // ── Compose 6-month history from absolute monthly snapshots ───────────
        // For each closed month in the window, read AWS_MONTHLY_TOTAL snapshot
        // instead of calling CE API. Current month falls through to CE API below.
        try {
            LocalDate endDate = (year != null && month != null)
                    ? YearMonth.of(year, month).atEndOfMonth() : LocalDate.now();
            List<Map<String, Object>> composed = new ArrayList<>();
            boolean allCoveredBySnapshot = true;

            for (int i = 5; i >= 0; i--) {
                YearMonth ym = YearMonth.from(endDate).minusMonths(i);
                String snapKey = "AWS_MONTHLY_TOTAL::" + accountId + "::" + ym;
                Optional<CachedData> snapRow = cachedDataRepository.findById(snapKey);
                if (snapRow.isPresent()) {
                    double total = objectMapper.readValue(snapRow.get().getJsonData(), Double.class);
                    Map<String, Object> point = new HashMap<>();
                    point.put("date", ym.atDay(1).toString());
                    point.put("cost", total);
                    composed.add(point);
                } else if (ym.isBefore(YearMonth.now())) {
                    // Closed month but no snapshot yet — let CE API handle everything
                    allCoveredBySnapshot = false;
                    break;
                } else {
                    // Current month — cannot snapshot; let CE API handle it
                    allCoveredBySnapshot = false;
                    break;
                }
            }

            if (allCoveredBySnapshot && composed.size() == 6) {
                redisCacheService.put(cacheKey, composed, 60 * 24);
                logger.info("✅ Cost history composed from monthly snapshots for account {}", accountId);
                return composed;
            }
        } catch (Exception e) {
            logger.debug("Monthly snapshot composition failed for {}, falling through to CE API: {}", accountId, e.getMessage());
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate end = (year != null && month != null) ? YearMonth.of(year, month).atEndOfMonth()
                    : LocalDate.now();
            LocalDate start = end.minusMonths(5).withDayOfMonth(1);

            Expression accountFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.LINKED_ACCOUNT)
                            .values(account.getAwsAccountId())
                            .build())
                    .build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(start.toString()).end(end.plusDays(1).toString()).build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .filter(accountFilter)
                    .build();

            List<ResultByTime> results = client.getCostAndUsage(request).resultsByTime();

            List<Map<String, Object>> result = results.stream().map(r -> {
                Map<String, Object> dataPoint = new HashMap<>();
                dataPoint.put("date", r.timePeriod().start());
                dataPoint.put("cost", Double.parseDouble(r.total().get("UnblendedCost").amount()));
                return dataPoint;
            }).collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost history for account {}. Error: {}", account.getAccountName(),
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostByDimension(CloudAccount account, String dimension, Integer year,
            Integer month) {
        return getCostByDimension(account, dimension, year, month, false);
    }

    public List<Map<String, Object>> getCostByDimension(CloudAccount account, String dimension, Integer year,
            Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostByDimension(account, dimension, year, month);
        }

        logger.info("[CostService] getCostByDimension called: account='{}' (awsId='{}'), dimension={}, year={}, month={}",
                account.getAccountName(), account.getAwsAccountId(), dimension, year, month);

        String accountId = account.getAwsAccountId();
        String cacheKey = String.format("BILLOPS_COST_DIMENSION::%s::%s::%s::%s", accountId, dimension, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        // ── Closed-month snapshot: read from absolute monthly breakdown ────────
        // Serves SERVICE or REGION breakdown from snapshot — zero CE API calls.
        if (year != null && month != null) {
            try {
                YearMonth ym = YearMonth.of(year, month);
                if (ym.isBefore(YearMonth.now())) {
                    String snapKey = "SERVICE".equalsIgnoreCase(dimension)
                            ? "AWS_MONTHLY_BREAKDOWN_SVC::" + accountId + "::" + ym
                            : "REGION".equalsIgnoreCase(dimension)
                                    ? "AWS_MONTHLY_BREAKDOWN_RGN::" + accountId + "::" + ym
                                    : null;

                    if (snapKey != null) {
                        Optional<CachedData> snapRow = cachedDataRepository.findById(snapKey);
                        if (snapRow.isPresent()) {
                            // Snapshot stores List<CostDto> {name, amount} — convert to billops format {name, cost}
                            List<Map<String, Object>> snapData = objectMapper.readValue(
                                    snapRow.get().getJsonData(),
                                    new TypeReference<List<Map<String, Object>>>() {});
                            // CostDto field is "amount" — normalize to "cost" expected by BillingService
                            List<Map<String, Object>> result = snapData.stream().map(entry -> {
                                Map<String, Object> m = new HashMap<>();
                                m.put("name", entry.get("name"));
                                Object amtRaw = entry.containsKey("cost") ? entry.get("cost") : entry.get("amount");
                                m.put("cost", amtRaw instanceof Number ? ((Number) amtRaw).doubleValue() : 0.0);
                                return m;
                            }).filter(m -> ((double) m.get("cost")) > 0.01)
                              .sorted(Comparator.comparingDouble((Map<String, Object> m) ->
                                      (double) m.get("cost")).reversed())
                              .collect(Collectors.toList());

                            if (!result.isEmpty()) {
                                redisCacheService.put(cacheKey, result, 60 * 24);
                                logger.info("✅ {} breakdown for {}/{} served from monthly snapshot — account {}",
                                        dimension, year, month, accountId);
                                return result;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Snapshot read skipped for getCostByDimension ({}): {}", accountId, e.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            DateInterval dateInterval = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.plusDays(1).toString())
                    .build();

            // No LINKED_ACCOUNT filter here — getCostByDimension returns org-wide service totals,
            // which correctly reflect the account's billed costs (including all linked accounts under
            // this payer). Filtering by LINKED_ACCOUNT here would only return direct management-account
            // charges (~$15) instead of the real consolidated total (~$2,072).
            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(dateInterval)
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key(dimension).build())
                    .build();

            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());

            double totalCost = result.stream().mapToDouble(m -> (double) m.get("cost")).sum();
            logger.info("[CostService] getCostByDimension result for '{}': {} service(s), total cost = ${}",
                    account.getAwsAccountId(), result.size(), String.format("%.2f", totalCost));

            redisCacheService.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;
        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost by dimension for account {}. Error: {}", account.getAccountName(),
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostForServiceInRegion(CloudAccount account, String serviceName, Integer year,
            Integer month) {
        return getCostForServiceInRegion(account, serviceName, year, month, false);
    }

    public List<Map<String, Object>> getCostForServiceInRegion(CloudAccount account, String serviceName, Integer year,
            Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostForServiceInRegion(account, serviceName, year, month);
        }

        String accountId = account.getAwsAccountId();
        String cacheKey = String.format("BILLOPS_COST_SVC_REGION::%s::%s::%s::%s", accountId, serviceName, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        // ── Closed-month snapshot: filter AWS_MONTHLY_SVC_REGION by service ─────
        // Reads the svc×region breakdown snapshot and extracts regions for this service.
        // Returns [{name: regionName, cost: X}] — same format CE API returns.
        if (year != null && month != null) {
            try {
                YearMonth ym = YearMonth.of(year, month);
                if (ym.isBefore(YearMonth.now())) {
                    String snapKey = "AWS_MONTHLY_SVC_REGION::" + accountId + "::" + ym;
                    Optional<CachedData> snapRow = cachedDataRepository.findById(snapKey);
                    if (snapRow.isPresent()) {
                        // Snapshot stores List<DetailedCostDto> {service, region, cost}
                        List<Map<String, Object>> allSvcRegion = objectMapper.readValue(
                                snapRow.get().getJsonData(),
                                new TypeReference<List<Map<String, Object>>>() {});

                        // Aggregate by region for this specific service
                        Map<String, Double> regionCostMap = new LinkedHashMap<>();
                        for (Map<String, Object> entry : allSvcRegion) {
                            Object svcObj = entry.get("service");
                            if (svcObj == null) continue;
                            String svc = svcObj.toString();
                            // DetailedCostDto field names: "service", "region", "cost"
                            if (!serviceName.equals(svc)) continue;
                            Object regionObj = entry.get("region");
                            Object costObj   = entry.get("cost");
                            if (regionObj == null || costObj == null) continue;
                            String region = regionObj.toString();
                            double cost   = ((Number) costObj).doubleValue();
                            regionCostMap.merge(region, cost, (a, b) -> a + b);
                        }

                        List<Map<String, Object>> result = regionCostMap.entrySet().stream()
                                .filter(e -> e.getValue() > 0.01)
                                .map(e -> {
                                    Map<String, Object> m = new HashMap<>();
                                    m.put("name", e.getKey());
                                    m.put("cost", e.getValue());
                                    return m;
                                })
                                .sorted(Comparator.comparingDouble((Map<String, Object> m) ->
                                        (double) m.get("cost")).reversed())
                                .collect(Collectors.toList());

                        // Cache even if empty (means service truly had no region split)
                        redisCacheService.put(cacheKey, result, 60 * 24);
                        logger.info("✅ Service-region breakdown for '{}' {}/{} served from snapshot — account {}",
                                serviceName, year, month, accountId);
                        return result;
                    }
                }
            } catch (Exception e) {
                logger.debug("Snapshot read skipped for getCostForServiceInRegion ({}): {}", accountId, e.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            DateInterval dateInterval = DateInterval.builder()
                    .start(startDate.toString())
                    .end(endDate.plusDays(1).toString())
                    .build();

            Expression serviceFilter = Expression.builder()
                    .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                    .build();
            Expression accountFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.LINKED_ACCOUNT)
                            .values(account.getAwsAccountId())
                            .build())
                    .build();
            Expression filter = Expression.builder().and(serviceFilter, accountFilter).build();

            GetCostAndUsageRequest request = GetCostAndUsageRequest.builder()
                    .timePeriod(dateInterval)
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost")
                    .filter(filter)
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("REGION").build())
                    .build();

            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", group.keys().get(0));
                        map.put("cost", Double.parseDouble(group.metrics().get("UnblendedCost").amount()));
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0.01)
                    .collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch cost for service '{}' in account {}. Error: {}", serviceName,
                    account.getAccountName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<Map<String, Object>> getCostByResource(CloudAccount account, String serviceName, String region,
            Integer year, Integer month) {
        return getCostByResource(account, serviceName, region, year, month, false);
    }

    public List<Map<String, Object>> getCostByResource(CloudAccount account, String serviceName, String region,
            Integer year, Integer month, boolean forceRefresh) {
        if (useMockData) {
            return costMockService.getMockCostByResource(account, serviceName, region, year, month);
        }

        logger.info("Fetching resource costs for service '{}' in region '{}'", serviceName, region);

        String cacheKey = String.format("BILLOPS_COST_RESOURCE::%s::%s::%s::%s::%s",
                account.getAwsAccountId(), serviceName, region, year, month);

        if (!forceRefresh) {
            Optional<List<Map<String, Object>>> cached = redisCacheService.get(cacheKey, new TypeReference<>() {});
            if (cached.isPresent()) {
                return cached.get();
            }
            try {
                Optional<CachedData> dbCached = cachedDataRepository.findById(cacheKey);
                if (dbCached.isPresent()) {
                    List<Map<String, Object>> dbData = objectMapper.readValue(dbCached.get().getJsonData(), new TypeReference<>() {});
                    if (dbData != null) {
                        redisCacheService.put(cacheKey, dbData, 60 * 24);
                        logger.info("✅ DB snapshot hit — re-warmed Redis: {}", cacheKey);
                        return dbData;
                    }
                }
            } catch (Exception dbEx) {
                logger.warn("DB cache miss for {}: {}", cacheKey, dbEx.getMessage());
            }
        }

        // ── Closed-month snapshot: filter AWS_MONTHLY_SVC_USAGE by service ──────
        // Groups SERVICE×USAGE_TYPE; region filter cannot be applied from snapshot.
        if (year != null && month != null) {
            try {
                YearMonth ym = YearMonth.of(year, month);
                if (ym.isBefore(YearMonth.now())) {
                    String snapKey = "AWS_MONTHLY_SVC_USAGE::" + account.getAwsAccountId() + "::" + ym;
                    Optional<CachedData> snapRow = cachedDataRepository.findById(snapKey);
                    if (snapRow.isPresent()) {
                        List<Map<String, Object>> allSvcUsage = objectMapper.readValue(
                                snapRow.get().getJsonData(),
                                new TypeReference<List<Map<String, Object>>>() {});

                        List<Map<String, Object>> filtered = allSvcUsage.stream()
                                .filter(e -> serviceName.equals(e.get("service")))
                                .map(e -> {
                                    Map<String, Object> m = new HashMap<>();
                                    Object usageType = e.get("usageType");
                                    m.put("name", usageType);
                                    m.put("id", usageType);
                                    Object rawCost = e.get("cost");
                                    m.put("cost", rawCost instanceof Number ? ((Number) rawCost).doubleValue() : 0.0);
                                    Object rawQty = e.get("quantity");
                                    m.put("quantity", rawQty instanceof Number ? ((Number) rawQty).doubleValue() : 0.0);
                                    Object rawUnit = e.get("unit");
                                    m.put("unit", rawUnit != null ? rawUnit.toString() : "");
                                    return m;
                                })
                                .filter(m -> (double) m.get("cost") > 0)
                                .collect(Collectors.toList());

                        if (!filtered.isEmpty()) {
                            logger.info("✅ SvcUsage snapshot hit for {} / {} — {} usage types",
                                    serviceName, ym, filtered.size());
                            return filtered;
                        }
                    }
                }
            } catch (Exception snapEx) {
                logger.warn("SvcUsage snapshot read failed for {}/{}-{}: {}",
                        serviceName, year, month, snapEx.getMessage());
            }
        }

        try {
            CostExplorerClient client = awsClientProvider.getCostExplorerClient(account);
            LocalDate startDate;
            LocalDate endDate;

            if (year != null && month != null) {
                YearMonth yearMonth = YearMonth.of(year, month);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } else {
                endDate = LocalDate.now();
                startDate = endDate.withDayOfMonth(1);
            }

            Expression serviceFilter = Expression.builder()
                    .dimensions(DimensionValues.builder().key(Dimension.SERVICE).values(serviceName).build())
                    .build();
            Expression accountFilter = Expression.builder()
                    .dimensions(DimensionValues.builder()
                            .key(Dimension.LINKED_ACCOUNT)
                            .values(account.getAwsAccountId())
                            .build())
                    .build();

            GetCostAndUsageRequest.Builder requestBuilder = GetCostAndUsageRequest.builder()
                    .timePeriod(DateInterval.builder().start(startDate.toString()).end(endDate.plusDays(1).toString())
                            .build())
                    .granularity(Granularity.MONTHLY)
                    .metrics("UnblendedCost", "UsageQuantity")
                    .groupBy(GroupDefinition.builder().type(GroupDefinitionType.DIMENSION).key("USAGE_TYPE").build());

            if (region != null && !region.isEmpty() && !"Global".equalsIgnoreCase(region)) {
                logger.info("Applying REGIONAL filter for region: {}", region);
                Expression regionFilter = Expression.builder()
                        .dimensions(DimensionValues.builder().key(Dimension.REGION).values(region).build())
                        .build();
                requestBuilder.filter(Expression.builder().and(serviceFilter, regionFilter, accountFilter).build());
            } else {
                logger.info("Applying GLOBAL (service-only) filter.");
                requestBuilder.filter(Expression.builder().and(serviceFilter, accountFilter).build());
            }

            GetCostAndUsageRequest request = requestBuilder.build();
            GetCostAndUsageResponse response = client.getCostAndUsage(request);

            if (response == null || !response.hasResultsByTime() || response.resultsByTime().isEmpty()) {
                return Collections.emptyList();
            }

            List<Map<String, Object>> result = response.resultsByTime().get(0).groups().stream()
                    .map(group -> {
                        Map<String, Object> map = new HashMap<>();
                        String usageType = group.keys().get(0);
                        MetricValue costMetric = group.metrics().get("UnblendedCost");
                        MetricValue usageMetric = group.metrics().get("UsageQuantity");

                        map.put("name", usageType);
                        map.put("id", usageType);
                        map.put("cost", Double.parseDouble(costMetric.amount()));
                        // MODIFIED: Added quantity and unit
                        map.put("quantity", usageMetric != null ? Double.parseDouble(usageMetric.amount()) : 0.0);
                        map.put("unit", usageMetric != null ? usageMetric.unit() : "");
                        return map;
                    })
                    .filter(map -> (double) map.get("cost") > 0)
                    .collect(Collectors.toList());

            redisCacheService.put(cacheKey, result, 60 * 24);
            try {
                CachedData snapshot = cachedDataRepository.findById(cacheKey).orElse(new CachedData());
                snapshot.setCacheKey(cacheKey);
                snapshot.setJsonData(objectMapper.writeValueAsString(result));
                snapshot.setLastUpdated(java.time.LocalDateTime.now());
                cachedDataRepository.save(snapshot);
            } catch (Exception dbEx) {
                logger.warn("Failed to save DB snapshot {}: {}", cacheKey, dbEx.getMessage());
            }
            return result;

        } catch (CostExplorerException e) {
            logger.warn("Could not fetch resource costs for service '{}' in region '{}'. Error: {}", serviceName,
                    region, e.getMessage());
            return Collections.emptyList();
        }
    }
}
