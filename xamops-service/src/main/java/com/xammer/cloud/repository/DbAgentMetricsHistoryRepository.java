package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbAgentMetricsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DbAgentMetricsHistoryRepository extends JpaRepository<DbAgentMetricsHistory, Long> {

    List<DbAgentMetricsHistory> findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
            Long dbAgentId, String dataType, LocalDateTime after);

    void deleteByCollectedAtBefore(LocalDateTime cutoff);
}
