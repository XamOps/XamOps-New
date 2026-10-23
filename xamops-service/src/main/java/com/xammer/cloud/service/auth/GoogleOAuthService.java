package com.xammer.cloud.service.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.auth.GoogleIdentity;
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
 * Handles the server-side Google OpenID Connect Authorization Code flow.
 *
 * The Client Secret is used only here (server-to-server) during the token
 * exchange and is never sent to the browser.
 *
 * ID token trust model: the id_token is obtained directly from Google's token
 * endpoint over TLS (not via the browser), so its origin is already trusted.
 * We additionally validate the audience (our client id), issuer, and expiry.
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    @Value("${google.oauth.client-id:}")
    private String clientId;

    @Value("${google.oauth.client-secret:}")
    private String clientSecret;

    // EXTERNAL CONSOLE TASK: the demo host reuses this same flow, so its callback URL
    // https://demo.xamops.com/api/auth/google/callback must be registered as an additional
    // Authorized redirect URI in the Google Cloud Console OAuth client, alongside the live one.
    // Without it, Google sign-in on demo.xamops.com fails with redirect_uri_mismatch. Comment
    // only — nothing here changes; the redirect URI itself is supplied per-environment via config.
    @Value("${google.oauth.redirect-uri:}")
    private String redirectUri;

    // Timeout-bounded (5s connect / 15s read). Must not be `new RestTemplate()`: these
    // calls run inline during browser login, and the default has no timeouts at all.
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GoogleOAuthService(@Qualifier("oauthRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }

    /**
     * Builds the Google authorization URL the browser is redirected to.
     * @param state opaque anti-CSRF value echoed back on the callback
     * @param redirectUri the OAuth {@code redirect_uri} to use; chosen per-request by the
     *                    controller (live vs demo host). MUST byte-match the value passed to
     *                    {@link #exchangeCodeForIdentity(String, String)} on the callback leg.
     */
    public String buildAuthorizationUrl(String state, String redirectUri) {
        return UriComponentsBuilder.fromUriString(AUTH_ENDPOINT)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("access_type", "online")
                .queryParam("prompt", "select_account")
                .build()
                .toUriString();
    }

    /**
     * Exchanges the authorization code for tokens and returns the verified identity.
     * @param redirectUri MUST be the SAME value used on the authorization leg
     *                    ({@link #buildAuthorizationUrl(String, String)}) or Google rejects the exchange.
     * @throws GoogleAuthException if the exchange fails or the token is invalid/unverified
     */
    public GoogleIdentity exchangeCodeForIdentity(String code, String redirectUri) {
        String idToken = requestIdToken(code, redirectUri);
        return parseAndValidateIdToken(idToken);
    }

    private String requestIdToken(String code, String redirectUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    TOKEN_ENDPOINT, HttpMethod.POST,
                    new HttpEntity<>(form, headers), String.class);

            JsonNode body = objectMapper.readTree(resp.getBody());
            JsonNode idToken = body.get("id_token");
            if (idToken == null || idToken.asText().isBlank()) {
                throw new GoogleAuthException("Google token response did not contain an id_token");
            }
            return idToken.asText();
        } catch (GoogleAuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Google token exchange failed: {}", e.getMessage());
            throw new GoogleAuthException("Failed to exchange authorization code with Google");
        }
    }

    private GoogleIdentity parseAndValidateIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                throw new GoogleAuthException("Malformed Google id_token");
            }
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonNode claims = objectMapper.readTree(payloadJson);

            String aud = text(claims, "aud");
            if (aud == null || !aud.equals(clientId)) {
                throw new GoogleAuthException("Google id_token audience mismatch");
            }

            String iss = text(claims, "iss");
            if (iss == null || !(iss.equals("accounts.google.com")
                    || iss.equals("https://accounts.google.com"))) {
                throw new GoogleAuthException("Google id_token issuer invalid");
            }

            long exp = claims.has("exp") ? claims.get("exp").asLong() : 0L;
            if (exp <= (System.currentTimeMillis() / 1000L)) {
                throw new GoogleAuthException("Google id_token has expired");
            }

            boolean emailVerified = claims.has("email_verified") && claims.get("email_verified").asBoolean();
            String email = text(claims, "email");
            if (email == null || !emailVerified) {
                throw new GoogleAuthException("Google email is missing or unverified");
            }

            return new GoogleIdentity(
                    text(claims, "sub"),
                    email.toLowerCase().trim(),
                    true,
                    text(claims, "name"),
                    text(claims, "picture"));
        } catch (GoogleAuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse Google id_token: {}", e.getMessage());
            throw new GoogleAuthException("Failed to validate Google identity");
        }
    }

    private static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /** Thrown when Google authentication cannot be completed or trusted. */
    public static class GoogleAuthException extends RuntimeException {
        public GoogleAuthException(String message) { super(message); }
    }
}
