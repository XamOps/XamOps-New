package com.xammer.cloud.controller;

import com.xammer.cloud.dto.TicketDto;
import com.xammer.cloud.dto.TicketReplyDto;
import com.xammer.cloud.service.TicketService;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/xamops")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
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
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.getTicketById(id));
    }

    // --- UPDATED ENDPOINT TO SUPPORT MULTIPART FILE UPLOAD ---
    @PostMapping(value = "/tickets/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketDto> addTicketReply(
            @PathVariable Long id,
            // FIX: Make message optional so file-only replies work
            @RequestPart(value = "message", required = false) String message,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "authorId", required = false) String authorIdStr,
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
}