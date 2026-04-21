package com.xammer.billops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.domain.UserPlatformConnection;
import com.xammer.billops.repository.MessagingIntegrationConfigRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.billops.repository.UserPlatformConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Polls each active Google Chat space every 30 seconds for new messages.
 * Saves inbound user replies as SupportMessages so they appear in the admin dashboard.
 *
 * This is needed because Google Chat HTTP webhooks in SPACE type only fire
 * for @mentions — not for all messages. Polling is the reliable alternative.
 */
@Component
public class GoogleChatPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatPollingScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CHAT_BASE = "https://chat.googleapis.com/v1";
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(java.time.ZoneOffset.UTC);

    private final UserPlatformConnectionRepository connectionRepo;
    private final SupportMessageRepository messageRepository;
    private final MessagingIntegrationConfigRepository configRepo;
    private final GoogleChatService googleChatService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GoogleChatPollingScheduler(UserPlatformConnectionRepository connectionRepo,
            SupportMessageRepository messageRepository,
            MessagingIntegrationConfigRepository configRepo,
            GoogleChatService googleChatService) {
        this.connectionRepo = connectionRepo;
        this.messageRepository = messageRepository;
        this.configRepo = configRepo;
        this.googleChatService = googleChatService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void pollGoogleChatMessages() {
        var config = configRepo.findByPlatform("GOOGLE_CHAT").orElse(null);
        if (config == null || config.getServiceAccountJson() == null || config.getAdminEmail() == null) {
            return;
        }

        List<UserPlatformConnection> connections =
                connectionRepo.findAllByPlatformAndActive("GOOGLE_CHAT", true);
        if (connections.isEmpty()) return;

        String dwdToken = googleChatService.getAccessTokenDWDMessages(
                config.getServiceAccountJson(), config.getAdminEmail());
        if (dwdToken == null) {
            log.warn("GChat poll: failed to obtain DWD messages token, skipping poll");
            return;
        }

        for (UserPlatformConnection conn : connections) {
            try {
                pollSpace(conn, dwdToken);
            } catch (Exception e) {
                log.warn("GChat poll: error polling space {} for userId={}: {}",
                        conn.getChannelId(), conn.getUserId(), e.getMessage());
            }
        }
    }

    private void pollSpace(UserPlatformConnection conn, String dwdToken) {
        String spaceName = conn.getChannelId();
        if (spaceName == null || !spaceName.startsWith("spaces/")) return;

        // Poll from 2 minutes ago to catch any missed messages (overlapping window with dedup)
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(2);
        String filter = "createTime>\"" + RFC3339.format(since.atZone(java.time.ZoneOffset.UTC)) + "\"";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dwdToken);

        try {
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                    .fromHttpUrl(CHAT_BASE + "/" + spaceName + "/messages")
                    .queryParam("filter", filter)
                    .build().encode().toUri();

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return;

            Object messagesObj = response.getBody().get("messages");
            if (!(messagesObj instanceof List)) return;

            List<?> messages = (List<?>) messagesObj;
            for (Object msgObj : messages) {
                try {
                    JsonNode msg = objectMapper.valueToTree(msgObj);
                    processMessage(conn, msg);
                } catch (Exception e) {
                    log.warn("GChat poll: error processing message: {}", e.getMessage());
                }
            }

        } catch (HttpClientErrorException e) {
            log.warn("GChat poll: API error for space {} {}: {}", spaceName,
                    e.getStatusCode(), e.getResponseBodyAsString());
        }
    }

    private void processMessage(UserPlatformConnection conn, JsonNode msg) {
        // Skip bot messages
        String senderType = msg.path("sender").path("type").asText("");
        if ("BOT".equals(senderType)) return;

        String text = msg.path("text").asText("").trim();
        if (text.isBlank()) return;

        // Deduplicate using message name as external ID
        String messageName = msg.path("name").asText(null);
        if (messageName != null && messageRepository.existsByExternalMessageId(messageName)) return;

        SupportMessage saved = new SupportMessage();
        saved.setUserId(conn.getUserId());
        saved.setContent(text);
        saved.setUserMessage(true);
        saved.setStatus("UNREAD");
        saved.setTimestamp(LocalDateTime.now(IST));
        saved.setSenderUsername("gchat_user");
        saved.setExternalPlatform("GOOGLE_CHAT");
        saved.setExternalMessageId(messageName);

        // Carry over account context from last message
        SupportMessage last = messageRepository.findFirstByUserIdOrderByTimestampDesc(conn.getUserId());
        if (last != null) {
            saved.setAccountId(last.getAccountId());
            saved.setAccountName(last.getAccountName());
            saved.setCloudProvider(last.getCloudProvider());
        }

        messageRepository.save(saved);
        log.info("GChat poll: saved inbound message for userId={} space={}", conn.getUserId(), conn.getChannelId());
    }
}
