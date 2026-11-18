package com.xammer.cloud.service.autospotting.ledger.writer;

import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.ledger.LedgerGate;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;
import com.xammer.cloud.service.autospotting.ledger.calc.CanonicalJson;
import com.xammer.cloud.service.autospotting.ledger.calc.EntryCalc;
import com.xammer.cloud.service.autospotting.ledger.calc.SavingsCalculator;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * {@code ledger-writer:aws} (design-ledger.md §13.1, §13.2, FR-LED-16, AC-LED-04; plan-phase-17.md 17.3):
 * the single leased writer of runs and entries. Daily at 02:30 UTC it writes day D-1; it also runs on a
 * trigger and on demand for {@code PRICE_RESOLVED}.
 *
 * <p>For each {@code (account, day)} that needs a run it does the eight steps of §13.2:
 * <ol>
 *   <li>holds the {@code ledger-writer:aws} job lease at generation G (the {@link JobRunner} body gets the
 *       handle);</li>
 *   <li>loads the end of the window's run chain ({@link LedgerRunChain#head});</li>
 *   <li>builds the canonical inputs of every group that earns an entry and hashes them
 *       ({@code inputs_sha256}; the empty group list hashes too, for an empty run);</li>
 *   <li>stops (no rows) when the head has the same {@code inputs_sha256} and the current calc version;</li>
 *   <li>computes every entry in memory with {@link SavingsCalculator};</li>
 *   <li>writes the run (superseding the head), entries and the OPEN statement row in one transaction whose
 *       first statement is the {@code FOR UPDATE} lease check; a unique violation on the chain means
 *       another writer won, so it rolls back, re-reads and decides again;</li>
 *   <li>after the commit meters {@code autospotting.ledger.entries{provider,tier}};</li>
 *   <li>the audit record is part of the transaction (the audit writer requires one).</li>
 * </ol>
 *
 * <p><strong>Which days need a run.</strong> From {@code f(M)}, the first day of the month on which any group
 * of the account earns an entry, to D-1, every day gets a run, an empty one (zero entries) on days with no
 * entry. An account with no group ever measured has no {@code f(M)} and gets no run at all: nothing is
 * measured, so the writer writes nothing (plan 17.5 says "empty runs only"; the design's {@code f(M)} rule
 * means none until the first conversion, which is what the evidence records).
 *
 * <p>Shadow only: this writes measurement. It never invoices and never marks anything billable on a
 * statement; {@code autospotting.ledger.billing-enabled} stays false and nothing here reads it.
 */
