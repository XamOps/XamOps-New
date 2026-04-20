package com.xammer.billops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends messages to users via the Google Chat REST API using a service account.
 *
 * Flow:
 *   1. Admin pastes Service Account JSON key in Integration Settings (one-time).
 *   2. Admin clicks "Setup Google Chat" for a user → backend calls spaces:setup
 *      to create a DM space and stores the space name in UserPlatformConnection.channelId.
 *   3. When admin sends a message → backend calls spaces.messages.create.
 *   4. User replies in Google Chat → Google POSTs to /api/billops/webhook/gchat.
 *
 * Google Cloud setup (one-time by admin):
 *   1. Create a Google Cloud Project.
 *   2. Enable the Google Chat API.
 *   3. Create a Chat App (bot) under Google Chat API → Configuration.
 *      - Set App URL (webhook) to: {your-domain}/api/billops/webhook/gchat
 *      - Enable "Receive 1:1 messages" and "Join spaces and group conversations"
 *   4. Create a Service Account under IAM & Admin → Service Accounts.
 *   5. Create and download a JSON key for the service account.
 *   6. Paste the JSON key in Integration Settings → Google Chat.
 *
 * Required service account scopes (set in Cloud Console or via domain delegation):
 *   https://www.googleapis.com/auth/chat.bot
 */
