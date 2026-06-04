package com.xammer.cloud.repository;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.FinOpsReportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface FinOpsReportScheduleRepository extends JpaRepository<FinOpsReportSchedule, Long> {

    List<FinOpsReportSchedule> findByCloudAccountId(Long cloudAccountId);

    @Query("SELECT s FROM FinOpsReportSchedule s " +
           "JOIN FETCH s.cloudAccount ca " +
           "JOIN FETCH ca.client " +
           "JOIN FETCH s.user " +
           "WHERE s.frequency = :frequency AND s.isActive = true")
    List<FinOpsReportSchedule> findAllActiveByFrequencyWithDetails(
            @Param("frequency") FinOpsReportSchedule.Frequency frequency);

    List<FinOpsReportSchedule> findByUserIdAndCloudAccountId(Long userId, Long cloudAccountId);

    @Modifying
    @Transactional
    void deleteByCloudAccountId(Long cloudAccountId);

    @Query("SELECT DISTINCT s.cloudAccount FROM FinOpsReportSchedule s WHERE s.isActive = true")
    List<CloudAccount> findDistinctActiveAccounts();

    @Query("SELECT DISTINCT s.email FROM FinOpsReportSchedule s WHERE s.cloudAccount.id = :accountId AND s.isActive = true")
    List<String> findActiveEmailsByAccountId(@Param("accountId") Long accountId);
}
