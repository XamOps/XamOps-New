package com.xammer.cloud.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.xammer.cloud.domain.DailySpotSavings;

/**
 * Legacy savings rows (D-12): every row is {@code legacy = true} and never billable
 * ({@code ck_dss_legacy}). A realized total reads only {@code legacy = false} rows, so it is 0 until
 * the ledger exists; legacy amounts are read separately and shown only as "Legacy (unverified)"
 * (AC-LED-11).
 */
@Repository
public interface DailySpotSavingsRepository extends JpaRepository<DailySpotSavings, Long> {

    Optional<DailySpotSavings> findByAccountIdAndDate(Long accountId, LocalDate date);

    /** Realized total across all accounts: non-legacy rows only. */
    @Query("SELECT SUM(d.amountSaved) FROM DailySpotSavings d WHERE d.legacy = false")
    Double getTotalSavings();

    /** Legacy total across all accounts, reported apart from every realized total. */
    @Query("SELECT SUM(d.amountSaved) FROM DailySpotSavings d WHERE d.legacy = true")
    Double getLegacyTotalSavings();

    /**
     * Every row in the range, legacy or not. Only for callers that label each row they return
     * (the AWS and GCP history points carry {@code legacy}); never for a total.
     */
    List<DailySpotSavings> findByAccountIdAndDateBetweenOrderByDateAsc(
            Long accountId, LocalDate start, LocalDate end);

    /** Non-legacy rows in the range: the only rows that may count as realized. */
    List<DailySpotSavings> findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(
            Long accountId, LocalDate start, LocalDate end);

    /** Realized per-day totals across all accounts: non-legacy rows only. */
    @Query("SELECT d.date, SUM(d.amountSaved), SUM(d.swapCount) FROM DailySpotSavings d WHERE d.legacy = false AND d.date >= :startDate GROUP BY d.date ORDER BY d.date ASC")
    List<Object[]> getAggregatedHistory(LocalDate startDate);

    /** Legacy per-day totals across all accounts, for the separate "Legacy (unverified)" series. */
    @Query("SELECT d.date, SUM(d.amountSaved), SUM(d.swapCount) FROM DailySpotSavings d WHERE d.legacy = true AND d.date >= :startDate GROUP BY d.date ORDER BY d.date ASC")
    List<Object[]> getLegacyAggregatedHistory(LocalDate startDate);

    /** Sum of the legacy rows of these accounts in {@code [start, end]}; null when there are none. */
    @Query("SELECT SUM(d.amountSaved) FROM DailySpotSavings d WHERE d.legacy = true AND d.account.id IN :accountIds AND d.date BETWEEN :start AND :end")
    Double sumLegacyAmount(Collection<Long> accountIds, LocalDate start, LocalDate end);

    @Modifying
    @Transactional
    void deleteByAccountId(Long accountId);
}
