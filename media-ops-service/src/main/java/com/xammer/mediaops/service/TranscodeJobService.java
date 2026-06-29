package com.xammer.mediaops.service;

import com.xammer.mediaops.domain.MediaStorageFile;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.TranscodeJobRequest;
import com.xammer.mediaops.repository.MediaStorageFileRepository;
import com.xammer.mediaops.repository.TranscodeJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TranscodeJobService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeJobService.class);
    private static final BigDecimal AWS_COST_PER_MINUTE = new BigDecimal("0.0150");

    private final TranscodeJobRepository jobRepo;
    private final MediaStorageFileRepository fileRepo;
    private final FFmpegService ffmpegService;
    private final S3StorageService s3Storage;
    private final SimpMessagingTemplate messaging;
    private final NotificationService notificationService;

    public TranscodeJobService(TranscodeJobRepository jobRepo,
                               MediaStorageFileRepository fileRepo,
                               FFmpegService ffmpegService,
                               S3StorageService s3Storage,
                               SimpMessagingTemplate messaging,
                               NotificationService notificationService) {
        this.jobRepo = jobRepo;
        this.fileRepo = fileRepo;
        this.ffmpegService = ffmpegService;
        this.s3Storage = s3Storage;
        this.messaging = messaging;
        this.notificationService = notificationService;
    }

    @Transactional
    public TranscodeJob createJob(Long clientId, TranscodeJobRequest req) {
        TranscodeJob job = new TranscodeJob();
        job.setClientId(clientId);
        job.setSourceUrl(req.getSourceUrl());
        job.setResolution(req.getResolution());
        job.setBitrate(req.getBitrate());
        job.setFps(req.getFps());
        job.setEnableAdaptive(req.isEnableAdaptive());

        String url = req.getSourceUrl();
        String rawName = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url;
        // Strip query params (presigned S3 URLs have ?X-Amz-... after the filename)
        int qIdx = rawName.indexOf('?');
        if (qIdx >= 0) rawName = rawName.substring(0, qIdx);
        // URL-decode percent-encoded characters in the key
        try { rawName = java.net.URLDecoder.decode(rawName, "UTF-8"); } catch (Exception ignored) {}
        if (rawName.isBlank()) rawName = "job";
        job.setName(rawName);

        try {
            job.setOutputFormat(TranscodeJob.OutputFormat.valueOf(req.getOutputFormat().toUpperCase()));
        } catch (Exception e) {
            job.setOutputFormat(TranscodeJob.OutputFormat.HLS);
        }

        // Map advanced fields from request
        job.setCodec(req.getCodec());

        if (req.getPriority() != null && !req.getPriority().isEmpty()) {
            try {
                job.setPriority(TranscodeJob.Priority.valueOf(req.getPriority().toUpperCase()));
            } catch (IllegalArgumentException e) {
                job.setPriority(TranscodeJob.Priority.NORMAL);
            }
        }

        job.setPresetId(req.getPresetId());
        job.setStartTime(req.getStartTime());
        job.setEndTime(req.getEndTime());

        job.setSubtitlePath(req.getSubtitlePath());
        if (req.getSubtitleMode() != null && !req.getSubtitleMode().isEmpty()) {
            try {
                job.setSubtitleMode(TranscodeJob.SubtitleMode.valueOf(req.getSubtitleMode().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }

        job.setWatermarkImagePath(req.getWatermarkImagePath());
        job.setWatermarkText(req.getWatermarkText());
        if (req.getWatermarkPosition() != null && !req.getWatermarkPosition().isEmpty()) {
            try {
                job.setWatermarkPosition(TranscodeJob.WatermarkPosition.valueOf(req.getWatermarkPosition().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        job.setWatermarkOpacity(req.getWatermarkOpacity());
        job.setWatermarkScale(req.getWatermarkScale());
        job.setWatermarkX(req.getWatermarkX());
        job.setWatermarkY(req.getWatermarkY());

        job.setThumbnailEnabled(req.isThumbnailEnabled());
        job.setThumbnailIntervalSeconds(req.getThumbnailIntervalSeconds());
        job.setThumbnailTimestamp(req.getThumbnailTimestamp());

        job.setAudioBitrate(req.getAudioBitrate());
        job.setAudioSampleRate(req.getAudioSampleRate());
        job.setAudioChannels(req.getAudioChannels());

        job.setCustomFfmpegParams(req.getCustomFfmpegParams());
        job.setEncryptionEnabled(req.isEncryptionEnabled());

        job.setStatus(TranscodeJob.JobStatus.QUEUED);
        return jobRepo.save(job);
    }

    @Async
    public void processJobAsync(Long jobId) {
        TranscodeJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;

        int duration = ffmpegService.probeDurationSeconds(job.getSourceUrl());
        if (duration > 0) job.setDurationSeconds(duration);

        job.setStatus(TranscodeJob.JobStatus.PROCESSING);
        job.setStartedAt(Instant.now());
        jobRepo.save(job);
        broadcast(job);

        boolean ok = ffmpegService.transcode(job, pct -> {
            job.setProgress(pct);
            jobRepo.save(job);
            broadcast(job);
        });

        if (ok) {
            job.setStatus(TranscodeJob.JobStatus.COMPLETED);
            job.setProgress(100);
            job.setCompletedAt(Instant.now());
            if (duration > 0)
                job.setAwsEquivalentCost(AWS_COST_PER_MINUTE.multiply(BigDecimal.valueOf(duration / 60.0)));

            // Thumbnail generation after successful transcode
            if (job.isThumbnailEnabled()) {
                log.info("[MediaOps] Generating thumbnails for job {}", job.getId());
                List<String> thumbnailPaths = ffmpegService.executeThumbnailGeneration(job);
                if (!thumbnailPaths.isEmpty()) {
                    log.info("[MediaOps] Generated {} thumbnails for job {}", thumbnailPaths.size(), job.getId());
                }
            }

            if (s3Storage.isEnabled() && job.getOutputPath() != null) {
                try {
                    String s3Url = s3Storage.uploadJobOutput(job.getId(), Paths.get(job.getOutputPath()));
                    log.info("[MediaOps] Job {} backed up to S3: {}", job.getId(), s3Url);
                    // Only point streaming URLs at S3 when local files are deleted (production).
                    // When delete-local-after-upload=false (dev), files still exist locally so
                    // the player continues using the local file server — bucket is private anyway.
                    if (s3Storage.isDeleteLocalAfterUpload()) {
                        job.setOutputUrl(s3Url);
                    }
                } catch (Exception e) {
                    log.error("[MediaOps] S3 upload failed for job {}, files remain local: {}", job.getId(), e.getMessage());
                }
            }

            jobRepo.save(job);
            registerOutputFile(job);
        } else {
            job.setStatus(TranscodeJob.JobStatus.FAILED);
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);
        }
        broadcast(job);

        // Send notifications on terminal status transitions
        if (job.getStatus() == TranscodeJob.JobStatus.COMPLETED) {
            notificationService.notifyJobCompleted(job);
        } else if (job.getStatus() == TranscodeJob.JobStatus.FAILED) {
            notificationService.notifyJobFailed(job);
        }
    }

    public List<TranscodeJob> listJobs(Long clientId) {
        return jobRepo.findByClientIdOrderByCreatedAtDesc(clientId);
    }

    public Optional<TranscodeJob> getJob(Long id, Long clientId) {
        return jobRepo.findByIdAndClientId(id, clientId);
    }

    @Transactional
    public Optional<TranscodeJob> retryJob(Long id, Long clientId) {
        return jobRepo.findByIdAndClientId(id, clientId).map(job -> {
            if (job.getStatus() != TranscodeJob.JobStatus.FAILED
                    && job.getStatus() != TranscodeJob.JobStatus.CANCELLED) {
                return null;
            }
            job.setStatus(TranscodeJob.JobStatus.QUEUED);
            job.setProgress(0);
            job.setErrorMessage(null);
            job.setStartedAt(null);
            job.setCompletedAt(null);
            job.setOutputPath(null);
            job.setAwsEquivalentCost(null);
            return jobRepo.save(job);
        });
    }

    @Transactional
    public boolean cancelJob(Long id, Long clientId) {
        return jobRepo.findByIdAndClientId(id, clientId).map(job -> {
            if (job.getStatus() == TranscodeJob.JobStatus.QUEUED) {
                job.setStatus(TranscodeJob.JobStatus.CANCELLED);
                jobRepo.save(job);
                return true;
            }
            return false;
        }).orElse(false);
    }

    private void broadcast(TranscodeJob job) {
        try {
            messaging.convertAndSend("/topic/media-ops/jobs/" + job.getId(),
                    Map.of("id", job.getId(), "status", job.getStatus().name(), "progress", job.getProgress()));
        } catch (Exception ignored) {}
    }

    private void registerOutputFile(TranscodeJob job) {
        if (job.getOutputPath() == null) return;
        try {
            Path dir = Paths.get(job.getOutputPath());
            if (!Files.exists(dir)) return;
            Files.list(dir)
                .filter(p -> { String n = p.getFileName().toString().toLowerCase();
                               return n.endsWith(".m3u8") || n.endsWith(".mpd") || n.endsWith(".mp4")
                                   || n.endsWith(".webm") || n.endsWith(".mkv"); })
                .findFirst()
                .ifPresent(out -> {
                    MediaStorageFile f = new MediaStorageFile();
                    f.setClientId(job.getClientId());
                    f.setName(out.getFileName().toString());
                    f.setFilePath(out.toString());
                    f.setJobId(job.getId());
                    String nm = f.getName().toLowerCase();
                    if (nm.endsWith(".m3u8") || nm.endsWith(".mpd")) {
                        f.setFileType(MediaStorageFile.FileType.HLS);
                        f.setFormat(nm.endsWith(".m3u8") ? "HLS" : "DASH");
                    } else {
                        f.setFileType(MediaStorageFile.FileType.VIDEO);
                        f.setFormat(job.getOutputFormat().name());
                    }
                    f.setResolution(job.getResolution());
                    f.setDurationSeconds(job.getDurationSeconds());
                    try { f.setSizeBytes(Files.size(out)); } catch (java.io.IOException ignored) {}
                    fileRepo.save(f);
                });
        } catch (Exception e) {
            log.warn("[MediaOps] registerOutputFile failed: {}", e.getMessage());
        }
    }
}
