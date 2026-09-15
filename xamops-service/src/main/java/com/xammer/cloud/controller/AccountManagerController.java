package com.xammer.cloud.controller;

import com.xammer.cloud.config.multitenancy.ImpersonationContext;
import com.xammer.cloud.config.multitenancy.TenantContext;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.UserCloudAccountAccess;
import com.xammer.cloud.dto.*;
import com.xammer.cloud.event.CloudAccountConnectedEvent;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.stack.StackDiagnosisService;
import com.xammer.cloud.service.stack.StackUpdateService;
import com.xammer.cloud.repository.UserCloudAccountAccessRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.MasterDatabaseService;
import com.xammer.cloud.service.gcp.GcpDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.ArrayList;
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
    private StackDiagnosisService stackDiagnosisService;

    @Autowired
    private StackUpdateService stackUpdateService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;

    @Autowired
    private AzureCostManagementService azureCostService;

    @Autowired
    private MasterDatabaseService masterDatabaseService;

    /** Announces a newly connected subscription so the onboarding WAR report can run. */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @PostMapping("/generate-stack-url")
    public ResponseEntity<Map<String, String>> generateStackUrl(@RequestBody AccountCreationRequestDto request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();
        try {
            Map<String, Object> result = awsAccountService.generateCloudFormationUrl(request.getAccountName(),
                    request.getAwsAccountId(), request.getAccessType(), clientId, request.getCurS3Bucket(),
                    request.getAwsBillingSource());
            Map<String, String> stackDetails = Map.of(
                    "url", result.get("url").toString(),
                    "externalId", result.get("externalId").toString(),
                    "eventBridgeEnabled", result.get("eventBridgeEnabled").toString(),
                    "roleName", result.get("roleName").toString());
            return ResponseEntity.ok(stackDetails);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not generate CloudFormation URL", "message", e.getMessage()));
        }
    }

    /**
     * What it takes to bring an already-connected account onto the current template.
     *
     * <p>Returns instructions rather than a link because CloudFormation quick-create links
     * cannot update an existing stack — see
     * {@link com.xammer.cloud.service.AwsAccountService#generateStackUpdateGuide}. Nothing
     * is written and no ExternalId is re-issued.
     */
    @PostMapping("/accounts/{id}/upgrade-stack-url")
    public ResponseEntity<?> generateStackUpdateGuide(@PathVariable Long id,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            CloudAccount account = cloudAccountRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + id));

            // The guide names a role and a stack in a specific AWS account; handing one to
            // the wrong tenant would walk them through changing somebody else's connection.
            if (account.getClient() == null
                    || !account.getClient().getId().equals(userDetails.getClientId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Account does not belong to this client"));
            }
            if (!"AWS".equalsIgnoreCase(account.getProvider())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Only AWS accounts have a CloudFormation stack to update"));
            }

            return ResponseEntity.ok(awsAccountService.generateStackUpdateGuide(account));
        } catch (IllegalStateException e) {
            // No template configured for this environment — not the caller's fault.
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Could not prepare the update guide",
                            "message", String.valueOf(e.getMessage())));
        }
    }

    @PostMapping("/eventbridge-callback")
    public ResponseEntity<?> eventBridgeCallback(@RequestBody Map<String, String> payload) {
        String externalId = payload.get("externalId");
        String awsAccountId = payload.get("awsAccountId");
        String roleArn = payload.get("roleArn");
        if (externalId == null || awsAccountId == null || roleArn == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields: externalId, awsAccountId, roleArn"));
        }
        // Optional: only templates from this release onward send it. An older stack connects
        // exactly as before and has its id discovered later from the role it created.
        String stackId = payload.get("stackId");
        try {
            awsAccountService.connectViaCallback(externalId, awsAccountId, roleArn, stackId);
            logger.info("EventBridge callback succeeded for externalId={}", externalId);
            return ResponseEntity.ok(Map.of("message", "Account connected successfully"));
        } catch (Exception e) {
            logger.error("EventBridge callback failed for externalId={}: {}", externalId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * What updating this account's stack would change, without changing anything.
     *
     * <p>Builds a change set and describes it. A stack that already matches comes back with
     * {@code noChanges}, which is a success — not an error — because once a fleet has been
     * swept it is the commonest answer.
     */
    @PostMapping("/stack-update/{accountId}/preview")
    public ResponseEntity<?> previewStackUpdate(@PathVariable String accountId) {
        return withAwsAccount(accountId, account ->
                ResponseEntity.ok(stackUpdateService.preview(account)));
    }

    /**
     * Runs a change set produced by the preview.
     *
     * <p>Takes the change set id rather than recomputing, so what executes is exactly what
     * was shown. A stale id fails at AWS rather than silently applying something else.
     */
    @PostMapping("/stack-update/{accountId}/execute")
    public ResponseEntity<?> executeStackUpdate(@PathVariable String accountId,
                                                @RequestBody Map<String, String> body) {
        return withAwsAccount(accountId, account -> {
            stackUpdateService.execute(account, body.get("changeSetId"));
            return ResponseEntity.accepted().body(Map.of("message", "Update started"));
        });
    }

    /** Stack status and recent events, for following an update as it runs. */
    @GetMapping("/stack-update/{accountId}/progress")
    public ResponseEntity<?> stackUpdateProgress(@PathVariable String accountId,
                                                 @RequestParam(defaultValue = "25") int limit) {
        return withAwsAccount(accountId, account ->
                ResponseEntity.ok(stackUpdateService.progress(account, Math.min(limit, 100))));
    }

    /** The innermost message — the SDK buries the useful one several wrappers down. */
    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    /**
     * Resolves an AWS account and turns a stack failure into a body the UI can render.
     *
     * <p>409 rather than 500 for a refusal: "this account has no CloudFormation permission"
     * is a state with a remedy, not a server fault, and the client shows the remedy instead
     * of a stack trace.
     */
    private ResponseEntity<?> withAwsAccount(String accountId,
                                             java.util.function.Function<CloudAccount, ResponseEntity<?>> action) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);
        if (!"AWS".equalsIgnoreCase(account.getProvider())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Stack updates apply to AWS accounts only"));
        }
        try {
            return action.apply(account);
        } catch (StackUpdateService.StackUpdateException e) {
            Map<String, String> body = new HashMap<>();
            body.put("verdict", e.getVerdict());
            body.put("error", e.getMessage());
            if (e.getRemedy() != null) {
                body.put("remedy", e.getRemedy());
            }
            return ResponseEntity.status(409).body(body);
        } catch (Exception e) {
            logger.warn("[stack] Update failed for account {}: {}", accountId, e.getMessage(), e);
            String message = rootMessage(e);
            Map<String, String> body = new HashMap<>();

            // A denial is the expected answer for an account whose role predates stack
            // updates, and it has a fix. Reporting it as "could not reach CloudFormation"
            // sends the reader looking for a network problem that does not exist — and
            // hides the one line AWS gave us that names the missing action.
            if (message.contains("AccessDenied") || message.contains("not authorized")) {
                body.put("verdict", "NO_CFN_PERMISSION");
                body.put("error", message);
                body.put("remedy", "Deploy the XamOps stack updater in this account once "
                        + "(xamops-stack-updater.yml, a small additive stack). It grants "
                        + "change-set permissions scoped to XamOps-* stacks and cannot "
                        + "affect the existing connection. After that this button works on "
                        + "its own.");
                body.put("grantTemplate", "xamops-stack-updater.yml");
                return ResponseEntity.status(409).body(body);
            }

            body.put("error", "CloudFormation rejected the request");
            body.put("detail", message);
            return ResponseEntity.status(502).body(body);
        }
    }

    /**
     * Which CloudFormation stack owns this account's connection, and — when none can be found —
     * why not.
     *
     * <p>Read-only. It answers whether an update could run, never runs one.
     */
    @GetMapping("/stack-status/{accountId}")
    public ResponseEntity<?> stackStatus(@PathVariable String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CloudAccount account = accounts.get(0);
        if (!"AWS".equalsIgnoreCase(account.getProvider())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Stack diagnosis applies to AWS accounts only"));
        }
        return ResponseEntity.ok(stackDiagnosisService.diagnose(account));
    }

    @PostMapping("/verify-account")
    public ResponseEntity<?> verifyAccount(@RequestBody VerifyAccountRequest request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            CloudAccount verifiedAccount = awsAccountService.verifyAccount(request);
            if (userDetails != null) {
                grantSubUserAccess(userDetails, verifiedAccount);
            }
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
                    logger.warn("⚠ Impersonated user found (ID: {}) but has no associated Client.", impersonatedUserId);
                }
            } else {
                logger.warn("⚠ Impersonated User ID {} NOT FOUND in DB. Current Tenant: {}",
                        impersonatedUserId, TenantContext.getCurrentTenant());
                // Fallback: If not found and we are in local, try finding any user by ID
                // regardless of what Hibernate might be doing with TenantContext
                logger.warn("⚠ Fallback: Attempting to find user by ID directly...");
            }
        }

        logger.info("✓ Active Tenant Context: {}", TenantContext.getCurrentTenant());
        logger.info("Fetching accounts for Client ID: {}", targetClientId);

        // 3. Fetch Accounts
        boolean isAdmin = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_BILLOPS_ADMIN".equals(role) || "ROLE_XAMOPS_ADMIN".equals(role)
                        || "ROLE_SUPER_ADMIN".equals(role));

        List<CloudAccount> accounts;

        // Only fetch ALL if admin AND NOT impersonating.
        // If impersonating, we usually want to see exactly what that user sees.
        if (isAdmin && impersonatedUserId == null) {
            logger.info("User is admin (no impersonation) - fetching all accounts");
            accounts = cloudAccountRepository.findAll();
        } else {
            // Fetch specific accounts for the target client (Self or Impersonated)
            Long userId = userDetails.getId();
            if (impersonatedUserId != null) {
                userId = impersonatedUserId;
            }

            Optional<User> userOpt = userRepository.findById(userId);
            List<Long> accessibleAccountIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);

            if (!accessibleAccountIds.isEmpty()) {
                logger.info("Mapped accounts found for user {} - restricting view to {} accounts", userId,
                        accessibleAccountIds.size());
                accounts = cloudAccountRepository.findAllById(accessibleAccountIds);
            } else if (userOpt.isPresent() && userOpt.get().getCreatedBy() == null) {
                // Primary user with no specific mappings: return all client accounts
                logger.info(
                        "Primary user detected (userId: {}) with no specific mappings - showing all client accounts",
                        userId);
                accounts = cloudAccountRepository.findByClientId(targetClientId);
            } else {
                // Sub-user with no mapped accounts: return empty
                logger.info("Sub-user detected (userId: {}) with no mapped accounts - returning empty list", userId);
                accounts = new ArrayList<>();
            }
        }

        logger.info("✓ Fetching accounts for Client ID: {} (Tenant: {})", targetClientId,
                TenantContext.getCurrentTenant());
        logger.info("✓ User Roles: {}", userDetails.getAuthorities());

        // ... Fetch Accounts ...

        return ResponseEntity.ok(accounts.stream()
                .map(this::mapToAccountDto)
                .collect(Collectors.toList()));
    }

    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> deleteAccount(@PathVariable Long id) {
        Optional<CloudAccount> existing = cloudAccountRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String accountName = existing.get().getAccountName();

        try {
            awsAccountService.deleteAccountById(id);
            return ResponseEntity.ok(Map.of("message", "Account " + accountName + " removed successfully."));
        } catch (DataIntegrityViolationException e) {
            // A dependent table we do not clean up yet. Surface the table/constraint the
            // database named so the gap is diagnosable instead of an opaque failure.
            String detail = rootCauseMessage(e);
            logger.error("Deletion of account {} ({}) hit an unhandled foreign key: {}", id, accountName, detail);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "Cannot delete " + accountName
                            + " — it still has linked data in the database. Details: " + detail));
        }
    }

    private String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? t.toString() : message.trim();
    }

    @PostMapping("/add-gcp-account")
    public ResponseEntity<?> addGcpAccount(@RequestBody GcpAccountRequestDto gcpAccountRequestDto,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();
        Client client = clientRepository.findById(clientId).orElse(null);
        try {
            CloudAccount savedAccount = gcpDataService.createGcpAccount(gcpAccountRequestDto, client);
            grantSubUserAccess(userDetails, savedAccount);
            return ResponseEntity.ok(Map.of("message", "GCP Account added successfully."));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
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
        dto.setGrafanaDashboardUid(account.getGrafanaDashboardUid());
        dto.setGrafanaDashboardSlug(account.getGrafanaDashboardSlug());
        dto.setBillingExportTable(account.getBillingExportTable());
        dto.setCspManaged(account.isCspManaged());
        dto.setBillingSource(account.resolveBillingSource().name());
        dto.setBillingDataAvailable(account.getBillingDataAvailable());
        return dto;
    }

    /**
     * Adds or Updates an Azure account based on Subscription ID.
     * Handles upsert logic: inserts if new, updates if existing.
     */
    @PostMapping("/azure")
    public ResponseEntity<?> addAzureAccount(@RequestBody AzureAccountRequestDto request,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        Long clientId = userDetails.getClientId();

        try {
            Client client = clientRepository.findById(clientId)
                    .orElseThrow(() -> new RuntimeException("Client not found for ID: " + clientId));

            // Check if account already exists by Subscription ID
            Optional<CloudAccount> existingAccountOpt = cloudAccountRepository
                    .findByAzureSubscriptionId(request.getSubscriptionId());

            boolean isNewAccount = existingAccountOpt.isEmpty();
            CloudAccount account;
            if (existingAccountOpt.isPresent()) {
                // UPDATE existing account
                account = existingAccountOpt.get();
                logger.info("Updating existing Azure account: {}", account.getAccountName());

                account.setAccountName(request.getAccountName());
                account.setAzureTenantId(request.getTenantId());
                // Subscription ID doesn't change as it is the lookup key
                account.setAzureClientId(request.getClientId());
                account.setAzureClientSecret(request.getClientSecret());
                account.setAccessType(request.getAccess());
                account.setExternalId(request.getPrincipalId());
                account.setStatus("CONNECTED"); // Re-enable status to CONNECTED
            } else {
                // INSERT new account
                logger.info("Creating new Azure account: {}", request.getAccountName());

                account = new CloudAccount();
                account.setAccountName(request.getAccountName());
                account.setProvider("Azure");
                account.setAzureTenantId(request.getTenantId());
                account.setAzureSubscriptionId(request.getSubscriptionId());
                account.setAzureClientId(request.getClientId());
                account.setAzureClientSecret(request.getClientSecret());
                account.setStatus("CONNECTED");
                account.setClient(client);
                account.setAccessType(request.getAccess());
                account.setExternalId(request.getPrincipalId());
            }

            // Save to DB (Insert or Update)
            CloudAccount savedAccount = cloudAccountRepository.save(account);
            if (isNewAccount) {
                grantSubUserAccess(userDetails, savedAccount);
                // Only on a first connection. Re-saving credentials for a subscription the
                // tenant already onboarded is not an onboarding, and should not re-send the
                // "your first security review" email.
                try {
                    eventPublisher.publishEvent(new CloudAccountConnectedEvent(
                            savedAccount.getId(), "Azure", false));
                } catch (Exception e) {
                    logger.warn("Could not publish connected event for Azure account {}: {}",
                            savedAccount.getId(), e.getMessage());
                }
            }

            // Logic to skip export setup based on account type is REMOVED.
            // We now rely on try-catch fallback below.

            try {
                // Attempt to setup or update cost exports
                Map<String, String> exportConfig = azureCostService.setupCostExports(savedAccount, request);
                savedAccount.setAzureBillingContainer(exportConfig.get("containerName"));
                savedAccount.setAzureBillingDirectory(exportConfig.get("directoryName"));
                CloudAccount finalAccount = cloudAccountRepository.save(savedAccount);
                return ResponseEntity.ok(finalAccount);

            } catch (Exception e) {
                logger.error("Azure account {} saved/updated, but cost export setup failed: {}", savedAccount.getId(),
                        e.getMessage());
                // Return the saved account anyway so the connection succeeds even if billing
                // setup fails
                return ResponseEntity.ok(savedAccount);
            }

        } catch (Exception e) {
            logger.error("Error adding/updating Azure account", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("An unexpected error occurred while processing the account.");
        }
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

    /**
     * If the requesting user is a sub-user (createdBy != null), create an explicit
     * UserCloudAccountAccess mapping so the newly added account is visible to them.
     */
    private void grantSubUserAccess(ClientUserDetails userDetails, CloudAccount savedAccount) {
        Optional<User> userOpt = userRepository.findById(userDetails.getId());
        if (userOpt.isPresent() && userOpt.get().getCreatedBy() != null) {
            userCloudAccountAccessRepository.save(new UserCloudAccountAccess(userOpt.get(), savedAccount));
            logger.info("Granted sub-user {} access to account {}", userDetails.getId(), savedAccount.getId());
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