package com.xammer.cloud.service.autospotting.core;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The {@code Idempotency-Key} contract for plan, confirm and operator POSTs (design-core.md §3.7,
 * §11). A client sends a UUID key with every create; the key and {@code request_sha256} (the
 * SHA-256 of the canonical request body) are stored on {@code spot_plan}/{@code spot_operation} with
 * a unique {@code (cloud_account_id, idempotency_key)} index. This service owns the three rules:
 *
 * <ol>
 *   <li><strong>Required.</strong> A plan/confirm/operator POST with no {@code Idempotency-Key} is
 *       {@code 400} ({@link #requireKey}).</li>
 *   <li><strong>Hash.</strong> {@link #hash} is the hex SHA-256 of the canonical body, stored as
 *       {@code request_sha256}.</li>
 *   <li><strong>Replay vs reuse.</strong> {@link #classify} compares the incoming hash with the
 *       hash stored for the same key: a match is a replay (the caller returns the original
 *       resource), a mismatch is {@code 422 IDEMPOTENCY_KEY_REUSED} ({@link #requireSameBody}).</li>
 * </ol>
 *
 * <p>The service holds no state: the {@code (key, hash)} lookup is the caller's, against the op/plan
 * store. It computes and compares only, so it is trivial to unit test.
 */
@Service
public class IdempotencyService {

    /** The verdict of comparing an incoming request against a stored one for the same key. */
    public enum Verdict {
        /** No prior request with this key: proceed and persist {@code (key, hash)}. */
        NEW,
        /** Same key, same hash: a replay — return the original resource. */
        REPLAY,
        /** Same key, different hash: {@code 422 IDEMPOTENCY_KEY_REUSED} (§3.7). */
        REUSED
    }

    /**
     * Require a non-blank {@code Idempotency-Key} on a create POST (§3.7). The {@code 400} here has no
     * stable {@code code} by design — a missing required header is a client-shape error, matching
     * the ux §9.2 row "Missing Idempotency-Key on plan or confirm → 400".
     *
     * @return the trimmed key
     * @throws AutoSpottingApiException 400 when the key is null or blank
     */
    public String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AutoSpottingApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "The Idempotency-Key header is required on this request.");
        }
        return idempotencyKey.trim();
    }

    /** The hex SHA-256 of the canonical request body, stored as {@code request_sha256} (§3.7). */
    public String hash(String canonicalBody) {
        String body = canonicalBody == null ? "" : canonicalBody;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this can't happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Classify an incoming request against the stored hash for the same key.
     *
     * @param storedHash  the {@code request_sha256} already stored for this key, or null if none
     * @param incomingHash {@link #hash} of the incoming body
     */
    public Verdict classify(String storedHash, String incomingHash) {
        if (storedHash == null) {
            return Verdict.NEW;
        }
        return storedHash.equals(incomingHash) ? Verdict.REPLAY : Verdict.REUSED;
    }

    /**
     * Enforce the reuse rule: a stored hash that differs from the incoming one is
     * {@code 422 IDEMPOTENCY_KEY_REUSED} (§3.7). Returns the verdict for the caller to act on a
     * replay ({@code REPLAY}) versus a fresh request ({@code NEW}).
     *
     * @throws AutoSpottingApiException 422 {@code IDEMPOTENCY_KEY_REUSED} on a hash mismatch
     */
    public Verdict requireSameBody(String storedHash, String incomingHash) {
        Verdict verdict = classify(storedHash, incomingHash);
        if (verdict == Verdict.REUSED) {
            throw AutoSpottingApiException.idempotencyKeyReused();
        }
        return verdict;
    }
}
