package com.xammer.cloud.service.matters;

import com.xammer.cloud.service.matters.LineageFilters.Selection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineageFiltersTest {

    private static MultiValueMap<String, String> params(String... pairs) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.add(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    private static void assertBadRequest(MultiValueMap<String, String> params, List<String> dimensions,
                                         String parameter, String message) {
        assertThatThrownBy(() -> LineageFilters.parse(params, dimensions))
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo(parameter);
                    assertThat(e.getMessage()).isEqualTo(message);
                });
    }

    @Test
    @DisplayName("no filter parameter is NONE")
    void none() {
        LineageFilters filters = LineageFilters.parse(params("q", "x"), LineageFilters.POLICY_DIMENSIONS);
        assertThat(filters).isEqualTo(LineageFilters.NONE);
        assertThat(filters.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("values are split, trimmed, de-duplicated and sorted; _exclude sets the Exclude mode")
    void parse() {
        LineageFilters filters = LineageFilters.parse(params(
                "severity", "MEDIUM, HIGH,,", "severity", "HIGH",
                "datastoreName_exclude", "Team Drive , Archive",
                "label", "PII"), LineageFilters.POLICY_DIMENSIONS);

        assertThat(filters.severity()).isEqualTo(new Selection(false, List.of("HIGH", "MEDIUM")));
        assertThat(filters.datastoreName()).isEqualTo(new Selection(true, List.of("Archive", "Team Drive")));
        assertThat(filters.label()).isEqualTo(new Selection(false, List.of("PII")));
        assertThat(filters.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("51 values, no value, 129 characters and a control character are 400s with build 2's messages")
    void valueRules() {
        String many = IntStream.rangeClosed(1, 51).mapToObj(i -> "v" + i).collect(Collectors.joining(","));
        List<String> dims = LineageFilters.POLICY_DIMENSIONS;
        assertBadRequest(params("label", many), dims, "label", "label needs 1 to 50 values");
        assertBadRequest(params("label", " , "), dims, "label", "label needs 1 to 50 values");
        assertBadRequest(params("severity_exclude", ""), dims, "severity_exclude",
                "severity_exclude needs 1 to 50 values");
        assertBadRequest(params("datastoreName", "x".repeat(129)), dims, "datastoreName",
                "datastoreName values must be 1 to 128 characters without control characters");
        assertBadRequest(params("datastoreName_exclude", "a\tb"), dims, "datastoreName_exclude",
                "datastoreName_exclude values must be 1 to 128 characters without control characters");

        String fifty = IntStream.rangeClosed(1, 50).mapToObj(i -> "v" + i).collect(Collectors.joining(","));
        assertThat(LineageFilters.parse(params("label", fifty, "label", "v1"), dims).label().values()).hasSize(50);
        assertThat(LineageFilters.parse(params("label", "x".repeat(128)), dims).label().values()).hasSize(1);
    }

    @Test
    @DisplayName("include and exclude of one dimension is a 400 on <dim>_exclude")
    void includeAndExclude() {
        assertBadRequest(params("severity", "HIGH", "severity_exclude", "LOW"), LineageFilters.POLICY_DIMENSIONS,
                "severity_exclude", "severity cannot be included and excluded at once");
    }

    @Test
    @DisplayName("the datastore level's dimensions reject datastoreName and label")
    void datastoreDimensions() {
        List<String> dims = LineageFilters.DATASTORE_DIMENSIONS;
        assertBadRequest(params("datastoreName", "Archive"), dims, "datastoreName",
                "datastoreName is not a supported parameter");
        assertBadRequest(params("label_exclude", "PII"), dims, "label_exclude",
                "label_exclude is not a supported parameter");
        assertThat(LineageFilters.parse(params("severity_exclude", "LOW"), dims).severity())
                .isEqualTo(new Selection(true, List.of("LOW")));
    }

    @Test
    @DisplayName("canonicalFragment depends on neither value nor parameter order, and encodes values")
    void canonicalFragment() {
        LineageFilters a = LineageFilters.parse(params("label", "PHI,PII", "severity", "HIGH",
                "datastoreName_exclude", "a&b=c"), LineageFilters.POLICY_DIMENSIONS);
        LineageFilters b = LineageFilters.parse(params("datastoreName_exclude", "a&b=c", "severity", "HIGH",
                "label", "PII", "label", "PHI"), LineageFilters.POLICY_DIMENSIONS);

        assertThat(a.canonicalFragment()).isEqualTo(b.canonicalFragment())
                .isEqualTo("severity=i:HIGH&datastoreName=e:a%26b%3Dc&label=i:PHI,PII");
        assertThat(LineageFilters.NONE.canonicalFragment()).isEqualTo("severity=i:&datastoreName=i:&label=i:");
        assertThat(new LineageRowQuery("q4 plan", 7, 7, a).canonicalFragment())
                .isEqualTo("q=q4+plan&skip=7&limit=7&severity=i:HIGH&datastoreName=e:a%26b%3Dc&label=i:PHI,PII");
        assertThat(new LineageRowQuery(null, 0, 7, null).canonicalFragment())
                .isEqualTo("q=&skip=0&limit=7&" + LineageFilters.NONE.canonicalFragment());
    }
}
