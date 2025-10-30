package com.xammer.billops.controller;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.User;
import com.xammer.billops.dto.*; // Import all DTOs
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.service.*; // Import all services
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus; // Import HttpStatus
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
import java.util.Optional; // Import Optional

import org.springframework.web.bind.annotation.RequestMethod; // Keep this import

@RestController
@RequestMapping("/api/billops")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5500", "https://uat.xamops.com" , "https://live.xamops.com"},
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowCredentials = "true")
public class BillopsController {
    private final BillingService billingService;
    private final CostService costService;
    private final ResourceService resourceService;
    private final UserRepository userRepository;
    private final CloudAccountRepository cloudAccountRepository;
    private final ExcelExportService excelExportService;
    private final CreditRequestService creditRequestService;
    private final TicketService ticketService;
    private final DashboardService dashboardService;
    private final GcpCostService gcpCostService;
    private static final Logger logger = LoggerFactory.getLogger(BillopsController.class);

    // Constructor remains the same
    public BillopsController(BillingService billingService,
                             CostService costService,
                             ResourceService resourceService,
                             UserRepository userRepository,
                             CloudAccountRepository cloudAccountRepository,
                             ExcelExportService excelExportService,
                             CreditRequestService creditRequestService,
                             TicketService ticketService,
                             DashboardService dashboardService,
                             GcpCostService gcpCostService) {
        this.billingService = billingService;
        this.costService = costService;
        this.resourceService = resourceService;
        this.userRepository = userRepository;
        this.cloudAccountRepository = cloudAccountRepository;
        this.excelExportService = excelExportService;
        this.creditRequestService = creditRequestService;
        this.ticketService = ticketService;
        this.dashboardService = dashboardService;
        this.gcpCostService = gcpCostService;
    }


    // --- NEW Endpoint to get CACHED summary billing data ---
    @GetMapping("/billing/cached")
    public ResponseEntity<BillingDashboardDto> getCachedBillingData(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        logger.debug("GET /billing/cached called for accounts: {}, period: {}-{}", accountIds, year, month);
        Optional<BillingDashboardDto> cachedData = billingService.getCachedBillingData(accountIds, year, month);
        return cachedData
                .map(ResponseEntity::ok) // If present, return 200 OK with data
                .orElseGet(() -> ResponseEntity.notFound().build()); // If not present, return 404 Not Found
    }

