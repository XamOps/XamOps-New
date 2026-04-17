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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Admin API for Microsoft Teams bot integration.
 *
 * POST /api/billops/teams/invite/{userId}
 *   → Generates a one-time registration code and emails the user instructions
 *     to install the Teams bot app and connect their account.
 *
 * GET /api/billops/teams/app/manifest.zip
 *   → Serves the Teams app package (.zip) containing manifest.json + icons.
 *     User uploads this to Teams (Apps → Manage your apps → Upload a custom app).
 *
 * GET /api/billops/teams/status/{userId}
 *   → Returns whether the user has completed Teams registration.
 */
@RestController
@RequestMapping("/api/billops/teams")
@CrossOrigin(origins = "*")
public class TeamsController {

    private static final Logger log = LoggerFactory.getLogger(TeamsController.class);

    /** In-memory store of pending registration codes. code → PendingRegistration */
    private static final Map<String, PendingRegistration> PENDING = new ConcurrentHashMap<>();
    private static final long CODE_TTL_MS = 48 * 60 * 60 * 1000L; // 48 hours

    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;

    @Value("${messaging.portal.base-url:https://app.xamops.com}")
    private String portalBaseUrl;

    @Value("${server.port:8082}")
    private String serverPort;

    public TeamsController(MessagingIntegrationConfigRepository configRepo,
                           UserPlatformConnectionRepository connectionRepo,
                           AppUserRepository appUserRepository,
                           EmailService emailService) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
    }

    // ── Called by other services to look up a registration code ──────────────

    /** Returns the userId for a valid pending registration code, or null if expired/unknown. */
    public static Long resolveRegistrationCode(String code) {
        if (code == null) return null;
        PendingRegistration reg = PENDING.get(code.trim().toUpperCase());
        if (reg == null) return null;
        if (System.currentTimeMillis() > reg.expiresAt) {
            PENDING.remove(code.trim().toUpperCase());
            return null;
        }
        return reg.userId;
    }

    /** Removes a code after it has been used. */
    public static void consumeRegistrationCode(String code) {
        if (code != null) PENDING.remove(code.trim().toUpperCase());
    }

    // ── Invite ────────────────────────────────────────────────────────────────

    /**
     * Sends the user an email with:
     *  - A download link for the Teams app package
     *  - A one-time registration code to send to the bot
     */
    @PostMapping("/invite/{userId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> sendInvite(@PathVariable Long userId) {
        MessagingIntegrationConfig config = configRepo.findByPlatform("TEAMS").orElse(null);
        if (config == null || config.getClientId() == null || config.getClientId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Teams App ID not configured. Go to Integrations → Teams → save App ID and App Secret."
            ));
        }

        AppUser user = appUserRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.badRequest().body(Map.of("error", "User not found"));

        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "User has no email address"));
        }

        // Generate a 6-character uppercase code
        String code = generateCode();
        PENDING.put(code, new PendingRegistration(userId, System.currentTimeMillis() + CODE_TTL_MS));

        String manifestDownloadUrl = portalBaseUrl + "/api/billops/teams/app/manifest.zip";
        String username = user.getUsername() != null ? user.getUsername() : email;

        try {
            emailService.sendHtmlEmail(email, "Connect with XamOps Support on Microsoft Teams",
                    buildInviteEmail(username, code, manifestDownloadUrl));
            log.info("Teams: invite email sent to userId={} email={} code={}", userId, email, code);
        } catch (Exception e) {
            log.error("Teams: failed to send invite email to {}: {}", email, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send invite email: " + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invited", true);
        result.put("email", email);
        result.put("message", "Invite email sent to " + email + ". Connection activates when user sends the registration code to the bot.");
        return ResponseEntity.ok(result);
    }

    // ── App manifest zip download (PUBLIC — user downloads this) ─────────────

    /**
     * Serves the Teams app package as a downloadable .zip file.
     * The manifest is generated dynamically with the configured App ID.
     * This endpoint is PUBLIC so users can download without authentication.
     */
    @GetMapping("/app/manifest.zip")
    public ResponseEntity<byte[]> downloadManifest() {
        MessagingIntegrationConfig config = configRepo.findByPlatform("TEAMS").orElse(null);
        if (config == null || config.getClientId() == null || config.getClientId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            byte[] zipBytes = buildManifestZip(config.getClientId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"xamops-support-teams.zip\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipBytes);
        } catch (Exception e) {
            log.error("Teams: failed to generate manifest zip: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @GetMapping("/status/{userId}")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable Long userId) {
        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, "TEAMS").orElse(null);
        boolean connected = conn != null && conn.isActive()
                && conn.getChannelId() != null && !conn.getChannelId().isBlank()
                && conn.getWebhookUrl() != null && !conn.getWebhookUrl().isBlank();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", connected);
        result.put("conversationId", conn != null ? conn.getChannelId() : null);
        result.put("updatedAt", conn != null && conn.getUpdatedAt() != null ? conn.getUpdatedAt().toString() : null);
        return ResponseEntity.ok(result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no O/0/I/1 to avoid confusion
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(rng.nextInt(chars.length())));
        return sb.toString();
    }

    private String buildInviteEmail(String username, String code, String manifestUrl) {
        return "<!DOCTYPE html><html><body style='font-family:Arial,sans-serif;color:#333;max-width:600px'>"
            + "<h2 style='color:#6264A7'>Connect with XamOps Support on Microsoft Teams</h2>"
            + "<p>Hi <strong>" + username + "</strong>,</p>"
            + "<p>Your XamOps support admin would like to connect with you on <strong>Microsoft Teams</strong>.</p>"
            + "<h3>Step 1: Install the XamOps Support bot</h3>"
            + "<ol>"
            + "<li>Download the app package: <a href='" + manifestUrl + "'>xamops-support-teams.zip</a></li>"
            + "<li>Open <strong>Microsoft Teams</strong></li>"
            + "<li>Go to <strong>Apps</strong> (left sidebar)</li>"
            + "<li>Click <strong>Manage your apps</strong> → <strong>Upload an app</strong></li>"
            + "<li>Select <strong>Upload a custom app</strong> and choose the downloaded .zip file</li>"
            + "<li>Click <strong>Add</strong> to install</li>"
            + "</ol>"
            + "<h3>Step 2: Complete registration</h3>"
            + "<ol>"
            + "<li>Open the <strong>XamOps Support</strong> bot in Teams (it will appear in your chats)</li>"
            + "<li>Send this registration code to the bot:</li>"
            + "</ol>"
            + "<div style='background:#f4f4f8;border:2px solid #6264A7;border-radius:8px;padding:20px;text-align:center;margin:20px 0'>"
            + "<span style='font-size:32px;font-weight:bold;letter-spacing:6px;color:#6264A7'>" + code + "</span>"
            + "<p style='margin:8px 0 0;color:#888;font-size:12px'>This code expires in 48 hours</p>"
            + "</div>"
            + "<p>Once you send the code, your admin will be able to reach you directly in Teams.</p>"
            + "<p style='color:#888;font-size:12px'>This is an automated message from XamOps.</p>"
            + "</body></html>";
    }

    /**
     * Builds a valid Teams app package zip containing manifest.json + two icon files.
     */
    private byte[] buildManifestZip(String appId) throws Exception {
        String manifest = buildManifestJson(appId);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // manifest.json
            addZipEntry(zos, "manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            // color icon (192x192 — minimal valid PNG, solid #6264A7 purple)
            addZipEntry(zos, "color.png", getColorIconBytes());
            // outline icon (32x32 — minimal valid PNG, white on transparent)
            addZipEntry(zos, "outline.png", getOutlineIconBytes());
        }
        return baos.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private String buildManifestJson(String appId) {
        return "{\n"
            + "  \"$schema\": \"https://developer.microsoft.com/json-schemas/teams/v1.17/MicrosoftTeams.schema.json\",\n"
            + "  \"manifestVersion\": \"1.17\",\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"id\": \"" + appId + "\",\n"
            + "  \"packageName\": \"com.xamops.support\",\n"
            + "  \"developer\": {\n"
            + "    \"name\": \"XamOps\",\n"
            + "    \"websiteUrl\": \"https://xamops.com\",\n"
            + "    \"privacyUrl\": \"https://xamops.com/privacy\",\n"
            + "    \"termsOfUseUrl\": \"https://xamops.com/terms\"\n"
            + "  },\n"
            + "  \"icons\": {\n"
            + "    \"color\": \"color.png\",\n"
            + "    \"outline\": \"outline.png\"\n"
            + "  },\n"
            + "  \"name\": {\n"
            + "    \"short\": \"XamOps Support\",\n"
            + "    \"full\": \"XamOps Cloud Support\"\n"
            + "  },\n"
            + "  \"description\": {\n"
            + "    \"short\": \"XamOps cloud support bot\",\n"
            + "    \"full\": \"Get instant cloud support from your XamOps team via Microsoft Teams.\"\n"
            + "  },\n"
            + "  \"accentColor\": \"#6264A7\",\n"
            + "  \"bots\": [\n"
            + "    {\n"
            + "      \"botId\": \"" + appId + "\",\n"
            + "      \"scopes\": [\"personal\"],\n"
            + "      \"supportsFiles\": false,\n"
            + "      \"isNotificationOnly\": false\n"
            + "    }\n"
            + "  ],\n"
            + "  \"permissions\": [\"identity\", \"messageTeamMembers\"],\n"
            + "  \"validDomains\": []\n"
            + "}";
    }

    /** Generates a 192x192 solid purple PNG for the Teams app color icon. */
    private byte[] getColorIconBytes() throws Exception {
        return generateSolidPng(192, 192, new Color(0x62, 0x64, 0xA7));
    }

    /** Generates a 32x32 solid white PNG for the Teams app outline icon. */
    private byte[] getOutlineIconBytes() throws Exception {
        return generateSolidPng(32, 32, Color.WHITE);
    }

    private byte[] generateSolidPng(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static class PendingRegistration {
        final long userId;
        final long expiresAt;
        PendingRegistration(long userId, long expiresAt) {
            this.userId = userId;
            this.expiresAt = expiresAt;
        }
    }
}
