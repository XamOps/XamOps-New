package com.xammer.mediaops.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.TranscodeBatch;
import com.xammer.mediaops.dto.BatchRequest;
import com.xammer.mediaops.dto.BatchResponse;
import com.xammer.mediaops.service.BatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/media-ops/batches")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @PostMapping
    public ResponseEntity<BatchResponse> createBatch(@RequestBody BatchRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getJobs() == null || req.getJobs().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        TranscodeBatch batch = batchService.createBatch(clientId, req);
        int progress = batchService.calculateAggregateProgress(batch.getId());
        return ResponseEntity.ok(BatchResponse.from(batch, progress));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BatchResponse> getBatch(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        try {
            TranscodeBatch batch = batchService.getBatch(id, clientId);
            int progress = batchService.calculateAggregateProgress(batch.getId());
            return ResponseEntity.ok(BatchResponse.from(batch, progress));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelBatch(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        try {
            batchService.cancelBatch(id, clientId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
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
        if (raw instanceof Long)    return (Long) raw;
        if (raw instanceof Integer) return ((Integer) raw).longValue();
        if (raw instanceof String)  {
            try { return Long.parseLong((String) raw); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
