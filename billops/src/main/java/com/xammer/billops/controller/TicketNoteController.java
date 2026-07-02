package com.xammer.billops.controller;

import com.xammer.billops.dto.TicketNoteDto;
import com.xammer.billops.repository.TicketNoteRepository;
import com.xammer.cloud.domain.TicketNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Shared internal note pad per ticket (admin-only). Persists the note HTML,
 * task checklist, colour and short edit-history server-side so every agent
 * sees the same notes regardless of device.
 */
@RestController
@RequestMapping("/api/billops/tickets/{ticketId}/note")
@PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
public class TicketNoteController {

    private static final Logger logger = LoggerFactory.getLogger(TicketNoteController.class);

    private final TicketNoteRepository ticketNoteRepository;

    public TicketNoteController(TicketNoteRepository ticketNoteRepository) {
        this.ticketNoteRepository = ticketNoteRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<TicketNoteDto> getNote(@PathVariable Long ticketId) {
        return ticketNoteRepository.findByTicketId(ticketId)
                .map(n -> ResponseEntity.ok(toDto(n)))
                .orElseGet(() -> ResponseEntity.ok(new TicketNoteDto()));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<TicketNoteDto> saveNote(
            @PathVariable Long ticketId,
            @RequestBody TicketNoteDto payload,
            Authentication authentication) {
        try {
            TicketNote note = ticketNoteRepository.findByTicketId(ticketId)
                    .orElseGet(() -> {
                        TicketNote n = new TicketNote();
                        n.setTicketId(ticketId);
                        return n;
                    });

            // Partial update — only apply non-null fields
            if (payload.getNoteHtml() != null) note.setNoteHtml(payload.getNoteHtml());
            if (payload.getTasksJson() != null) note.setTasksJson(payload.getTasksJson());
            if (payload.getColor() != null) note.setColor(payload.getColor());
            if (payload.getHistoryJson() != null) note.setHistoryJson(payload.getHistoryJson());

            note.setUpdatedAt(LocalDateTime.now());
            if (authentication != null) note.setUpdatedBy(authentication.getName());

            TicketNote saved = ticketNoteRepository.save(note);
            return ResponseEntity.ok(toDto(saved));
        } catch (Exception e) {
            logger.error("Failed to save note for ticket {}: {}", ticketId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private TicketNoteDto toDto(TicketNote n) {
        TicketNoteDto dto = new TicketNoteDto();
        dto.setNoteHtml(n.getNoteHtml());
        dto.setTasksJson(n.getTasksJson());
        dto.setColor(n.getColor());
        dto.setHistoryJson(n.getHistoryJson());
        dto.setUpdatedAt(n.getUpdatedAt());
        dto.setUpdatedBy(n.getUpdatedBy());
        return dto;
    }
}
