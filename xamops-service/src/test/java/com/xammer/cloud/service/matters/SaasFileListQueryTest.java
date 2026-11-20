package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** F1's query (design 5.3 as changed by plan C3 to C5). Values are decoded, as Spring binds them. */
class SaasFileListQueryTest {

    private static final String OWNER_A = "0a0b0c0d0e0f101112131415";
    private static final String OWNER_B = "1a1b1c1d1e1f202122232425";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static MultiValueMap<String, String> params(String... namesAndValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            params.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return params;
    }

    private static void assertRejected(MultiValueMap<String, String> params, String parameter) {
        assertThatThrownBy(() -> SaasFileListQuery.parse(params))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    @Test
    @DisplayName("no parameter: limit 10, first page, no filter; only limit is sent")
    void defaults() {
        SaasFileListQuery query = SaasFileListQuery.parse(params());
        assertThat(query.limit()).isEqualTo(10);
        assertThat(query.cursor()).isNull();
        assertThat(query.documentClass()).isNull();
        assertThat(query.toVendorParams(objectMapper)).containsExactly(entry("limit", List.of("10")));
    }

    @Test
    @DisplayName("every parameter maps to its vendor name in order: subcategory plain, lists as JSON arrays, "
            + "one share flag; owners keep both ids, lower-cased and sorted")
    void vendorParams() {
        SaasFileListQuery query = SaasFileListQuery.parse(params(
                "limit", "25", "cursor", "ab+c/d==", "q", " q4 plan ", "sensitives", "SECRETS,PII",
                "documentClass", " Travel Receipts ", "sharing", "internal", "category", "financial",
                "appType", "GMAIL", "owner", OWNER_B.toUpperCase() + "," + OWNER_A,
                "internalUser", "ana@example.com", "externalUser", "lee@example.org"));
        assertThat(query.owners()).containsExactly(OWNER_A, OWNER_B);
        assertThat(query.toVendorParams(objectMapper)).containsExactly(
                entry("limit", List.of("25")),
                entry("cursor", List.of("ab+c/d==")),
                entry("name_search_str", List.of("q4 plan")),
                entry("label", List.of("[\"PII\",\"SECRETS\"]")),
                entry("subcategory", List.of("Travel Receipts")),
                entry("category", List.of("[\"financial\"]")),
                entry("source", List.of("[\"GMAIL\"]")),
                entry("owners", List.of("[\"" + OWNER_A + "\",\"" + OWNER_B + "\"]")),
                entry("internal_users", List.of("[\"ana@example.com\"]")),
                entry("external_users", List.of("[\"lee@example.org\"]")),
                entry("internal_share", List.of("true")));
    }

    @Test
    @DisplayName("sharing external and public give external_share=true and public_share=true")
    void shareFlags() {
        assertThat(SaasFileListQuery.parse(params("sharing", "external")).toVendorParams(objectMapper))
                .containsEntry("external_share", List.of("true")).doesNotContainKeys("internal_share", "public_share");
        assertThat(SaasFileListQuery.parse(params("sharing", "public")).toVendorParams(objectMapper))
                .containsEntry("public_share", List.of("true"));
    }

    @Test
    @DisplayName("documentClass is one value: a comma stays part of it; blank is absent; twice or over 128 is a 400")
    void documentClass() {
        assertThat(SaasFileListQuery.parse(params("documentClass", "Media, R&D")).documentClass())
                .isEqualTo("Media, R&D");
        assertThat(SaasFileListQuery.parse(params("documentClass", "  ")).documentClass()).isNull();
        assertRejected(params("documentClass", "a", "documentClass", "b"), "documentClass");
        assertRejected(params("documentClass", "d".repeat(129)), "documentClass");
    }

    @Test
    @DisplayName("each rule is a 400 naming its parameter")
    void rejections() {
        assertRejected(params("limit", "0"), "limit");
        assertRejected(params("cursor", "a b"), "cursor");
        assertRejected(params("cursor", "a", "cursor", "b"), "cursor");
        assertRejected(params("q", "q".repeat(201)), "q");
        assertRejected(params("sharing", "anyone"), "sharing");
        assertRejected(params("sensitives", "pii"), "sensitives");
        assertRejected(params("appType", "Gmail"), "appType");
        assertRejected(params("category", ","), "category");
        assertRejected(params("owner", "ana@example.com"), "owner");
        assertRejected(params("internalUser", "a".repeat(321)), "internalUser");
        assertRejected(params("externalUser", "a\u0000b"), "externalUser");
        assertThatThrownBy(() -> SaasFileListQuery.parse(params("owner", "x".repeat(400))))
                .hasMessage("owner values must be 1 to 128 characters without control characters");
    }

    @Test
    @DisplayName("the canonical fragment ignores value and parameter order, URL-encodes values, and differs by cursor")
    void canonicalFragment() {
        String a = SaasFileListQuery.parse(params("category", "b,a", "q", "x y", "owner", OWNER_A))
                .canonicalFragment();
        String b = SaasFileListQuery.parse(params("owner", OWNER_A, "q", "x y", "category", "a", "category", "b"))
                .canonicalFragment();
        assertThat(a).isEqualTo(b).contains("q=x+y").contains("category=a,b");
        assertThat(SaasFileListQuery.parse(params("cursor", "abc")).canonicalFragment())
                .isNotEqualTo(SaasFileListQuery.parse(params()).canonicalFragment());
    }

    @Test
    @DisplayName("F2's category filter follows the TEXT rule")
    void categoryFilter() {
        assertThat(SaasFileListQuery.categoryFilter(params("category", "b,a"))).containsExactly("a", "b");
        assertThat(SaasFileListQuery.categoryFilter(params())).isEmpty();
        assertThatThrownBy(() -> SaasFileListQuery.categoryFilter(params("category", "c".repeat(129))))
                .isInstanceOf(DspmBadRequestException.class);
    }
}
