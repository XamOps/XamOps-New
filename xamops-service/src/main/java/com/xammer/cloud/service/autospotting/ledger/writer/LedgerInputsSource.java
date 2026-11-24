package com.xammer.cloud.service.autospotting.ledger.writer;

import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Where the writer gets what the calculator needs (design-ledger.md §13.2 step 3). The production
 * implementation reads the working tables; tests supply fixtures (the golden fixtures, a fixed group)
 * so the writer is exercised end to end against PostgreSQL without a cloud or a live schema of groups.
 */
public interface LedgerInputsSource {

    /** AWS accounts that have at least one group that was ever measured (the only accounts that can have a run). */
    List<Long> candidateAccounts();

    /**
     * The first UTC day on which any group of the account has something to record: the day of its first
     * measured state (design-ledger.md §13.2 {@code f(M)}). Empty when the account has no such group, in
     * which case it has no run at all: nothing is measured, so nothing is written.
     */
    Optional<LocalDate> firstRelevantDay(long cloudAccountId);

    /**
     * One {@link CalcInputs} for every candidate group of the account for the UTC day, whether or not the
     * calculator will find an entry in it. Order does not matter; the writer sorts.
     */
    List<CalcInputs> inputsFor(long cloudAccountId, LocalDate day);
}
