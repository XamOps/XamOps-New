package com.xammer.billops.controller;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.billops.dto.*;
import com.xammer.billops.dto.azure.AzureBillingDashboardDto; // Imported
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/billops")
@CrossOrigin(origins = { "http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com",
        "https://live.xamops.com" }, methods = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                RequestMethod.DELETE, RequestMethod.OPTIONS }, allowCredentials = "true")
public class BillopsController {

    private static final Logger logger = LoggerFactory.getLogger(BillopsController.class);

    private final BillingService billingService;
    private final CostService costService;
    private final ResourceService resourceService;
    private final AppUserRepository userRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ExcelExportService excelExportService;
    private final CreditRequestService creditRequestService;
    private final DashboardService dashboardService;
    private final GcpCostService gcpCostService;
    private final AzureCostService azureCostService; // Injected

    public BillopsController(BillingService billingService,
            CostService costService,
            ResourceService resourceService,
            AppUserRepository userRepository,
            CloudAccountRepository cloudAccountRepository,
            ExcelExportService excelExportService,
            CreditRequestService creditRequestService,
            DashboardService dashboardService,
            GcpCostService gcpCostService,
            AzureCostService azureCostService) { // Injected in constructor
        this.billingService = billingService;
        this.costService = costService;
        this.resourceService = resourceService;
        this.userRepository = userRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.excelExportService = excelExportService;
        this.creditRequestService = creditRequestService;
        this.dashboardService = dashboardService;
        this.gcpCostService = gcpCostService;
        this.azureCostService = azureCostService;
    }

