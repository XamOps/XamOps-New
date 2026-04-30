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
import com.xammer.cloud.service.CostDataSnapshotService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.xammer.cloud.service.billops.ClientSavingsService;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.YearMonth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

// --- CONSOLIDATED DTOS TO RESOLVE COMPILATION ISSUES ---

class BillOpsAccountDto {
    private Long id;
    private String name;
    private String providerAccountId;
    private String provider;
    private Double consumption;
    private Double creditsApplied;

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

    public Double getCreditsApplied() {
        return creditsApplied;
    }

    public void setCreditsApplied(Double creditsApplied) {
        this.creditsApplied = creditsApplied;
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
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_BILLOPS_ADMIN_SUB') or hasAuthority('ROLE_SUPER_ADMIN')")
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

    @Autowired
    private CostDataSnapshotService costDataSnapshotService;

    @Autowired
    private ClientSavingsService clientSavingsService;

    @Autowired
    private CachedDataRepository cachedDataRepository;

    @Autowired
    private ObjectMapper objectMapper;

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

        // Detect ROLE_BILLOPS_ADMIN_SUB — scope to their owned clients only
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isSubAdmin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_BILLOPS_ADMIN_SUB".equals(a.getAuthority()));

        if (isSubAdmin) {
            // Sub-admin: find their DB user record by username to get their ID
            String username = auth.getName();
            Optional<User> subAdminUser = userRepository.findByUsername(username);
            if (!subAdminUser.isPresent()) {
                log.warn("SUB_ADMIN '{}' not found in user table — returning empty client list", username);
                return new ArrayList<>();
            }
            Long subAdminId = subAdminUser.get().getId();
            // Use a per-user cache key so sub-admins never share the primary admin cache
            String subCacheKey = CLIENTS_CACHE_KEY + ":sub:" + subAdminId;
            Optional<List<BillOpsClientDetailsDto>> subCached = redisCacheService.get(
                    subCacheKey, new TypeReference<List<BillOpsClientDetailsDto>>() {});
            if (subCached.isPresent()) {
                log.info("Returning cached sub-admin client details for user {}", subAdminId);
                return subCached.get();
            }
            List<Client> clients = clientRepository.findByOwnerAdminId(subAdminId);
            log.info("SUB_ADMIN {} owned clients: {}", subAdminId, clients.size());
            if (clients.isEmpty()) return new ArrayList<>();
            List<BillOpsClientDetailsDto> result = buildClientDtos(clients);
            redisCacheService.put(subCacheKey, result, CLIENTS_CACHE_TTL_MINUTES);
            return result;
        }

        // 0. Return cached response if available (primary admin only)
        Optional<List<BillOpsClientDetailsDto>> cached = redisCacheService.get(
                CLIENTS_CACHE_KEY, new TypeReference<List<BillOpsClientDetailsDto>>() {});
        if (cached.isPresent()) {
            log.info("Returning client details from Redis cache");
            return cached.get();
        }

        // 1. Fetch all clients and build response
        List<Client> clients = clientRepository.findAll();
        if (clients.isEmpty()) return new ArrayList<>();
        List<BillOpsClientDetailsDto> result = buildClientDtos(clients);
        redisCacheService.put(CLIENTS_CACHE_KEY, result, CLIENTS_CACHE_TTL_MINUTES);
        return result;
    }