@Service
public class GoogleChatService {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatService.class);
    private static final String CHAT_SCOPE = "https://www.googleapis.com/auth/chat.bot";
    private static final String TOKEN_URL  = "https://oauth2.googleapis.com/token";
    private static final String CHAT_BASE  = "https://chat.googleapis.com/v1";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Simple in-memory token cache: serviceAccountEmail → {token, expiresAt} */
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Creates a DM space between the Workspace admin (via DWD) and userEmail.
     * spaces:setup requires user auth — the impersonated admin becomes the space owner.
     * Messages are subsequently sent by the admin (also via DWD) using chat.messages.create scope.
     * Returns the space name (e.g. "spaces/ABCDEF") or throws with a descriptive message.
     */
    public String createSpaceAndInvite(String serviceAccountJson, String adminEmail,
                                       String userEmail, String displayName) throws Exception {
        String dwdToken = getAccessTokenDWD(serviceAccountJson, adminEmail);
        if (dwdToken == null) {
            throw new Exception("Failed to obtain DWD access token. Ensure Domain-Wide Delegation is enabled for the service account.");
        }
        String spaceName = setupDirectMessageSpace(dwdToken, userEmail);
        log.info("GChat: DM space {} created for {} (admin={})", spaceName, userEmail, adminEmail);
        // Attempt to add bot to the space so inbound messages trigger the webhook.
        // This only works for SPACE/GROUP spaces, not DIRECT_MESSAGE — logged as warn if it fails.
        try {
            addBotToSpace(serviceAccountJson, spaceName);
            log.info("GChat: bot added to space {}", spaceName);
        } catch (Exception e) {
            log.warn("GChat: bot could not join space {} (DM spaces don't support bots — inbound via webhook won't work): {}", spaceName, e.getMessage());
        }
        return spaceName;
    }

    /**
     * Get (or create) a DM space between the bot and a user identified by email.
     * Uses spaces:setup which atomically creates the space and adds the human member.
     * Returns the space name (e.g. "spaces/ABCDEF") or throws with a descriptive message.
     */
    public String getOrCreateDMSpace(String serviceAccountJson, String userEmail) throws Exception {
        String accessToken = getAccessToken(serviceAccountJson);
        if (accessToken == null) {
            throw new Exception("Failed to obtain Google access token. Check service account JSON and that Chat API is enabled.");
        }

        return setupDirectMessageSpace(accessToken, userEmail);
    }

    /**
     * Send a formatted message to a Google Chat space.
     *
     * @param serviceAccountJson  Service account JSON key content
     * @param spaceName           e.g. "spaces/ABCDEF"
     * @param message             Plain text message from admin
     * @param adminName           Admin's display name
     * @return message resource name or null on failure
     */
    public String sendMessage(String serviceAccountJson, String spaceName, String message, String adminName) {
        return sendMessage(serviceAccountJson, null, spaceName, message, adminName);
    }

    public String sendMessage(String serviceAccountJson, String adminEmail, String spaceName, String message, String adminName) {
        try {
            // Use DWD token (admin sends as themselves into the DM they own).
            // Falls back to bot token if adminEmail is not provided.
            String accessToken = (adminEmail != null && !adminEmail.isBlank())
                    ? getAccessTokenDWDMessages(serviceAccountJson, adminEmail)
                    : getAccessToken(serviceAccountJson);
            if (accessToken == null) return null;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // DWD (user auth) only supports plain text — Cards require app/bot auth
            boolean usingDwd = adminEmail != null && !adminEmail.isBlank();
            Map<String, Object> body = usingDwd
                    ? Map.of("text", message)
                    : buildMessagePayload(message, adminName);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    CHAT_BASE + "/" + spaceName + "/messages",
                    HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String msgName = (String) response.getBody().get("name");
                log.info("GChat: message sent to {} → {}", spaceName, msgName);
                return msgName != null ? msgName : "sent";
            }
            log.error("GChat: spaces.messages.create returned {}", response.getStatusCode());
        } catch (HttpClientErrorException e) {
            log.error("GChat: sendMessage client error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            log.error("GChat: sendMessage server error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GChat: sendMessage failed for space {}: {}", spaceName, e.getMessage(), e);
        }
        return null;
    }

    // ── Internal: Google Chat API calls ───────────────────────────────────────

    /**
     * Uses spaces:setup to atomically create a DM space between the bot and the user.
     * This is the correct single-call approach for bot-to-human DMs.
     */
    private String setupDirectMessageSpace(String accessToken, String userEmail) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Build membership for the human user
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", "users/" + userEmail);
        member.put("type", "HUMAN");

        Map<String, Object> membership = new LinkedHashMap<>();
        membership.put("member", member);

        Map<String, Object> space = new LinkedHashMap<>();
        space.put("spaceType", "DIRECT_MESSAGE");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("space", space);
        body.put("memberships", List.of(membership));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    CHAT_BASE + "/spaces:setup",
                    HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String spaceName = (String) response.getBody().get("name");
                log.info("GChat: DM space {} ready for {}", spaceName, userEmail);
                return spaceName;
            }
            String msg = "spaces:setup returned " + response.getStatusCode();
            log.error("GChat: {}", msg);
            throw new Exception(msg);

        } catch (HttpClientErrorException e) {
            String body2 = e.getResponseBodyAsString();
            log.error("GChat: spaces:setup DM client error {} body={}", e.getStatusCode(), body2);
            throw new Exception("Google Chat API error " + e.getStatusCode() + ": " + body2);
        } catch (HttpServerErrorException e) {
            String body2 = e.getResponseBodyAsString();
            log.error("GChat: spaces:setup server error {} body={}", e.getStatusCode(), body2);
            throw new Exception("Google Chat API server error " + e.getStatusCode() + ": " + body2);
        }
    }

    /**
     * Bot adds itself to the space using its own app token with chat.memberships.app scope.
     * Only works for SPACE/GROUP spaces — DIRECT_MESSAGE spaces do not support bots.
     */
    private void addBotToSpace(String serviceAccountJson, String spaceName) throws Exception {
        JsonNode sa = objectMapper.readTree(serviceAccountJson);
        String clientEmail = sa.path("client_email").asText();
        String privateKeyPem = sa.path("private_key").asText();

        String cacheKey = clientEmail + "|SCOPE|chat.memberships.app";
        CachedToken cached = tokenCache.get(cacheKey);
        String appToken = (cached != null && System.currentTimeMillis() < cached.expiresAt) ? cached.token : null;
        if (appToken == null) {
            tokenCache.remove(cacheKey);
            String jwt = buildJwt(clientEmail, privateKeyPem, "https://www.googleapis.com/auth/chat.memberships.app");
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.util.MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", jwt);
            ResponseEntity<Map<String, Object>> tr = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, new HttpEntity<>(form, h),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (!tr.getStatusCode().is2xxSuccessful() || tr.getBody() == null)
                throw new Exception("Failed to obtain chat.memberships.app token");
            appToken = (String) tr.getBody().get("access_token");
            Number exp = (Number) tr.getBody().get("expires_in");
            tokenCache.put(cacheKey, new CachedToken(appToken, System.currentTimeMillis() + ((exp != null ? exp.longValue() : 3600L) - 300L) * 1000L));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(appToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("member", Map.of("type", "BOT"));
        try {
            restTemplate.exchange(CHAT_BASE + "/" + spaceName + "/members",
                    HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (HttpClientErrorException e) {
            throw new Exception("addBotToSpace " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    // ── Internal: Access token (JWT → token exchange) ─────────────────────────

    /**
     * Gets a valid access token, using a cache to avoid re-issuing JWTs every call.
     * Tokens are cached per service account email and refreshed 5 minutes before expiry.
     */
    public String getAccessToken(String serviceAccountJson) {
        try {
            JsonNode sa = objectMapper.readTree(serviceAccountJson);
            String clientEmail = sa.path("client_email").asText();
            String privateKeyPem = sa.path("private_key").asText();

            if (clientEmail == null || clientEmail.isBlank()) {
                log.error("GChat: service account JSON missing client_email");
                return null;
            }
            if (privateKeyPem == null || privateKeyPem.isBlank()) {
                log.error("GChat: service account JSON missing private_key");
                return null;
            }

            // Check cache first
            CachedToken cached = tokenCache.get(clientEmail);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
                return cached.token;
            }

            // Invalidate expired cache entry
            tokenCache.remove(clientEmail);

            // Build and sign JWT
            String jwt = buildJwt(clientEmail, privateKeyPem, CHAT_SCOPE);

            // Exchange JWT for access token
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("assertion", jwt);

            HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(body, headers);

            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        TOKEN_URL, HttpMethod.POST, tokenRequest,
                        new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    String token = (String) response.getBody().get("access_token");
                    Number expiresIn = (Number) response.getBody().get("expires_in");
                    long expiresAt = System.currentTimeMillis()
                            + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                    tokenCache.put(clientEmail, new CachedToken(token, expiresAt));
                    log.info("GChat: obtained access token for {}", clientEmail);
                    return token;
                }
                log.error("GChat: token exchange returned {}: {}", response.getStatusCode(), response.getBody());

            } catch (HttpClientErrorException e) {
                log.error("GChat: token exchange client error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            } catch (HttpServerErrorException e) {
                log.error("GChat: token exchange server error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            }
        } catch (Exception e) {
            log.error("GChat: getAccessToken failed: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Gets a DWD (Domain-Wide Delegation) access token.
     * The JWT includes a "sub" claim set to adminEmail, so the service account
     * impersonates that user and can create spaces / manage members.
     * Scopes: chat.spaces + chat.memberships
     */
    public String getAccessTokenDWD(String serviceAccountJson, String adminEmail) {
        try {
            JsonNode sa = objectMapper.readTree(serviceAccountJson);
            String clientEmail = sa.path("client_email").asText();
            String privateKeyPem = sa.path("private_key").asText();

            if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
                log.error("GChat DWD: service account JSON missing client_email or private_key");
                return null;
            }

            String cacheKey = clientEmail + "|DWD|" + adminEmail;
            CachedToken cached = tokenCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
                return cached.token;
            }
            tokenCache.remove(cacheKey);

            String dwdScope = "https://www.googleapis.com/auth/chat.spaces "
                    + "https://www.googleapis.com/auth/chat.memberships "
                    + "https://www.googleapis.com/auth/chat.memberships.app";
            String jwt = buildJwtDWD(clientEmail, privateKeyPem, dwdScope, adminEmail);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.util.MultiValueMap<String, String> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("assertion", jwt);

            HttpEntity<org.springframework.util.MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(body, headers);
            try {
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        TOKEN_URL, HttpMethod.POST, tokenRequest,
                        new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    String token = (String) response.getBody().get("access_token");
                    Number expiresIn = (Number) response.getBody().get("expires_in");
                    long expiresAt = System.currentTimeMillis()
                            + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                    tokenCache.put(cacheKey, new CachedToken(token, expiresAt));
                    log.info("GChat: obtained DWD token for admin={}", adminEmail);
                    return token;
                }
                log.error("GChat DWD: token exchange failed {}", response.getStatusCode());
            } catch (HttpClientErrorException e) {
                log.error("GChat DWD: token exchange error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            }
        } catch (Exception e) {
            log.error("GChat DWD: getAccessTokenDWD failed: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * DWD token scoped only for sending messages (chat.messages.create).
     * Must be authorized separately in Workspace Admin Console DWD entry.
     */
    private String getAccessTokenDWDMessages(String serviceAccountJson, String adminEmail) {
        try {
            JsonNode sa = objectMapper.readTree(serviceAccountJson);
            String clientEmail = sa.path("client_email").asText();
            String privateKeyPem = sa.path("private_key").asText();

            String cacheKey = clientEmail + "|DWD_MSG|" + adminEmail;
            CachedToken cached = tokenCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt) return cached.token;
            tokenCache.remove(cacheKey);

            String scope = "https://www.googleapis.com/auth/chat.messages";
            String jwt = buildJwtDWD(clientEmail, privateKeyPem, scope, adminEmail);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            org.springframework.util.MultiValueMap<String, String> body = new org.springframework.util.LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("assertion", jwt);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String token = (String) response.getBody().get("access_token");
                Number expiresIn = (Number) response.getBody().get("expires_in");
                long expiresAt = System.currentTimeMillis() + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                tokenCache.put(cacheKey, new CachedToken(token, expiresAt));
                log.info("GChat: obtained DWD messages token for admin={}", adminEmail);
                return token;
            }
        } catch (HttpClientErrorException e) {
            log.error("GChat DWD messages: token exchange error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GChat DWD messages: token failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Builds a signed RS256 JWT for the service account.
     * Uses only Java built-in crypto — no extra dependencies required.
     */
    private String buildJwt(String clientEmail, String privateKeyPem, String scope) throws Exception {
        // Strip PEM headers and whitespace
        String stripped = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(stripped);
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        long now = System.currentTimeMillis() / 1000;

        String header = base64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64url("{"
                + "\"iss\":\"" + clientEmail + "\","
                + "\"scope\":\"" + scope + "\","
                + "\"aud\":\"" + TOKEN_URL + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + 3600)
                + "}");

        String signingInput = header + "." + payload;

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sig.sign());

        return signingInput + "." + signature;
    }

    /** Same as buildJwt but adds the "sub" claim for DWD impersonation. */
    private String buildJwtDWD(String clientEmail, String privateKeyPem, String scope, String subEmail) throws Exception {
        String stripped = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(stripped);
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        long now = System.currentTimeMillis() / 1000;

        String header = base64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64url("{"
                + "\"iss\":\"" + clientEmail + "\","
                + "\"sub\":\"" + subEmail + "\","
                + "\"scope\":\"" + scope + "\","
                + "\"aud\":\"" + TOKEN_URL + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + 3600)
                + "}");

        String signingInput = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        return signingInput + "." + signature;
    }

    private String base64url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ── Message payload builder ───────────────────────────────────────────────

    private Map<String, Object> buildMessagePayload(String message, String adminName) {
        // Google Chat Card v2 format
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", "XamOps Support Message");
        header.put("subtitle", "From: " + adminName);

        Map<String, Object> textPara = new LinkedHashMap<>();
        textPara.put("text", message);
        Map<String, Object> textWidget = new LinkedHashMap<>();
        textWidget.put("textParagraph", textPara);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("widgets", List.of(textWidget));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", header);
        card.put("sections", List.of(section));

        Map<String, Object> cardV2 = new LinkedHashMap<>();
        cardV2.put("cardId", "xamops-support-" + System.currentTimeMillis());
        cardV2.put("card", card);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", adminName + ": " + message); // fallback plain text
        payload.put("cardsV2", List.of(cardV2));
        return payload;
    }

    // ── Cache record ─────────────────────────────────────────────────────────

    private static class CachedToken {
        final String token;
        final long expiresAt; // epoch millis
        CachedToken(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }
    }
}
