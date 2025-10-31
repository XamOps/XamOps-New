package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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

    // Initialize the SecretKey once on startup
    @PostConstruct
    private void init() {
        try {
            // Decode the Base64 key string into bytes
            byte[] decodedKey = Base64.getDecoder().decode(secretKeyString);
            if (decodedKey.length != 32) { // Check for AES-256 key length
                logger.error("Invalid secret key length. Expected 32 bytes for AES-256, but got {}.", decodedKey.length);
                throw new IllegalArgumentException("Invalid secret key length. Requires a 32-byte (Base64 encoded) key for AES-256.");
            }
            this.secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, KEY_ALGORITHM);
            logger.info("EncryptionService initialized successfully.");
        } catch (IllegalArgumentException e) {
             logger.error("Error decoding Base64 secret key: {}", e.getMessage(), e);
             // Fail fast if the key is invalid
             throw new IllegalStateException("Failed to initialize EncryptionService due to invalid secret key.", e);
        } catch (Exception e) {
            logger.error("Unexpected error initializing EncryptionService: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize EncryptionService.", e);
        }
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