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
import com.xammer.cloud.service.WasteDeleteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.Executor;

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
    private final WasteDeleteService wasteDeleteService;
    private final Executor wasteLookupExecutor;

    public DashboardController(DashboardDataService dashboardDataService,
            OptimizationService optimizationService,
            CloudListService cloudListService,
            AwsAccountService awsAccountService,
            CloudAccountRepository cloudAccountRepository,
            DashboardLayoutRepository dashboardLayoutRepository,
            WasteDeleteService wasteDeleteService,
            @Qualifier("wasteLookupExecutor") Executor wasteLookupExecutor) {
        this.dashboardDataService = dashboardDataService;
        this.optimizationService = optimizationService;
        this.cloudListService = cloudListService;
        this.awsAccountService = awsAccountService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardLayoutRepository = dashboardLayoutRepository;
        this.wasteDeleteService = wasteDeleteService;
        this.wasteLookupExecutor = wasteLookupExecutor;
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

    // ============================================================================================
    // PHASE 4: GRANULAR STANDARD DASHBOARD ENDPOINTS
    // ============================================================================================

    @GetMapping("/dashboard/granular/kpi")
    public ResponseEntity<?> getGranularKpi(@RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        return ResponseEntity.ok(dashboardDataService.getGranularKpi(accountId, forceRefresh, userDetails));
    }

    @GetMapping("/dashboard/granular/inventory")
    public ResponseEntity<?> getGranularInventory(@RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        return ResponseEntity.ok(dashboardDataService.getGranularInventory(accountId, forceRefresh, userDetails));
    }

    @GetMapping("/dashboard/granular/cost")
    public ResponseEntity<?> getGranularCost(@RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        return ResponseEntity.ok(dashboardDataService.getGranularCost(accountId, forceRefresh, userDetails));
    }

    @GetMapping("/dashboard/granular/optimization")
    public ResponseEntity<?> getGranularOptimization(@RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        return ResponseEntity.ok(dashboardDataService.getGranularOptimization(accountId, forceRefresh, userDetails));
    }

    @GetMapping("/dashboard/granular/map")
    public ResponseEntity<?> getGranularMap(@RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        return ResponseEntity.ok(dashboardDataService.getGranularMap(accountId, forceRefresh, userDetails));
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

    /** What the waste endpoint's lookups found: a saved snapshot to serve, or the account to scan. */
    private record WasteLookup(List<DashboardData.WastedResource> snapshot, CloudAccount account) {
    }

    @GetMapping("/waste")
    public CompletableFuture<List<DashboardData.WastedResource>> getWastedResources(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];
        String cacheKey = "waste-" + accountIdToUse;

        // No database access on this request thread: Open Session in View would hold its
        // connection until the response is written, i.e. for the whole scan. The lookups run
        // on wasteLookupExecutor, where each query returns its connection when it ends.
        return CompletableFuture.supplyAsync(() -> {
                    if (!forceRefresh) {
                        // ⭐ NEW: Check database cache first
                        List<DashboardData.WastedResource> cachedWaste = dashboardDataService.getWasteSnapshot(cacheKey);
                        if (cachedWaste != null) {
                            logger.info("Serving Waste data from database cache for account {}", accountIdToUse);
                            return new WasteLookup(cachedWaste, null);
                        }
                    }
                    // A forced scan keeps the previous snapshot until it saves its own result. It
                    // used to delete the snapshot first, so a slow or failed scan left nothing to
                    // serve and every later page load started yet another full scan.

                    // Fallback: Fetch fresh data
                    logger.info("Fetching fresh Waste data for account {}", accountIdToUse);
                    List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountIdToUse);
                    if (accounts.isEmpty()) {
                        throw new RuntimeException("Account not found: " + accountIdToUse);
                    }
                    return new WasteLookup(null, accounts.get(0));
                }, wasteLookupExecutor)
                .thenCompose(lookup -> lookup.snapshot() != null
                        ? CompletableFuture.completedFuture(lookup.snapshot())
                        : scanAndSave(lookup.account(), cacheKey, accountIdToUse, forceRefresh));
    }

    private CompletableFuture<List<DashboardData.WastedResource>> scanAndSave(CloudAccount account,
            String cacheKey, String accountIdToUse, boolean forceRefresh) {
        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenCompose(
                        activeRegions -> optimizationService.getWastedResources(account, activeRegions, forceRefresh))
                .thenApply(result -> {
                    // Always update the DB cache with the freshly fetched data so that
                    // subsequent loads (after Redis TTL expires) don't serve stale data.
                    dashboardDataService.saveWasteSnapshot(cacheKey, result);
                    logger.info("Updated DB cache with fresh Waste data for account {}", accountIdToUse);
                    return result;
                })
                .exceptionally(ex -> {
                    logger.error("Error fetching wasted resources for account {}", accountIdToUse, ex);
                    return Collections.emptyList();
                });
    }

    @DeleteMapping("/waste/delete")
    public ResponseEntity<?> deleteWasteResource(
            @RequestParam String accountId,
            @RequestParam String resourceId,
            @RequestParam String resourceType,
            @RequestParam String region,
            @RequestParam(defaultValue = "false") boolean acknowledgeAutoSpotting,
            @AuthenticationPrincipal ClientUserDetails user) {
        try {
            wasteDeleteService.deleteResource(accountId, resourceId, resourceType, region,
                    acknowledgeAutoSpotting, user);
            logger.info("[WasteDelete] Successfully deleted {} '{}' for account {}", resourceType, resourceId, accountId);
            return ResponseEntity.ok(Map.of("message", "Resource deleted successfully"));
        } catch (com.xammer.cloud.service.WasteDeleteBlockedException e) {
            // AutoSpotting manages this group (design-core.md section 7.6): nothing was deleted.
            logger.warn("[WasteDelete] Refused {} '{}': {} ({})", resourceType, resourceId, e.getCode(), e.getState());
            return ResponseEntity.status(e.getStatus())
                    .body(Map.of("code", e.getCode(), "state", e.getState(), "error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.warn("[WasteDelete] Bad request: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("[WasteDelete] Error deleting {} '{}': {}", resourceType, resourceId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete resource", "message", e.getMessage()));
        }
    }

    @GetMapping("/dashboard/layout")
    public DashboardLayout getDashboardLayout() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return dashboardLayoutRepository.findById(username)
                .orElse(new DashboardLayout(username, "[]"));
    }

    @PostMapping("/dashboard/layout")
    public ResponseEntity<java.util.Map<String, String>> saveDashboardLayout(@RequestBody String layoutConfig) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        DashboardLayout layout = new DashboardLayout(username, layoutConfig);
        dashboardLayoutRepository.save(layout);
        return ResponseEntity.ok(java.util.Collections.singletonMap("message", "Layout saved successfully"));
    }

    @ExceptionHandler({ ExecutionException.class, InterruptedException.class })
    public ResponseEntity<Map<String, String>> handleAsyncException(Exception e) {
        logger.error("An asynchronous execution error occurred: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch data from AWS.", "message", e.getMessage()));
    }
}