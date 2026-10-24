package com.xammer.cloud.repository;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spot interruption history (design §B3.4).
 *
 * <p><strong>Tenant invariant:</strong> every method except the retention purge takes
 * {@code clusterConfigId}, so no caller can issue an unscoped read or write. There is no
 * {@code findAll} use.
 */
@Repository
public interface K8sSpotInterruptionEventRepository extends JpaRepository<K8sSpotInterruptionEvent, Long> {

    /** The writer's row lock: one node lifecycle, any {@code recovery_status}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<K8sSpotInterruptionEvent> findByClusterConfigIdAndNodeNameAndNodeCreatedAt(
            Long clusterConfigId, String nodeName, Instant nodeCreatedAt);

    /** Unlocked existence check for removal inference; the write then goes through the locked writer. */
    Optional<K8sSpotInterruptionEvent> findFirstByClusterConfigIdAndNodeNameAndNodeCreatedAt(
            Long clusterConfigId, String nodeName, Instant nodeCreatedAt);

    /** Every row of one cluster in one recovery status ({@code tracking} for recovery evaluation). */
    List<K8sSpotInterruptionEvent> findByClusterConfigIdAndRecoveryStatus(Long clusterConfigId, String recoveryStatus);

    /** The drain-outcome join: rows of one node noticed within a window around the drain's start. */
    List<K8sSpotInterruptionEvent> findByClusterConfigIdAndNodeNameAndNoticedAtBetween(
            Long clusterConfigId, String nodeName, Instant from, Instant to);

    /** The read API: one cluster's rows noticed after {@code since}, newest first. */
    List<K8sSpotInterruptionEvent> findByClusterConfigIdAndNoticedAtAfterOrderByNoticedAtDesc(
            Long clusterConfigId, Instant since, Pageable pageable);

    /**
     * Deletes at most {@code batchSize} rows noticed before {@code cutoff} (retention). Batched like
     * {@code AgentEventRepository.deleteOldEventsBatch} so a backlog never becomes one long
     * transaction.
     */
    @Modifying
    @Transactional // one short transaction per batch — the caller loops without holding one
    @Query(value = "DELETE FROM k8s_spot_interruption_event WHERE id IN ("
            + "SELECT id FROM k8s_spot_interruption_event WHERE noticed_at < :cutoff LIMIT :batchSize)",
            nativeQuery = true)
    int deleteOlderThanBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
