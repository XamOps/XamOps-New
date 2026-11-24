package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.3: the Scan Management list queries, per kind. */
class ScanListQueryTest {

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    private static void assertRejected(Runnable call, String parameter) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DspmBadRequestException.class,
                e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    @Test
    @DisplayName("defaults: page 1, pageSize 10, no search, no filters")
    void defaults() {
        ScanListQuery query = ScanListQuery.from(ScanListQuery.Kind.SCHEDULES, params());
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(10);
        assertThat(query.search()).isNull();
        assertThat(query.filters()).isEmpty();
        assertThat(query.toVendorParams()).containsExactly(
                entry("page", List.of("1")),
                entry("page_size", List.of("10")));
    }

    @Test
    @DisplayName("schedules: page, page_size, q, then platform, status, coverage in that order")
    void schedules() {
        ScanListQuery query = ScanListQuery.from(ScanListQuery.Kind.SCHEDULES, params(
                "coverage", "ALL_DATASTORES", "q", " nightly ", "status", "ACTIVE", "platform", "on_prem",
                "page", "3", "pageSize", "25"));
        assertThat(query.toVendorParams()).containsExactly(
                entry("page", List.of("3")),
                entry("page_size", List.of("25")),
                entry("q", List.of("nightly")),
                entry("platform", List.of("on_prem")),
                entry("status", List.of("ACTIVE")),
                entry("coverage", List.of("ALL_DATASTORES")));
    }

    @Test
    @DisplayName("runs: coverage, platform, status, trigger; run datastores: account, status; scopes: platform")
    void otherKinds() {
        assertThat(ScanListQuery.from(ScanListQuery.Kind.RUNS, params(
                "trigger", "SCHEDULED", "platform", "endpoint", "coverage", "SELECTED_DATASTORES", "status", "FAILED"))
                .toVendorParams().keySet())
                .containsExactly("page", "page_size", "coverage", "platform", "status", "trigger");
        assertThat(ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params(
                "status", "SUCCESS", "account", " prod account ")).toVendorParams()).containsExactly(
                entry("page", List.of("1")),
                entry("page_size", List.of("10")),
                entry("account", List.of("prod account")),
                entry("status", List.of("SUCCESS")));
        assertThat(ScanListQuery.from(ScanListQuery.Kind.SCOPES, params("platform", "cloud")).filters())
                .containsExactly(entry("platform", "cloud"));
    }

    @Test
    @DisplayName("a filter of another kind is not parsed")
    void otherKindsFilterIgnored() {
        ScanListQuery scopes = ScanListQuery.from(ScanListQuery.Kind.SCOPES,
                params("status", "not a code", "trigger", "IMMEDIATE", "account", "x"));
        assertThat(scopes.filters()).isEmpty();
        ScanListQuery datastores = ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES,
                params("platform", "nowhere"));
        assertThat(datastores.filters()).isEmpty();
    }

    @Test
    @DisplayName("each rule rejects with the parameter's name")
    void rules() {
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.SCHEDULES, params("platform", "CLOUD")), "platform");
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.SCHEDULES, params("status", "active")), "status");
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.RUNS, params("trigger", "now!")), "trigger");
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params("account", "a,b")),
                "account");
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.RUNS, params("pageSize", "101")), "pageSize");
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.RUNS, params("q", "q".repeat(201))), "q");
        assertRejected(() -> ScanListQuery.from(ScanListQuery.Kind.RUNS, params("status", "A", "status", "B")),
                "status");
    }

    @Test
    @DisplayName("the fragment holds the vendor params, URL-encoded, and changes with each value")
    void canonicalFragment() {
        ScanListQuery base = ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params());
        assertThat(base.canonicalFragment()).isEqualTo("page=1&page_size=10");
        assertThat(ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params("account", "a&b=c"))
                .canonicalFragment()).isEqualTo("page=1&page_size=10&account=a%26b%3Dc");
        assertThat(ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params("q", "x")).canonicalFragment())
                .isNotEqualTo(base.canonicalFragment());
        assertThat(ScanListQuery.from(ScanListQuery.Kind.RUN_DATASTORES, params("page", "2")).canonicalFragment())
                .isNotEqualTo(base.canonicalFragment());
    }
}
