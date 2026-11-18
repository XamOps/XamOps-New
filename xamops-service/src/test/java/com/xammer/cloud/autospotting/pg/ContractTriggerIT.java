package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code autospotting_contract} trigger (plan-phase-15.md task 15.3; design-ledger.md §2.3): contracts
 * of one client never overlap, dates are never backdated, terms are immutable after insert, a contract
 * is never deleted, and two concurrent inserts for one client serialize on the advisory lock.
 */
@Tag("containers")
class ContractTriggerIT {

    private static final String P0001 = "P0001";

    @Test
    void overlapIsRejected_adjacentAndOtherClientsAreNot() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1, "now() + interval '1 day'", "now() + interval '10 days'");
            // Overlapping at the start, inside, around, and open-ended over it: all rejected.
            for (String[] range : new String[][] {
                    {"now() + interval '5 days'", "now() + interval '20 days'"},
                    {"now() + interval '2 days'", "now() + interval '3 days'"},
                    {"now() + interval '12 hours'", "now() + interval '30 days'"},
                    {"now() + interval '9 days'", "NULL"}}) {
                assertThat(LedgerSql.message(() -> LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1, range[0], range[1])))
                        .as(range[0] + " .. " + range[1]).contains("overlaps");
            }
            // The range is half-open: a contract starting exactly when the first ends is fine.
            LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1, "now() + interval '10 days'", "now() + interval '20 days'");
            // Another client is independent.
            LedgerSql.insertContract(jdbc, UUID.randomUUID(), 2, "now() + interval '2 days'", "now() + interval '3 days'");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM autospotting_contract", Long.class)).isEqualTo(3L);
        }
    }

    @Test
    void backdatedDatesAreRejected_onInsertAndUpdate() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1,
                    "now() - interval '1 day'", "NULL"))).isEqualTo(P0001);
            assertThat(LedgerSql.message(() -> LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1,
                    "now() - interval '1 day'", "NULL"))).contains("backdated");
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1,
                    "now() + interval '1 day'", "now() - interval '1 hour'"))).isNotNull();
            UUID id = UUID.randomUUID();
            LedgerSql.insertContract(jdbc, id, 1, "now() + interval '1 day'", "NULL");
            assertThat(LedgerSql.message(() -> jdbc.update(
                    "UPDATE autospotting_contract SET effective_to = now() - interval '1 hour' WHERE id = ?", id)))
                    .contains("backdated");
            assertThat(jdbc.queryForObject("SELECT effective_to IS NULL FROM autospotting_contract WHERE id = ?",
                    Boolean.class, id)).isTrue();
        }
    }

    @Test
    void termsAreImmutable_endingEarlierIsTheOnlyChange_andNothingIsDeleted() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID id = UUID.randomUUID();
            LedgerSql.insertContract(jdbc, id, 1, "now() + interval '1 day'", "now() + interval '30 days'");
            for (String change : new String[] {"share_pct = 50", "monthly_cap = 5000.00", "monthly_cap = NULL",
                    "declared_discount_pct = 10", "client_id = 9", "effective_from = now() + interval '2 days'"}) {
                assertThat(LedgerSql.sqlState(() -> jdbc.update("UPDATE autospotting_contract SET " + change
                        + " WHERE id = ?", id))).as(change).isEqualTo(P0001);
            }
            // Extending is refused; ending earlier at a future instant is allowed.
            assertThat(LedgerSql.message(() -> jdbc.update("UPDATE autospotting_contract SET effective_to ="
                    + " now() + interval '40 days' WHERE id = ?", id))).contains("never extended");
            jdbc.update("UPDATE autospotting_contract SET effective_to = now() + interval '5 days' WHERE id = ?", id);
            assertThat(LedgerSql.sqlState(() -> jdbc.update("DELETE FROM autospotting_contract WHERE id = ?", id)))
                    .isEqualTo(P0001);
            // After ending early, the freed range can be used by a new record.
            LedgerSql.insertContract(jdbc, UUID.randomUUID(), 1, "now() + interval '6 days'", "NULL");
        }
    }

    @Test
    void checksRejectOutOfRangeTerms() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            String[][] bad = {
                    {"share_pct", "0"}, {"share_pct", "100.000001"}, {"share_pct", "-1"},
                    {"monthly_cap", "0"}, {"monthly_cap", "-5"},
                    {"cap_currency", "'EUR'"}, {"declared_discount_pct", "100"}, {"declared_discount_pct", "-0.1"}};
            for (String[] b : bad) {
                String sql = "INSERT INTO autospotting_contract (id, client_id, share_pct, monthly_cap, cap_currency,"
                        + " declared_discount_pct, effective_from, created_by, created_at) VALUES (?, 1, "
                        + ("share_pct".equals(b[0]) ? b[1] : "20") + ", "
                        + ("monthly_cap".equals(b[0]) ? b[1] : "NULL") + ", "
                        + ("cap_currency".equals(b[0]) ? b[1] : "'USD'") + ", "
                        + ("declared_discount_pct".equals(b[0]) ? b[1] : "NULL")
                        + ", now() + interval '1 day', 't', now())";
                assertThat(LedgerSql.sqlState(() -> jdbc.update(sql, UUID.randomUUID()))).as(b[0] + "=" + b[1])
                        .isEqualTo("23514");
            }
            // The boundaries that are allowed: 100 percent share, 0 declared discount (declared none).
            jdbc.update("INSERT INTO autospotting_contract (id, client_id, share_pct, declared_discount_pct,"
                    + " effective_from, created_by, created_at) VALUES (?, 1, 100, 0, now() + interval '1 day', 't', now())",
                    UUID.randomUUID());
            assertThat(jdbc.queryForObject("SELECT declared_discount_pct FROM autospotting_contract", java.math.BigDecimal.class)
                    .signum()).isZero();
        }
    }

    @Test
    void concurrentInsertsForOneClient_serializeAndExactlyOneWins() throws Exception {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            CountDownLatch firstInserted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
                try (Connection c = db.connect()) {
                    c.setAutoCommit(false);
                    insert(c);
                    firstInserted.countDown();
                    releaseFirst.await(20, TimeUnit.SECONDS);
                    c.commit();
                } catch (SQLException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(firstInserted.await(20, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
                try (Connection c = db.connect()) {
                    c.setAutoCommit(false);
                    insert(c);
                    c.commit();
                    return "inserted";
                } catch (SQLException e) {
                    return e.getSQLState() + ":" + e.getMessage();
                }
            });
            // The second insert waits on the first's advisory lock; it cannot finish before the first commits.
            Thread.sleep(700);
            assertThat(second.isDone()).as("second insert blocked on the client's advisory lock").isFalse();
            releaseFirst.countDown();
            first.get(20, TimeUnit.SECONDS);
            String outcome = second.get(20, TimeUnit.SECONDS);
            assertThat(outcome).startsWith(P0001).contains("overlaps");
            assertThat(db.jdbc().queryForObject("SELECT count(*) FROM autospotting_contract", Long.class)).isEqualTo(1L);
        }
    }

    private static void insert(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO autospotting_contract (id, client_id, share_pct,"
                + " effective_from, created_by, created_at) VALUES (?, 77, 20, now() + interval '1 day', 't', now())")) {
            ps.setObject(1, UUID.randomUUID());
            ps.executeUpdate();
        }
    }
}