    /** Builds BillOpsClientDetailsDto list for a given set of clients (fetches costs in parallel). */
    private List<BillOpsClientDetailsDto> buildClientDtos(List<Client> clients) {
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

        List<String> allCacheKeys = allAccounts.stream()
                .map(a -> {
                    String id = a.getProviderAccountId();
                    if (id == null || id.isEmpty()) return null;
                    if ("AWS".equalsIgnoreCase(a.getProvider()))   return "AWS_MTD_COST::" + id;
                    if ("GCP".equalsIgnoreCase(a.getProvider()))   return "GCP_MONTHLY_TOTAL::" + id + "::" + currentMonth;
                    if ("Azure".equalsIgnoreCase(a.getProvider())) return "AZURE_COST_BILLING_SUMMARY::" + id;
                    return null;
                })
                .filter(k -> k != null)
                .collect(Collectors.toList());

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

        // Live fallback — only for accounts not yet in DB cache (new accounts / first load)
        if (!cacheMissAccounts.isEmpty()) {
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

        // ── AWS credits — parallel (no DB cache exists for credits) ──────────────────
        Map<Long, Double> creditsByAccountId = new HashMap<>();
        List<CompletableFuture<Double>> creditFutures = allAccounts.stream().map(account -> {
            String providerAccId = account.getProviderAccountId();
            String provider = account.getProvider();
            if ("AWS".equalsIgnoreCase(provider) && providerAccId != null && !providerAccId.isEmpty()) {
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
                accDto.setCreditsApplied(creditsByAccountId.getOrDefault(account.getId(), 0.0));
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
            dto.put("markupPercent", payer.getMarkupPercent());
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

    /**
     * Set (or update) the markup percentage for a payer account.
     * All billing rows will be multiplied by (1 + markupPercent/100) during the next extraction.
     * Re-run extract-all after updating to regenerate CSVs with the new markup applied.
     *
     * PUT /api/xamops/billops-admin/marketplace/payers/{id}/markup
     * Body: { "markupPercent": 5.3 }
     */
    @PutMapping("/marketplace/payers/{id}/markup")
    public ResponseEntity<Map<String, Object>> updateMarkup(
            @PathVariable Long id,
            @RequestBody Map<String, Double> body) {
        double markupPercent = body.getOrDefault("markupPercent", 0.0);
        MarketplacePayerAccount payer = marketplacePayerAccountRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Payer account not found: " + id));
        payer.setMarkupPercent(markupPercent);
        marketplacePayerAccountRepository.save(payer);
        log.info("Updated markup for payer {} ({}) to {}%",
                payer.getId(), payer.getAccountName(), markupPercent);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("payerAccountId", payer.getId());
        response.put("accountName", payer.getAccountName());
        response.put("markupPercent", payer.getMarkupPercent());
        response.put("message", "Markup updated. Re-run extract-all to apply to billing CSVs.");
        return ResponseEntity.ok(response);
    }

    // =========================================================
    // COST HISTORY SNAPSHOT ENDPOINTS
    // =========================================================

    /**
     * Pre-extract ALL historical cost months (Jan 2025 → last month) for every
     * active AWS account and store them in cached_data with absolute month keys.
     *
     * Run this ONCE before Billing Conductor is applied (before April 1st).
     * Already-snapshotted months are skipped automatically.
     *
     * POST /api/xamops/billops-admin/snapshot-cost-history
     */
    @PostMapping("/snapshot-cost-history")
    public ResponseEntity<Map<String, Object>> snapshotCostHistory() {
        log.info("Starting historical cost snapshot (pre-Billing-Conductor extraction)...");
        Map<String, String> accountResults = costDataSnapshotService.snapshotAllHistoricalMonths();

        long ok   = accountResults.values().stream().filter(v -> v.startsWith("OK")).count();
        long fail = accountResults.size() - ok;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", fail == 0 ? "COMPLETE" : "PARTIAL");
        response.put("accountsProcessed", accountResults.size());
        response.put("accountsOk", ok);
        response.put("accountsFailed", fail);
        response.put("details", accountResults);
        response.put("message", "Historical cost snapshots saved to cached_data. Closed months will now " +
                "be served from DB snapshots instead of the CE API.");
        return ResponseEntity.ok(response);
    }

    /**
     * Snapshot current month's final cost data for all AWS accounts.
     * Normally called automatically by the end-of-month scheduler but can be
     * triggered manually if needed.
     *
     * POST /api/xamops/billops-admin/snapshot-current-month
     */
    @PostMapping("/snapshot-current-month")
    public ResponseEntity<Map<String, String>> snapshotCurrentMonth() {
        log.info("Manual trigger: snapshotting current month final cost data...");
        costDataSnapshotService.snapshotCurrentMonthFinal();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("message", "Current month cost data snapshotted for all AWS accounts.");
        return ResponseEntity.ok(response);
    }

    /**
     * Returns a per-account, per-month snapshot coverage report.
     * Shows which months are fully saved, partially saved, or missing.
     *
     * Response per account:
     *   status        → COMPLETE | INCOMPLETE | PARTIAL_KEYS | NOT_STARTED
     *   complete      → list of YYYY-MM months where all 7 key types are present
     *   missing       → list of YYYY-MM months where no key types are present
     *   partial       → map of YYYY-MM → [missing key type names] (some keys saved, some not)
     *
     * GET /api/xamops/billops-admin/snapshot-status
     */
    @GetMapping("/snapshot-status")
    public ResponseEntity<Map<String, Object>> getSnapshotStatus() {
        log.info("Fetching snapshot coverage status...");
        Map<String, Object> report = costDataSnapshotService.getSnapshotStatusReport();
        return ResponseEntity.ok(report);
    }

    // =========================================================
    // CLIENT SAVINGS ENDPOINT
    // =========================================================

    @GetMapping("/clients/{clientId}/savings")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<ClientSavingsDashboardDto> getClientSavings(
            @PathVariable Long clientId,
            @RequestParam(required = false) String preset,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        LocalDate[] range = resolveSavingsRange(preset, startDate, endDate);
        ClientSavingsDashboardDto dto = clientSavingsService.getSavings(clientId, range[0], range[1]);
        return ResponseEntity.ok(dto);
    }

    private LocalDate[] resolveSavingsRange(String preset, String startDate, String endDate) {
        LocalDate end = LocalDate.now();
        if (preset != null) {
            return switch (preset) {
                case "7d"  -> new LocalDate[]{ end.minusDays(7),  end };
                case "90d" -> new LocalDate[]{ end.minusDays(90), end };
                default    -> new LocalDate[]{ end.minusDays(30), end };
            };
        }
        if (startDate != null && endDate != null) {
            try {
                LocalDate start = LocalDate.parse(startDate);
                LocalDate end2 = LocalDate.parse(endDate);
                if (start.isAfter(end2)) {
                    throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "startDate must be on or before endDate");
                }
                return new LocalDate[]{ start, end2 };
            } catch (java.time.format.DateTimeParseException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid date format, expected YYYY-MM-DD");
            }
        }
        return new LocalDate[]{ end.minusDays(30), end };
    }
}
