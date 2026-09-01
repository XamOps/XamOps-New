package com.xammer.mediaops.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.domain.TranscodePreset;
import com.xammer.mediaops.dto.PresetRequest;
import com.xammer.mediaops.service.PresetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.List;

@RestController
@RequestMapping("/api/media-ops/presets")
public class PresetController {

    private final PresetService presetService;

    public PresetController(PresetService presetService) {
        this.presetService = presetService;
    }

    @PostMapping
    public ResponseEntity<TranscodePreset> createPreset(@RequestBody PresetRequest req, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getName() == null || req.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        TranscodePreset preset = presetService.create(clientId, req);
        return ResponseEntity.ok(preset);
    }

    @GetMapping
    public ResponseEntity<List<TranscodePreset>> listPresets(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(presetService.listByClient(clientId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TranscodePreset> updatePreset(@PathVariable Long id,
                                                        @RequestBody PresetRequest req,
                                                        HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        if (req.getName() == null || req.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            TranscodePreset updated = presetService.update(id, clientId, req);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePreset(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        try {
            presetService.delete(id, clientId);
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
