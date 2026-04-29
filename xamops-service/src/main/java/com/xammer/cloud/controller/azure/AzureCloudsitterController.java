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
    public ResponseEntity<List<Map<String, String>>> listVms(@RequestParam String subscriptionId) {
        return ResponseEntity.ok(azureCloudsitterService.listVmsForSubscription(subscriptionId));
    }

    @PostMapping("/policies")
    public ResponseEntity<CloudsitterPolicy> createPolicy(@RequestBody CloudsitterPolicyDto dto) {
        return ResponseEntity.ok(azureCloudsitterService.createPolicy(dto));
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<CloudsitterPolicy> updatePolicy(@PathVariable Long id,
            @RequestBody CloudsitterPolicyDto dto) {
        return ResponseEntity.ok(azureCloudsitterService.updatePolicy(id, dto));
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable Long id) {
        azureCloudsitterService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignPolicy(@RequestBody CloudsitterAssignmentRequest request) {
        azureCloudsitterService.assignPolicyToInstances(request);
        return ResponseEntity.ok().build();
    }
}
