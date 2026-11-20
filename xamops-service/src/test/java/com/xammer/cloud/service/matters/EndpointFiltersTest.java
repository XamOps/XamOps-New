package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.3: the Endpoint drawer filters and the File View query. Values are decoded, as Spring binds them. */
class EndpointFiltersTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static MultiValueMap<String, String> params(String... namesAndValues) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            params.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return params;
    }

    private static void assertRejected(MultiValueMap<String, String> params, String parameter) {
        assertThatThrownBy(() -> EndpointFilters.parse(params))
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    @Test
    @DisplayName("documentClass=a,b is two values; occurrences are merged, trimmed, de-duplicated and sorted")
    void splitting() {
        assertThat(EndpointFilters.parse(params("documentClass", "a,b")).documentClass()).containsExactly("a", "b");

        EndpointFilters filters = EndpointFilters.parse(params(
                "documentClass", "b,a", "documentClass", " a , c ", "category", "it & technical"));
        assertThat(filters.documentClass()).containsExactly("a", "b", "c");
        assertThat(filters.category()).containsExactly("it & technical");
        assertThat(filters.sensitives()).isEmpty();
        assertThat(filters.dataElement()).isEmpty();
    }

    @Test
    @DisplayName("no filter parameter gives NONE; other names are left to requireAllowed")
    void absent() {
        assertThat(EndpointFilters.parse(params())).isEqualTo(EndpointFilters.NONE);
        assertThat(EndpointFilters.parse(params("q", "notes", "skip", "10"))).isEqualTo(EndpointFilters.NONE);
    }

    @Test
    @DisplayName("50 distinct values pass and duplicates do not count; a 51st is a 400 naming the parameter")
    void valueCount() {
        String fifty = IntStream.range(0, 50).mapToObj(i -> "v" + i).collect(Collectors.joining(","));
        assertThat(EndpointFilters.parse(params("dataElement", fifty)).dataElement()).hasSize(50);
        assertThat(EndpointFilters.parse(params("dataElement", fifty + ",v0,v1")).dataElement()).hasSize(50);
        assertRejected(params("dataElement", fifty + ",v50"), "dataElement");
        assertRejected(params("dataElement", fifty, "dataElement", "v50"), "dataElement");
    }

    @Test
    @DisplayName("a present filter with only empty values is a 400: documentClass=, and category=%20")
    void allEmpty() {
        assertRejected(params("documentClass", ","), "documentClass");
        assertRejected(params("documentClass", ""), "documentClass");
        assertRejected(params("category", " "), "category");
        assertRejected(params("sensitives", " , ,"), "sensitives");
    }

    @Test
    @DisplayName("a control character, an over-long value and a lower-case sensitives value are 400s that never echo it")
    void valueFormat() {
        assertRejected(params("dataElement", "EMAIL\u0007ADDRESS"), "dataElement");
        assertRejected(params("documentClass", "x".repeat(129)), "documentClass");
        assertThat(EndpointFilters.parse(params("documentClass", "x".repeat(128))).documentClass()).hasSize(1);
        assertRejected(params("sensitives", "pii"), "sensitives");
        assertRejected(params("sensitives", "PII-X"), "sensitives");
        assertRejected(params("sensitives", "1PII"), "sensitives");
        assertThat(EndpointFilters.parse(params("sensitives", "PII,FINANCIAL_DATA")).sensitives())
                .containsExactly("FINANCIAL_DATA", "PII");

        assertThatThrownBy(() -> EndpointFilters.parse(params("sensitives", "needle")))
                .hasMessageNotContaining("needle");
        assertThatThrownBy(() -> EndpointFilters.parse(params("category", "needle\u0000x")))
                .hasMessageNotContaining("needle");
    }

    @Test
    @DisplayName("vendor params: one JSON array per non-empty filter, as sensitives, subcategory, category, entity_name")
    void vendorParams() {
        EndpointFilters filters = EndpointFilters.parse(params(
                "dataElement", "PERSON NAME,EMAIL ADDRESS", "category", "it & technical",
                "documentClass", "System Documentation", "sensitives", "PII"));
        assertThat(filters.toVendorParams(objectMapper)).containsExactly(
                entry("sensitives", List.of("[\"PII\"]")),
                entry("subcategory", List.of("[\"System Documentation\"]")),
                entry("category", List.of("[\"it & technical\"]")),
                entry("entity_name", List.of("[\"EMAIL ADDRESS\",\"PERSON NAME\"]")));
        assertThat(EndpointFilters.NONE.toVendorParams(objectMapper)).isEmpty();
        assertThat(EndpointFilters.parse(params("category", "hr")).toVendorParams(objectMapper))
                .containsOnlyKeys("category");
    }

    @Test
    @DisplayName("the fragment depends on neither value nor parameter order, and URL-encodes every value")
    void canonicalFragment() {
        String first = EndpointFilters.parse(params("documentClass", "b,a", "category", "x y")).canonicalFragment();
        String second = EndpointFilters.parse(params(
                "category", "x y", "documentClass", "a", "documentClass", "b,a")).canonicalFragment();
        assertThat(first).isEqualTo(second).isEqualTo("sensitives=&documentClass=a,b&category=x+y&dataElement=");

        assertThat(EndpointFilters.parse(params("dataElement", "a&category=b")).canonicalFragment())
                .isEqualTo("sensitives=&documentClass=&category=&dataElement=a%26category%3Db");
        assertThat(EndpointFilters.parse(params("category", "x")).canonicalFragment())
                .isNotEqualTo(EndpointFilters.parse(params("documentClass", "x")).canonicalFragment());
        assertThat(EndpointFilters.NONE.canonicalFragment())
                .isEqualTo("sensitives=&documentClass=&category=&dataElement=");
    }

    @Test
    @DisplayName("the file query sends skip, limit, search, then the filters; its fragment adds q, skip and limit")
    void fileQuery() {
        EndpointFilters filters = EndpointFilters.parse(params("documentClass", "System Documentation"));
        EndpointFileQuery query = new EndpointFileQuery(filters, "notes & v2", 20, 10);
        assertThat(query.toVendorParams(objectMapper)).containsExactly(
                entry("skip", List.of("20")),
                entry("limit", List.of("10")),
                entry("search", List.of("notes & v2")),
                entry("subcategory", List.of("[\"System Documentation\"]")));
        assertThat(query.canonicalFragment()).isEqualTo("sensitives=&documentClass=System+Documentation"
                + "&category=&dataElement=&q=notes+%26+v2&skip=20&limit=10");

        EndpointFileQuery firstPage = new EndpointFileQuery(EndpointFilters.NONE, null, 0, 10);
        assertThat(firstPage.toVendorParams(objectMapper))
                .containsExactly(entry("skip", List.of("0")), entry("limit", List.of("10")));
        assertThat(firstPage.canonicalFragment())
                .isEqualTo("sensitives=&documentClass=&category=&dataElement=&q=&skip=0&limit=10");
        assertThat(new EndpointFileQuery(EndpointFilters.NONE, null, 10, 10).canonicalFragment())
                .isNotEqualTo(firstPage.canonicalFragment());
    }
}
