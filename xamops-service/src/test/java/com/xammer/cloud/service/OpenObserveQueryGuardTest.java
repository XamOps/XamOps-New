package com.xammer.cloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the boundary between the Logs tab's SQL editor and OpenObserve.
 *
 * The editor sends a query the user typed. Before this guard existed it was forwarded
 * verbatim, using the OpenObserve admin credentials, with no check that it was a single
 * read-only statement.
 *
 * The awkward requirement is that a *log* console must let people search for the very words
 * a naive keyword blocklist would reject — "delete", "drop table", "truncated" all legitimately
 * appear in log messages. So the guard blanks string literals before scanning, and these tests
 * pin both halves of that behaviour: the rejections and, just as importantly, the queries that
 * must keep working.
 */
class OpenObserveQueryGuardTest {

    // ── Accepted ──────────────────────────────────────────────────────────────

    @Test
    void acceptsAPlainSelect() {
        String sql = "SELECT * FROM \"kubernetes\" ORDER BY _timestamp DESC LIMIT 50";
        assertThat(OpenObserveService.requireReadOnlySelect(sql)).isEqualTo(sql);
    }

    @Test
    void acceptsLowercaseAndLeadingWhitespace() {
        assertThat(OpenObserveService.requireReadOnlySelect("   select level from \"default\"  "))
                .isEqualTo("select level from \"default\"");
    }

    @Test
    void acceptsCteQueries() {
        String sql = "WITH e AS (SELECT * FROM \"default\") SELECT count(*) FROM e";
        assertThat(OpenObserveService.requireReadOnlySelect(sql)).isEqualTo(sql);
    }

    @Test
    void stripsASingleTrailingSemicolon() {
        assertThat(OpenObserveService.requireReadOnlySelect("SELECT 1 FROM \"default\";"))
                .isEqualTo("SELECT 1 FROM \"default\"");
    }

    @Test
    @DisplayName("searching logs for words that are also SQL keywords still works")
    void acceptsForbiddenKeywordsInsideStringLiterals() {
        // The whole point of a log search. A blocklist that scanned the raw text would reject
        // every one of these and users would conclude the editor is broken.
        assertThat(OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" WHERE message LIKE '%delete failed%'")).isNotBlank();
        assertThat(OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" WHERE message LIKE '%DROP TABLE users%'")).isNotBlank();
        assertThat(OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" WHERE body = 'update; truncate'")).isNotBlank();
    }

    @Test
    void acceptsEscapedQuotesInsideLiterals() {
        assertThat(OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" WHERE message LIKE '%can''t delete%'")).isNotBlank();
    }

    @Test
    void doesNotMistakeKeywordSubstringsForKeywords() {
        // "offset" contains "set", "created_at" contains "create", "updated" contains "update".
        assertThat(OpenObserveService.requireReadOnlySelect(
                "SELECT created_at, updated_by FROM \"default\" LIMIT 10 OFFSET 20")).isNotBlank();
    }

    // ── Rejected ──────────────────────────────────────────────────────────────

    @Test
    void rejectsStatementChaining() {
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\"; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single statement");
    }

    @Test
    void rejectsWriteStatements() {
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect("DELETE FROM \"default\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only SELECT");
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect("DROP TABLE \"default\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only SELECT");
    }

    @Test
    void rejectsWritesHiddenBehindASelectPrefix() {
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" UNION SELECT 1 WHERE (INSERT INTO x VALUES (1))"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INSERT");
    }

    @Test
    void rejectsCommentsUsedToSplitStatements() {
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" --ignore the rest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comments");
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect(
                "SELECT /* sneaky */ * FROM \"default\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Comments");
    }

    // ── Stream names ──────────────────────────────────────────────────────────

    @Test
    void acceptsRealisticStreamNames() {
        assertThat(OpenObserveService.sqlIdentifier("kubernetes")).isEqualTo("kubernetes");
        assertThat(OpenObserveService.sqlIdentifier("ec2-logs")).isEqualTo("ec2-logs");
        assertThat(OpenObserveService.sqlIdentifier("host_metrics.v2")).isEqualTo("host_metrics.v2");
        assertThat(OpenObserveService.sqlIdentifier("  default  ")).isEqualTo("default");
    }

    @Test
    void defaultsWhenNoStreamIsGiven() {
        assertThat(OpenObserveService.sqlIdentifier(null)).isEqualTo("default");
        assertThat(OpenObserveService.sqlIdentifier("   ")).isEqualTo("default");
    }

    @Test
    @DisplayName("a stream name cannot break out of the quoted identifier")
    void rejectsStreamNamesThatWouldEscapeTheIdentifier() {
        // Interpolated as FROM "<stream>", so an embedded double quote closed the identifier
        // and everything after it was parsed as SQL.
        assertThatThrownBy(() -> OpenObserveService.sqlIdentifier("a\" UNION SELECT * FROM \"b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid stream name");
        assertThatThrownBy(() -> OpenObserveService.sqlIdentifier("default\""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenObserveService.sqlIdentifier("a b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpenObserveService.sqlIdentifier("logs;drop"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlongStreamNames() {
        assertThatThrownBy(() -> OpenObserveService.sqlIdentifier("s".repeat(200)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyAndOversizedQueries() {
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect("   ;  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> OpenObserveService.requireReadOnlySelect(
                "SELECT * FROM \"default\" WHERE x = '" + "a".repeat(5000) + "'"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }
}
