package com.xammer.billops.controller;

import com.xammer.billops.config.multitenancy.ImpersonationContext; // ✅ Added
import com.xammer.billops.config.multitenancy.TenantContext; // ✅ Added
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.User; // ✅ Added (Note: User entity is often shared/copied)
import com.xammer.billops.dto.*;
import com.xammer.billops.dto.GlobalUserDto; // ✅ Added
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserRepository; // ✅ Added
import com.xammer.billops.service.AwsAccountService;
import com.xammer.billops.service.GcpDataService;
import com.xammer.billops.service.MasterDatabaseService; // ✅ Added
import com.xammer.billops.service.azure.AzureCostManagementService;
import com.xammer.cloud.security.ClientUserDetails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private UserRepository userRepository; // ✅ Injected for Impersonation lookup

    @Autowired
    private AzureCostManagementService azureCostService;

    @Autowired
    private MasterDatabaseService masterDatabaseService; // ✅ Injected for Tenant Fallback

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

        logger.info("========== GET ACCOUNTS REQUEST (BILLOPS) ==========");

        // 1. Authentication Check
        if (authentication == null || userDetails == null) {
            logger.error("✗ Authentication object is NULL");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication required", "message",
                            "No authentication found in security context"));
        }

        logger.info("✓ Authentication present. Username: {}", userDetails.getUsername());

        // 2. Tenant Context Fallback (If Filter missed it)
        String currentTenant = TenantContext.getCurrentTenant();
        if (currentTenant == null || "default".equals(currentTenant)) {
            try {
                String username = userDetails.getUsername();
                Optional<GlobalUserDto> globalUser = masterDatabaseService.findGlobalUser(username);
                if (globalUser.isPresent()) {
                    String resolvedTenantId = globalUser.get().getTenantId();
                    if (!resolvedTenantId.equals(currentTenant)) {
                        logger.info("⚠ Forcing Tenant Context Switch: {} -> {}", currentTenant, resolvedTenantId);
                        TenantContext.setCurrentTenant(resolvedTenantId);
                    }
                }
            } catch (Exception e) {
                logger.error("✗ Error during Tenant fallback resolution: {}", e.getMessage());
            }
        }

        // 3. Handle Impersonation Logic
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

        // 4. Check Roles
        boolean isAdmin = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role) || "ROLE_XAMOPS_ADMIN".equals(role));

        List<CloudAccount> accounts;

        // If Admin AND NOT Impersonating, show ALL accounts.
        // If Impersonating, show only THAT USER's accounts.
        if (isAdmin && impersonatedUserId == null) {
            logger.info("User is admin (no impersonation) - fetching all accounts");
            accounts = cloudAccountRepository.findAll();
        } else {
            logger.info("Fetching accounts for Client ID: {}", targetClientId);
            accounts = cloudAccountRepository.findByClientId(targetClientId);
        }

        logger.info("✓ Found {} accounts in Tenant: {}", accounts.size(), TenantContext.getCurrentTenant());

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

    @PostMapping("/accounts/{accountId}/monitoring")
    public ResponseEntity<?> updateMonitoringEndpoint(@PathVariable String accountId,
            @RequestBody Map<String, String> payload) {
        String grafanaIp = payload.get("grafanaIp");
        if (grafanaIp == null || grafanaIp.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "grafanaIp is required in the request body."));
        }

        // Find account by AWS Account ID, GCP Project ID, or Azure Subscription ID
        // Note: Ensure findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId exists in
        // BillOps CloudAccountRepository
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

    // @PostMapping("/azure")
    // public ResponseEntity<?> addAzureAccount(@RequestBody AzureAccountRequestDto
    // request,
    // @AuthenticationPrincipal ClientUserDetails userDetails) {
    // Long clientId = userDetails.getClientId();

    // try {
    // Client client = clientRepository.findById(clientId)
    // .orElseThrow(() -> new RuntimeException("Client not found for ID: " +
    // clientId));

    // CloudAccount newAccount = new CloudAccount();
    // newAccount.setAccountName(request.getAccountName());
    // newAccount.setProvider("Azure");
    // newAccount.setAzureTenantId(request.getTenantId());
    // newAccount.setAzureSubscriptionId(request.getSubscriptionId());
    // newAccount.setAzureClientId(request.getClientId());
    // newAccount.setAzureClientSecret(request.getClientSecret());
    // newAccount.setStatus("CONNECTED"); // Set to CONNECTED, as setup follows
    // newAccount.setClient(client);
    // newAccount.setAccessType(request.getAccess());
    // newAccount.setExternalId(request.getPrincipalId());

    // // First save to get an ID and basic info
    // CloudAccount savedAccount = cloudAccountRepository.save(newAccount);

    // try {
    // // 1. Call setupCostExports, which now returns the path info
    // Map<String, String> exportConfig =
    // azureCostService.setupCostExports(savedAccount, request);

    // // 2. Get the names and save them to the account
    // savedAccount.setAzureBillingContainer(exportConfig.get("containerName"));
    // savedAccount.setAzureBillingDirectory(exportConfig.get("directoryName"));

    // // 3. Re-save the account with the new path information
    // CloudAccount finalAccount = cloudAccountRepository.save(savedAccount);
    // return ResponseEntity.ok(finalAccount);

    // } catch (Exception e) {
    // logger.error("Azure account {} added, but cost export setup failed: {}",
    // savedAccount.getId(),
    // e.getMessage());
    // return ResponseEntity.ok(savedAccount);
    // }

    // } catch (Exception e) {
    // logger.error("Error adding Azure account (pre-cost-setup)", e);
    // return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    // .body("An unexpected error occurred while adding the account.");
    // }
    // }
}