package com.xammer.billops.controller;

import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.service.TicketService;
import com.xammer.cloud.security.ClientUserDetails;
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

@RestController
@RequestMapping("/api/billops")
public class TicketController {

    private static final Logger logger = LoggerFactory.getLogger(TicketController.class);
    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TicketDto> createTicket(@RequestBody TicketDto ticketDto, Authentication authentication) {
        try {
            if (authentication != null && authentication.getPrincipal() instanceof ClientUserDetails) {
                ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
                ticketDto.setCreatorId(userDetails.getId());
                
                if (ticketDto.getClientId() == null) {
                    ticketDto.setClientId(userDetails.getClientId());
                }
            }

            return ResponseEntity.ok(ticketService.createTicket(ticketDto));
        } catch (RuntimeException e) {
            logger.error("Error creating ticket: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @GetMapping("/tickets/cached")
    public ResponseEntity<List<TicketDto>> getCachedAllTickets() {
        return getAllTickets(false);
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<TicketDto>> getAllTickets(@RequestParam(defaultValue = "false") boolean forceRefresh) {
        logger.debug("GET /tickets called. ForceRefresh: {}", forceRefresh);
        try {
            return ResponseEntity.ok(ticketService.getAllTickets(forceRefresh));
        } catch (Exception e) {
            logger.error("Error fetching tickets: {}", e.getMessage(), e);
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

    // --- UPDATED: FILE ATTACHMENT ENDPOINT ---
    @PostMapping(value = "/tickets/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketDto> addTicketReply(
            @PathVariable Long id,
            // CHANGE 1: Made message optional
            @RequestPart(value = "message", required = false) String message, 
            @RequestPart(value = "file", required = false) MultipartFile file,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String username = authentication.getName();

            TicketReplyDto replyDto = new TicketReplyDto();
            replyDto.setAuthorUsername(username);
            
            // CHANGE 2: Handle null message gracefully
            String safeMessage = (message != null) ? message : "";
            replyDto.setMessage(safeMessage);
            
            if (authentication.getPrincipal() instanceof ClientUserDetails) {
                replyDto.setAuthorId(((ClientUserDetails) authentication.getPrincipal()).getId());
            }

            // CHANGE 3: Allow if EITHER message has text OR file is attached
            boolean hasMessage = !safeMessage.isBlank();
            boolean hasFile = file != null && !file.isEmpty();

            if (!hasMessage && !hasFile) {
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
        return getTicketsByCategory(category, false);
    }

    @GetMapping("/tickets/category/{category}")
    public ResponseEntity<List<TicketDto>> getTicketsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        try {
            String decodedCategory = java.net.URLDecoder.decode(category, java.nio.charset.StandardCharsets.UTF_8);
            logger.debug("GET /tickets/category/{} called. ForceRefresh: {}", decodedCategory, forceRefresh);
            return ResponseEntity.ok(ticketService.getTicketsByCategory(decodedCategory, forceRefresh));
        } catch (Exception e) {
            logger.error("Error fetching tickets by category {}: {}", category, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}