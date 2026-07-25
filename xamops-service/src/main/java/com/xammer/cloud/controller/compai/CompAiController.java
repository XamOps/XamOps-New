package com.xammer.cloud.controller.compai;

import com.xammer.cloud.service.compai.CompAiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SOC2 Program endpoints. Backed by a self-hosted comp.ai instance via
 * {@link CompAiService}; the frontend "SOC2 Program" page consumes these.
 */
@RestController
@RequestMapping("/api/soc2")
@RequiredArgsConstructor
public class CompAiController {

    private final CompAiService compAiService;

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        return ResponseEntity.ok(compAiService.getOverview());
    }

    @GetMapping("/controls")
    public ResponseEntity<?> controls() {
        return ResponseEntity.ok(compAiService.getControls());
    }

    @GetMapping("/policies")
    public ResponseEntity<?> policies() {
        return ResponseEntity.ok(compAiService.getPolicies());
    }

    @GetMapping("/tasks")
    public ResponseEntity<?> tasks() {
        return ResponseEntity.ok(compAiService.getTasks());
    }

    @GetMapping("/personnel")
    public ResponseEntity<?> personnel() {
        return ResponseEntity.ok(compAiService.getPersonnel());
    }

    @GetMapping("/vendors")
    public ResponseEntity<?> vendors() {
        return ResponseEntity.ok(compAiService.getVendors());
    }

    @GetMapping("/risks")
    public ResponseEntity<?> risks() {
        return ResponseEntity.ok(compAiService.getRisks());
    }

    @GetMapping("/cloud-tests")
    public ResponseEntity<?> cloudTests() {
        return ResponseEntity.ok(compAiService.getCloudTests());
    }

    @GetMapping("/integrations")
    public ResponseEntity<?> integrations() {
        return ResponseEntity.ok(compAiService.getIntegrations());
    }
}
