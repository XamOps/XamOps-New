package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.domain.CloudsitterPolicy;
import com.xammer.cloud.dto.CloudsitterAssignmentDto;
import com.xammer.cloud.dto.CloudsitterAssignmentRequest;
import com.xammer.cloud.dto.CloudsitterPolicyDto;
import com.xammer.cloud.service.gcp.GcpCloudsitterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gcp/cloudsitter")
public class GcpCloudsitterController {

    private final GcpCloudsitterService gcpCloudsitterService;

    public GcpCloudsitterController(GcpCloudsitterService gcpCloudsitterService) {
        this.gcpCloudsitterService = gcpCloudsitterService;
    }

    @GetMapping("/policies")
    public ResponseEntity<List<CloudsitterPolicy>> getAllPolicies() {
        return ResponseEntity.ok(gcpCloudsitterService.getAllPolicies());
    }

    @GetMapping("/assignments")
    public ResponseEntity<Map<String, CloudsitterAssignmentDto>> getAssignments(
            @RequestParam String gcpProjectId) {
        return ResponseEntity.ok(gcpCloudsitterService.getAssignmentsForProject(gcpProjectId));
    }

    @GetMapping("/vms")
    public ResponseEntity<List<Map<String, String>>> listVms(@RequestParam String gcpProjectId) {
        return ResponseEntity.ok(gcpCloudsitterService.listVmsForProject(gcpProjectId));
    }

    @PostMapping("/policies")
    public ResponseEntity<CloudsitterPolicy> createPolicy(@RequestBody CloudsitterPolicyDto dto) {
        return ResponseEntity.ok(gcpCloudsitterService.createPolicy(dto));
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<CloudsitterPolicy> updatePolicy(@PathVariable Long id,
            @RequestBody CloudsitterPolicyDto dto) {
        return ResponseEntity.ok(gcpCloudsitterService.updatePolicy(id, dto));
    }

    @DeleteMapping("/policies/{id}")
    public ResponseEntity<Void> deletePolicy(@PathVariable Long id) {
        gcpCloudsitterService.deletePolicy(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/assign")
    public ResponseEntity<?> assignPolicy(@RequestBody CloudsitterAssignmentRequest request) {
        gcpCloudsitterService.assignPolicyToInstances(request);
        return ResponseEntity.ok().build();
    }
}
