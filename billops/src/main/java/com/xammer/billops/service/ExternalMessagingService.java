package com.xammer.billops.service;

import com.xammer.billops.domain.MessagingIntegrationConfig;
import com.xammer.billops.domain.UserPlatformConnection;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Routes outbound support messages to external messaging platforms.
 *
 * Supported platforms:
 *   SLACK       → Incoming Webhook URL (JSON POST)  OR  Bot Token + chat.postMessage
 *   TEAMS       → Incoming Webhook URL (Adaptive Card JSON POST)
 *   GOOGLE_CHAT → Incoming Webhook URL (JSON POST)
 *
 * Cross-org flow:
 *   - Admin configures (once):  Bot Token for Slack in Integration Settings
 *   - Per user:                 Admin pastes the user's Incoming Webhook URL in the user connection panel
 *   - On reply:                 This service POSTs the message to that URL
 *   - Inbound replies:          MessagingWebhookController handles events from each platform
 */
@Service
public class ExternalMessagingService {

    private static final Logger log = LoggerFactory.getLogger(ExternalMessagingService.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final MessagingIntegrationConfigRepository configRepo;
    private final UserPlatformConnectionRepository connectionRepo;

    @Value("${messaging.portal.base-url:https://app.xamops.com}")
    private String portalBaseUrl;

    public ExternalMessagingService(MessagingIntegrationConfigRepository configRepo,
                                    UserPlatformConnectionRepository connectionRepo) {
        this.configRepo = configRepo;
        this.connectionRepo = connectionRepo;
    }

    /**
     * Send a support message to the user's configured platform.
     *
     * @param userId    XamOps user ID
     * @param platform  SLACK | TEAMS | GOOGLE_CHAT
     * @param message   Message text from admin
     * @param adminName Name of the admin sending the message
     * @param userEmail User's registered email (used for Slack auto-discovery)
     * @return external message ID or null if failed
     */
    public String sendMessage(Long userId, String platform, String message, String adminName, String userEmail) {
        if (platform == null || platform.isBlank()) return null;

        UserPlatformConnection conn = connectionRepo.findByUserIdAndPlatform(userId, platform).orElse(null);

        // For Slack: auto-create a bare connection record so we can cache discovered IDs
        if (conn == null && "SLACK".equalsIgnoreCase(platform)) {
            conn = new UserPlatformConnection();
            conn.setUserId(userId);
            conn.setPlatform("SLACK");
            conn.setActive(true);
            conn.setCreatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            conn.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            conn = connectionRepo.save(conn);
        }

        if (conn == null || !conn.isActive()) {
            log.warn("No active {} connection for userId={}", platform, userId);
            return null;
        }

        try {
            switch (platform.toUpperCase()) {
                case "SLACK":       return sendToSlack(conn, message, adminName, userEmail);
                case "TEAMS":       return sendToTeams(conn, message, adminName);
                case "GOOGLE_CHAT": return sendToGoogleChat(conn, message, adminName);
                default:
                    log.warn("Unknown platform: {}", platform);
                    return null;
            }
        } catch (Exception e) {
            log.error("Failed to send message via {} for userId={}: {}", platform, userId, e.getMessage());
            return null;
        }
    }

    /** Backwards-compatible overload (no email). */
    public String sendMessage(Long userId, String platform, String message, String adminName) {
        return sendMessage(userId, platform, message, adminName, null);
    }

    // ── SLACK ─────────────────────────────────────────────────────────────────

    private String sendToSlack(UserPlatformConnection conn, String message, String adminName, String userEmail) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // ── Priority 1: Incoming Webhook URL (cross-org, no bot token needed) ──
        if (conn.getWebhookUrl() != null && !conn.getWebhookUrl().isBlank()) {
            Map<String, Object> body = buildSlackWebhookPayload(message, adminName);
            HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    conn.getWebhookUrl(), HttpMethod.POST, req,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (resp.getStatusCode().is2xxSuccessful()) return "sent";
            log.error("Slack webhook returned {}", resp.getStatusCode());
            return null;
        }

        // ── Priority 2 & 3 both need bot token ──
        MessagingIntegrationConfig config = configRepo.findByPlatform("SLACK").orElse(null);
        if (config == null || !config.isEnabled() || config.getBotToken() == null) {
            log.warn("Slack bot token not configured");
            return null;
        }
        headers.setBearerAuth(config.getBotToken());

        String channelId = conn.getChannelId();

        // ── Priority 2: Auto-discover DM channel via user email ──
        if ((channelId == null || channelId.isBlank()) && userEmail != null && !userEmail.isBlank()) {
            log.info("Auto-discovering Slack user by email: {}", userEmail);
            String slackUserId = lookupSlackUserByEmail(config.getBotToken(), userEmail);
            if (slackUserId != null) {
                channelId = openSlackDM(config.getBotToken(), slackUserId);
                if (channelId != null) {
                    // Cache discovered IDs so next send is instant
                    conn.setChannelId(channelId);
                    conn.setPlatformUserId(slackUserId);
                    conn.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
                    connectionRepo.save(conn);
                    log.info("Cached Slack channelId={} for userId={}", channelId, conn.getUserId());
                }
            }
        }

        if (channelId == null || channelId.isBlank()) {
            log.warn("No Slack channel available for userId={} (no webhook, no channelId, email lookup failed)", conn.getUserId());
            return null;
        }

        // ── Send via bot token + channel ──
        Map<String, Object> body = buildSlackWebhookPayload(message, adminName);
        body.put("channel", channelId);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://slack.com/api/chat.postMessage", HttpMethod.POST, request,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

        if (response.getStatusCode().is2xxSuccessful()) {
            Map<String, Object> resp = response.getBody();
            if (resp != null && Boolean.TRUE.equals(resp.get("ok"))) {
                return resp.get("ts") != null ? resp.get("ts").toString() : "sent";
            }
            if (resp != null) log.error("Slack chat.postMessage error: {}", resp.get("error"));
        }
        log.error("Slack API returned {}", response.getStatusCode());
        return null;
    }

