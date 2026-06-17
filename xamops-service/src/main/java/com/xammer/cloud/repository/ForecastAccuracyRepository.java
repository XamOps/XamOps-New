package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ForecastAccuracyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ForecastAccuracyRepository extends JpaRepository<ForecastAccuracyRecord, Long> {

    /**
     * Records that are due for evaluation: still pending and the target day has closed.
     */
    List<ForecastAccuracyRecord> findByStatusAndTargetDateLessThanEqual(String status, LocalDate targetDate);

    /**
     * Idempotency check for recording — returns the set of target dates already stored
     * for a given forecast batch.
     */
    @Query("SELECT r.targetDate FROM ForecastAccuracyRecord r "
            + "WHERE r.provider = :provider AND r.accountId = :accountId "
            + "AND r.serviceName = :serviceName AND r.forecastDate = :forecastDate")
    List<LocalDate> findExistingTargetDates(@Param("provider") String provider,
                                            @Param("accountId") String accountId,
                                            @Param("serviceName") String serviceName,
                                            @Param("forecastDate") LocalDate forecastDate);

    /**
     * Evaluated records for a specific account/service within a window — used for metrics.
     */
    @Query("SELECT r FROM ForecastAccuracyRecord r "
            + "WHERE r.provider = :provider AND r.accountId = :accountId "
            + "AND r.serviceName = :serviceName AND r.status = 'EVALUATED' "
            + "AND r.targetDate >= :since")
    List<ForecastAccuracyRecord> findEvaluatedForAccountService(@Param("provider") String provider,
                                                                @Param("accountId") String accountId,
                                                                @Param("serviceName") String serviceName,
                                                                @Param("since") LocalDate since);

    /**
     * Evaluated records for an account (all services) within a window.
     */
    @Query("SELECT r FROM ForecastAccuracyRecord r "
            + "WHERE r.provider = :provider AND r.accountId = :accountId "
            + "AND r.status = 'EVALUATED' AND r.targetDate >= :since")
    List<ForecastAccuracyRecord> findEvaluatedForAccount(@Param("provider") String provider,
                                                         @Param("accountId") String accountId,
                                                         @Param("since") LocalDate since);

    /**
     * Retention cleanup — delete old terminal records to bound table growth.
     */
    @Query("DELETE FROM ForecastAccuracyRecord r WHERE r.targetDate < :cutoff "
            + "AND r.status IN ('EVALUATED','SKIPPED')")
    @org.springframework.data.jpa.repository.Modifying
    int deleteOldRecords(@Param("cutoff") LocalDate cutoff);
}
