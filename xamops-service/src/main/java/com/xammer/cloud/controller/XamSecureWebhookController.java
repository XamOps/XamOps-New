package com.xammer.cloud.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.upwind.UpwindSyncService;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public ingest endpoint for Upwind's real-time detection webhook (vendor=CUSTOM).
 * Auth is a shared secret in the X-Upwind-Secret header (constant-time compare).
 * Registered in Upwind via XamSecureOnboardingController / admin.
 */
@RestController
@RequestMapping("/api/xamsecure/webhook")
@Slf4j
public class XamSecureWebhookController {

    private final UpwindSyncService syncService;
    private final ObjectMapper objectMapper;

    @Setter
    @Value("${upwind.webhook.secret:}")
    private String webhookSecret;

    public XamSecureWebhookController(UpwindSyncService syncService, ObjectMapper objectMapper) {
        this.syncService = syncService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/upwind")
    public ResponseEntity<?> ingest(@RequestHeader(value = "X-Upwind-Secret", required = false) String secret,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody String body) {
        return doIngest(secret, authorization, body);
    }

    /**
     * Backward-compatible overload (no Authorization header) retained for existing callers/tests.
     */
    public ResponseEntity<?> ingest(String secret, String body) {
        return doIngest(secret, null, body);
    }

    private ResponseEntity<?> doIngest(String secret, String authorization, String body) {
        if (secret == null && authorization != null && authorization.startsWith("Bearer ")) {
            secret = authorization.substring("Bearer ".length());
        }
        if (webhookSecret == null || webhookSecret.isEmpty()
                || secret == null
                || !java.security.MessageDigest.isEqual(
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            int count = 0;
            if (json.isArray()) {
                for (JsonNode item : json) { syncService.ingestDetection(item); count++; }
            } else if (json.has("id")) {
                syncService.ingestDetection(json); count++;
            }
            return ResponseEntity.ok(Map.of("ingested", count));
        } catch (Exception e) {
            log.error("[Upwind webhook] ingest failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "invalid payload"));
        }
    }
}
