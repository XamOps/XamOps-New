package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BillOpsClientsCacheWarmingService {

    private static final Logger log = LoggerFactory.getLogger(BillOpsClientsCacheWarmingService.class);
    private static final String CLIENTS_CACHE_KEY = "billops:admin:clients";
    private static final int CLIENTS_CACHE_TTL_MINUTES = 5;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private CachedDataRepository cachedDataRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // Warm the cache on startup
    @EventListener(ApplicationReadyEvent.class)
    public void warmCacheOnStartup() {
        log.info("🔄 Warming BillOps clients cache on startup...");
        warmClientsCache();
    }

    // Periodically refresh the cache every 4 minutes (before 5-minute TTL expires)
    @Scheduled(fixedDelay = 240000) // 4 minutes
    public void refreshClientsCache() {
        log.info("🔄 Refreshing BillOps clients cache (scheduled)...");
        warmClientsCache();
    }

    /**
     * Warm the clients cache by building and storing the full client details.
     * This runs in the background so dashboard loads show cached data immediately.
     */
    private void warmClientsCache() {
        try {
            List<Client> clients = clientRepository.findAll();
            if (clients.isEmpty()) {
                log.info("No clients found to cache");
                return;
            }

            List<BillOpsClientDetailsDto> clientDtos = buildClientDtos(clients);
            redisCacheService.put(CLIENTS_CACHE_KEY, clientDtos, CLIENTS_CACHE_TTL_MINUTES);
            log.info("✅ BillOps clients cache warmed with {} clients", clientDtos.size());
        } catch (Exception e) {
            log.error("❌ Failed to warm BillOps clients cache", e);
        }
    }

    /**
     * DTO class for clients (mirrors BillOpsAdminController implementation)
     */
    private static class BillOpsClientDetailsDto {
        public Long id;
        public String name;
        public String email;
        public List<BillOpsAccountDto> accounts;
        public List<BillOpsUserDto> users;
        public Double totalConsumption;
        public boolean starred;

        public BillOpsClientDetailsDto() {}
    }

    private static class BillOpsAccountDto {
        public Long id;
        public String name;
        public String providerAccountId;
        public String provider;
        public Double consumption;
        public Double creditsApplied;

        public BillOpsAccountDto() {}
    }

    private static class BillOpsUserDto {
        public Long id;
        public String username;
        public String email;
        public List<Long> accountAccessIds;
        public Long parentId;
        public java.time.LocalDateTime lastLoginAt;

        public BillOpsUserDto() {}
    }

    /**
     * Build client DTOs with cached cost data only (simple variant for cache warming).
     * This avoids async API calls to keep startup fast.
     */
    private List<BillOpsClientDetailsDto> buildClientDtos(List<Client> clients) {
        if (clients.isEmpty()) return new ArrayList<>();

        List<Long> clientIds = clients.stream().map(Client::getId).collect(Collectors.toList());

        // Fetch all accounts and users
        List<CloudAccount> allAccounts = cloudAccountRepository.findByClientIdIn(clientIds);
        Map<Long, List<CloudAccount>> accountsByClientId = allAccounts.stream()
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        List<User> allUsers = userRepository.findByClientIdIn(clientIds);
        Map<Long, List<User>> usersByClientId = allUsers.stream()
                .collect(Collectors.groupingBy(u -> u.getClient().getId()));

        // Fetch user-account-access records
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

        // Batch-read MTD costs from DB cache only (no async API fallback on warm-up)
        String currentMonth = YearMonth.now().toString();
        String awsMonthStart = YearMonth.now().atDay(1).toString();
        String awsMonthEnd = YearMonth.now().atEndOfMonth().toString();

        List<String> allCacheKeys = new ArrayList<>();
        for (CloudAccount a : allAccounts) {
            String id = a.getProviderAccountId();
            if (id == null || id.isEmpty()) continue;
            if ("AWS".equalsIgnoreCase(a.getProvider())) {
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
        for (CloudAccount account : allAccounts) {
            String providerAccId = account.getProviderAccountId();
            String provider = account.getProvider();
            if (providerAccId == null || providerAccId.isEmpty()) {
                costByAccountId.put(account.getId(), 0.0);
                continue;
            }
            try {
                if ("AWS".equalsIgnoreCase(provider)) {
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
                        Double val = objectMapper.readValue(json, Double.class);
                        costByAccountId.put(account.getId(), val != null ? val : 0.0);
                        continue;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse cost for account {}", account.getId(), e);
            }
            costByAccountId.put(account.getId(), 0.0);
        }

        // Build client DTOs
        List<BillOpsClientDetailsDto> result = new ArrayList<>();
        for (Client client : clients) {
            BillOpsClientDetailsDto dto = new BillOpsClientDetailsDto();
            dto.id = client.getId();
            dto.name = client.getName();
            dto.email = client.getEmail();
            dto.starred = client.isStarred();

            // Build account DTOs with costs
            dto.accounts = new ArrayList<>();
            double totalCost = 0.0;
            for (CloudAccount acc : accountsByClientId.getOrDefault(client.getId(), new ArrayList<>())) {
                BillOpsAccountDto accDto = new BillOpsAccountDto();
                accDto.id = acc.getId();
                accDto.name = acc.getAccountName();
                accDto.providerAccountId = acc.getProviderAccountId();
                accDto.provider = acc.getProvider();
                accDto.consumption = costByAccountId.getOrDefault(acc.getId(), 0.0);
                accDto.creditsApplied = 0.0;
                dto.accounts.add(accDto);
                totalCost += accDto.consumption;
            }

            // Build user DTOs
            dto.users = new ArrayList<>();
            for (User user : usersByClientId.getOrDefault(client.getId(), new ArrayList<>())) {
                BillOpsUserDto userDto = new BillOpsUserDto();
                userDto.id = user.getId();
                userDto.username = user.getUsername();
                userDto.email = user.getEmail();
                userDto.parentId = user.getCreatedBy();
                userDto.lastLoginAt = user.getLastLoginAt();
                userDto.accountAccessIds = accountAccessByUserId.getOrDefault(user.getId(), new ArrayList<>());
                dto.users.add(userDto);
            }

            dto.totalConsumption = totalCost;
            result.add(dto);
        }

        return result;
    }
}
