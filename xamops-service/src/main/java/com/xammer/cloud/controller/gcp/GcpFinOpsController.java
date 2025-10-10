package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
import com.xammer.cloud.service.gcp.GcpFinOpsReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/finops")
@Slf4j
public class GcpFinOpsController {

    private final GcpFinOpsReportService gcpFinOpsReportService;

    public GcpFinOpsController(GcpFinOpsReportService gcpFinOpsReportService) {
        this.gcpFinOpsReportService = gcpFinOpsReportService;
    }

    @GetMapping("/report")
    public CompletableFuture<ResponseEntity<?>> getFinOpsReport(@RequestParam String accountId) {
        log.info("Starting GCP FinOps report for accountId: {}", accountId);

        return gcpFinOpsReportService.generateFinOpsReport(accountId)
                .<ResponseEntity<?>>thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("Failed to generate GCP FinOps report for accountId: {}", accountId, ex);
                    return ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", "Failed to retrieve GCP FinOps report.", "message", ex.getMessage()));
                });
    }

    // Note: The downloadReport functionality is a placeholder.
    @GetMapping("/report/download")
    public ResponseEntity<byte[]> downloadReport(@RequestParam String accountId) {
        log.warn("PDF download is not implemented. This is a placeholder.");
        return ResponseEntity.ok().body(new byte[0]);
    }
}