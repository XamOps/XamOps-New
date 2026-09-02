package com.xammer.cloud.domain.converter;

import com.xammer.cloud.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the at-rest encryption applied to the GCP service-account key and Azure
 * client secret.
 *
 * <p>
 * The legacy-plaintext case is the important one: rows written before the converter existed
 * are unencrypted, and if reads failed closed on them every existing GCP and Azure
 * integration would break the moment this deployed. That path is asserted here so a future
 * tightening of the converter cannot remove it silently — it has to fail this test first.
 */
class EncryptedStringConverterTest {

    private static final String KEY_B64 = "K13FNAPVAx9eUT0oJTI2jOL8JDcj3FUkUWSRVgoXSd0=";

    private EncryptedStringConverter converter;
    private EncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        encryptionService = new EncryptionService();
        ReflectionTestUtils.setField(encryptionService, "secretKeyString", KEY_B64);
        ReflectionTestUtils.invokeMethod(encryptionService, "init");

        converter = new EncryptedStringConverter(providerOf(encryptionService));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<EncryptionService> providerOf(EncryptionService service) {
        ObjectProvider<EncryptionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @Test
    void roundTripsAValue() {
        String secret = "{\"type\":\"service_account\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\"}";

        String stored = converter.convertToDatabaseColumn(secret);

        assertThat(stored).isNotNull();
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(secret);
    }

    @Test
    void storedFormIsNotThePlaintext() {
        String stored = converter.convertToDatabaseColumn("super-secret-value");

        // The whole point: what lands in the column must not contain the credential.
        assertThat(stored).doesNotContain("super-secret-value");
        assertThat(stored).startsWith("enc:v1:");
    }

    @Test
    void encryptionIsNonDeterministic() {
        // AES-GCM with a per-value random IV: the same input must not produce the same
        // ciphertext, otherwise equal credentials are identifiable in a dump.
        String a = converter.convertToDatabaseColumn("same-input");
        String b = converter.convertToDatabaseColumn("same-input");

        assertThat(a).isNotEqualTo(b);
        assertThat(converter.convertToEntityAttribute(a)).isEqualTo("same-input");
        assertThat(converter.convertToEntityAttribute(b)).isEqualTo("same-input");
    }

    @Test
    void readsLegacyPlaintextUnchanged() {
        // Rows that predate the converter. Must keep working or every existing GCP/Azure
        // integration breaks on deploy.
        String legacy = "{\"type\":\"service_account\"}";

        assertThat(converter.convertToEntityAttribute(legacy)).isEqualTo(legacy);
    }

    @Test
    void doesNotDoubleEncrypt() {
        String once = converter.convertToDatabaseColumn("value");

        assertThat(converter.convertToDatabaseColumn(once)).isEqualTo(once);
    }

    @Test
    void passesThroughNullAndEmpty() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn("")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }

    @Test
    void returnsNullWhenCiphertextWasWrittenWithADifferentKey() {
        String stored = converter.convertToDatabaseColumn("value");

        EncryptionService otherKey = new EncryptionService();
        ReflectionTestUtils.setField(otherKey, "secretKeyString",
                "dna/W6GjfQxNteV3LnOm1qqrE0Swvjwna2Kaag/NjMY=");
        ReflectionTestUtils.invokeMethod(otherKey, "init");
        EncryptedStringConverter mismatched = new EncryptedStringConverter(providerOf(otherKey));

        // Must not hand back the raw column: that would leak ciphertext into logs and API
        // responses as though it were the credential.
        assertThat(mismatched.convertToEntityAttribute(stored)).isNull();
    }

    @Test
    void refusesToWritePlaintextWhenEncryptionUnavailable() {
        EncryptedStringConverter unavailable = new EncryptedStringConverter(providerOf(null));

        assertThatThrownBy(() -> unavailable.convertToDatabaseColumn("secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to store");
    }
}
