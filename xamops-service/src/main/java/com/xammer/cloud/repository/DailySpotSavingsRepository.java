package com.xammer.cloud.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.xammer.cloud.domain.DailySpotSavings;

@Repository
public interface DailySpotSavingsRepository extends JpaRepository<DailySpotSavings, Long> {

    Optional<DailySpotSavings> findByAccountIdAndDate(Long accountId, LocalDate date);

    @Query("SELECT SUM(d.amountSaved) FROM DailySpotSavings d")
    Double getTotalSavings();

    List<DailySpotSavings> findByAccountIdAndDateAfterOrderByDateAsc(Long accountId, LocalDate startDate);

    List<DailySpotSavings> findByAccountIdAndDateBetweenOrderByDateAsc(
            Long accountId, LocalDate start, LocalDate end);

    @Query("SELECT d.date, SUM(d.amountSaved), SUM(d.swapCount) FROM DailySpotSavings d WHERE d.date >= :startDate GROUP BY d.date ORDER BY d.date ASC")
    List<Object[]> getAggregatedHistory(LocalDate startDate);
}
