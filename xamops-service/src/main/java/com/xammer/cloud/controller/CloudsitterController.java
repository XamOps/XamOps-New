package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
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

    // --- NEW: Get all available policies ---
    @GetMapping("/policies")
    public ResponseEntity<List<CloudsitterPolicy>> getAllPolicies() {
        return ResponseEntity.ok(cloudsitterService.getAllPolicies());
    }

    // --- NEW: Get assignments for specific account ---
    @GetMapping("/assignments")
    public ResponseEntity<Map<String, CloudsitterAssignmentDto>> getAssignments(@RequestParam String accountId) {
        return ResponseEntity.ok(cloudsitterService.getAssignmentsForAccount(accountId));
    }

    @PostMapping("/policies")
    public ResponseEntity<?> createPolicy(@RequestBody CloudsitterPolicyDto dto) {
        cloudsitterService.createPolicy(dto);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignPolicy(@RequestBody CloudsitterAssignmentRequest request) {
        cloudsitterService.assignPolicyToInstances(request);
        return ResponseEntity.ok().build();
    }
}