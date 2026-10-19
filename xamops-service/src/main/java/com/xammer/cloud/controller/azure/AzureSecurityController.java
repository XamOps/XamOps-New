package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.ProwlerFinding;
import com.xammer.cloud.dto.azure.AzureSecurityAssessment;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.azure.AzureSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for Azure Security data (Defender for Cloud).
 *
 * GET /api/xamops/azure/security?accountId={subscriptionId}&forceRefresh=false
 */
@RestController
@RequestMapping("/api/xamops/azure")
public class AzureSecurityController {

    private static final Logger log = LoggerFactory.getLogger(AzureSecurityController.class);

    private final AzureSecurityService azureSecurityService;
    private final ProwlerService prowlerService;

    public AzureSecurityController(AzureSecurityService azureSecurityService, ProwlerService prowlerService) {
        this.azureSecurityService = azureSecurityService;
        this.prowlerService = prowlerService;
    }

    @GetMapping("/security")
    public ResponseEntity<AzureSecurityResponse> getSecurityData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Security request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzureSecurityResponse response = azureSecurityService.getSecurityData(accountId, forceRefresh);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to fetch Azure security data for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.ok(new AzureSecurityResponse(
                    AzureSecurityResponse.Status.API_ERROR, 0, 100, new ArrayList<>(), new ArrayList<>()));
        }
    }

    /**
     * Returns Defender assessments as a flat findings array the shared dashboard Security card
     * can count by severity.
     *
     * <p>The shared {@code SummaryCardsSection} (AWS, GCP, Azure) fetches
     * {@code /security/findings} and buckets each item by its {@code severity} into
     * Critical/High/Medium/Low. AWS and GCP expose that route returning an array; Azure only had
     * {@code /security}, which returns the {@link AzureSecurityResponse} wrapper the card cannot
     * map over, so the card received a 404 and showed "No scan data". This adapts the existing
     * service into the array shape without touching the wrapper route the standalone Azure
     * Security page depends on.
     *
     * <p>Preserves the false-zero/absence discipline: OK or NO_DATA returns 200 with the
     * (possibly empty) array, while PERMISSION_ERROR or API_ERROR returns 503 so the card shows
     * an error state rather than a measured "0 findings" — mirroring {@code AzureWasteController}.
     */
    @GetMapping("/security/findings")
    public ResponseEntity<List<Map<String, Object>>> getSecurityFindings(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            log.info("Azure Security findings request - Subscription: {} (forceRefresh={})", accountId, forceRefresh);
            AzureSecurityResponse resp = azureSecurityService.getSecurityData(accountId, forceRefresh);

            AzureSecurityResponse.Status status = resp == null ? null : resp.getStatus();
            if (status == AzureSecurityResponse.Status.PERMISSION_ERROR
                    || status == AzureSecurityResponse.Status.API_ERROR
                    || status == null) {
                // Do not let a permission/API failure read as a measured "all clear".
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }

            List<Map<String, Object>> findings = new ArrayList<>();
            List<AzureSecurityAssessment> assessments = resp.getAssessments();
            if (assessments != null) {
                for (AzureSecurityAssessment a : assessments) {
                    Map<String, Object> finding = new LinkedHashMap<>();
                    finding.put("id", a.getAssessmentId());
                    // The card only needs severity to bucket the donut; keep the Defender casing.
                    finding.put("severity", a.getSeverity());
                    finding.put("title", a.getDisplayName());
                    finding.put("category", a.getCategory());
                    finding.put("resourceName", a.getResourceName());
                    finding.put("complianceControl", a.getComplianceControl());
                    findings.add(finding);
                }
            }
            return ResponseEntity.ok(findings);
        } catch (Exception e) {
            log.error("Failed to fetch Azure security findings for {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    // --- Prowler Compliance Scan endpoints ---

    @PostMapping("/security/prowler/scan")
    public ResponseEntity<Map<String, Object>> triggerComplianceScan(@RequestParam String accountId) {
        log.info("Azure Prowler compliance scan triggered for subscription: {}", accountId);

        Map<String, Object> current = prowlerService.getAzureScanStatus(accountId);
        if ("RUNNING".equals(current.get("status"))) {
            return ResponseEntity.ok(Map.of(
                    "status", "ALREADY_RUNNING",
                    "message", "A scan is already in progress for this Azure subscription."));
        }

        // Shared with the weekly scheduler via ProwlerFrameworks so the two paths cannot ask for
        // different frameworks. Prowler rejects the ENTIRE --compliance argument if any single
        // name is invalid, so a drifted list scans nothing at all.
        prowlerService.triggerAzureScanAsync(accountId,
                com.xammer.cloud.service.security.ProwlerFrameworks.AZURE);

        return ResponseEntity.ok(Map.of(
                "status", "STARTED",
                "message", "Azure compliance scan triggered. Poll /prowler/status for progress."));
    }

    @GetMapping("/security/prowler/status")
    public ResponseEntity<Map<String, Object>> getScanStatus(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getAzureScanStatus(accountId));
    }

    @GetMapping("/security/prowler/findings")
    public ResponseEntity<List<ProwlerFinding>> getProwlerFindings(@RequestParam String accountId) {
        return ResponseEntity.ok(prowlerService.getCachedAzureFindings(accountId));
    }
}
