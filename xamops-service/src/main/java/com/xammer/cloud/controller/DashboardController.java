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
import org.springframework.security.core.Authentication; // Added missing import
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
 * Updated for Phase 4: Robust Error Handling & Granular Loading.
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

    // ============================================================================================
    // PHASE 2: TICKER ENDPOINT
    // ============================================================================================

    @GetMapping("/dashboard/unified/ticker")
    public ResponseEntity<List<String>> getUnifiedTicker(@AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            List<String> ticker = dashboardDataService.getUnifiedTicker(userDetails);
            return ResponseEntity.ok(ticker);
        } catch (Exception ex) {
            logger.error("Error fetching ticker", ex);
            return ResponseEntity.ok(Collections.emptyList()); // Fail silently for UI elements
        }
    }

    // ============================================================================================
    // PHASE 1 & 4: GRANULAR UNIFIED ENDPOINTS (With Safe Error Handling)
    // ============================================================================================

    /**
     * 1. Summary Endpoint: Returns account counts and static high-level info.
     */
    @GetMapping("/dashboard/unified/summary")
    public ResponseEntity<?> getUnifiedSummary(@AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            DashboardData data = dashboardDataService.getUnifiedSummary(userDetails);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching unified summary", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch summary data"));
        }
    }

    /**
     * 2. AWS Specific Data
     */
    @GetMapping("/dashboard/unified/aws")
    public ResponseEntity<?> getUnifiedAwsData(
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            DashboardData data = dashboardDataService.getUnifiedAwsData(userDetails, forceRefresh);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching unified AWS data", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch AWS data: " + ex.getMessage()));
        }
    }

    /**
     * 3. GCP Specific Data
     */
    @GetMapping("/dashboard/unified/gcp")
    public ResponseEntity<?> getUnifiedGcpData(
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            DashboardData data = dashboardDataService.getUnifiedGcpData(userDetails, forceRefresh);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching unified GCP data", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch GCP data: " + ex.getMessage()));
        }
    }

    /**
     * 4. Azure Specific Data
     */
    @GetMapping("/dashboard/unified/azure")
    public ResponseEntity<?> getUnifiedAzureData(
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            DashboardData data = dashboardDataService.getUnifiedAzureData(userDetails, forceRefresh);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching unified Azure data", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch Azure data: " + ex.getMessage()));
        }
    }

    /**
     * 5. Health & Code Quality
     */
    @GetMapping("/dashboard/unified/health")
    public ResponseEntity<?> getUnifiedHealthData(
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            DashboardData.CodeQualitySummary data = dashboardDataService.getUnifiedHealthData(userDetails);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching health data", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to fetch health metrics"));
        }
    }

    /**
     * Helper to create a consistent error JSON response.
     * Uses DashboardData DTO which already has an 'error' field.
     */
    private DashboardData createErrorResponse(String message) {
        DashboardData errorData = new DashboardData();
        errorData.setError(message);
        return errorData;
    }

    // ============================================================================================
    // LEGACY / SHARED ENDPOINTS
    // ============================================================================================

    @GetMapping("/dashboard/data")
    public ResponseEntity<DashboardData> getDashboardData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            DashboardData data = dashboardDataService.getDashboardData(accountId, forceRefresh, userDetails);
            return ResponseEntity.ok(data);
        } catch (Exception ex) {
            logger.error("Error fetching dashboard data for account {}", accountId, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/dashboard/data/multi-account")
    public ResponseEntity<?> getMultiAccountDashboardData(
            @RequestParam List<String> accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        try {
            if (accountIds == null || accountIds.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Account list cannot be empty"));
            }
            if (accountIds.size() > 10) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Maximum 10 accounts can be selected"));
            }
            DashboardData aggregatedData = dashboardDataService.getMultiAccountDashboardData(accountIds, forceRefresh,
                    userDetails);
            return ResponseEntity.ok(aggregatedData);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
        } catch (Exception ex) {
            logger.error("Error fetching multi-account data", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch data", "message", ex.getMessage()));
        }
    }

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

    @GetMapping("/dashboard/layout")
    public DashboardLayout getDashboardLayout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return dashboardLayoutRepository.findById(username)
                .orElse(new DashboardLayout(username, "[]"));
    }

    @PostMapping("/dashboard/layout")
    public ResponseEntity<Void> saveDashboardLayout(@RequestBody String layoutConfig) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        DashboardLayout layout = new DashboardLayout(username, layoutConfig);
        dashboardLayoutRepository.save(layout);
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler({ ExecutionException.class, InterruptedException.class })
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        logger.error("An asynchronous execution error occurred: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}