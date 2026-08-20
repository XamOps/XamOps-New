package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.dto.CloudsitterRecommendationDto;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.CloudsitterService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xamops/cloudsitter")
public class CloudsitterController {

    private final CloudsitterService cloudsitterService;

    public CloudsitterController(CloudsitterService cloudsitterService) {
        this.cloudsitterService = cloudsitterService;
    }

    @GetMapping("/policies")
    public ResponseEntity<List<CloudsitterPolicy>> getAllPolicies() {
        return ResponseEntity.ok(cloudsitterService.getAllPolicies());
    }

    @GetMapping("/assignments")
    public ResponseEntity<Map<String, CloudsitterAssignmentDto>> getAssignments(@RequestParam String accountId) {
        return ResponseEntity.ok(cloudsitterService.getAssignmentsForAccount(accountId));
    }

    // ✅ UPDATED: Returns created policy
    @PostMapping("/policies")
    public ResponseEntity<CloudsitterPolicy> createPolicy(@RequestBody CloudsitterPolicyDto dto) {
        CloudsitterPolicy created = cloudsitterService.createPolicy(dto);
        return ResponseEntity.ok(created);
    }

    // ✅ NEW: Update policy
    @PutMapping("/policies/{id}")
    public ResponseEntity<CloudsitterPolicy> updatePolicy(@PathVariable Long id,
            @RequestBody CloudsitterPolicyDto dto) {
        CloudsitterPolicy updated = cloudsitterService.updatePolicy(id, dto);
        return ResponseEntity.ok(updated);
    }

    // ✅ NEW: Delete policy
    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable Long id) {
        cloudsitterService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignPolicy(@RequestBody CloudsitterAssignmentRequest request) {
        // Returns a per-instance outcome map: some instances are refused (ASG members,
        // Kubernetes nodes, production-tagged) and the caller needs to know which.
        Map<String, String> outcomes = cloudsitterService.assignPolicyToInstances(request);
        long assigned = outcomes.values().stream().filter("ASSIGNED"::equals).count();
        long skipped = outcomes.size() - assigned;
        return ResponseEntity.ok(Map.of(
                "assigned", assigned,
                "skipped", skipped,
                "results", outcomes));
    }

    @GetMapping("/audit-log")
    public ResponseEntity<Map<String, Object>> getAuditLog(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal ClientUserDetails userDetails) {
        return ResponseEntity.ok(cloudsitterService.getAuditLog(accountId, userDetails.getClientId(), page, size));
    }

    @GetMapping("/recommendation")
    public ResponseEntity<CloudsitterRecommendationDto> getRecommendation(
            @RequestParam String accountId,
            @RequestParam String instanceId,
            @RequestParam String region,
            @RequestParam(defaultValue = "30") int lookbackDays,
            @RequestParam(required = false) String timeZone) {
        // timeZone must match the policy that will adopt this schedule; the enforcer
        // evaluates schedules in the policy's zone, not UTC.
        CloudsitterRecommendationDto dto = cloudsitterService.getScheduleRecommendation(
                accountId, instanceId, region, lookbackDays, timeZone);
        return ResponseEntity.ok(dto);
    }
}