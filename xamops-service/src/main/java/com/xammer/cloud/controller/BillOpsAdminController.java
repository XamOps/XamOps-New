package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.MarketplaceAccount;
import com.xammer.cloud.domain.MarketplacePayerAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.marketplace.BillingShareRequest;
import com.xammer.cloud.dto.marketplace.ConnectMarketplaceRequest;
import com.xammer.cloud.dto.marketplace.MarketplaceAccountSummaryDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.MarketplaceAccountRepository;
import com.xammer.cloud.repository.MarketplacePayerAccountRepository;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.CostService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.gcp.GcpCostService;
import com.xammer.cloud.service.azure.AzureCostService;
import com.xammer.cloud.service.marketplace.MarketplaceAccountDiscoveryService;
import com.xammer.cloud.service.marketplace.MarketplaceBillingExtractionService;
import com.xammer.cloud.service.marketplace.MarketplaceBillingShareService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    @Autowired
    private MarketplacePayerAccountRepository marketplacePayerAccountRepository;

    @Autowired
    private MarketplaceAccountRepository marketplaceAccountRepository;

    @Autowired
    private MarketplaceAccountDiscoveryService marketplaceAccountDiscoveryService;

    @Autowired
    private MarketplaceBillingExtractionService marketplaceBillingExtractionService;

    @Autowired
    private MarketplaceBillingShareService marketplaceBillingShareService;

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

    // =========================================================
    // MARKETPLACE BILLING ENDPOINTS
    // =========================================================

    /**
     * Connect a marketplace payer account and auto-discover all member accounts.
     * Call this once with the IAM Role ARN created in the payer account.
     *
     * POST /api/xamops/billops-admin/marketplace/connect
     * Body: { "accountName": "My Marketplace", "roleArn": "arn:aws:iam::123456789012:role/XamOpsMarketRole" }
     */
    @PostMapping("/marketplace/connect")
    public ResponseEntity<Map<String, Object>> connectMarketplace(
            @RequestBody ConnectMarketplaceRequest request) {
        log.info("Connecting marketplace account with roleArn: {}", request.getRoleArn());
        MarketplacePayerAccount payer = marketplaceAccountDiscoveryService.connectAndDiscover(request);
        List<MarketplaceAccount> discovered = marketplaceAccountRepository.findByPayerAccount(payer);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payerAccountId", payer.getId());
        response.put("payerAwsAccountId", payer.getPayerAwsAccountId());
        response.put("accountName", payer.getAccountName());
        response.put("totalDiscovered", discovered.size());
        response.put("marketplaceXamops",
                discovered.stream().filter(a -> "MARKETPLACE_XAMOPS".equals(a.getCategory())).count());
        response.put("marketplaceOnly",
                discovered.stream().filter(a -> "MARKETPLACE_ONLY".equals(a.getCategory())).count());
        response.put("status", "CONNECTED");
        return ResponseEntity.ok(response);
    }

    /**
     * Return the connected payer account(s) so the UI can retrieve payerAccountId on page load.
     *
     * GET /api/xamops/billops-admin/marketplace/payers
     */
    @GetMapping("/marketplace/payers")
    public ResponseEntity<List<Map<String, Object>>> getMarketplacePayers() {
        List<MarketplacePayerAccount> payers = marketplacePayerAccountRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MarketplacePayerAccount payer : payers) {
            List<MarketplaceAccount> discovered = marketplaceAccountRepository.findByPayerAccount(payer);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("payerAccountId", payer.getId());
            dto.put("payerAwsAccountId", payer.getPayerAwsAccountId());
            dto.put("accountName", payer.getAccountName());
            dto.put("totalDiscovered", discovered.size());
            dto.put("marketplaceXamops",
                    discovered.stream().filter(a -> "MARKETPLACE_XAMOPS".equals(a.getCategory())).count());
            dto.put("marketplaceOnly",
                    discovered.stream().filter(a -> "MARKETPLACE_ONLY".equals(a.getCategory())).count());
            dto.put("status", payer.getStatus());
            result.add(dto);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * List all discovered marketplace accounts with their extraction status.
     *
     * GET /api/xamops/billops-admin/marketplace/accounts
     */
    @GetMapping("/marketplace/accounts")
    public ResponseEntity<List<MarketplaceAccountSummaryDto>> getMarketplaceAccounts() {
        List<MarketplacePayerAccount> payers = marketplacePayerAccountRepository.findAll();
        List<MarketplaceAccountSummaryDto> result = new ArrayList<>();
        for (MarketplacePayerAccount payer : payers) {
            marketplaceAccountRepository.findByPayerAccount(payer).forEach(account -> {
                MarketplaceAccountSummaryDto dto = new MarketplaceAccountSummaryDto();
                dto.setId(account.getId());
                dto.setAwsAccountId(account.getAwsAccountId());
                dto.setAccountName(account.getAccountName());
                dto.setContactEmail(account.getContactEmail());
                dto.setCategory(account.getCategory());
                dto.setBillingExtracted(account.getBillingExtracted());
                dto.setStatus(account.getStatus());
                dto.setDiscoveredAt(account.getDiscoveredAt());
                result.add(dto);
            });
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the list of months (e.g. ["2025-02","2025-03"]) for which billing data
     * has been extracted into DB for the given account.
     *
     * GET /api/xamops/billops-admin/marketplace/accounts/{accountId}/billing-months
     */
    @GetMapping("/marketplace/accounts/{accountId}/billing-months")
    public ResponseEntity<List<String>> getBillingMonths(@PathVariable String accountId) {
        List<String> months = marketplaceBillingExtractionService.getAvailableMonths(accountId);
        return ResponseEntity.ok(months);
    }

    /**
     * Extract billing data for ALL marketplace accounts (Dec 2024 – Mar 2026).
     * Run this ONCE before applying Billing Conductor.
     * Saves CSV to S3 and summary rows to DB for every account.
     *
     * POST /api/xamops/billops-admin/marketplace/extract-all/{payerAccountId}
     */
    @PostMapping("/marketplace/extract-all/{payerAccountId}")
    public ResponseEntity<Map<String, String>> extractAllBilling(
            @PathVariable Long payerAccountId) {
        log.info("Starting bulk billing extraction for payer account {}", payerAccountId);
        Map<String, String> results = marketplaceBillingExtractionService.extractAll(payerAccountId);
        return ResponseEntity.ok(results);
    }

    /**
     * Share billing data for a single marketplace account via email.
     * Generates a 48-hour S3 download link and emails it to the customer.
     *
     * POST /api/xamops/billops-admin/marketplace/share-billing
     * Body: { "awsAccountId": "123456789012", "recipientEmail": "customer@example.com" }
     */
    @PostMapping("/marketplace/share-billing")
    public ResponseEntity<Map<String, String>> shareBilling(
            @RequestBody BillingShareRequest request) {
        log.info("Sharing billing data for account {} → {}",
                request.getAwsAccountId(), request.getRecipientEmail());
        marketplaceBillingShareService.shareBillingData(
                request.getAwsAccountId(), request.getRecipientEmail(), request.getYearMonth());
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "EMAIL_SENT");
        response.put("awsAccountId", request.getAwsAccountId());
        response.put("yearMonth", request.getYearMonth());
        response.put("sentTo", request.getRecipientEmail());
        response.put("message", "Billing data download link sent successfully (valid 48 hours)");
        return ResponseEntity.ok(response);
    }
}
