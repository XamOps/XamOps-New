package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.UpwindAccountConnection;
import com.xammer.cloud.repository.UpwindAccountConnectionRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.upwind.UpwindApiClient;
import com.xammer.cloud.service.upwind.XamSecureService;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/xamsecure/onboarding")
public class XamSecureOnboardingController {

    private final XamSecureService xamSecureService;
    private final UpwindAccountConnectionRepository connectionRepo;
    private final UpwindApiClient apiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Setter
    @Value("${upwind.webhook.secret:}")
    private String webhookSecret;

    @Setter
    @Value("${xamops.public.base-url:http://localhost:8080}")
    private String publicBaseUrl;

    public XamSecureOnboardingController(XamSecureService xamSecureService,
                                          UpwindAccountConnectionRepository connectionRepo,
                                          UpwindApiClient apiClient) {
        this.xamSecureService = xamSecureService;
        this.connectionRepo = connectionRepo;
        this.apiClient = apiClient;
    }

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

    /**
     * Admin-only: register Upwind's real-time detection webhook (vendor=CUSTOM)
     * pointing at our ingest endpoint, authenticated with a BEARER token equal
     * to the shared upwind.webhook.secret.
     */
    @PostMapping("/register-webhook")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> registerWebhook() {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "upwind.webhook.secret not configured"));
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "XamOps XamSecure");
        body.put("vendor", "CUSTOM");
        ObjectNode config = body.putObject("config");
        config.put("url", publicBaseUrl + "/api/xamsecure/webhook/upwind");
        ObjectNode auth = config.putObject("authentication");
        auth.put("type", "BEARER");
        auth.put("token", webhookSecret);
        try {
            JsonNode created = apiClient.createWebhook(body);
            return ResponseEntity.ok(Map.of("webhookId", created.path("id").asText()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
