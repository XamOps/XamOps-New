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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API for setting up Google Chat spaces per user via Domain-Wide Delegation.
 *
 * POST /api/billops/gchat/setup/{userId}
 *   → Creates a Google Chat Space (impersonating admin via DWD), invites the user by email.
 *   → Google automatically sends an invitation email to the user.
 *   → Stores the space name in UserPlatformConnection so admin can message the user.
 *
 * GET /api/billops/gchat/status/{userId}
 *   → Returns whether a Google Chat Space has been set up for this user.
 *
 * Requirements (one-time admin setup):
 *   1. Enable Google Chat API in Google Cloud Console.
 *   2. Create a Service Account and download the JSON key.
 *   3. Enable Domain-Wide Delegation on the service account.
 *   4. In Google Workspace Admin Console → Security → API Controls → Domain-wide delegation:
 *      Add the service account client ID with scopes:
 *        https://www.googleapis.com/auth/chat.spaces
 *        https://www.googleapis.com/auth/chat.memberships
 *        https://www.googleapis.com/auth/chat.bot
 *   5. In XamOps Integrations → Google Chat: paste service account JSON + admin email.
 *   6. Enable external spaces in Google Workspace Admin → Apps → Google Chat → Settings.
 */
@RestController
@RequestMapping("/api/billops/gchat")
@CrossOrigin(origins = "*")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
public class GoogleChatController {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;
    private final AppUserRepository appUserRepository;
    private final GoogleChatService googleChatService;

    public GoogleChatController(MessagingIntegrationConfigRepository configRepo,
                                UserPlatformConnectionRepository connectionRepo,
                                AppUserRepository appUserRepository,
                                GoogleChatService googleChatService) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
        this.appUserRepository = appUserRepository;
        this.googleChatService = googleChatService;
    }

    /**
     * Creates a Google Chat Space for the user using DWD and invites them by email.
     * Google sends the invitation email automatically — no custom email needed.
     */
    @PostMapping("/setup/{userId}")
    public ResponseEntity<Map<String, Object>> setupGoogleChat(@PathVariable Long userId) {
        MessagingIntegrationConfig config = configRepo.findByPlatform("GOOGLE_CHAT").orElse(null);
        if (config == null || config.getServiceAccountJson() == null || config.getServiceAccountJson().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Google Chat service account not configured. Go to Integrations → Google Chat → paste service account JSON."
            ));
        }
        if (config.getAdminEmail() == null || config.getAdminEmail().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Admin email not configured. Go to Integrations → Google Chat → enter your Workspace admin email."
            ));
        }

        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        String userEmail = user.getEmail();
        if (userEmail == null || userEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User has no email address"));
        }

        // If already connected, return existing space info
        UserPlatformConnection existing = connectionRepo.findByUserIdAndPlatform(userId, "GOOGLE_CHAT").orElse(null);
        if (existing != null && existing.isActive() && existing.getChannelId() != null
                && existing.getChannelId().startsWith("spaces/")) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("spaceName", existing.getChannelId());
            result.put("message", "Already connected via space: " + existing.getChannelId());
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
                conn.setUserId(userId);
                conn.setPlatform("GOOGLE_CHAT");
                conn.setCreatedAt(LocalDateTime.now(IST));
            }
            conn.setChannelId(spaceName);
            conn.setPlatformUserId(userEmail);
            conn.setActive(true);
            conn.setUpdatedAt(LocalDateTime.now(IST));
            connectionRepo.save(conn);

            log.info("GChat: space {} created and user {} invited for userId={}", spaceName, userEmail, userId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("spaceName", spaceName);
            result.put("message", "Google Chat space created and invitation sent to " + userEmail + ". The user will receive an invite from Google.");
            return ResponseEntity.ok(result);

        } catch (GoogleChatService.OrgBlocksExternalException e) {
            log.warn("GChat: org blocks external for userId={} email={}", userId, userEmail);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", false);
            result.put("errorCode", "USER_ORG_BLOCKS_EXTERNAL");
            result.put("message", e.getUserEmail() + "'s organization has blocked external Google Chat messaging.");
            result.put("suggestion", "Ask their admin to enable external messaging in Google Workspace Admin Console, or use email instead.");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("GChat: setup failed for userId={} email={}: {}", userId, userEmail, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                "connected", false,
                "errorCode", "INTERNAL_ERROR",
                "error", "Failed to create Google Chat space: " + e.getMessage()
            ));
        }
    }

    /**
     * Debug endpoint — tests DWD token + sends a test message to the user's space.
     * GET /api/billops/gchat/debug/{userId}
     */
    @GetMapping("/debug/{userId}")
    public ResponseEntity<Map<String, Object>> debug(@PathVariable Long userId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        MessagingIntegrationConfig config = configRepo.findByPlatform("GOOGLE_CHAT").orElse(null);
        result.put("configFound", config != null);
        result.put("serviceAccountConfigured", config != null && config.getServiceAccountJson() != null && !config.getServiceAccountJson().isBlank());
        result.put("adminEmail", config != null ? config.getAdminEmail() : null);

        if (config == null || config.getServiceAccountJson() == null) return ResponseEntity.ok(result);

        // Test DWD token
        try {
            String dwdToken = googleChatService.getAccessTokenDWD(config.getServiceAccountJson(), config.getAdminEmail());
            result.put("dwdTokenObtained", dwdToken != null);
        } catch (Exception e) {
            result.put("dwdTokenError", e.getMessage());
        }

        // Check connection
        com.xammer.billops.domain.UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, "GOOGLE_CHAT").orElse(null);
        result.put("connectionFound", conn != null);
        result.put("spaceName", conn != null ? conn.getChannelId() : null);
        result.put("userEmail", conn != null ? conn.getPlatformUserId() : null);

        // Try sending a test message
        if (conn != null && conn.getChannelId() != null) {
            try {
                String msgResult = googleChatService.sendMessage(
                        config.getServiceAccountJson(), config.getAdminEmail(),
                        conn.getChannelId(), "Test message from XamOps debug endpoint", "XamOps Debug");
                result.put("sendResult", msgResult != null ? msgResult : "null — send failed, check logs");
            } catch (Exception e) {
                result.put("sendError", e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns whether a Google Chat Space has been set up for a user.
     */
    @GetMapping("/status/{userId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable Long userId) {
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, "GOOGLE_CHAT").orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        boolean connected = conn != null && conn.isActive() && conn.getChannelId() != null
                && conn.getChannelId().startsWith("spaces/");
        result.put("connected", connected);
        result.put("spaceName", conn != null ? conn.getChannelId() : null);
        result.put("email", conn != null ? conn.getPlatformUserId() : null);
        result.put("updatedAt", conn != null && conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
        return ResponseEntity.ok(result);
    }
}
