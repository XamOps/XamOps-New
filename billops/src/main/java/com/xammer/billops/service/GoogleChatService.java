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
 * Google Chat integration using the new SPACE-type architecture:
 *
 * Setup flow (per user):
 * 1. DWD token (impersonating admin) → POST /v1/spaces (SPACE type, named)
 * 2. DWD token → POST /v1/spaces/{id}/members (add human user)
 * 3. Bot token (no DWD) → POST /v1/spaces/{id}/members (bot adds itself)
 * — Bot is now a member → inbound webhook fires when user replies ✅
 *
 * Send flow:
 * Bot token (no DWD, no sub=) → POST /v1/spaces/{id}/messages
 * → Message appears as "XamOps Support" (app name), not a person ✅
 * Falls back to DWD send if bot send fails.
 *
 * Inbound flow:
 * User replies → Google POSTs to /api/billops/webhook/gchat
 * → Webhook saves message, pushes to dashboard via SSE/WebSocket ✅
 *
 * Required DWD scopes (Google Workspace Admin Console):
 * chat.spaces, chat.memberships, chat.memberships.app, chat.messages, chat.bot
 */
@Service
public class GoogleChatService {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatService.class);
    private static final String CHAT_SCOPE = "https://www.googleapis.com/auth/chat.bot";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String CHAT_BASE = "https://chat.googleapis.com/v1";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Creates a named SPACE, adds the user, then adds the bot.
     * Bot membership enables inbound webhook. Bot sends messages (shows app name,
     * not admin name).
     * Falls back to spaces:setup (DIRECT_MESSAGE) if SPACE creation fails.
     */
    public String createSpaceAndInvite(String serviceAccountJson, String adminEmail,
            String userEmail, String displayName) throws Exception {

        // Step 1: Get DWD token (impersonating admin)
        String dwdToken = getAccessTokenDWD(serviceAccountJson, adminEmail);
        if (dwdToken == null) {
            throw new Exception("Failed to obtain DWD access token. Ensure Domain-Wide Delegation is enabled.");
        }

        String spaceName;
        boolean botAdded = false;

        try {
            // Step 2: Create named SPACE via DWD
            spaceName = createNamedSpace(dwdToken, displayName);
            log.info("GChat: SPACE {} created (admin={})", spaceName, adminEmail);

            // Step 3: Add user as HUMAN member via DWD
            addUserToSpace(dwdToken, spaceName, userEmail);
            log.info("GChat: user {} added to space {}", userEmail, spaceName);

            // Step 4: Add bot to space via DWD with chat.memberships.app scope
            try {
                addBotToSpace(serviceAccountJson, adminEmail, spaceName);
                botAdded = true;
                log.info("GChat: bot added to space {} — inbound webhook enabled", spaceName);
            } catch (Exception e) {
                log.warn("GChat: bot could not join space {}: {}", spaceName, e.getMessage());
            }

        } catch (OrgBlocksExternalException e) {
            throw e;
        } catch (Exception e) {
            log.warn("GChat: SPACE creation failed ({}), falling back to DIRECT_MESSAGE", e.getMessage());
            spaceName = setupDirectMessageSpace(dwdToken, userEmail);
            log.info("GChat: fallback DM space {} created for {}", spaceName, userEmail);
        }

        if (!botAdded) {
            log.warn("GChat: bot not in space {} — outbound via DWD, inbound won't work", spaceName);
        }
        return spaceName;
    }

    /**
     * Sends a message to a Google Chat space.
     * Tries bot token first (message shows as app name).
     * Falls back to DWD (message shows as admin's name) if bot token fails.
     */
    public String sendMessage(String serviceAccountJson, String spaceName, String message, String adminName) {
        return sendMessage(serviceAccountJson, null, spaceName, message, adminName);
    }

    public String sendMessage(String serviceAccountJson, String adminEmail,
            String spaceName, String message, String adminName) {
        // Try bot token first — message shows as "XamOps Support" (app name), no
        // personal name
        String botToken = getAccessToken(serviceAccountJson);
        if (botToken != null) {
            String result = doSend(botToken, spaceName, buildMessagePayload(message, adminName));
            if (result != null)
                return result;
            log.warn("GChat: bot send failed for space {}, falling back to DWD", spaceName);
        }

        // Fallback: DWD token — message shows as admin's name (e.g. "Aditya Mehta")
        if (adminEmail != null && !adminEmail.isBlank()) {
            String dwdToken = getAccessTokenDWDMessages(serviceAccountJson, adminEmail);
            if (dwdToken != null) {
                String result = doSend(dwdToken, spaceName, Map.of("text", message));
                if (result != null)
                    return result;
            }
        }
        log.error("GChat: all send methods failed for space {}", spaceName);
        return null;
    }

    // ── Internal: Space management ────────────────────────────────────────────

    /**
     * Creates a named SPACE via DWD (POST /v1/spaces). Requires chat.spaces scope.
     */
    private String createNamedSpace(String dwdToken, String displayName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dwdToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> space = new LinkedHashMap<>();
        space.put("spaceType", "SPACE");
        space.put("displayName", displayName != null && !displayName.isBlank() ? displayName : "XamOps Support");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(space, headers);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    CHAT_BASE + "/spaces",
                    HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("name");
            }
            throw new Exception("spaces.create returned " + response.getStatusCode());
        } catch (HttpClientErrorException e) {
            throw new Exception("spaces.create error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    /**
     * Creates a DM space between the bot and the user using the bot's own token.
     * Bot is automatically a member → all user messages fire the webhook
     * (no @mention needed).
     */
    private String createBotDmSpace(String botToken, String userEmail) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(botToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

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

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    CHAT_BASE + "/spaces:setup",
                    HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("name");
            }
            throw new Exception("spaces:setup returned " + response.getStatusCode());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 403) {
                String body2 = e.getResponseBodyAsString();
                if (body2.contains("external") || body2.contains("blocked")) {
                    throw new OrgBlocksExternalException(userEmail);
                }
            }
            throw new Exception("createBotDmSpace " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    /** Adds a human user to the space via DWD (POST /v1/spaces/{id}/members). */
    private void addUserToSpace(String dwdToken, String spaceName, String userEmail) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dwdToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> member = new LinkedHashMap<>();
        member.put("name", "users/" + userEmail);
        member.put("type", "HUMAN");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member", member);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    CHAT_BASE + "/" + spaceName + "/members",
                    HttpMethod.POST, request,
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new Exception("members.create returned " + response.getStatusCode());
            }
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 403) {
                throw new OrgBlocksExternalException(userEmail);
            }
            throw new Exception("addUserToSpace error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    /** Thrown when the user's org has blocked external Google Chat messaging. */
    public static class OrgBlocksExternalException extends Exception {
        private final String userEmail;

        public OrgBlocksExternalException(String userEmail) {
            super("USER_ORG_BLOCKS_EXTERNAL: " + userEmail + "'s organization has blocked external Google Chat.");
            this.userEmail = userEmail;
        }

        public String getUserEmail() {
            return userEmail;
        }
    }

    /**
     * Adds the bot to the space using DWD token with chat.memberships.app scope,
     * impersonating the admin. This is the correct approach for internal Chat apps.
     * Resource name must be "users/app" with type "BOT".
     */
    private void addBotToSpace(String serviceAccountJson, String adminEmail, String spaceName) throws Exception {
        JsonNode sa = objectMapper.readTree(serviceAccountJson);
        String clientEmail = sa.path("client_email").asText();
        String privateKeyPem = sa.path("private_key").asText();

        // DWD (sub=adminEmail) with chat.memberships.app scope.
        String cacheKey = clientEmail + "|DWD_BOT|" + adminEmail;
        CachedToken cached = tokenCache.get(cacheKey);
        String token = (cached != null && System.currentTimeMillis() < cached.expiresAt) ? cached.token : null;
        if (token == null) {
            tokenCache.remove(cacheKey);
            String jwt = buildJwtDWD(clientEmail, privateKeyPem,
                    "https://www.googleapis.com/auth/chat.memberships.app", adminEmail);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            form.add("assertion", jwt);
            ResponseEntity<Map<String, Object>> tr;
            try {
                tr = restTemplate.exchange(
                        TOKEN_URL, HttpMethod.POST, new HttpEntity<>(form, h),
                        new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                        });
            } catch (HttpClientErrorException e) {
                throw new Exception("chat.memberships.app DWD token failed "
                        + e.getStatusCode() + ": " + e.getResponseBodyAsString());
            }
            if (!tr.getStatusCode().is2xxSuccessful() || tr.getBody() == null)
                throw new Exception("Failed to obtain chat.memberships.app DWD token");
            token = (String) tr.getBody().get("access_token");
            Number exp = (Number) tr.getBody().get("expires_in");
            tokenCache.put(cacheKey, new CachedToken(token,
                    System.currentTimeMillis() + ((exp != null ? exp.longValue() : 3600L) - 300L) * 1000L));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("member", Map.of("name", "users/app", "type", "BOT"));
        try {
            restTemplate.exchange(CHAT_BASE + "/" + spaceName + "/members",
                    HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (HttpClientErrorException e) {
            throw new Exception("addBotToSpace " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    /**
     * Fallback: spaces:setup DIRECT_MESSAGE (when SPACE creation fails).
     * Bot cannot join DM spaces — inbound won't work in this path.
     */
    private String setupDirectMessageSpace(String dwdToken, String userEmail) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(dwdToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

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
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (String) response.getBody().get("name");
            }
            throw new Exception("spaces:setup returned " + response.getStatusCode());
        } catch (HttpClientErrorException e) {
            throw new Exception("spaces:setup error " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        }
    }

    // ── Internal: Send helper ─────────────────────────────────────────────────

    private String doSend(String accessToken, String spaceName, Map<String, Object> payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    CHAT_BASE + "/" + spaceName + "/messages",
                    HttpMethod.POST, new HttpEntity<>(payload, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String msgName = (String) response.getBody().get("name");
                log.info("GChat: message sent to {} → {}", spaceName, msgName);
                return msgName != null ? msgName : "sent";
            }
        } catch (HttpClientErrorException e) {
            log.error("GChat: doSend client error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            log.error("GChat: doSend server error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GChat: doSend failed: {}", e.getMessage());
        }
        return null;
    }

    // ── Internal: Access tokens ───────────────────────────────────────────────

    /** Bot's own token (chat.bot scope, no DWD). Message appears as app name. */
    public String getAccessToken(String serviceAccountJson) {
        try {
            JsonNode sa = objectMapper.readTree(serviceAccountJson);
            String clientEmail = sa.path("client_email").asText();
            String privateKeyPem = sa.path("private_key").asText();

            if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
                log.error("GChat: service account JSON missing client_email or private_key");
                return null;
            }

            CachedToken cached = tokenCache.get(clientEmail);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt)
                return cached.token;
            tokenCache.remove(clientEmail);

            String jwt = buildJwt(clientEmail, privateKeyPem, CHAT_SCOPE);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("assertion", jwt);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String token = (String) response.getBody().get("access_token");
                Number expiresIn = (Number) response.getBody().get("expires_in");
                long expiresAt = System.currentTimeMillis()
                        + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                tokenCache.put(clientEmail, new CachedToken(token, expiresAt));
                log.info("GChat: obtained bot token for {}", clientEmail);
                return token;
            }
        } catch (HttpClientErrorException e) {
            log.error("GChat: bot token error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GChat: getAccessToken failed: {}", e.getMessage(), e);
        }
        return null;
    }

    /** DWD token for space/member management. Impersonates adminEmail. */
    public String getAccessTokenDWD(String serviceAccountJson, String adminEmail) {
        try {
            JsonNode sa = objectMapper.readTree(serviceAccountJson);
            String clientEmail = sa.path("client_email").asText();
            String privateKeyPem = sa.path("private_key").asText();

            if (clientEmail.isBlank() || privateKeyPem.isBlank()) {
                log.error("GChat DWD: service account JSON missing fields");
                return null;
            }

            String cacheKey = clientEmail + "|DWD|" + adminEmail;
            CachedToken cached = tokenCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt)
                return cached.token;
            tokenCache.remove(cacheKey);

            String dwdScope = "https://www.googleapis.com/auth/chat.spaces "
                    + "https://www.googleapis.com/auth/chat.memberships";
            String jwt = buildJwtDWD(clientEmail, privateKeyPem, dwdScope, adminEmail);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("assertion", jwt);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String token = (String) response.getBody().get("access_token");
                Number expiresIn = (Number) response.getBody().get("expires_in");
                long expiresAt = System.currentTimeMillis()
                        + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                tokenCache.put(cacheKey, new CachedToken(token, expiresAt));
                log.info("GChat: obtained DWD token for admin={}", adminEmail);
                return token;
            }
        } catch (HttpClientErrorException e) {
            log.error("GChat DWD: token error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GChat DWD: getAccessTokenDWD failed: {}", e.getMessage(), e);
        }
        return null;
    }

    /** DWD token scoped for sending/reading messages (chat.messages). */
    public String getAccessTokenDWDMessages(String serviceAccountJson, String adminEmail) {
        try {
            JsonNode sa = objectMapper.readTree(serviceAccountJson);
            String clientEmail = sa.path("client_email").asText();
            String privateKeyPem = sa.path("private_key").asText();

            String cacheKey = clientEmail + "|DWD_MSG|" + adminEmail;
            CachedToken cached = tokenCache.get(cacheKey);
            if (cached != null && System.currentTimeMillis() < cached.expiresAt)
                return cached.token;
            tokenCache.remove(cacheKey);

            String jwt = buildJwtDWD(clientEmail, privateKeyPem,
                    "https://www.googleapis.com/auth/chat.messages", adminEmail);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("assertion", jwt);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    TOKEN_URL, HttpMethod.POST, new HttpEntity<>(body, headers),
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String token = (String) response.getBody().get("access_token");
                Number expiresIn = (Number) response.getBody().get("expires_in");
                long expiresAt = System.currentTimeMillis()
                        + ((expiresIn != null ? expiresIn.longValue() : 3600L) - 300L) * 1000L;
                tokenCache.put(cacheKey, new CachedToken(token, expiresAt));
                log.info("GChat: obtained DWD messages token for admin={}", adminEmail);
                return token;
            }
        } catch (HttpClientErrorException e) {
            log.error("GChat DWD messages: token error {} body={}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("GChat DWD messages: token failed: {}", e.getMessage());
        }
        return null;
    }

    // ── Internal: JWT builders ────────────────────────────────────────────────

    private String buildJwt(String clientEmail, String privateKeyPem, String scope) throws Exception {
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
        String payload = base64url("{\"iss\":\"" + clientEmail + "\","
                + "\"scope\":\"" + scope + "\","
                + "\"aud\":\"" + TOKEN_URL + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + 3600) + "}");
        String signingInput = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }

    private String buildJwtDWD(String clientEmail, String privateKeyPem,
            String scope, String subEmail) throws Exception {
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
        String payload = base64url("{\"iss\":\"" + clientEmail + "\","
                + "\"sub\":\"" + subEmail + "\","
                + "\"scope\":\"" + scope + "\","
                + "\"aud\":\"" + TOKEN_URL + "\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + 3600) + "}");
        String signingInput = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }

    private String base64url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    // ── Message payload ───────────────────────────────────────────────────────

    private Map<String, Object> buildMessagePayload(String message, String adminName) {
        Map<String, Object> textPara = new LinkedHashMap<>();
        textPara.put("text", message);
        Map<String, Object> textWidget = new LinkedHashMap<>();
        textWidget.put("textParagraph", textPara);

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("widgets", List.of(textWidget));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("header", Map.of("title", "XamOps Support", "subtitle", "From: " + adminName));
        card.put("sections", List.of(section));

        Map<String, Object> cardV2 = new LinkedHashMap<>();
        cardV2.put("cardId", "xamops-" + System.currentTimeMillis());
        cardV2.put("card", card);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", message); // plain text fallback
        payload.put("cardsV2", List.of(cardV2));
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