package com.xammer.cloud.controller.azure;

import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.service.azure.AzureCloudsitterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/azure/cloudsitter")
public class AzureCloudsitterController {

    private final AzureCloudsitterService azureCloudsitterService;

    public AzureCloudsitterController(AzureCloudsitterService azureCloudsitterService) {
        this.azureCloudsitterService = azureCloudsitterService;
    }

    @GetMapping("/policies")
    public ResponseEntity<List<CloudsitterPolicy>> getAllPolicies() {
        return ResponseEntity.ok(azureCloudsitterService.getAllPolicies());
    }

    @GetMapping("/assignments")
    public ResponseEntity<Map<String, CloudsitterAssignmentDto>> getAssignments(
            @RequestParam String subscriptionId) {
        return ResponseEntity.ok(azureCloudsitterService.getAssignmentsForSubscription(subscriptionId));
    }

    @GetMapping("/vms")
    public ResponseEntity<?> listVms(@RequestParam String subscriptionId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        try {
            return ResponseEntity.ok(azureCloudsitterService.listVmsForSubscription(subscriptionId, refresh));
        } catch (IllegalStateException e) {
            // A failed listing is an error, not an empty subscription.
            return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/policies")
    public ResponseEntity<CloudsitterPolicy> createPolicy(@RequestBody CloudsitterPolicyDto dto) {
        return ResponseEntity.ok(azureCloudsitterService.createPolicy(dto));
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<?> updatePolicy(@PathVariable Long id,
            @RequestBody CloudsitterPolicyDto dto) {
        try {
            return ResponseEntity.ok(azureCloudsitterService.updatePolicy(id, dto));
        } catch (IllegalStateException e) {
            // A policy AWS or GCP instances also use: say why instead of a bare 500.
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<?> deletePolicy(@PathVariable Long id) {
        try {
            azureCloudsitterService.deletePolicy(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignPolicy(@RequestBody CloudsitterAssignmentRequest request) {
        azureCloudsitterService.assignPolicyToInstances(request);
        return ResponseEntity.ok().build();
    }
}
