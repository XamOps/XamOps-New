package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.gcp.GcpCostService;
import com.xammer.cloud.service.azure.AzureCostService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

// --- CONSOLIDATED DTOS TO RESOLVE COMPILATION ISSUES ---

class BillOpsAccountDto {
    private Long id;
    private String name;
    private String providerAccountId;
    private String provider;
    private Double consumption;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProviderAccountId() {
        return providerAccountId;
    }

    public void setProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Double getConsumption() {
        return consumption;
    }

    public void setConsumption(Double consumption) {
        this.consumption = consumption;
    }
}

class BillOpsUserDto {
    private Long id;
    private String username;
    private String email;
    private List<Long> accountAccessIds;
    private Long parentId;  // null = primary user, set = sub-user of that parent
    private java.time.LocalDateTime lastLoginAt;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<Long> getAccountAccessIds() {
        return accountAccessIds;
    }

    public void setAccountAccessIds(List<Long> accountAccessIds) {
        this.accountAccessIds = accountAccessIds;
    }

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public java.time.LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(java.time.LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}



class BillOpsClientDetailsDto {
    private Long id;
    private String name;
    private String email;
    private List<BillOpsAccountDto> accounts;
    private List<BillOpsUserDto> users;
    private Double totalConsumption;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<BillOpsAccountDto> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<BillOpsAccountDto> accounts) {
        this.accounts = accounts;
    }

    public List<BillOpsUserDto> getUsers() {
        return users;
    }

    public void setUsers(List<BillOpsUserDto> users) {
        this.users = users;
    }

    public Double getTotalConsumption() {
        return totalConsumption;
    }

    public void setTotalConsumption(Double totalConsumption) {
        this.totalConsumption = totalConsumption;
    }
}

