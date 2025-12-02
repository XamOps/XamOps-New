package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.ImpersonationContext; // ✅ ADD IMPORT
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User; // ✅ ADD IMPORT
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.UserRepository; // ✅ ADD IMPORT
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.MasterDatabaseService;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import com.xammer.cloud.dto.azure.AzureAccountRequestDto;
import org.springframework.http.HttpStatus;
import com.xammer.cloud.service.azure.AzureCostManagementService;

@RestController
@RequestMapping("/api/xamops/account-manager")
public class AccountManagerController {

    private static final Logger logger = LoggerFactory.getLogger(AccountManagerController.class);

    @Autowired
    private AwsAccountService awsAccountService;

    @Autowired
    private GcpDataService gcpDataService;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository; // ✅ INJECT USER REPOSITORY

    @Autowired
    private AzureCostManagementService azureCostService;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();
        try {
            Map<String, Object> result = awsAccountService.generateCloudFormationUrl(request.getAccountName(),
                    request.getAwsAccountId(), request.getAccessType(), clientId);
            Map<String, String> stackDetails = Map.of(
                    "url", result.get("url").toString(),
                    "externalId", result.get("externalId").toString());
            return ResponseEntity.ok(stackDetails);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not generate CloudFormation URL", "message", e.getMessage()));
        }
    }

    @PostMapping("/verify-account")
    public ResponseEntity<?> verifyAccount(@RequestBody VerifyAccountRequest request) {
        try {
            CloudAccount verifiedAccount = awsAccountService.verifyAccount(request);
            return ResponseEntity
                    .ok(Map.of("message", "Account " + verifiedAccount.getAccountName() + " connected successfully!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Account verification failed", "message", e.getMessage()));
        }
    }

    @GetMapping("/accounts")
    public ResponseEntity<?> getAccounts(@AuthenticationPrincipal ClientUserDetails userDetails,
            Authentication authentication) {

        logger.info("========== GET ACCOUNTS REQUEST ==========");

        if (authentication == null || userDetails == null) {
            logger.error("✗ Authentication object is NULL");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required"));
        }

        // 1. Force Tenant Context if missing (Fallback Logic)
        String currentTenant = TenantContext.getCurrentTenant();
        if (currentTenant == null || "default".equals(currentTenant)) {
            try {
                String username = userDetails.getUsername();
                logger.warn("⚠ Tenant Context is '{}'. Attempting to resolve tenant for user '{}' via Master DB...",
                        currentTenant, username);

                Optional<GlobalUserDto> globalUser = masterDatabaseService.findGlobalUser(username);
                if (globalUser.isPresent()) {
                    String resolvedTenantId = globalUser.get().getTenantId();
                    logger.info("✓ Resolved Tenant ID: {}. Switching Context now.", resolvedTenantId);
                    TenantContext.setCurrentTenant(resolvedTenantId);
                }
            } catch (Exception e) {
                logger.error("✗ Error during Tenant fallback resolution: {}", e.getMessage());
            }
        }

        // 2. Determine Target Client ID (Handle Impersonation)
        Long targetClientId = userDetails.getClientId();
        Long impersonatedUserId = ImpersonationContext.getImpersonatedUserId();

        if (impersonatedUserId != null) {
            logger.info("ℹ Impersonation Active. Target User ID: {}", impersonatedUserId);
            // Fetch the impersonated user from the CURRENT tenant DB
            Optional<User> impersonatedUserOpt = userRepository.findById(impersonatedUserId);

            if (impersonatedUserOpt.isPresent()) {
                User impersonatedUser = impersonatedUserOpt.get();
                if (impersonatedUser.getClient() != null) {
                    targetClientId = impersonatedUser.getClient().getId();
                    logger.info("✓ Switched Client ID to Impersonated User's Client: {}", targetClientId);
                } else {
                    logger.warn("⚠ Impersonated user found but has no associated Client.");
                }
            } else {
                logger.warn("⚠ Impersonated User ID {} not found in current tenant DB.", impersonatedUserId);
            }
        }

        logger.info("✓ Active Tenant Context: {}", TenantContext.getCurrentTenant());
        logger.info("Fetching accounts for Client ID: {}", targetClientId);

        // 3. Fetch Accounts
        boolean isAdmin = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role) || "ROLE_XAMOPS_ADMIN".equals(role));

        List<CloudAccount> accounts;

        // Only fetch ALL if admin AND NOT impersonating.
        // If impersonating, we usually want to see exactly what that user sees.
        if (isAdmin && impersonatedUserId == null) {
            logger.info("User is admin (no impersonation) - fetching all accounts");
            accounts = cloudAccountRepository.findAll();
        } else {
            // Fetch specific accounts for the target client (Self or Impersonated)
            accounts = cloudAccountRepository.findByClientId(targetClientId);
        }

        logger.info("✓ Found {} accounts in DB: {}", accounts.size(), TenantContext.getCurrentTenant());

        return ResponseEntity.ok(accounts.stream()
                .map(this::mapToAccountDto)
                .collect(Collectors.toList()));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> deleteAccount(@PathVariable Long id) {
        return cloudAccountRepository.findById(id)
                .map(account -> {
                    awsAccountService.clearAllCaches();
                    cloudAccountRepository.delete(account);
                    return ResponseEntity
                            .ok(Map.of("message", "Account " + account.getAccountName() + " removed successfully."));
                }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/add-gcp-account")
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto gcpAccountRequestDto,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();
        Client client = clientRepository.findById(clientId).orElse(null);
        gcpDataService.createGcpAccount(gcpAccountRequestDto, client);
        return ResponseEntity.ok(Map.of("message", "GCP Account added successfully."));
    }

    private AccountDto mapToAccountDto(CloudAccount account) {
        String connectionType;
        String accountIdentifier;

        switch (account.getProvider()) {
            case "AWS":
                connectionType = "Cross-account role";
                accountIdentifier = account.getAwsAccountId();
                break;
            case "GCP":
                connectionType = "Workload Identity Federation";
                accountIdentifier = account.getGcpProjectId();
                break;
            case "Azure":
                connectionType = "Service Principal";
                accountIdentifier = account.getAzureSubscriptionId();
                break;
            default:
                connectionType = "Unknown";
                accountIdentifier = "N/A";
                break;
        }
        AccountDto dto = new AccountDto(
                account.getId(),
                account.getAccountName(),
                accountIdentifier,
                account.getAccessType(),
                connectionType,
                account.getStatus(),
                account.getRoleArn(),
                account.getExternalId(),
                account.getProvider());
        dto.setGrafanaIp(account.getGrafanaIp());
        return dto;
    }

    @PostMapping("/azure")
    public ResponseEntity<?> addAzureAccount(@RequestBody AzureAccountRequestDto request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();

        try {
            Client client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new RuntimeException("Client not found for ID: " + clientId));

            CloudAccount newAccount = new CloudAccount();
            newAccount.setAccountName(request.getAccountName());
            newAccount.setProvider("Azure");
            newAccount.setAzureTenantId(request.getTenantId());
            newAccount.setAzureSubscriptionId(request.getSubscriptionId());
            newAccount.setAzureClientId(request.getClientId());
            newAccount.setAzureClientSecret(request.getClientSecret());
            newAccount.setStatus("CONNECTED"); // Set to CONNECTED, as setup follows
            newAccount.setClient(client);
            newAccount.setAccessType(request.getAccess());
            newAccount.setExternalId(request.getPrincipalId());

            // First save to get an ID and basic info
            CloudAccount savedAccount = cloudAccountRepository.save(newAccount);

            try {
                Map<String, String> exportConfig = azureCostService.setupCostExports(savedAccount, request);
                savedAccount.setAzureBillingContainer(exportConfig.get("containerName"));
                savedAccount.setAzureBillingDirectory(exportConfig.get("directoryName"));
                CloudAccount finalAccount = cloudAccountRepository.save(savedAccount);
                return ResponseEntity.ok(finalAccount);

            } catch (Exception e) {
                logger.error("Azure account {} added, but cost export setup failed: {}", savedAccount.getId(),
                        e.getMessage());
                return ResponseEntity.ok(savedAccount);
            }

        } catch (Exception e) {
            logger.error("Error adding Azure account (pre-cost-setup)", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred while adding the account.");
        }
    }

    @PostMapping("/accounts/{accountId}/monitoring")
    public ResponseEntity<?> updateMonitoringEndpoint(@PathVariable String accountId,
            @RequestBody Map<String, String> payload) {
        String grafanaIp = payload.get("grafanaIp");
        if (grafanaIp == null || grafanaIp.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "grafanaIp is required in the request body."));
        }

        CloudAccount account = cloudAccountRepository
                .findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(accountId, accountId, accountId)
                .orElse(null);

        if (account == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Account not found with ID: " + accountId));
        }

        account.setGrafanaIp(grafanaIp);
        cloudAccountRepository.save(account);

        return ResponseEntity.ok()
                .body(Map.of("message", "Monitoring endpoint updated successfully for account: " + accountId));
    }
}