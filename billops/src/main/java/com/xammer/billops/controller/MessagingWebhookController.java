package com.xammer.billops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.domain.UserPlatformConnection;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Receives inbound messages (user replies) from external platforms and saves them
 * as SupportMessages so they appear in the admin support dashboard.
 *
 * Endpoints:
 *   POST /api/billops/webhook/slack   — Slack Events API (including URL verification challenge)
 *   POST /api/billops/webhook/teams   — Microsoft Teams Bot Framework activity
 *   POST /api/billops/webhook/gchat   — Google Chat app event
 *
 * Setup instructions for each platform:
 *
 * SLACK:
 *   1. In your Slack App settings → "Event Subscriptions" → enable events
 *   2. Set Request URL to: https://yourdomain.com/api/billops/webhook/slack
 *   3. Subscribe to bot events: message.channels, message.im
 *   4. Save the Signing Secret in Integration Settings (used to verify requests)
 *
 * TEAMS:
 *   1. Create an Outgoing Webhook in your Teams channel
 *   2. Set the callback URL to: https://yourdomain.com/api/billops/webhook/teams
 *
 * GOOGLE CHAT:
 *   1. In Google Cloud Console → Google Chat API → Configuration
 *   2. Set App URL to: https://yourdomain.com/api/billops/webhook/gchat
 */
