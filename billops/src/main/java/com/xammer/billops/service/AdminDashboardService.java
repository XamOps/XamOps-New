package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.AccountSummaryDto;
import com.xammer.billops.dto.AdminDashboardDto;
import com.xammer.billops.dto.UserDashboardDto;
import com.xammer.billops.dto.BillingDashboardDto;
import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.repository.BillOpsCachedDataRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.util.SecurityUtils;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

@Service
public class AdminDashboardService {

    private static final Logger logger = LoggerFactory.getLogger(AdminDashboardService.class);

    @PostConstruct
    public void init() {
        logger.info("✅ AdminDashboardService initialized");
    }

    private final CloudAccountRepository cloudAccountRepository;
    private final InvoiceRepository invoiceRepository;
    private final TicketRepository ticketRepository;
    private final ClientRepository clientRepository;
    private final RedisCacheService redisCache;
    private final AccountAccessHelper accountAccessHelper;
    private final BillOpsCachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;

    private static final String CACHE_KEY = "billops:admin:dashboard";
    private static final String DB_CACHE_KEY_PREFIX = "BILLOPS_ADMIN_DASHBOARD::";
    private static final String TOP_SPENDING_DB_KEY = "BILLOPS_TOP_SPENDING::system";
    private static final long CACHE_TTL_MINUTES = 120;
    /**
     * A DB snapshot is only worth serving while it is no older than the Redis TTL. Past that it is
     * treated as a miss and rebuilt — otherwise a Redis miss re-warms from an arbitrarily old
     * snapshot and the dashboard freezes at whatever the last build produced, which is how two
     * admins end up looking at counts from different days.
     */
    private static final long SNAPSHOT_MAX_AGE_MINUTES = CACHE_TTL_MINUTES;

    private final BillingService billingService;
    private final CostService costService;

    public AdminDashboardService(
            InvoiceRepository invoiceRepository,
            CloudAccountRepository cloudAccountRepository,
            TicketRepository ticketRepository,
            ClientRepository clientRepository,
            RedisCacheService redisCacheService,
            BillingService billingService,
            CostService costService,
            AccountAccessHelper accountAccessHelper,
            BillOpsCachedDataRepository cachedDataRepository,
            ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.ticketRepository = ticketRepository;
        this.clientRepository = clientRepository;
        this.redisCache = redisCacheService;
        this.billingService = billingService;
        this.costService = costService;
        this.accountAccessHelper = accountAccessHelper;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
    }

