package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.dto.CloudsitterRecommendationDto;
import com.xammer.cloud.service.CloudsitterService;
import org.springframework.http.ResponseEntity;
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
        cloudsitterService.assignPolicyToInstances(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/recommendation")
    public ResponseEntity<CloudsitterRecommendationDto> getRecommendation(
            @RequestParam String accountId,
            @RequestParam String instanceId,
            @RequestParam String region,
            @RequestParam(defaultValue = "30") int lookbackDays) {
        CloudsitterRecommendationDto dto =
                cloudsitterService.getScheduleRecommendation(accountId, instanceId, region, lookbackDays);
        return ResponseEntity.ok(dto);
    }
}