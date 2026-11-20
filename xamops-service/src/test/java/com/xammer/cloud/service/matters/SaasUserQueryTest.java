package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.3: the SaaS users query; skip is a page index (page - 1). */
class SaasUserQueryTest {

    @Test
    @DisplayName("driveType, limit = pageSize, skip = page - 1, then search, status, domain when set")
    void vendorParams() {
        assertThat(new SaasUserQuery("GMAIL", null, null, null, 1, 10).toVendorParams()).containsExactly(
                entry("driveType", List.of("GMAIL")),
                entry("limit", List.of("10")),
                entry("skip", List.of("0")));
        assertThat(new SaasUserQuery("GOOGLE_WORKSPACE", "ann", "SUSPENDED", "example.test", 3, 25).toVendorParams())
                .containsExactly(
                        entry("driveType", List.of("GOOGLE_WORKSPACE")),
                        entry("limit", List.of("25")),
                        entry("skip", List.of("2")),
                        entry("search", List.of("ann")),
                        entry("status", List.of("SUSPENDED")),
                        entry("domain", List.of("example.test")));
    }

    @Test
    @DisplayName("driveType is required")
    void driveTypeRequired() {
        assertThatThrownBy(() -> new SaasUserQuery(null, null, null, null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the fragment URL-encodes values and changes with each field")
    void canonicalFragment() {
        String base = new SaasUserQuery("GMAIL", null, null, null, 1, 10).canonicalFragment();
        assertThat(base).isEqualTo("driveType=GMAIL&limit=10&skip=0");
        assertThat(new SaasUserQuery("GMAIL", "a b", null, null, 1, 10).canonicalFragment())
                .isEqualTo("driveType=GMAIL&limit=10&skip=0&search=a+b");
        assertThat(new SaasUserQuery("SLACK", null, null, null, 1, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasUserQuery("GMAIL", null, "ACTIVE", null, 1, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasUserQuery("GMAIL", null, null, "x.test", 1, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasUserQuery("GMAIL", null, null, null, 2, 10).canonicalFragment()).isNotEqualTo(base);
    }
}
