package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.ExcelExportService;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.SecurityService;
import com.xammer.cloud.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final CloudAccountRepository cloudAccountRepository;
    private final CloudListService cloudListService;
    private final DashboardDataService dashboardDataService;
    private final ExcelExportService excelExportService;
    private final ProwlerService prowlerService;

    public SecurityController(SecurityService securityService,
            CloudAccountRepository cloudAccountRepository,
            CloudListService cloudListService,
            DashboardDataService dashboardDataService,
            ExcelExportService excelExportService,
            ProwlerService prowlerService) {
        this.securityService = securityService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cloudListService = cloudListService;
        this.dashboardDataService = dashboardDataService;
        this.excelExportService = excelExportService;
        this.prowlerService = prowlerService;
    }

    @GetMapping("/findings")
    public CompletableFuture<ResponseEntity<List<DashboardData.SecurityFinding>>> getSecurityFindings(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("Fetching security findings for accountId: {}, forceRefresh: {}", accountId, forceRefresh);

        String cacheKey = "security-" + accountId;

        if (!forceRefresh) {
            // ⭐ NEW: Check database cache first
            List<DashboardData.SecurityFinding> cachedFindings = (List<DashboardData.SecurityFinding>) dashboardDataService
                    .getSecuritySnapshot(
                            cacheKey, new TypeReference<List<DashboardData.SecurityFinding>>() {
                            });

            if (cachedFindings != null) {
                logger.info("Serving security findings from database cache for account {}", accountId);
                return CompletableFuture.completedFuture(ResponseEntity.ok(cachedFindings));
            }
        }

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            logger.warn("No account found for accountId: {}", accountId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.ok(Collections.emptyList()));
        }

        CloudAccount account = accounts.get(0);

        // Ownership check to prevent IDOR
        if (!SecurityUtils.isAuthorizedForAccount(account)) {
            logger.warn("Unauthorized access attempt to findings for account: {}", accountId);
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(403).body(Collections.emptyList()));
        }

        // Fallback: Fetch fresh data
        logger.info("Fetching fresh security findings for account {}", accountId);
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

        // Ownership check to prevent IDOR
        if (!SecurityUtils.isAuthorizedForAccount(account)) {
            logger.warn("Unauthorized access attempt to export findings for account: {}", accountId);
            throw new RuntimeException("Access Denied");
        }

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

    // ✅ Trigger a full compliance scan with all frameworks
    @PostMapping("/prowler/scan")
    public ResponseEntity<Map<String, Object>> triggerComplianceScan(@RequestParam String accountId) {
        logger.info("Manual compliance scan triggered for accountId: {}", accountId);

        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "ERROR",
                    "message", "Account not found: " + accountId));
        }

        CloudAccount account = accounts.get(0);
        // Ownership check to prevent IDOR
        if (!SecurityUtils.isAuthorizedForAccount(account)) {
            logger.warn("Unauthorized access attempt to trigger scan for account: {}", accountId);
            return ResponseEntity.status(403).body(Map.of("error", "Access Denied"));
        }

        // Check if a scan is already running
        Map<String, Object> currentStatus = prowlerService.getScanStatus(accountId);
        if ("RUNNING".equals(currentStatus.get("status"))) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "A scan is already in progress for this account."));
        }

        // Trigger full compliance scan with all services and frameworks
        String[] services = {
                "s3", "ec2", "iam", "rds", "cloudtrail", "cloudwatch",
                "kms", "lambda", "vpc", "config", "sns", "sqs",
                "efs", "elb", "elbv2", "secretsmanager", "guardduty"
        };
        String[] complianceFrameworks = {
                "cis_3.0_aws", "soc2_aws", "pci_3.2.1_aws",
                "hipaa_aws", "nist_csf_1.1_aws", "nist_800_53_revision_5_aws",
                "gdpr_aws", "iso27001_2013_aws", "mitre_attack_aws",
                "cisa_aws", "fedramp_moderate_revision_4_aws",
                "ffiec_aws", "rbi_cyber_security_framework_aws",
                "ens_rd2022_aws", "aws_well_architected_framework_security_pillar_aws",
                "aws_foundational_security_best_practices_aws",
                "gxp_21_cfr_part_11_aws"
        };

        prowlerService.triggerScanAsync(accountId, "us-east-1", services, complianceFrameworks);

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "Compliance scan triggered successfully. Poll /prowler/status for progress."));
    }

    // ✅ Endpoint for Polling Status
    @GetMapping("/prowler/status")
    public ResponseEntity<Map<String, Object>> getProwlerStatus(@RequestParam String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (!accounts.isEmpty()) {
            if (!SecurityUtils.isAuthorizedForAccount(accounts.get(0))) {
                logger.warn("Unauthorized access attempt to get scan status for account: {}", accountId);
                return ResponseEntity.status(403).body(Map.of("error", "Access Denied"));
            }
        }
        return ResponseEntity.ok(prowlerService.getScanStatus(accountId));
    }
}