package com.xammer.mediaops.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.MediaStorageFile;
import com.xammer.mediaops.domain.TranscodeJob;
import com.xammer.mediaops.dto.*;
import com.xammer.mediaops.service.MediaStorageService;
import com.xammer.mediaops.service.MediaStreamService;
import com.xammer.mediaops.service.TranscodeJobService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/media-ops")
public class MediaOpsController {

    private final TranscodeJobService jobService;
    private final MediaStreamService streamService;
    private final MediaStorageService storageService;

    public MediaOpsController(TranscodeJobService jobService,
                              MediaStreamService streamService,
                              MediaStorageService storageService) {
        this.jobService = jobService;
        this.streamService = streamService;
        this.storageService = storageService;
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
    public ResponseEntity<TranscodeJobDto> createJob(@RequestBody TranscodeJobRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getSourceUrl() == null || req.getSourceUrl().isBlank())
            return ResponseEntity.badRequest().build();
        TranscodeJob job = jobService.createJob(clientId, req);
        jobService.processJobAsync(job.getId());
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
