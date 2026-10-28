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
import java.util.Set;

@Service
public class TranscodeJobService {

    private static final Logger log = LoggerFactory.getLogger(TranscodeJobService.class);
    private static final BigDecimal AWS_COST_PER_MINUTE = new BigDecimal("0.0150");

    private final TranscodeJobRepository jobRepo;
    private final MediaStorageFileRepository fileRepo;
    private final FFmpegService ffmpegService;
    private final S3StorageService s3Storage;
    private final UserS3Service userS3Service;
    private final AccountS3Service accountS3Service;
    private final SourceUrlResolver sourceUrlResolver;
    private final SimpMessagingTemplate messaging;
    private final NotificationService notificationService;

    public TranscodeJobService(TranscodeJobRepository jobRepo,
                               MediaStorageFileRepository fileRepo,
                               FFmpegService ffmpegService,
                               S3StorageService s3Storage,
                               UserS3Service userS3Service,
                               AccountS3Service accountS3Service,
                               SourceUrlResolver sourceUrlResolver,
                               SimpMessagingTemplate messaging,
                               NotificationService notificationService) {
        this.jobRepo = jobRepo;
        this.fileRepo = fileRepo;
        this.ffmpegService = ffmpegService;
        this.s3Storage = s3Storage;
        this.userS3Service = userS3Service;
        this.accountS3Service = accountS3Service;
        this.sourceUrlResolver = sourceUrlResolver;
        this.messaging = messaging;
        this.notificationService = notificationService;
    }

    @Transactional
    /**
     * Where this job's output is written, relative to the destination bucket.
     *
     * Sources picked from S3 mirror their own path ({@code xamops-mediaops/DRSAKSHI/1.mp4/HLS-46}),
     * so a customer browsing the output bucket can tell which video produced which folder.
     * Direct URLs and uploads have no source key and keep the {@code jobs/<id>} layout.
     */
    private String outputPrefixFor(TranscodeJob job) {
        String format = job.getOutputFormat() != null ? job.getOutputFormat().name() : null;
        return MediaOutputPath.forSource(job.getSourceKey(), format, job.getId());
    }

    public TranscodeJob createJob(Long clientId, TranscodeJobRequest req) {
        TranscodeJob job = new TranscodeJob();
        job.setClientId(clientId);
        job.setSourceUrl(req.getSourceUrl());
        job.setSourceKey(req.getSourceKey());

        // Where the source lives, so the presigned sourceUrl above can be regenerated
        // when the job actually runs. Without this the job is stuck with a link that
        // expires long before a queued, scheduled or retried run gets to it.
        job.setSourceBucket(req.getSourceBucket());
        job.setSourceRegion(req.getSourceRegion());
        job.setSourceAccountId(req.getSourceAccountId());
        job.setSourceS3ConfigId(req.getSourceS3ConfigId());

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

        // Encoding speed: whitelist-validated (the value ends up on the ffmpeg
        // command line). Anything unrecognized falls back to the veryfast default.
        if (req.getEncodingSpeed() != null) {
            String speed = req.getEncodingSpeed().trim().toLowerCase();
            if (Set.of("fast", "veryfast", "superfast", "ultrafast").contains(speed)) {
                job.setEncodingSpeed(speed);
            }
        }

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

        // Optional customer-owned S3 output destination (validated in the controller).
        // Either a key-based connection (outputS3ConfigId) or a bucket in the
        // customer's connected AWS account (outputAccountId + outputBucket).
        job.setOutputS3ConfigId(req.getOutputS3ConfigId());
        if (req.getOutputS3ConfigId() == null
                && req.getOutputAccountId() != null && !req.getOutputAccountId().isBlank()
                && req.getOutputBucket() != null && !req.getOutputBucket().isBlank()) {
            job.setOutputAccountId(req.getOutputAccountId());
            job.setOutputBucket(req.getOutputBucket());
        }

        job.setStatus(TranscodeJob.JobStatus.QUEUED);
        return jobRepo.save(job);
    }

