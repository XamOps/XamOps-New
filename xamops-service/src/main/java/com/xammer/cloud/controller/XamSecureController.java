package com.xammer.cloud.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.upwind.XamSecureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/xamsecure")
@RequiredArgsConstructor
public class XamSecureController {

    private final XamSecureService xamSecureService;

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getSummary(user.getClientId(), accountId));
    }

    @GetMapping("/issues")
    public ResponseEntity<?> issues(@AuthenticationPrincipal ClientUserDetails user,
                                    @RequestParam(required = false) String accountId,
                                    @RequestParam(required = false) String severity) {
        return ResponseEntity.ok(xamSecureService.getIssues(user.getClientId(), accountId, severity));
    }

    @GetMapping("/issues/detail")
    public ResponseEntity<?> issueDetail(@AuthenticationPrincipal ClientUserDetails user,
                                         @RequestParam(required = false) String accountId,
                                         @RequestParam String check) {
        return ResponseEntity.ok(xamSecureService.getIssueDetail(user.getClientId(), accountId, check));
    }

    /**
     * One threat story in full, for the detail panel. Returns an empty object when the story
     * references no detection in the caller's accounts — stories carry no account id of their
     * own, so detection ownership is what establishes tenancy.
     */
    @GetMapping("/stories/{storyId}")
    public ResponseEntity<?> storyDetail(@AuthenticationPrincipal ClientUserDetails user,
                                         @PathVariable String storyId,
                                         @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(
                xamSecureService.getThreatStoryDetail(user.getClientId(), accountId, storyId));
    }

    @GetMapping("/stories")
    public ResponseEntity<?> stories(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getThreatStories(user.getClientId(), accountId));
    }

    /**
     * Findings grouped by image, package or resource — the dimensions Upwind offers next to
     * CVE, and usually the more actionable ones.
     */
    @GetMapping("/vulnerabilities/groups")
    public ResponseEntity<?> vulnerabilityGroups(@AuthenticationPrincipal ClientUserDetails user,
                                                 @RequestParam(required = false) String accountId,
                                                 @RequestParam(defaultValue = "image") String dimension,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getVulnerabilityGroups(
                user.getClientId(), accountId, dimension, page, size));
    }

    /**
     * Aggregates for the Threats dashboard: funnel, seven-day trend, MITRE tactic and source
     * breakdowns. The funnel's raw event count is reported unavailable rather than guessed.
     */
    /** Detections grouped by threat, resource or account — Upwind's Group by chips. */
    @GetMapping("/threats/groups")
    public ResponseEntity<?> threatGroups(@AuthenticationPrincipal ClientUserDetails user,
                                          @RequestParam(required = false) String accountId,
                                          @RequestParam(defaultValue = "threat") String dimension) {
        return ResponseEntity.ok(
                xamSecureService.getThreatGroups(user.getClientId(), accountId, dimension));
    }

    @GetMapping("/threats/overview")
    public ResponseEntity<?> threatOverview(@AuthenticationPrincipal ClientUserDetails user,
                                            @RequestParam(required = false) String accountId,
                                            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(
                xamSecureService.getThreatOverview(user.getClientId(), accountId, days));
    }

    @GetMapping("/attack-surface")
    public ResponseEntity<?> attackSurface(@AuthenticationPrincipal ClientUserDetails user,
                                           @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getAttackSurface(user.getClientId(), accountId));
    }

    @GetMapping("/threats")
    public ResponseEntity<?> threats(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getThreats(user.getClientId(), accountId, severity, page, size));
    }

    @GetMapping("/vulnerabilities/dashboard")
    public ResponseEntity<?> vulnerabilityDashboard(@AuthenticationPrincipal ClientUserDetails user,
                                                    @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getVulnerabilityDashboard(user.getClientId(), accountId));
    }

    @GetMapping("/vulnerabilities/by-cve")
    public ResponseEntity<?> vulnerabilitiesByCve(@AuthenticationPrincipal ClientUserDetails user,
                                                  @RequestParam(required = false) String accountId,
                                                  @RequestParam(required = false) String severity,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size,
                                                  @RequestParam(required = false) String view) {
        return ResponseEntity.ok(xamSecureService.getVulnerabilitiesByCve(
                user.getClientId(), accountId, severity, page, size, view));
    }

    @GetMapping("/vulnerabilities/cve/findings")
    public ResponseEntity<?> cveFindings(@AuthenticationPrincipal ClientUserDetails user,
                                         @RequestParam(required = false) String accountId,
                                         @RequestParam String cveId) {
        return ResponseEntity.ok(xamSecureService.getCveFindings(user.getClientId(), accountId, cveId));
    }

    @GetMapping("/vulnerabilities")
    public ResponseEntity<?> vulnerabilities(@AuthenticationPrincipal ClientUserDetails user,
                                             @RequestParam(required = false) String accountId,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getVulnerabilities(user.getClientId(), accountId, severity, page, size));
    }

    @GetMapping("/posture")
    public ResponseEntity<?> posture(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getPosture(user.getClientId(), accountId, severity, page, size));
    }

    @GetMapping("/inventory/categories")
    public ResponseEntity<?> inventoryCategories(@AuthenticationPrincipal ClientUserDetails user,
                                                 @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getInventoryCategories(user.getClientId(), accountId));
    }

    /** Data-classification / privilege / EOL / exposure rollup over the synced asset catalog. */
    @GetMapping("/inventory/risk")
    public ResponseEntity<?> inventoryRisk(@AuthenticationPrincipal ClientUserDetails user,
                                           @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getInventoryRisk(user.getClientId(), accountId));
    }

    @GetMapping("/inventory/network")
    public ResponseEntity<?> inventoryNetwork(@AuthenticationPrincipal ClientUserDetails user,
                                              @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getNetworkTopology(user.getClientId(), accountId));
    }

    @GetMapping("/inventory")
    public ResponseEntity<?> inventory(@AuthenticationPrincipal ClientUserDetails user,
                                       @RequestParam(required = false) String accountId,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getInventory(user.getClientId(), accountId, category, page, size));
    }

    @GetMapping("/compliance")
    public ResponseEntity<?> compliance(@AuthenticationPrincipal ClientUserDetails user,
                                        @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getCompliance(user.getClientId(), accountId));
    }

    @GetMapping("/configurations/dashboard")
    public ResponseEntity<?> configurationsDashboard(@AuthenticationPrincipal ClientUserDetails user,
                                                     @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getConfigurationsDashboard(user.getClientId(), accountId));
    }

    /**
     * Identities with failing configuration checks. Derived, not fetched — Upwind's
     * Identities module is not reachable on our credential.
     */
    @GetMapping("/identities")
    public ResponseEntity<?> identities(@AuthenticationPrincipal ClientUserDetails user,
                                        @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getIdentities(user.getClientId(), accountId));
    }

    /**
     * Unified view of one resource across configurations, vulnerabilities, detections and
     * API endpoints — the cross-tab join the flat tables couldn't express.
     */
    @GetMapping("/resource")
    public ResponseEntity<?> resourceDetail(@AuthenticationPrincipal ClientUserDetails user,
                                            @RequestParam(required = false) String accountId,
                                            @RequestParam String resourceId) {
        return ResponseEntity.ok(
                xamSecureService.getResourceDetail(user.getClientId(), accountId, resourceId));
    }

    /**
     * Sync freshness per domain, so the UI can show when Upwind data was last reconciled
     * instead of silently rendering stale rows.
     */
    @GetMapping("/sync-status")
    public ResponseEntity<?> syncStatus() {
        return ResponseEntity.ok(xamSecureService.getSyncStatus());
    }

    @GetMapping("/api-security")
    public ResponseEntity<?> apiSecurity(@AuthenticationPrincipal ClientUserDetails user,
                                         @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getApiSecurity(user.getClientId(), accountId));
    }

    @GetMapping("/components")
    public ResponseEntity<?> components(@AuthenticationPrincipal ClientUserDetails user,
                                        @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getComponents(user.getClientId(), accountId));
    }

    @GetMapping("/threat-policies")
    public ResponseEntity<?> threatPolicies(@AuthenticationPrincipal ClientUserDetails user,
                                            @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getThreatPolicies(user.getClientId(), accountId));
    }

    @GetMapping("/workflows")
    public ResponseEntity<?> workflows(@AuthenticationPrincipal ClientUserDetails user,
                                       @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getWorkflows(user.getClientId(), accountId));
    }

    @PostMapping("/threats/{upwindId}/status")
    public ResponseEntity<?> updateThreatStatus(@AuthenticationPrincipal ClientUserDetails user,
                                                @PathVariable String upwindId,
                                                @RequestBody Map<String, String> body) {
        String status = body.getOrDefault("status", "ARCHIVED");
        xamSecureService.updateThreatStatus(user.getClientId(), upwindId, status);
        return ResponseEntity.ok(Map.of("status", status));
    }
}
