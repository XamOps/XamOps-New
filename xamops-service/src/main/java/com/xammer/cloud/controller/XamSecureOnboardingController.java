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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
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

    /** Name we register under; also the key used to detect an existing registration. */
    public static final String WEBHOOK_NAME = "XamOps XamSecure";

    /**
     * Admin-only: register Upwind's real-time detection webhook pointing at our ingest
     * endpoint, authenticated with a bearer token equal to the shared upwind.webhook.secret.
     *
     * Uses the documented v2 shape: {@code POST /v2/{org}/integrations/webhooks} with a
     * LOWERCASE vendor ({@code custom}) and the url/authentication nested under
     * {@code config}, whose {@code authentication.type} is likewise lowercase
     * ({@code bearer}). The previous implementation posted uppercase values to the v1
     * {@code /integration-webhooks} path.
     *
     * Idempotent: the webhook search runs first, so re-running this does not accumulate
     * duplicate registrations that would each deliver the same event.
     */
    @PostMapping("/register-webhook")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> registerWebhook() {
        if (webhookSecret == null || webhookSecret.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "upwind.webhook.secret not configured"));
        }
        try {
            for (JsonNode existing : apiClient.searchWebhooks(200)) {
                if (WEBHOOK_NAME.equals(existing.path("name").asText(null))) {
                    return ResponseEntity.ok(Map.of(
                            "webhookId", existing.path("id").asText(),
                            "status", "already-registered"));
                }
            }
        } catch (Exception e) {
            // A search failure shouldn't block registration; worst case we create a duplicate.
            log.warn("[Upwind] webhook search failed before registration: {}", e.getMessage());
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", WEBHOOK_NAME);
        body.put("vendor", "custom");
        ObjectNode config = body.putObject("config");
        config.put("url", publicBaseUrl + "/api/xamsecure/webhook/upwind");
        ObjectNode auth = config.putObject("authentication");
        auth.put("type", "bearer");
        auth.put("token", webhookSecret);
        try {
            JsonNode created = apiClient.createWebhook(body);
            return ResponseEntity.ok(Map.of(
                    "webhookId", created.path("id").asText(), "status", "created"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin-only: list the webhooks registered on the Upwind org, for troubleshooting. */
    @GetMapping("/webhooks")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> listWebhooks() {
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode w : apiClient.searchWebhooks(200)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", w.path("id").asText(null));
                m.put("name", w.path("name").asText(null));
                m.put("vendor", w.path("vendor").asText(null));
                m.put("status", w.path("status").asText(null));
                m.put("url", w.path("config").path("url").asText(null));
                m.put("createTime", w.path("create_time").asText(null));
                rows.add(m);
            }
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin-only: enable/disable or rename a registered webhook. */
    @PatchMapping("/webhooks/{webhookId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> patchWebhook(@PathVariable String webhookId,
                                          @RequestBody Map<String, String> body) {
        Map<String, Object> patch = new LinkedHashMap<>();
        if (body.get("name") != null) patch.put("name", body.get("name"));
        String status = body.get("status");
        if (status != null) {
            if (!"enabled".equals(status) && !"disabled".equals(status)) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "status must be 'enabled' or 'disabled'"));
            }
            patch.put("status", status);
        }
        if (patch.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name or status is required"));
        }
        try {
            return ResponseEntity.ok(apiClient.updateWebhook(webhookId, patch));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin-only: remove a registered webhook. */
    @DeleteMapping("/webhooks/{webhookId}")
    @PreAuthorize("hasAuthority('ROLE_SUPER_ADMIN')")
    public ResponseEntity<?> deleteWebhook(@PathVariable String webhookId) {
        try {
            apiClient.deleteWebhook(webhookId);
            return ResponseEntity.ok(Map.of("status", "deleted", "webhookId", webhookId));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }
}
