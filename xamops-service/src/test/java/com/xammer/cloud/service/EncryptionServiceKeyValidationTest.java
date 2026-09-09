package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers startup validation of the AES-256 key.
 *
 * This bean fail-fasts, which is correct -- booting without a working key would mean
 * handing out unencrypted credentials -- but it also means an unset key takes the entire
 * service down, and this exception is the only thing the operator sees. Both xamops-service
 * and billops crash-looped on exactly that after the key moved to an environment variable
 * that was never populated, and the message named neither the property nor where to set it.
 *
 * So what is asserted here is the diagnostic, not just the rejection: every failure has to
 * identify the property, the environment variable, and how to produce a valid value.
 */
class EncryptionServiceKeyValidationTest {

    /** Drives the @PostConstruct hook directly, since it is private. */
    private static void initWith(EncryptionService service, String key) throws Exception {
        Field f = EncryptionService.class.getDeclaredField("secretKeyString");
        f.setAccessible(true);
        f.set(service, key);
        Method init = EncryptionService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        try {
            init.invoke(service);
        } catch (InvocationTargetException e) {
            // Unwrap so callers assert on the real cause rather than the reflection wrapper.
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static String validKey() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    @Test
    @DisplayName("a valid 32-byte key initialises")
    void validKeyInitialises() {
        assertThatCode(() -> initWith(new EncryptionService(), validKey())).doesNotThrowAnyException();
    }

    /** The exact production failure: env var present but empty. */
    @Test
    @DisplayName("an empty key says it is NOT SET and names the env var")
    void emptyKeyIsReportedAsNotSet() {
        assertThatThrownBy(() -> initWith(new EncryptionService(), ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.encryption.secret-key")
                .hasMessageContaining("is not set")
                .hasMessageContaining("APP_ENCRYPTION_SECRET_KEY")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void blankKeyIsReportedAsNotSet() {
        assertThatThrownBy(() -> initWith(new EncryptionService(), "   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not set");
    }

    @Test
    void nullKeyIsReportedAsNotSet() {
        assertThatThrownBy(() -> initWith(new EncryptionService(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not set");
    }

    /** A wrong-length key is a different problem from an absent one, and must read that way. */
    @Test
    @DisplayName("a short key reports the actual byte count, not just 'invalid'")
    void shortKeyReportsItsLength()  {
        String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> initWith(new EncryptionService(), sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decodes to 16 byte(s)")
                .hasMessageContaining("requires exactly 32")
                .hasMessageContaining("APP_ENCRYPTION_SECRET_KEY");
    }

    @Test
    void nonBase64KeyIsReportedAsSuch() {
        assertThatThrownBy(() -> initWith(new EncryptionService(), "not!valid!base64!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
    }

    /**
     * A key pasted into a secret store commonly picks up a trailing newline, which is not
     * valid Base64. Tolerated rather than turned into a confusing startup failure.
     */
    @Test
    @DisplayName("surrounding whitespace on an otherwise valid key is tolerated")
    void surroundingWhitespaceIsTolerated() {
        assertThatCode(() -> initWith(new EncryptionService(), "\n" + validKey() + "\n"))
                .doesNotThrowAnyException();
    }

    /** The key must never reach a log or an exception message. */
    @Test
    @DisplayName("the key value is not echoed in failure messages")
    void keyValueIsNeverEchoed() {
        String sixteenBytes = Base64.getEncoder().encodeToString("SENSITIVE-KEY-16".getBytes());

        assertThatThrownBy(() -> initWith(new EncryptionService(), sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(sixteenBytes);
                    assertThat(e.getMessage()).doesNotContain("SENSITIVE-KEY-16");
                });
    }
}