    @Async("transcodeExecutor")
    public void processJobAsync(Long jobId) {
        TranscodeJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;

        // Renew the source link before touching it. The URL stored at submit time is a
        // presigned link that has very likely expired by now — this job may have spent
        // hours in the queue, been scheduled for later, or been retried days afterwards.
        // Signing again here is what makes a retry able to succeed; replaying the stored
        // URL could only ever reproduce the original 403.
        try {
            // resolve() may also fill in a source location it had to discover, so save
            // unconditionally rather than only when the URL text changed.
            job.setSourceUrl(sourceUrlResolver.resolve(job));
            jobRepo.save(job);
        } catch (SourceUrlResolver.SourceUnavailableException e) {
            log.warn("[MediaOps] Job {} cannot start: {}", jobId, e.getMessage());
            job.setStatus(TranscodeJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepo.save(job);
            broadcast(job);
            notificationService.notifyJobFailed(job);
            return;
        }

        int duration = ffmpegService.probeDurationSeconds(job.getSourceUrl());
        if (duration > 0) job.setDurationSeconds(duration);

        // Capture the source file size up front so the UI can show input size and,
        // once the output is measured below, the storage saved by transcoding.
        // ffprobe reports format.size for regular files/objects; it can be absent for
        // some live/HTTP streams, in which case we simply leave it null.
        try {
            com.xammer.mediaops.dto.FileProbeResult probe = ffmpegService.probeFile(job.getSourceUrl());
            if (probe != null && probe.getFileSizeBytes() > 0) {
                job.setInputSizeBytes(probe.getFileSizeBytes());
            }
        } catch (Exception e) {
            log.debug("[MediaOps] Could not probe input size for job {}: {}", jobId, e.getMessage());
        }

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

            // Measure the produced output (sum of every file in the output directory —
            // for HLS/DASH that's the manifest plus all segments). This is what the UI
            // shows as "Output Size" and uses to compute storage saved vs the source.
            long outBytes = directorySizeBytes(job.getOutputPath());
            if (outBytes > 0) job.setOutputSizeBytes(outBytes);

            // Persist and broadcast COMPLETED immediately once FFmpeg succeeds — the
            // transcode itself is done. Thumbnail generation and the S3 backup below
            // are secondary steps that can take minutes; without this early save the
            // UI would sit at "Processing 100%" for that entire window.
            jobRepo.save(job);
            broadcast(job);

            // Thumbnail generation after successful transcode
            if (job.isThumbnailEnabled()) {
                log.info("[MediaOps] Generating thumbnails for job {}", job.getId());
                List<String> thumbnailPaths = ffmpegService.executeThumbnailGeneration(job);
                if (!thumbnailPaths.isEmpty()) {
                    log.info("[MediaOps] Generated {} thumbnails for job {}", thumbnailPaths.size(), job.getId());
                }
            }

            if (job.getOutputS3ConfigId() != null && job.getOutputPath() != null) {
                // Customer chose a key-based connected bucket. Upload there
                // (bucket stays private; playback/download go through the backend proxy).
                try {
                    String prefix = outputPrefixFor(job);
                    userS3Service.uploadDirectory(job.getOutputS3ConfigId(), job.getClientId(),
                            Paths.get(job.getOutputPath()), prefix);
                    job.setOutputBucketPrefix(prefix);
                    log.info("[MediaOps] Job {} output stored in customer bucket (config {}) under {}",
                            job.getId(), job.getOutputS3ConfigId(), prefix);
                } catch (Exception e) {
                    log.error("[MediaOps] Customer-bucket upload failed for job {}: {}", job.getId(), e.getMessage());
                    job.setErrorMessage("Output upload to your S3 bucket failed: " + e.getMessage());
                }
            } else if (job.getOutputAccountId() != null && job.getOutputBucket() != null && job.getOutputPath() != null) {
                // Customer chose a bucket in their connected AWS account. Upload via the
                // account's assumed IAM role; bucket stays private (proxy playback/download).
                try {
                    String prefix = outputPrefixFor(job);
                    String region = accountS3Service.uploadDirectory(job.getOutputAccountId(), job.getClientId(),
                            job.getOutputBucket(), Paths.get(job.getOutputPath()), prefix);
                    job.setOutputBucketPrefix(prefix);
                    job.setOutputRegion(region);
                    log.info("[MediaOps] Job {} output stored in account {} bucket {} under {}",
                            job.getId(), job.getOutputAccountId(), job.getOutputBucket(), prefix);
                } catch (Exception e) {
                    log.error("[MediaOps] Account-bucket upload failed for job {}: {}", job.getId(), e.getMessage());
                    job.setErrorMessage("Output upload to your account bucket failed: " + e.getMessage());
                }
            } else if (s3Storage.isEnabled() && job.getOutputPath() != null) {
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
        } else if (ffmpegService.consumeStopRequested(job.getId())) {
            // Killed by an explicit user stop — cancelled, not failed.
            job.setStatus(TranscodeJob.JobStatus.CANCELLED);
            if (job.getCompletedAt() == null) job.setCompletedAt(Instant.now());
            jobRepo.save(job);
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

    /**
     * Whether a completed job has output files available for download, either on the
     * local disk (dev / delete-local-after-upload=false) or in S3 (production).
     */
    public boolean hasDownloadableOutput(TranscodeJob job) {
        if (job.getOutputPath() != null) {
            Path dir = Paths.get(job.getOutputPath());
            if (Files.isDirectory(dir)) {
                try {
                    if (Files.walk(dir).anyMatch(Files::isRegularFile)) return true;
                } catch (java.io.IOException ignored) { }
            }
        }
        // Output stored in a customer bucket is downloadable via the proxy/archive.
        if (job.getOutputBucketPrefix() != null
                && (job.getOutputS3ConfigId() != null || job.getOutputAccountId() != null)) return true;
        return !s3Storage.listJobKeys(job.getId()).isEmpty();
    }

    /**
     * Stream one output file of a customer-bucket job (by its path relative to the
     * job's output prefix) to the HTTP response, with Range support. Backs the
     * {@code GET /jobs/{id}/play/**} playback proxy.
     */
    public void streamOutputObject(TranscodeJob job, String relativePath, String rangeHeader,
                                   jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        boolean customerBucket = job.getOutputBucketPrefix() != null
                && (job.getOutputS3ConfigId() != null || job.getOutputAccountId() != null);
        if (!customerBucket) {
            // Output lives in the XamOps bucket (outputUrl is set once it's uploaded there).
            // Read it with the service's credentials instead of letting the browser hit S3
            // directly — the bucket prefix is private, so a direct fetch gets 403.
            if (job.getOutputUrl() != null) {
                s3Storage.streamJobObject(job.getId(), relativePath, rangeHeader, response);
            } else {
                response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
            }
            return;
        }
        if (job.getOutputBucketPrefix() == null) {
            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String key = job.getOutputBucketPrefix() + "/" + relativePath;
        if (job.getOutputS3ConfigId() != null) {
            userS3Service.streamObject(job.getOutputS3ConfigId(), job.getClientId(), key, rangeHeader, response);
        } else if (job.getOutputAccountId() != null && job.getOutputBucket() != null) {
            accountS3Service.streamObject(job.getOutputAccountId(), job.getClientId(), job.getOutputBucket(),
                    job.getOutputRegion(), key, rangeHeader, response);
        } else {
            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * Stream a ZIP archive of all output files for a job (the transcoded video plus
     * related files — HLS/DASH segments and manifests, thumbnails, subtitles, keys).
     * Local files are used when present, otherwise the objects are pulled from S3.
     */
    public void writeJobArchive(TranscodeJob job, java.io.OutputStream out) throws java.io.IOException {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            boolean wroteAnything = false;

            // Prefer local files when the output directory still exists on disk.
            if (job.getOutputPath() != null) {
                Path dir = Paths.get(job.getOutputPath());
                if (Files.isDirectory(dir)) {
                    java.util.List<Path> files;
                    try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                        files = walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList());
                    }
                    for (Path file : files) {
                        String entryName = dir.relativize(file).toString().replace('\\', '/');
                        zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                        Files.copy(file, zip);
                        zip.closeEntry();
                        wroteAnything = true;
                    }
                }
            }

            // Fall back to the customer bucket when the output lives there and no
            // local copies remain (e.g. after cleanup on a production host).
            if (!wroteAnything && job.getOutputBucketPrefix() != null) {
                try {
                    if (job.getOutputS3ConfigId() != null) {
                        wroteAnything = userS3Service.writeObjectsUnderPrefix(
                                job.getOutputS3ConfigId(), job.getClientId(), job.getOutputBucketPrefix(), zip);
                    } else if (job.getOutputAccountId() != null && job.getOutputBucket() != null) {
                        wroteAnything = accountS3Service.writeObjectsUnderPrefix(
                                job.getOutputAccountId(), job.getClientId(), job.getOutputBucket(),
                                job.getOutputRegion(), job.getOutputBucketPrefix(), zip);
                    }
                } catch (Exception e) {
                    log.warn("[MediaOps] Archive from customer bucket failed for job {}: {}", job.getId(), e.getMessage());
                }
            }

            // Fall back to XamOps S3 when nothing was found locally.
            if (!wroteAnything && s3Storage.isEnabled()) {
                String prefix = s3Storage.jobKeyPrefix(job.getId());
                for (String key : s3Storage.listJobKeys(job.getId())) {
                    String entryName = key.startsWith(prefix) ? key.substring(prefix.length()) : key;
                    if (entryName.isEmpty()) continue;
                    zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                    try (var in = s3Storage.getObject(key)) {
                        in.transferTo(zip);
                    }
                    zip.closeEntry();
                    wroteAnything = true;
                }
            }

            if (!wroteAnything) {
                log.warn("[MediaOps] No output files found to archive for job {}", job.getId());
            }
        }
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
            job.setOutputBucketPrefix(null);
            job.setOutputRegion(null);
            job.setAwsEquivalentCost(null);
            return jobRepo.save(job);
        });
    }

    @Transactional
    public boolean cancelJob(Long id, Long clientId) {
        return jobRepo.findByIdAndClientId(id, clientId).map(job -> {
            switch (job.getStatus()) {
                case QUEUED:
                    job.setStatus(TranscodeJob.JobStatus.CANCELLED);
                    jobRepo.save(job);
                    broadcast(job);
                    return true;
                case PROCESSING:
                case PAUSED:
                    // Kill the live FFmpeg process; processJobAsync sees the stop flag
                    // and finalizes as CANCELLED. Status is set here too so the UI
                    // reacts immediately rather than waiting on the async worker.
                    // If no process is alive (service restarted mid-job), still cancel
                    // the orphaned row so it doesn't sit at "Processing" forever.
                    ffmpegService.stopJobProcess(id);
                    job.setStatus(TranscodeJob.JobStatus.CANCELLED);
                    job.setCompletedAt(Instant.now());
                    jobRepo.save(job);
                    broadcast(job);
                    return true;
                default:
                    return false;
            }
        }).orElse(false);
    }

    /** Pauses a PROCESSING job by suspending its FFmpeg process (SIGSTOP). */
    @Transactional
    public Optional<TranscodeJob> pauseJob(Long id, Long clientId) {
        return jobRepo.findByIdAndClientId(id, clientId).map(job -> {
            if (job.getStatus() != TranscodeJob.JobStatus.PROCESSING) return null;
            if (!ffmpegService.pauseJobProcess(id)) return null;
            job.setStatus(TranscodeJob.JobStatus.PAUSED);
            jobRepo.save(job);
            broadcast(job);
            return job;
        });
    }

    /** Resumes a PAUSED job's FFmpeg process (SIGCONT) — continues where it stopped. */
    @Transactional
    public Optional<TranscodeJob> resumeJob(Long id, Long clientId) {
        return jobRepo.findByIdAndClientId(id, clientId).map(job -> {
            if (job.getStatus() != TranscodeJob.JobStatus.PAUSED) return null;
            if (!ffmpegService.resumeJobProcess(id)) return null;
            job.setStatus(TranscodeJob.JobStatus.PROCESSING);
            jobRepo.save(job);
            broadcast(job);
            return job;
        });
    }

    private void broadcast(TranscodeJob job) {
        try {
            messaging.convertAndSend("/topic/media-ops/jobs/" + job.getId(),
                    (Object) Map.of("id", job.getId(), "status", job.getStatus().name(), "progress", job.getProgress()));
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

    /**
     * Total size, in bytes, of every regular file under the given output directory.
     * For single-file outputs (MP4/WebM) that's just the one file; for HLS/DASH it's
     * the manifest plus all segments. Returns 0 when the path is missing or unreadable.
     */
    private long directorySizeBytes(String outputPath) {
        if (outputPath == null) return 0L;
        Path dir = Paths.get(outputPath);
        if (!Files.exists(dir)) return 0L;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                       .mapToLong(p -> { try { return Files.size(p); } catch (java.io.IOException e) { return 0L; } })
                       .sum();
        } catch (Exception e) {
            log.debug("[MediaOps] directorySizeBytes failed for {}: {}", outputPath, e.getMessage());
            return 0L;
        }
    }
}
