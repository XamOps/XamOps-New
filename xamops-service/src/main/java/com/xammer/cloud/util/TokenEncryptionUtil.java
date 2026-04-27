package com.xammer.cloud.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility for Authentik API tokens stored in the database.
 *
 * Storage format: Base64( IV[12 bytes] || CipherText || AuthTag[16 bytes] )
 *
 * The encryption key is a Base64-encoded 32-byte value stored in the
 * IDENTITY_ENCRYPTION_KEY environment variable (configured in application.properties
 * as identity.encryption-key=${IDENTITY_ENCRYPTION_KEY}).
 *
 * To generate a key:
 *   openssl rand -base64 32
 *
 * Token values are never logged. This class performs no I/O and has no Spring
 * dependencies so it can be used as a static utility anywhere in the backend.
 */
public final class TokenEncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private TokenEncryptionUtil() {}

    public static String encrypt(String plaintext, String base64Key) {
        try {
            byte[] key = Base64.getDecoder().decode(base64Key);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV so it can be extracted during decryption
            byte[] combined = new byte[IV_LENGTH_BYTES + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(cipherText, 0, combined, IV_LENGTH_BYTES, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Token encryption failed", e);
        }
    }

    public static String decrypt(String encryptedBase64, String base64Key) {
        try {
            byte[] key = Base64.getDecoder().decode(base64Key);
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);

            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] cipherText = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(cipherText), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Token decryption failed", e);
        }
    }
}
