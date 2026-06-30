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
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Slack distributed-app OAuth 2.0 flow.
 *
 * Flow:
 *   1. Admin clicks "Send Slack Connect Link" for a user in the dashboard.
 *   2. GET /api/billops/slack/oauth/install/{userId}
 *      → Generates a unique state token, builds the Slack OAuth URL, emails it to the user.
 *   3. User clicks the link in their email → Slack shows the "Add to Slack" permission screen.
 *   4. User clicks "Allow" → Slack redirects to:
 *      GET /api/billops/slack/oauth/callback?code=...&state=...
 *   5. Backend exchanges the code for a bot token, stores it in UserPlatformConnection,
 *      opens a DM channel, and returns a success HTML page.
 *
 * Slack App setup required (api.slack.com):
 *   - Distribution → Enable distribution (so users in other workspaces can install)
 *   - OAuth & Permissions → Redirect URLs → add: {base-url}/api/billops/slack/oauth/callback
 *   - Bot Token Scopes: chat:write, im:write, im:history, users:read, users:read.email
 *   - Event Subscriptions → Request URL: {base-url}/api/billops/webhook/slack
 *   - Event Subscriptions → Bot Events: message.im
 *
 * Admin configures Client ID + Client Secret in Integration Settings once.
 */
@RestController
@RequestMapping("/api/billops/slack/oauth")
@CrossOrigin(origins = "*")
public class SlackOAuthController {

