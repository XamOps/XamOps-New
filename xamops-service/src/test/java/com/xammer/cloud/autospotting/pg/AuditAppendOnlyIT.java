package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code autospotting_audit} is append-only on real PostgreSQL (plan-phase-05.md task 5.3,
 * design-core.md §8). The {@code BEFORE UPDATE OR DELETE} trigger of the spine migration raises
 * {@code P0001}, so a row can be inserted but never changed or removed. Complements the broader
 * {@code SpineConstraintsIT}: this IT pins the audit table's append-only contract on its own, by the
 * row shape the synchronous writer produces.
 */
@Tag("containers")
class AuditAppendOnlyIT {

    private static final String P0001_APPEND = "append-only";

    @Test
    void insertSucceeds_butUpdateAndDeleteRaiseP0001() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();

            jdbc.update("INSERT INTO autospotting_audit "
                    + "(event_type, actor_type, actor_user_id, impersonator_user_id, action, outcome, "
                    + " before_state, after_state) "
                    + "VALUES ('STATE_TRANSITION', 'USER', 99, 5, 'REVERT', 'OK', "
                    + " '{\"state\":\"ACTIVE\"}'::jsonb, '{\"state\":\"REVERTING\"}'::jsonb)");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_audit", Long.class)).isEqualTo(1L);

            assertAppendOnly(() -> jdbc.update("UPDATE autospotting_audit SET outcome = 'FAILED'"));
            assertAppendOnly(() -> jdbc.update("DELETE FROM autospotting_audit"));

            // The row is unchanged after the rejected mutations.
            assertThat(jdbc.queryForObject("SELECT outcome FROM autospotting_audit", String.class)).isEqualTo("OK");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_audit", Long.class)).isEqualTo(1L);
        }
    }

    @Test
    void bothIdentitiesPersistForAnImpersonatedActor() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            jdbc.update("INSERT INTO autospotting_audit "
                    + "(event_type, actor_type, actor_user_id, impersonator_user_id, action, outcome) "
                    + "VALUES ('STATE_TRANSITION', 'USER', 99, 5, 'REVERT', 'OK')");
            assertThat(jdbc.queryForObject("SELECT actor_user_id FROM autospotting_audit", Long.class)).isEqualTo(99L);
            assertThat(jdbc.queryForObject("SELECT impersonator_user_id FROM autospotting_audit", Long.class))
                    .isEqualTo(5L);
        }
    }

    private static void assertAppendOnly(Runnable mutation) {
        assertThatThrownBy(mutation::run)
                .isInstanceOf(UncategorizedSQLException.class)
                .hasMessageContaining(P0001_APPEND);
    }
}
