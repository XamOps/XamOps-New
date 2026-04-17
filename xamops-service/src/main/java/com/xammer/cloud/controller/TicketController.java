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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    // --- UPDATED ENDPOINT TO SUPPORT MULTIPART FILE UPLOAD ---
    @PostMapping(value = "/tickets/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketDto> addTicketReply(
            @PathVariable Long id,
            // FIX: Make message optional so file-only replies work
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "authorId", required = false) String authorIdStr,
            @RequestPart(value = "isInternal", required = false) String isInternalStr,
            Authentication authentication) {

        // Validate that we have at least something to post
        boolean hasMessage = message != null && !message.isBlank();
        boolean hasFile = file != null && !file.isEmpty();

        if (!hasMessage && !hasFile) {
            return ResponseEntity.badRequest().build();
        }

        TicketReplyDto replyDto = new TicketReplyDto();
        replyDto.setMessage(hasMessage ? message : "");

        // Prefer Authentication object for security
        if (authentication != null) {
            replyDto.setAuthorUsername(authentication.getName());
        }

        // Fallback to authorId if passed manually (e.g., admin actions if needed,
        // though Auth is preferred)
        if (authorIdStr != null && !authorIdStr.isBlank()) {
            try {
                replyDto.setAuthorId(Long.parseLong(authorIdStr));
            } catch (NumberFormatException e) {
                // Ignore invalid ID format
            }
        }

        if ("true".equalsIgnoreCase(isInternalStr)) {
            replyDto.setInternal(true);
        }

        return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto, file));
    }
    // ----------------------------------------------------------

    @PostMapping("/tickets/{id}/close")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.closeTicket(id));
    }

    @GetMapping("/tickets/category/{category}")
    public ResponseEntity<List<TicketDto>> getTicketsByCategory(@PathVariable String category) {
        return ResponseEntity.ok(ticketService.getTicketsByCategory(category));
    }

    // ─── EDIT / DELETE REPLY ────────────────────────────────────────────────

    @PatchMapping("/tickets/replies/{replyId}")
    public ResponseEntity<?> editReply(
            @PathVariable Long replyId,
            @RequestBody Map<String, String> payload,
            Authentication authentication) {
        try {
            if (authentication == null || !(authentication.getPrincipal() instanceof ClientUserDetails)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Long userId = ((ClientUserDetails) authentication.getPrincipal()).getId();

            return ticketReplyRepository.findById(replyId).map(reply -> {
                if (!reply.getAuthor().getId().equals(userId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                if (reply.isDeleted()) {
                    return ResponseEntity.status(HttpStatus.GONE).build();
                }
                LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(15);
                if (reply.getCreatedAt().isBefore(cutoff)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }

                reply.setMessage(payload.getOrDefault("message", reply.getMessage()));
                reply.setEdited(true);
                reply.setEditedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
                ticketReplyRepository.save(reply);
                return ResponseEntity.ok().build();
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error editing reply {}: {}", replyId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/tickets/replies/{replyId}")
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