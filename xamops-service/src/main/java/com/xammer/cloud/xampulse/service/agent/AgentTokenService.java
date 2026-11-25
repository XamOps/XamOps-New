package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.config.XamPulseProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * Short-lived ES256 agent access tokens: {@code sub=instanceId}, {@code tid=cloudAccountId}, 24 h, with a key id.
 * Verification pins the algorithm to ES256 (so {@code none} and HMAC-with-public-key tricks are rejected) and
 * requires the configured issuer. With no keys configured, nothing can be issued and everything fails verification.
 */
@Service("xpAgentTokenService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class AgentTokenService {

    public static final Duration TTL = Duration.ofHours(24);

    public record AgentClaims(String instanceId, long tenantId, Instant expiresAt) {}

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String issuer;
    private final String keyId;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentTokenService(XamPulseProperties props) {
        this(parsePrivate(props.getJwt().getPrivateKeyPem()), parsePublic(props.getJwt().getPublicKeyPem()),
                props.getJwt().getIssuer(), props.getJwt().getKeyId(), Clock.systemUTC());
    }

    public AgentTokenService(PrivateKey privateKey, PublicKey publicKey, String issuer, String keyId, Clock clock) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.issuer = issuer;
        this.keyId = keyId;
        this.clock = clock;
    }

    public String issue(String instanceId, long tenantId) {
        if (privateKey == null) {
            throw new IllegalStateException("XamPulse agent signing key is not configured");
        }
        Instant now = clock.instant();
        return Jwts.builder()
                .setHeaderParam("kid", keyId)
                .setSubject(instanceId)
                .setIssuer(issuer)
                .claim("tid", tenantId)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(TTL)))
                .signWith(privateKey, SignatureAlgorithm.ES256)
                .compact();
    }

    /** Empty for anything that is not a valid, unexpired token we issued. */
    public Optional<AgentClaims> verify(String token) {
        if (publicKey == null || token == null || token.isBlank() || token.length() > 4096) {
            return Optional.empty();
        }
        try {
            var jws = Jwts.parserBuilder()
                    .setSigningKey(publicKey)
                    .requireIssuer(issuer)
                    .setClock(() -> Date.from(clock.instant()))
                    .build()
                    .parseClaimsJws(token);
            JwsHeader header = jws.getHeader();
            if (!SignatureAlgorithm.ES256.getValue().equals(header.getAlgorithm())) {
                return Optional.empty();
            }
            Claims c = jws.getBody();
            Number tid = c.get("tid", Number.class);
            if (c.getSubject() == null || tid == null || c.getExpiration() == null) {
                return Optional.empty();
            }
            return Optional.of(new AgentClaims(c.getSubject(), tid.longValue(), c.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static PrivateKey parsePrivate(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("xampulse.jwt.private-key-pem is not a valid PKCS#8 EC key");
        }
    }

    static PublicKey parsePublic(String pem) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("xampulse.jwt.public-key-pem is not a valid X.509 EC key");
        }
    }

    private static byte[] der(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
