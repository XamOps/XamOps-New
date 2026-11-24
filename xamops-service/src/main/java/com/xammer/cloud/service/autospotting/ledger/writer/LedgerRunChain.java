package com.xammer.cloud.service.autospotting.ledger.writer;

import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The run chain of a window and its one fenced write (design-ledger.md §2.2, §13.2 steps 2 and 6). Runs
 * form a linear chain per {@code (provider, account, window_start)}: one root, one successor per run, both
 * enforced by unique indexes ({@code ux_slr_root}, {@code ux_slr_chain}), so two writers cannot fork it.
 *
 * <p>{@link #append} is one transaction whose <em>first</em> statement is the lease check
 * {@code SELECT generation FROM autospotting_job_lease WHERE name = :n AND holder = :h AND generation = :G AND
 * expires_at > clock_timestamp() FOR UPDATE} (clock_timestamp, like every lease comparison, core §3.1). No
 * row means {@link LeaseLostException} and nothing is written; {@code FOR UPDATE} on the lease row also
 * serializes any two holders that both believe they hold it. A unique violation on the run insert means
 * another writer won the window: the transaction rolls back and {@link ChainRaceException} tells the caller
 * to re-read the chain.
 */
@Repository
public class LedgerRunChain {

    /** The end of a window's chain. */
    public record Head(UUID runId, String inputsSha256, String calcVersion) {
    }

    /** One entry to store: what the calculator decided and the canonical inputs it decided from. */
    public record NewEntry(EntryCalc calc, CalcInputs inputs, String inputsJson, String inputsSha256) {
    }

    /** One run to append. {@code supersedes} is null for the root of the window. */
    public record NewRun(UUID id, String provider, long cloudAccountId, Instant windowStart, Instant windowEnd,
                         String calcVersion, String inputsSha256, UUID supersedes, String trigger,
                         List<NewEntry> entries) {
    }

    /** Another writer inserted the next link of this window first; nothing was written. */
    public static final class ChainRaceException extends RuntimeException {
        public ChainRaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public LedgerRunChain(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The current run of the window: the one no other run supersedes. */
    public Optional<Head> head(String provider, long cloudAccountId, Instant windowStart) {
        return jdbc.query("SELECT r.id, r.inputs_sha256, r.calc_version FROM savings_ledger_run r"
                        + " WHERE r.provider = ? AND r.cloud_account_id = ? AND r.window_start = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM savings_ledger_run s WHERE s.supersedes_run_id = r.id)",
                (rs, i) -> new Head(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                provider, cloudAccountId, Timestamp.from(windowStart)).stream().findFirst();
    }

    /**
     * Appends the run, its entries and the OPEN statement rows in one fenced transaction. {@code inTx}
     * runs inside the same transaction after the inserts (the writer's audit record).
     */
    public void append(LeaseHandle lease, NewRun run, Consumer<NewRun> inTx) {
        if (lease.kind() != LeaseHandle.Kind.JOB) {
            throw new IllegalArgumentException("the ledger writer fences on a JOB lease; got " + lease.kind());
        }
        tx.executeWithoutResult(status -> {
            List<Long> held = jdbc.query("SELECT generation FROM autospotting_job_lease WHERE name = ? AND holder = ?"
                            + " AND generation = ? AND expires_at > clock_timestamp() FOR UPDATE",
                    (rs, i) -> rs.getLong(1), lease.resourceId(), lease.token(), lease.generation());
            if (held.isEmpty()) {
                lease.markLost();
                throw new LeaseLostException("ledger writer lease lost for " + lease.resourceId() + " (token="
                        + lease.token() + ", gen=" + lease.generation() + ")");
            }
            try {
                jdbc.update("INSERT INTO savings_ledger_run (id, provider, cloud_account_id, window_start, window_end,"
                                + " calc_version, inputs_sha256, supersedes_run_id, trigger, job_lease_name,"
                                + " lease_generation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                        run.id(), run.provider(), run.cloudAccountId(), Timestamp.from(run.windowStart()),
                        Timestamp.from(run.windowEnd()), run.calcVersion(), run.inputsSha256(), run.supersedes(),
                        run.trigger(), lease.resourceId(), lease.generation());
            } catch (DuplicateKeyException e) {
                status.setRollbackOnly();
                throw new ChainRaceException("another writer appended to window " + run.windowStart(), e);
            }
            for (NewEntry entry : run.entries()) {
                insertEntry(run, entry, lease.generation());
                openStatement(run, entry.calc());
            }
            inTx.accept(run);
        });
    }

    private void insertEntry(NewRun run, NewEntry n, long generation) {
        EntryCalc e = n.calc();
        jdbc.update("INSERT INTO savings_ledger_entry (id, run_id, entry_type, provider, cloud_account_id, client_id,"
                        + " group_id, period_start, period_end, statement_month, calc_version, tier,"
                        + " reconciliation_status, billable, non_billable_reason, unknown_inputs, currency,"
                        + " instance_seconds, counterfactual_unit_seconds, counterfactual_list_cost,"
                        + " effective_rate_adjustment, counterfactual_cost, spot_cost, gross_savings,"
                        + " commitment_deduction, net_savings, net_savings_lower_bound, spot_cost_source,"
                        + " price_snapshot_ids, contract_id, exclusions, inputs, inputs_sha256, lease_generation,"
                        + " created_at) VALUES (?, ?, 'ACCRUAL', ?, ?, ?, CAST(? AS uuid), ?, ?, ?, ?, ?, 'PENDING',"
                        + " ?, ?, CAST(? AS text[]), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid[]),"
                        + " CAST(? AS uuid), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, now())",
                UUID.randomUUID(), run.id(), run.provider(), e.cloudAccountId(), e.clientId(), e.groupId(),
                Timestamp.from(e.periodStart()), Timestamp.from(e.periodEnd()), Date.valueOf(e.statementMonth()),
                e.calcVersion(), e.tier().name(), e.billable(), e.nonBillableReason(), array(e.unknownInputs()),
                e.currency(), e.instanceSeconds(), e.counterfactualUnitSeconds(), e.counterfactualListCost(),
                e.effectiveRateAdjustment(), e.counterfactualCost(), e.spotCost(), e.grossSavings(),
                e.commitmentDeduction(), e.netSavings(), e.netSavingsLowerBound(), e.spotCostSource(),
                array(e.priceSnapshotIds()), e.contractId(), CanonicalJson.compact(e.exclusions()),
                n.inputsJson(), n.inputsSha256(), generation);
    }

    /** The OPEN statement row of the account-month, created once; its content is built at close (Phase 32). */
    private void openStatement(NewRun run, EntryCalc e) {
        jdbc.update("INSERT INTO savings_statement (id, provider, cloud_account_id, client_id, statement_month,"
                        + " status, currency, created_at) VALUES (?, ?, ?, ?, ?, 'OPEN', 'USD', now())"
                        + " ON CONFLICT (provider, cloud_account_id, statement_month) DO NOTHING",
                UUID.randomUUID(), run.provider(), run.cloudAccountId(), e.clientId(), Date.valueOf(e.statementMonth()));
    }

    /** A PostgreSQL array literal of tokens that never need quoting (reason codes, UUIDs). */
    static String array(List<String> values) {
        for (String v : values) {
            if (!v.matches("[A-Za-z0-9_.-]+")) {
                throw new IllegalArgumentException("not an array-safe token: " + v);
            }
        }
        return "{" + String.join(",", values) + "}";
    }
}
