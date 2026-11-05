package com.xammer.cloud.autospotting.core;

import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.IdempotencyService;
import com.xammer.cloud.service.autospotting.core.IdempotencyService.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link IdempotencyService} (plan-phase-09.md task 9.2, design-core.md §3.7, §11):
 * a required key, the canonical-body SHA-256, and the replay-vs-reuse verdict.
 */
class IdempotencyServiceTest {

    private final IdempotencyService service = new IdempotencyService();

    @Test
    void requireKey_missingOrBlank_is400() {
        for (String bad : new String[] { null, "", "   " }) {
            assertThatThrownBy(() -> service.requireKey(bad))
                    .isInstanceOf(AutoSpottingApiException.class)
                    .satisfies(e -> {
                        AutoSpottingApiException ex = (AutoSpottingApiException) e;
                        assertThat(ex.getStatus().value()).isEqualTo(400);
                        assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
                    });
        }
    }

    @Test
    void requireKey_trimsAndReturns() {
        assertThat(service.requireKey("  key-123 ")).isEqualTo("key-123");
    }

    @Test
    void hash_isDeterministicHexSha256_andBodySensitive() {
        String a = service.hash("{\"engaged\":true}");
        String b = service.hash("{\"engaged\":true}");
        String c = service.hash("{\"engaged\":false}");
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a).isNotEqualTo(c);
        // Known vector: SHA-256("") is the well-known empty-string digest.
        assertThat(service.hash(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(service.hash(null)).isEqualTo(service.hash(""));
    }

    @Test
    void classify_newReplayReused() {
        assertThat(service.classify(null, "h1")).isEqualTo(Verdict.NEW);
        assertThat(service.classify("h1", "h1")).isEqualTo(Verdict.REPLAY);
        assertThat(service.classify("h1", "h2")).isEqualTo(Verdict.REUSED);
    }

    @Test
    void requireSameBody_mismatch_is422_reuse() {
        assertThatThrownBy(() -> service.requireSameBody("h1", "h2"))
                .isInstanceOf(AutoSpottingApiException.class)
                .satisfies(e -> {
                    AutoSpottingApiException ex = (AutoSpottingApiException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
    }

    @Test
    void requireSameBody_newAndReplay_pass() {
        assertThat(service.requireSameBody(null, "h1")).isEqualTo(Verdict.NEW);
        assertThat(service.requireSameBody("h1", "h1")).isEqualTo(Verdict.REPLAY);
    }
}
