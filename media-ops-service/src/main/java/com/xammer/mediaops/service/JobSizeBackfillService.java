package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.FileProbeResult;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * One-off backfill for input/output sizes on jobs that completed before size tracking
 * was added to {@link TranscodeJobService#processJobAsync}. Runs once on startup in a
 * background thread so it never delays boot; jobs that already have both sizes are
 * skipped, so subsequent restarts do essentially no work.
 */
@Service
public class JobSizeBackfillService {

    private static final Logger log = LoggerFactory.getLogger(JobSizeBackfillService.class);

    private final TranscodeJobRepository jobRepo;
    private final FFmpegService ffmpegService;
    private final SourceUrlResolver sourceUrlResolver;

    public JobSizeBackfillService(TranscodeJobRepository jobRepo,
                                  FFmpegService ffmpegService,
                                  SourceUrlResolver sourceUrlResolver) {
        this.jobRepo = jobRepo;
        this.ffmpegService = ffmpegService;
        this.sourceUrlResolver = sourceUrlResolver;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(this::backfill, "mediaops-size-backfill");
        t.setDaemon(true);
        t.start();
    }

    void backfill() {
        List<TranscodeJob> jobs;
        try {
            jobs = jobRepo.findByStatusIn(List.of(TranscodeJob.JobStatus.COMPLETED)).stream()
                    .filter(j -> j.getInputSizeBytes() == null || j.getOutputSizeBytes() == null)
                    .toList();
        } catch (Exception e) {
            log.warn("[MediaOps] Size backfill skipped: {}", e.getMessage());
            return;
        }
        if (jobs.isEmpty()) return;
        log.info("[MediaOps] Backfilling input/output sizes for {} completed job(s)", jobs.size());

        int updated = 0;
        for (TranscodeJob job : jobs) {
            boolean changed = false;

            // Output: sum of every file in the local output directory (manifest + segments).
            // Missing when outputs were moved to S3 and local copies deleted — left null then.
            if (job.getOutputSizeBytes() == null) {
                long out = directorySizeBytes(job.getOutputPath());
                if (out > 0) { job.setOutputSizeBytes(out); changed = true; }
            }

            // Input: re-sign the source (stored presigned URLs have long expired) and probe it.
            if (job.getInputSizeBytes() == null) {
                try {
                    String url = sourceUrlResolver.resolve(job);
                    FileProbeResult probe = ffmpegService.probeFile(url);
                    if (probe != null && probe.getFileSizeBytes() > 0) {
                        job.setInputSizeBytes(probe.getFileSizeBytes());
                        changed = true;
                    }
                } catch (Exception e) {
                    // Source deleted, account disconnected, etc. — nothing to measure.
                    log.debug("[MediaOps] Input size unavailable for job {}: {}", job.getId(), e.getMessage());
                }
            }

            if (changed) {
                try {
                    // Only touch the size columns on a fresh copy, so we never overwrite
                    // anything else that changed (e.g. a retry) since we loaded the list.
                    jobRepo.findById(job.getId()).ifPresent(fresh -> {
                        if (fresh.getInputSizeBytes() == null) fresh.setInputSizeBytes(job.getInputSizeBytes());
                        if (fresh.getOutputSizeBytes() == null) fresh.setOutputSizeBytes(job.getOutputSizeBytes());
                        jobRepo.save(fresh);
                    });
                    updated++;
                } catch (Exception e) {
                    log.warn("[MediaOps] Could not save sizes for job {}: {}", job.getId(), e.getMessage());
                }
            }
        }
        log.info("[MediaOps] Size backfill done: {} of {} job(s) updated", updated, jobs.size());
    }

    private static long directorySizeBytes(String outputPath) {
        if (outputPath == null) return 0L;
        Path dir = Paths.get(outputPath);
        if (!Files.exists(dir)) return 0L;
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                       .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0L; } })
                       .sum();
        } catch (Exception e) {
            return 0L;
        }
    }
}