@RestController
@RequestMapping("/api/xamops/billops-admin")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public class BillOpsAdminController {

    private static final Logger log = LoggerFactory.getLogger(BillOpsAdminController.class);
    private static final String CLIENTS_CACHE_KEY = "billops:admin:clients";
    private static final int CLIENTS_CACHE_TTL_MINUTES = 5;

    @Autowired
    private RedisCacheService redisCacheService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private CostService costService;

    @Autowired
    private GcpCostService gcpCostService;

    @Autowired
    private AzureCostService azureCostService;

    /** Evict the clients cache (call this after creating a client or user) */
    @DeleteMapping("/clients/cache")
    public ResponseEntity<String> evictClientsCache() {
        redisCacheService.evict(CLIENTS_CACHE_KEY);
        return ResponseEntity.ok("Cache evicted");
    }

    /** Debug: check last_login_at values directly from DB (bypasses Redis cache) */
    @GetMapping("/debug/last-login")
    public ResponseEntity<List<java.util.Map<String, Object>>> debugLastLogin() {
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        userRepository.findAll().forEach(user -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", user.getId());
            row.put("username", user.getUsername());
            row.put("lastLoginAt", user.getLastLoginAt() != null ? user.getLastLoginAt().toString() : null);
            result.add(row);
        });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/clients")
    public List<BillOpsClientDetailsDto> getClients() {
        log.info("Fetching client details for BillOps Admin (Optimised)");

        // 0. Return cached response if available
        Optional<List<BillOpsClientDetailsDto>> cached = redisCacheService.get(
                CLIENTS_CACHE_KEY, new TypeReference<List<BillOpsClientDetailsDto>>() {});
        if (cached.isPresent()) {
            log.info("Returning client details from Redis cache");
            return cached.get();
        }

        // 1. Fetch all clients in ONE query
        List<Client> clients = clientRepository.findAll();
        if (clients.isEmpty()) return new ArrayList<>();

        List<Long> clientIds = clients.stream().map(Client::getId).collect(Collectors.toList());

        // 2. Fetch ALL accounts for ALL clients in ONE query (no N+1)
        List<CloudAccount> allAccounts = cloudAccountRepository.findByClientIdIn(clientIds);
        Map<Long, List<CloudAccount>> accountsByClientId = allAccounts.stream()
                .collect(Collectors.groupingBy(a -> a.getClient().getId()));

        // 3. Fetch ALL users for ALL clients in ONE query (no N+1)
        List<User> allUsers = userRepository.findByClientIdIn(clientIds);
        Map<Long, List<User>> usersByClientId = allUsers.stream()
                .collect(Collectors.groupingBy(u -> u.getClient().getId()));

        // 4. Fetch ALL user-account-access records in ONE query (no N*M+1)
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

        // 5. Kick off ALL cloud cost API calls in PARALLEL (not sequential)
        List<CompletableFuture<Double>> costFutures = allAccounts.stream().map(account -> {
            String providerAccId = account.getProviderAccountId();
            String provider = account.getProvider();
            if (providerAccId == null || providerAccId.isEmpty()) {
                return CompletableFuture.completedFuture(0.0);
            }
            try {
                if ("AWS".equalsIgnoreCase(provider)) {
                    return costService.getTotalMonthToDateCost(providerAccId, false)
                            .exceptionally(e -> { log.error("AWS cost error for {}: {}", account.getId(), e.getMessage()); return 0.0; });
                } else if ("GCP".equalsIgnoreCase(provider)) {
                    return gcpCostService.getUnfilteredMonthToDateSpend(providerAccId)
                            .exceptionally(e -> { log.error("GCP cost error for {}: {}", account.getId(), e.getMessage()); return 0.0; });
                } else if ("Azure".equalsIgnoreCase(provider)) {
                    return azureCostService.getTotalMonthToDateCost(providerAccId)
                            .exceptionally(e -> { log.error("Azure cost error for {}: {}", account.getId(), e.getMessage()); return 0.0; });
                }
            } catch (Exception e) {
                log.error("Error starting cost fetch for account {}: {}", account.getId(), e.getMessage());
            }
            return CompletableFuture.completedFuture(0.0);
        }).collect(Collectors.toList());

        // Wait for ALL cloud calls to finish at once (parallel, not sequential)
        CompletableFuture.allOf(costFutures.toArray(new CompletableFuture[0])).join();

        // Build cost map: accountId → consumption
        Map<Long, Double> costByAccountId = new HashMap<>();
        for (int i = 0; i < allAccounts.size(); i++) {
            Double cost = costFutures.get(i).join();
            costByAccountId.put(allAccounts.get(i).getId(), cost != null ? cost : 0.0);
        }

        // 6. Assemble response
        List<BillOpsClientDetailsDto> result = new ArrayList<>();
        for (Client client : clients) {
            BillOpsClientDetailsDto clientDto = new BillOpsClientDetailsDto();
            clientDto.setId(client.getId());
            clientDto.setName(client.getName());
            clientDto.setEmail(client.getEmail());

            List<CloudAccount> accounts = accountsByClientId.getOrDefault(client.getId(), new ArrayList<>());
            List<BillOpsAccountDto> accountDtos = new ArrayList<>();
            double totalConsumption = 0;

            for (CloudAccount account : accounts) {
                BillOpsAccountDto accDto = new BillOpsAccountDto();
                accDto.setId(account.getId());
                accDto.setName(account.getAccountName());
                accDto.setProvider(account.getProvider());
                accDto.setProviderAccountId(account.getProviderAccountId());
                double consumption = costByAccountId.getOrDefault(account.getId(), 0.0);
                accDto.setConsumption(consumption);
                totalConsumption += consumption;
                accountDtos.add(accDto);
            }
            clientDto.setAccounts(accountDtos);
            clientDto.setTotalConsumption(totalConsumption);

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
                userDtos.add(userDto);
            }
            clientDto.setUsers(userDtos);
            result.add(clientDto);
        }

        // Cache for next requests
        redisCacheService.put(CLIENTS_CACHE_KEY, result, CLIENTS_CACHE_TTL_MINUTES);
        return result;
    }
}
