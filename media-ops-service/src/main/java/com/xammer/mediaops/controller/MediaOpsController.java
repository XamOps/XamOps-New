package com.xammer.mediaops.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.MediaStorageFile;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.*;
import com.xammer.mediaops.domain.TranscodePreset;
import com.xammer.mediaops.service.FileProbeService;
import com.xammer.mediaops.service.MediaStorageService;
import com.xammer.mediaops.service.MediaStreamService;
import com.xammer.mediaops.service.PresetService;
import com.xammer.mediaops.service.PriorityQueueService;
import com.xammer.mediaops.service.SchedulerService;
import com.xammer.mediaops.service.AccountS3Service;
import com.xammer.mediaops.service.TranscodeJobService;
import com.xammer.mediaops.service.UserS3Service;
import com.xammer.mediaops.validation.TranscodeJobValidator;
import com.xammer.mediaops.validation.ValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/media-ops")
public class MediaOpsController {

    private final TranscodeJobService jobService;
    private final MediaStreamService streamService;
    private final MediaStorageService storageService;
    private final FileProbeService fileProbeService;
    private final PriorityQueueService priorityQueueService;
    private final SchedulerService schedulerService;
    private final PresetService presetService;
    private final TranscodeJobValidator transcodeJobValidator;
    private final UserS3Service userS3Service;
    private final AccountS3Service accountS3Service;

    public MediaOpsController(TranscodeJobService jobService,
                              MediaStreamService streamService,
                              MediaStorageService storageService,
                              FileProbeService fileProbeService,
                              PriorityQueueService priorityQueueService,
                              SchedulerService schedulerService,
                              PresetService presetService,
                              TranscodeJobValidator transcodeJobValidator,
                              UserS3Service userS3Service,
                              AccountS3Service accountS3Service) {
        this.jobService = jobService;
        this.streamService = streamService;
        this.storageService = storageService;
        this.fileProbeService = fileProbeService;
        this.priorityQueueService = priorityQueueService;
        this.schedulerService = schedulerService;
        this.presetService = presetService;
        this.transcodeJobValidator = transcodeJobValidator;
        this.userS3Service = userS3Service;
        this.accountS3Service = accountS3Service;
    }

    // ─── Overview ──────────────────────────────────────────────────────────────

    @GetMapping("/overview")
    public ResponseEntity<MediaOpsOverviewDto> overview(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(storageService.buildOverview(clientId));
    }

    // ─── Transcoding Jobs ──────────────────────────────────────────────────────

