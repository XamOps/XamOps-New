package com.xammer.mediaops.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.UserS3Config;
import com.xammer.mediaops.dto.S3FileItem;
import com.xammer.mediaops.dto.UserS3ConfigDto;
import com.xammer.mediaops.dto.UserS3ConnectRequest;
import com.xammer.mediaops.service.UserS3Service;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/media-ops/user-s3")
public class UserS3Controller {

    private final UserS3Service userS3Service;

    public UserS3Controller(UserS3Service userS3Service) {
        this.userS3Service = userS3Service;
    }

    // ─── List connected S3 configs ─────────────────────────────────────────────

    @GetMapping("/configs")
    public ResponseEntity<List<UserS3ConfigDto>> listConfigs(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        List<UserS3ConfigDto> dtos = userS3Service.listConfigs(clientId).stream()
                .map(UserS3ConfigDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    // ─── Connect a new S3 bucket ───────────────────────────────────────────────

    @PostMapping("/configs")
    public ResponseEntity<?> connect(@RequestBody UserS3ConnectRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();

        if (req.getBucketName() == null || req.getBucketName().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "bucketName is required"));
        if (req.getRegion() == null || req.getRegion().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "region is required"));
        if (req.getAccessKey() == null || req.getAccessKey().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "accessKey is required"));
        if (req.getSecretKey() == null || req.getSecretKey().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "secretKey is required"));

        try {
            UserS3Config saved = userS3Service.connect(clientId, req);
            return ResponseEntity.ok(UserS3ConfigDto.from(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Disconnect an S3 config ───────────────────────────────────────────────

    @DeleteMapping("/configs/{id}")
    public ResponseEntity<Void> deleteConfig(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return userS3Service.deleteConfig(id, clientId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ─── List video files in a connected bucket ────────────────────────────────

    @GetMapping("/configs/{id}/files")
    public ResponseEntity<?> listFiles(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        try {
            List<S3FileItem> files = userS3Service.listFiles(id, clientId);
            return ResponseEntity.ok(files);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "list-failed", "message", e.getMessage()));
        }
    }

    // ─── Generate presigned URL for preview or transcoding ────────────────────

    @PostMapping("/configs/{id}/presign")
    public ResponseEntity<?> presign(@PathVariable Long id,
                                     @RequestBody Map<String, Object> body,
                                     HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();

        String s3Key = (String) body.get("s3Key");
        if (s3Key == null || s3Key.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "s3Key is required"));

        int expiry = body.containsKey("expiryMinutes")
                ? Integer.parseInt(String.valueOf(body.get("expiryMinutes")))
                : 60;

        try {
            String url = userS3Service.presignUrl(id, clientId, s3Key, expiry);
            return ResponseEntity.ok(Map.of("url", url, "expiresInMinutes", expiry));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "presign-failed", "message", e.getMessage()));
        }
    }

    // ─── Helper ────────────────────────────────────────────────────────────────

    private Long resolveClientId(HttpSession session) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof ClientUserDetails) {
            return ((ClientUserDetails) auth.getPrincipal()).getClientId();
        }
        Object raw = session.getAttribute("clientId");
        if (raw instanceof Long) return (Long) raw;
        if (raw instanceof Integer) return ((Integer) raw).longValue();
        if (raw instanceof String) {
            try { return Long.parseLong((String) raw); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
