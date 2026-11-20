package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.prices.GcpShapeStore;
import com.xammer.cloud.service.autospotting.ledger.writer.GcpLedgerInputsSource;
import com.xammer.cloud.service.autospotting.ledger.writer.GcpLedgerWriter;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerRunChain;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code ledger-writer:gcp} on real PostgreSQL (plan-phase-30.md 30.4): with no managed GCP group it writes no row, an
 * AWS group is never read, and a seeded GCP group gets one {@code gcp-1} run whose entry is measured, never billable
 * (the reconciliation tier is MEASURED_ONLY until the export is read), and a second pass with the same inputs writes
 * nothing.
 */
@Tag("containers")
class GcpLedgerWriterIT {
    private static final String LEASE = "ledger-writer:gcp";

    private static GcpLedgerWriter writer(DataSource ds) {
        return new GcpLedgerWriter(new GcpLedgerInputsSource(ds), new LedgerRunChain(ds, Ledger17Support.txManager(ds)),
                mock(JobRunner.class), mock(LedgerGate.class), new AutoSpottingMetrics(new SimpleMeterRegistry()),
                mock(AutoSpottingAuditWriter.class));
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void withNoManagedGcpGroupItWritesNoRows_andAnAwsGroupIsNotRead() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            DataSource ds = Ledger17Support.dataSource(db);
            JdbcTemplate jdbc = db.jdbc();
            Ledger17Support.setLease(jdbc, LEASE, "holder-A", 1L);
            jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, state,"
                    + " state_changed_at, created_by, created_at, updated_at) VALUES (gen_random_uuid(), 'AWS', 7, 9,"
                    + " 'us-east-1/asg', 'ACTIVE', now(), 'test', now(), now())");
            jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, state,"
                    + " state_changed_at, created_by, created_at, updated_at) VALUES (gen_random_uuid(), 'GCP', 8, 9,"
                    + " 'p/us-central1/mig', 'UNMANAGED', now(), 'test', now(), now())");
            var source = new GcpLedgerInputsSource(ds);
            assertThat(source.candidateAccounts()).isEmpty();
            assertThat(writer(ds).runDaily(Ledger17Support.handle(LEASE, "holder-A", 1L), LocalDate.parse("2026-11-20")))
                    .isZero();
            for (String table : List.of("savings_ledger_run", "savings_ledger_entry", "savings_ledger_entry_item",
                    "savings_statement", "spot_price_snapshot", "spot_usage_interval", "spot_group_capacity_sample")) {
                assertThat(count(jdbc, table)).as(table).isZero();
            }
        }
    }

    @Test
    void aSeededGcpGroupGetsOneMeasuredGcp1Run_andARerunWritesNothing() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            DataSource ds = Ledger17Support.dataSource(db);
            JdbcTemplate jdbc = db.jdbc();
            Ledger17Support.setLease(jdbc, LEASE, "holder-A", 1L);
            jdbc.execute("CREATE TABLE IF NOT EXISTS cloud_account (id BIGINT PRIMARY KEY, billing_source VARCHAR(32),"
                    + " csp_managed BOOLEAN NOT NULL DEFAULT false)");
            jdbc.update("INSERT INTO cloud_account (id, billing_source, csp_managed) VALUES (7, 'OWN_EXPORT', false)");
            UUID group = UUID.randomUUID();
            UUID snapshot = UUID.randomUUID();
            jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                    + " payload_sha256, captured_at) VALUES (?, ?, 'GCP', 1, true,"
                    + " CAST('{\"templates\":[{\"resource\":{\"properties\":{\"machineType\":\"n2-standard-4\"}}}]}' AS jsonb),"
                    + " 'x', now())", snapshot, group);
            jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, state,"
                    + " state_changed_at, created_by, created_at, updated_at, snapshot_id) VALUES (?, 'GCP', 7, 9,"
                    + " 'p/us-central1/mig', 'ACTIVE', now(), 'test', now(), now(), ?)", group, snapshot);
            jdbc.update("INSERT INTO spot_group_transition (group_id, from_state, to_state, reason_code, actor_type,"
                    + " occurred_at, effective_at) VALUES (?, 'PLANNED', 'ACTIVE', 'TEST', 'SYSTEM',"
                    + " TIMESTAMPTZ '2026-11-18 00:00:00+00', TIMESTAMPTZ '2026-11-18 00:00:00+00')", group);
            jdbc.update("INSERT INTO spot_group_capacity_sample (group_id, observed_at, desired_units, od_floor_units,"
                    + " original_spot_units, source) VALUES (?, TIMESTAMPTZ '2026-11-18 00:00:00+00', 1, 0, 0, 'SWEEP')",
                    group);
            jdbc.update("INSERT INTO spot_usage_interval (id, provider, cloud_account_id, group_id, instance_id,"
                    + " instance_name, instance_type, zone, purchase_option, capacity_weight, start_at, start_source,"
                    + " closed, created_at, updated_at) VALUES (gen_random_uuid(), 'GCP', 7, ?, 'vm-1', 'vm-1',"
                    + " 'n2-standard-4', 'us-central1-a', 'SPOT', 1, TIMESTAMPTZ '2026-11-18 00:00:00+00',"
                    + " 'SWEEP_FIRST_SEEN', false, now(), now())", group);
            new GcpShapeStore(ds).insert(new GcpShapeStore.StoredShape("n2-standard-4", "n2", new BigDecimal("4"),
                    new BigDecimal("16"), false, "gcp-shared-core-v1"), Instant.parse("2026-11-18T00:00:00Z"));
            String[][] prices = {{"ON_DEMAND", "CORE", "0.031611"}, {"ON_DEMAND", "RAM", "0.004237"},
                    {"SPOT", "CORE", "0.00758"}, {"SPOT", "RAM", "0.001016"}};
            for (String[] p : prices) {
                jdbc.update("INSERT INTO spot_price_snapshot (id, provider, source, price_kind, region, zone, sku_or_type,"
                        + " platform, rate, unit, currency, effective_from, effective_to, source_version,"
                        + " source_effective_time_stated, fetched_at, payload_sha256, payload) VALUES (gen_random_uuid(),"
                        + " 'GCP', 'GCP_CATALOG', ?, 'us-central1', NULL, ?, NULL, ?, ?, 'USD',"
                        + " TIMESTAMPTZ '2026-11-01 00:00:00+00', NULL, 'gcp-sku-match-v1:t', true, now(), ?, '{}')",
                        p[0], "n2:" + p[1], new BigDecimal(p[2]), "CORE".equals(p[1]) ? "VCPU_HOUR" : "GIB_HOUR",
                        "sha-" + p[0] + p[1]);
            }
            GcpLedgerWriter w = writer(ds);
            var lease = Ledger17Support.handle(LEASE, "holder-A", 1L);
            assertThat(w.runDaily(lease, LocalDate.parse("2026-11-19"))).isEqualTo(2);
            List<Map<String, Object>> entries = jdbc.queryForList("SELECT e.provider, e.calc_version, e.tier, e.billable,"
                    + " e.non_billable_reason, e.instance_seconds FROM savings_ledger_entry e ORDER BY e.period_start");
            assertThat(entries).isNotEmpty().allSatisfy(e -> {
                assertThat(e.get("provider")).isEqualTo("GCP");
                assertThat(e.get("calc_version")).isEqualTo("gcp-1");
                assertThat(e.get("billable")).isEqualTo(false);
            });
            assertThat(jdbc.queryForList("SELECT DISTINCT job_lease_name FROM savings_ledger_run", String.class))
                    .containsExactly(LEASE);
            assertThat(jdbc.queryForList("SELECT DISTINCT provider FROM savings_ledger_run", String.class))
                    .containsExactly("GCP");
            long entriesBefore = count(jdbc, "savings_ledger_entry");
            long runsBefore = count(jdbc, "savings_ledger_run");
            assertThat(w.runDaily(lease, LocalDate.parse("2026-11-19"))).as("same inputs: no new run").isZero();
            assertThat(count(jdbc, "savings_ledger_entry")).isEqualTo(entriesBefore);
            assertThat(count(jdbc, "savings_ledger_run")).isEqualTo(runsBefore);
        }
    }
}
