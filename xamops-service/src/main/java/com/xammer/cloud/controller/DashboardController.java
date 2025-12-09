package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DashboardLayout;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DashboardLayoutRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.OptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * REST controller for handling all dashboard-related API requests for the
 * XamOps module.
 * This controller is now a pure backend component, returning JSON data.
 */
@RestController
@RequestMapping("/api/xamops")
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardDataService dashboardDataService;
    private final OptimizationService optimizationService;
    private final CloudListService cloudListService;
    private final AwsAccountService awsAccountService;
    private final CloudAccountRepository cloudAccountRepository;
    private final DashboardLayoutRepository dashboardLayoutRepository;

    public DashboardController(DashboardDataService dashboardDataService,
            OptimizationService optimizationService,
            CloudListService cloudListService,
            AwsAccountService awsAccountService,
            CloudAccountRepository cloudAccountRepository,
            DashboardLayoutRepository dashboardLayoutRepository) {
        this.dashboardDataService = dashboardDataService;
        this.optimizationService = optimizationService;
        this.cloudListService = cloudListService;
        this.awsAccountService = awsAccountService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardLayoutRepository = dashboardLayoutRepository;
    }

    /**
     * Fetches the main dashboard data for a single account.
     * This endpoint now waits for the data fetching to complete and returns the
     * full payload,
     * supporting both initial loads and forced refreshes in a single, consistent
     * way.
     */
    @GetMapping("/dashboard/data")
    public ResponseEntity<DashboardData> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            // The method now directly calls the data service, waits for the result,
            // and returns the full data payload in the response.
            DashboardData data = dashboardDataService.getDashboardData(accountId, forceRefresh, userDetails);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching dashboard data for account {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * NEW: Fetches aggregated dashboard data for multiple AWS accounts.
     * This endpoint aggregates metrics across selected accounts and returns
     * consolidated data.
     */
    @GetMapping("/dashboard/data/multi-account")
    public ResponseEntity<?> getMultiAccountDashboardData(
            @RequestParam List<String> accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            // Validate input
            if (accountIds == null || accountIds.isEmpty()) {
                logger.warn("Multi-account request received with empty account list");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Account list cannot be empty"));
            }

            if (accountIds.size() > 10) {
                logger.warn("Multi-account request exceeds maximum limit of 10 accounts");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Maximum 10 accounts can be selected"));
            }

            logger.info("Fetching multi-account dashboard data for {} accounts: {}", accountIds.size(), accountIds);

            DashboardData aggregatedData = dashboardDataService.getMultiAccountDashboardData(
                    accountIds, forceRefresh, userDetails);

            return ResponseEntity.ok(aggregatedData);
        } catch (IllegalArgumentException ex) {
            // Validation errors (e.g., mixed providers, invalid account IDs)
            logger.warn("Validation error for multi-account request: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            // Server errors
            logger.error("Error fetching multi-account dashboard data for accounts {}", accountIds, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch dashboard data", "message", ex.getMessage()));
        }
    }

    /**
     * Fetches wasted resources data.
     */
    @GetMapping("/waste")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountIdToUse);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountIdToUse);
        }
        CloudAccount account = accounts.get(0);

        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenCompose(
                        activeRegions -> optimizationService.getWastedResources(account, activeRegions, forceRefresh))
                .exceptionally(ex -> {
                    logger.error("Error fetching wasted resources for account {}", accountIdToUse, ex);
                    return Collections.emptyList();
                });
    }

    /**
     * Fetches the user-specific dashboard layout configuration.
     */
    @GetMapping("/dashboard/layout")
    public DashboardLayout getDashboardLayout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return dashboardLayoutRepository.findById(username)
                .orElse(new DashboardLayout(username, "[]")); // Return a default empty layout
    }

    /**
     * Saves the user-specific dashboard layout configuration.
     */
    @PostMapping("/dashboard/layout")
    public ResponseEntity<Void> saveDashboardLayout(@RequestBody String layoutConfig) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        DashboardLayout layout = new DashboardLayout(username, layoutConfig);
        dashboardLayoutRepository.save(layout);
        return ResponseEntity.ok().build();
    }

    /**
     * Centralized exception handler for asynchronous errors in this controller.
     */
    @ExceptionHandler({ ExecutionException.class, InterruptedException.class })
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        logger.error("An asynchronous execution error occurred: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}
