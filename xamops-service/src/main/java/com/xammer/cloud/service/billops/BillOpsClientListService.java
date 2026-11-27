package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.billops.BillOpsAccountDto;
import com.xammer.cloud.dto.billops.BillOpsClientDetailsDto;
import com.xammer.cloud.dto.billops.BillOpsUserDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import com.xammer.cloud.repository.UpwindInventoryAssetRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.azure.AzureCostService;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Builds the BillOps admin client list: one {@link BillOpsClientDetailsDto} per client, with its
 * accounts, users, month-to-date spend and Upwind (XamSecure) estate.
 *
 * <p>This is the only builder of that list. {@code BillOpsAdminController} builds it on a cache
 * miss, {@code BillOpsClientsCacheWarmingService} rebuilds it every few minutes, and both store it
 * under {@link #CLIENTS_CACHE_KEY}, which is what the Accounts page is served from. The warmer used
 * to keep its own copy of this code, and that copy never gained the Upwind fields. It rewrites the
 * key before the key can expire, so its copy was the one the page almost always showed: every
 * client read as having no AWS accounts, and the Security column said "n/a" for all of them.
 * One builder means the two callers cannot drift apart again.
 */
@Service
public class BillOpsClientListService {

    /**
     * Redis key for the primary admins' client list. Sub-admins are cached separately, under
     * {@code CLIENTS_CACHE_KEY + ":sub:" + userId}. The billops module evicts by this prefix too
     * ({@code AppUserService}), so a change here has to be made there as well.
     */
    public static final String CLIENTS_CACHE_KEY = "billops:admin:clients";

    public static final int CLIENTS_CACHE_TTL_MINUTES = 5;

    private static final Logger log = LoggerFactory.getLogger(BillOpsClientListService.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final UserRepository userRepository;
    private final UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    private final CachedDataRepository cachedDataRepository;
    private final ObjectMapper objectMapper;
    private final CostService costService;
    private final GcpCostService gcpCostService;
    private final AzureCostService azureCostService;
    // Upwind estate, for the two admin columns. Same Spring context — the Upwind tables are owned
    // by this module, so no cross-service call is involved.
    private final UpwindAccountConnectionRepository upwindConnectionRepository;
    private final UpwindInventoryAssetRepository upwindInventoryRepository;

    public BillOpsClientListService(CloudAccountRepository cloudAccountRepository,
                                    UserRepository userRepository,
                                    UserCloudAccountAccessRepository userCloudAccountAccessRepository,
                                    CachedDataRepository cachedDataRepository,
                                    ObjectMapper objectMapper,
                                    CostService costService,
                                    GcpCostService gcpCostService,
                                    AzureCostService azureCostService,
                                    UpwindAccountConnectionRepository upwindConnectionRepository,
                                    UpwindInventoryAssetRepository upwindInventoryRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.userRepository = userRepository;
        this.userCloudAccountAccessRepository = userCloudAccountAccessRepository;
        this.cachedDataRepository = cachedDataRepository;
        this.objectMapper = objectMapper;
        this.costService = costService;
        this.gcpCostService = gcpCostService;
        this.azureCostService = azureCostService;
        this.upwindConnectionRepository = upwindConnectionRepository;
        this.upwindInventoryRepository = upwindInventoryRepository;
    }

    /**
     * Builds the list for a request. Spend the DB cache does not hold yet, and AWS credits, are
     * fetched live from the cloud APIs, in parallel.
     */
    public List<BillOpsClientDetailsDto> build(List<Client> clients) {
        return build(clients, true);
    }

    /**
     * Builds the list from the database alone, for the background cache warmer. Spend the DB
     * cache does not hold yet, and AWS credits, are reported as 0 instead of being fetched, so a
     * job that runs every few minutes never calls AWS, GCP or Azure. Everything else, the Upwind
     * columns included, is the same as {@link #build(List)}.
     */
    public List<BillOpsClientDetailsDto> buildFromCacheOnly(List<Client> clients) {
        return build(clients, false);
    }

    /** Asset category holding the things a runtime sensor protects, and the licence unit. */
    private static final String UPWIND_WORKLOAD_CATEGORY = "compute_platform";

    /**
     * Whether a catalog {@code protected_by} value means a runtime sensor is covering the asset.
     *
     * Only {@code sensor} counts. {@code cloud_scanner} is agentless, and a null value means the
     * catalog gave no verdict rather than that the asset is uncovered — folding either into the
     * sensor count would overstate consumption on the one number the admin team bills against.
     */
    private static boolean isSensorMechanism(String protectedBy) {
        if (protectedBy == null || protectedBy.isBlank()) return false;
        String v = protectedBy.trim().toLowerCase();
        return v.contains("sensor") || v.contains("agent");
    }

    /**
     * @param liveCloudCalls whether spend missing from the DB cache, and AWS credits, may be
     *                       fetched from the cloud APIs. When false they are reported as 0.
     */
    private List<BillOpsClientDetailsDto> build(List<Client> clients, boolean liveCloudCalls) {
        if (clients.isEmpty()) return new ArrayList<>();

        List<Long> clientIds = clients.stream().map(Client::getId).collect(Collectors.toList());

        // Fetch ALL accounts for ALL clients in ONE query (no N+1)
        List<CloudAccount> allAccounts = cloudAccountRepository.findByClientIdIn(clientIds);
        Map<Long, List<CloudAccount>> accountsByClientId = allAccounts.stream()
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        // Fetch ALL users for ALL clients in ONE query (no N+1)
        List<User> allUsers = userRepository.findByClientIdIn(clientIds);
        Map<Long, List<User>> usersByClientId = allUsers.stream()
                .collect(Collectors.groupingBy(u -> u.getClient().getId()));

        // Fetch ALL user-account-access records in ONE query (no N*M+1)
        List<Long> allUserIds = allUsers.stream().map(User::getId).collect(Collectors.toList());
        Map<Long, List<Long>> accountAccessByUserId = new HashMap<>();
        if (!allUserIds.isEmpty()) {
            List<Object[]> accessRows = userCloudAccountAccessRepository
                    .findUserIdAndAccountIdsByUserIdIn(allUserIds);
            for (Object[] row : accessRows) {
                Long userId = ((Number) row[0]).longValue();
                Long accountId = ((Number) row[1]).longValue();
                accountAccessByUserId.computeIfAbsent(userId, k -> new ArrayList<>()).add(accountId);
            }
        }

        // ── Batch-read MTD costs from DB cache (3 IN queries, one per provider) ──────
        // Same cache keys written by DashboardDataService / CostService — identical to
        // what the Account Dashboard shows, and safe for hundreds of accounts.
        String currentMonth = YearMonth.now().toString();
        // AWS date-scoped breakdown key parts — identical to what CostService/FinOps write for MTD.
        String awsMonthStart = YearMonth.now().atDay(1).toString();
        String awsMonthEnd   = YearMonth.now().atEndOfMonth().toString();

        List<String> allCacheKeys = new ArrayList<>();
        for (CloudAccount a : allAccounts) {
            String id = a.getProviderAccountId();
            if (id == null || id.isEmpty()) continue;
            if ("AWS".equalsIgnoreCase(a.getProvider())) {
                // PRIMARY: same date-scoped row the Cost & FinOps pages read; AWS_MTD_COST kept as fallback.
                allCacheKeys.add("AWS_COST_BREAKDOWN::" + id + "::SERVICE::::" + awsMonthStart + "::" + awsMonthEnd);
                allCacheKeys.add("AWS_MTD_COST::" + id);
            } else if ("GCP".equalsIgnoreCase(a.getProvider())) {
                allCacheKeys.add("GCP_MONTHLY_TOTAL::" + id + "::" + currentMonth);
            } else if ("Azure".equalsIgnoreCase(a.getProvider())) {
                allCacheKeys.add("AZURE_COST_BILLING_SUMMARY::" + id);
            }
        }

        Map<String, String> cacheJsonByKey = new HashMap<>();
        if (!allCacheKeys.isEmpty()) {
            cachedDataRepository.findAllById(allCacheKeys)
                    .forEach(e -> cacheJsonByKey.put(e.getCacheKey(), e.getJsonData()));
        }

        Map<Long, Double> costByAccountId = new HashMap<>();
        // Collect accounts whose DB cache was cold — fall back to live API for these only
        List<CloudAccount> cacheMissAccounts = new ArrayList<>();

        for (CloudAccount account : allAccounts) {
            String providerAccId = account.getProviderAccountId();
            String provider      = account.getProvider();
            if (providerAccId == null || providerAccId.isEmpty()) {
                costByAccountId.put(account.getId(), 0.0);
                continue;
            }
            try {
                if ("AWS".equalsIgnoreCase(provider)) {
                    // PRIMARY: date-scoped service breakdown — the same DB row (and value) the
                    // Cost and FinOps pages show. Authoritative for the current month; if the row
                    // exists we trust it even when the sum is 0.
                    String bkJson = cacheJsonByKey.get(
                            "AWS_COST_BREAKDOWN::" + providerAccId + "::SERVICE::::" + awsMonthStart + "::" + awsMonthEnd);
                    if (bkJson != null) {
                        List<Map<String, Object>> costs = objectMapper.readValue(
                                bkJson, new TypeReference<List<Map<String, Object>>>() {});
                        double total = costs.stream()
                                .mapToDouble(s -> ((Number) s.getOrDefault("amount", 0.0)).doubleValue())
                                .sum();
                        costByAccountId.put(account.getId(), total);
                        continue;
                    }
                    // FALLBACK: non-dated AWS_MTD_COST (may be stale — only used if breakdown absent).
                    String json = cacheJsonByKey.get("AWS_MTD_COST::" + providerAccId);
                    if (json != null) {
                        Double val = objectMapper.readValue(json, Double.class);
                        costByAccountId.put(account.getId(), val != null ? val : 0.0);
                        continue;
                    }
                } else if ("GCP".equalsIgnoreCase(provider)) {
                    String json = cacheJsonByKey.get("GCP_MONTHLY_TOTAL::" + providerAccId + "::" + currentMonth);
                    if (json != null) {
                        Double val = objectMapper.readValue(json, Double.class);
                        costByAccountId.put(account.getId(), val != null ? val : 0.0);
                        continue;
                    }
                } else if ("Azure".equalsIgnoreCase(provider)) {
                    String json = cacheJsonByKey.get("AZURE_COST_BILLING_SUMMARY::" + providerAccId);
                    if (json != null) {
                        List<Map<String, Object>> summary = objectMapper.readValue(
                                json, new TypeReference<List<Map<String, Object>>>() {});
                        double total = summary.stream()
                                .mapToDouble(s -> ((Number) s.getOrDefault("monthToDateCost", 0.0)).doubleValue())
                                .sum();
                        costByAccountId.put(account.getId(), total);
                        continue;
                    }
                }
            } catch (Exception e) {
                log.warn("DB cache parse failed for account {}: {}", account.getId(), e.getMessage());
            }
            // DB cache cold for this account — queue for live API fallback
            cacheMissAccounts.add(account);
        }

        // Live fallback — only for accounts not yet in DB cache (new accounts / first load).
        // Skipped when building from the cache alone: those accounts read as 0 until it fills.
        if (liveCloudCalls && !cacheMissAccounts.isEmpty()) {
            log.info("DB cache cold for {} account(s) — falling back to live API", cacheMissAccounts.size());
            List<CompletableFuture<Double>> missFutures = cacheMissAccounts.stream().map(account -> {
                String providerAccId = account.getProviderAccountId();
                String provider      = account.getProvider();
                try {
                    if ("AWS".equalsIgnoreCase(provider))
                        return costService.getTotalMonthToDateCost(providerAccId, false)
                                .exceptionally(e -> { log.error("AWS cost fallback error {}: {}", account.getId(), e.getMessage()); return 0.0; });
                    if ("GCP".equalsIgnoreCase(provider))
                        return gcpCostService.getUnfilteredMonthToDateSpend(providerAccId)
                                .exceptionally(e -> { log.error("GCP cost fallback error {}: {}", account.getId(), e.getMessage()); return 0.0; });
                    if ("Azure".equalsIgnoreCase(provider))
                        return azureCostService.getTotalMonthToDateCost(providerAccId)
                                .exceptionally(e -> { log.error("Azure cost fallback error {}: {}", account.getId(), e.getMessage()); return 0.0; });
                } catch (Exception e) {
                    log.error("Cost fallback launch error for account {}: {}", account.getId(), e.getMessage());
                }
                return CompletableFuture.completedFuture(0.0);
            }).collect(Collectors.toList());
            CompletableFuture.allOf(missFutures.toArray(new CompletableFuture[0])).join();
            for (int i = 0; i < cacheMissAccounts.size(); i++) {
                Double cost = missFutures.get(i).join();
                costByAccountId.put(cacheMissAccounts.get(i).getId(), cost != null ? cost : 0.0);
            }
        }

        // ── Upwind onboarding state + workload metering (2 queries for ALL clients) ──────
        //
        // Both are local table reads. Deliberately NOT calling the Upwind API here: this payload
        // covers every client and is Redis-cached for 5 minutes, whereas XamSecureService's
        // per-client getOnboardingStatus can afford a live read because it is scoped to one
        // tenant. An admin list that fans out to a third-party API per account would be the
        // slowest page in the product.
        List<String> allProviderAccountIds = allAccounts.stream()
                .map(CloudAccount::getProviderAccountId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .collect(Collectors.toList());

        Map<String, String> upwindStatusByAccountId = new HashMap<>();
        Map<String, long[]> upwindWorkloadsByAccountId = new HashMap<>();  // [total, sensor]
        if (!allProviderAccountIds.isEmpty()) {
            try {
                for (UpwindAccountConnection c :
                        upwindConnectionRepository.findByCloudAccountIdIn(allProviderAccountIds)) {
                    if (c.getCloudAccountId() != null) {
                        upwindStatusByAccountId.put(c.getCloudAccountId(), c.getStatus());
                    }
                }
                // [cloudAccountId, assetSubCategory, protectedBy, count] over compute assets only.
                for (Object[] r : upwindInventoryRepository.coverageBreakdown(
                        allProviderAccountIds, UPWIND_WORKLOAD_CATEGORY)) {
                    String acct = (String) r[0];
                    if (acct == null) continue;
                    long count = r[3] == null ? 0L : ((Number) r[3]).longValue();
                    long[] agg = upwindWorkloadsByAccountId
                            .computeIfAbsent(acct, k -> new long[2]);
                    agg[0] += count;
                    if (isSensorMechanism((String) r[2])) agg[1] += count;
                }
            } catch (Exception e) {
                // A reporting column must never take down the admin list. Absent numbers render
                // as "—" rather than as zero, so a failure here cannot be misread as "no estate".
                log.warn("Upwind admin columns unavailable: {}", e.getMessage());
                upwindStatusByAccountId.clear();
                upwindWorkloadsByAccountId.clear();
            }
        }

        // ── AWS credits — parallel (no DB cache exists for credits) ──────────────────
        Map<Long, Double> creditsByAccountId = new HashMap<>();
        List<CompletableFuture<Double>> creditFutures = allAccounts.stream().map(account -> {
            String providerAccId = account.getProviderAccountId();
            String provider = account.getProvider();
            // Credits have no DB cache, so building from the cache alone reports 0 for them.
            if (liveCloudCalls && "AWS".equalsIgnoreCase(provider) && providerAccId != null && !providerAccId.isEmpty()) {
                return costService.getCreditsApplied(providerAccId)
                        .exceptionally(e -> { log.warn("Credits fetch error for {}: {}", account.getId(), e.getMessage()); return 0.0; });
            }
            return CompletableFuture.completedFuture(0.0);
        }).collect(Collectors.toList());
        CompletableFuture.allOf(creditFutures.toArray(new CompletableFuture[0])).join();
        for (int i = 0; i < allAccounts.size(); i++) {
            Double credits = creditFutures.get(i).join();
            creditsByAccountId.put(allAccounts.get(i).getId(), credits != null ? credits : 0.0);
        }

        // Assemble response
        List<BillOpsClientDetailsDto> result = new ArrayList<>();
        for (Client client : clients) {
            BillOpsClientDetailsDto clientDto = new BillOpsClientDetailsDto();
            clientDto.setId(client.getId());
            clientDto.setName(client.getName());
            clientDto.setEmail(client.getEmail());
            clientDto.setStarred(client.isStarred());
            clientDto.setXamSecureOnboardingEnabled(client.isXamSecureOnboardingEnabled());
            clientDto.setSmsNotificationsEnabled(client.isSmsNotificationsEnabled());
            clientDto.setWhatsappNotificationsEnabled(client.isWhatsappNotificationsEnabled());

            List<CloudAccount> accounts = accountsByClientId.getOrDefault(client.getId(), new ArrayList<>());
            List<BillOpsAccountDto> accountDtos = new ArrayList<>();
            double totalConsumption = 0;
            int upwindConnected = 0, upwindPending = 0, upwindEligible = 0;
            long upwindWorkloads = 0, upwindSensorWorkloads = 0;

            for (CloudAccount account : accounts) {
                BillOpsAccountDto accDto = new BillOpsAccountDto();
                accDto.setId(account.getId());
                accDto.setName(account.getAccountName());
                accDto.setProvider(account.getProvider());
                accDto.setProviderAccountId(account.getProviderAccountId());
                double consumption = costByAccountId.getOrDefault(account.getId(), 0.0);
                accDto.setConsumption(consumption);
                totalConsumption += consumption;
                accDto.setCreditsApplied(creditsByAccountId.getOrDefault(account.getId(), 0.0));

                // Upwind state. Only AWS can be self-service onboarded, so anything else is left
                // null — "not applicable" rather than "not done".
                String providerAccId = account.getProviderAccountId();
                boolean awsEligible = "AWS".equalsIgnoreCase(account.getProvider())
                        && providerAccId != null && !providerAccId.isEmpty();
                if (awsEligible) {
                    upwindEligible++;
                    String status = upwindStatusByAccountId.get(providerAccId);
                    accDto.setUpwindStatus(status == null ? "NOT_CONNECTED" : status);
                    if ("CONNECTED".equalsIgnoreCase(status)) upwindConnected++;
                    else if ("PENDING".equalsIgnoreCase(status)) upwindPending++;
                }
                if (providerAccId != null) {
                    long[] agg = upwindWorkloadsByAccountId.get(providerAccId);
                    if (agg != null) {
                        accDto.setUpwindWorkloads(agg[0]);
                        accDto.setUpwindSensorWorkloads(agg[1]);
                        upwindWorkloads += agg[0];
                        upwindSensorWorkloads += agg[1];
                    }
                }
                accountDtos.add(accDto);
            }
            clientDto.setAccounts(accountDtos);
            clientDto.setTotalConsumption(totalConsumption);
            clientDto.setUpwindConnectedAccounts(upwindConnected);
            clientDto.setUpwindPendingAccounts(upwindPending);
            clientDto.setUpwindEligibleAccounts(upwindEligible);
            clientDto.setUpwindWorkloads(upwindWorkloads);
            clientDto.setUpwindSensorWorkloads(upwindSensorWorkloads);

            List<User> users = usersByClientId.getOrDefault(client.getId(), new ArrayList<>());
            List<BillOpsUserDto> userDtos = new ArrayList<>();
            for (User user : users) {
                BillOpsUserDto userDto = new BillOpsUserDto();
                userDto.setId(user.getId());
                userDto.setUsername(user.getUsername());
                userDto.setEmail(user.getEmail());
                userDto.setAccountAccessIds(accountAccessByUserId.getOrDefault(user.getId(), new ArrayList<>()));
                userDto.setParentId(user.getCreatedBy());
                userDto.setLastLoginAt(user.getLastLoginAt());
                userDto.setEmailNotificationsEnabled(user.isEmailNotificationsEnabled());
                userDtos.add(userDto);
            }
            clientDto.setUsers(userDtos);
            result.add(clientDto);
        }
        return result;
    }
}
