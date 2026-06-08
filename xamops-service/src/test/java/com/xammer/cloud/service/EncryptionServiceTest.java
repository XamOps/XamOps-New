package com.xammer.cloud.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EncryptionService (AES-256/GCM).
 *
 * Injects the secret key via ReflectionTestUtils to avoid needing Spring context.
 * Uses a deterministic 32-byte (256-bit) test key.
 */
class EncryptionServiceTest {

    private EncryptionService service;

    // 32 bytes Base64-encoded → AES-256 test key
    private static final String TEST_KEY_B64 =
            Base64.getEncoder().encodeToString(new byte[32]); // all-zero key for testing

    @BeforeEach
    void setUp() throws Exception {
        service = new EncryptionService();
        ReflectionTestUtils.setField(service, "secretKeyString", TEST_KEY_B64);
        // Manually call @PostConstruct
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    // ── Encrypt / Decrypt round-trip ──────────────────────────────────────────

    @Test
    void roundTrip_shortString() {
        String plaintext = "Hello, World!";
        String encrypted = service.encrypt(plaintext);
        assertThat(encrypted).isNotNull().isNotEqualTo(plaintext);
        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_emptyString() {
        String plaintext = "";
        String encrypted = service.encrypt(plaintext);
        assertThat(encrypted).isNotNull();
        assertThat(service.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_specialCharacters() {
        String plaintext = "!@#$%^&*()_+{}|:<>?~`-=[]\\;',./";
        assertThat(service.decrypt(service.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_longString() {
        String plaintext = "A".repeat(10_000);
        assertThat(service.decrypt(service.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_unicodeString() {
        String plaintext = "こんにちは世界 🌍 café naïve résumé";
        assertThat(service.decrypt(service.encrypt(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_apiKey() {
        String apiKey = "sk-proj-abc123XYZ-someLongApiKeyValue_0987654321";
        assertThat(service.decrypt(service.encrypt(apiKey))).isEqualTo(apiKey);
    }

    // ── Encrypt produces different ciphertexts each time (random IV) ──────────

    @Test
    void encrypt_sameInputProducesDifferentCiphertexts() {
        String plaintext = "same-plaintext";
        String ct1 = service.encrypt(plaintext);
        String ct2 = service.encrypt(plaintext);
        // Different IVs → different ciphertexts
        assertThat(ct1).isNotEqualTo(ct2);
        // Both decrypt to same plaintext
        assertThat(service.decrypt(ct1)).isEqualTo(plaintext);
        assertThat(service.decrypt(ct2)).isEqualTo(plaintext);
    }

    // ── Output is valid Base64 ────────────────────────────────────────────────

    @Test
    void encrypt_outputIsBase64() {
        String encrypted = service.encrypt("test");
        assertThat(encrypted).isNotNull();
        // Should not throw
        Base64.getDecoder().decode(encrypted);
    }

    @Test
    void encrypted_lengthIsGreaterThanIvLength() {
        String encrypted = service.encrypt("test");
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        // IV is 12 bytes + at least some ciphertext
        assertThat(decoded.length).isGreaterThan(12);
    }

    // ── Null safety ───────────────────────────────────────────────────────────

    @Test
    void encrypt_nullInput_returnsNull() {
        assertThat(service.encrypt(null)).isNull();
    }

    @Test
    void decrypt_nullInput_returnsNull() {
        assertThat(service.decrypt(null)).isNull();
    }

    // ── Tampered ciphertext ───────────────────────────────────────────────────

    @Test
    void decrypt_tamperedCiphertext_returnsNull() {
        String encrypted = service.encrypt("original-secret");
        // Flip a character to simulate tampering
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "XXXX";
        assertThat(service.decrypt(tampered)).isNull();
    }

    @Test
    void decrypt_tooShortInput_returnsNull() {
        // Base64 of only 5 bytes — shorter than the 12-byte IV
        String tooShort = Base64.getEncoder().encodeToString(new byte[5]);
        assertThat(service.decrypt(tooShort)).isNull();
    }

    @Test
    void decrypt_completelyInvalidBase64_returnsNull() {
        assertThat(service.decrypt("not-valid-base64!!!")).isNull();
    }
}
