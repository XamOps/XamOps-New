package com.xammer.cloud.util;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * Generation and comparison of the 6-digit codes used by password reset and login 2FA.
 *
 * <p>
 * Both call sites previously used {@code new Random().nextInt(999999)}. That is wrong twice
 * over. {@link java.util.Random} is a linear congruential generator seeded from the clock,
 * so observing one code narrows the seed and makes the next ones predictable — an attacker
 * who can trigger a reset for their own account can derive the code issued to someone
 * else's moments later. And {@code nextInt(999999)} is exclusive of its bound, so 999999
 * never occurred and the space was one short of the six digits it appeared to be.
 *
 * <p>
 * {@link SecureRandom} fixes the predictability. The range is expressed as
 * {@code nextInt(1_000_000)} so all 10^6 codes are reachable.
 *
 * <p>
 * 10^6 is only ~20 bits, which is not much on its own; a six-digit code is only safe when
 * attempts are capped. The cap lives in the callers (see
 * {@code RedisCacheService.registerOtpFailure}) because it needs the shared Redis state.
 * Widening the code instead was rejected: users type these by hand, and an attempt limit
 * is the control that actually bounds the attack.
 */
public final class OtpCodes {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private OtpCodes() {
    }

    /** A uniformly random code in {@code 000000}–{@code 999999}, zero-padded. */
    public static String generate() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /**
     * Constant-time comparison, so response timing does not leak how many leading digits
     * were correct. Marginal next to the attempt cap, but this is the cheap half of the fix
     * and {@code String.equals} short-circuits on the first mismatched character.
     *
     * <p>
     * Null-safe and fails closed: a missing or expired code never matches.
     */
    public static boolean matches(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
