package com.xammer.billops.controller;

import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/billops")
public class TicketController {

    private static final Logger logger = LoggerFactory.getLogger(TicketController.class);
    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TicketDto> createTicket(@RequestBody TicketDto ticketDto) {
        try {
            return ResponseEntity.ok(ticketService.createTicket(ticketDto));
        } catch (RuntimeException e) {
            logger.error("Error creating ticket: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @GetMapping("/tickets/cached")
    public ResponseEntity<List<TicketDto>> getCachedAllTickets() {
        logger.debug("GET /tickets/cached called");
        Optional<List<TicketDto>> cachedData = ticketService.getCachedAllTickets();
        return cachedData
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<TicketDto>> getAllTickets() {
        logger.debug("GET /tickets (fresh) called");
        try {
            return ResponseEntity.ok(ticketService.getAllTicketsAndCache());
        } catch (Exception e) {
            logger.error("Error fetching fresh tickets: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ticketService.getTicketById(id));
        } catch (RuntimeException e) {
            logger.warn("Ticket not found for ID: {}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    // --- FILE ATTACHMENT ENDPOINT ---
    @PostMapping(value = "/tickets/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketDto> addTicketReply(
            @PathVariable Long id,
            @RequestPart("message") String message,
            @RequestPart(value = "file", required = false) MultipartFile file,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String username = authentication.getName();

            TicketReplyDto replyDto = new TicketReplyDto();
            replyDto.setAuthorUsername(username);
            replyDto.setMessage(message);

            if (replyDto.getMessage() == null || replyDto.getMessage().isBlank()) {
                return ResponseEntity.badRequest().build();
            }

            return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto, file));
        } catch (IllegalStateException e) {
            logger.error("Error adding reply to ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RuntimeException e) {
            logger.error("Error adding reply to ticket {}: {}", id, e.getMessage());
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PostMapping("/tickets/{id}/close")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ticketService.closeTicket(id));
        } catch (RuntimeException e) {
            logger.error("Error closing ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/tickets/category/{category}/cached")
    public ResponseEntity<List<TicketDto>> getCachedTicketsByCategory(@PathVariable String category) {
        try {
            String decodedCategory = java.net.URLDecoder.decode(category, java.nio.charset.StandardCharsets.UTF_8);
            logger.debug("GET /tickets/category/{}/cached called", decodedCategory);
            Optional<List<TicketDto>> cachedData = ticketService.getCachedTicketsByCategory(decodedCategory);
            return cachedData
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error fetching cached tickets by category {}: {}", category, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/tickets/category/{category}")
    public ResponseEntity<List<TicketDto>> getTicketsByCategory(@PathVariable String category) {
        try {
            String decodedCategory = java.net.URLDecoder.decode(category, java.nio.charset.StandardCharsets.UTF_8);
            logger.debug("GET /tickets/category/{} (fresh) called", decodedCategory);
            return ResponseEntity.ok(ticketService.getTicketsByCategoryAndCache(decodedCategory));
        } catch (Exception e) {
            logger.error("Error fetching fresh tickets by category {}: {}", category, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}