package com.xammer.cloud.repository;

import com.xammer.cloud.domain.AgentEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentEventRepository extends JpaRepository<AgentEvent, Long> {

    /**
     * Find events by cluster, ordered by creation time descending
     */
    List<AgentEvent> findByClusterConfigIdOrderByCreatedAtDesc(Long clusterConfigId);

    /**
     * Find events by cluster with pagination
     */
    Page<AgentEvent> findByClusterConfigIdOrderByCreatedAtDesc(Long clusterConfigId, Pageable pageable);

    /**
     * The 20 newest events of one cluster whose type starts with {@code prefix}, newest first.
     * Used with {@code "autoscaling_"} for the automation Activity feed, so heartbeat rows can't
     * push configuration changes out of a fixed-size page. Spring Data escapes {@code %} and
     * {@code _} in {@code StartingWith} values, so the prefix matches literally.
     */
    List<AgentEvent> findTop20ByClusterConfigIdAndEventTypeStartingWithOrderByCreatedAtDesc(
            Long clusterConfigId, String prefix);

    /**
     * The newest event of one cluster whose agent version ends with {@code suffix} and that was
     * created after {@code since}. Capabilities calls it with {@code "-autoscaler"} and now - 24 h
     * (server zone, like created_at), so the {@code LIKE '%-autoscaler'} filter only scans one day
     * of that cluster's rows (design §6.2).
     */
    Optional<AgentEvent> findFirstByClusterConfigIdAndAgentVersionEndingWithAndCreatedAtAfterOrderByCreatedAtDesc(
            Long clusterConfigId, String suffix, LocalDateTime since);

    /**
     * Find events by type
     */
    List<AgentEvent> findByEventType(String eventType);

    /**
     * Find recent events for a cluster
     */
    @Query("SELECT e FROM AgentEvent e WHERE e.clusterConfigId = :clusterConfigId " +
           "AND e.createdAt > :since ORDER BY e.createdAt DESC")
    List<AgentEvent> findRecentEvents(
        @Param("clusterConfigId") Long clusterConfigId,
        @Param("since") LocalDateTime since
    );

    /**
     * Count events by type for a cluster
     */
    @Query("SELECT e.eventType, COUNT(e) FROM AgentEvent e " +
           "WHERE e.clusterConfigId = :clusterConfigId " +
           "GROUP BY e.eventType")
    List<Object[]> countEventsByType(@Param("clusterConfigId") Long clusterConfigId);

    /**
     * Get last successful push for a cluster
     */
    @Query("SELECT e FROM AgentEvent e WHERE e.clusterConfigId = :clusterConfigId " +
           "AND e.eventType = 'push_success' ORDER BY e.createdAt DESC")
    List<AgentEvent> findLastSuccessfulPush(@Param("clusterConfigId") Long clusterConfigId, Pageable pageable);

    /**
     * Delete old events (cleanup)
     */
    /**
     * Deletes at most {@code batchSize} old events. Batched for the same reason as
     * {@code AgentDataCacheRepository.deleteStaleDataBatch}: this table has never been
     * pruned, so the first run must not become one enormous transaction.
     */
    @Modifying
    @Transactional // one short transaction per batch — the caller loops without holding one
    @Query(value = "DELETE FROM agent_events WHERE id IN ("
            + "SELECT id FROM agent_events WHERE created_at < :cutoff LIMIT :batchSize)",
            nativeQuery = true)
    int deleteOldEventsBatch(@Param("cutoff") LocalDateTime cutoff,
                             @Param("batchSize") int batchSize);

    /**
     * Count errors in last 24 hours for a cluster
     */
    @Query("SELECT COUNT(e) FROM AgentEvent e WHERE e.clusterConfigId = :clusterConfigId " +
           "AND e.eventType = 'push_error' AND e.createdAt > :since")
    int countRecentErrors(
        @Param("clusterConfigId") Long clusterConfigId,
        @Param("since") LocalDateTime since
    );
}
