package com.xammer.cloud.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.auth.MicrosoftIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Handles the server-side Microsoft (Entra ID / Azure AD) OpenID Connect
 * Authorization Code flow. Mirrors {@link GoogleOAuthService}.
 *
 * The Client Secret is used only here (server-to-server) during the token
 * exchange and is never sent to the browser.
 *
 * ID token trust model: the id_token is obtained directly from Microsoft's
 * token endpoint over TLS (not via the browser), so its origin is already
 * trusted. We additionally validate the audience (our client id), issuer, and
 * expiry. The tenant is "common" so both work/school (Microsoft 365) and
 * personal (outlook.com/hotmail) accounts can sign in; the issuer therefore
 * carries a per-tenant GUID and is validated by shape rather than a fixed value.
 */
@Service
public class MicrosoftOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftOAuthService.class);

    // {tenant} is substituted from config (default "common" = any account type).
    private static final String AUTH_ENDPOINT_TMPL  = "https://login.microsoftonline.com/%s/oauth2/v2.0/authorize";
    private static final String TOKEN_ENDPOINT_TMPL = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";

    @Value("${microsoft.oauth.client-id:}")
    private String clientId;

    @Value("${microsoft.oauth.client-secret:}")
    private String clientSecret;

    @Value("${microsoft.oauth.redirect-uri:}")
    private String redirectUri;

    @Value("${microsoft.oauth.tenant:common}")
    private String tenant;

    // Timeout-bounded (5s connect / 15s read). Must not be `new RestTemplate()`: these
    // calls run inline during browser login, and the default has no timeouts at all.
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MicrosoftOAuthService(@Qualifier("oauthRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }

    /**
     * Builds the Microsoft authorization URL the browser is redirected to.
     * @param state opaque anti-CSRF value echoed back on the callback
     */
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromHttpUrl(String.format(AUTH_ENDPOINT_TMPL, tenant))
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("response_mode", "query")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("prompt", "select_account")
                .build()
                .toUriString();
    }

    /**
     * Exchanges the authorization code for tokens and returns the verified identity.
     * @throws MicrosoftAuthException if the exchange fails or the token is invalid/unverified
     */
    public MicrosoftIdentity exchangeCodeForIdentity(String code) {
        String idToken = requestIdToken(code);
        return parseAndValidateIdToken(idToken);
    }

    private String requestIdToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        form.add("scope", "openid email profile");

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    String.format(TOKEN_ENDPOINT_TMPL, tenant), HttpMethod.POST,
                    new HttpEntity<>(form, headers), String.class);

            JsonNode body = objectMapper.readTree(resp.getBody());
            JsonNode idToken = body.get("id_token");
            if (idToken == null || idToken.asText().isBlank()) {
                throw new MicrosoftAuthException("Microsoft token response did not contain an id_token");
            }
            return idToken.asText();
        } catch (MicrosoftAuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Microsoft token exchange failed: {}", e.getMessage());
            throw new MicrosoftAuthException("Failed to exchange authorization code with Microsoft");
        }
    }

    private MicrosoftIdentity parseAndValidateIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new MicrosoftAuthException("Malformed Microsoft id_token");
            }
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode claims = objectMapper.readTree(payloadJson);

            String aud = text(claims, "aud");
            if (aud == null || !aud.equals(clientId)) {
                throw new MicrosoftAuthException("Microsoft id_token audience mismatch");
            }

            // Issuer is per-tenant (e.g. https://login.microsoftonline.com/<tid>/v2.0),
            // so validate by shape rather than a fixed string.
            String iss = text(claims, "iss");
            if (iss == null
                    || !iss.startsWith("https://login.microsoftonline.com/")
                    || !iss.endsWith("/v2.0")) {
                throw new MicrosoftAuthException("Microsoft id_token issuer invalid");
            }

            long exp = claims.has("exp") ? claims.get("exp").asLong() : 0L;
            if (exp <= (System.currentTimeMillis() / 1000L)) {
                throw new MicrosoftAuthException("Microsoft id_token has expired");
            }

            // Microsoft puts the address in "email" when available, otherwise the
            // sign-in name is in "preferred_username" (which is an email for
            // work/school and personal accounts).
            String email = text(claims, "email");
            if (email == null || email.isBlank()) {
                email = text(claims, "preferred_username");
            }
            if (email == null || email.isBlank() || !email.contains("@")) {
                throw new MicrosoftAuthException("Microsoft account did not return an email address");
            }

            String sub = text(claims, "sub");
            if (sub == null || sub.isBlank()) {
                throw new MicrosoftAuthException("Microsoft id_token missing subject");
            }

            return new MicrosoftIdentity(
                    sub,
                    email.toLowerCase().trim(),
                    true, // Microsoft only issues tokens for the account the user authenticated to
                    text(claims, "name"));
        } catch (MicrosoftAuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Microsoft id_token: {}", e.getMessage());
            throw new MicrosoftAuthException("Failed to validate Microsoft identity");
        }
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** Thrown when Microsoft authentication cannot be completed or trusted. */
    public static class MicrosoftAuthException extends RuntimeException {
        public MicrosoftAuthException(String message) { super(message); }
    }
}
