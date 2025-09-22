package com.xammer.cloud.controller;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.xammer.cloud.dto.azure.AzureAccountRequestDto;
import com.xammer.cloud.service.azure.AzureClientProvider;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;


@RestController
@RequestMapping("/api/xamops/account-manager")
public class AccountManagerController {

    private final AwsAccountService awsAccountService;
    private final GcpDataService gcpDataService;
    private final CloudAccountRepository cloudAccountRepository;
    private final ClientRepository clientRepository;
    private AzureClientProvider azureClientProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();



    public AccountManagerController(AwsAccountService awsAccountService, GcpDataService gcpDataService, CloudAccountRepository cloudAccountRepository, ClientRepository clientRepository) {
        this.awsAccountService = awsAccountService;
        this.gcpDataService = gcpDataService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.clientRepository = clientRepository;

    }

    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
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
    public List<AccountDto> getAccounts(Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        return cloudAccountRepository.findByClientId(clientId).stream()
                .map(this::mapToAccountDto)
                .collect(Collectors.toList());
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
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto gcpAccountRequestDto, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        Client client = clientRepository.findById(clientId).orElse(null);
        try {
            gcpDataService.createGcpAccount(gcpAccountRequestDto, client);
            return ResponseEntity.ok(Map.of("message", "GCP Account added successfully."));
        } catch (java.io.IOException e) {
            return ResponseEntity.status(500).body("IOException: " + e.getMessage());
        }
    }

    private AccountDto mapToAccountDto(CloudAccount account) {
        String connectionType;
        String accountIdentifier;

        // This switch statement ensures the correct ID is used for each provider
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
                accountIdentifier = account.getAzureSubscriptionId(); // This line fixes the issue
                break;
            default:
                connectionType = "Unknown";
                accountIdentifier = "N/A";
                break;
        }

        return new AccountDto(
                account.getId(),
                account.getAccountName(),
                accountIdentifier,
                account.getAccessType(),
                connectionType,
                account.getStatus(),
                account.getRoleArn(),
                account.getExternalId(),
                account.getProvider()
        );
    }
    @PostMapping("/accounts/azure")
    public ResponseEntity<?> addAzureAccount(@RequestBody AzureAccountRequestDto request, Authentication authentication) {
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new RuntimeException("Client not found"));

        try {
            // The request DTO now directly contains the credential fields
            String tenantId = request.getTenantId();
            String subscriptionId = request.getSubscriptionId();
            String clientIdStr = request.getClientId();
            String clientSecret = request.getClientSecret();

            if (tenantId == null || tenantId.isEmpty() ||
                    subscriptionId == null || subscriptionId.isEmpty() ||
                    clientIdStr == null || clientIdStr.isEmpty() ||
                    clientSecret == null || clientSecret.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("The provided JSON is missing required fields (tenantId, subscriptionId, clientId, clientSecret).");
            }

            // Note: Ensure AzureClientProvider is properly injected if you have one for validation
            // boolean credentialsValid = azureClientProvider.verifyCredentials(tenantId, subscriptionId, clientIdStr, clientSecret);
            // if (!credentialsValid) {
            //     return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid Azure credentials provided.");
            // }

            CloudAccount newAccount = new CloudAccount();
            newAccount.setAccountName(request.getAccountName());
            newAccount.setProvider("Azure");
            newAccount.setAzureTenantId(tenantId);
            newAccount.setAzureSubscriptionId(subscriptionId);
            newAccount.setAzureClientId(clientIdStr);
            newAccount.setAzureClientSecret(clientSecret); // Encryption is strongly recommended for secrets
            newAccount.setStatus("CONNECTED");
            newAccount.setClient(client);

            CloudAccount savedAccount = cloudAccountRepository.save(newAccount);
            return ResponseEntity.ok(savedAccount);

        } catch (Exception e) {
            // Log the exception for debugging
            System.err.println("Error adding Azure account: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred while adding the account.");
        }
    }
}
