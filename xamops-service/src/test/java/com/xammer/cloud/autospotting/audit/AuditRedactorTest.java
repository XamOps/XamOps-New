package com.xammer.cloud.autospotting.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.service.autospotting.core.audit.AuditRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditRedactor} unit tests (plan-phase-05.md task 5.3, FR-SEC-06, AC-SEC-07). The fixtures
 * carry the four secret shapes the plan names — an {@code AKIA...} access key id, a 40-char secret,
 * a PEM private-key block and a bearer token — and every one is masked, whether it sits in a
 * sensitive-named field, a free-text field, a JSON array or a plain message.
 */
class AuditRedactorTest {

    private static final String ACCESS_KEY_ID = "AKIAIOSFODNN7EXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"; // 40 chars
    private static final String BEARER = "Bearer ya29.a0AfH6SMByExampleTokenValue12345";
    private static final String PEM =
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----";

    private AuditRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new AuditRedactor(new ObjectMapper());
    }

    @Test
    void masksSensitiveNamedFields() {
        String json = "{\"accessKeyId\":\"" + ACCESS_KEY_ID + "\","
                + "\"secretAccessKey\":\"" + SECRET_KEY + "\","
                + "\"sessionToken\":\"abc\","
                + "\"Authorization\":\"" + BEARER + "\"}";
        String out = redactor.redactJson(json);
        assertThat(out).doesNotContain(ACCESS_KEY_ID);
        assertThat(out).doesNotContain(SECRET_KEY);
        assertThat(out).doesNotContain("ya29.");
        assertThat(out).contains(AuditRedactor.MASK);
    }

    @Test
    void masksSecretValuesInNonSensitiveFields() {
        // A secret pasted into a free-text field (e.g. a reason or a note) is still caught by value.
        String json = "{\"note\":\"the key is " + ACCESS_KEY_ID + " please rotate\","
                + "\"detail\":\"" + SECRET_KEY + "\"}";
        String out = redactor.redactJson(json);
        assertThat(out).doesNotContain(ACCESS_KEY_ID);
        assertThat(out).doesNotContain(SECRET_KEY);
    }

    @Test
    void masksPemPrivateKeyBlock() {
        String json = "{\"privateKey\":\"" + PEM.replace("\n", "\\n") + "\"}";
        String out = redactor.redactJson(json);
        assertThat(out).doesNotContain("MIIEpAIBAAKCAQEA");
        assertThat(out).contains(AuditRedactor.MASK);
    }

    @Test
    void masksSecretsInsideArrays() {
        String json = "{\"items\":[\"" + ACCESS_KEY_ID + "\",\"harmless\"]}";
        String out = redactor.redactJson(json);
        assertThat(out).doesNotContain(ACCESS_KEY_ID);
        assertThat(out).contains("harmless");
    }

    @Test
    void masksSecretsInsideNestedObjects() {
        String json = "{\"outer\":{\"inner\":{\"secretKey\":\"" + SECRET_KEY + "\"}}}";
        String out = redactor.redactJson(json);
        assertThat(out).doesNotContain(SECRET_KEY);
    }

    @Test
    void redactsAPlainMessage() {
        String message = "AWS rejected the call with key " + ACCESS_KEY_ID + " and " + BEARER;
        String out = redactor.redactMessage(message);
        assertThat(out).doesNotContain(ACCESS_KEY_ID);
        assertThat(out).doesNotContain("ya29.");
        assertThat(out).contains(AuditRedactor.MASK);
    }

    @Test
    void nonJsonInputIsTreatedAsAMessage() {
        // A raw provider error body that isn't JSON is still scrubbed.
        String body = "ServiceException: access denied for " + ACCESS_KEY_ID;
        String out = redactor.redactJson(body);
        assertThat(out).doesNotContain(ACCESS_KEY_ID);
    }

    @Test
    void nullInputStaysNull() {
        assertThat(redactor.redactJson(null)).isNull();
        assertThat(redactor.redactMessage(null)).isNull();
    }

    @Test
    void harmlessContentSurvives() {
        String json = "{\"groupId\":\"abc-123\",\"state\":\"ACTIVE\",\"count\":4}";
        String out = redactor.redactJson(json);
        assertThat(out).contains("abc-123");
        assertThat(out).contains("ACTIVE");
        assertThat(out).contains("4");
        assertThat(out).doesNotContain(AuditRedactor.MASK);
    }
}
