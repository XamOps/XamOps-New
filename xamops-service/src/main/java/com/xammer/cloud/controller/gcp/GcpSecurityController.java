package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GcpContainerVulnerabilityDto;
import com.xammer.cloud.dto.gcp.GcpIamPolicyDriftDto;
import com.xammer.cloud.dto.gcp.GcpSecurityFinding;
import com.xammer.cloud.service.gcp.GcpSecurityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/gcp/security")
public class GcpSecurityController {

    private final GcpSecurityService gcpSecurityService;

    public GcpSecurityController(GcpSecurityService gcpSecurityService) {
        this.gcpSecurityService = gcpSecurityService;
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
}