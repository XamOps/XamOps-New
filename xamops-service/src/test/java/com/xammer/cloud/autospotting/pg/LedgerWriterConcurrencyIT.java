package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerRunChain;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerWriter;
import com.xammer.cloud.service.autospotting.ledger.writer.LedgerWriter.Outcome;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** AC-LED-04 (design-ledger.md §13.2): one writer wins a window, the rest see its run, a stale holder is fenced out. */
@Tag("containers")
class LedgerWriterConcurrencyIT {

    private static LedgerWriter writer(DataSource ds, Ledger17Support.GoldenInputs inputs) {
        return new LedgerWriter(Ledger17Support.source(inputs),
                new LedgerRunChain(ds, Ledger17Support.txManager(ds)), mock(JobRunner.class), mock(LedgerGate.class),
                new AutoSpottingMetrics(new SimpleMeterRegistry()), mock(AutoSpottingAuditWriter.class));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void fourWritersTwoHolders_oneRunOneEntrySet_fifthCallNoRows_staleHolderFenced() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            DataSource ds = Ledger17Support.dataSource(db);
            JdbcTemplate jdbc = db.jdbc();
            var inputs = Ledger17Support.golden("aws-steady");
            long account = inputs.days().get(0).group().cloudAccountId();
            LocalDate day = inputs.days().get(0).windowStart().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            Ledger17Support.setLease(jdbc, "holder-A", 5L);
            LedgerWriter writer = writer(ds, inputs);

            LeaseHandle a = Ledger17Support.handle("holder-A", 5L);
            LeaseHandle b = Ledger17Support.handle("holder-B", 5L); // two distinct holder ids; only A holds the row
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CyclicBarrier barrier = new CyclicBarrier(4);
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                LeaseHandle h = i == 3 ? b : a;
                results.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        return writer.writeDay(h, account, day, "DAILY");
                    } catch (LeaseLostException e) {
                        return e;
                    }
                }));
            }
            long written = 0;
            for (Future<Object> f : results) {
                Object r = f.get(60, TimeUnit.SECONDS);
                if (r == Outcome.WRITTEN) written++;
            }
            pool.shutdown();
            assertThat(written).isEqualTo(1);
            assertThat(count(jdbc, "savings_ledger_run")).isEqualTo(1);
            int entries = count(jdbc, "savings_ledger_entry");
            assertThat(entries).isEqualTo(inputs.days().stream().filter(d -> d.windowStart().equals(inputs.days().get(0).windowStart())).toList().size());
            assertThat(entries).isPositive();

            // A fifth call with identical inputs creates zero rows.
            int runs = count(jdbc, "savings_ledger_run");
            assertThat(writer.writeDay(a, account, day, "DAILY")).isEqualTo(Outcome.NO_OP);
            assertThat(count(jdbc, "savings_ledger_run")).isEqualTo(runs);
            assertThat(count(jdbc, "savings_ledger_entry")).isEqualTo(entries);

            // A stale holder at generation G-1 is fenced out.
            LeaseHandle stale = Ledger17Support.handle("holder-A", 4L);
            LedgerWriter other = writer(ds, Ledger17Support.golden("aws-90min"));
            assertThatThrownBy(() -> other.writeDay(stale, 1001L, LocalDate.parse("2026-10-02"), "DAILY"))
                    .isInstanceOf(LeaseLostException.class);
            assertThat(count(jdbc, "savings_ledger_run")).isEqualTo(runs);
            assertThat(stale.isLost()).isTrue();
        }
    }

    /** The empty case of the rollout: no managed group means no run and no row at all (plan 17.5). */
    @Test
    void noActiveManagedGroups_writesNoRows() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            DataSource ds = Ledger17Support.dataSource(db);
            JdbcTemplate jdbc = db.jdbc();
            Ledger17Support.setLease(jdbc, "holder-A", 1L);
            // The production source over a database with groups that are not measured: UNMANAGED, PLANNED, GONE, REVERTED.
            for (String state : List.of("UNMANAGED", "PLANNED", "GONE", "REVERTED", "FAILED")) {
                jdbc.update("INSERT INTO spot_group (id, provider, cloud_account_id, client_id, group_key, state,"
                        + " state_changed_at, created_by, created_at, updated_at)"
                        + " VALUES (gen_random_uuid(), 'AWS', 7, 9, ?, ?, now(), 'test', now(), now())",
                        "us-east-1/asg-" + state, state);
            }
            var source = new com.xammer.cloud.service.autospotting.ledger.writer.JdbcLedgerInputsSource(ds);
            assertThat(source.candidateAccounts()).isEmpty();
            LedgerWriter writer = new LedgerWriter(source, new LedgerRunChain(ds, Ledger17Support.txManager(ds)),
                    mock(JobRunner.class), mock(LedgerGate.class), new AutoSpottingMetrics(new SimpleMeterRegistry()),
                    mock(AutoSpottingAuditWriter.class));
            assertThat(writer.runDaily(Ledger17Support.handle("holder-A", 1L), LocalDate.parse("2026-11-20"))).isZero();
            for (String table : List.of("savings_ledger_run", "savings_ledger_entry", "savings_ledger_entry_item",
                    "savings_statement", "spot_price_snapshot", "spot_usage_interval", "spot_group_capacity_sample")) {
                assertThat(count(jdbc, table)).as(table).isZero();
            }
        }
    }
}