@RestController
@RequestMapping("/api/billops/webhook")
@CrossOrigin(origins = "*")
public class MessagingWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MessagingWebhookController.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SupportMessageRepository messageRepository;
    private final UserPlatformConnectionRepository connectionRepo;
    private final MessagingIntegrationConfigRepository configRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MessagingWebhookController(SupportMessageRepository messageRepository,
                                      UserPlatformConnectionRepository connectionRepo,
                                      MessagingIntegrationConfigRepository configRepo) {
        this.messageRepository = messageRepository;
        this.connectionRepo = connectionRepo;
        this.configRepo = configRepo;
    }

    // ── SLACK ─────────────────────────────────────────────────────────────────

    /**
     * Handles Slack Events API payloads.
     * - Responds to URL verification challenge automatically.
     * - Saves user replies as SupportMessages.
     */
    @PostMapping("/slack")
    public ResponseEntity<Map<String, Object>> handleSlackEvent(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Slack-Signature", required = false) String slackSig,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp) {

        // Verify signature if signing secret is configured
        if (slackSig != null && timestamp != null) {
            String signingSecret = configRepo.findByPlatform("SLACK")
                    .map(c -> c.getSigningSecret()).orElse(null);
            if (signingSecret != null && !verifySlackSignature(signingSecret, timestamp, rawBody, slackSig)) {
                log.warn("Slack signature verification failed");
                return ResponseEntity.status(401).body(null);
            }
        }

        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            String type = payload.path("type").asText();

            // 1. URL verification challenge (Slack sends this when you first configure the endpoint)
            if ("url_verification".equals(type)) {
                String challenge = payload.path("challenge").asText();
                Map<String, Object> response = new HashMap<>();
                response.put("challenge", challenge);
                return ResponseEntity.ok(response);
            }

            // 2. Event callback
            if ("event_callback".equals(type)) {
                JsonNode event = payload.path("event");
                String eventType = event.path("type").asText();

                // Only handle regular messages (skip bot messages and subtypes like edits/deletes)
                if ("message".equals(eventType) && !event.has("subtype")) {
                    String channelId = event.path("channel").asText();
                    String slackUserId = event.path("user").asText();
                    String text = event.path("text").asText();
                    String ts = event.path("ts").asText();

                    if (text == null || text.isBlank()) {
                        return ResponseEntity.ok(Map.of("ok", true));
                    }

                    // Find the XamOps user by Slack channel ID
                    UserPlatformConnection conn = resolveConnection(channelId, slackUserId, "SLACK");
                    if (conn == null) {
                        log.warn("Slack: no user connection found for channel={} user={}", channelId, slackUserId);
                        return ResponseEntity.ok(Map.of("ok", true));
                    }

                    saveInboundMessage(conn.getUserId(), text, "SLACK", ts);
                }
            }
        } catch (Exception e) {
            log.error("Error processing Slack event: {}", e.getMessage());
        }

        Map<String, Object> ok = new HashMap<>();
        ok.put("ok", true);
        return ResponseEntity.ok(ok);
    }

    // ── MICROSOFT TEAMS ───────────────────────────────────────────────────────

    /**
     * Handles Teams Outgoing Webhook or Bot Framework activity payloads.
     */
    @PostMapping("/teams")
    public ResponseEntity<Map<String, Object>> handleTeamsEvent(@RequestBody String rawBody) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            String activityType = payload.path("type").asText();

            if ("message".equals(activityType)) {
                String text = payload.path("text").asText("").trim();
                // Teams wraps the bot mention in <at>...</at> — strip it
                text = text.replaceAll("<at>[^<]*</at>", "").trim();

                if (text.isBlank()) {
                    return ResponseEntity.ok(Map.of("type", "message", "text", ""));
                }

                // Channel ID for correlation
                String channelId = payload.path("channelData").path("channel").path("id").asText(null);
                if (channelId == null) {
                    channelId = payload.path("conversation").path("id").asText(null);
                }
                String teamsUserId = payload.path("from").path("id").asText(null);
                String activityId = payload.path("id").asText(null);

                UserPlatformConnection conn = resolveConnection(channelId, teamsUserId, "TEAMS");
                if (conn == null) {
                    log.warn("Teams: no user connection found for channel={} user={}", channelId, teamsUserId);
                    return ResponseEntity.ok(Map.of("type", "message", "text", "Message received."));
                }

                saveInboundMessage(conn.getUserId(), text, "TEAMS", activityId);
            }
        } catch (Exception e) {
            log.error("Error processing Teams event: {}", e.getMessage());
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("type", "message");
        resp.put("text", "");
        return ResponseEntity.ok(resp);
    }

    // ── GOOGLE CHAT ───────────────────────────────────────────────────────────

    /**
     * Handles Google Chat app events (MESSAGE type).
     */
    @PostMapping("/gchat")
    public ResponseEntity<Map<String, Object>> handleGChatEvent(@RequestBody String rawBody) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);
            String eventType = payload.path("type").asText();

            if ("MESSAGE".equals(eventType)) {
                String text = payload.path("message").path("text").asText("").trim();
                String spaceName = payload.path("message").path("space").path("name").asText(null);
                String senderEmail = payload.path("message").path("sender").path("email").asText(null);
                String messageName = payload.path("message").path("name").asText(null);

                if (text.isBlank()) {
                    return ResponseEntity.ok(Map.of("text", ""));
                }

                UserPlatformConnection conn = resolveConnection(spaceName, senderEmail, "GOOGLE_CHAT");
                if (conn == null) {
                    log.warn("GChat: no user connection found for space={} sender={}", spaceName, senderEmail);
                    return ResponseEntity.ok(Map.of("text", "Message received. Please reply via XamOps portal."));
                }

                saveInboundMessage(conn.getUserId(), text, "GOOGLE_CHAT", messageName);
            }
        } catch (Exception e) {
            log.error("Error processing Google Chat event: {}", e.getMessage());
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("text", "");
        return ResponseEntity.ok(resp);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Try to resolve UserPlatformConnection by channelId first, then by platformUserId.
     */
    private UserPlatformConnection resolveConnection(String channelId, String platformUserId, String platform) {
        if (channelId != null && !channelId.isBlank()) {
            Optional<UserPlatformConnection> byChannel =
                    connectionRepo.findByChannelIdAndPlatform(channelId, platform);
            if (byChannel.isPresent()) return byChannel.get();
        }
        if (platformUserId != null && !platformUserId.isBlank()) {
            return connectionRepo.findByPlatformUserIdAndPlatform(platformUserId, platform).orElse(null);
        }
        return null;
    }

    private void saveInboundMessage(Long userId, String text, String platform, String externalId) {
        // Fetch most recent message to carry over accountId / accountName / cloudProvider
        SupportMessage last = messageRepository.findFirstByUserIdOrderByTimestampDesc(userId);

        SupportMessage msg = new SupportMessage();
        msg.setUserId(userId);
        msg.setContent(text);
        msg.setUserMessage(true);
        msg.setStatus("UNREAD");
        msg.setTimestamp(LocalDateTime.now(IST));
        msg.setSenderUsername(platform.toLowerCase() + "_user");
        msg.setExternalPlatform(platform);
        msg.setExternalMessageId(externalId);

        if (last != null) {
            msg.setAccountId(last.getAccountId());
            msg.setAccountName(last.getAccountName());
            msg.setCloudProvider(last.getCloudProvider());
        }

        messageRepository.save(msg);
        log.info("Saved inbound {} message for userId={}", platform, userId);
    }

    /**
     * Verifies Slack's HMAC-SHA256 request signature.
     * See: https://api.slack.com/authentication/verifying-requests-from-slack
     */
    private boolean verifySlackSignature(String signingSecret, String timestamp, String body, String slackSig) {
        try {
            String baseString = "v0:" + timestamp + ":" + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("v0=");
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString().equals(slackSig);
        } catch (Exception e) {
            log.error("Slack signature verification error: {}", e.getMessage());
            return false;
        }
    }
}
