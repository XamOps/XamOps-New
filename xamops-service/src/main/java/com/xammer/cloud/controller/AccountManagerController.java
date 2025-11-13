package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsAccountService;
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
import java.util.stream.Collectors;
import com.xammer.cloud.dto.azure.AzureAccountRequestDto;
import org.springframework.http.HttpStatus;

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

    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request, @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();
        try {
            Map<String, Object> result = awsAccountService.generateCloudFormationUrl(request.getAccountName(), request.getAwsAccountId(), request.getAccessType(), clientId);
            Map<String, String> stackDetails = Map.of(
                    "url", result.get("url").toString(),
                    "externalId", result.get("externalId").toString()
            );
            return ResponseEntity.ok(stackDetails);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not generate CloudFormation URL", "message", e.getMessage()));
        }
    }

    @PostMapping("/verify-account")
    public ResponseEntity<?> verifyAccount(@RequestBody VerifyAccountRequest request) {
        try {
            CloudAccount verifiedAccount = awsAccountService.verifyAccount(request);
            return ResponseEntity.ok(Map.of("message", "Account " + verifiedAccount.getAccountName() + " connected successfully!"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account verification failed", "message", e.getMessage()));
        }
    }

 @GetMapping("/accounts")
public ResponseEntity<?> getAccounts(@AuthenticationPrincipal ClientUserDetails userDetails, Authentication authentication) {
    
    logger.info("========== GET ACCOUNTS REQUEST ==========");
    
    // Check authentication state
    if (authentication == null) {
        logger.error("✗ Authentication object is NULL");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "Authentication required", "message", "No authentication found in security context"));
    }
    
    logger.info("✓ Authentication present: {}", authentication.isAuthenticated());
    logger.info("  - Principal type: {}", authentication.getPrincipal().getClass().getName());
    logger.info("  - Principal value: {}", authentication.getPrincipal());
    
    // Check if userDetails was properly deserialized
    if (userDetails == null) {
        logger.error("✗ ClientUserDetails is NULL - Session deserialization failed");
        logger.error("  - Authentication principal type: {}", authentication.getPrincipal().getClass().getName());
        logger.error("  - This usually means Redis session could not deserialize ClientUserDetails");
        logger.error("  - SOLUTION: Clear Redis sessions and re-login");
        
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of(
                "error", "Session deserialization failed",
                "message", "User details could not be restored from session. Please log out and log back in.",
                "principalType", authentication.getPrincipal().getClass().getName()
            ));
    }
    
    logger.info("✓ ClientUserDetails restored successfully");
    logger.info("  - Username: {}", userDetails.getUsername());
    logger.info("  - Client ID: {}", userDetails.getClientId());
    logger.info("  - Authorities: {}", userDetails.getAuthorities());
    
    // Continue with original logic
    boolean isAdmin = userDetails.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role));
    
    List<CloudAccount> accounts;
    if (isAdmin) {
        logger.info("User is admin - fetching all accounts");
        accounts = cloudAccountRepository.findAll();
    } else {
        Long clientId = userDetails.getClientId();
        logger.info("Fetching accounts for Client ID: {}", clientId);
        accounts = cloudAccountRepository.findByClientId(clientId);
    }
    
    logger.info("✓ Found {} accounts", accounts.size());
    
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
                    return ResponseEntity.ok(Map.of("message", "Account " + account.getAccountName() + " removed successfully."));
                }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/add-gcp-account")
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto gcpAccountRequestDto, @AuthenticationPrincipal ClientUserDetails userDetails) {
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
        dto.setGrafanaIp(account.getGrafanaIp()); // <-- This is the new line you must add
        return dto;
    }


    @PostMapping("/azure")
    public ResponseEntity<?> addAzureAccount(@RequestBody AzureAccountRequestDto request, @AuthenticationPrincipal ClientUserDetails userDetails) {
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
            newAccount.setStatus("CONNECTED");
            newAccount.setClient(client);

            CloudAccount savedAccount = cloudAccountRepository.save(newAccount);
            return ResponseEntity.ok(savedAccount);

        } catch (Exception e) {
            logger.error("Error adding Azure account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred while adding the account.");
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