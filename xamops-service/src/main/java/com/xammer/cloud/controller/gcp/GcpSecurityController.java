package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.gcp.GcpSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/security")
public class GcpSecurityController {

    private static final Logger logger = LoggerFactory.getLogger(GcpSecurityController.class);

    private final GcpSecurityService gcpSecurityService;
    private final ProwlerService prowlerService;

    public GcpSecurityController(GcpSecurityService gcpSecurityService, ProwlerService prowlerService) {
        this.gcpSecurityService = gcpSecurityService;
        this.prowlerService = prowlerService;
    }
    
    @GetMapping("/iam-drift")
    public CompletableFuture<ResponseEntity<List<GcpIamPolicyDriftDto>>> getIamPolicyDrift(@RequestParam String accountId) {
        return gcpSecurityService.getIamPolicyDrift(accountId)
                .thenApply(ResponseEntity::ok);
    }

    @GetMapping("/container-vulnerabilities")
    public CompletableFuture<ResponseEntity<List<GcpContainerVulnerabilityDto>>> getContainerVulnerabilities(@RequestParam String accountId) {
        return gcpSecurityService.getContainerScanningResults(accountId)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Fetches security findings for the specified GCP project.
     * @param accountId The GCP Project ID.
     * @return A CompletableFuture containing a list of security findings.
     */
    @GetMapping("/findings")
    public CompletableFuture<ResponseEntity<List<GcpSecurityFinding>>> getSecurityFindings(@RequestParam String accountId) {
        return gcpSecurityService.getSecurityFindings(accountId)
                .thenApply(ResponseEntity::ok);
    }

    // --- Prowler Compliance Scan endpoints ---

    @PostMapping("/prowler/scan")
    public ResponseEntity<Map<String, Object>> triggerComplianceScan(@RequestParam String accountId) {
        logger.info("GCP Prowler compliance scan triggered for project: {}", accountId);

        Map<String, Object> current = prowlerService.getGcpScanStatus(accountId);
        if ("RUNNING".equals(current.get("status"))) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "A scan is already in progress for this GCP project."));
        }

        String[] frameworks = {
                "cis_2.0_gcp", "soc2_gcp", "pci_3.2.1_gcp",
                "iso27001_2013_gcp", "mitre_attack_gcp"
        };

        prowlerService.triggerGcpScanAsync(accountId, frameworks);

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "GCP compliance scan triggered. Poll /prowler/status for progress."));
    }

    @GetMapping("/prowler/status")
    public ResponseEntity<Map<String, Object>> getScanStatus(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getGcpScanStatus(accountId));
    }

    @GetMapping("/prowler/findings")
    public ResponseEntity<List<ProwlerFinding>> getProwlerFindings(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getCachedGcpFindings(accountId));
    }
}