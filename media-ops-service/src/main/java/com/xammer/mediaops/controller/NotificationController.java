package com.xammer.mediaops.controller;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.mediaops.dto.NotificationDto;
import com.xammer.mediaops.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/media-ops/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationDto>> listNotifications(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(notificationService.getNotifications(clientId));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Integer>> getUnreadCount(HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("count", notificationService.getUnreadCount(clientId)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long id, HttpSession session) {
        Long clientId = resolveClientId(session);
        if (clientId == null) return ResponseEntity.status(401).build();
        boolean marked = notificationService.markAsRead(id, clientId);
        return marked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
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
