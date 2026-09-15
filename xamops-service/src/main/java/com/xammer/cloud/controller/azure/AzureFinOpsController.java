package com.xammer.cloud.controller.azure;

import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.FinOpsReportScheduleDto;
import com.xammer.cloud.dto.azure.AzureBudgetCreateRequestDto;
import com.xammer.cloud.dto.azure.AzureBudgetDto;
import com.xammer.cloud.dto.azure.AzureFinOpsReportDto;
// --- FIX: Import UserRepository ---
import com.xammer.cloud.repository.UserRepository; 
import com.xammer.cloud.service.azure.AzureBudgetService;
import com.xammer.cloud.service.azure.AzureFinOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/azure")
@CrossOrigin(origins = "*") 
public class AzureFinOpsController {

    private static final Logger log = LoggerFactory.getLogger(AzureFinOpsController.class);

    @Autowired
    private AzureFinOpsService azureFinOpsService;

    @Autowired
    private AzureBudgetService azureBudgetService;

    @Autowired
    private com.xammer.cloud.service.azure.AzureCostAnomalyService azureCostAnomalyService;

    // --- FIX: Use UserRepository instead of UserService ---
    @Autowired
    private UserRepository userRepository; 

    /**
     * GET /api/azure/finops-report
     * Fetches the main FinOps report data for the given subscription.
     */
    @GetMapping("/finops-report")
    public ResponseEntity<AzureFinOpsReportDto> getFinOpsReport(@RequestParam String subscriptionId) {
        AzureFinOpsReportDto report = azureFinOpsService.getFinOpsReport(subscriptionId);
        return ResponseEntity.ok(report);
    }

    /**
     * GET /api/azure/finops/billing-summary
     * Returns per-service cost breakdown for a given date range (used by Cost Explorer compare).
     */
    @GetMapping("/finops/billing-summary")
    public ResponseEntity<List<Map<String, Object>>> getAzureBillingSummary(
            @RequestParam String subscriptionId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(azureFinOpsService.getBillingSummaryForDateRange(subscriptionId, startDate, endDate));
    }

    /**
     * GET /api/azure/finops/cost-by-region
     * Returns per-region cost breakdown for a given date range (used by Cost Explorer compare).
     */
    @GetMapping("/finops/cost-by-region")
    public ResponseEntity<List<Map<String, Object>>> getAzureCostByRegion(
            @RequestParam String subscriptionId,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return ResponseEntity.ok(azureFinOpsService.getCostByRegionForDateRange(subscriptionId, startDate, endDate));
    }

    /**
     * GET /api/azure/finops-schedules
     * Lists all report schedules for the current user and subscription.
     */
    @GetMapping("/finops-schedules")
    public ResponseEntity<List<FinOpsReportScheduleDto>> getSchedules(@RequestParam String subscriptionId, Principal principal) {
        // --- FIX: Use userRepository ---
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<FinOpsReportScheduleDto> schedules = azureFinOpsService.getSchedules(subscriptionId, user);
        return ResponseEntity.ok(schedules);
    }

    /**
     * POST /api/azure/finops-schedules
     * Creates a new report schedule for the current user.
     * The DTO will be populated by the frontend. We must get the subscriptionId
     * from the DTO's 'cloudAccountId' field, which the frontend will set.
     */
    @PostMapping("/finops-schedules")
    public ResponseEntity<FinOpsReportScheduleDto> createSchedule(@RequestBody FinOpsReportScheduleDto scheduleDto, Principal principal) {
        // --- FIX: Use userRepository ---
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        FinOpsReportScheduleDto createdSchedule = azureFinOpsService.createSchedule(scheduleDto, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdSchedule);
    }

    /**
     * DELETE /api/azure/finops-schedules/{scheduleId}
     * Deletes a specific report schedule for the current user.
     */
    @DeleteMapping("/finops-schedules/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(@PathVariable Long scheduleId, Principal principal) {
        // --- FIX: Use userRepository ---
        User user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        azureFinOpsService.deleteSchedule(scheduleId, user);
        return ResponseEntity.noContent().build();
    }

    // ==================== BUDGET MANAGEMENT ====================

