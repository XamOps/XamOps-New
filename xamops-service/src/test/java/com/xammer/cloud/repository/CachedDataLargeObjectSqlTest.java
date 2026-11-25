package com.xammer.cloud.repository;
import com.xammer.cloud.autospotting.pg.PgHarness;
import com.xammer.cloud.domain.type.LegacyLobTextType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * Runs {@link LegacyLobTextType#storedLength} and the exact native SQL of
 * {@link CachedDataRepository#deleteOversizedByCacheKey} on real PostgreSQL.
 * Needs Docker (or {@code -Dpg.harness.admin-url}); run with {@code -Dgroups=containers -Dsurefire.excludedGroups=}.
 */
@Tag("containers")
class CachedDataLargeObjectSqlTest {
    private static String sql(String method) throws Exception {
        return CachedDataRepository.class.getMethod(method, String.class).getAnnotation(Query.class)
                .value().replace(":key", "?");
    }
    private static void insert(JdbcTemplate jdbc, String key, String value) {
        jdbc.update("INSERT INTO cached_data (cache_key, json_data) VALUES (?, ?)", key, value);
    }
    private static Long length(JdbcTemplate jdbc, String key) {
        return LegacyLobTextType.storedLength(jdbc, "cached_data", "json_data", "cache_key", key);
    }
    @Test
    void lengthMeasuresLargeObjectsAndInlineTextAndDeleteLeavesTheObject() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            jdbc.execute("CREATE TABLE cached_data (cache_key varchar(255) PRIMARY KEY, json_data text, last_updated timestamp)");
            Long oid = jdbc.queryForObject("SELECT lo_from_bytea(0, convert_to(repeat('x', 5000), 'UTF8'))", Long.class);
            insert(jdbc, "lo", Long.toString(oid));
            insert(jdbc, "inline", "[1,2,3]");
            insert(jdbc, "number", "42");
            insert(jdbc, "missing", "99999999");
            assertThat(length(jdbc, "lo")).isEqualTo(5000L);
            assertThat(length(jdbc, "inline")).isEqualTo(7L);
            assertThat(length(jdbc, "number")).isEqualTo(2L);
            assertThat(length(jdbc, "missing")).isZero();
            assertThat(length(jdbc, "absent")).isNull();
            String delete = sql("deleteOversizedByCacheKey");
            assertThat(jdbc.update(delete, "lo")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cached_data WHERE cache_key = 'lo'", Long.class)).isZero();
            // The large object stays: only the runbook's managed cleanup removes large objects.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_largeobject_metadata WHERE oid = ?",
                    Long.class, oid)).isEqualTo(1L);
            assertThat(jdbc.update(delete, "inline")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cached_data WHERE cache_key = 'inline'", Long.class)).isZero();
            assertThat(jdbc.update(delete, "absent")).isZero();
        }
    }
}
