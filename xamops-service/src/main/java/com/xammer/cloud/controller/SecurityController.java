package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.ExcelExportService;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.SecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/security")
public class SecurityController {

    private static final Logger logger = LoggerFactory.getLogger(SecurityController.class);

    private final SecurityService securityService;
    private final CloudListService cloudListService;
    private final ExcelExportService excelExportService;
    private final CloudAccountRepository cloudAccountRepository;
    private final ProwlerService prowlerService; // ✅ Added ProwlerService

    public SecurityController(SecurityService securityService,
            CloudListService cloudListService,
            ExcelExportService excelExportService,
            CloudAccountRepository cloudAccountRepository,
            ProwlerService prowlerService) { // ✅ Injected here
        this.securityService = securityService;
        this.cloudListService = cloudListService;
        this.excelExportService = excelExportService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.prowlerService = prowlerService;
    }

    @GetMapping("/findings")
    public CompletableFuture<ResponseEntity<List<DashboardData.SecurityFinding>>> getSecurityFindings(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("Fetching security findings for accountId: {}, forceRefresh: {}", accountId, forceRefresh);

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            logger.warn("No account found for accountId: {}", accountId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.ok(Collections.emptyList()));
        }

        CloudAccount account = accounts.get(0);

        return cloudListService.getRegionStatusForAccount(account, forceRefresh)
                .thenCompose(activeRegions -> {
                    logger.info("Found {} active regions for account {}",
                            activeRegions.size(), accountId);
                    return securityService.getComprehensiveSecurityFindings(
                            account, activeRegions, forceRefresh);
                })
                .thenApply(findings -> {
                    logger.info("Returning {} security findings for account {}",
                            findings.size(), accountId);
                    return ResponseEntity.ok(findings);
                })
                .exceptionally(ex -> {
                    logger.error("Error fetching security findings for account {}",
                            accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/export")
    public CompletableFuture<ResponseEntity<byte[]>> exportFindingsToExcel(@RequestParam String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        CloudAccount account = accounts.get(0);

        return cloudListService.getRegionStatusForAccount(account, true)
                .thenCompose((List<DashboardData.RegionStatus> activeRegions) -> securityService
                        .getComprehensiveSecurityFindings(account, activeRegions, true))
                .thenApply(findings -> {
                    ByteArrayInputStream in = excelExportService.exportSecurityFindingsToExcel(findings);
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("Content-Disposition", "attachment; filename=security-findings.xlsx");

                    return ResponseEntity
                            .ok()
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(in.readAllBytes());
                })
                .exceptionally(ex -> {
                    logger.error("Error exporting security findings for account {}", accountId, ex);
                    return ResponseEntity.status(500).build();
                });
    }

    // ✅ New Endpoint for Polling Status
    @GetMapping("/prowler/status")
    public ResponseEntity<Map<String, Object>> getProwlerStatus(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getScanStatus(accountId));
    }
}