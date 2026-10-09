package com.xammer.cloud.domain.type;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-7: billops and xamops-service apply one rule. The shared classes and fixtures are duplicated
 * under one name (design D3), so this test fails as soon as a copy diverges from {@code ../billops}.
 */
class LobTextSourceParityTest {

    private static final Path BILLOPS = Path.of("..", "billops");

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/java/com/xammer/cloud/domain/type/LegacyLobTextType.java",
            "src/main/java/com/xammer/cloud/domain/type/LobTextWriteMode.java",
            "src/main/java/com/xammer/cloud/domain/type/OidShapeGuard.java",
            "src/test/java/com/xammer/cloud/domain/type/PgTestSupport.java",
            "src/test/resources/lobtext/candidate-vectors.csv"})
    void sharedFileIsByteIdenticalInBothServices(String relative) throws IOException {
        Path here = Path.of(relative);
        Path there = BILLOPS.resolve(relative);
        assertThat(here).as("run from the xamops-service module directory").isRegularFile();
        assertThat(there).as("billops copy").isRegularFile();
        assertThat(Files.mismatch(here, there)).as("first differing byte between %s and %s", here, there).isEqualTo(-1L);
    }

    /**
     * NFR-7, the SQL half: the manual scripts apply the same D12 candidate rule as the Java type, so
     * none of them may drift to a looser predicate such as {@code ^[0-9]{1,10}$}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "census-lob-text.sql",
            "normalize-lob-text-columns.sql",
            "rollback-lob-text.sql",
            "dev-ops-script-lob-to-inline.sql"})
    void eachScriptCarriesTheSameCandidateRule(String name) throws IOException {
        Path script = Path.of("..", "scripts", "db", "lob-text", name);
        assertThat(script).isRegularFile();
        String sql = Files.readString(script);
        assertThat(sql).as("%s", name)
                .contains("'^[1-9][0-9]{4,9}$'")
                .contains("16384")
                .contains("4294967295");
    }

    @Test
    void candidateRuleIsTheDocumentedOne() {
        assertThat(LegacyLobTextType.CANDIDATE.pattern()).isEqualTo("[1-9][0-9]{4,9}");
        assertThat(LegacyLobTextType.MIN_OID).isEqualTo(16_384L);
        assertThat(LegacyLobTextType.MAX_OID).isEqualTo(4_294_967_295L);
        assertThat(LobTextWriteMode.PROPERTY).isEqualTo("xammer.lob-text.write-mode");
    }
}
