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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


@RestController
@RequestMapping("/api/xamops/gcp/finops")
@Slf4j
public class GcpFinOpsController {

        private final GcpFinOpsReportService gcpFinOpsReportService;
        private final GcpBudgetService gcpBudgetService;
        private final GcpCostService gcpCostService;

        public GcpFinOpsController(GcpFinOpsReportService gcpFinOpsReportService,
                        GcpBudgetService gcpBudgetService,
                        GcpCostService gcpCostService) {
                this.gcpFinOpsReportService = gcpFinOpsReportService;
                this.gcpBudgetService = gcpBudgetService;
                this.gcpCostService = gcpCostService;
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

                log.info("[GcpFinOps] /report START — accountId={} forceRefresh={} dates={}-{}", accountId, forceRefresh, startDate, endDate);
                try {
                        GcpFinOpsReportDto report;
                        if (startDate != null && endDate != null) {
                                report = gcpFinOpsReportService
                                        .generateFinOpsReportWithDateRange(accountId, startDate, endDate, forceRefresh)
                                        .get(120, TimeUnit.SECONDS);
                        } else {
                                report = gcpFinOpsReportService
                                        .generateFinOpsReport(accountId, forceRefresh)
                                        .get(120, TimeUnit.SECONDS);
                        }
                        if (report == null) return CompletableFuture.completedFuture(ResponseEntity.noContent().build());
                        log.info("[GcpFinOps] /report OK — accountId={}", accountId);
                        return CompletableFuture.completedFuture(ResponseEntity.ok(report));
                } catch (TimeoutException e) {
                        log.warn("[GcpFinOps] /report TIMEOUT — accountId={}", accountId);
                        return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Map.of("error", "GCP FinOps report timed out. Please try again.")));
                } catch (Exception e) {
                        log.error("[GcpFinOps] /report ERROR — accountId={}", accountId, e);
                        return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                .body(Map.of("error", "Failed to retrieve GCP FinOps report.",
                                             "message", e.getMessage() != null ? e.getMessage() : "Unknown error")));
                }
        }

        /**
         * Get cost breakdown by service with optional date filtering.
         */
        @GetMapping("/cost-by-service")
        public CompletableFuture<ResponseEntity<?>> getCostByService(
                        @RequestParam String accountId,
                        @RequestParam(defaultValue = "false") boolean forceRefresh,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                log.info("[GcpFinOps] cost-by-service START — account={} forceRefresh={} dates={}-{}", accountId, forceRefresh, startDate, endDate);
                final long startMs = System.currentTimeMillis();

                return gcpCostService.getBillingSummaryWithDateRange(accountId, startDate, endDate, forceRefresh)
                                .orTimeout(55, TimeUnit.SECONDS)
                                .<ResponseEntity<?>>thenApply(data -> {
                                        log.info("[GcpFinOps] cost-by-service OK — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                                        return ResponseEntity.ok(data);
                                })
                                .exceptionally(ex -> {
                                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                        if (cause instanceof TimeoutException) { log.warn("[GcpFinOps] cost-by-service TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList()); }
                                        log.error("[GcpFinOps] cost-by-service ERROR — account={}", accountId, ex);
                                        return ResponseEntity.ok(Collections.emptyList());
                                });
        }

        /**
         * Get cost breakdown by region with optional date filtering.
         */
        @GetMapping("/cost-by-region")
        public CompletableFuture<ResponseEntity<?>> getCostByRegion(
                        @RequestParam String accountId,
                        @RequestParam(defaultValue = "false") boolean forceRefresh,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                log.info("[GcpFinOps] cost-by-region START — account={} forceRefresh={} dates={}-{}", accountId, forceRefresh, startDate, endDate);
                final long startMs = System.currentTimeMillis();

                return gcpCostService.getCostByRegionWithDateRange(accountId, startDate, endDate, forceRefresh)
                                .orTimeout(55, TimeUnit.SECONDS)
                                .<ResponseEntity<?>>thenApply(data -> {
                                        log.info("[GcpFinOps] cost-by-region OK — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                                        return ResponseEntity.ok(data);
                                })
                                .exceptionally(ex -> {
                                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                        if (cause instanceof TimeoutException) { log.warn("[GcpFinOps] cost-by-region TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList()); }
                                        log.error("[GcpFinOps] cost-by-region ERROR — account={}", accountId, ex);
                                        return ResponseEntity.ok(Collections.emptyList());
                                });
        }

        /**
         * Get cost breakdown by label (tag) with optional date filtering.
         */
        @GetMapping("/cost-by-label")
        public CompletableFuture<ResponseEntity<?>> getCostByLabel(
                        @RequestParam String accountId,
                        @RequestParam String labelKey,
                        @RequestParam(defaultValue = "false") boolean forceRefresh,
                        @RequestParam(required = false) String startDate,
                        @RequestParam(required = false) String endDate) {

                log.info("[GcpFinOps] cost-by-label START — account={} label={} forceRefresh={} dates={}-{}", accountId, labelKey, forceRefresh, startDate, endDate);
                final long startMs = System.currentTimeMillis();

                return gcpCostService.getCostByLabel(accountId, labelKey, startDate, endDate, forceRefresh)
                                .orTimeout(55, TimeUnit.SECONDS)
                                .<ResponseEntity<?>>thenApply(data -> {
                                        log.info("[GcpFinOps] cost-by-label OK — account={} label={} durationMs={}", accountId, labelKey, System.currentTimeMillis() - startMs);
                                        return ResponseEntity.ok(data);
                                })
                                .exceptionally(ex -> {
                                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                        if (cause instanceof TimeoutException) { log.warn("[GcpFinOps] cost-by-label TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList()); }
                                        log.error("[GcpFinOps] cost-by-label ERROR — account={}", accountId, ex);
                                        return ResponseEntity.ok(Collections.emptyList());
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
                                .orTimeout(55, TimeUnit.SECONDS)
                                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                                .exceptionally(ex -> {
                                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                        if (cause instanceof TimeoutException) { log.warn("[GcpFinOps] budgets TIMEOUT for {}", accountId); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList()); }
                                        log.error("[GcpFinOps] budgets ERROR for {}", accountId, ex);
                                        return ResponseEntity.ok(Collections.emptyList());
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

                        // Invalidate FinOps cache (Redis + DB) so next load reflects the new budget
                        gcpFinOpsReportService.clearFinOpsReportCache(accountId);

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

                        // Invalidate FinOps cache (Redis + DB) so next load reflects the deletion
                        gcpFinOpsReportService.clearFinOpsReportCache(accountId);

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
        public CompletableFuture<ResponseEntity<?>> getCostAnomalies(
                        @RequestParam String accountId,
                        @RequestParam(defaultValue = "false") boolean forceRefresh) {

                log.info("[GcpFinOps] anomalies START — account={} forceRefresh={}", accountId, forceRefresh);
                final long startMs = System.currentTimeMillis();

                return gcpCostService.getCostAnomalies(accountId, forceRefresh)
                                .orTimeout(55, TimeUnit.SECONDS)
                                .<ResponseEntity<?>>thenApply(data -> {
                                        log.info("[GcpFinOps] anomalies OK — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs);
                                        return ResponseEntity.ok(data);
                                })
                                .exceptionally(ex -> {
                                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                        if (cause instanceof TimeoutException) { log.warn("[GcpFinOps] anomalies TIMEOUT — account={} durationMs={}", accountId, System.currentTimeMillis() - startMs); return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Collections.emptyList()); }
                                        log.error("[GcpFinOps] anomalies ERROR — account={}", accountId, ex);
                                        return ResponseEntity.ok(Collections.emptyList());
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
