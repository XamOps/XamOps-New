package com.xammer.cloud.repository;

import com.xammer.cloud.autospotting.pg.PgHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the exact native SQL of {@link CachedDataRepository#findJsonLengthByCacheKey} and
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

    @Test
    void lengthAndDeleteHandleLargeObjectsAndInlineText() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            jdbc.execute("CREATE TABLE cached_data (cache_key varchar(255) PRIMARY KEY, json_data text, last_updated timestamp)");
            Long oid = jdbc.queryForObject("SELECT lo_from_bytea(0, convert_to(repeat('x', 5000), 'UTF8'))", Long.class);
            insert(jdbc, "lo", Long.toString(oid));
            insert(jdbc, "inline", "[1,2,3]");
            insert(jdbc, "number", "42");
            insert(jdbc, "missing", "99999999");

            String length = sql("findJsonLengthByCacheKey");
            assertThat(jdbc.queryForObject(length, Long.class, "lo")).isEqualTo(5000L);
            assertThat(jdbc.queryForObject(length, Long.class, "inline")).isEqualTo(7L);
            assertThat(jdbc.queryForObject(length, Long.class, "number")).isEqualTo(2L);
            assertThat(jdbc.queryForObject(length, Long.class, "missing")).isZero();
            assertThat(jdbc.queryForList(length, Long.class, "absent")).isEmpty();

            String delete = sql("deleteOversizedByCacheKey");
            assertThat(jdbc.queryForObject(delete, Long.class, "lo")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_largeobject_metadata WHERE oid = ?",
                    Long.class, oid)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cached_data WHERE cache_key = 'lo'", Long.class)).isZero();

            assertThat(jdbc.queryForObject(delete, Long.class, "inline")).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cached_data WHERE cache_key = 'inline'", Long.class)).isZero();
        }
    }
}
