package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.xammer.cloud.service.matters.SaasFileQuery.Kind.DATA_ELEMENT;
import static com.xammer.cloud.service.matters.SaasFileQuery.Kind.DOCUMENT_CLASS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Design 5.3: the SaaS file-list query, its vendor params (GAP-20) and its cache fragment. */
class SaasFileQueryTest {

    @Test
    @DisplayName("a data element goes as sensitive_entity, a plain string after limit and skip")
    void dataElementVendorParams() {
        SaasFileQuery query = new SaasFileQuery(DATA_ELEMENT, "EMAIL ADDRESS", null, 0, 5);
        assertThat(query.toVendorParams()).containsExactly(
                entry("limit", List.of("5")),
                entry("skip", List.of("0")),
                entry("sensitive_entity", List.of("EMAIL ADDRESS")));
    }

    @Test
    @DisplayName("a document class goes as subcategory, a plain string after the search")
    void documentClassVendorParams() {
        SaasFileQuery query = new SaasFileQuery(DOCUMENT_CLASS, "Incident Reports", "q4 plan", 10, 5);
        assertThat(query.toVendorParams()).containsExactly(
                entry("limit", List.of("5")),
                entry("skip", List.of("10")),
                entry("search", List.of("q4 plan")),
                entry("subcategory", List.of("Incident Reports")));
    }

    @Test
    @DisplayName("the fragment names every field and URL-encodes the value and q")
    void canonicalFragment() {
        assertThat(new SaasFileQuery(DATA_ELEMENT, "EMAIL ADDRESS", null, 0, 5).canonicalFragment())
                .isEqualTo("kind=DATA_ELEMENT&value=EMAIL+ADDRESS&q=&skip=0&limit=5");
        assertThat(new SaasFileQuery(DOCUMENT_CLASS, "a&q=b", "x&skip=9", 5, 5).canonicalFragment())
                .isEqualTo("kind=DOCUMENT_CLASS&value=a%26q%3Db&q=x%26skip%3D9&skip=5&limit=5");
    }

    @Test
    @DisplayName("kind, value, search and page each change the fragment")
    void fragmentIdentity() {
        String base = new SaasFileQuery(DATA_ELEMENT, "Notes", null, 0, 5).canonicalFragment();
        assertThat(new SaasFileQuery(DOCUMENT_CLASS, "Notes", null, 0, 5).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasFileQuery(DATA_ELEMENT, "Notes 2", null, 0, 5).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasFileQuery(DATA_ELEMENT, "Notes", "x", 0, 5).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasFileQuery(DATA_ELEMENT, "Notes", null, 5, 5).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasFileQuery(DATA_ELEMENT, "Notes", null, 0, 10).canonicalFragment()).isNotEqualTo(base);
        assertThat(new SaasFileQuery(DATA_ELEMENT, "Notes", null, 0, 5).canonicalFragment()).isEqualTo(base);
    }
}
