package com.xammer.mediaops.repository;

import com.xammer.mediaops.domain.TranscodeJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TranscodeJobRepository extends JpaRepository<TranscodeJob, Long> {

    List<TranscodeJob> findByClientIdOrderByCreatedAtDesc(Long clientId);

    List<TranscodeJob> findByClientIdAndStatusOrderByCreatedAtDesc(Long clientId, TranscodeJob.JobStatus status);

    Optional<TranscodeJob> findByIdAndClientId(Long id, Long clientId);

    List<TranscodeJob> findByStatusIn(List<TranscodeJob.JobStatus> statuses);

    @Query("SELECT COUNT(j) FROM TranscodeJob j WHERE j.clientId = :clientId AND j.status = :status")
    long countByClientIdAndStatus(Long clientId, TranscodeJob.JobStatus status);

    @Query("SELECT COALESCE(SUM(j.awsEquivalentCost), 0) FROM TranscodeJob j WHERE j.clientId = :clientId AND j.status = 'COMPLETED'")
    BigDecimal totalSavingsByClientId(Long clientId);

    /**
     * Returns the next job to process from the priority queue.
     * Orders by priority DESC (HIGH > NORMAL > LOW) then by createdAt ASC (oldest first).
     */
    Optional<TranscodeJob> findFirstByStatusOrderByPriorityDescCreatedAtAsc(TranscodeJob.JobStatus status);

    /**
     * Returns all jobs for a client in priority queue order for display.
     * Orders by priority DESC then by createdAt ASC within the same priority.
     */
    List<TranscodeJob> findByClientIdAndStatusOrderByPriorityDescCreatedAtAsc(Long clientId, TranscodeJob.JobStatus status);

    /**
     * Returns all jobs belonging to a batch.
     */
    List<TranscodeJob> findByBatchId(Long batchId);

    /**
     * Returns all SCHEDULED jobs whose scheduledAt time has passed (due for execution).
     */
    List<TranscodeJob> findByStatusAndScheduledAtBefore(TranscodeJob.JobStatus status, Instant time);
}
