package com.xammer.cloud.controller;

import com.xammer.cloud.dto.TicketDto;
import com.xammer.cloud.dto.TicketReplyDto;
import com.xammer.cloud.repository.TicketReplyRepository;
import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.service.TicketService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/xamops")
public class TicketController {

    private static final Logger logger = LoggerFactory.getLogger(TicketController.class);

    private final TicketService ticketService;
    private final TicketReplyRepository ticketReplyRepository;

    public TicketController(TicketService ticketService, TicketReplyRepository ticketReplyRepository) {
        this.ticketService = ticketService;
        this.ticketReplyRepository = ticketReplyRepository;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TicketDto> createTicket(@RequestBody TicketDto ticketDto) {
        return ResponseEntity.ok(ticketService.createTicket(ticketDto));
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<TicketDto>> getAllTickets() {
        return ResponseEntity.ok(ticketService.getAllTickets());
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id, Authentication authentication) {
        boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN"));
        return ResponseEntity.ok(ticketService.getTicketByIdForViewer(id, isAdmin));
    }

    @PostMapping("/tickets/{id}/email")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<Void> sendTicketEmail(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String recipientEmail = body.get("recipientEmail");
        ticketService.sendTicketThreadEmail(id, recipientEmail);
        return ResponseEntity.ok().build();
    }

    // --- UPDATED ENDPOINT TO SUPPORT MULTIPLE FILE UPLOAD ---
    @PostMapping(value = "/tickets/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketDto> addTicketReply(
            @PathVariable Long id,
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart(value = "authorId", required = false) String authorIdStr,
            @RequestPart(value = "isInternal", required = false) String isInternalStr,
            Authentication authentication) {

        boolean hasMessage = message != null && !message.isBlank();
        boolean hasFiles = files != null && files.stream().anyMatch(f -> !f.isEmpty());

        if (!hasMessage && !hasFiles) {
            return ResponseEntity.badRequest().build();
        }

        TicketReplyDto replyDto = new TicketReplyDto();
        replyDto.setMessage(hasMessage ? message : "");

        if (authentication != null) {
            replyDto.setAuthorUsername(authentication.getName());
        }

        if (authorIdStr != null && !authorIdStr.isBlank()) {
            try {
                replyDto.setAuthorId(Long.parseLong(authorIdStr));
            } catch (NumberFormatException e) {
                // ignore invalid ID
            }
        }

        if ("true".equalsIgnoreCase(isInternalStr)) {
            replyDto.setInternal(true);
        }

        List<MultipartFile> nonEmptyFiles = (files != null)
                ? files.stream().filter(f -> f != null && !f.isEmpty()).toList()
                : List.of();

        return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto, nonEmptyFiles));
    }
    // ----------------------------------------------------------

    @PostMapping("/tickets/{id}/close")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.closeTicket(id));
    }

    @PostMapping("/tickets/{id}/reopen")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<TicketDto> reopenTicket(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.reopenTicket(id));
    }

    @GetMapping("/tickets/category/{category}")
    public ResponseEntity<List<TicketDto>> getTicketsByCategory(
            @PathVariable String category,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        List<TicketDto> tickets = ticketService.getTicketsByCategory(category);
        String eTag = "\"" + tickets.hashCode() + "\"";
        if (eTag.equals(ifNoneMatch)) {
            return ResponseEntity.status(304).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).mustRevalidate())
                .eTag(eTag)
                .body(tickets);
    }

    @GetMapping("/tickets/category/{category}/count")
    public ResponseEntity<Map<String, Long>> getActiveTicketCount(@PathVariable String category) {
        return ResponseEntity.ok(Map.of("count", ticketService.getActiveTicketCount(category)));
    }

    // ─── EDIT / DELETE REPLY ────────────────────────────────────────────────

    @PatchMapping("/tickets/replies/{replyId}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> editReply(
            @PathVariable Long replyId,
            @RequestBody Map<String, String> payload,
            Authentication authentication) {
        try {
            if (authentication == null || !(authentication.getPrincipal() instanceof ClientUserDetails)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Long userId = ((ClientUserDetails) authentication.getPrincipal()).getId();

            // Fetch the reply and all its ticket siblings in one query (authors initialized) —
            // avoids lazy-loading on a detached entity (which caused HTTP 500).
            java.util.List<com.xammer.cloud.domain.TicketReply> siblings =
                    ticketReplyRepository.findSiblingsByReplyId(replyId);
            com.xammer.cloud.domain.TicketReply target = siblings.stream()
                    .filter(r -> r.getId().equals(replyId)).findFirst().orElse(null);
            if (target == null) {
                return ResponseEntity.notFound().build();
            }
            if (target.getAuthor() == null || !target.getAuthor().getId().equals(userId)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("NOT_AUTHOR");
            }
            if (target.isDeleted()) {
                return ResponseEntity.status(HttpStatus.GONE).body("DELETED");
            }
            // Editable until the OTHER side has seen it — i.e. no non-internal reply from a
            // different author exists after this one (by insertion id). No time limit.
            Long targetAuthorId = target.getAuthor().getId();
            boolean seenByOther = siblings.stream()
                    .filter(r -> !r.isInternal() && !r.isDeleted()
                            && r.getAuthor() != null && r.getId() != null)
                    .anyMatch(r -> !r.getAuthor().getId().equals(targetAuthorId)
                            && r.getId() > target.getId());
            if (seenByOther) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("SEEN_BY_OTHER");
            }

            target.setMessage(payload.getOrDefault("message", target.getMessage()));
            target.setEdited(true);
            target.setEditedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            ticketReplyRepository.save(target);
            return ResponseEntity.ok().body("OK");
        } catch (Exception e) {
            logger.error("Error editing reply {}: {}", replyId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/tickets/replies/{replyId}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<?> deleteReply(
            @PathVariable Long replyId,
            Authentication authentication) {
        try {
            if (authentication == null || !(authentication.getPrincipal() instanceof ClientUserDetails)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Long userId = ((ClientUserDetails) authentication.getPrincipal()).getId();
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN"));

            return ticketReplyRepository.findById(replyId).map(reply -> {
                if (!reply.getAuthor().getId().equals(userId) && !isAdmin) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                if (reply.isDeleted()) {
                    return ResponseEntity.ok().build();
                }

                reply.setDeleted(true);
                ticketReplyRepository.save(reply);
                return ResponseEntity.ok().build();
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error deleting reply {}: {}", replyId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}