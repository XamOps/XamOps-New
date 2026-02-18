package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpBudgetCreateRequestDto;
import com.xammer.cloud.dto.gcp.GcpBudgetDto;
import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import com.xammer.cloud.service.gcp.GcpBudgetService;
import com.xammer.cloud.service.gcp.GcpCostService;
import com.xammer.cloud.service.gcp.GcpFinOpsReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/finops")
@Slf4j
public class GcpFinOpsController {

        private final GcpFinOpsReportService gcpFinOpsReportService;
        private final GcpBudgetService gcpBudgetService;
        private final GcpCostService gcpCostService;
        private final com.xammer.cloud.service.DashboardDataService dashboardDataService;

        public GcpFinOpsController(GcpFinOpsReportService gcpFinOpsReportService,
                        GcpBudgetService gcpBudgetService,
                        GcpCostService gcpCostService,
                        com.xammer.cloud.service.DashboardDataService dashboardDataService) {
                this.gcpFinOpsReportService = gcpFinOpsReportService;
                this.gcpBudgetService = gcpBudgetService;
                this.gcpCostService = gcpCostService;
                this.dashboardDataService = dashboardDataService;
        }

        /**
         * Get GCP FinOps report with optional date range filtering.
         */
        @GetMapping("/report")
        public CompletableFuture<ResponseEntity<?>> getFinOpsReport(
                        @RequestParam String accountId,
                        @RequestParam(defaultValue = "false") boolean forceRefresh,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                if (startDate != null && endDate != null) {
                        log.info("Starting GCP FinOps report for accountId: {} with date range: {} to {}",
                                        accountId, startDate, endDate);
                        return gcpFinOpsReportService
                                        .generateFinOpsReportWithDateRange(accountId, startDate, endDate, forceRefresh)
                                        .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                        .exceptionally(ex -> {
                                                log.error("Failed to generate GCP FinOps report for accountId: {} (dates: {} to {})",
                                                                accountId, startDate, endDate, ex);
                                                return ResponseEntity
                                                                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                                .body(Map.of("error",
                                                                                "Failed to retrieve GCP FinOps report.",
                                                                                "message",
                                                                                ex.getMessage() != null
                                                                                                ? ex.getMessage()
                                                                                                : "Unknown error"));
                                        });
                }

                log.info("Starting GCP FinOps report for accountId: {}", accountId);

                if (!forceRefresh) {
                        // ⭐ NEW: Check database snapshot first
                        GcpFinOpsReportDto cachedReport = dashboardDataService
                                        .getGcpFinOpsSnapshot("gcp-finops-" + accountId);
                        if (cachedReport != null) {
                                log.info("Serving GCP FinOps report from database cache for account {}", accountId);
                                return CompletableFuture.completedFuture(ResponseEntity.ok(cachedReport));
                        }
                }

                if (forceRefresh) {
                        return gcpFinOpsReportService.refreshFinOpsReport(accountId)
                                        .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                        .exceptionally(ex -> {
                                                log.error("Failed to generate GCP FinOps report for accountId: {}",
                                                                accountId, ex);
                                                return ResponseEntity
                                                                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                                .body(Map.of("error",
                                                                                "Failed to retrieve GCP FinOps report.",
                                                                                "message",
                                                                                ex.getMessage() != null
                                                                                                ? ex.getMessage()
                                                                                                : "Unknown error"));
                                        });
                }

                return gcpFinOpsReportService.generateFinOpsReport(accountId)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to generate GCP FinOps report for accountId: {}", accountId,
                                                        ex);
                                        return ResponseEntity
                                                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Map.of("error", "Failed to retrieve GCP FinOps report.",
                                                                        "message",
                                                                        ex.getMessage() != null ? ex.getMessage()
                                                                                        : "Unknown error"));
                                });
        }

        /**
         * Get cost breakdown by service with optional date filtering.
         */
        @GetMapping("/cost-by-service")
        public CompletableFuture<ResponseEntity<?>> getCostByService(
                        @RequestParam String accountId,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                log.info("Fetching cost by service for GCP project: {} (dates: {} to {})", accountId, startDate,
                                endDate);

                return gcpCostService.getBillingSummaryWithDateRange(accountId, startDate, endDate)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to fetch cost by service for project {}", accountId, ex);
                                        return ResponseEntity
                                                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Map.of("error", "Failed to retrieve cost by service."));
                                });
        }

        /**
         * Get cost breakdown by region with optional date filtering.
         */
        @GetMapping("/cost-by-region")
        public CompletableFuture<ResponseEntity<?>> getCostByRegion(
                        @RequestParam String accountId,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                log.info("Fetching cost by region for GCP project: {} (dates: {} to {})", accountId, startDate,
                                endDate);

                return gcpCostService.getCostByRegionWithDateRange(accountId, startDate, endDate)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to fetch cost by region for project {}", accountId, ex);
                                        return ResponseEntity
                                                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Map.of("error", "Failed to retrieve cost by region."));
                                });
        }

        /**
         * Get cost breakdown by label (tag) with optional date filtering.
         */
        @GetMapping("/cost-by-label")
        public CompletableFuture<ResponseEntity<?>> getCostByLabel(
                        @RequestParam String accountId,
                        @RequestParam String labelKey,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                log.info("Fetching cost by label '{}' for GCP project: {} (dates: {} to {})",
                                labelKey, accountId, startDate, endDate);

                return gcpCostService.getCostByLabel(accountId, labelKey, startDate, endDate)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to fetch cost by label for project {}", accountId, ex);
                                        return ResponseEntity
                                                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Map.of("error", "Failed to retrieve cost by label."));
                                });
        }

        // ==================== BUDGET MANAGEMENT ====================

        /**
         * Get all budgets for a GCP project.
         */
        @GetMapping("/budgets")
        public CompletableFuture<ResponseEntity<?>> getBudgets(@RequestParam String accountId) {
                log.info("Fetching budgets for GCP project: {}", accountId);
                return gcpBudgetService.getBudgets(accountId)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to fetch budgets for project {}", accountId, ex);
                                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Collections.emptyList());
                                });
        }

        /**
         * Create a new budget for a GCP project.
         */
        @PostMapping("/budgets")
        public ResponseEntity<?> createBudget(
                        @RequestParam String accountId,
                        @RequestBody GcpBudgetCreateRequestDto budgetRequest,
                        Principal principal) {

                try {
                        String username = principal != null ? principal.getName() : "system";
                        log.info("Creating budget '{}' for GCP project {} by user {}",
                                        budgetRequest.getBudgetName(), accountId, username);

                        GcpBudgetDto createdBudget = gcpBudgetService.createBudget(accountId, budgetRequest, username);

                        // Invalidate database snapshot to ensure dashboard reflects new budget
                        dashboardDataService.deleteSnapshot("gcp-finops-" + accountId);

                        return ResponseEntity.status(HttpStatus.CREATED).body(createdBudget);
                } catch (Exception e) {
                        log.error("Error creating budget for GCP project {}", accountId, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(Collections.singletonMap("error",
                                                        "Failed to create budget: " + e.getMessage()));
                }
        }

        /**
         * Delete a budget by name for a GCP project.
         */
        @DeleteMapping("/budgets")
        public ResponseEntity<?> deleteBudget(
                        @RequestParam String accountId,
                        @RequestParam String budgetName) {

                try {
                        log.info("Deleting budget '{}' for GCP project {}", budgetName, accountId);
                        gcpBudgetService.deleteBudget(accountId, budgetName);

                        // Invalidate database snapshot
                        dashboardDataService.deleteSnapshot("gcp-finops-" + accountId);

                        return ResponseEntity.ok(Map.of("message", "Budget deleted successfully"));
                } catch (Exception e) {
                        log.error("Error deleting budget '{}' for GCP project {}", budgetName, accountId, e);
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                        .body(Collections.singletonMap("error",
                                                        "Failed to delete budget: " + e.getMessage()));
                }
        }

        // ==================== COST ANOMALIES ====================

        /**
         * Get cost anomalies for a GCP project.
         */
        @GetMapping("/anomalies")
        public CompletableFuture<ResponseEntity<?>> getCostAnomalies(@RequestParam String accountId) {
                log.info("Fetching cost anomalies for GCP project: {}", accountId);

                return gcpCostService.getCostAnomalies(accountId)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to fetch cost anomalies for project {}", accountId, ex);
                                        return ResponseEntity
                                                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Map.of("error", "Failed to retrieve cost anomalies."));
                                });
        }

        // ==================== UTILITIES ====================

        /**
         * Download FinOps report (placeholder).
         */
        @GetMapping("/report/download")
        public ResponseEntity<byte[]> downloadReport(@RequestParam String accountId) {
                log.warn("PDF download is not implemented. This is a placeholder.");
                return ResponseEntity.ok().body(new byte[0]);
        }

        /**
         * Refresh the FinOps report cache for a GCP project.
         */
        @PostMapping("/report/refresh")
        public CompletableFuture<ResponseEntity<?>> refreshReport(@RequestParam String accountId) {
                log.info("Refreshing GCP FinOps report for accountId: {}", accountId);

                return gcpFinOpsReportService.refreshFinOpsReport(accountId)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        log.error("Failed to refresh GCP FinOps report for accountId: {}", accountId,
                                                        ex);
                                        return ResponseEntity
                                                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                                                        .body(Map.of("error", "Failed to refresh GCP FinOps report.",
                                                                        "message", ex.getMessage()));
                                });
        }
}
