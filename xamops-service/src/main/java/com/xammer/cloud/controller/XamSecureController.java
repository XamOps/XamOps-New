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

    @GetMapping("/threats")
    public ResponseEntity<?> threats(@AuthenticationPrincipal ClientUserDetails user,
                                     @RequestParam(required = false) String accountId,
                                     @RequestParam(required = false) String severity,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(xamSecureService.getThreats(user.getClientId(), accountId, severity, page, size));
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

    @GetMapping("/api-security")
    public ResponseEntity<?> apiSecurity(@AuthenticationPrincipal ClientUserDetails user,
                                         @RequestParam(required = false) String accountId) {
        return ResponseEntity.ok(xamSecureService.getApiSecurity(user.getClientId(), accountId));
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
