package com.xammer.billops.controller;

import com.xammer.billops.domain.MessagingIntegrationConfig;
import com.xammer.billops.domain.UserPlatformConnection;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import com.xammer.billops.service.EmailService;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API for setting up Google Chat DM spaces per user.
 *
 * POST /api/billops/gchat/setup/{userId}
 *   → Sends the user an email inviting them to message the XamOps bot in Google Chat.
 *   → When the user sends a message, the webhook auto-registers the space ID.
 *
 * GET /api/billops/gchat/status/{userId}
 *   → Returns whether a Google Chat DM has been set up for this user.
 *
 * NOTE: Google Chat's spaces.setup API requires user-level OAuth scopes that are
 * not available to service accounts without Domain-Wide Delegation. The correct
 * flow is therefore user-initiated: the user messages the bot first, and the
 * webhook (POST /api/billops/webhook/gchat) auto-registers the connection.
 */
@RestController
@RequestMapping("/api/billops/gchat")
@CrossOrigin(origins = "*")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
public class GoogleChatController {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatController.class);

    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;

    @Value("${messaging.gchat.bot-name:XamOps Support}")
    private String botName;

    public GoogleChatController(MessagingIntegrationConfigRepository configRepo,
                                UserPlatformConnectionRepository connectionRepo,
                                AppUserRepository appUserRepository,
                                EmailService emailService) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
    }

    /**
     * Sends the user an email with instructions to message the bot in Google Chat.
     * The connection is established automatically when the user sends their first message.
     */
    @PostMapping("/setup/{userId}")
    public ResponseEntity<Map<String, Object>> sendInvite(@PathVariable Long userId) {
        // Check config exists
        MessagingIntegrationConfig config = configRepo.findByPlatform("GOOGLE_CHAT").orElse(null);
        if (config == null || config.getServiceAccountJson() == null || config.getServiceAccountJson().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Google Chat service account not configured. Go to Integrations → Google Chat → paste service account JSON."
            ));
        }

        // Check user
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User has no email address"));
        }

        // Send invite email
        String subject = "Connect with XamOps Support on Google Chat";
        String html = buildInviteEmail(user.getUsername() != null ? user.getUsername() : email, botName);
        try {
            emailService.sendHtmlEmail(email, subject, html);
            log.info("GChat: invite email sent to userId={} email={}", userId, email);
        } catch (Exception e) {
            log.error("GChat: failed to send invite email to {}: {}", email, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send invite email: " + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invited", true);
        result.put("email", email);
        result.put("message", "Invite email sent to " + email + ". Connection will activate when the user messages the bot.");
        return ResponseEntity.ok(result);
    }

    /**
     * Returns whether a Google Chat DM has been set up for a user.
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

    private String buildInviteEmail(String username, String bot) {
        return "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;color:#333'>"
            + "<h2>Connect with XamOps Support on Google Chat</h2>"
            + "<p>Hi <strong>" + username + "</strong>,</p>"
            + "<p>Your XamOps support admin would like to connect with you on <strong>Google Chat</strong>.</p>"
            + "<p>To activate the connection, please follow these steps:</p>"
            + "<ol>"
            + "<li>Open <a href='https://chat.google.com'>Google Chat</a></li>"
            + "<li>Click <strong>New chat</strong> or use the search bar</li>"
            + "<li>Search for <strong>" + bot + "</strong> (look under Apps)</li>"
            + "<li>Send any message (e.g. <em>Hi</em>) to the bot</li>"
            + "</ol>"
            + "<p>Once you send a message, your admin will be able to reach you directly in Google Chat.</p>"
            + "<p style='color:#888;font-size:12px'>This is an automated message from XamOps.</p>"
            + "</body></html>";
    }
}