@Service
public class LedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(LedgerWriter.class);
    public static final String JOB = "ledger-writer:aws";
    public static final String PROVIDER = "AWS";
    static final int MAX_ATTEMPTS = 3;
    static final Duration LEASE_CHECK = Duration.ofSeconds(30);

    /** What one {@link #writeDay} call did. */
    public enum Outcome {
        /** A run was appended. */
        WRITTEN,
        /** The head already has these inputs and calc version: zero rows created. */
        NO_OP,
        /** A later day with no entry yet and none before it in the month: nothing to record. */
        NOT_STARTED
    }

    /** The canonical inputs of a run: the window, the account, the calc version and every entry's inputs. */
    record RunInputs(Instant windowStart, Instant windowEnd, long cloudAccountId, String calcVersion,
                     List<CalcInputs> groups) {
    }

    private final LedgerInputsSource source;
    private final LedgerRunChain chain;
    private final SavingsCalculator calculator = new SavingsCalculator();
    private final JobRunner jobRunner;
    private final LedgerGate gate;
    private final AutoSpottingMetrics metrics;
    private final AutoSpottingAuditWriter audit;
    private final Clock clock;
    private final java.util.concurrent.atomic.AtomicReference<Instant> lastSuccess;

    @org.springframework.beans.factory.annotation.Autowired
    public LedgerWriter(LedgerInputsSource source, LedgerRunChain chain, JobRunner jobRunner, LedgerGate gate,
                        AutoSpottingMetrics metrics, AutoSpottingAuditWriter audit) {
        this(source, chain, jobRunner, gate, metrics, audit, Clock.systemUTC());
    }

    public LedgerWriter(LedgerInputsSource source, LedgerRunChain chain, JobRunner jobRunner, LedgerGate gate,
                 AutoSpottingMetrics metrics, AutoSpottingAuditWriter audit, Clock clock) {
        this.source = source;
        this.chain = chain;
        this.jobRunner = jobRunner;
        this.gate = gate;
        this.metrics = metrics;
        this.audit = audit;
        this.clock = clock;
        this.lastSuccess = new java.util.concurrent.atomic.AtomicReference<>(clock.instant());
        // "Ledger writer missed run" (§6.4): seconds since the last completed daily pass, 0 while the writer is off.
        metrics.bindLedgerLastSuccessAge(JOB, () -> gate.awsWriterOn()
                ? Duration.between(lastSuccess.get(), clock.instant()).getSeconds() : 0L);
    }

    // ------------------------------------------------------------------ triggers

    /** Daily at 02:30 UTC for D-1. */
    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")
    public void daily() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        jobRunner.runLeased(JOB, "daily-" + today, (LeaseHandle lease) -> {
            if (gate.awsWriterOn()) {
                runDaily(lease, today.minusDays(1));
            }
        });
    }

    /** On demand for a day, e.g. {@code PRICE_RESOLVED} after a late price lands (§13.1). */
    public void requestRun(long cloudAccountId, LocalDate day, String trigger) {
        jobRunner.runLeased(JOB, null, (LeaseHandle lease) -> {
            if (gate.awsWriterOn()) {
                writeDay(lease, cloudAccountId, day, trigger);
            }
        });
    }

    // ------------------------------------------------------------------ the daily pass

    /**
     * Writes every account's runs from {@code f(M)} to {@code lastDay}. Returns the number of runs
     * appended; an install with no measured group returns 0 and writes no row.
     */
    public int runDaily(LeaseHandle lease, LocalDate lastDay) {
        int written = 0;
        for (long accountId : source.candidateAccounts()) {
            written += runAccount(lease, accountId, lastDay, "DAILY");
        }
        if (written == 0) {
            log.debug("ledger-writer:aws wrote no runs for {}", lastDay);
        }
        // A pass that finished without an exception is a success, even with nothing to write (liveness).
        lastSuccess.set(clock.instant());
        return written;
    }

    int runAccount(LeaseHandle lease, long accountId, LocalDate lastDay, String trigger) {
        Optional<LocalDate> first = source.firstRelevantDay(accountId);
        if (first.isEmpty()) {
            return 0;
        }
        LocalDate from = lastDay.withDayOfMonth(1);
        if (first.get().isAfter(from)) {
            from = first.get();
        }
        int written = 0;
        boolean started = false;
        for (LocalDate day = from; !day.isAfter(lastDay); day = day.plusDays(1)) {
            lease.assertHeld(LEASE_CHECK);
            Outcome outcome = writeDay(lease, accountId, day, trigger, started);
            if (outcome != Outcome.NOT_STARTED) {
                started = true;
            }
            if (outcome == Outcome.WRITTEN) {
                written++;
            }
        }
        return written;
    }

    /** One window, for an on-demand or trigger run: the month is taken as already started. */
    public Outcome writeDay(LeaseHandle lease, long accountId, LocalDate day, String trigger) {
        return writeDay(lease, accountId, day, trigger, true);
    }

    // ------------------------------------------------------------------ one account and day

    Outcome writeDay(LeaseHandle lease, long accountId, LocalDate day, String trigger, boolean monthStarted) {
        Instant start = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        // Steps 3 and 5: canonical inputs and the entries, in memory. The inputs do not depend on the head.
        Map<String, Computed> byGroup = new TreeMap<>();
        for (CalcInputs in : source.inputsFor(accountId, day)) {
            calculator.calculate(in).ifPresent(e -> byGroup.put(in.group().groupId(), new Computed(in, e)));
        }
        List<Computed> computed = new ArrayList<>(byGroup.values());
        if (computed.isEmpty() && !monthStarted) {
            return Outcome.NOT_STARTED;
        }
        List<CalcInputs> entryInputs = computed.stream().map(Computed::inputs).toList();
        String sha = CanonicalJson.hash(new RunInputs(start, end, accountId, SavingsCalculator.VERSION, entryInputs));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // Step 2: the end of the chain. Step 4: an equal head is a no-op.
            Optional<LedgerRunChain.Head> head = chain.head(PROVIDER, accountId, start);
            if (head.isPresent() && head.get().inputsSha256().equals(sha)
                    && SavingsCalculator.VERSION.equals(head.get().calcVersion())) {
                return Outcome.NO_OP;
            }
            List<LedgerRunChain.NewEntry> entries = new ArrayList<>();
            for (Computed c : computed) {
                String json = CanonicalJson.compact(c.inputs());
                entries.add(new LedgerRunChain.NewEntry(c.entry(), c.inputs(), json, CanonicalJson.sha256(json)));
            }
            LedgerRunChain.NewRun run = new LedgerRunChain.NewRun(UUID.randomUUID(), PROVIDER, accountId, start, end,
                    SavingsCalculator.VERSION, sha, head.map(LedgerRunChain.Head::runId).orElse(null), trigger,
                    entries);
            try {
                // Step 6: one fenced transaction. The audit record is written inside it (step 7 follows the commit).
                chain.append(lease, run, r -> auditRun(r, accountId, day));
            } catch (LedgerRunChain.ChainRaceException race) {
                log.debug("ledger-writer:aws lost the chain race for account {} day {}; re-reading (attempt {})",
                        accountId, day, attempt);
                continue;
            } catch (LeaseLostException lost) {
                throw lost;
            }
            meter(computed);
            return Outcome.WRITTEN;
        }
        // Another writer kept winning; whatever it wrote is the chain now, and the next trigger decides again.
        return Outcome.NO_OP;
    }

    private record Computed(CalcInputs inputs, EntryCalc entry) {
    }

    private void auditRun(LedgerRunChain.NewRun run, long accountId, LocalDate day) {
        audit.write(AuditRecord.builder("LEDGER_RUN", Actor.system(), "WRITE_RUN", "SUCCESS")
                .provider(PROVIDER).cloudAccountId(accountId)
                .details("{\"runId\":\"" + run.id() + "\",\"day\":\"" + day + "\",\"trigger\":\"" + run.trigger()
                        + "\",\"entries\":" + run.entries().size() + ",\"calcVersion\":\"" + run.calcVersion()
                        + "\",\"supersedes\":" + (run.supersedes() == null ? "null" : "\"" + run.supersedes() + "\"")
                        + "}")
                .build());
    }

    /** {@code autospotting.ledger.entries{provider,tier}} after the commit (§13.2 step 7). */
    private void meter(List<Computed> computed) {
        Map<String, Long> byTier = new TreeMap<>();
        computed.stream().map(c -> c.entry().tier().name()).sorted(Comparator.naturalOrder())
                .forEach(t -> byTier.merge(t, 1L, Long::sum));
        byTier.forEach((tier, n) -> metrics.ledgerEntries(Provider.AWS, tier, n));
        computed.stream().filter(c -> "UNKNOWN".equals(c.entry().tier().name()))
                .forEach(c -> metrics.ledgerUnknown(Provider.AWS));
    }
}
