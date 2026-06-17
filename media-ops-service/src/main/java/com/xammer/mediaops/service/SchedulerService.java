package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.domain.TranscodeJob.JobStatus;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Scheduler service responsible for transitioning due SCHEDULED jobs to QUEUED
 * and providing future-time validated job scheduling.
 *
 * Validates: Requirements 12.1, 12.2, 12.3
 */
@Service
public class SchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerService.class);

    private final TranscodeJobRepository jobRepo;
    private final TranscodeJobService jobService;

    public SchedulerService(TranscodeJobRepository jobRepo, TranscodeJobService jobService) {
        this.jobRepo = jobRepo;
        this.jobService = jobService;
    }

    /**
     * Periodically checks for SCHEDULED jobs whose scheduledAt time has passed
     * and transitions them to QUEUED status, then triggers async processing.
     * Runs every 30 seconds.
     *
     * Requirement 12.2: WHEN the scheduled time arrives, THE Job_Scheduler SHALL
     * transition the TranscodeJob to QUEUED status and insert the job into the Priority_Queue.
     */
    @Scheduled(fixedRate = 30000)
    @Transactional
    public void checkScheduledJobs() {
        List<TranscodeJob> dueJobs = jobRepo.findByStatusAndScheduledAtBefore(
                JobStatus.SCHEDULED, Instant.now());

        for (TranscodeJob job : dueJobs) {
            log.info("[MediaOps] Scheduled job {} is due, transitioning to QUEUED", job.getId());
            job.setStatus(JobStatus.QUEUED);
            job.setScheduledAt(null);
            jobRepo.save(job);
            jobService.processJobAsync(job.getId());
        }

        if (!dueJobs.isEmpty()) {
            log.info("[MediaOps] Transitioned {} scheduled job(s) to QUEUED", dueJobs.size());
        }
    }

    /**
     * Schedules a job for future execution. Validates that the scheduled time is in the future.
     *
     * Requirement 12.1: Hold the job in SCHEDULED status until the specified time.
     * Requirement 12.3: IF the scheduled time is in the past, reject with schedule-time-invalid error.
     *
     * @param job         the transcode job to schedule
     * @param scheduledAt the future time at which the job should be executed
     * @throws IllegalArgumentException if scheduledAt is in the past
     */
    @Transactional
    public void scheduleJob(TranscodeJob job, Instant scheduledAt) {
        if (scheduledAt.isBefore(Instant.now())) {
            throw new IllegalArgumentException("Scheduled time must be in the future");
        }
        job.setStatus(JobStatus.SCHEDULED);
        job.setScheduledAt(scheduledAt);
        jobRepo.save(job);
        log.info("[MediaOps] Job {} scheduled for execution at {}", job.getId(), scheduledAt);
    }

    /**
     * Cancels a SCHEDULED job, transitioning it to CANCELLED status.
     *
     * Requirement 12.5: Provide a REST API endpoint to cancel a SCHEDULED TranscodeJob.
     *
     * @param job the transcode job to cancel (must be in SCHEDULED status)
     * @throws IllegalStateException if the job is not in SCHEDULED status
     */
    @Transactional
    public void cancelScheduledJob(TranscodeJob job) {
        if (job.getStatus() != JobStatus.SCHEDULED) {
            throw new IllegalStateException("Can only cancel SCHEDULED jobs via this method");
        }
        log.info("[MediaOps] Cancelling scheduled job {}", job.getId());
        job.setStatus(JobStatus.CANCELLED);
        job.setScheduledAt(null);
        jobRepo.save(job);
    }
}
