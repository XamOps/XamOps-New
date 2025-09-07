package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DashboardLayout;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DashboardLayoutRepository;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.OptimizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * REST controller for handling all dashboard-related API requests for the XamOps module.
 * This controller is now a pure backend component, returning JSON data.
 */
@RestController
// 1. ADDED A BASE PATH for all XamOps APIs. This is crucial for the API Gateway routing.
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
     * Fetches the main dashboard data.
     * The path is changed to be more descriptive for an API.
     */
    @GetMapping("/dashboard-data")
    public DashboardData getDashboardData(
            @RequestParam(required = false) boolean force,
            @RequestParam String accountId) throws ExecutionException, InterruptedException, java.io.IOException {

        if (force) {
            awsAccountService.clearAllCaches();
        }
        // 2. SIMPLIFIED RETURN TYPE: Spring Boot automatically handles the ResponseEntity and serialization.
        return dashboardDataService.getDashboardData(accountId, force);
    }

    /**
     * Fetches wasted resources data.
     */
     @GetMapping("/waste")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources(
            @RequestParam String accountIds, // <-- FIX: Changed parameter name
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        
        String accountIdToUse = accountIds.split(",")[0]; // Use the first ID
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountIdToUse)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountIdToUse));

        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenCompose(activeRegions -> optimizationService.getWastedResources(account, activeRegions, forceRefresh))
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
    @ExceptionHandler({ExecutionException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        logger.error("An asynchronous execution error occurred: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}