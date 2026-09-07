package com.xammer.cloud.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * AES-256-GCM encryption utility for Authentik API tokens stored in the database.
 *
 * Storage format: Base64( IV[12 bytes] || CipherText || AuthTag[16 bytes] )
 *
 * The encryption key is a Base64-encoded 32-byte value supplied as
 * {@code identity.encryption-key}, which must come from the environment or a secret store.
 *
 * To generate a key:
 *   openssl rand -base64 32
 *
 * <p><b>Key rotation.</b> The ciphertext carries no key identifier, so a single-key utility can
 * never rotate: a new key makes every stored token undecryptable at once. This key was found
 * committed in plaintext in a repository an attacker had read access to, so it has to be rotated.
 * The {@code decrypt} overload taking {@code previousKeysCsv} accepts retired keys for reading
 * while {@code encrypt} always uses the current key, so rotation is: set the new key, move the old
 * one to {@code identity.encryption-previous-keys}, deploy, re-encrypt, then drop the old key.
 * GCM authenticates, so a wrong key fails on the tag rather than returning wrong plaintext — which
 * is what makes trying keys in sequence safe.
 *
 * Token values are never logged. This class performs no I/O and has no Spring
 * dependencies so it can be used as a static utility anywhere in the backend.
 */
public final class TokenEncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int AES_256_KEY_BYTES = 32;

    private TokenEncryptionUtil() {}

    /** Encrypts with the current key. */
    public static String encrypt(String plaintext, String base64Key) {
        try {
            byte[] key = decodeKey(base64Key, "identity.encryption-key");
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV so it can be extracted during decryption
            byte[] combined = new byte[IV_LENGTH_BYTES + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(cipherText, 0, combined, IV_LENGTH_BYTES, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (IllegalArgumentException e) {
            throw e; // a misconfigured key, already described
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    /** Decrypts with a single key. Retained so existing callers are unaffected. */
    public static String decrypt(String encryptedBase64, String base64Key) {
        return decrypt(encryptedBase64, base64Key, null);
    }

    /**
     * Decrypts with the current key, falling back to retired keys.
     *
     * @param previousKeysCsv comma-separated retired keys, newest first; null or blank for none
     * @throws IllegalStateException if no configured key can decrypt the value
     */
    public static String decrypt(String encryptedBase64, String base64Key, String previousKeysCsv) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            throw new IllegalStateException("Token decryption failed: no ciphertext supplied");
        }

        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(encryptedBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Token decryption failed: ciphertext is not valid Base64", e);
        }
        if (combined.length <= IV_LENGTH_BYTES) {
            throw new IllegalStateException("Token decryption failed: ciphertext is too short to contain an IV");
        }

        byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
        byte[] cipherText = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

        List<byte[]> keys = keyChain(base64Key, previousKeysCsv);
        for (byte[] key : keys) {
            try {
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_LENGTH_BITS, iv));
                return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
            } catch (Exception e) {
                // GCM tag mismatch means "not this key" — try the next one. The message below
                // deliberately says nothing about the token itself.
            }
        }
        throw new IllegalStateException("Token decryption failed with all " + keys.size()
                + " configured key(s). The value may predate the current keys, or be corrupt.");
    }

    /**
     * True when a value decrypts only under a retired key, so re-encrypting moves it onto the
     * current one. Drives the rotation migration and makes re-runs safe.
     */
    public static boolean needsReEncryption(String encryptedBase64, String base64Key, String previousKeysCsv) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) return false;
        if (previousKeysCsv == null || previousKeysCsv.isBlank()) return false;
        try {
            decrypt(encryptedBase64, base64Key, null);
            return false; // already readable with the current key
        } catch (RuntimeException notCurrent) {
            try {
                decrypt(encryptedBase64, base64Key, previousKeysCsv);
                return true; // readable, but only with a retired key
            } catch (RuntimeException unreadable) {
                return false; // no key works; leave it alone rather than destroy it
            }
        }
    }

    /**
     * Re-encrypts a value under the current key.
     *
     * @return new ciphertext, or null if no configured key could decrypt it — in which case the
     *         caller must leave the stored value alone rather than overwrite it.
     */
    public static String reEncrypt(String encryptedBase64, String base64Key, String previousKeysCsv) {
        String plaintext;
        try {
            plaintext = decrypt(encryptedBase64, base64Key, previousKeysCsv);
        } catch (RuntimeException e) {
            return null;
        }
        return encrypt(plaintext, base64Key);
    }

    /** Current key first, then any retired keys, in the order decryption should try them. */
    private static List<byte[]> keyChain(String base64Key, String previousKeysCsv) {
        List<byte[]> keys = new ArrayList<>();
        keys.add(decodeKey(base64Key, "identity.encryption-key"));
        if (previousKeysCsv != null && !previousKeysCsv.isBlank()) {
            String[] parts = previousKeysCsv.split(",");
            for (int i = 0; i < parts.length; i++) {
                String raw = parts[i].trim();
                if (raw.isEmpty()) continue;
                keys.add(decodeKey(raw, "identity.encryption-previous-keys[" + i + "]"));
            }
        }
        return keys;
    }

    /**
     * Validates the key before use. Previously a missing or wrong-length key surfaced as
     * "Token decryption failed", which reads like corrupt data rather than a misconfiguration.
     */
    private static byte[] decodeKey(String base64Key, String propertyName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException(propertyName + " is not configured. Supply it from "
                    + "the environment or a secret store — it must not be committed to a properties file.");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(propertyName + " is not valid Base64.", e);
        }
        if (key.length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException(propertyName + " decodes to " + key.length
                    + " bytes; AES-256 requires " + AES_256_KEY_BYTES + ".");
        }
        return key;
    }
}
