package com.xammer.billops.controller;

import com.xammer.billops.config.multitenancy.ImpersonationContext; // ✅ Added
import com.xammer.billops.config.multitenancy.TenantContext; // ✅ Added
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.dto.*;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.service.AwsAccountService;
import com.xammer.billops.service.GcpDataService;
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

import java.util.ArrayList;
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
    private AppUserRepository appUserRepository; // ✅ Injected for Impersonation lookup

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private AzureCostManagementService azureCostService;


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


        // 3. Handle Impersonation Logic
        Long targetClientId = userDetails.getClientId();
        Long impersonatedUserId = ImpersonationContext.getImpersonatedUserId();

        if (impersonatedUserId != null) {
            logger.info("ℹ Impersonation Active. Target User ID: {}", impersonatedUserId);
            // Fetch the impersonated user from the CURRENT tenant DB
            Optional<AppUser> impersonatedUserOpt = appUserRepository.findById(impersonatedUserId);

            if (impersonatedUserOpt.isPresent()) {
                AppUser impersonatedUser = impersonatedUserOpt.get();
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
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role) || "ROLE_BILLOPS_ADMIN_SUB".equals(role) || "ROLE_XAMOPS_ADMIN".equals(role));

        List<CloudAccount> accounts;

        // If Admin AND NOT Impersonating, show ALL accounts.
        // If Impersonating, show only THAT USER's accounts.
        if (isAdmin && impersonatedUserId == null) {
            logger.info("User is admin (no impersonation) - fetching all accounts");
            accounts = cloudAccountRepository.findAll();
        } else {
            // Fetch specific accounts for the target client (Self or Impersonated)
            Long userId = userDetails.getId();
            if (impersonatedUserId != null) {
                userId = impersonatedUserId;
            }

            // Check if user is a sub-user (has createdBy set)
            Optional<AppUser> userOpt = appUserRepository.findById(userId);
            if (userOpt.isPresent() && userOpt.get().getCreatedBy() != null) {
                // Sub-user: return only assigned accounts
                logger.info("Sub-user detected (userId: {}) - fetching only assigned accounts", userId);
                List<Long> accessibleAccountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);
                logger.info("Accessible account IDs for sub-user: {}", accessibleAccountIds);

                if (accessibleAccountIds.isEmpty()) {
                    accounts = new ArrayList<>();
                } else {
                    accounts = cloudAccountRepository.findAllById(accessibleAccountIds);
                }
            } else {
                // Primary user or admin: return all client accounts
                logger.info("Fetching accounts for Client ID: {}", targetClientId);
                accounts = cloudAccountRepository.findByClientId(targetClientId);
            }
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

    @PutMapping("/accounts/{accountId}/billing-table")
    public ResponseEntity<?> updateBillingTable(@PathVariable Long accountId,
            @RequestBody Map<String, String> payload) {
        String billingExportTable = payload.get("billingExportTable");
        if (billingExportTable == null || billingExportTable.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "billingExportTable is required in the request body."));
        }

        try {
            gcpDataService.updateBillingTable(accountId, billingExportTable.trim());
            return ResponseEntity.ok()
                    .body(Map.of("message", "Billing export table updated successfully."));
        } catch (Exception e) {
            logger.error("Failed to update billing table for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("message", e.getMessage()));
        }
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
        dto.setBillingExportTable(account.getBillingExportTable());

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