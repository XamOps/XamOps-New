package com.xammer.cloud.domain.converter;

import com.xammer.cloud.service.EncryptionService;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Encrypts long-lived cloud credentials at rest.
 *
 * <p>
 * The GCP service-account JSON key and the Azure client secret were stored as plaintext.
 * Unlike AWS, where XamOps holds only a role ARN and mints short-lived STS sessions, these
 * are standing credentials with no expiry: a database dump, a read-replica snapshot, or a
 * backup leak hands over persistent access to every GCP project and Azure subscription
 * onboarded to the platform. An {@link EncryptionService} already existed and was used
 * elsewhere; it was simply never applied to these columns.
 *
 * <h2>Why an AttributeConverter</h2>
 * Encrypting at the persistence boundary means every read and write path is covered by
 * construction — including the ones that bypass the service layer. Doing it in the client
 * providers instead would have left any other query returning plaintext, and would need
 * revisiting each time a new caller appears.
 *
 * <h2>Legacy plaintext</h2>
 * {@link #convertToEntityAttribute} falls back to returning the stored value unchanged when
 * it does not decrypt. Rows written before this change are plaintext, and failing them
 * closed would break every existing GCP and Azure integration the moment this deploys.
 * Each row upgrades itself the next time it is written.
 *
 * <p>
 * That fallback is a migration affordance, not the end state: while it is in place, an
 * attacker who can write to the column could replace ciphertext with plaintext and have it
 * accepted. Existing rows should be re-encrypted with a one-off backfill and this fallback
 * then removed — tracked separately because it needs a migration window, not a code change.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

    /**
     * Marks values this converter produced, so reads can tell ciphertext from legacy
     * plaintext without relying on "did decryption throw", which cannot distinguish
     * "plaintext" from "wrong key" — and treating a key mismatch as plaintext would hand
     * out ciphertext as if it were a credential.
     */
    private static final String PREFIX = "enc:v1:";

    /**
     * Resolved lazily. Hibernate builds converters during EntityManagerFactory startup,
     * which can precede the EncryptionService bean being available; an ObjectProvider defers
     * the lookup to first use instead of creating a startup ordering dependency.
     */
    private final ObjectProvider<EncryptionService> encryptionServiceProvider;

    public EncryptedStringConverter(ObjectProvider<EncryptionService> encryptionServiceProvider) {
        this.encryptionServiceProvider = encryptionServiceProvider;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        if (attribute.startsWith(PREFIX)) {
            // Already ciphertext; re-encrypting would double-wrap.
            return attribute;
        }

        EncryptionService encryptionService = encryptionServiceProvider.getIfAvailable();
        if (encryptionService == null) {
            // Refuse rather than silently persisting plaintext. A missing or misconfigured
            // encryption key is a deployment fault, and writing the credential in the clear
            // is exactly the outcome this class exists to prevent.
            throw new IllegalStateException(
                    "EncryptionService unavailable — refusing to store a cloud credential unencrypted");
        }

        String ciphertext = encryptionService.encrypt(attribute);
        if (ciphertext == null) {
            throw new IllegalStateException(
                    "Failed to encrypt a cloud credential — refusing to store it unencrypted");
        }
        return PREFIX + ciphertext;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        if (!dbData.startsWith(PREFIX)) {
            // Legacy plaintext row, written before this converter existed.
            return dbData;
        }

        EncryptionService encryptionService = encryptionServiceProvider.getIfAvailable();
        if (encryptionService == null) {
            log.error("EncryptionService unavailable — cannot decrypt a stored cloud credential");
            return null;
        }

        String plaintext = encryptionService.decrypt(dbData.substring(PREFIX.length()));
        if (plaintext == null) {
            // Wrong key, or the value was tampered with (GCM tag mismatch). Returning null
            // surfaces as a failed cloud connection, which is the safe outcome; returning the
            // raw column would leak ciphertext into logs and API responses.
            log.error("Failed to decrypt a stored cloud credential — check that "
                    + "app.encryption.secret-key matches the key the value was written with");
            return null;
        }
        return plaintext;
    }
}
