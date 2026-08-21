package com.xammer.cloud.service.call;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mints LiveKit join tokens.
 *
 * A LiveKit access token is a plain HS256 JWT signed with the API secret, so no
 * LiveKit server SDK is needed — jjwt is already on the classpath. The secret is
 * used as raw UTF-8 key material (not base64-decoded), which is what LiveKit
 * expects and differs from the impersonation token handling elsewhere in this
 * service.
 *
 * Grants are deliberately narrow: a token is valid for exactly one room and one
 * identity, and expires shortly after issue. Joining a call later means asking
 * for a fresh token, which re-runs the authorization check.
 */
@Service
public class LiveKitTokenService {

    @Value("${xamops.calls.livekit.api-key:}")
    private String apiKey;

    @Value("${xamops.calls.livekit.api-secret:}")
    private String apiSecret;

    /** Browser-facing LiveKit websocket URL, e.g. wss://livekit.xamops.com. */
    @Value("${xamops.calls.livekit.url:}")
    private String livekitUrl;

    /**
     * Default kept in step with application.properties.
     *
     * A LiveKit token is presented on join and reused by the client on its automatic
     * reconnect, so a short TTL is not the safety win it looks like: at 900s any call
     * longer than fifteen minutes could not survive a network blip, because the
     * reconnect retried with an expired token and stranded the participant.
     * Authorization is re-checked whenever a token is issued and a token is worthless
     * without a live session, so the longer lifetime widens no meaningful window.
     */
    @Value("${xamops.calls.livekit.token-ttl-seconds:14400}")
    private long tokenTtlSeconds;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && apiSecret != null && !apiSecret.isBlank()
                && livekitUrl != null && !livekitUrl.isBlank();
    }

    public String getLivekitUrl() {
        return livekitUrl;
    }

    /**
     * Issues a join token for one participant in one room.
     *
     * @param identity stable per-user identity; LiveKit evicts an existing
     *                 participant with the same identity, which is what we want
     *                 when a user rejoins from a second tab
     */
    public String createJoinToken(String roomName, String identity, String displayName, boolean canPublish) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "LiveKit is not configured; set xamops.calls.livekit.api-key, api-secret and url");
        }

        // HS256 requires 256 bits of key material. LiveKit's own `--dev` default
        // secret ("secret") is far shorter, so without this check the first call
        // dies inside jjwt with an opaque WeakKeyException. Padding is not an
        // option — LiveKit verifies against the raw secret, so any adjustment
        // here would produce tokens its server rejects.
        byte[] keyBytes = apiSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "LiveKit api-secret must be at least 32 characters (got " + keyBytes.length
                            + "). Generate one with `livekit-server generate-keys`.");
        }

        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiry = new Date(now + tokenTtlSeconds * 1000L);

        Map<String, Object> videoGrant = new LinkedHashMap<>();
        videoGrant.put("room", roomName);
        videoGrant.put("roomJoin", true);
        videoGrant.put("canPublish", canPublish);
        videoGrant.put("canSubscribe", true);
        // Data messages carry in-room UI state (mute, hand-raise) without a round
        // trip through our backend.
        videoGrant.put("canPublishData", true);

        return Jwts.builder()
                .setIssuer(apiKey)
                .setSubject(identity)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(issuedAt)
                .setNotBefore(issuedAt)
                .setExpiration(expiry)
                .claim("name", displayName)
                .claim("video", videoGrant)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
