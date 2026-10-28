package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.domain.TranscodeJob.JobStatus;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Scheduled component that reclaims local disk on the host by deleting the local
 * output directory of COMPLETED jobs once a retention window has passed — but only
 * when a remote copy (customer/account bucket or XamOps S3) is confirmed to exist,
 * so the only copy is never deleted.
 *
 * Completed output accumulates under {@code mediaops.storage.base-dir} because the
 * customer/account-bucket upload paths do not honour {@code delete-local-after-upload}
 * (only the XamOps-bucket path does). This service prevents that unbounded growth.
 *
 * It never writes to the DB and never touches S3 objects; it only reads remote state
 * and removes files from the local filesystem.
 */
@Service
public class LocalOutputCleanupService {

    private static final Logger log = LoggerFactory.getLogger(LocalOutputCleanupService.class);

    private final TranscodeJobRepository jobRepo;
    private final S3StorageService s3Storage;

    @Value("${mediaops.cleanup.local-output.enabled:true}")
    private boolean enabled;

    @Value("${mediaops.cleanup.local-output.retention-seconds:3600}")
    private long retentionSeconds;

    public LocalOutputCleanupService(TranscodeJobRepository jobRepo, S3StorageService s3Storage) {
        this.jobRepo = jobRepo;
        this.s3Storage = s3Storage;
    }

    /**
     * Every {@code mediaops.cleanup.local-output.interval-ms} (default 15 minutes),
     * find COMPLETED jobs whose completedAt is older than the retention window and
     * delete their local output directory when a remote copy exists.
     */
    @Scheduled(fixedDelayString = "${mediaops.cleanup.local-output.interval-ms:900000}")
    public void cleanupCompletedLocalOutputs() {
        if (!enabled) {
            log.debug("[MediaOps] Local-output cleanup disabled — skipping run.");
            return;
        }

        Instant cutoff = Instant.now().minusSeconds(retentionSeconds);
        List<TranscodeJob> jobs = jobRepo.findByStatusAndCompletedAtBefore(JobStatus.COMPLETED, cutoff);

        for (TranscodeJob job : jobs) {
            try {
                // Guard A — status: defensive re-check; never act on any other status.
                if (job.getStatus() != JobStatus.COMPLETED) {
                    continue;
                }

                // Guard B — remote copy must exist so we never delete the only copy.
                // Mirrors TranscodeJobService.hasDownloadableOutput: a customer/account
                // bucket copy, OR XamOps S3 has keys for the job.
                boolean remoteCopyExists =
                        (job.getOutputBucketPrefix() != null
                                && (job.getOutputS3ConfigId() != null || job.getOutputAccountId() != null))
                        || (s3Storage.isEnabled() && !s3Storage.listJobKeys(job.getId()).isEmpty());
                if (!remoteCopyExists) {
                    log.info("[MediaOps] Skipping local cleanup for job {} — no remote copy found (output only exists locally)", job.getId());
                    continue;
                }

                // Null-safe outputPath.
                String outputPath = job.getOutputPath();
                if (outputPath == null || outputPath.isBlank()) {
                    continue;
                }

                Path dir = Paths.get(outputPath);
                if (!Files.exists(dir)) {
                    continue; // already cleaned
                }

                deleteDirectory(dir);
                log.info("[MediaOps] Deleted local output for completed job {} at {}", job.getId(), outputPath);
            } catch (Exception e) {
                // Per-job isolation: one failure must not abort the batch.
                log.warn("[MediaOps] Local-output cleanup failed for job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    /**
     * Recursively delete a directory, swallowing per-file IO errors. Replicates
     * {@link S3StorageService}'s private {@code deleteDirectory} pattern (that method
     * is not visible here, so widening its visibility is avoided).
     */
    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            log.warn("[MediaOps] Could not delete local dir {}: {}", dir, e.getMessage());
        }
    }
}
