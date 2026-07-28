package com.xammer.cloud.service.call;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Issues and verifies the short-lived token a browser presents when opening a
 * call signalling socket.
 *
 * This exists because the WebSocket handshake is not a reliable place to
 * establish identity here: the tickets UI is authenticated against billops while
 * the socket terminates on xamops-service, and {@code /xamops-ws/**} sits in the
 * permitAll block. Rather than depend on session cookies propagating between two
 * services, the client fetches this token over an authenticated REST call and
 * hands it to the broker on CONNECT.
 */
@Service
public class CallWsTokenService {

    private static final Logger log = LoggerFactory.getLogger(CallWsTokenService.class);
    private static final String ISSUER = "xamops-calls";

    /** Base64-encoded HMAC key. Must be overridden per environment. */
    @Value("${xamops.calls.ws-token.secret}")
    private String secret;

    /** Long enough to survive a socket reconnect; the client refetches on expiry. */
    @Value("${xamops.calls.ws-token.ttl-seconds:1800}")
    private long ttlSeconds;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    public String issue(Long userId, String username, String displayName,
            Collection<String> authorities, Long clientId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setIssuer(ISSUER)
                .setSubject(String.valueOf(userId))
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlSeconds * 1000L))
                .claim("username", username)
                .claim("name", displayName)
                .claim("roles", authorities)
                .claim("clientId", clientId)
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * @return the caller's identity, or null if the token is missing, expired, or
     *         not one of ours — callers treat null as "reject the connection"
     */
    @SuppressWarnings("unchecked")
    public CallUserPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key())
                    .requireIssuer(ISSUER)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            Long userId = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            String displayName = claims.get("name", String.class);
            // JSON numbers round-trip as Integer when small, so widen rather than cast.
            Object rawClientId = claims.get("clientId");
            Long clientId = (rawClientId instanceof Number) ? ((Number) rawClientId).longValue() : null;

            Set<String> roles = new LinkedHashSet<>();
            Object rawRoles = claims.get("roles");
            if (rawRoles instanceof Collection) {
                for (Object r : (Collection<Object>) rawRoles) {
                    if (r != null) {
                        roles.add(String.valueOf(r));
                    }
                }
            } else if (rawRoles instanceof String) {
                roles.add((String) rawRoles);
            }

            return new CallUserPrincipal(userId, username, displayName, roles, clientId);
        } catch (Exception e) {
            log.debug("Rejected call websocket token: {}", e.getMessage());
            return null;
        }
    }
}