    private static final Logger log = LoggerFactory.getLogger(SlackOAuthController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Pending OAuth states: state-token → userId. Expires after 30 minutes (checked on use). */
    private final Map<String, PendingState> pendingStates = new ConcurrentHashMap<>();

    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${messaging.portal.base-url:https://app.xamops.com}")
    private String portalBaseUrl;

    public SlackOAuthController(MessagingIntegrationConfigRepository configRepo,
                                UserPlatformConnectionRepository connectionRepo,
                                AppUserRepository appUserRepository,
                                EmailService emailService) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
    }

    // ── Step 1: Admin triggers the install link ───────────────────────────────

    /**
     * Called by admin from the dashboard for a specific user.
     * Generates the Slack OAuth URL and emails it to the user.
     *
     * Requires: ROLE_BILLOPS_ADMIN or ROLE_BILLOPS_ADMIN_SUB
     */
    @PostMapping("/install/{userId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> sendInstallLink(@PathVariable Long userId) {
        // Look up user
        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User has no email address"));
        }

        // Check Slack app configuration
        MessagingIntegrationConfig config = configRepo.findByPlatform("SLACK").orElse(null);
        if (config == null || config.getClientId() == null || config.getClientId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Slack App Client ID not configured. Go to Integrations → Slack → enter Client ID and Client Secret."
            ));
        }

        // Generate a secure random state token
        String stateToken = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(stateToken, new PendingState(userId, System.currentTimeMillis()));

        // Clean up expired states (older than 30 min)
        pendingStates.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().createdAt > 30 * 60 * 1000L);

        // Build the Slack OAuth URL
        String redirectUri = portalBaseUrl + "/api/billops/slack/oauth/callback";
        String scopes = "chat:write,im:write,im:history,users:read,users:read.email";
        String installUrl = "https://slack.com/oauth/v2/authorize"
                + "?client_id=" + config.getClientId()
                + "&scope=" + scopes
                + "&redirect_uri=" + encodeUrl(redirectUri)
                + "&state=" + stateToken;

        // Send email to user
        String displayName = user.getUsername() != null ? user.getUsername() : email;
        String htmlBody = buildInstallEmailHtml(displayName, installUrl);
        try {
            emailService.sendHtmlEmail(email, "Connect Your Slack to XamOps Support", htmlBody);
            log.info("Sent Slack install link to userId={} email={}", userId, email);
        } catch (Exception e) {
            log.error("Failed to send Slack install email to {}: {}", email, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send email: " + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        result.put("email", maskEmail(email));
        result.put("installUrl", installUrl); // also return so admin can share manually if needed
        return ResponseEntity.ok(result);
    }

    // ── User-initiated Slack connection (self-service from Add Account page) ──

    /**
     * Called by the logged-in user directly to initiate Slack OAuth.
     * Returns the Slack OAuth URL — frontend redirects the user to it.
     * No admin role required — any authenticated user can connect their own Slack.
     *
     * GET /api/billops/slack/oauth/connect
     */
    @GetMapping("/connect")
    public ResponseEntity<Map<String, Object>> userInitiateConnect(java.security.Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // Find the logged-in user
        AppUser user = appUserRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }

        // Check Slack app configuration (admin must have configured Client ID once)
        MessagingIntegrationConfig config = configRepo.findByPlatform("SLACK").orElse(null);
        if (config == null || config.getClientId() == null || config.getClientId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Slack integration not available. Contact your admin to configure Slack App credentials."
            ));
        }

        // Check if already connected
        UserPlatformConnection existing = connectionRepo.findByUserIdAndPlatform(user.getId(), "SLACK").orElse(null);
        if (existing != null && existing.isActive() && existing.getUserBotToken() != null) {
            return ResponseEntity.ok(Map.of(
                "alreadyConnected", true,
                "workspaceId", existing.getWorkspaceId() != null ? existing.getWorkspaceId() : "",
                "message", "Slack is already connected."
            ));
        }

        // Generate state token
        String stateToken = UUID.randomUUID().toString().replace("-", "");
        pendingStates.put(stateToken, new PendingState(user.getId(), System.currentTimeMillis()));
        pendingStates.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().createdAt > 30 * 60 * 1000L);

        // Build Slack OAuth URL
        String redirectUri = portalBaseUrl + "/api/billops/slack/oauth/callback";
        String scopes = "app_mentions:read,assistant:write,channels:history,channels:join,channels:read,chat:write,files:read,groups:history,groups:read,im:history,im:read,im:write,mpim:history,mpim:read,users:read,users:read.email";
        String installUrl = "https://slack.com/oauth/v2/authorize"
                + "?client_id=" + config.getClientId()
                + "&scope=" + scopes
                + "&redirect_uri=" + encodeUrl(redirectUri)
                + "&state=" + stateToken;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installUrl", installUrl);
        result.put("alreadyConnected", false);
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the current user's Slack connection status.
     * GET /api/billops/slack/oauth/my-status
     */
    @GetMapping("/my-status")
    public ResponseEntity<Map<String, Object>> getMyConnectionStatus(java.security.Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        AppUser user = appUserRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.ok(Map.of("connected", false));
        }
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(user.getId(), "SLACK").orElse(null);
        boolean connected = conn != null && conn.isActive() && conn.getUserBotToken() != null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", connected);
        result.put("workspaceId", conn != null ? conn.getWorkspaceId() : null);
        result.put("updatedAt", conn != null && conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
        return ResponseEntity.ok(result);
    }

    /**
     * Disconnect the current user's Slack connection.
     * DELETE /api/billops/slack/oauth/disconnect
     */
    @DeleteMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> userDisconnect(java.security.Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        AppUser user = appUserRepository.findByUsername(principal.getName()).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(user.getId(), "SLACK").orElse(null);
        if (conn != null) {
            conn.setActive(false);
            conn.setUpdatedAt(LocalDateTime.now(IST));
            connectionRepo.save(conn);
        }
        return ResponseEntity.ok(Map.of("disconnected", true));
    }

    // ── Step 2: Slack redirects here after user approves ─────────────────────

    /**
     * Public OAuth callback — no authentication required.
     * Slack redirects here with ?code=...&state=... after user installs the app.
     */
    @GetMapping("/callback")
    public ResponseEntity<String> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {

        if (error != null) {
            log.warn("Slack OAuth error: {}", error);
            return ResponseEntity.ok(buildHtmlPage("Slack Connection Cancelled",
                    "You cancelled the Slack connection. You can try again from your XamOps portal.",
                    false));
        }

        if (code == null || state == null) {
            return ResponseEntity.ok(buildHtmlPage("Invalid Request",
                    "Missing code or state parameter. Please try again.", false));
        }

        // Validate state
        PendingState pending = pendingStates.remove(state);
        if (pending == null) {
            return ResponseEntity.ok(buildHtmlPage("Link Expired",
                    "This install link has expired or already been used. Please ask your admin to send a new link.",
                    false));
        }
        if (System.currentTimeMillis() - pending.createdAt > 30 * 60 * 1000L) {
            return ResponseEntity.ok(buildHtmlPage("Link Expired",
                    "This install link has expired (30 minute limit). Please ask your admin to send a new link.",
                    false));
        }

        Long userId = pending.userId;

        // Get Slack app config
        MessagingIntegrationConfig config = configRepo.findByPlatform("SLACK").orElse(null);
        if (config == null || config.getClientId() == null || config.getClientSecret() == null) {
            return ResponseEntity.ok(buildHtmlPage("Configuration Error",
                    "Slack app not properly configured. Please contact your admin.", false));
        }

        // Exchange code for token
        try {
            String redirectUri = portalBaseUrl + "/api/billops/slack/oauth/callback";
            Map<String, Object> tokenResponse = exchangeCodeForToken(
                    config.getClientId(), config.getClientSecret(), code, redirectUri);

            if (tokenResponse == null || !Boolean.TRUE.equals(tokenResponse.get("ok"))) {
                String slackError = tokenResponse != null ? String.valueOf(tokenResponse.get("error")) : "null response";
                log.error("Slack oauth.v2.access failed for userId={}: {}", userId, slackError);
                return ResponseEntity.ok(buildHtmlPage("Connection Failed",
                        "Could not complete Slack connection: " + slackError + ". Please try again.", false));
            }

            // Extract tokens and IDs
            String botToken = (String) tokenResponse.get("access_token");

            @SuppressWarnings("unchecked")
            Map<String, Object> authedUser = (Map<String, Object>) tokenResponse.get("authed_user");
            String slackUserId = authedUser != null ? (String) authedUser.get("id") : null;

            @SuppressWarnings("unchecked")
            Map<String, Object> team = (Map<String, Object>) tokenResponse.get("team");
            String workspaceId = team != null ? (String) team.get("id") : null;

            if (botToken == null || slackUserId == null) {
                return ResponseEntity.ok(buildHtmlPage("Connection Failed",
                        "Incomplete response from Slack. Please try again.", false));
            }

            // Open DM channel with the user using the new bot token
            String channelId = openSlackDM(botToken, slackUserId);

            // Save / update the connection
            UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, "SLACK")
                    .orElse(new UserPlatformConnection());

            if (conn.getId() == null) {
                conn.setUserId(userId);
                conn.setPlatform("SLACK");
                conn.setCreatedAt(LocalDateTime.now(IST));
            }
            conn.setUserBotToken(botToken);
            conn.setPlatformUserId(slackUserId);
            conn.setWorkspaceId(workspaceId);
            if (channelId != null) conn.setChannelId(channelId);
            conn.setActive(true);
            conn.setUpdatedAt(LocalDateTime.now(IST));
            connectionRepo.save(conn);

            log.info("Slack OAuth complete for userId={} workspace={} channelId={}", userId, workspaceId, channelId);

            AppUser user = appUserRepository.findById(userId).orElse(null);
            String displayName = user != null && user.getUsername() != null ? user.getUsername() : "User";

            return ResponseEntity.ok(buildHtmlPage("Slack Connected!",
                    "Your Slack is now connected to XamOps Support, " + displayName + ". "
                    + "You'll receive support messages directly in your Slack DMs. "
                    + "You can close this tab.",
                    true));

        } catch (Exception e) {
            log.error("Slack OAuth callback error for userId={}: {}", userId, e.getMessage());
            return ResponseEntity.ok(buildHtmlPage("Error",
                    "An error occurred: " + e.getMessage() + ". Please try again.", false));
        }
    }

    // ── Status endpoint ───────────────────────────────────────────────────────

    /**
     * Returns whether a user has completed the Slack OAuth install.
     * GET /api/billops/slack/oauth/status/{userId}
     */
    @GetMapping("/status/{userId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> getConnectionStatus(@PathVariable Long userId) {
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, "SLACK").orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        boolean connected = conn != null && conn.isActive() && conn.getUserBotToken() != null;
        result.put("connected", connected);
        result.put("workspaceId", conn != null ? conn.getWorkspaceId() : null);
        result.put("channelId", conn != null ? conn.getChannelId() : null);
        result.put("platformUserId", conn != null ? conn.getPlatformUserId() : null);
        result.put("updatedAt", conn != null && conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Exchange the OAuth code for a bot token via Slack's oauth.v2.access API. */
    private Map<String, Object> exchangeCodeForToken(String clientId, String clientSecret, String code, String redirectUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        // Slack requires Basic auth with client_id:client_secret
        String credentials = clientId + ":" + clientSecret;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
        headers.set("Authorization", "Basic " + encoded);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://slack.com/api/oauth.v2.access", HttpMethod.POST, request,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        return response.getBody();
    }

    /** Open a Slack DM channel with the authed user using the new bot token. */
    private String openSlackDM(String botToken, String slackUserId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(botToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("users", slackUserId);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://slack.com/api/conversations.open", HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (response.getBody() != null && Boolean.TRUE.equals(response.getBody().get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> channel = (Map<String, Object>) response.getBody().get("channel");
                return channel != null ? (String) channel.get("id") : null;
            }
            log.warn("conversations.open failed: {}", response.getBody() != null ? response.getBody().get("error") : "null");
        } catch (Exception e) {
            log.error("Error opening DM for slackUserId={}: {}", slackUserId, e.getMessage());
        }
        return null;
    }

    private String encodeUrl(String url) {
        try {
            return java.net.URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        String[] parts = email.split("@");
        String local = parts[0];
        String shown = local.length() > 2 ? local.substring(0, 2) + "***" : "***";
        return shown + "@" + parts[1];
    }

    private String buildInstallEmailHtml(String displayName, String installUrl) {
        return "<!DOCTYPE html><html><body style='margin:0;padding:0;background:#f4f6fb;font-family:Arial,sans-serif;'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' style='background:#f4f6fb;padding:32px 0;'>"
            + "<tr><td align='center'>"
            + "<table width='560' cellpadding='0' cellspacing='0' style='background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 2px 12px rgba(0,0,0,0.08);'>"
            + "<tr><td style='background:linear-gradient(135deg,#4A154B,#7c3aed);padding:28px 32px;'>"
            + "<h1 style='margin:0;color:#fff;font-size:20px;font-weight:700;'>Connect Slack to XamOps Support</h1>"
            + "<p style='margin:4px 0 0;color:rgba(255,255,255,0.75);font-size:13px;'>One-click setup — takes 30 seconds</p>"
            + "</td></tr>"
            + "<tr><td style='padding:28px 32px;'>"
            + "<p style='margin:0 0 16px;font-size:15px;color:#1f2937;'>Hi <strong>" + displayName + "</strong>,</p>"
            + "<p style='margin:0 0 16px;font-size:14px;color:#4b5563;line-height:1.6;'>Your XamOps support team wants to send you messages directly in Slack. Click the button below to connect your Slack account — you only need to do this once.</p>"
            + "<a href='" + installUrl + "' style='display:inline-block;margin:8px 0 20px;background:#4A154B;color:#fff;text-decoration:none;font-size:14px;font-weight:600;padding:14px 32px;border-radius:8px;'>"
            + "<span style='margin-right:8px;'>⚡</span>Add to Slack</a>"
            + "<p style='margin:0;font-size:12px;color:#9ca3af;'>This link expires in 30 minutes. If it's expired, ask your admin to resend it.</p>"
            + "</td></tr>"
            + "<tr><td style='padding:16px 32px 24px;border-top:1px solid #f3f4f6;'>"
            + "<p style='margin:0;font-size:11px;color:#9ca3af;'>XamOps Support · You're receiving this because your support admin wants to connect with you on Slack.</p>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String buildHtmlPage(String title, String message, boolean success) {
        String color = success ? "#16a34a" : "#dc2626";
        String icon = success ? "✅" : "❌";
        return "<!DOCTYPE html><html><head><title>" + title + "</title>"
            + "<style>body{margin:0;padding:0;background:#f4f6fb;font-family:Arial,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;}"
            + ".card{background:#fff;border-radius:16px;padding:48px 40px;max-width:480px;text-align:center;box-shadow:0 4px 24px rgba(0,0,0,0.1);}"
            + "h1{font-size:24px;color:" + color + ";margin:0 0 16px;}"
            + "p{font-size:15px;color:#4b5563;line-height:1.6;margin:0;}"
            + ".icon{font-size:48px;margin-bottom:16px;}"
            + "</style></head><body>"
            + "<div class='card'><div class='icon'>" + icon + "</div>"
            + "<h1>" + title + "</h1>"
            + "<p>" + message + "</p>"
            + "</div></body></html>";
    }

    /** Stores a pending OAuth state with its creation timestamp. */
    private static class PendingState {
        final Long userId;
        final long createdAt;
        PendingState(Long userId, long createdAt) {
            this.userId = userId;
            this.createdAt = createdAt;
        }
    }
}
