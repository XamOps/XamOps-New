package com.xammer.cloud.repository;

import com.xammer.cloud.domain.DbAgentMetricsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DbAgentMetricsHistoryRepository extends JpaRepository<DbAgentMetricsHistory, Long> {

    List<DbAgentMetricsHistory> findByDbAgentIdAndDataTypeAndCollectedAtAfterOrderByCollectedAtAsc(
            Long dbAgentId, String dataType, LocalDateTime after);

    /**
     * The two most recent history rows for an agent + data type, newest first. Pillar A's delta
     * findings (deadlocks, cache-hit, temp spill, checkpoint/WAL) are rates that need two cumulative
     * samples; fewer than two rows drives the "collecting — need a second sample" state (§6.3).
     */
    List<DbAgentMetricsHistory> findTop2ByDbAgentIdAndDataTypeOrderByCollectedAtDesc(
            Long dbAgentId, String dataType);

    void deleteByCollectedAtBefore(LocalDateTime cutoff);

    /**
     * PERF-5: bulk-delete history rows older than the retention cutoff. A single JPQL bulk DELETE
     * (one statement, no per-row entity load) invoked by {@code AgentDataCleanupScheduler} so this
     * append-only table stays bounded. Returns the number of rows removed.
     */
    @Modifying
    @Query("delete from DbAgentMetricsHistory h where h.collectedAt < :cutoff")
    int purgeOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
