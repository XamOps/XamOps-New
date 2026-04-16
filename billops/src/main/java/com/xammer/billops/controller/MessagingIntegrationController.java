package com.xammer.billops.controller;

import com.xammer.billops.domain.MessagingIntegrationConfig;
import com.xammer.billops.domain.UserPlatformConnection;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import com.xammer.billops.service.ExternalMessagingService;
import com.xammer.cloud.domain.AppUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Admin API for managing messaging platform integrations.
 *
 * Platform config (admin, one-time):
 *   GET    /api/billops/messaging/config              → list all platform configs (tokens masked)
 *   POST   /api/billops/messaging/config              → save / update a platform config
 *   DELETE /api/billops/messaging/config/{platform}   → disable a platform
 *
 * Per-user connections (admin sets per conversation):
 *   GET    /api/billops/messaging/users/{userId}/connections               → user's connections
 *   POST   /api/billops/messaging/users/{userId}/connections               → add / update connection
 *   DELETE /api/billops/messaging/users/{userId}/connections/{platform}    → remove connection
 */
@RestController
@RequestMapping("/api/billops/messaging")
@CrossOrigin(origins = "*")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
public class MessagingIntegrationController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;
    private final AppUserRepository appUserRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    public MessagingIntegrationController(MessagingIntegrationConfigRepository configRepo,
                                          UserPlatformConnectionRepository connectionRepo,
                                          AppUserRepository appUserRepository) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
        this.appUserRepository = appUserRepository;
    }

    // ── Platform Config (admin, tenant-wide) ──────────────────────────────────

    /** List all platform configs. Bot tokens are masked for security. */
    @GetMapping("/config")
    public List<Map<String, Object>> getAllConfigs() {
        List<MessagingIntegrationConfig> configs = configRepo.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MessagingIntegrationConfig c : configs) {
            result.add(maskConfig(c));
        }
        return result;
    }

    /** Save or update a platform's integration config. */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, String> payload) {
        String platform = payload.get("platform");
        if (platform == null || platform.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform is required"));
        }
        platform = platform.toUpperCase();

        MessagingIntegrationConfig config = configRepo.findByPlatform(platform)
                .orElse(new MessagingIntegrationConfig());

        if (config.getId() == null) {
            config.setCreatedAt(LocalDateTime.now(IST));
        }
        config.setPlatform(platform);
        config.setUpdatedAt(LocalDateTime.now(IST));

        // Only update fields that are provided (don't overwrite with blanks)
        if (payload.containsKey("botToken") && !payload.get("botToken").isBlank()) {
            config.setBotToken(payload.get("botToken"));
        }
        if (payload.containsKey("signingSecret") && !payload.get("signingSecret").isBlank()) {
            config.setSigningSecret(payload.get("signingSecret"));
        }
        if (payload.containsKey("label")) {
            config.setLabel(payload.get("label"));
        }
        if (payload.containsKey("enabled")) {
            config.setEnabled("true".equalsIgnoreCase(payload.get("enabled")));
        } else {
            config.setEnabled(true); // default to enabled when saving
        }

        MessagingIntegrationConfig saved = configRepo.save(config);
        return ResponseEntity.ok(maskConfig(saved));
    }

    /** Disable (soft-delete) a platform config. */
    @DeleteMapping("/config/{platform}")
    public ResponseEntity<Map<String, Object>> disableConfig(@PathVariable String platform) {
        return configRepo.findByPlatform(platform.toUpperCase()).map(config -> {
            config.setEnabled(false);
            config.setUpdatedAt(LocalDateTime.now(IST));
            configRepo.save(config);
            Map<String, Object> body = new HashMap<>();
            body.put("disabled", true);
            body.put("platform", platform.toUpperCase());
            return ResponseEntity.<Map<String, Object>>ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Per-User Platform Connections ─────────────────────────────────────────

    /** Get all platform connections for a user. */
    @GetMapping("/users/{userId}/connections")
    public List<Map<String, Object>> getUserConnections(@PathVariable Long userId) {
        List<UserPlatformConnection> connections = connectionRepo.findByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserPlatformConnection c : connections) {
            result.add(connectionToMap(c));
        }
        return result;
    }

    /**
     * Add or update a user's platform connection.
     * Body: { platform, webhookUrl, channelId, platformUserId }
     */
    @PostMapping("/users/{userId}/connections")
    public ResponseEntity<Map<String, Object>> saveUserConnection(
            @PathVariable Long userId,
            @RequestBody Map<String, String> payload) {

        String platform = payload.get("platform");
        if (platform == null || platform.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "platform is required"));
        }
        platform = platform.toUpperCase();

        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, platform)
                .orElse(new UserPlatformConnection());

        if (conn.getId() == null) {
            conn.setUserId(userId);
            conn.setPlatform(platform);
            conn.setCreatedAt(LocalDateTime.now(IST));
        }
        conn.setUpdatedAt(LocalDateTime.now(IST));
        conn.setActive(true);

        if (payload.containsKey("webhookUrl")) conn.setWebhookUrl(payload.get("webhookUrl"));
        if (payload.containsKey("channelId"))  conn.setChannelId(payload.get("channelId"));
        if (payload.containsKey("platformUserId")) conn.setPlatformUserId(payload.get("platformUserId"));

        UserPlatformConnection saved = connectionRepo.save(conn);
        return ResponseEntity.ok(connectionToMap(saved));
    }

    /** Remove a user's connection for a specific platform. */
    @DeleteMapping("/users/{userId}/connections/{platform}")
    public ResponseEntity<Map<String, Object>> removeUserConnection(
            @PathVariable Long userId,
            @PathVariable String platform) {

        return connectionRepo.findByUserIdAndPlatform(userId, platform.toUpperCase()).map(conn -> {
            conn.setActive(false);
            conn.setUpdatedAt(LocalDateTime.now(IST));
            connectionRepo.save(conn);
            Map<String, Object> body = new HashMap<>();
            body.put("removed", true);
            body.put("userId", userId);
            body.put("platform", platform.toUpperCase());
            return ResponseEntity.<Map<String, Object>>ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Debug: test Slack auto-discovery for a user ───────────────────────────

    /**
     * Test endpoint — checks every step of the Slack email-discovery flow.
     * GET /api/billops/messaging/test/slack/{userId}
     */
    @GetMapping("/test/slack/{userId}")
    public ResponseEntity<Map<String, Object>> testSlack(@PathVariable Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Step 1: user email
        AppUser user = appUserRepository.findById(userId).orElse(null);
        result.put("step1_userId", userId);
        result.put("step1_email", user != null ? user.getEmail() : "USER NOT FOUND");
        if (user == null || user.getEmail() == null) {
            result.put("error", "No email found for userId=" + userId);
            return ResponseEntity.ok(result);
        }
        String email = user.getEmail();

        // Step 2: bot token
        MessagingIntegrationConfig config = configRepo.findByPlatform("SLACK").orElse(null);
        result.put("step2_slackConfigured", config != null && config.isEnabled() && config.getBotToken() != null);
        if (config == null || !config.isEnabled() || config.getBotToken() == null) {
            result.put("error", "Slack bot token not configured");
            return ResponseEntity.ok(result);
        }

        // Step 3: users.lookupByEmail
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(config.getBotToken());
            HttpEntity<?> req = new HttpEntity<>(headers);
            ResponseEntity<Map> lookupResp = restTemplate.exchange(
                    "https://slack.com/api/users.lookupByEmail?email=" + email,
                    HttpMethod.GET, req, Map.class);
            Map<?, ?> lookupBody = lookupResp.getBody();
            result.put("step3_lookupByEmail_ok", lookupBody != null && Boolean.TRUE.equals(lookupBody.get("ok")));
            result.put("step3_lookupByEmail_error", lookupBody != null ? lookupBody.get("error") : "no response");
            result.put("step3_lookupByEmail_status", lookupResp.getStatusCode().toString());

            if (lookupBody != null && Boolean.TRUE.equals(lookupBody.get("ok"))) {
                Map<?, ?> slackUser = (Map<?, ?>) lookupBody.get("user");
                String slackUserId = slackUser != null ? (String) slackUser.get("id") : null;
                result.put("step3_slackUserId", slackUserId);

                // Step 4: conversations.open
                if (slackUserId != null) {
                    HttpHeaders h2 = new HttpHeaders();
                    h2.setBearerAuth(config.getBotToken());
                    h2.setContentType(MediaType.APPLICATION_JSON);
                    Map<String, Object> body = new HashMap<>();
                    body.put("users", slackUserId);
                    HttpEntity<Map<String, Object>> req2 = new HttpEntity<>(body, h2);
                    ResponseEntity<Map> openResp = restTemplate.exchange(
                            "https://slack.com/api/conversations.open",
                            HttpMethod.POST, req2, Map.class);
                    Map<?, ?> openBody = openResp.getBody();
                    result.put("step4_conversationsOpen_ok", openBody != null && Boolean.TRUE.equals(openBody.get("ok")));
                    result.put("step4_conversationsOpen_error", openBody != null ? openBody.get("error") : "no response");
                    if (openBody != null && Boolean.TRUE.equals(openBody.get("ok"))) {
                        Map<?, ?> channel = (Map<?, ?>) openBody.get("channel");
                        result.put("step4_dmChannelId", channel != null ? channel.get("id") : null);
                        result.put("step4_SUCCESS", "All steps passed — messages should arrive as Slack DM");
                    }
                }
            }
        } catch (Exception e) {
            result.put("step3_exception", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> maskConfig(MessagingIntegrationConfig c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("platform", c.getPlatform());
        m.put("label", c.getLabel());
        m.put("enabled", c.isEnabled());
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        // Mask sensitive values — only show whether they are configured
        m.put("botTokenConfigured", c.getBotToken() != null && !c.getBotToken().isBlank());
        m.put("signingSecretConfigured", c.getSigningSecret() != null && !c.getSigningSecret().isBlank());
        return m;
    }

    private Map<String, Object> connectionToMap(UserPlatformConnection c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("userId", c.getUserId());
        m.put("platform", c.getPlatform());
        m.put("webhookUrl", c.getWebhookUrl());
        m.put("channelId", c.getChannelId());
        m.put("platformUserId", c.getPlatformUserId());
        m.put("active", c.isActive());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        return m;
    }
}
