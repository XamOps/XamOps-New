package com.xammer.cloud.repository;

import com.xammer.cloud.domain.JobExecutionHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JobExecutionHistoryRepository extends JpaRepository<JobExecutionHistory, Long> {
    List<JobExecutionHistory> findByJobNameOrderByStartTimeDesc(String jobName);

    List<JobExecutionHistory> findByTenantIdOrderByStartTimeDesc(String tenantId);
}