    // --- MODIFIED Endpoint to get FRESH summary billing data (and update cache) ---
    @GetMapping("/billing")
    public ResponseEntity<BillingDashboardDto> getFreshBillingData(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
         logger.debug("GET /billing called for accounts: {}, period: {}-{}", accountIds, year, month);
        try {
            // This method now fetches fresh data AND updates the cache via @CachePut
            Optional<BillingDashboardDto> data = billingService.getBillingDataAndCache(accountIds, year, month);
             // Even if data is empty (e.g., no valid accounts), return OK with the empty DTO
            return data.map(ResponseEntity::ok)
                       .orElseGet(() -> {
                            // This case might happen if the service method itself returns empty Optional
                            // Log it, but still return OK with an empty body perhaps, or internal server error?
                            logger.error("Billing service returned empty Optional unexpectedly for accounts: {}", accountIds);
                            // Let's return OK with a default empty DTO to avoid breaking the frontend if possible
                             return ResponseEntity.ok(new BillingDashboardDto());
                            // Or: return ResponseEntity.internalServerError().build();
                       });
        } catch (Exception e) {
            logger.error("Error fetching fresh billing data for accounts {}: {}", accountIds, e.getMessage(), e);
            // Print stack trace for debugging during development
             e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }


    // --- NEW Endpoint to get CACHED detailed billing data ---
    @GetMapping("/detailed-breakdown/cached")
    public ResponseEntity<List<ServiceCostDetailDto>> getCachedDetailedBillingReport(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        logger.debug("GET /detailed-breakdown/cached called for accounts: {}, period: {}-{}", accountIds, year, month);
        Optional<List<ServiceCostDetailDto>> cachedData = billingService.getCachedDetailedBillingReport(accountIds, year, month);
        return cachedData
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }


    // --- MODIFIED Endpoint to get FRESH detailed billing data (and update cache) ---
    @GetMapping("/detailed-breakdown")
    public ResponseEntity<List<ServiceCostDetailDto>> getFreshDetailedBillingReport(
            @RequestParam List<String> accountIds,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        logger.debug("GET /detailed-breakdown called for accounts: {}, period: {}-{}", accountIds, year, month);
        try {
             // This method now fetches fresh data AND updates the cache via @CachePut
            Optional<List<ServiceCostDetailDto>> data = billingService.getDetailedBillingReportAndCache(accountIds, year, month);
            // Return OK even if the list inside Optional is empty
             return data.map(ResponseEntity::ok)
                       .orElseGet(() -> {
                           logger.error("Detailed billing service returned empty Optional unexpectedly for accounts: {}", accountIds);
                           // Return OK with empty list
                            return ResponseEntity.ok(Collections.emptyList());
                           // Or: return ResponseEntity.internalServerError().build();
                       });
        } catch (Exception e) {
            logger.error("Error fetching fresh detailed billing data for accounts {}: {}", accountIds, e.getMessage(), e);
             e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }


    // --- Deprecated single account ID endpoints - Keep for backward compatibility or remove ---
    // Consider marking them @Deprecated and eventually removing them if the frontend always uses the list-based ones.
    @Deprecated
    @GetMapping("/billing/{accountId}")
    public ResponseEntity<BillingDashboardDto> getBillingDataSingle(
            @PathVariable String accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return getFreshBillingData(Collections.singletonList(accountId), year, month); // Delegate to the list version
    }

     @Deprecated
    @GetMapping("/detailed-breakdown/{accountId}")
    public ResponseEntity<List<ServiceCostDetailDto>> getDetailedBillingReportSingle(
            @PathVariable String accountId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return getFreshDetailedBillingReport(Collections.singletonList(accountId), year, month); // Delegate to the list version
    }
    // --- End Deprecated ---


    // --- Other Endpoints (remain largely unchanged unless they need caching adjustments) ---

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Billops service is running successfully!");
    }

    @GetMapping("/dashboard/cards")
    public ResponseEntity<DashboardCardDto> getDashboardCards() {
        // This likely doesn't need the stale-while-revalidate pattern unless it's slow
        return ResponseEntity.ok(dashboardService.getDashboardCards());
    }

    @GetMapping("/aws-credits")
    public ResponseEntity<AwsCreditDto> getAwsCredits(@RequestParam String accountId) {
        // Caching might be useful here too, but implementing stale-while-revalidate
        // would follow the same pattern as above if needed.
        try {
            CloudAccount account = cloudAccountRepository.findByAwsAccountIdIn(Collections.singletonList(accountId))
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("Account not found: " + accountId)); // Consider custom exception
            return ResponseEntity.ok(costService.getAwsCredits(account));
        } catch (RuntimeException e) {
            logger.error("Error getting AWS credits for account {}: {}", accountId, e.getMessage());
             // Return 404 if account not found, 500 otherwise
            if (e.getMessage().startsWith("Account not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.internalServerError().build();
        }
    }


    @GetMapping("/billing/gcp/dashboard/{accountIdentifier}")
    public ResponseEntity<GcpBillingDashboardDto> getGcpBillingDashboard(@PathVariable String accountIdentifier) {
        // Implement stale-while-revalidate for GCP if needed, following the same pattern
        try {
            GcpBillingDashboardDto dashboardDto = gcpCostService.getGcpBillingDashboardDto(accountIdentifier);
            return ResponseEntity.ok(dashboardDto);
        } catch (IllegalArgumentException e) {
            logger.error("Error fetching GCP billing data (Bad Request): " + e.getMessage(), e);
            // Return 400 or 404 depending on whether it's bad input or not found
            if (e.getMessage().contains("Cloud account not found")) {
                 return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (IOException | InterruptedException e) {
            logger.error("Error fetching GCP billing data (Server Error): " + e.getMessage(), e);
            Thread.currentThread().interrupt(); // Re-interrupt if InterruptedException
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        } catch (Exception e) {
            logger.error("An unexpected error occurred while fetching GCP billing data for {}: {}", accountIdentifier, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/billing/gcp/resource-costs")
    public ResponseEntity<List<GcpResourceCostDto>> getGcpResourceCosts(
            @RequestParam String accountIdentifier,
            @RequestParam String serviceName) {
         // Implement stale-while-revalidate for GCP if needed
        try {
            List<GcpResourceCostDto> resourceCosts = gcpCostService.getResourceCostsForService(accountIdentifier, serviceName);
            return ResponseEntity.ok(resourceCosts);
        } catch (IllegalArgumentException e) {
             logger.error("Bad request for GCP resource costs for service '{}', account '{}': {}", serviceName, accountIdentifier, e.getMessage());
              if (e.getMessage().contains("Cloud account not found")) {
                 return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            logger.error("Failed to get GCP resource costs for service '{}', account '{}': {}", serviceName, accountIdentifier, e.getMessage(), e);
             if (e instanceof InterruptedException) {
                 Thread.currentThread().interrupt();
             }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }


    @GetMapping("/accounts")
    public ResponseEntity<List<CloudAccount>> getCloudAccounts(Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName())); // More specific message
            // Handle cases where user might not have a client associated (though schema implies it's required)
            Client client = Optional.ofNullable(user.getClient())
                                   .orElseThrow(() -> new IllegalStateException("User " + user.getUsername() + " has no associated client."));

            return ResponseEntity.ok(cloudAccountRepository.findByClientId(client.getId()));
        } catch (UsernameNotFoundException e) {
             logger.warn("Attempt to access accounts by non-existent user: {}", authentication.getName());
             return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); // Or FORBIDDEN depending on security setup
        } catch (IllegalStateException e) {
             logger.error("Data integrity issue: {}", e.getMessage());
             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
            logger.error("Unexpected error fetching accounts for user {}: {}", authentication.getName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }


    // getInstanceBreakdown - Caching strategy might apply here too
    @GetMapping("/breakdown/instances")
    public ResponseEntity<List<Map<String, Object>>> getInstanceBreakdown(@RequestParam Long accountId,
                                                                         @RequestParam String region,
                                                                         @RequestParam String serviceName,
                                                                         Authentication authentication) {
        try {
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

            Client client = Optional.ofNullable(user.getClient())
                                    .orElseThrow(() -> new IllegalStateException("User " + user.getUsername() + " has no associated client."));


            // Verify the user owns the account they are requesting
            CloudAccount account = cloudAccountRepository.findById(accountId)
                    .filter(acc -> acc.getClient() != null && acc.getClient().getId().equals(client.getId()))
                    .orElseThrow(() -> new SecurityException("Account not found or access denied for ID: " + accountId)); // Use SecurityException for access issues


            List<Map<String, Object>> data = resourceService.getResourcesInRegion(account, region, serviceName);
            return ResponseEntity.ok(data);
         } catch (UsernameNotFoundException e) {
             logger.warn("Attempt to access instance breakdown by non-existent user: {}", authentication.getName());
             return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
         } catch (SecurityException e) {
             logger.warn("Access denied for user {} to account {}: {}", authentication.getName(), accountId, e.getMessage());
             return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalStateException e) {
             logger.error("Data integrity issue: {}", e.getMessage());
             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (Exception e) {
             logger.error("Error fetching instance breakdown for account {}: {}", accountId, e.getMessage(), e);
             return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // exportBillingData - Not suitable for caching the response directly due to streaming nature.
    // Caching happens within the services it calls (costService, resourceService).
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportBillingData(@RequestParam Long accountId,
                                                    @RequestParam(required = false) Integer year,
                                                    @RequestParam(required = false) Integer month,
                                                    Authentication authentication) throws IOException { // Keep throws IOException for clarity
         try {
            User user = userRepository.findByUsername(authentication.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + authentication.getName()));

             Client client = Optional.ofNullable(user.getClient())
                                     .orElseThrow(() -> new IllegalStateException("User " + user.getUsername() + " has no associated client."));


            CloudAccount account = cloudAccountRepository.findById(accountId)
                    .filter(acc -> acc.getClient() != null && acc.getClient().getId().equals(client.getId()))
                    .orElseThrow(() -> new SecurityException("Account not found or access denied for ID: " + accountId)); // Use SecurityException


            ByteArrayInputStream in = excelExportService.generateBillingReport(account, year, month, costService, resourceService);

            HttpHeaders headers = new HttpHeaders();
             String safeAccountName = Optional.ofNullable(account.getAccountName()).orElse("unknown")
                                             .replaceAll("[^a-zA-Z0-9_-]", "_"); // Sanitize name
            headers.add("Content-Disposition", "attachment; filename=billing-report-" + safeAccountName + ".xlsx");

            return ResponseEntity
                    .ok()
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(in.readAllBytes());

         } catch (UsernameNotFoundException e) {
             logger.warn("Attempt to export billing data by non-existent user: {}", authentication.getName());
             // Can't easily return ResponseEntity here because of throws IOException signature.
             // Consider refactoring to not throw IOException directly or handle it differently.
              // For now, rethrow a runtime exception to indicate failure.
             throw new RuntimeException("Unauthorized access during export", e);
         } catch (SecurityException e) {
             logger.warn("Access denied for user {} to account {} during export: {}", authentication.getName(), accountId, e.getMessage());
              throw new RuntimeException("Forbidden access during export", e);
         } catch (IOException ioe) {
              logger.error("IO error during Excel generation for account {}: {}", accountId, ioe.getMessage(), ioe);
              throw ioe; // Re-throw IOException as per method signature
         } catch (Exception e) {
            logger.error("Unexpected error during Excel export for account {}: {}", accountId, e.getMessage(), e);
             // Wrap other exceptions in RuntimeException
            throw new RuntimeException("Excel export failed", e);
        }
    }


    // --- Credit Requests Endpoints ---
    @PostMapping("/credits")
    public ResponseEntity<CreditRequestDto> createCreditRequest(@RequestBody CreditRequestDto creditRequestDto) {
         try {
            return ResponseEntity.ok(creditRequestService.createCreditRequest(creditRequestDto));
         } catch (RuntimeException e) { // Catch potential runtime exceptions (e.g., User not found)
             logger.error("Error creating credit request: {}", e.getMessage(), e);
             // Consider returning a more specific error based on the exception type
             return ResponseEntity.status(HttpStatus.BAD_REQUEST).build(); // Or INTERNAL_SERVER_ERROR
         }
    }

    @GetMapping("/credits/admin/all")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')") // Keep authorization check
    public ResponseEntity<List<CreditRequestDto>> getAllCreditRequests() {
        return ResponseEntity.ok(creditRequestService.getAllCreditRequests());
    }

    @GetMapping("/credits/user/{userId}")
    public ResponseEntity<List<CreditRequestDto>> getCreditRequestsByUser(@PathVariable Long userId) {
        // Add check: Ensure the authenticated user is requesting their own credits or is an admin
        // This requires access to Authentication object and potentially user roles.
        // Simplified for now.
        return ResponseEntity.ok(creditRequestService.getCreditRequestsByUserId(userId));
    }

    @PutMapping("/credits/{id}/status")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')") // Keep authorization check
    public ResponseEntity<CreditRequestDto> updateRequestStatus(@PathVariable Long id, @RequestBody Map<String, String> statusUpdate) {
        String status = statusUpdate.get("status");
         if (status == null || status.isBlank()) {
             return ResponseEntity.badRequest().build(); // Basic validation
         }
        try {
            return ResponseEntity.ok(creditRequestService.updateRequestStatus(id, status));
        } catch (RuntimeException e) { // Catch potential "Credit Request not found"
             logger.error("Error updating credit request status for ID {}: {}", id, e.getMessage());
             return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // Return 404 if not found
        }
    }


    // --- Tickets Endpoints ---
    @PostMapping("/tickets")
    public ResponseEntity<TicketDto> createTicket(@RequestBody TicketDto ticketDto) {
         try {
             return ResponseEntity.ok(ticketService.createTicket(ticketDto));
         } catch (RuntimeException e) { // Catch e.g., Client not found
             logger.error("Error creating ticket: {}", e.getMessage(), e);
             return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
         }
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<TicketDto>> getAllTickets() {
        // Consider adding pagination for large numbers of tickets
        return ResponseEntity.ok(ticketService.getAllTickets());
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id) {
         try {
            return ResponseEntity.ok(ticketService.getTicketById(id));
         } catch (RuntimeException e) { // Catch Ticket not found
             logger.warn("Ticket not found for ID: {}", id);
             return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
         }
    }

    @PostMapping("/tickets/{id}/replies")
    public ResponseEntity<TicketDto> addTicketReply(@PathVariable Long id, @RequestBody TicketReplyDto replyDto) {
         try {
             // Add validation: Check if replyDto has authorId and message
             if (replyDto.getAuthorId() == null || replyDto.getMessage() == null || replyDto.getMessage().isBlank()) {
                 return ResponseEntity.badRequest().build();
             }
            return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto));
         } catch (IllegalStateException e) { // Handle closed-ticket or similar state issues first
             logger.error("Error adding reply to ticket {}: {}", id, e.getMessage());
             return ResponseEntity.status(HttpStatus.CONFLICT).build(); // 409 Conflict for adding to closed ticket
         } catch (RuntimeException e) { // Catch Ticket/User not found or other runtime errors
             logger.error("Error adding reply to ticket {}: {}", id, e.getMessage());
             if (e.getMessage().contains("not found")) {
                 return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
             }
             return ResponseEntity.status(HttpStatus.BAD_REQUEST).build(); // Other runtime errors
         }
    }

    @PostMapping("/tickets/{id}/close")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')") // Keep authorization
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
         try {
             return ResponseEntity.ok(ticketService.closeTicket(id));
         } catch (RuntimeException e) { // Catch Ticket not found
             logger.error("Error closing ticket {}: {}", id, e.getMessage());
             return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
         }
    }


     // --- Lambdas for orElseThrow (kept at the end for clarity) ---
    private java.util.function.Supplier<RuntimeException> accountNotFoundException(String accountId) {
        return () -> new RuntimeException("Account not found: " + accountId); // Or a more specific custom exception
    }

     private java.util.function.Supplier<UsernameNotFoundException> userNotFoundException(String username) {
        return () -> new UsernameNotFoundException("User not found: " + username);
    }

     private java.util.function.Supplier<RuntimeException> accessDeniedException(Long accountId) {
         return () -> new SecurityException("Account not found or access denied for ID: " + accountId);
     }


}