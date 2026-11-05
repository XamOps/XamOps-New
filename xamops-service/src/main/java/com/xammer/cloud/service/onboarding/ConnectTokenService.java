package com.xammer.cloud.service.onboarding;

import com.xammer.cloud.domain.ClusterConnectToken;
import com.xammer.cloud.repository.ClusterConnectTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Mints, hashes, issues, revokes and atomically consumes one-time connect tokens
 * (design §2.1, §2.2, §4.6).
 *
 * <p>A raw token is {@code xct_} + 32 random bytes URL-safe base64 (no padding) = 43 chars. Only
 * its SHA-256 (lowercase hex) is stored, alongside a 12-char display prefix ({@code xct_} + the
 * next 8 chars) that is safe for logs and the UI. The raw value is returned to the caller exactly
 * once, at issue; it is never persisted or logged.</p>
 */
@Service
public class ConnectTokenService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectTokenService.class);

    public static final String TOKEN_PREFIX = "xct_";
    private static final int TOKEN_BYTES = 32;
    /** 'xct_' + 43 base64url chars of 32 bytes. */
    public static final int TOKEN_BODY_LEN = 43;
    public static final long DEFAULT_TTL_SECONDS = 600;

    // Purposes (design §2.1).
    public static final String PURPOSE_CONNECT_READ_ONLY = "CONNECT_READ_ONLY";
    public static final String PURPOSE_ENABLE_AUTOMATION = "ENABLE_AUTOMATION";
    public static final String PURPOSE_ROTATE_DATA_KEY = "ROTATE_DATA_KEY";
    public static final String PURPOSE_ROTATE_AUTOMATION_KEY = "ROTATE_AUTOMATION_KEY";

    private final ClusterConnectTokenRepository tokenRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public ConnectTokenService(ClusterConnectTokenRepository tokenRepository, Clock clock) {
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    /** The raw token plus the persisted row — the raw value lives only in this return value. */
    public record IssuedToken(String rawToken, ClusterConnectToken row) {
    }

    /** A freshly minted raw token. */
    public String mint() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase-hex SHA-256 of the full token. */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The 12-char display prefix ('xct_' + next 8 chars) — log/UI safe. */
    public static String displayPrefix(String rawToken) {
        if (rawToken == null) {
            return TOKEN_PREFIX;
        }
        return rawToken.substring(0, Math.min(12, rawToken.length()));
    }

    /**
     * Validate a bearer value's shape: {@code xct_} + 43 base64url chars. Guards the exchange's
     * {@code TOKEN_INVALID} arm (design §4.3) before any DB lookup.
     */
    public static boolean hasValidShape(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return false;
        }
        String body = rawToken.substring(TOKEN_PREFIX.length());
        return body.length() == TOKEN_BODY_LEN && body.matches("[A-Za-z0-9_-]+");
    }

    /**
     * Issue a token for one purpose. Revokes earlier unused tokens of the same (cluster, purpose)
     * in the same transaction (the "at most one live token per (cluster, purpose)" rule, §2.2),
     * then mints and persists a new row. The caller sets purpose-specific fields (features,
     * createReadRole, permissionHash, nodeScaler, identityMode, nodeRemoval) on the returned row.
     */
    @Transactional
    public IssuedToken issue(Long clusterConfigId, String purpose, long issuedByUserId, long ttlSeconds) {
        tokenRepository.revokeLiveTokens(clusterConfigId, purpose, Instant.now(clock));

        String raw = mint();
        Instant now = Instant.now(clock);
        ClusterConnectToken row = new ClusterConnectToken();
        row.setTokenHash(hash(raw));
        row.setTokenPrefix(displayPrefix(raw));
        row.setClusterConfigId(clusterConfigId);
        row.setPurpose(purpose);
        row.setFeatures("");
        row.setCreateReadRole(false);
        row.setNodeRemoval(false);
        row.setIssuedByUserId(issuedByUserId);
        row.setIssuedAt(now);
        row.setExpiresAt(now.plus(ttlSeconds, ChronoUnit.SECONDS));
        ClusterConnectToken saved = tokenRepository.save(row);
        logger.info("Issued connect token {} purpose={} cluster={} ttl={}s",
                saved.getTokenPrefix(), purpose, clusterConfigId, ttlSeconds);
        return new IssuedToken(raw, saved);
    }

    /** Resolve a token by its raw value's hash. */
    public Optional<ClusterConnectToken> findByRaw(String rawToken) {
        return tokenRepository.findByTokenHash(hash(rawToken));
    }

    /**
     * Atomically mark a token used. Returns true only when this caller won the race (the DB
     * updated exactly one row). A false result means used/expired/revoked — the caller re-reads
     * the row to pick the refusal code (design §2.2, AC-4).
     */
    @Transactional
    public boolean consume(Long tokenId, String fromIp) {
        return tokenRepository.consume(tokenId, Instant.now(clock), fromIp) == 1;
    }

    @Transactional
    public void revoke(ClusterConnectToken token) {
        token.setRevokedAt(Instant.now(clock));
        tokenRepository.save(token);
    }

    /** The newest token per purpose for a cluster (for the status endpoint; design §4.2). */
    public List<ClusterConnectToken> findByClusterAndPurpose(Long clusterConfigId, String purpose) {
        return tokenRepository.findByClusterConfigIdAndPurpose(clusterConfigId, purpose);
    }
}
