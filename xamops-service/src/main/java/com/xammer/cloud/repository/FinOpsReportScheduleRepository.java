package com.xammer.cloud.repository;

import com.xammer.cloud.domain.FinOpsReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
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
     */
    List<FinOpsReportSchedule> findByFrequencyAndIsActiveTrue(String frequency);
    
    /**
     * Finds schedules for a specific user and account.
     */
    List<FinOpsReportSchedule> findByUserIdAndCloudAccountId(Long userId, Long cloudAccountId);
}