    /**
     * GET /api/azure/finops/budgets
     * Get all budgets for an Azure subscription.
     */
    @GetMapping("/finops/budgets")
    public CompletableFuture<ResponseEntity<?>> getBudgets(@RequestParam String subscriptionId) {
        log.info("Fetching budgets for Azure subscription: {}", subscriptionId);
        return azureBudgetService.getBudgets(subscriptionId)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[AzureFinOps] budgets TIMEOUT for {}", subscriptionId);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.singletonMap("error",
                                        "Azure did not answer the budgets request in time."));
                    }
                    // "Could not read budgets" must not look like "there are no budgets". This used to
                    // return 200 with an empty array for every failure, which is exactly the confident
                    // absence the mock removal was supposed to end.
                    if (cause instanceof AzureBudgetService.BudgetsUnavailableException unavailable) {
                        log.warn("[AzureFinOps] budgets UNAVAILABLE for {}: {}",
                                subscriptionId, unavailable.getMessage());
                        HttpStatus status = unavailable.isPermissionProblem()
                                ? HttpStatus.FORBIDDEN
                                : HttpStatus.SERVICE_UNAVAILABLE;
                        return ResponseEntity.status(status)
                                .body(Collections.singletonMap("error", unavailable.getMessage()));
                    }
                    log.error("[AzureFinOps] budgets ERROR for {}", subscriptionId, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Collections.singletonMap("error",
                                    "Failed to read budgets: " + cause.getMessage()));
                });
    }

    /**
     * POST /api/azure/finops/budgets
     * Create a new budget for an Azure subscription.
     */
    @PostMapping("/finops/budgets")
    public ResponseEntity<?> createBudget(
            @RequestParam String subscriptionId,
            @RequestBody AzureBudgetCreateRequestDto budgetRequest,
            Principal principal) {
        try {
            String username = principal != null ? principal.getName() : "system";
            log.info("Creating budget '{}' for Azure subscription {} by user {}",
                    budgetRequest.getBudgetName(), subscriptionId, username);

            AzureBudgetDto createdBudget = azureBudgetService.createBudget(subscriptionId, budgetRequest, username);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdBudget);
        } catch (IllegalArgumentException e) {
            // A rejected name or limit is the caller's input, not a server fault. Returning 500 gave
            // the UI nothing to show but "something went wrong".
            log.warn("Rejected budget for Azure subscription {}: {}", subscriptionId, e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Collections.singletonMap("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Missing role, or an offer the Consumption API does not serve. Both are actionable, and
            // the message composed by the service names the remediation.
            log.warn("Cannot create budget for Azure subscription {}: {}", subscriptionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Collections.singletonMap("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating budget for Azure subscription {}", subscriptionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "Failed to create budget: " + e.getMessage()));
        }
    }

    /**
     * DELETE /api/azure/finops/budgets
     * Delete a budget for an Azure subscription.
     */
    @DeleteMapping("/finops/budgets")
    public ResponseEntity<?> deleteBudget(
            @RequestParam String subscriptionId,
            @RequestParam String budgetName) {
        try {
            log.info("Deleting budget '{}' for Azure subscription {}", budgetName, subscriptionId);
            azureBudgetService.deleteBudget(subscriptionId, budgetName);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // Previously a bare 500 with no body, so the "assign Cost Management Contributor" message
            // the service composes never reached the client.
            log.warn("Cannot delete budget '{}' for Azure subscription {}: {}",
                    budgetName, subscriptionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Collections.singletonMap("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error deleting budget '{}' for Azure subscription {}", budgetName, subscriptionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error",
                            "Failed to delete budget: " + e.getMessage()));
        }
    }

    // ==================== COST ANOMALIES ====================

    /**
     * GET /api/azure/finops/anomalies
     *
     * <p>
     * Cost anomalies for an Azure subscription. AWS exposes {@code /api/finops/cost-anomalies} and
     * GCP exposes {@code /api/gcp/finops/anomalies}; Azure had no equivalent, so nothing could ask
     * for its anomalies even after they were detected. Same shape and same timeout handling as the
     * GCP endpoint so a client can treat all three alike.
     */
    @GetMapping("/finops/anomalies")
    public CompletableFuture<ResponseEntity<?>> getCostAnomalies(
            @RequestParam String subscriptionId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("[AzureFinOps] anomalies START — subscription={} forceRefresh={}",
                subscriptionId, forceRefresh);
        final long startMs = System.currentTimeMillis();

        return azureCostAnomalyService.getCostAnomalies(subscriptionId, forceRefresh)
                .orTimeout(55, TimeUnit.SECONDS)
                .<ResponseEntity<?>>thenApply(outcome -> {
                    log.info("[AzureFinOps] anomalies OK — subscription={} count={} reason={} durationMs={}",
                            subscriptionId, outcome.anomalies().size(), outcome.unavailableReason(),
                            System.currentTimeMillis() - startMs);
                    // The whole outcome, not just the list: a client receiving a bare `[]` cannot tell
                    // "spend looks normal" from "no baseline yet".
                    return ResponseEntity.ok(outcome);
                })
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof TimeoutException) {
                        log.warn("[AzureFinOps] anomalies TIMEOUT — subscription={} durationMs={}",
                                subscriptionId, System.currentTimeMillis() - startMs);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Collections.emptyList());
                    }
                    log.error("[AzureFinOps] anomalies ERROR — subscription={}", subscriptionId, ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Collections.emptyList());
                });
    }
}