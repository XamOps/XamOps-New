package com.xammer.cloud.controller;

import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.upwind.XamSecureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/xamsecure/onboarding")
@RequiredArgsConstructor
public class XamSecureOnboardingController {

    private final XamSecureService xamSecureService;
    private final UpwindAccountConnectionRepository connectionRepo;

    /** Customer-facing: connection + sensor status for the caller's own accounts. */
    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal ClientUserDetails user) {
        return ResponseEntity.ok(xamSecureService.getOnboardingStatus(user.getClientId()));
    }

    /**
     * Admin-only: register/update an Upwind connection for a cloud account.
     * Phase 1 fallback flow — a Xammer admin creates the Cloud account /
     * Sensor credential in the Upwind console and registers the resulting
     * install command here; customers then see it in their Onboarding tab.
     */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String cloudAccountId = body.get("cloudAccountId");
        if (cloudAccountId == null || cloudAccountId.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cloudAccountId is required"));
        }
        UpwindAccountConnection conn = connectionRepo.findByCloudAccountId(cloudAccountId)
                .orElseGet(UpwindAccountConnection::new);
        conn.setCloudAccountId(cloudAccountId);
        if (body.get("status") != null) conn.setStatus(body.get("status"));
        if (body.get("sensorInstallCommand") != null) conn.setSensorInstallCommand(body.get("sensorInstallCommand"));
        if (body.get("notes") != null) conn.setNotes(body.get("notes"));
        conn.setUpdatedAt(Instant.now());
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "saved"));
    }
}