    private void saveToDb(String dbKey, AdminDashboardDto data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            CachedData entity = new CachedData();
            entity.setCacheKey(dbKey);
            entity.setJsonData(json);
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            logger.debug("Saved admin dashboard snapshot to DB: {}", dbKey);
        } catch (Exception e) {
            logger.warn("Failed to save admin dashboard snapshot to DB for {}: {}", dbKey, e.getMessage());
        }
    }

    private String getCacheKey() {
        return CACHE_KEY + ":system";
    }

    /** True while a DB snapshot is recent enough to serve instead of rebuilding. */
    private boolean isSnapshotFresh(CachedData entry) {
        LocalDateTime lastUpdated = entry.getLastUpdated();
        if (lastUpdated == null) return false;
        return lastUpdated.isAfter(
                LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(SNAPSHOT_MAX_AGE_MINUTES));
    }

    @Transactional
    public AdminDashboardDto getAdminDashboardSummary(boolean forceRefresh) {
        // Sub-users get a custom filtered view — never served from (or stored to) the global cache
        List<CloudAccount> filteredAccounts = accountAccessHelper.filterAccounts(cloudAccountRepository.findAll());
        boolean isSubUser = accountAccessHelper.getAllowedDbIds() != null;

        if (!isSubUser) {
            // Primary admin: 3-layer cache — Redis → DB snapshot → live build
            String tenantCacheKey = getCacheKey();
            String dbKey = DB_CACHE_KEY_PREFIX + "system";

            if (!forceRefresh) {
                // 1. Try Redis
                Optional<AdminDashboardDto> cached = redisCache.get(tenantCacheKey, AdminDashboardDto.class);
                if (cached.isPresent()) {
                    logger.info("Returning Redis-cached admin dashboard for tenant: {}", tenantCacheKey);
                    return cached.get();
                }
                // 2. Try DB snapshot — only while it is still fresh, otherwise fall through and rebuild
                Optional<CachedData> dbEntry = cachedDataRepository.findById(dbKey);
                if (dbEntry.isPresent() && isSnapshotFresh(dbEntry.get())) {
                    try {
                        AdminDashboardDto dbData = objectMapper.readValue(dbEntry.get().getJsonData(), AdminDashboardDto.class);
                        redisCache.put(tenantCacheKey, dbData, CACHE_TTL_MINUTES);
                        logger.info("Returning DB-cached admin dashboard for tenant {}, re-warmed Redis", tenantCacheKey);
                        return dbData;
                    } catch (Exception e) {
                        logger.warn("Failed to deserialize DB admin dashboard snapshot for {}: {}", dbKey, e.getMessage());
                    }
                } else if (dbEntry.isPresent()) {
                    logger.info("DB admin dashboard snapshot {} older than {} min (lastUpdated={}) — rebuilding",
                            dbKey, SNAPSHOT_MAX_AGE_MINUTES, dbEntry.get().getLastUpdated());
                }
            }

            // 3. Build from source (DB aggregation + BillingService)
            logger.info("Building admin dashboard summary for tenant {}", tenantCacheKey);
            AdminDashboardDto dashboard = buildDashboard(filteredAccounts);
            dashboard.setLastUpdated(System.currentTimeMillis());
            redisCache.put(tenantCacheKey, dashboard, CACHE_TTL_MINUTES);
            saveToDb(dbKey, dashboard);
            logger.info("Admin dashboard cached for tenant {} (Redis {}min + DB snapshot)", tenantCacheKey, CACHE_TTL_MINUTES);
            return dashboard;
        }

        // Sub-user: 3-layer cache keyed by userId
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            String userCacheKey = CACHE_KEY + ":user:" + userId;
            String userDbKey = DB_CACHE_KEY_PREFIX + "user::" + userId;

            if (!forceRefresh) {
                Optional<AdminDashboardDto> cached = redisCache.get(userCacheKey, AdminDashboardDto.class);
                if (cached.isPresent()) {
                    logger.info("Returning Redis-cached sub-user dashboard for user {}", userId);
                    return cached.get();
                }
                Optional<CachedData> dbEntry = cachedDataRepository.findById(userDbKey);
                if (dbEntry.isPresent() && isSnapshotFresh(dbEntry.get())) {
                    try {
                        AdminDashboardDto dbData = objectMapper.readValue(dbEntry.get().getJsonData(), AdminDashboardDto.class);
                        redisCache.put(userCacheKey, dbData, CACHE_TTL_MINUTES);
                        logger.info("Returning DB-cached sub-user dashboard for user {}, re-warmed Redis", userId);
                        return dbData;
                    } catch (Exception e) {
                        logger.warn("Failed to deserialize sub-user DB dashboard for user {}: {}", userId, e.getMessage());
                    }
                } else if (dbEntry.isPresent()) {
                    logger.info("DB sub-user dashboard snapshot {} older than {} min (lastUpdated={}) — rebuilding",
                            userDbKey, SNAPSHOT_MAX_AGE_MINUTES, dbEntry.get().getLastUpdated());
                }
            }

            logger.info("Building filtered admin dashboard summary for sub-user {}", userId);
            AdminDashboardDto dashboard = buildDashboard(filteredAccounts);
            dashboard.setLastUpdated(System.currentTimeMillis());
            redisCache.put(userCacheKey, dashboard, CACHE_TTL_MINUTES);
            saveToDb(userDbKey, dashboard);
            return dashboard;
        } catch (Exception e) {
            logger.warn("Sub-user cache key resolution failed, serving live: {}", e.getMessage());
            AdminDashboardDto dashboard = buildDashboard(filteredAccounts);
            dashboard.setLastUpdated(System.currentTimeMillis());
            return dashboard;
        }
    }

    private AdminDashboardDto buildDashboard(List<CloudAccount> allAccounts) {
        AdminDashboardDto dashboard = new AdminDashboardDto();
        dashboard.setTotalAccounts((long) allAccounts.size());

        // Collect account IDs and client IDs visible to this user
        Set<Long> visibleAccountIds = allAccounts.stream()
                .map(CloudAccount::getId)
                .collect(Collectors.toSet());
        Set<Long> visibleClientIds = allAccounts.stream()
                .filter(a -> a.getClient() != null)
                .map(a -> a.getClient().getId())
                .collect(Collectors.toSet());
        // null = primary admin (no restriction — use full DB counts)
        boolean isRestricted = accountAccessHelper.getAllowedDbIds() != null;

        // Count by provider
        long awsCount = allAccounts.stream().filter(a -> "AWS".equalsIgnoreCase(a.getProvider())).count();
        long gcpCount = allAccounts.stream().filter(a -> "GCP".equalsIgnoreCase(a.getProvider())).count();
        long azureCount = allAccounts.stream().filter(a -> "Azure".equalsIgnoreCase(a.getProvider())).count();

        dashboard.setAwsAccountsCount(awsCount);
        dashboard.setGcpAccountsCount(gcpCount);
        dashboard.setAzureAccountsCount(azureCount);

        // Count by status - will be set later after creating account summaries
        Map<String, Long> accountsByStatus = allAccounts.stream()
                .collect(Collectors.groupingBy(
                        acc -> acc.getStatus() != null ? acc.getStatus() : "UNKNOWN",
                        Collectors.counting()));
        dashboard.setAccountsByStatus(accountsByStatus);

        // Get invoice counts — scoped to visible accounts for sub-admins
        List<Invoice> allInvoices = invoiceRepository.findAll();
        List<Invoice> scopedInvoices = isRestricted
                ? allInvoices.stream()
                        .filter(inv -> inv.getCloudAccount() != null && visibleAccountIds.contains(inv.getCloudAccount().getId()))
                        .collect(Collectors.toList())
                : allInvoices;
        long pendingInvoices = scopedInvoices.stream()
                .filter(inv -> inv.getStatus() == Invoice.InvoiceStatus.DRAFT)
                .count();
        long finalizedInvoices = scopedInvoices.stream()
                .filter(inv -> inv.getStatus() == Invoice.InvoiceStatus.FINALIZED)
                .count();
        dashboard.setPendingInvoices(pendingInvoices);
        dashboard.setFinalizedInvoices(finalizedInvoices);

        // Get open tickets count — scoped to visible clients for sub-admins
        List<Ticket> allTickets = ticketRepository.findAll();
        List<Ticket> scopedTickets = isRestricted
                ? allTickets.stream()
                        .filter(t -> t.getClient() != null && visibleClientIds.contains(t.getClient().getId()))
                        .collect(Collectors.toList())
                : allTickets;
        long openTickets = scopedTickets.stream()
                .filter(t -> "OPEN".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus()))
                .count();
        dashboard.setOpenTickets(openTickets);

        // Get total clients — scoped to visible clients for sub-admins
        long totalClients = isRestricted ? visibleClientIds.size() : clientRepository.count();
        dashboard.setTotalClients(totalClients);

        // Calculate spending for all accounts
        YearMonth currentMonth = YearMonth.now();
        YearMonth lastMonth = currentMonth.minusMonths(1);

        List<AccountSummaryDto> accountSummaries = allAccounts.stream()
                .map(account -> {
                    BigDecimal currentSpending = calculateAccountSpending(account, currentMonth);
                    BigDecimal lastSpending = calculateAccountSpending(account, lastMonth);
                    LocalDate lastInvoiceDate = getLastInvoiceDate(account);
                    return convertToAccountSummary(account, currentSpending, lastSpending, lastInvoiceDate);
                })
                .collect(Collectors.toList());

        // Calculate total spending
        BigDecimal totalSpending = accountSummaries.stream()
                .map(acc -> acc.getCurrentMonthSpending() != null ? acc.getCurrentMonthSpending() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        dashboard.setTotalSpending(totalSpending);

        // Top 10 spending accounts
        List<AccountSummaryDto> topAccounts = accountSummaries.stream()
                .sorted((a1, a2) -> {
                    BigDecimal s1 = a1.getCurrentMonthSpending() != null ? a1.getCurrentMonthSpending()
                            : BigDecimal.ZERO;
                    BigDecimal s2 = a2.getCurrentMonthSpending() != null ? a2.getCurrentMonthSpending()
                            : BigDecimal.ZERO;
                    return s2.compareTo(s1);
                })
                .limit(10)
                .collect(Collectors.toList());
        dashboard.setTopSpendingAccounts(topAccounts);

        // Spending by provider
        Map<String, BigDecimal> spendingByProvider = new HashMap<>();
        spendingByProvider.put("AWS", BigDecimal.ZERO);
        spendingByProvider.put("GCP", BigDecimal.ZERO);
        spendingByProvider.put("Azure", BigDecimal.ZERO);

        for (AccountSummaryDto account : accountSummaries) {
            BigDecimal spending = account.getCurrentMonthSpending() != null ? account.getCurrentMonthSpending()
                    : BigDecimal.ZERO;
            spendingByProvider.merge(account.getProvider(), spending, BigDecimal::add);
        }
        dashboard.setSpendingByProvider(spendingByProvider);

        // Recent invoices (last 10)
        List<Invoice> recentInvoiceEntities = invoiceRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "invoiceDate"))).getContent();

        List<InvoiceDto> recentInvoices = recentInvoiceEntities.stream()
                .map(this::convertToInvoiceDto)
                .collect(Collectors.toList());
        dashboard.setRecentInvoices(recentInvoices);

        // Recent tickets (last 10)
        List<Ticket> recentTicketEntities = ticketRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        List<TicketDto> recentTickets = recentTicketEntities.stream()
                .map(this::convertToTicketDto)
                .collect(Collectors.toList());
        dashboard.setRecentTickets(recentTickets);

        // Spending trend (last 6 months)
        dashboard.setSpendingTrend(calculateSpendingTrend(allAccounts, 6));

        return dashboard;
    }

    /**
     * Fetches live current-month MTD cost from CE API for every AWS account,
     * reuses cached data for GCP/Azure, sorts and returns the top 10.
     * Persists the result to DB so subsequent full-dashboard loads stay fresh.
     */
    @Transactional
    public List<AccountSummaryDto> refreshTopSpendingAccountsLive() {
        List<CloudAccount> allAccounts = accountAccessHelper.filterAccounts(cloudAccountRepository.findAll());
        YearMonth currentMonth = YearMonth.now();
        YearMonth lastMonth = currentMonth.minusMonths(1);

        List<AccountSummaryDto> accountSummaries = allAccounts.stream()
                .map(account -> {
                    BigDecimal currentSpending = fetchLiveCurrentMonthCost(account, currentMonth);
                    BigDecimal lastSpending = calculateAccountSpending(account, lastMonth);
                    LocalDate lastInvoiceDate = getLastInvoiceDate(account);
                    return convertToAccountSummary(account, currentSpending, lastSpending, lastInvoiceDate);
                })
                .collect(Collectors.toList());

        List<AccountSummaryDto> top10 = accountSummaries.stream()
                .sorted((a1, a2) -> {
                    BigDecimal s1 = a1.getCurrentMonthSpending() != null ? a1.getCurrentMonthSpending() : BigDecimal.ZERO;
                    BigDecimal s2 = a2.getCurrentMonthSpending() != null ? a2.getCurrentMonthSpending() : BigDecimal.ZERO;
                    return s2.compareTo(s1);
                })
                .limit(10)
                .collect(Collectors.toList());

        // Persist top 10 to DB under its own key
        try {
            CachedData entity = cachedDataRepository.findById(TOP_SPENDING_DB_KEY).orElse(new CachedData());
            entity.setCacheKey(TOP_SPENDING_DB_KEY);
            entity.setJsonData(objectMapper.writeValueAsString(top10));
            entity.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            cachedDataRepository.save(entity);
            logger.info("✅ Saved live top-spending snapshot to DB: {}", TOP_SPENDING_DB_KEY);
        } catch (Exception e) {
            logger.warn("Failed to save top-spending snapshot: {}", e.getMessage());
        }

        // Patch the full cached AdminDashboardDto in Redis so the 30s background poll
        // doesn't overwrite the fresh top 10 with the stale DB snapshot.
        // We update only topSpendingAccounts — other KPI fields (totalSpending, counts) are left intact.
        try {
            String redisCacheKey = getCacheKey();
            Optional<AdminDashboardDto> cachedDashboard = redisCache.get(redisCacheKey, AdminDashboardDto.class);
            AdminDashboardDto dashToUpdate = cachedDashboard.orElseGet(() -> {
                // Redis was cold — try DB snapshot
                try {
                    Optional<CachedData> dbEntry = cachedDataRepository.findById(DB_CACHE_KEY_PREFIX + "system");
                    if (dbEntry.isPresent()) {
                        return objectMapper.readValue(dbEntry.get().getJsonData(), AdminDashboardDto.class);
                    }
                } catch (Exception ignored) {}
                return new AdminDashboardDto();
            });

            dashToUpdate.setTopSpendingAccounts(top10);
            // Recompute totalSpending from all accounts (AWS live via CE, Azure/GCP from Redis/DB cache)
            BigDecimal freshTotal = accountSummaries.stream()
                    .map(a -> a.getCurrentMonthSpending() != null ? a.getCurrentMonthSpending() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            dashToUpdate.setTotalSpending(freshTotal);
            dashToUpdate.setLastUpdated(System.currentTimeMillis());

            redisCache.put(redisCacheKey, dashToUpdate, CACHE_TTL_MINUTES);
            saveToDb(DB_CACHE_KEY_PREFIX + "system", dashToUpdate);
            logger.info("✅ Patched full admin dashboard cache with fresh top-spending data");
        } catch (Exception e) {
            logger.warn("Failed to patch admin dashboard cache after top-spending refresh: {}", e.getMessage());
        }

        return top10;
    }

    /**
     * For AWS: forces a live CE API call (current month only, 1 CE call per account).
     * For GCP/Azure: reuses existing DB cache — their schedulers keep those fresh.
     * Also writes the fresh value to AWS_MTD_COST::{accountId} in DB.
     */
    private BigDecimal fetchLiveCurrentMonthCost(CloudAccount account, YearMonth currentMonth) {
        String provider = account.getProvider();

        if ("GCP".equalsIgnoreCase(provider) || "Azure".equalsIgnoreCase(provider)) {
            return calculateAccountSpending(account, currentMonth);
        }

        // AWS — hit CE API directly via billops CostService (forceRefresh=true)
        String accountId = account.getAwsAccountId();
        if (accountId == null) return BigDecimal.ZERO;

        try {
            List<Map<String, Object>> history = costService.getCostHistory(
                    account, currentMonth.getYear(), currentMonth.getMonthValue(), true);

            if (history != null && !history.isEmpty()) {
                double total = ((Number) history.get(history.size() - 1).getOrDefault("cost", 0.0)).doubleValue();

                // Write fresh value to AWS_MTD_COST so calculateAccountSpending stays in sync
                try {
                    CachedData snap = cachedDataRepository.findById("AWS_MTD_COST::" + accountId).orElse(new CachedData());
                    snap.setCacheKey("AWS_MTD_COST::" + accountId);
                    snap.setJsonData(objectMapper.writeValueAsString(total));
                    snap.setLastUpdated(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
                    cachedDataRepository.save(snap);
                    logger.info("✅ Updated AWS_MTD_COST::{} = {}", accountId, total);
                } catch (Exception e) {
                    logger.debug("Failed to update AWS_MTD_COST for {}: {}", accountId, e.getMessage());
                }

                return BigDecimal.valueOf(total);
            }
        } catch (Exception e) {
            logger.warn("Live CE fetch failed for {}, falling back to cache: {}", accountId, e.getMessage());
        }

        return calculateAccountSpending(account, currentMonth);
    }

    private List<UserDashboardDto.CostHistory> calculateSpendingTrend(List<CloudAccount> accounts, int months) {
        List<UserDashboardDto.CostHistory> trend = new ArrayList<>();
        YearMonth currentMonth = YearMonth.now();

        for (int i = months - 1; i >= 0; i--) {
            YearMonth month = currentMonth.minusMonths(i);
            double totalCost = 0.0;
            Map<String, Double> costByProvider = new HashMap<>();
            costByProvider.put("AWS", 0.0);
            costByProvider.put("GCP", 0.0);
            costByProvider.put("Azure", 0.0);

            for (CloudAccount account : accounts) {
                BigDecimal cost = calculateAccountSpending(account, month);
                totalCost += (cost != null ? cost.doubleValue() : 0.0);

                String provider = account.getProvider() != null ? account.getProvider().toUpperCase() : "AWS";
                costByProvider.merge(provider, (cost != null ? cost.doubleValue() : 0.0), Double::sum);
            }

            trend.add(new UserDashboardDto.CostHistory(month.toString(), totalCost, costByProvider));
        }

        return trend;
    }

    private BigDecimal calculateAccountSpending(CloudAccount account, YearMonth month) {
        String provider = account.getProvider();
        boolean isCurrentMonth = month.equals(YearMonth.now());

        // ── GCP ──────────────────────────────────────────────────────────────────────
        if ("GCP".equalsIgnoreCase(provider)) {
            String gcpProjectId = account.getGcpProjectId();
            if (gcpProjectId == null) return BigDecimal.ZERO;
            // GCP_MONTHLY_TOTAL is updated daily (2:05 AM GCP refresh), covers all months.
            String gcpKey = "GCP_MONTHLY_TOTAL::" + gcpProjectId + "::" + month;
            try {
                Optional<CachedData> snap = cachedDataRepository.findById(gcpKey);
                if (snap.isPresent()) {
                    Double total = objectMapper.readValue(snap.get().getJsonData(), Double.class);
                    if (total != null) return BigDecimal.valueOf(total);
                }
            } catch (Exception e) {
                logger.debug("GCP_MONTHLY_TOTAL read failed for {}/{}: {}", gcpProjectId, month, e.getMessage());
            }
            return BigDecimal.ZERO;
        }

        // ── Azure ─────────────────────────────────────────────────────────────────────
        if ("Azure".equalsIgnoreCase(provider)) {
            String subscriptionId = account.getAzureSubscriptionId();
            if (subscriptionId == null) return BigDecimal.ZERO;

            if (isCurrentMonth) {
                String azureKey = "AZURE_COST_BILLING_SUMMARY::" + subscriptionId;
                try {
                    Optional<CachedData> snap = cachedDataRepository.findById(azureKey);
                    if (snap.isPresent()) {
                        List<Map<String, Object>> summary = objectMapper.readValue(
                                snap.get().getJsonData(), new TypeReference<List<Map<String, Object>>>() {});
                        double total = summary.stream()
                                .mapToDouble(s -> ((Number) s.getOrDefault("monthToDateCost", 0.0)).doubleValue())
                                .sum();
                        if (total > 0) return BigDecimal.valueOf(total);
                    } else {
                        logger.warn("Azure DB key not found: {}", azureKey);
                    }
                } catch (Exception e) {
                    logger.warn("AZURE_COST_BILLING_SUMMARY read failed for {}: {}", subscriptionId, e.getMessage());
                }
            } else {
                // For past months, read from cost history. Labels are formatted "MMM yyyy" (e.g. "Mar 2026").
                String historyKey = "AZURE_COST_HISTORY::" + subscriptionId;
                String monthLabel = month.format(DateTimeFormatter.ofPattern("MMM yyyy"));
                try {
                    Optional<CachedData> snap = cachedDataRepository.findById(historyKey);
                    if (snap.isPresent()) {
                        Map<String, Object> history = objectMapper.readValue(
                                snap.get().getJsonData(), new TypeReference<Map<String, Object>>() {});
                        @SuppressWarnings("unchecked")
                        List<String> labels = (List<String>) history.get("labels");
                        @SuppressWarnings("unchecked")
                        List<Number> costs = (List<Number>) history.get("costs");
                        if (labels != null && costs != null) {
                            for (int i = 0; i < labels.size() && i < costs.size(); i++) {
                                if (monthLabel.equals(labels.get(i))) {
                                    return BigDecimal.valueOf(costs.get(i).doubleValue());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("AZURE_COST_HISTORY read failed for {}/{}: {}", subscriptionId, month, e.getMessage());
                }
            }
            return BigDecimal.ZERO;
        }

        // ── AWS ───────────────────────────────────────────────────────────────────────
        String accountId = account.getAwsAccountId();
        if (accountId == null) return BigDecimal.ZERO;

        String breakdownPrefix = "AWS_COST_BREAKDOWN::" + accountId + "::SERVICE::::" + month.atDay(1) + "::";

        if (isCurrentMonth) {
            // 1a. Date-scoped AWS_COST_BREAKDOWN — the exact key (and value) that
            //     BillOpsAdminController#buildClientDtos reads for the Client Details page and the
            //     admin dashboard spend tile, refreshed on every Cost/FinOps visit. This has to come
            //     first: the two endpoints must resolve the same number for the same account, and
            //     the alternatives below are only refreshed WED/SUN. The row is trusted even when it
            //     sums to 0, matching buildClientDtos.
            String breakdownKey = breakdownPrefix + month.atEndOfMonth();
            try {
                Optional<CachedData> breakdown = cachedDataRepository.findById(breakdownKey);
                if (breakdown.isPresent()) {
                    List<Map<String, Object>> costs = objectMapper.readValue(
                            breakdown.get().getJsonData(), new TypeReference<List<Map<String, Object>>>() {});
                    double total = costs.stream()
                            .mapToDouble(c -> ((Number) c.getOrDefault("amount", 0.0)).doubleValue())
                            .sum();
                    return BigDecimal.valueOf(total);
                }
            } catch (Exception e) {
                logger.debug("AWS_COST_BREAKDOWN read failed for {}/{}: {}", accountId, month, e.getMessage());
            }

            // 1b. AWS_MTD_COST — populated on every dashboard visit and by the WED/SUN scheduler.
            String mtdKey = "AWS_MTD_COST::" + accountId;
            try {
                Optional<CachedData> mtd = cachedDataRepository.findById(mtdKey);
                if (mtd.isPresent()) {
                    Double total = objectMapper.readValue(mtd.get().getJsonData(), Double.class);
                    if (total != null && total > 0) return BigDecimal.valueOf(total);
                }
            } catch (Exception e) {
                logger.debug("AWS_MTD_COST read failed for {}: {}", accountId, e.getMessage());
            }

            // 1c. Any breakdown row for this month, whatever its end date.
            try {
                Optional<CachedData> breakdown = cachedDataRepository.findFirstByCacheKeyStartingWith(breakdownPrefix);
                if (breakdown.isPresent()) {
                    List<Map<String, Object>> costs = objectMapper.readValue(
                            breakdown.get().getJsonData(), new TypeReference<List<Map<String, Object>>>() {});
                    double total = costs.stream()
                            .mapToDouble(c -> ((Number) c.getOrDefault("amount", 0.0)).doubleValue())
                            .sum();
                    if (total > 0) return BigDecimal.valueOf(total);
                }
            } catch (Exception e) {
                logger.debug("AWS_COST_BREAKDOWN prefix read failed for {}/{}: {}", accountId, month, e.getMessage());
            }

            // 1d. DASHBOARD_DATA::{tenant}::{accountId} — last resort. Written by
            //     refreshAwsDashboardData() at WED/SUN 3:30 AM, so it can be days behind.
            try {
                Optional<CachedData> dashEntry = cachedDataRepository
                        .findFirstByCacheKeyStartingWithAndCacheKeyEndingWith("DASHBOARD_DATA::", "::" + accountId);
                if (dashEntry.isPresent()) {
                    Map<String, Object> dashMap = objectMapper.readValue(
                            dashEntry.get().getJsonData(), new TypeReference<Map<String, Object>>() {});
                    Object selectedAccount = dashMap.get("selectedAccount");
                    if (selectedAccount instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> accMap = (Map<String, Object>) selectedAccount;
                        Object mtd = accMap.get("monthToDateSpend");
                        if (mtd instanceof Number && ((Number) mtd).doubleValue() > 0) {
                            return BigDecimal.valueOf(((Number) mtd).doubleValue());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("DASHBOARD_DATA read failed for {}: {}", accountId, e.getMessage());
            }
        }

        // 2. Try AWS_MONTHLY_TOTAL — authoritative for closed months; WED/SUN for current.
        String monthlyKey = "AWS_MONTHLY_TOTAL::" + accountId + "::" + month;
        try {
            Optional<CachedData> snap = cachedDataRepository.findById(monthlyKey);
            if (snap.isPresent()) {
                Double total = objectMapper.readValue(snap.get().getJsonData(), Double.class);
                if (total != null) return BigDecimal.valueOf(total);
            }
        } catch (Exception e) {
            logger.debug("AWS_MONTHLY_TOTAL read failed for {}/{}: {}", accountId, month, e.getMessage());
        }

        // 3. For closed months, also try breakdown as a fallback (if MONTHLY_TOTAL missing).
        if (!isCurrentMonth) {
            try {
                Optional<CachedData> breakdown = cachedDataRepository.findFirstByCacheKeyStartingWith(breakdownPrefix);
                if (breakdown.isPresent()) {
                    List<Map<String, Object>> costs = objectMapper.readValue(
                            breakdown.get().getJsonData(), new TypeReference<List<Map<String, Object>>>() {});
                    double total = costs.stream()
                            .mapToDouble(c -> ((Number) c.getOrDefault("amount", 0.0)).doubleValue())
                            .sum();
                    return BigDecimal.valueOf(total);
                }
            } catch (Exception e) {
                logger.debug("AWS_COST_BREAKDOWN prefix read failed for {}/{}: {}", accountId, month, e.getMessage());
            }
        }

        // 4. Fallback: BillingService — uses its own Redis/DB cache before hitting CE API
        try {
            BillingDashboardDto billingData = billingService.getAccountBillingSummary(
                    account, month.getYear(), month.getMonthValue());
            if (billingData != null && billingData.getServiceBreakdown() != null) {
                return billingData.getServiceBreakdown().stream()
                        .map(s -> BigDecimal.valueOf(s.getCost()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
        } catch (Exception e) {
            logger.warn("Error calculating spending for account {}: {}", account.getAccountName(), e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    private LocalDate getLastInvoiceDate(CloudAccount account) {
        try {
            return invoiceRepository
                    .findTop1ByCloudAccountIdOrderByInvoiceDateDesc(account.getId())
                    .map(Invoice::getInvoiceDate)
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Error getting last invoice date for account {}: {}", account.getAccountName(), e.getMessage());
        }
        return null;
    }

    private AccountSummaryDto convertToAccountSummary(CloudAccount account, BigDecimal currentMonthSpending,
            BigDecimal lastMonthSpending, LocalDate lastInvoiceDate) {
        AccountSummaryDto dto = new AccountSummaryDto();
        dto.setId(account.getId());
        dto.setAccountName(account.getAccountName());
        dto.setProvider(account.getProvider());
        dto.setStatus(account.getStatus());

        // Set account identifier based on provider
        if ("AWS".equalsIgnoreCase(account.getProvider())) {
            dto.setAccountIdentifier(account.getAwsAccountId());
        } else if ("GCP".equalsIgnoreCase(account.getProvider())) {
            dto.setAccountIdentifier(account.getGcpProjectId());
        } else if ("Azure".equalsIgnoreCase(account.getProvider())) {
            dto.setAccountIdentifier(account.getAzureSubscriptionId());
        }

        dto.setCurrentMonthSpending(currentMonthSpending);
        dto.setLastMonthSpending(lastMonthSpending);
        dto.setLastInvoiceDate(lastInvoiceDate);
        dto.setClientName(account.getClient() != null ? account.getClient().getName() : "N/A");
        dto.setClientId(account.getClient() != null ? account.getClient().getId() : null);

        return dto;
    }

    private InvoiceDto convertToInvoiceDto(Invoice invoice) {
        InvoiceDto dto = new InvoiceDto();
        dto.setId(invoice.getId());
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setInvoiceDate(invoice.getInvoiceDate());
        dto.setBillingPeriod(invoice.getBillingPeriod());
        dto.setStatus(invoice.getStatus().name());
        dto.setAmount(invoice.getAmount());
        dto.setPreDiscountTotal(invoice.getPreDiscountTotal());
        dto.setDiscountAmount(invoice.getDiscountAmount());
        dto.setTaxAmount(invoice.getTaxAmount());

        if (invoice.getCloudAccount() != null) {
            dto.setAccountName(invoice.getCloudAccount().getAccountName());
            dto.setProvider(invoice.getCloudAccount().getProvider());
        }
        if (invoice.getClient() != null) {
            dto.setClientName(invoice.getClient().getName());
        }

        return dto;
    }

    private TicketDto convertToTicketDto(Ticket ticket) {
        TicketDto dto = new TicketDto();
        dto.setId(ticket.getId());
        dto.setSubject(ticket.getSubject());
        dto.setDescription(ticket.getDescription());
        dto.setStatus(ticket.getStatus());
        dto.setCategory(ticket.getCategory());
        dto.setSeverity(ticket.getSeverity());
        dto.setCreatedAt(ticket.getCreatedAt());

        if (ticket.getCreator() != null) {
            dto.setCreatorId(ticket.getCreator().getId());
        }

        return dto;
    }
}
