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
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList());
                    }
                    log.error("[AzureFinOps] budgets ERROR for {}", subscriptionId, ex);
                    return ResponseEntity.ok(Collections.emptyList());
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
    public ResponseEntity<Void> deleteBudget(
            @RequestParam String subscriptionId,
            @RequestParam String budgetName) {
        try {
            log.info("Deleting budget '{}' for Azure subscription {}", budgetName, subscriptionId);
            azureBudgetService.deleteBudget(subscriptionId, budgetName);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error deleting budget '{}' for Azure subscription {}", budgetName, subscriptionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}