package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Both harness modes start, and Flyway reports exactly the migrations each mode should apply.
 * Needs Docker (or {@code -Dpg.harness.admin-url} for a local throwaway server).
 */
@Tag("containers")
class PgHarnessSmokeTest {

    @Test
    void emptySchema_appliesOnlyTheAutoSpottingMigrations() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            System.out.println("[PgHarness] server=" + db.server().description() + " server_version="
                    + db.jdbc().queryForObject("SHOW server_version", String.class));

            assertThat(db.baselineVersion()).isEmpty();
            assertThat(db.appliedVersions())
                    .containsExactlyElementsOf(PgHarness.autoSpottingMigrations().stream()
                            .map(m -> m.version().getVersion()).toList())
                    .contains("20261006.1", "20261009.1");
            // Nothing but what those migrations create: no Hibernate tables in this mode.
            assertThat(PgHarness.tableExists(db.jdbc(), "cloud_account")).isFalse();
            assertThat(PgHarness.tableExists(db.jdbc(), "daily_spot_savings")).isTrue();
        }
    }

    @Test
    void hibernateBase_baselinesAt20260929_2AndAppliesEverythingAfter() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.baselineVersion()).contains(PgHarness.BASELINE_VERSION);
            assertThat(db.appliedVersions())
                    .containsExactlyElementsOf(PgHarness.migrationsAfterBaseline().stream()
                            .map(m -> m.version().getVersion()).toList())
                    .contains("20261006.1", "20261009.1");
            // The base schema came from the entities.
            assertThat(PgHarness.tableExists(db.jdbc(), "cloud_account")).isTrue();
            assertThat(PgHarness.tableExists(db.jdbc(), "spot_optimization_event")).isTrue();
            assertThat(PgHarness.tableExists(db.jdbc(), "autospotting_settings")).isTrue();
        }
    }

    @Test
    void containerRunsThePinnedEngine() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            String version = db.jdbc().queryForObject("SHOW server_version", String.class);
            if (db.server().description().startsWith("Testcontainers")) {
                assertThat(version).startsWith("15.13");
            } else {
                // A local server chosen with -Dpg.harness.admin-url; the CI job always uses the container.
                assertThat(version).isNotBlank();
            }
        }
    }
}