    /**
     * High-performance endpoint for Billing Summary.
     * Returns cached data immediately if available (latency < 50ms).
     * Fetches from AWS in parallel if forceRefresh=true or data is missing.
     */
    @GetMapping("/billing")
    public ResponseEntity<BillingDashboardDto> getBillingData(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.debug("GET /billing called. Accounts: {}, Period: {}-{}, ForceRefresh: {}", accountIds, year, month,
                forceRefresh);
        try {
            BillingDashboardDto data = billingService.getBillingData(accountIds, year, month, forceRefresh);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            logger.error("Error fetching billing data for accounts {}: {}", accountIds, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * High-performance endpoint for Detailed Breakdown.
     * Uses parallel processing for multi-account requests to reduce wait times.
     */
    @GetMapping("/detailed-breakdown")
    public ResponseEntity<List<ServiceCostDetailDto>> getDetailedBillingReport(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.debug("GET /detailed-breakdown called. ForceRefresh: {}", forceRefresh);
        try {
            List<ServiceCostDetailDto> data = billingService.getDetailedBillingReport(accountIds, year, month,
                    forceRefresh);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            logger.error("Error fetching detailed breakdown for accounts {}: {}", accountIds, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // --- Backward Compatibility / Legacy Endpoints ---

    // Redirects legacy "cached" endpoint to the new smart endpoint
    @GetMapping("/billing/cached")
    public ResponseEntity<BillingDashboardDto> getCachedBillingData(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return getBillingData(accountIds, year, month, false);
    }

    // Redirects legacy "cached" endpoint to the new smart endpoint
    @GetMapping("/detailed-breakdown/cached")
    public ResponseEntity<List<ServiceCostDetailDto>> getCachedDetailedReport(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return getDetailedBillingReport(accountIds, year, month, false);
    }

    @Deprecated
    @GetMapping("/billing/{accountId}")
    public ResponseEntity<BillingDashboardDto> getBillingDataSingle(
            @PathVariable String accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return getBillingData(Collections.singletonList(accountId), year, month, false);
    }

    @Deprecated
    @GetMapping("/detailed-breakdown/{accountId}")
    public ResponseEntity<List<ServiceCostDetailDto>> getDetailedBillingReportSingle(
            @PathVariable String accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return getDetailedBillingReport(Collections.singletonList(accountId), year, month, false);
    }

    // --- Standard Endpoints (Unchanged) ---

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Billops service is running successfully!");
    }

    @GetMapping("/dashboard/cards")
    public ResponseEntity<DashboardCardDto> getDashboardCards() {
        return ResponseEntity.ok(dashboardService.getDashboardCards());
    }

    @GetMapping("/aws-credits")
    public ResponseEntity<AwsCreditDto> getAwsCredits(@RequestParam String accountId) {
        try {
            CloudAccount account = cloudAccountRepository.findByAwsAccountIdIn(Collections.singletonList(accountId))
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
            return ResponseEntity.ok(costService.getAwsCredits(account));
        } catch (RuntimeException e) {
            logger.error("Error getting AWS credits for account {}: {}", accountId, e.getMessage());
            if (e.getMessage().startsWith("Account not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/billing/gcp/dashboard/cached")
    public ResponseEntity<GcpBillingDashboardDto> getCachedGcpBillingDashboard(
            @RequestParam String accountIdentifier,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        logger.debug("GET /billing/gcp/dashboard/cached called for account: {}", accountIdentifier);
        Optional<GcpBillingDashboardDto> cachedData = gcpCostService.getCachedGcpBillingDashboardDto(accountIdentifier);

        return cachedData
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/billing/gcp/dashboard/{accountIdentifier}")
    public ResponseEntity<GcpBillingDashboardDto> getGcpBillingDashboard(@PathVariable String accountIdentifier) {
        try {
            GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDtoAndCache(accountIdentifier)
                    .orElseThrow(() -> new IllegalStateException("GCP data was unexpectedly null after fetch"));
            return ResponseEntity.ok(dashboardDto);
        } catch (IllegalArgumentException e) {
            logger.error("Error fetching GCP billing data (Bad Request): " + e.getMessage(), e);
            if (e.getMessage().contains("Cloud account not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (IOException | InterruptedException e) {
            logger.error("Error fetching GCP billing data (Server Error): " + e.getMessage(), e);
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        } catch (Exception e) {
            logger.error("An unexpected error occurred while fetching GCP billing data for {}: {}", accountIdentifier,
                    e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/billing/gcp/resource-costs/cached")
    public ResponseEntity<List<GcpResourceCostDto>> getCachedGcpResourceCosts(
            @RequestParam String accountIdentifier,
            @RequestParam String serviceName) {
        logger.debug("GET /billing/gcp/resource-costs/cached for account: {}, service: {}", accountIdentifier,
                serviceName);

        Optional<List<GcpResourceCostDto>> cachedData = gcpCostService
                .getCachedResourceCostsForService(accountIdentifier, serviceName);

        return cachedData
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/billing/gcp/resource-costs")
    public ResponseEntity<List<GcpResourceCostDto>> getGcpResourceCosts(
            @RequestParam String accountIdentifier,
            @RequestParam String serviceName) {
        try {
            List<GcpResourceCostDto> resourceCosts = gcpCostService
                    .getResourceCostsForServiceAndCache(accountIdentifier, serviceName)
                    .orElse(Collections.emptyList());
            return ResponseEntity.ok(resourceCosts);
        } catch (IllegalArgumentException e) {
            logger.error("Bad request for GCP resource costs for service '{}', account '{}': {}", serviceName,
                    accountIdentifier, e.getMessage());
            if (e.getMessage().contains("Cloud account not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            logger.error("Failed to get GCP resource costs for service '{}', account '{}': {}", serviceName,
                    accountIdentifier, e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // --- Azure Endpoints ---

    @GetMapping("/billing/azure/dashboard/{subscriptionId}")
    public ResponseEntity<AzureBillingDashboardDto> getAzureBillingDashboard(
            @PathVariable String subscriptionId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        try {
            AzureBillingDashboardDto data = azureCostService.getBillingDashboard(subscriptionId, year, month);
            return ResponseEntity.ok(data);
        } catch (IllegalArgumentException e) {
            logger.warn("Azure account not found: {}", subscriptionId);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error fetching Azure billing data for subscription {}: {}", subscriptionId, e.getMessage(),
                    e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // -----------------------

    @GetMapping("/accounts")
    public ResponseEntity<List<CloudAccount>> getCloudAccounts(Authentication authentication) {
        try {
            AppUser user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

            Client client = Optional.ofNullable(user.getClient())
                    .orElseThrow(() -> new IllegalStateException(
                            "User " + user.getUsername() + " has no associated client."));

            return ResponseEntity.ok(cloudAccountRepository.findByClientId(client.getId()));
        } catch (UsernameNotFoundException e) {
            logger.warn("Attempt to access accounts by non-existent user: {}", authentication.getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (IllegalStateException e) {
            logger.error("Data integrity issue: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            logger.error("Unexpected error fetching accounts for user {}: {}", authentication.getName(), e.getMessage(),
                    e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/breakdown/instances")
    public ResponseEntity<List<Map<String, Object>>> getInstanceBreakdown(@RequestParam Long accountId,
            @RequestParam String region,
            @RequestParam String serviceName,
            Authentication authentication) {
        try {
            AppUser user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

            Client client = Optional.ofNullable(user.getClient())
                    .orElseThrow(() -> new IllegalStateException(
                            "User " + user.getUsername() + " has no associated client."));

            CloudAccount account = cloudAccountRepository.findById(accountId)
                    .filter(acc -> acc.getClient() != null && acc.getClient().getId().equals(client.getId()))
                    .orElseThrow(
                            () -> new SecurityException("Account not found or access denied for ID: " + accountId));

            List<Map<String, Object>> data = resourceService.getResourcesInRegion(account, region, serviceName);
            return ResponseEntity.ok(data);
        } catch (UsernameNotFoundException e) {
            logger.warn("Attempt to access instance breakdown by non-existent user: {}", authentication.getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (SecurityException e) {
            logger.warn("Access denied for user {} to account {}: {}", authentication.getName(), accountId,
                    e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalStateException e) {
            logger.error("Data integrity issue: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            logger.error("Error fetching instance breakdown for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportBillingData(@RequestParam Long accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Authentication authentication) throws IOException {
        try {
            AppUser user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

            Client client = Optional.ofNullable(user.getClient())
                    .orElseThrow(() -> new IllegalStateException(
                            "User " + user.getUsername() + " has no associated client."));

            CloudAccount account = cloudAccountRepository.findById(accountId)
                    .filter(acc -> acc.getClient() != null && acc.getClient().getId().equals(client.getId()))
                    .orElseThrow(
                            () -> new SecurityException("Account not found or access denied for ID: " + accountId));

            ByteArrayInputStream in = excelExportService.generateBillingReport(account, year, month, costService,
                    resourceService);

            HttpHeaders headers = new HttpHeaders();
            String safeAccountName = Optional.ofNullable(account.getAccountName()).orElse("unknown")
                    .replaceAll("[^a-zA-Z0-9_-]", "_");
            headers.add("Content-Disposition", "attachment; filename=billing-report-" + safeAccountName + ".xlsx");

            return ResponseEntity
                    .ok()
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(in.readAllBytes());

        } catch (UsernameNotFoundException e) {
            logger.warn("Attempt to export billing data by non-existent user: {}", authentication.getName());
            throw new RuntimeException("Unauthorized access during export", e);
        } catch (SecurityException e) {
            logger.warn("Access denied for user {} to account {} during export: {}", authentication.getName(),
                    accountId, e.getMessage());
            throw new RuntimeException("Forbidden access during export", e);
        } catch (IOException ioe) {
            logger.error("IO error during Excel generation for account {}: {}", accountId, ioe.getMessage(), ioe);
            throw ioe;
        } catch (Exception e) {
            logger.error("Unexpected error during Excel export for account {}: {}", accountId, e.getMessage(), e);
            throw new RuntimeException("Excel export failed", e);
        }
    }

    // --- Credit Requests Endpoints ---
    @PostMapping("/credits")
    public ResponseEntity<CreditRequestDto> createCreditRequest(@RequestBody CreditRequestDto creditRequestDto) {
        try {
            return ResponseEntity.ok(creditRequestService.createCreditRequest(creditRequestDto));
        } catch (RuntimeException e) {
            logger.error("Error creating credit request: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @GetMapping("/credits/admin/all/cached")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<List<CreditRequestDto>> getCachedAllCreditRequests() {
        logger.debug("GET /credits/admin/all/cached called");
        return creditRequestService.getCachedAllCreditRequests()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/credits/admin/all")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<List<CreditRequestDto>> getFreshAllCreditRequests() {
        logger.debug("GET /credits/admin/all (fresh) called");
        return ResponseEntity.ok(creditRequestService.getAllCreditRequestsAndCache());
    }

    @GetMapping("/credits/user/{userId}/cached")
    public ResponseEntity<List<CreditRequestDto>> getCachedCreditRequestsByUser(@PathVariable Long userId) {
        logger.debug("GET /credits/user/{}/cached called", userId);
        return creditRequestService.getCachedCreditRequestsByUserId(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/credits/user/{userId}")
    public ResponseEntity<List<CreditRequestDto>> getFreshCreditRequestsByUser(@PathVariable Long userId) {
        logger.debug("GET /credits/user/{} (fresh) called", userId);
        return ResponseEntity.ok(creditRequestService.getCreditRequestsByUserIdAndCache(userId));
    }

    @PutMapping("/credits/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<CreditRequestDto> updateRequestStatus(@PathVariable Long id,
            @RequestBody Map<String, String> statusUpdate) {
        String status = statusUpdate.get("status");
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            return ResponseEntity.ok(creditRequestService.updateRequestStatus(id, status));
        } catch (RuntimeException e) {
            logger.error("Error updating credit request status for ID {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }
}