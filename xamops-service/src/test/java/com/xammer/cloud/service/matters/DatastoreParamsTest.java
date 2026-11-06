package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Design 5.3.1: the parameters that are not filter dimensions. */
class DatastoreParamsTest {

    private static void assertRejected(Executable call, String parameter) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(DspmBadRequestException.class,
                        e -> assertThat(e.parameter()).isEqualTo(parameter));
    }

    @Test
    @DisplayName("sourceId is 24 hex characters, lower-cased")
    void sourceId() {
        assertThat(DatastoreParams.sourceId("0123456789ABCDEF01234567")).isEqualTo("0123456789abcdef01234567");
        assertRejected(() -> DatastoreParams.sourceId("0123456789abcdef0123456"), "sourceId");
        assertRejected(() -> DatastoreParams.sourceId("0123456789abcdef012345678"), "sourceId");
        assertRejected(() -> DatastoreParams.sourceId("0123456789abcdef0123456z"), "sourceId");
        assertRejected(() -> DatastoreParams.sourceId(null), "sourceId");
        assertRejected(() -> DatastoreParams.sourceId("../0123456789abcdef01234"), "sourceId");
    }

    @Test
    @DisplayName("Data Flow ids are canonical UUIDs, lower-cased, and the error names the parameter")
    void uuid() {
        assertThat(DatastoreParams.uuid("schemaId", "0000000A-0000-4000-8000-00000000000B"))
                .isEqualTo("0000000a-0000-4000-8000-00000000000b");
        assertRejected(() -> DatastoreParams.uuid("schemaId", "0000000a00004000800000000000000b"), "schemaId");
        assertRejected(() -> DatastoreParams.uuid("tableId", "0000000a-0000-4000-8000-00000000000"), "tableId");
        assertRejected(() -> DatastoreParams.uuid("databaseId", "g000000a-0000-4000-8000-00000000000b"), "databaseId");
        assertRejected(() -> DatastoreParams.uuid("databaseId", null), "databaseId");
    }

    @Test
    @DisplayName("skip is 0 to 10,000, default 0")
    void skip() {
        assertThat(DatastoreParams.skip(null)).isZero();
        assertThat(DatastoreParams.skip("0")).isZero();
        assertThat(DatastoreParams.skip("10000")).isEqualTo(10_000);
        assertRejected(() -> DatastoreParams.skip("10001"), "skip");
        assertRejected(() -> DatastoreParams.skip("-1"), "skip");
        assertRejected(() -> DatastoreParams.skip("123456"), "skip");
        assertRejected(() -> DatastoreParams.skip("x"), "skip");
    }

    @Test
    @DisplayName("limit is 1 to 100, default 10")
    void limit() {
        assertThat(DatastoreParams.limit(null)).isEqualTo(10);
        assertThat(DatastoreParams.limit("1")).isEqualTo(1);
        assertThat(DatastoreParams.limit("100")).isEqualTo(100);
        assertRejected(() -> DatastoreParams.limit("0"), "limit");
        assertRejected(() -> DatastoreParams.limit("101"), "limit");
        assertRejected(() -> DatastoreParams.limit("1000"), "limit");
        assertRejected(() -> DatastoreParams.limit(""), "limit");
    }

    @Test
    @DisplayName("showFalsePositives accepts only true and false")
    void showFalsePositives() {
        assertThat(DatastoreParams.showFalsePositives(null)).isFalse();
        assertThat(DatastoreParams.showFalsePositives("true")).isTrue();
        assertThat(DatastoreParams.showFalsePositives("false")).isFalse();
        assertRejected(() -> DatastoreParams.showFalsePositives("TRUE"), "showFalsePositives");
        assertRejected(() -> DatastoreParams.showFalsePositives("1"), "showFalsePositives");
        assertRejected(() -> DatastoreParams.showFalsePositives(""), "showFalsePositives");
    }

    @Test
    @DisplayName("entityName is required, trimmed, 1 to 128 characters, no control characters")
    void entityName() {
        assertThat(DatastoreParams.entityName("  PERSON NAME ")).isEqualTo("PERSON NAME");
        assertThat(DatastoreParams.entityName("x".repeat(128))).hasSize(128);
        assertRejected(() -> DatastoreParams.entityName(null), "entityName");
        assertRejected(() -> DatastoreParams.entityName("   "), "entityName");
        assertRejected(() -> DatastoreParams.entityName("x".repeat(129)), "entityName");
        assertRejected(() -> DatastoreParams.entityName("PERSON\u0000NAME"), "entityName");
    }

    @Test
    @DisplayName("page and pageSize, with the list and Data Flow offset bounds")
    void paging() {
        assertThat(DatastoreParams.listPaging(null, null)).isEqualTo(new DatastoreParams.Paging(1, 10));
        assertThat(DatastoreParams.listPaging("1001", "10").offset()).isEqualTo(10_000);
        assertRejected(() -> DatastoreParams.listPaging("1002", "10"), "page");
        assertThat(DatastoreParams.dataFlowPaging("1000", "10").offset()).isEqualTo(9_990);
        assertThat(DatastoreParams.dataFlowPaging("334", "30").offset()).isEqualTo(9_990);
        assertRejected(() -> DatastoreParams.dataFlowPaging("1001", "10"), "page");
        assertRejected(() -> DatastoreParams.dataFlowPaging("101", "100"), "page");
        assertRejected(() -> DatastoreParams.dataFlowPaging("0", "10"), "page");
        assertRejected(() -> DatastoreParams.dataFlowPaging("1", "101"), "pageSize");
    }

    @Test
    @DisplayName("search is trimmed and bounded; the Data Flow variant ignores fewer than 3 characters")
    void search() {
        assertThat(DatastoreParams.search(" abc ")).isEqualTo("abc");
        assertThat(DatastoreParams.search("")).isNull();
        assertRejected(() -> DatastoreParams.search("q".repeat(201)), "q");
        assertThat(DatastoreParams.dataFlowSearch("ab")).isNull();
        assertThat(DatastoreParams.dataFlowSearch("  ab  ")).isNull();
        assertThat(DatastoreParams.dataFlowSearch("abc")).isEqualTo("abc");
        assertRejected(() -> DatastoreParams.dataFlowSearch("a\u0007bc"), "q");
    }

    @Test
    @DisplayName("an unknown name, clientId included, and a repeated control parameter are rejected")
    void namesAndRepeats() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("page", "1");
        params.add("clientId", "99");
        assertRejected(() -> DatastoreParams.requireAllowed(params, Set.of("page", "pageSize", "q")), "clientId");

        MultiValueMap<String, String> repeated = new LinkedMultiValueMap<>();
        repeated.add("skip", "0");
        repeated.add("skip", "10");
        assertRejected(() -> DatastoreParams.single(repeated, "skip"), "skip");
        assertThat(DatastoreParams.single(repeated, "limit")).isNull();
        DatastoreParams.requireAllowed(repeated, Set.of("skip"));
    }
}
