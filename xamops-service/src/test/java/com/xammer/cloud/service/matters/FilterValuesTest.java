package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design 5.3: the multi-value filter rule shared by the Endpoint drawer and the Files page. */
class FilterValuesTest {

    private static MultiValueMap<String, String> params(String... namesAndValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            params.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return params;
    }

    private static void assertRejected(MultiValueMap<String, String> params, String name, FilterValues.Rule rule,
                                       String message) {
        assertThatThrownBy(() -> FilterValues.parse(params, name, rule))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo(name);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    @Test
    @DisplayName("occurrences are split on commas, trimmed, de-duplicated and sorted; absent is an empty list")
    void splitting() {
        assertThat(FilterValues.parse(params("tag", "b, a", "tag", "a,,c "), "tag", FilterValues.Rule.TEXT))
                .containsExactly("a", "b", "c");
        assertThat(FilterValues.parse(params("q", "x"), "tag", FilterValues.Rule.TEXT)).isEmpty();
    }

    @Test
    @DisplayName("present with no value left, or a 51st distinct value, is '<name> needs 1 to 50 values'")
    void valueCount() {
        assertRejected(params("category", " , "), "category", FilterValues.Rule.TEXT, "category needs 1 to 50 values");
        String fifty = IntStream.range(0, 50).mapToObj(i -> "v" + i).collect(Collectors.joining(","));
        assertThat(FilterValues.parse(params("category", fifty + ",v0"), "category", FilterValues.Rule.TEXT))
                .hasSize(50);
        assertRejected(params("category", fifty + ",v50"), "category", FilterValues.Rule.TEXT,
                "category needs 1 to 50 values");
    }

    @Test
    @DisplayName("TEXT and CODE take 128 characters, EMAIL 320; a control character is rejected; the message "
            + "takes the limit from the rule and never holds the value")
    void lengthByRule() {
        assertThat(FilterValues.parse(params("hostname", "h".repeat(128)), "hostname", FilterValues.Rule.TEXT))
                .hasSize(1);
        assertRejected(params("hostname", "h".repeat(129)), "hostname", FilterValues.Rule.TEXT,
                "hostname values must be 1 to 128 characters without control characters");
        assertRejected(params("tag", "a\u0007b"), "tag", FilterValues.Rule.TEXT,
                "tag values must be 1 to 128 characters without control characters");
        String email = "a".repeat(308) + "@example.com";
        assertThat(FilterValues.parse(params("externalUser", email), "externalUser", FilterValues.Rule.EMAIL))
                .containsExactly(email);
        assertRejected(params("externalUser", "a" + email), "externalUser", FilterValues.Rule.EMAIL,
                "externalUser values must be 1 to 320 characters without control characters");
        assertRejected(params("appType", "C".repeat(129)), "appType", FilterValues.Rule.CODE,
                "appType values must be 1 to 128 characters without control characters");
    }

    @Test
    @DisplayName("CODE needs upper-case codes, checked after the length")
    void codes() {
        assertThat(FilterValues.parse(params("appType", "GMAIL,GOOGLE_WORKSPACE"), "appType", FilterValues.Rule.CODE))
                .containsExactly("GMAIL", "GOOGLE_WORKSPACE");
        assertRejected(params("appType", "gmail"), "appType", FilterValues.Rule.CODE,
                "appType values must be upper-case codes of letters, digits and underscores");
        assertRejected(params("sensitives", "PII-1"), "sensitives", FilterValues.Rule.CODE,
                "sensitives values must be upper-case codes of letters, digits and underscores");
    }

    @Test
    @DisplayName("HEX24 needs 24 hexadecimal characters and lower-cases them before de-duplicating")
    void hex24() {
        assertThat(FilterValues.parse(params("owner", "0A0B0C0D0E0F101112131415,0a0b0c0d0e0f101112131415"), "owner",
                FilterValues.Rule.HEX24)).containsExactly("0a0b0c0d0e0f101112131415");
        assertRejected(params("owner", "ana@example.com"), "owner", FilterValues.Rule.HEX24,
                "owner values must be 24 hexadecimal characters");
        assertRejected(params("owner", "0a0b0c0d0e0f10111213141"), "owner", FilterValues.Rule.HEX24,
                "owner values must be 24 hexadecimal characters");
    }
}
