package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.3: the Remediation activity query, its vendor params and its cache fragment. */
class RemediationQueryTest {

    @Test
    @DisplayName("skip and limit always, then status, q and actionType when set")
    void vendorParams() {
        assertThat(new RemediationQuery(null, null, null, 0, 10).toVendorParams()).containsExactly(
                entry("skip", List.of("0")),
                entry("limit", List.of("10")));
        assertThat(new RemediationQuery("q4 plan", "FAILED", "MAKE_PRIVATE", 20, 10).toVendorParams()).containsExactly(
                entry("skip", List.of("20")),
                entry("limit", List.of("10")),
                entry("status", List.of("FAILED")),
                entry("q", List.of("q4 plan")),
                entry("actionType", List.of("MAKE_PRIVATE")));
    }

    @Test
    @DisplayName("the fragment leaves absent values out and URL-encodes the rest")
    void canonicalFragment() {
        assertThat(new RemediationQuery(null, null, null, 0, 10).canonicalFragment()).isEqualTo("skip=0&limit=10");
        assertThat(new RemediationQuery("a&status=b", "FAILED", null, 0, 10).canonicalFragment())
                .isEqualTo("skip=0&limit=10&status=FAILED&q=a%26status%3Db");
    }

    @Test
    @DisplayName("every field changes the fragment")
    void fragmentIdentity() {
        String base = new RemediationQuery(null, null, null, 0, 10).canonicalFragment();
        assertThat(new RemediationQuery("x", null, null, 0, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new RemediationQuery(null, "FAILED", null, 0, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new RemediationQuery(null, null, "DELETE_FILE", 0, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new RemediationQuery(null, null, null, 10, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new RemediationQuery(null, null, null, 0, 20).canonicalFragment()).isNotEqualTo(base);
    }
}
