package com.xammer.cloud.service.matters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
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
    @DisplayName("policyId is 1 to 128 letters, digits, '_', '-' or '.', kept as given; the message never echoes it")
    void policyId() {
        for (String valid : new String[]{"p-0123456789abcdef", "snake_case_rule", "a.b-c_d", "P-ABC", "x"}) {
            assertThat(DatastoreParams.isPolicyId(valid)).isTrue();
            assertThat(DatastoreParams.policyId(valid)).isEqualTo(valid);
        }
        assertThat(DatastoreParams.policyId("a" + "b".repeat(127))).hasSize(128);
        for (String invalid : new String[]{null, "", "a".repeat(129), "-leading", "p/1", "p 1", "p%2F1", "../p"}) {
            assertThat(DatastoreParams.isPolicyId(invalid)).isFalse();
            assertThatThrownBy(() -> DatastoreParams.policyId(invalid))
                    .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                        assertThat(e.parameter()).isEqualTo("policyId");
                        assertThat(e.getMessage())
                                .isEqualTo("policyId must be 1 to 128 letters, digits, '_', '-' or '.'");
                    });
        }
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
    @DisplayName("limit with a default: 5 for the SaaS file list, 10 elsewhere, with the same 1 to 100 bounds")
    void limitWithDefault() {
        assertThat(DatastoreParams.limit(null, 5)).isEqualTo(5);
        assertThat(DatastoreParams.limit(null, 10)).isEqualTo(10);
        assertThat(DatastoreParams.limit(null)).isEqualTo(10);
        assertThat(DatastoreParams.limit("7", 5)).isEqualTo(7);
        assertThat(DatastoreParams.limit("100", 5)).isEqualTo(100);
        assertRejected(() -> DatastoreParams.limit("0", 5), "limit");
        assertRejected(() -> DatastoreParams.limit("101", 5), "limit");
        assertRejected(() -> DatastoreParams.limit("", 5), "limit");
        assertRejected(() -> DatastoreParams.limit("5x", 5), "limit");
    }

    @Test
    @DisplayName("fileId is 24 hex characters, lower-cased, and the error names fileId")
    void fileId() {
        assertThat(DatastoreParams.fileId("FEDCBA9876543210FEDCBA98")).isEqualTo("fedcba9876543210fedcba98");
        assertRejected(() -> DatastoreParams.fileId("fedcba9876543210fedcba9"), "fileId");
        assertRejected(() -> DatastoreParams.fileId("fedcba9876543210fedcba987"), "fileId");
        assertRejected(() -> DatastoreParams.fileId("fedcba9876543210fedcba9z"), "fileId");
        assertRejected(() -> DatastoreParams.fileId(null), "fileId");
        assertRejected(() -> DatastoreParams.fileId("../fedcba9876543210fedcb"), "fileId");
    }

    @Test
    @DisplayName("view is exactly data_elements or document_classes, default data_elements")
    void saasView() {
        assertThat(DatastoreParams.saasView(null)).isEqualTo("data_elements");
        assertThat(DatastoreParams.saasView("data_elements")).isEqualTo("data_elements");
        assertThat(DatastoreParams.saasView("document_classes")).isEqualTo("document_classes");
        assertRejected(() -> DatastoreParams.saasView("DATA_ELEMENTS"), "view");
        assertRejected(() -> DatastoreParams.saasView(" data_elements"), "view");
        assertRejected(() -> DatastoreParams.saasView(""), "view");
        assertRejected(() -> DatastoreParams.saasView("records"), "view");
    }

    @Test
    @DisplayName("requiredText is trimmed, 1 to 128 characters, no control characters, and never echoes the value")
    void requiredText() {
        assertThat(DatastoreParams.requiredText("documentClass", "  Incident Reports ")).isEqualTo("Incident Reports");
        assertThat(DatastoreParams.requiredText("dataElement", "x".repeat(128))).hasSize(128);
        assertRejected(() -> DatastoreParams.requiredText("dataElement", null), "dataElement");
        assertRejected(() -> DatastoreParams.requiredText("dataElement", "   "), "dataElement");
        assertRejected(() -> DatastoreParams.requiredText("documentClass", "x".repeat(129)), "documentClass");
        assertRejected(() -> DatastoreParams.requiredText("documentClass", "needle\u0007value"), "documentClass");
        assertThatThrownBy(() -> DatastoreParams.requiredText("documentClass", "needle\u0007value"))
                .hasMessageNotContaining("needle");
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

    @Test
    @DisplayName("cursor: absent is null; base64url and base64 with +, / and == pass unchanged; anything else is a 400")
    void cursor() {
        assertThat(DatastoreParams.cursor(null)).isNull();
        assertThat(DatastoreParams.cursor("eyJpZCI6IjEyMyJ9")).isEqualTo("eyJpZCI6IjEyMyJ9");
        assertThat(DatastoreParams.cursor("ab_c-d")).isEqualTo("ab_c-d");
        assertThat(DatastoreParams.cursor("ab+c/d==")).isEqualTo("ab+c/d==");
        assertThat(DatastoreParams.cursor("c".repeat(1024))).hasSize(1024);
        assertRejected(() -> DatastoreParams.cursor(""), "cursor");
        assertRejected(() -> DatastoreParams.cursor("c".repeat(1025)), "cursor");
        assertRejected(() -> DatastoreParams.cursor("ab cd"), "cursor");
        assertRejected(() -> DatastoreParams.cursor("ab%2Bcd"), "cursor");
        assertRejected(() -> DatastoreParams.cursor("abcd==="), "cursor");
        assertRejected(() -> DatastoreParams.cursor("=="), "cursor");
        assertThatThrownBy(() -> DatastoreParams.cursor("ab cd"))
                .hasMessage("cursor must be a cursor returned by the previous page");
        assertThat(DatastoreParams.isCursor("ab+c/d==")).isTrue();
        assertThat(DatastoreParams.isCursor("ab cd")).isFalse();
        assertThat(DatastoreParams.isCursor(null)).isFalse();
    }

    @Test
    @DisplayName("choice: absent is null; exact values only, case-sensitive; the error names the parameter")
    void choice() {
        List<String> sharing = List.of("internal", "external", "public");
        assertThat(DatastoreParams.choice("sharing", null, sharing)).isNull();
        assertThat(DatastoreParams.choice("sharing", "public", sharing)).isEqualTo("public");
        assertRejected(() -> DatastoreParams.choice("sharing", "Public", sharing), "sharing");
        assertRejected(() -> DatastoreParams.choice("sharing", " public", sharing), "sharing");
        assertRejected(() -> DatastoreParams.choice("lastModified", "", List.of("48h", "7d", "30d")), "lastModified");
        assertThatThrownBy(() -> DatastoreParams.choice("lastModified", "1d", List.of("48h", "7d", "30d")))
                .hasMessage("lastModified must be one of 48h, 7d, 30d");
    }

    // ── build 6 ──────────────────────────────────────────────────────────────────────────────────

    private static void assertRejectedWithout(Executable call, String parameter, String value) {
        assertThatThrownBy(call::execute)
                .isInstanceOfSatisfying(DspmBadRequestException.class, e -> {
                    assertThat(e.parameter()).isEqualTo(parameter);
                    assertThat(e.getMessage()).contains(parameter).doesNotContain(value);
                });
    }

    @Test
    @DisplayName("integrationId is 24 hex characters, lower-cased, and the error names integrationId")
    void integrationId() {
        assertThat(DatastoreParams.integrationId("ABCDEF0123456789ABCDEF01")).isEqualTo("abcdef0123456789abcdef01");
        assertRejected(() -> DatastoreParams.integrationId(null), "integrationId");
        assertRejectedWithout(() -> DatastoreParams.integrationId("needle0123456789abcdef01"), "integrationId",
                "needle");
        assertRejected(() -> DatastoreParams.integrationId("0000000a-0000-4000-8000-00000000000b"), "integrationId");
    }

    @Test
    @DisplayName("scanObjectId accepts 24 hex or a canonical UUID, lower-cased, and names its parameter")
    void scanObjectId() {
        assertThat(DatastoreParams.scanObjectId("scheduleId", "ABCDEF0123456789ABCDEF01"))
                .isEqualTo("abcdef0123456789abcdef01");
        assertThat(DatastoreParams.scanObjectId("runId", "0000000A-0000-4000-8000-00000000000B"))
                .isEqualTo("0000000a-0000-4000-8000-00000000000b");
        assertRejected(() -> DatastoreParams.scanObjectId("runId", null), "runId");
        assertRejected(() -> DatastoreParams.scanObjectId("scopeId", "abcdef0123456789abcdef0"), "scopeId");
        assertRejectedWithout(() -> DatastoreParams.scanObjectId("scheduleId", "../needle"), "scheduleId", "needle");
    }

    @Test
    @DisplayName("code is an upper-snake code of 1 to 64 characters; absent is null")
    void code() {
        assertThat(DatastoreParams.code("status", null)).isNull();
        assertThat(DatastoreParams.code("status", "WAITING_APPROVAL")).isEqualTo("WAITING_APPROVAL");
        assertThat(DatastoreParams.code("status", "A" + "1".repeat(63))).hasSize(64);
        assertRejected(() -> DatastoreParams.code("status", "A" + "1".repeat(64)), "status");
        assertRejected(() -> DatastoreParams.code("actionType", ""), "actionType");
        assertRejected(() -> DatastoreParams.code("actionType", "_MAKE"), "actionType");
        assertRejectedWithout(() -> DatastoreParams.code("trigger", "needle"), "trigger", "needle");
    }

    @Test
    @DisplayName("oneOf accepts exactly a listed value, case-sensitive; absent is null")
    void oneOf() {
        Set<String> platforms = Set.of("cloud", "on_prem", "endpoint");
        assertThat(DatastoreParams.oneOf("platform", null, platforms)).isNull();
        assertThat(DatastoreParams.oneOf("platform", "on_prem", platforms)).isEqualTo("on_prem");
        assertRejected(() -> DatastoreParams.oneOf("platform", "CLOUD", platforms), "platform");
        assertRejected(() -> DatastoreParams.oneOf("platform", "", platforms), "platform");
        assertRejectedWithout(() -> DatastoreParams.oneOf("platform", "needle", platforms), "platform", "needle");
    }

    @Test
    @DisplayName("freeText is trimmed, 1 to 128 characters, no control character, no comma; absent is null")
    void freeText() {
        assertThat(DatastoreParams.freeText("account", null)).isNull();
        assertThat(DatastoreParams.freeText("account", "  prod account ")).isEqualTo("prod account");
        assertThat(DatastoreParams.freeText("account", "x".repeat(128))).hasSize(128);
        assertRejected(() -> DatastoreParams.freeText("account", "   "), "account");
        assertRejected(() -> DatastoreParams.freeText("account", "x".repeat(129)), "account");
        assertRejected(() -> DatastoreParams.freeText("account", "a\u0007b"), "account");
        assertRejectedWithout(() -> DatastoreParams.freeText("account", "needle,other"), "account", "needle");
    }

    @Test
    @DisplayName("domain is 1 to 253 letters, digits, dots or hyphens; absent is null")
    void domain() {
        assertThat(DatastoreParams.domain(null)).isNull();
        assertThat(DatastoreParams.domain("mail.example-test.org")).isEqualTo("mail.example-test.org");
        assertThat(DatastoreParams.domain("a".repeat(253))).hasSize(253);
        assertRejected(() -> DatastoreParams.domain("a".repeat(254)), "domain");
        assertRejected(() -> DatastoreParams.domain(""), "domain");
        assertRejectedWithout(() -> DatastoreParams.domain("needle@example.test"), "domain", "needle");
    }

    @Test
    @DisplayName("booleanFlag accepts exactly true and false; absent is null")
    void booleanFlag() {
        assertThat(DatastoreParams.booleanFlag("isNew", null)).isNull();
        assertThat(DatastoreParams.booleanFlag("isNew", "true")).isTrue();
        assertThat(DatastoreParams.booleanFlag("isNew", "false")).isFalse();
        assertRejected(() -> DatastoreParams.booleanFlag("isNew", "TRUE"), "isNew");
        assertRejectedWithout(() -> DatastoreParams.booleanFlag("isNew", "yes"), "isNew", "yes");
    }
}
