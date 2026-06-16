package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.domain.TranscodeJob.JobStatus;
import com.xammer.mediaops.domain.TranscodeJob.Priority;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Manages job execution ordering based on priority and submission time.
 * Jobs are ordered by priority DESC (HIGH > NORMAL > LOW) then by createdAt ASC (oldest first).
 */
@Service
public class PriorityQueueService {

    private static final Logger log = LoggerFactory.getLogger(PriorityQueueService.class);

    private final TranscodeJobRepository jobRepo;

    public PriorityQueueService(TranscodeJobRepository jobRepo) {
        this.jobRepo = jobRepo;
    }

    /**
     * Returns the next job to process, ordered by priority DESC then createdAt ASC.
     * Only considers jobs in QUEUED status.
     *
     * @return the highest-priority, oldest-submitted QUEUED job, or empty if no jobs are queued
     */
    public Optional<TranscodeJob> pollNext() {
        return jobRepo.findFirstByStatusOrderByPriorityDescCreatedAtAsc(JobStatus.QUEUED);
    }

    /**
     * Returns the ordered queue of pending jobs for a specific client for display purposes.
     * Jobs are ordered by priority DESC then by createdAt ASC within the same priority level.
     *
     * @param clientId the client whose queue to retrieve
     * @return list of QUEUED jobs in priority order
     */
    public List<TranscodeJob> getQueue(Long clientId) {
        return jobRepo.findByClientIdAndStatusOrderByPriorityDescCreatedAtAsc(clientId, JobStatus.QUEUED);
    }

    /**
     * Updates the priority of a queued job, effectively reordering the queue.
     * Only jobs in QUEUED status can have their priority changed.
     *
     * @param jobId       the job identifier
     * @param clientId    the client identifier (for ownership validation)
     * @param newPriority the new priority level to assign
     * @return the updated job entity
     * @throws IllegalArgumentException if the job is not found or does not belong to the client
     * @throws IllegalStateException    if the job is not in QUEUED status
     */
    @Transactional
    public TranscodeJob updatePriority(Long jobId, Long clientId, Priority newPriority) {
        TranscodeJob job = jobRepo.findByIdAndClientId(jobId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.getStatus() != JobStatus.QUEUED) {
            throw new IllegalStateException("Can only change priority of QUEUED jobs");
        }

        log.info("[MediaOps] Updating priority for job {} from {} to {}", jobId, job.getPriority(), newPriority);
        job.setPriority(newPriority);
        return jobRepo.save(job);
    }
}
