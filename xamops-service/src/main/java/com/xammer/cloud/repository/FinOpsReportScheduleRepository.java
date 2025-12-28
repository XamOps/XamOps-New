package com.xammer.cloud.repository;

import com.xammer.cloud.domain.FinOpsReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinOpsReportScheduleRepository extends JpaRepository<FinOpsReportSchedule, Long> {

    /**
     * Finds all schedules for a specific CloudAccount.
     */
    List<FinOpsReportSchedule> findByCloudAccountId(Long cloudAccountId);

    /**
     * Finds all active schedules by a specific frequency.
     * --- MODIFIED: Renamed method to force @Query to be used ---
     * This eagerly loads the CloudAccount and User entities to prevent
     * LazyInitializationException in the scheduled service.
     */
    @Query("SELECT s FROM FinOpsReportSchedule s JOIN FETCH s.cloudAccount JOIN FETCH s.user WHERE s.frequency = :frequency AND s.isActive = true")
    List<FinOpsReportSchedule> findAllActiveByFrequencyWithDetails(
            @Param("frequency") FinOpsReportSchedule.Frequency frequency);

    /**
     * Finds schedules for a specific user and account.
     */
    List<FinOpsReportSchedule> findByUserIdAndCloudAccountId(Long userId, Long cloudAccountId);
}