    @GetMapping("/jobs")
    public ResponseEntity<List<TranscodeJobDto>> listJobs(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(jobService.listJobs(clientId).stream()
                .map(TranscodeJobDto::from).collect(Collectors.toList()));
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> createJob(@RequestBody TranscodeJobRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getSourceUrl() == null || req.getSourceUrl().isBlank())
            return ResponseEntity.badRequest().build();

        // Step 1: If presetId is present, load preset and apply via PresetService
        if (req.getPresetId() != null) {
            List<TranscodePreset> presets = presetService.listByClient(clientId);
            TranscodePreset preset = presets.stream()
                    .filter(p -> p.getId().equals(req.getPresetId()))
                    .findFirst()
                    .orElse(null);
            if (preset != null) {
                presetService.applyPreset(req, preset);
            }
        }

        // Step 2: Validate the request
        try {
            transcodeJobValidator.validate(
                    req.getCustomFfmpegParams(),
                    null, // subtitle filename not available in this request DTO
                    req.getStartTime(),
                    req.getEndTime(),
                    req.getCodec(),
                    req.getOutputFormat(),
                    req.getWatermarkOpacity(),
                    req.isEncryptionEnabled(),
                    null  // watermark file size not available in this request DTO
            );
        } catch (ValidationException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getErrorCode(),
                    "message", e.getMessage()
            ));
        }

        // Step 2b: If a customer S3 output destination was chosen, confirm it belongs
        // to this client and is writable — fail fast with a clear message rather than
        // letting the job break at upload time after transcoding.
        if (req.getOutputS3ConfigId() != null) {
            try {
                userS3Service.verifyWritable(req.getOutputS3ConfigId(), clientId);
            } catch (NoSuchElementException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "output-bucket-not-found",
                        "message", "Selected S3 output destination was not found."));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "output-bucket-not-writable",
                        "message", e.getMessage()));
            }
        } else if (req.getOutputAccountId() != null && !req.getOutputAccountId().isBlank()
                && req.getOutputBucket() != null && !req.getOutputBucket().isBlank()) {
            try {
                accountS3Service.verifyWritable(req.getOutputAccountId(), clientId, req.getOutputBucket());
            } catch (NoSuchElementException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "output-account-not-found",
                        "message", "Selected AWS account is not connected for this workspace."));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "output-bucket-not-writable",
                        "message", e.getMessage()));
            }
        }

        // Step 3: Create the job (maps all advanced fields)
        TranscodeJob job = jobService.createJob(clientId, req);

        // Step 4: If scheduledAt is provided, schedule instead of immediate processing
        if (req.getScheduledAt() != null) {
            try {
                schedulerService.scheduleJob(job, req.getScheduledAt());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "schedule-time-invalid",
                        "message", e.getMessage()
                ));
            }
        } else {
            // Step 5: Process immediately
            jobService.processJobAsync(job.getId());
        }

        return ResponseEntity.ok(TranscodeJobDto.from(job));
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<TranscodeJobDto> getJob(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return jobService.getJob(id, clientId)
                .map(j -> ResponseEntity.ok(TranscodeJobDto.from(j)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{id}/download")
    public void downloadJob(@PathVariable Long id, HttpSession session, HttpServletResponse response) throws IOException {
        Long clientId = resolveClientId(session);
        if (clientId == null) { response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); return; }

        Optional<TranscodeJob> opt = jobService.getJob(id, clientId);
        if (opt.isEmpty()) { response.setStatus(HttpServletResponse.SC_NOT_FOUND); return; }

        TranscodeJob job = opt.get();
        if (job.getStatus() != TranscodeJob.JobStatus.COMPLETED) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return;
        }
        if (!jobService.hasDownloadableOutput(job)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String baseName = (job.getName() != null && !job.getName().isBlank())
                ? job.getName().replaceAll("\\.[^.]+$", "")   // strip source extension
                : "job-" + id;
        String safeName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "transcode-" + id + "-" + safeName + ".zip";

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        jobService.writeJobArchive(job, response.getOutputStream());
        response.flushBuffer();
    }

    /**
     * Playback proxy for jobs whose output is stored in a customer's own (private)
     * S3 bucket. Streams each requested file — HLS/DASH manifest, media segments,
     * a progressive MP4/WebM, thumbnails — through the backend with Range support,
     * so every output format plays without the customer bucket ever being public.
     * The wildcard path after {@code /play/} is the file's path relative to the
     * job's output prefix (e.g. {@code index.m3u8}, {@code segment0.ts}).
     */
    @GetMapping("/jobs/{id}/play/**")
    public void playOutput(@PathVariable Long id, HttpServletRequest request,
                           HttpServletResponse response, HttpSession session) throws IOException {
        Long clientId = resolveClientId(session);
        if (clientId == null) { response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); return; }

        Optional<TranscodeJob> opt = jobService.getJob(id, clientId);
        if (opt.isEmpty()) { response.setStatus(HttpServletResponse.SC_NOT_FOUND); return; }

        // Everything after "/jobs/{id}/play/" is the object path relative to the prefix.
        String marker = "/jobs/" + id + "/play/";
        String uri = request.getRequestURI();
        int idx = uri.indexOf(marker);
        String relative = idx >= 0 ? uri.substring(idx + marker.length()) : "";
        relative = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        // Reject path traversal and absolute paths.
        if (relative.isBlank() || relative.contains("..") || relative.startsWith("/")) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        try {
            jobService.streamOutputObject(opt.get(), relative, request.getHeader("Range"), response);
        } catch (NoSuchElementException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<TranscodeJobDto> retryJob(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return jobService.retryJob(id, clientId)
                .map(job -> { jobService.processJobAsync(job.getId()); return ResponseEntity.ok(TranscodeJobDto.from(job)); })
                .orElse(ResponseEntity.status(409).build());
    }

    @DeleteMapping("/jobs/{id}")
    public ResponseEntity<Void> cancelJob(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return jobService.cancelJob(id, clientId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/jobs/{id}/pause")
    public ResponseEntity<TranscodeJobDto> pauseJob(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return jobService.pauseJob(id, clientId)
                .map(j -> ResponseEntity.ok(TranscodeJobDto.from(j)))
                .orElse(ResponseEntity.status(409).build());
    }

    @PostMapping("/jobs/{id}/resume")
    public ResponseEntity<TranscodeJobDto> resumeJob(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return jobService.resumeJob(id, clientId)
                .map(j -> ResponseEntity.ok(TranscodeJobDto.from(j)))
                .orElse(ResponseEntity.status(409).build());
    }

    // ─── Priority and Scheduling ───────────────────────────────────────────────

    @PatchMapping("/jobs/{id}/priority")
    public ResponseEntity<TranscodeJobDto> updateJobPriority(@PathVariable Long id,
                                                              @RequestBody PriorityUpdateRequest req,
                                                              HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();

        TranscodeJob.Priority priority;
        try {
            priority = TranscodeJob.Priority.valueOf(req.getPriority().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().build();
        }

        try {
            TranscodeJob updated = priorityQueueService.updatePriority(id, clientId, priority);
            return ResponseEntity.ok(TranscodeJobDto.from(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).build();
        }
    }

    @PatchMapping("/jobs/{id}/schedule")
    public ResponseEntity<TranscodeJobDto> updateJobSchedule(@PathVariable Long id,
                                                              @RequestBody ScheduleUpdateRequest req,
                                                              HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();

        if (req.getScheduledAt() == null) {
            return ResponseEntity.badRequest().build();
        }

        return jobService.getJob(id, clientId).map(job -> {
            if (job.getStatus() != TranscodeJob.JobStatus.SCHEDULED) {
                return ResponseEntity.status(409).<TranscodeJobDto>build();
            }
            try {
                schedulerService.scheduleJob(job, req.getScheduledAt());
                return ResponseEntity.ok(TranscodeJobDto.from(job));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<TranscodeJobDto>build();
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/jobs/{id}/schedule")
    public ResponseEntity<Void> cancelScheduledJob(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();

        return jobService.getJob(id, clientId).map(job -> {
            if (job.getStatus() != TranscodeJob.JobStatus.SCHEDULED) {
                return ResponseEntity.status(409).<Void>build();
            }
            schedulerService.cancelScheduledJob(job);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    // ─── Live Streams ──────────────────────────────────────────────────────────

    @GetMapping("/streams")
    public ResponseEntity<List<MediaStreamDto>> listStreams(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(streamService.listStreams(clientId).stream()
                .map(MediaStreamDto::from).collect(Collectors.toList()));
    }

    @PostMapping("/streams")
    public ResponseEntity<MediaStreamDto> createStream(@RequestBody MediaStreamRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getName() == null || req.getName().isBlank())
            return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(MediaStreamDto.from(streamService.createStream(clientId, req)));
    }

    @GetMapping("/streams/{id}")
    public ResponseEntity<MediaStreamDto> getStream(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return streamService.getStream(id, clientId)
                .map(s -> ResponseEntity.ok(MediaStreamDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/streams/{id}/start")
    public ResponseEntity<MediaStreamDto> startStream(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return streamService.startStream(id, clientId)
                .map(s -> ResponseEntity.ok(MediaStreamDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/streams/{id}/stop")
    public ResponseEntity<MediaStreamDto> stopStream(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return streamService.stopStream(id, clientId)
                .map(s -> ResponseEntity.ok(MediaStreamDto.from(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/streams/{id}")
    public ResponseEntity<Void> deleteStream(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return streamService.deleteStream(id, clientId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ─── Storage ───────────────────────────────────────────────────────────────

    @PostMapping("/storage/upload")
    public ResponseEntity<MediaStorageFileDto> uploadFile(@RequestParam("file") MultipartFile file, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (file.isEmpty()) return ResponseEntity.badRequest().build();
        try {
            return ResponseEntity.ok(MediaStorageFileDto.from(storageService.uploadFile(clientId, file)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/storage/files")
    public ResponseEntity<List<MediaStorageFileDto>> listFiles(@RequestParam(required = false) String type,
                                                               HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<MediaStorageFileDto> dtos;
        if (type != null && !type.isBlank()) {
            try {
                MediaStorageFile.FileType ft = MediaStorageFile.FileType.valueOf(type.toUpperCase());
                dtos = storageService.listFilesByType(clientId, ft).stream()
                        .map(MediaStorageFileDto::from).collect(Collectors.toList());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            dtos = storageService.listFiles(clientId).stream()
                    .map(MediaStorageFileDto::from).collect(Collectors.toList());
        }
        return ResponseEntity.ok(dtos);
    }

    @DeleteMapping("/storage/files/{id}")
    public ResponseEntity<Void> deleteFile(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return storageService.deleteFile(id, clientId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/storage/usage")
    public ResponseEntity<Map<String, Long>> storageUsage(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("usedBytes", storageService.totalStorageBytes(clientId)));
    }

    // ─── File Probe & Estimation ──────────────────────────────────────────────

    @PostMapping("/probe")
    public ResponseEntity<?> probeFile(@RequestBody ProbeRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getSourceUrl() == null || req.getSourceUrl().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sourceUrl is required"));
        }
        try {
            validateProbeUrl(req.getSourceUrl());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid-url", "message", e.getMessage()));
        }
        try {
            FileProbeResult result = fileProbeService.probe(req.getSourceUrl());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "probe-failed"));
        }
    }

    private static void validateProbeUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !scheme.equals("s3"))) {
            throw new IllegalArgumentException("Only https:// and s3:// URLs are permitted");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URLs with credentials are not permitted");
        }
        String host = uri.getHost();
        if (host != null && !scheme.equals("s3")) {
            try {
                for (InetAddress addr : InetAddress.getAllByName(host)) {
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                            || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                        throw new IllegalArgumentException("URL resolves to a private or internal address");
                    }
                }
            } catch (java.net.UnknownHostException e) {
                throw new IllegalArgumentException("URL host could not be resolved");
            }
        }
    }

    @PostMapping("/estimate")
    public ResponseEntity<?> estimateSize(@RequestBody EstimateRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        SizeEstimation estimation = fileProbeService.estimateOutputSize(
                req.getVideoBitrate(), req.getAudioBitrate(), req.getDurationSeconds());
        return ResponseEntity.ok(estimation);
    }

    // ─── Helper ────────────────────────────────────────────────────────────────

    private Long resolveClientId(HttpSession session) {
        // Primary: read from Spring Security context (xamops-service stores clientId
        // inside SPRING_SECURITY_CONTEXT via ClientUserDetails, not as a plain attribute)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof ClientUserDetails) {
            return ((ClientUserDetails) auth.getPrincipal()).getClientId();
        }

        // Fallback: direct session attribute (future-proof if caller sets it explicitly)
        Object raw = session.getAttribute("clientId");
        if (raw instanceof Long)    return (Long) raw;
        if (raw instanceof Integer) return ((Integer) raw).longValue();
        if (raw instanceof String)  {
            try { return Long.parseLong((String) raw); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
