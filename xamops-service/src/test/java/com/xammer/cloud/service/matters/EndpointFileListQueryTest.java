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

/** F3's query (design 5.3 as changed by plan C6). Values are decoded, as Spring binds them. */
class EndpointFileListQueryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static MultiValueMap<String, String> params(String... namesAndValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            params.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return params;
    }

    private static void assertRejected(MultiValueMap<String, String> params, String parameter) {
        assertThatThrownBy(() -> EndpointFileListQuery.parse(params))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    @Test
    @DisplayName("no parameter: skip 0, limit 10, no filter; only skip and limit are sent")
    void defaults() {
        EndpointFileListQuery query = EndpointFileListQuery.parse(params());
        assertThat(query.filters()).isEqualTo(EndpointFilters.NONE);
        assertThat(query.toVendorParams(objectMapper))
                .containsExactly(entry("skip", List.of("0")), entry("limit", List.of("10")));
    }

    @Test
    @DisplayName("build 2's four filters, then tags, uids and hostname as JSON arrays, then last_modified; never user")
    void vendorParams() {
        EndpointFileListQuery query = EndpointFileListQuery.parse(params(
                "skip", "20", "limit", "25", "q", "notes", "sensitives", "PII", "documentClass", "Travel Receipts",
                "category", "financial", "dataElement", "BADGE NUMBER", "tag", "Reviewed,Archived",
                "uid", "UID-02,UID-01", "hostname", "Team-Laptop-02", "lastModified", "48h"));
        assertThat(query.toVendorParams(objectMapper)).containsExactly(
                entry("skip", List.of("20")),
                entry("limit", List.of("25")),
                entry("search", List.of("notes")),
                entry("sensitives", List.of("[\"PII\"]")),
                entry("subcategory", List.of("[\"Travel Receipts\"]")),
                entry("category", List.of("[\"financial\"]")),
                entry("entity_name", List.of("[\"BADGE NUMBER\"]")),
                entry("tags", List.of("[\"Archived\",\"Reviewed\"]")),
                entry("uids", List.of("[\"UID-01\",\"UID-02\"]")),
                entry("hostname", List.of("[\"Team-Laptop-02\"]")),
                entry("last_modified", List.of("48h")));
        assertThat(query.toVendorParams(objectMapper)).doesNotContainKeys("user", "users");
    }

    @Test
    @DisplayName("build 2's messages hold for its four filters; each new rule is a 400 naming its parameter")
    void rejections() {
        assertThatThrownBy(() -> EndpointFileListQuery.parse(params("sensitives", "pii")))
                .hasMessage("sensitives values must be upper-case codes of letters, digits and underscores");
        assertRejected(params("skip", "10001"), "skip");
        assertRejected(params("limit", "101"), "limit");
        assertRejected(params("q", "a", "q", "b"), "q");
        assertRejected(params("tag", ","), "tag");
        assertRejected(params("uid", "u".repeat(129)), "uid");
        assertRejected(params("hostname", "h\u0001x"), "hostname");
        assertRejected(params("lastModified", "1d"), "lastModified");
        assertRejected(params("lastModified", "7d", "lastModified", "30d"), "lastModified");
    }

    @Test
    @DisplayName("the canonical fragment extends build 2's and ignores value and parameter order")
    void canonicalFragment() {
        String a = EndpointFileListQuery.parse(params("tag", "b,a", "uid", "u1", "lastModified", "7d"))
                .canonicalFragment();
        String b = EndpointFileListQuery.parse(params("lastModified", "7d", "uid", "u1", "tag", "a", "tag", "b"))
                .canonicalFragment();
        assertThat(a).isEqualTo(b)
                .startsWith(EndpointFilters.NONE.canonicalFragment())
                .contains("&tag=a,b&uid=u1&hostname=&lastModified=7d");
    }
}
