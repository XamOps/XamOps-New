package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

@Service
public class EncryptionService {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionService.class);

    // Inject the secret key from application properties
    @Value("${app.encryption.secret-key}")
    private String secretKeyString;

    private SecretKey secretKey;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits for GCM IV
    private static final int GCM_TAG_LENGTH = 128; // GCM Authentication Tag length
    private static final String KEY_ALGORITHM = "AES";

    /** Environment variable the key is supplied through in every deployed profile. */
    private static final String KEY_ENV_VAR = "APP_ENCRYPTION_SECRET_KEY";
    private static final String KEY_PROPERTY = "app.encryption.secret-key";
    private static final String HOW_TO_GENERATE = "Generate one with: openssl rand -base64 32";

    /**
     * Validates the key and fails startup if it is unusable.
     *
     * Failing fast is correct -- a service that boots without a working key would hand
     * out unencrypted credentials -- but it means this message is the only thing an
     * operator sees when the whole service is crash-looping. It therefore names the
     * property, the environment variable that supplies it, and what a valid value looks
     * like, and distinguishes "not configured" from "configured but wrong".
     *
     * The earlier version reported only "Invalid secret key length ... got 0", which
     * gave no indication of which of several keys was empty or where it came from.
     *
     * The key itself is never logged.
     */
    @PostConstruct
    private void init() {
        if (secretKeyString == null || secretKeyString.isBlank()) {
            throw new IllegalStateException(String.format(
                    "%s is not set. Supply a 32-byte AES-256 key, Base64 encoded, via the %s "
                            + "environment variable. %s",
                    KEY_PROPERTY, KEY_ENV_VAR, HOW_TO_GENERATE));
        }

        byte[] decodedKey;
        try {
            // Trimmed because a key pasted into a secret store commonly carries a trailing
            // newline, which is not valid Base64 and would otherwise fail confusingly.
            decodedKey = Base64.getDecoder().decode(secretKeyString.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(String.format(
                    "%s is not valid Base64 (supplied via %s). %s",
                    KEY_PROPERTY, KEY_ENV_VAR, HOW_TO_GENERATE), e);
        }

        if (decodedKey.length != 32) {
            throw new IllegalStateException(String.format(
                    "%s decodes to %d byte(s); AES-256 requires exactly 32. Supplied via %s. %s",
                    KEY_PROPERTY, decodedKey.length, KEY_ENV_VAR, HOW_TO_GENERATE));
        }

        this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, KEY_ALGORITHM);
        logger.info("EncryptionService initialized (AES-256/GCM).");
    }

    /**
     * Encrypts plaintext using AES/GCM. Includes IV in the output.
     * @param plaintext The string to encrypt.
     * @return Base64 encoded ciphertext (IV + encrypted data + tag). Returns null on error.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || secretKey == null) {
            logger.error("Plaintext or secret key is null, cannot encrypt.");
            return null;
        }
        try {
            // 1. Generate a random IV (Initialization Vector)
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);

            // 2. Initialize Cipher for encryption
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmParameterSpec);

            // 3. Encrypt the data
            byte[] cipherTextBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 4. Combine IV and CipherText (IV is needed for decryption)
            // Output format: [IV (12 bytes)] + [CipherText]
            byte[] encryptedOutput = new byte[iv.length + cipherTextBytes.length];
            System.arraycopy(iv, 0, encryptedOutput, 0, iv.length);
            System.arraycopy(cipherTextBytes, 0, encryptedOutput, iv.length, cipherTextBytes.length);

            // 5. Base64 encode the combined output for storage
            return Base64.getEncoder().encodeToString(encryptedOutput);

        } catch (Exception e) {
            logger.error("Encryption failed: {}", e.getMessage(), e);
            return null; // Or throw a custom exception
        }
    }

    /**
     * Decrypts Base64 encoded ciphertext (IV + encrypted data + tag) using AES/GCM.
     * @param base64CipherText The Base64 encoded string from the encrypt method.
     * @return The original plaintext string. Returns null on error or if input is invalid.
     */
    public String decrypt(String base64CipherText) {
        if (base64CipherText == null || secretKey == null) {
             logger.error("Ciphertext or secret key is null, cannot decrypt.");
            return null;
        }
        try {
            // 1. Base64 decode the input
            byte[] decodedCipherText = Base64.getDecoder().decode(base64CipherText);

            // Ensure the decoded data is long enough to contain the IV
            if (decodedCipherText.length < GCM_IV_LENGTH) {
                logger.error("Invalid ciphertext length received for decryption.");
                return null;
            }

            // 2. Extract the IV from the beginning
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(decodedCipherText, 0, iv, 0, iv.length);

            // 3. Extract the actual encrypted data (after the IV)
            byte[] encryptedBytes = new byte[decodedCipherText.length - GCM_IV_LENGTH];
            System.arraycopy(decodedCipherText, GCM_IV_LENGTH, encryptedBytes, 0, encryptedBytes.length);

            // 4. Initialize Cipher for decryption
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);

            // 5. Decrypt the data
            byte[] plainTextBytes = cipher.doFinal(encryptedBytes);

            // 6. Convert back to String
            return new String(plainTextBytes, StandardCharsets.UTF_8);

        } catch (IllegalArgumentException e) {
             logger.error("Decryption failed - Base64 decoding error: {}", e.getMessage(), e);
             return null;
        } catch (Exception e) {
            logger.error("Decryption failed: {}", e.getMessage(), e);
            // Common reasons: incorrect key, tampered ciphertext (GCM tag mismatch), incorrect IV
            return null; // Or throw a custom exception
        }
    }
}