    /** Look up a Slack user by their email address. Requires users:read.email scope. */
    private String lookupSlackUserByEmail(String botToken, String email) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(botToken);
            HttpEntity<?> request = new HttpEntity<>(headers);
            String url = "https://slack.com/api/users.lookupByEmail?email=" + email;
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (Boolean.TRUE.equals(body.get("ok"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> user = (Map<String, Object>) body.get("user");
                    if (user != null) {
                        String id = (String) user.get("id");
                        log.info("Found Slack user {} for email {}", id, email);
                        return id;
                    }
                } else {
                    log.warn("Slack users.lookupByEmail failed for {}: {}", email, body.get("error"));
                }
            }
        } catch (Exception e) {
            log.error("Error looking up Slack user by email {}: {}", email, e.getMessage());
        }
        return null;
    }

    /** Open a Slack DM channel with a user. Requires im:write scope. */
    private String openSlackDM(String botToken, String slackUserId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(botToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("users", slackUserId);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://slack.com/api/conversations.open", HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respBody = response.getBody();
                if (Boolean.TRUE.equals(respBody.get("ok"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> channel = (Map<String, Object>) respBody.get("channel");
                    if (channel != null) {
                        String id = (String) channel.get("id");
                        log.info("Opened Slack DM channel {} for user {}", id, slackUserId);
                        return id;
                    }
                } else {
                    log.warn("Slack conversations.open failed for {}: {}", slackUserId, respBody.get("error"));
                }
            }
        } catch (Exception e) {
            log.error("Error opening Slack DM for user {}: {}", slackUserId, e.getMessage());
        }
        return null;
    }

    private Map<String, Object> buildSlackWebhookPayload(String message, String adminName) {
        String portalLink = portalBaseUrl + "/billops/support";

        // Blocks API — rich formatting
        List<Map<String, Object>> blocks = new ArrayList<>();

        // Header block
        Map<String, Object> header = new HashMap<>();
        header.put("type", "header");
        Map<String, Object> headerText = new HashMap<>();
        headerText.put("type", "plain_text");
        headerText.put("text", "XamOps Support Message");
        header.put("text", headerText);
        blocks.add(header);

        // Divider
        blocks.add(Map.of("type", "divider"));

        // Message body
        Map<String, Object> section = new HashMap<>();
        section.put("type", "section");
        Map<String, Object> sectionText = new HashMap<>();
        sectionText.put("type", "mrkdwn");
        sectionText.put("text", "*From:* " + escapeSlack(adminName) + "\n\n" + escapeSlack(message));
        section.put("text", sectionText);
        blocks.add(section);

        // Reply button
        Map<String, Object> actions = new HashMap<>();
        actions.put("type", "actions");
        Map<String, Object> button = new HashMap<>();
        button.put("type", "button");
        button.put("text", Map.of("type", "plain_text", "text", "Reply in XamOps Portal"));
        button.put("url", portalLink);
        button.put("style", "primary");
        actions.put("elements", List.of(button));
        blocks.add(actions);

        // Context footer
        Map<String, Object> context = new HashMap<>();
        context.put("type", "context");
        Map<String, Object> ctxText = new HashMap<>();
        ctxText.put("type", "mrkdwn");
        ctxText.put("text", "Reply directly in this thread or visit the XamOps support portal.");
        context.put("elements", List.of(ctxText));
        blocks.add(context);

        Map<String, Object> payload = new HashMap<>();
        payload.put("text", "New message from XamOps Support: " + message); // fallback text
        payload.put("blocks", blocks);
        return payload;
    }

    // ── MICROSOFT TEAMS ───────────────────────────────────────────────────────

    private String sendToTeams(UserPlatformConnection conn, String message, String adminName) {
        if (conn.getWebhookUrl() == null || conn.getWebhookUrl().isBlank()) {
            log.warn("No Teams webhook URL for userId={}", conn.getUserId());
            return null;
        }

        String portalLink = portalBaseUrl + "/billops/support";

        // Adaptive Card payload for Teams Incoming Webhook
        Map<String, Object> adaptiveCard = new LinkedHashMap<>();
        adaptiveCard.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        adaptiveCard.put("type", "AdaptiveCard");
        adaptiveCard.put("version", "1.4");

        List<Map<String, Object>> cardBody = new ArrayList<>();

        // Title
        Map<String, Object> title = new LinkedHashMap<>();
        title.put("type", "TextBlock");
        title.put("text", "XamOps Support Message");
        title.put("weight", "Bolder");
        title.put("size", "Medium");
        title.put("color", "Accent");
        cardBody.add(title);

        // From
        Map<String, Object> fromBlock = new LinkedHashMap<>();
        fromBlock.put("type", "TextBlock");
        fromBlock.put("text", "**From:** " + adminName);
        fromBlock.put("isSubtle", true);
        fromBlock.put("spacing", "None");
        cardBody.add(fromBlock);

        // Separator
        Map<String, Object> sep = new LinkedHashMap<>();
        sep.put("type", "TextBlock");
        sep.put("text", "");
        sep.put("spacing", "Small");
        cardBody.add(sep);

        // Message
        Map<String, Object> msgBlock = new LinkedHashMap<>();
        msgBlock.put("type", "TextBlock");
        msgBlock.put("text", message);
        msgBlock.put("wrap", true);
        cardBody.add(msgBlock);

        adaptiveCard.put("body", cardBody);

        // Action button
        Map<String, Object> openUrlAction = new LinkedHashMap<>();
        openUrlAction.put("type", "Action.OpenUrl");
        openUrlAction.put("title", "Reply in XamOps Portal");
        openUrlAction.put("url", portalLink);
        adaptiveCard.put("actions", List.of(openUrlAction));

        // Wrap in the Teams message envelope
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("contentUrl", null);
        attachment.put("content", adaptiveCard);

        Map<String, Object> teamsPayload = new LinkedHashMap<>();
        teamsPayload.put("type", "message");
        teamsPayload.put("attachments", List.of(attachment));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(teamsPayload, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(conn.getWebhookUrl(), request, String.class);
        if (response.getStatusCode().is2xxSuccessful()) return "sent";
        log.error("Teams webhook returned {}", response.getStatusCode());
        return null;
    }

    // ── GOOGLE CHAT ───────────────────────────────────────────────────────────

    private String sendToGoogleChat(UserPlatformConnection conn, String message, String adminName) {
        if (conn.getWebhookUrl() == null || conn.getWebhookUrl().isBlank()) {
            log.warn("No Google Chat webhook URL for userId={}", conn.getUserId());
            return null;
        }

        String portalLink = portalBaseUrl + "/billops/support";

        // Google Chat Card v2 format
        Map<String, Object> cardHeader = new LinkedHashMap<>();
        cardHeader.put("title", "XamOps Support Message");
        cardHeader.put("subtitle", "From: " + adminName);
        cardHeader.put("imageUrl", "https://app.xamops.com/logo-square.png");
        cardHeader.put("imageType", "CIRCLE");

        Map<String, Object> textWidget = new LinkedHashMap<>();
        Map<String, Object> textParagraph = new LinkedHashMap<>();
        textParagraph.put("text", message);
        textWidget.put("textParagraph", textParagraph);

        Map<String, Object> buttonWidget = new LinkedHashMap<>();
        Map<String, Object> buttonList = new LinkedHashMap<>();
        Map<String, Object> btn = new LinkedHashMap<>();
        btn.put("text", "Reply in XamOps Portal");
        Map<String, Object> onClickAction = new LinkedHashMap<>();
        Map<String, Object> openLink = new LinkedHashMap<>();
        openLink.put("url", portalLink);
        onClickAction.put("openLink", openLink);
        btn.put("onClick", onClickAction);
        buttonList.put("buttons", List.of(btn));
        buttonWidget.put("buttonList", buttonList);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("widgets", List.of(textWidget, buttonWidget));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", cardHeader);
        card.put("sections", List.of(section));

        Map<String, Object> cardV2 = new LinkedHashMap<>();
        cardV2.put("cardId", "support-msg-" + System.currentTimeMillis());
        cardV2.put("card", card);

        Map<String, Object> gchatPayload = new LinkedHashMap<>();
        gchatPayload.put("cardsV2", List.of(cardV2));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(gchatPayload, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                conn.getWebhookUrl(), HttpMethod.POST, request,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        if (response.getStatusCode().is2xxSuccessful()) {
            Map<String, Object> resp = response.getBody();
            if (resp != null && resp.get("name") != null) return resp.get("name").toString();
            return "sent";
        }
        log.error("Google Chat webhook returned {}", response.getStatusCode());
        return null;
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private String escapeSlack(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
