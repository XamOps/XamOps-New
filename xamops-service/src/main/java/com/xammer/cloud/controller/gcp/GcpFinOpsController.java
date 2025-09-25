package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.gcp.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/finops")
@Slf4j
public class GcpFinOpsController {

    private final GcpCostService gcpCostService;
    private final GcpOptimizationService gcpOptimizationService;
    private final GcpDataService gcpDataService;
    private final GcpBudgetService gcpBudgetService;
    private final CloudAccountRepository cloudAccountRepository;
    private final GcpFinOpsReportService gcpFinOpsReportService;


    public GcpFinOpsController(GcpCostService gcpCostService, GcpOptimizationService gcpOptimizationService, GcpDataService gcpDataService, GcpBudgetService gcpBudgetService, CloudAccountRepository cloudAccountRepository, GcpFinOpsReportService gcpFinOpsReportService) {
        this.gcpCostService = gcpCostService;
        this.gcpOptimizationService = gcpOptimizationService;
        this.gcpDataService = gcpDataService;
        this.gcpBudgetService = gcpBudgetService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.gcpFinOpsReportService = gcpFinOpsReportService;
    }

    @GetMapping("/report/download")
    public CompletableFuture<ResponseEntity<byte[]>> downloadReport(@RequestParam String accountId) {
        return getFinOpsReport(accountId).thenApply(reportEntity -> {
            GcpFinOpsReportDto reportDto = reportEntity.getBody();
            ByteArrayInputStream pdf = gcpFinOpsReportService.generatePdfReport(reportDto);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Disposition", "inline; filename=gcp-finops-report.pdf");

            return ResponseEntity
                    .ok()
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf.readAllBytes());
        });
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<GcpFinOpsReportDto>> getFinOpsReport(@RequestParam String accountId) {
        log.info("Starting GCP FinOps report for accountId: {}", accountId);
        GcpFinOpsReportDto finalReport = new GcpFinOpsReportDto();

        CloudAccount account = cloudAccountRepository.findByGcpProjectId(accountId)
                .orElseThrow(() -> new RuntimeException("GCP Account not found: " + accountId));
        
        String billingAccountId = account.getBillingExportTable();

        CompletableFuture<Void> billingFuture = gcpCostService.getBillingSummary(accountId).thenAccept(summary -> {
            finalReport.setBillingSummary(summary);
            double mtd = summary.stream().mapToDouble(dto -> dto.getAmount()).sum();
            finalReport.setMonthToDateSpend(mtd);
            finalReport.setForecastedSpend(gcpDataService.calculateForecastedSpend(mtd));
        });

        CompletableFuture<Void> historyFuture = gcpCostService.getHistoricalCosts(accountId).thenAccept(history -> {
            finalReport.setCostHistory(history);
            if (history.size() > 1) finalReport.setLastMonthSpend(history.get(history.size() - 2).getAmount());
        });
        
        CompletableFuture<Void> taggingComplianceFuture = gcpDataService.getTagComplianceReport(accountId)
            .thenAccept(finalReport::setTaggingCompliance);

        return CompletableFuture.allOf(
            billingFuture, historyFuture, taggingComplianceFuture,
            gcpCostService.getCostByTag(accountId, "owner").thenAccept(finalReport::setCostAllocationByTag),
            gcpOptimizationService.getOptimizationSummary(accountId).thenAccept(finalReport::setOptimizationSummary),
            gcpOptimizationService.getRightsizingRecommendations(accountId).thenAccept(finalReport::setRightsizingRecommendations),
            gcpOptimizationService.getWasteReport(accountId).thenAccept(finalReport::setWastedResources),
            gcpBudgetService.getBudgets(billingAccountId).thenAccept(finalReport::setBudgets)
        ).thenApply(v -> {
            log.info("Successfully aggregated GCP FinOps report for accountId: {}", accountId);
            return ResponseEntity.ok(finalReport);
        }).exceptionally(ex -> {
            log.error("Failed to generate GCP FinOps report for accountId: {}", accountId, ex);
            return ResponseEntity.internalServerError().build();
        });
    }
}