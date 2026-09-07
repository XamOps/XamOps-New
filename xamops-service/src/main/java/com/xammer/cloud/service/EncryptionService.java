package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * AES-256-GCM encryption for credentials held at rest.
 *
 * <p>Protects LLM provider API keys, AWS access/secret keys, GitHub PATs, Jenkins and SonarQube
 * tokens, CloudiDR tokens and key-pool entries.
 *
 * <p><b>Key rotation.</b> The ciphertext format is {@code base64(IV || ciphertext+tag)} with no key
 * identifier, so a single-key service can never rotate: changing the key makes every stored value
 * undecryptable at once. That mattered in August 2026, when this key was found committed in
 * plaintext across four properties files in a repository an attacker had read access to — it had to
 * be rotated, and rotating it would have broken every integration.
 *
 * <p>So decryption accepts a list of keys and encryption always uses the first. Rotation becomes:
 * put the new key in {@code app.encryption.secret-key}, move the old one to
 * {@code app.encryption.previous-keys}, deploy, re-encrypt stored values, then drop the old key.
 * Nothing breaks at any step. GCM authenticates, so a wrong key fails on the tag rather than
 * returning wrong plaintext, which is what makes trying keys in sequence safe.
 *
 * <p>Both properties should come from the environment or a secret store, never from a properties
 * file in the repository.
 */
@Service
public class EncryptionService {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionService.class);

    /** Current key. Used for all encryption. Required — startup fails without it. */
    @Value("${app.encryption.secret-key}")
    private String secretKeyString;

    /**
     * Retired keys, comma-separated, newest first. Decrypt-only: values still encrypted under them
     * keep working until re-encrypted. Empty in steady state.
     */
    @Value("${app.encryption.previous-keys:}")
    private String previousKeysString;

    private SecretKey primaryKey;
    /** Primary first, then retired keys, in the order decryption should try them. */
    private List<SecretKey> decryptionKeys = Collections.emptyList();

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96-bit IV, the GCM recommendation
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final String KEY_ALGORITHM = "AES";
    private static final int AES_256_KEY_BYTES = 32;

    @PostConstruct
    private void init() {
        this.primaryKey = parseKey(secretKeyString, "app.encryption.secret-key");

        List<SecretKey> keys = new ArrayList<>();
        keys.add(primaryKey);

        if (previousKeysString != null && !previousKeysString.isBlank()) {
            String[] parts = previousKeysString.split(",");
            for (int i = 0; i < parts.length; i++) {
                String raw = parts[i].trim();
                if (raw.isEmpty()) continue;
                keys.add(parseKey(raw, "app.encryption.previous-keys[" + i + "]"));
            }
        }

        this.decryptionKeys = List.copyOf(keys);
        if (decryptionKeys.size() > 1) {
            logger.warn("EncryptionService initialised with 1 active key and {} retired key(s). "
                    + "Values still encrypted under a retired key will keep working; re-encrypt them "
                    + "and then remove app.encryption.previous-keys.", decryptionKeys.size() - 1);
        } else {
            logger.info("EncryptionService initialised with 1 active key and no retired keys.");
        }
    }

    /**
     * Rejects anything that is not a 32-byte Base64 value, naming the property so a
     * misconfiguration is obvious. Fails startup rather than deferring to the first decrypt, where
     * it would surface as an unrelated integration error.
     */
    private SecretKey parseKey(String base64Key, String propertyName) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(propertyName + " is not configured. Supply it from the "
                    + "environment or a secret store — it must not be committed to a properties file.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " is not valid Base64.", e);
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            throw new IllegalStateException(propertyName + " decodes to " + decoded.length
                    + " bytes; AES-256 requires " + AES_256_KEY_BYTES + ".");
        }
        return new SecretKeySpec(decoded, 0, decoded.length, KEY_ALGORITHM);
    }

    /**
     * Encrypts with the current key.
     *
     * @return {@code base64(IV || ciphertext+tag)}, or null on failure.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || primaryKey == null) {
            logger.error("Plaintext or secret key is null, cannot encrypt.");
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, primaryKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherTextBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherTextBytes.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherTextBytes, 0, out, iv.length, cipherTextBytes.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            logger.error("Encryption failed: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Decrypts with the current key, falling back to retired keys.
     *
     * @return the plaintext, or null if no configured key can decrypt it.
     */
    public String decrypt(String base64CipherText) {
        if (base64CipherText == null || decryptionKeys.isEmpty()) {
            logger.error("Ciphertext or secret key is null, cannot decrypt.");
            return null;
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64CipherText);
        } catch (IllegalArgumentException e) {
            logger.error("Decryption failed - Base64 decoding error: {}", e.getMessage());
            return null;
        }
        if (decoded.length < GCM_IV_LENGTH) {
            logger.error("Invalid ciphertext length received for decryption.");
            return null;
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(decoded, 0, iv, 0, iv.length);
        byte[] encrypted = new byte[decoded.length - GCM_IV_LENGTH];
        System.arraycopy(decoded, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

        for (int i = 0; i < decryptionKeys.size(); i++) {
            try {
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, decryptionKeys.get(i),
                        new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                String plaintext = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
                if (i > 0) {
                    logger.debug("Value decrypted with retired key #{} — re-encrypt to retire it.", i);
                }
                return plaintext;
            } catch (Exception e) {
                // GCM tag mismatch means "not this key". Only the last failure is worth reporting;
                // logging each attempt would make one ordinary lookup look like an attack.
                if (i == decryptionKeys.size() - 1) {
                    logger.error("Decryption failed with all {} configured key(s). The value may "
                            + "predate the current keys, or the ciphertext may be corrupt.",
                            decryptionKeys.size());
                }
            }
        }
        return null;
    }

    /**
     * True when a value decrypts only under a retired key, so re-encrypting it would move it onto
     * the current one. Drives the rotation migration; also lets it be re-run safely, since already
     * migrated values return false.
     */
    public boolean needsReEncryption(String base64CipherText) {
        if (base64CipherText == null || base64CipherText.isBlank()) return false;
        if (decryptionKeys.size() <= 1) return false;
        return decryptWithPrimaryOnly(base64CipherText) == null && decrypt(base64CipherText) != null;
    }

    private String decryptWithPrimaryOnly(String base64CipherText) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64CipherText);
            if (decoded.length < GCM_IV_LENGTH) return null;
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, iv.length);
            byte[] encrypted = new byte[decoded.length - GCM_IV_LENGTH];
            System.arraycopy(decoded, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, primaryKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Re-encrypts a value under the current key, for the rotation migration.
     *
     * @return new ciphertext, or null if the value could not be decrypted with any key — in which
     *         case the caller must leave the stored value alone rather than overwrite it.
     */
    public String reEncrypt(String base64CipherText) {
        String plaintext = decrypt(base64CipherText);
        if (plaintext == null) return null;
        return encrypt(plaintext);
    }

    /** Number of keys accepted for decryption, active plus retired. */
    public int configuredKeyCount() {
        return decryptionKeys.size();
    }
}
