package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand;
import com.xammer.cloud.service.autospotting.ledger.LedgerRecomputeCommand.StoredEntry;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerRunChain;
import com.xammer.cloud.service.autospotting.ledger.writer.GcpLedgerWriter;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerWriter;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Golden fixtures through the writer and back out of PostgreSQL (ux §9.3, plan-phase-17.md 17.3): every
 * money column reads back with exactly the text the calculator produced (NUMERIC(24,10) round trip), and the
 * stored entry recomputes from its stored inputs with zero diff.
 */
@Tag("containers")
class LedgerWriterGoldenIT {

    @Test
    void everyGoldenFixtureRoundTripsThroughPostgresByteIdentical() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            DataSource ds = Ledger17Support.dataSource(db);
            JdbcTemplate jdbc = db.jdbc();
            Ledger17Support.setLease(jdbc, "holder-A", 1L);
            var handle = Ledger17Support.handle("holder-A", 1L);
            LedgerRecomputeCommand recompute = new LedgerRecomputeCommand(null, null);
            SavingsCalculator calculator = new SavingsCalculator();
            int checked = 0;
            Ledger17Support.setLease(jdbc, "ledger-writer:gcp", "holder-A", 1L);
            var gcpHandle = Ledger17Support.handle("ledger-writer:gcp", "holder-A", 1L);
            for (String name : Ledger17Support.goldenNames()) {
                var inputs = Ledger17Support.golden(name);
                boolean gcp = inputs.days().stream().anyMatch(d -> "GCP".equals(d.provider()));
                String provider = gcp ? "GCP" : "AWS";
                var lease = gcp ? gcpHandle : handle;
                LedgerWriter writer = gcp ? new GcpLedgerWriter(Ledger17Support.source(inputs),
                        new LedgerRunChain(ds, Ledger17Support.txManager(ds)), mock(JobRunner.class),
                        mock(LedgerGate.class), new AutoSpottingMetrics(new SimpleMeterRegistry()),
                        mock(AutoSpottingAuditWriter.class))
                        : new LedgerWriter(Ledger17Support.source(inputs),
                        new LedgerRunChain(ds, Ledger17Support.txManager(ds)), mock(JobRunner.class),
                        mock(LedgerGate.class), new AutoSpottingMetrics(new SimpleMeterRegistry()),
                        mock(AutoSpottingAuditWriter.class));
                for (CalcInputs day : inputs.days()) {
                    java.time.LocalDate d = day.windowStart().atZone(ZoneOffset.UTC).toLocalDate();
                    writer.writeDay(lease, day.group().cloudAccountId(), d, "DAILY");
                    EntryCalc expected = calculator.calculate(day).orElse(null);
                    // The entry of the head run of this window for this group.
                    List<Map<String, Object>> rows = jdbc.queryForList("SELECT e.* FROM savings_ledger_entry e"
                            + " JOIN savings_ledger_run r ON r.id = e.run_id WHERE r.provider = ? AND r.cloud_account_id = ?"
                            + " AND r.window_start = ? AND e.group_id = CAST(? AS uuid) AND NOT EXISTS (SELECT 1 FROM"
                            + " savings_ledger_run s WHERE s.supersedes_run_id = r.id)", provider, day.group().cloudAccountId(),
                            java.sql.Timestamp.from(day.windowStart()), day.group().groupId());
                    if (expected == null) {
                        continue; // no entry earned that day; the window got an empty or unchanged run
                    }
                    assertThat(rows).as(name + " " + d).hasSize(1);
                    Map<String, Object> row = rows.get(0);
                    assertThat(text(row, "counterfactual_list_cost")).as(name + " " + d).isEqualTo(expected.counterfactualListCost().toPlainString());
                    assertThat(text(row, "effective_rate_adjustment")).isEqualTo(expected.effectiveRateAdjustment().toPlainString());
                    assertThat(text(row, "counterfactual_cost")).isEqualTo(expected.counterfactualCost().toPlainString());
                    assertThat(text(row, "spot_cost")).isEqualTo(expected.spotCost().toPlainString());
                    assertThat(text(row, "gross_savings")).isEqualTo(expected.grossSavings().toPlainString());
                    assertThat(text(row, "commitment_deduction")).isEqualTo(expected.commitmentDeduction().toPlainString());
                    assertThat(text(row, "net_savings")).isEqualTo(expected.netSavings().toPlainString());
                    assertThat(text(row, "counterfactual_unit_seconds")).isEqualTo(expected.counterfactualUnitSeconds().toPlainString());
                    if (expected.netSavingsLowerBound() == null) {
                        assertThat(row.get("net_savings_lower_bound")).isNull();
                    } else {
                        assertThat(text(row, "net_savings_lower_bound")).isEqualTo(expected.netSavingsLowerBound().toPlainString());
                    }
                    assertThat(row.get("tier")).isEqualTo(expected.tier().name());
                    assertThat(row.get("billable")).isEqualTo(expected.billable());
                    assertThat(row.get("reconciliation_status")).isEqualTo("PENDING");

                    // Back out of PG and through the recompute tool: zero diff.
                    @SuppressWarnings("unchecked")
                    var unknown = (java.sql.Array) row.get("unknown_inputs");
                    List<String> unknownInputs = new ArrayList<>();
                    try {
                        for (Object o : (Object[]) unknown.getArray()) unknownInputs.add(String.valueOf(o));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    StoredEntry stored = new StoredEntry(String.valueOf(row.get("id")), (String) row.get("calc_version"),
                            String.valueOf(row.get("inputs")), (String) row.get("inputs_sha256"), (String) row.get("tier"),
                            (Boolean) row.get("billable"), (String) row.get("non_billable_reason"), unknownInputs,
                            ((Number) row.get("instance_seconds")).longValue(), (BigDecimal) row.get("counterfactual_unit_seconds"),
                            (BigDecimal) row.get("counterfactual_list_cost"), (BigDecimal) row.get("effective_rate_adjustment"),
                            (BigDecimal) row.get("counterfactual_cost"), (BigDecimal) row.get("spot_cost"),
                            (BigDecimal) row.get("gross_savings"), (BigDecimal) row.get("commitment_deduction"),
                            (BigDecimal) row.get("net_savings"), (BigDecimal) row.get("net_savings_lower_bound"),
                            String.valueOf(row.get("exclusions")));
                    var result = recompute.recompute(stored, List.of(), Map.of());
                    assertThat(result.diffs()).as(name + " " + d).isEmpty();
                    checked++;
                }
            }
            assertThat(checked).isGreaterThan(50);
            assertThat(jdbc.queryForList("SELECT DISTINCT calc_version FROM savings_ledger_entry WHERE provider = 'GCP'",
                    String.class)).containsExactly("gcp-1");
            // Every statement the writer opened is OPEN, USD, with no billing content.
            assertThat(jdbc.queryForList("SELECT DISTINCT status || '/' || currency || '/' || coalesce(invoiceable::text,'null')"
                    + " || '/' || coalesce(total_billable::text,'null') FROM savings_statement", String.class))
                    .containsExactly("OPEN/USD/null/null");
        }
    }

    private static String text(Map<String, Object> row, String column) {
        return ((BigDecimal) row.get(column)).toPlainString();
    }
}
