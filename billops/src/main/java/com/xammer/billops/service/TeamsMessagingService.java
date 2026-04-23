package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends messages to Microsoft Teams users via the Bot Framework REST API.
 *
 * Cross-org flow:
 *   1. Admin registers a bot in Azure (App ID + App Secret) — one-time setup.
 *   2. User installs the bot app package in their Teams (any org, any workspace).
 *   3. User messages the bot with a registration code → webhook stores serviceUrl + conversationId.
 *   4. Admin sends a message → this service POSTs to {serviceUrl}/v3/conversations/{conversationId}/activities.
 *
 * Token endpoint:
 *   POST https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token
 *   scope = https://api.botframework.com/.default
 *
 * Send endpoint:
 *   POST {serviceUrl}/v3/conversations/{conversationId}/activities
 *   Authorization: Bearer {token}
 */
@Service
public class TeamsMessagingService {

    private static final Logger log = LoggerFactory.getLogger(TeamsMessagingService.class);
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token";
    private static final String BOT_SCOPE = "https://api.botframework.com/.default";

    private final RestTemplate restTemplate = new RestTemplate();

    /** Cache: appId → {token, expiresAt} */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Send a message to a Teams user via Bot Framework REST API.
     *
     * @param serviceUrl     The Bot Framework service URL from the original activity (region-specific).
     * @param conversationId The Teams conversation ID from the original activity.
     * @param appId          Azure Bot App ID (Microsoft App ID).
     * @param appSecret      Azure Bot App Secret (Bot Framework password).
     * @param message        Plain text message from admin.
     * @param adminName      Admin's display name.
     * @return activity ID on success, null on failure.
     */
    public String sendMessage(String serviceUrl, String conversationId,
                              String appId, String appSecret,
                              String message, String adminName) {
        try {
            String token = getAccessToken(appId, appSecret);
            if (token == null) {
                log.error("Teams: failed to obtain access token for appId={}", appId);
                return null;
            }

            // Normalise serviceUrl — must end with /
            if (!serviceUrl.endsWith("/")) serviceUrl = serviceUrl + "/";

            String url = serviceUrl + "v3/conversations/" + conversationId + "/activities";

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> body = buildMessagePayload(message, adminName);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful()) {
                String activityId = response.getBody() != null ? (String) response.getBody().get("id") : "sent";
                log.info("Teams: message sent to conversationId={} activityId={}", conversationId, activityId);
                return activityId != null ? activityId : "sent";
            }
            log.error("Teams: sendMessage returned {}", response.getStatusCode());

        } catch (HttpClientErrorException e) {
            log.error("Teams: sendMessage client error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            log.error("Teams: sendMessage server error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Teams: sendMessage failed conversationId={}: {}", conversationId, e.getMessage(), e);
        }
        return null;
    }

    // ── Token (client credentials) ─────────────────────────────────────────────

    /**
     * Gets a Bot Framework access token via client_credentials grant.
     * Tokens are cached per appId and refreshed 5 minutes before expiry.
     */
    public String getAccessToken(String appId, String appSecret) {
        CachedToken cached = tokenCache.get(appId);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            return cached.token;
        }
        tokenCache.remove(appId);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("client_id", appId);
            body.add("client_secret", appSecret);
            body.add("scope", BOT_SCOPE);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String token = (String) response.getBody().get("access_token");
                Number expiresIn = (Number) response.getBody().get("expires_in");
                long expiresAt = System.currentTimeMillis()
                        + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                tokenCache.put(appId, new CachedToken(token, expiresAt));
                log.info("Teams: obtained access token for appId={}", appId);
                return token;
            }
            log.error("Teams: token exchange returned {}: {}", response.getStatusCode(), response.getBody());

        } catch (HttpClientErrorException e) {
            log.error("Teams: token exchange client error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            log.error("Teams: token exchange server error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Teams: getAccessToken failed: {}", e.getMessage(), e);
        }
        return null;
    }

    // ── Message payload (Adaptive Card) ───────────────────────────────────────

    private Map<String, Object> buildMessagePayload(String message, String adminName) {
        // Adaptive Card v1.4 — renders richly in Teams
        Map<String, Object> adaptiveCard = new LinkedHashMap<>();
        adaptiveCard.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        adaptiveCard.put("type", "AdaptiveCard");
        adaptiveCard.put("version", "1.4");

        List<Map<String, Object>> cardBody = new ArrayList<>();

        Map<String, Object> title = new LinkedHashMap<>();
        title.put("type", "TextBlock");
        title.put("text", "XamOps Support Message");
        title.put("weight", "Bolder");
        title.put("size", "Medium");
        title.put("color", "Accent");
        cardBody.add(title);

        Map<String, Object> from = new LinkedHashMap<>();
        from.put("type", "TextBlock");
        from.put("text", "**From:** " + adminName);
        from.put("isSubtle", true);
        from.put("spacing", "None");
        cardBody.add(from);

        Map<String, Object> msgBlock = new LinkedHashMap<>();
        msgBlock.put("type", "TextBlock");
        msgBlock.put("text", message);
        msgBlock.put("wrap", true);
        msgBlock.put("spacing", "Medium");
        cardBody.add(msgBlock);

        adaptiveCard.put("body", cardBody);

        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("contentUrl", null);
        attachment.put("content", adaptiveCard);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "message");
        payload.put("text", adminName + ": " + message); // fallback plain text
        payload.put("attachments", List.of(attachment));
        return payload;
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private static class CachedToken {
        final String token;
        final long expiresAt;
        CachedToken(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }
}
