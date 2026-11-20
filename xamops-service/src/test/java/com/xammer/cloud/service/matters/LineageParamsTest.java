package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineageParamsTest {

    private static void assertBadRequest(Runnable call, String parameter, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
            assertThat(e.parameter()).isEqualTo(parameter);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    @DisplayName("level is required and exactly policy or datastore")
    void level() {
        assertThat(LineageParams.level("policy")).isEqualTo("policy");
        assertThat(LineageParams.level("datastore")).isEqualTo("datastore");
        for (String bad : Arrays.asList("Policy", "file", "", null)) {
            assertBadRequest(() -> LineageParams.level(bad), "level", "level must be policy or datastore");
        }
    }

    @Test
    @DisplayName("exposureId takes 1 to 64 letters, digits or hyphens")
    void exposureId() {
        assertThat(LineageParams.exposureId("Abc-123")).isEqualTo("Abc-123");
        assertThat(LineageParams.exposureId("a".repeat(64))).hasSize(64);
        for (String bad : Arrays.asList("", "a".repeat(65), "a/b", "a_b", "a b", "a..b", null)) {
            assertBadRequest(() -> LineageParams.exposureId(bad), "exposureId",
                    "exposureId must be 1 to 64 letters, digits or hyphens");
        }
    }

    @Test
    @DisplayName("fileId takes 24 hex or a UUID, lower-cased")
    void fileId() {
        assertThat(LineageParams.fileId("FEDCBA9876543210FEDCBA98")).isEqualTo("fedcba9876543210fedcba98");
        assertThat(LineageParams.fileId("0000000A-0000-4000-8000-00000000000B"))
                .isEqualTo("0000000a-0000-4000-8000-00000000000b");
        for (String bad : Arrays.asList("fedcba9876543210fedcba9", "fedcba9876543210fedcba98g",
                "0000000a-0000-4000-8000-00000000000", "not-an-id", "", null)) {
            assertBadRequest(() -> LineageParams.fileId(bad), "fileId",
                    "fileId must be 24 hexadecimal characters or a UUID");
        }
    }

    @Test
    @DisplayName("limit is 1..max, the default when absent")
    void limit() {
        assertThat(LineageParams.limit(null, 50, 7)).isEqualTo(7);
        assertThat(LineageParams.limit("1", 50, 7)).isEqualTo(1);
        assertThat(LineageParams.limit("50", 50, 7)).isEqualTo(50);
        assertThat(LineageParams.limit("100", 100, 7)).isEqualTo(100);
        for (String bad : Arrays.asList("0", "51", "-1", "1.5", "", "abc", "0050")) {
            assertBadRequest(() -> LineageParams.limit(bad, 50, 7), "limit", "limit must be an integer from 1 to 50");
        }
        assertBadRequest(() -> LineageParams.limit("101", 100, 7), "limit", "limit must be an integer from 1 to 100");
    }
}
