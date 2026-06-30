package com.xammer.billops.controller;

import com.xammer.billops.domain.MessagingIntegrationConfig;
import com.xammer.billops.domain.UserPlatformConnection;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import com.xammer.billops.service.GoogleChatService;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-facing integration endpoints (no admin role required).
 * Allows authenticated users to self-connect messaging platforms.
 */
@RestController
@RequestMapping("/api/billops/user/integrations")
@CrossOrigin(origins = "*")
public class UserIntegrationController {

    private static final Logger log = LoggerFactory.getLogger(UserIntegrationController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;
    private final AppUserRepository appUserRepository;
    private final GoogleChatService googleChatService;

    public UserIntegrationController(
            MessagingIntegrationConfigRepository configRepo,
            UserPlatformConnectionRepository connectionRepo,
            AppUserRepository appUserRepository,
            GoogleChatService googleChatService) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
        this.appUserRepository = appUserRepository;
        this.googleChatService = googleChatService;
    }

    // ── Google Chat: User self-connect ────────────────────────────────────────

    /**
     * User-initiated Google Chat connection.
     * Creates a Space, invites the user by their registered email, and adds the bot.
     * Google sends an invitation to the user automatically.
     *
     * POST /api/billops/user/integrations/gchat/connect
     */
    @PostMapping("/gchat/connect")
    public ResponseEntity<Map<String, Object>> connectGoogleChat(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        AppUser user = appUserRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }

        String userEmail = user.getEmail();
        if (userEmail == null || userEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Your account has no email address configured. Please update your profile."));
        }

        // Check Google Chat service account config (admin sets this up once)
        MessagingIntegrationConfig config = configRepo.findByPlatform("GOOGLE_CHAT").orElse(null);
        if (config == null || config.getServiceAccountJson() == null || config.getServiceAccountJson().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Google Chat integration is not available. Contact your admin to configure it."
            ));
        }
        if (config.getAdminEmail() == null || config.getAdminEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Google Chat admin email not configured. Contact your admin."
            ));
        }

        // Check if already connected
        UserPlatformConnection existing = connectionRepo.findByUserIdAndPlatform(user.getId(), "GOOGLE_CHAT").orElse(null);
        if (existing != null && existing.isActive() && existing.getChannelId() != null
                && existing.getChannelId().startsWith("spaces/")) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("alreadyConnected", true);
            result.put("spaceName", existing.getChannelId());
            result.put("message", "Google Chat is already connected.");
            return ResponseEntity.ok(result);
        }

        String username = user.getUsername() != null ? user.getUsername() : userEmail;
        String displayName = "XamOps Support — " + username;

        try {
            String spaceName = googleChatService.createSpaceAndInvite(
                    config.getServiceAccountJson(),
                    config.getAdminEmail(),
                    userEmail,
                    displayName);

            // Save the connection
            UserPlatformConnection conn = existing != null ? existing : new UserPlatformConnection();
            if (conn.getId() == null) {
                conn.setUserId(user.getId());
                conn.setPlatform("GOOGLE_CHAT");
                conn.setCreatedAt(LocalDateTime.now(IST));
            }
            conn.setChannelId(spaceName);
            conn.setPlatformUserId(userEmail);
            conn.setActive(true);
            conn.setUpdatedAt(LocalDateTime.now(IST));
            connectionRepo.save(conn);

            log.info("GChat user self-connect: space {} created for userId={} email={}", spaceName, user.getId(), userEmail);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("alreadyConnected", false);
            result.put("spaceName", spaceName);
            result.put("message", "Google Chat space created! Check your email or Google Chat for the invitation.");
            return ResponseEntity.ok(result);

        } catch (GoogleChatService.OrgBlocksExternalException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", false);
            result.put("errorCode", "USER_ORG_BLOCKS_EXTERNAL");
            result.put("error", "Your organization has blocked external Google Chat messaging. Ask your org admin to enable it, or use Slack instead.");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("GChat user self-connect failed for userId={}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "connected", false,
                "error", "Failed to create Google Chat space: " + e.getMessage()
            ));
        }
    }

    /**
     * Returns the current user's Google Chat connection status.
     * GET /api/billops/user/integrations/gchat/status
     */
    @GetMapping("/gchat/status")
    public ResponseEntity<Map<String, Object>> getGChatStatus(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        AppUser user = appUserRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.ok(Map.of("connected", false));
        }
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(user.getId(), "GOOGLE_CHAT").orElse(null);
        boolean connected = conn != null && conn.isActive() && conn.getChannelId() != null
                && conn.getChannelId().startsWith("spaces/");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", connected);
        result.put("spaceName", conn != null ? conn.getChannelId() : null);
        result.put("updatedAt", conn != null && conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
        return ResponseEntity.ok(result);
    }

    /**
     * Disconnect the current user's Google Chat connection.
     * DELETE /api/billops/user/integrations/gchat/disconnect
     */
    @DeleteMapping("/gchat/disconnect")
    public ResponseEntity<Map<String, Object>> disconnectGChat(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        AppUser user = appUserRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(user.getId(), "GOOGLE_CHAT").orElse(null);
        if (conn != null) {
            conn.setActive(false);
            conn.setUpdatedAt(LocalDateTime.now(IST));
            connectionRepo.save(conn);
        }
        return ResponseEntity.ok(Map.of("disconnected", true));
    }
}
