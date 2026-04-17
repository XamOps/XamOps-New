package com.xammer.billops.controller;

import com.xammer.billops.dto.AdminUserDto;
import com.xammer.billops.dto.AssignTicketRequest;
import com.xammer.billops.dto.RcaRequestDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.service.RcaService;
import com.xammer.billops.service.TicketService;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/billops")
public class TicketController {

    private static final Logger logger = LoggerFactory.getLogger(TicketController.class);
    private final TicketService ticketService;
    private final RcaService rcaService;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Autowired
    private TicketReplyRepository ticketReplyRepository;

    public TicketController(TicketService ticketService, RcaService rcaService) {
        this.ticketService = ticketService;
        this.rcaService = rcaService;
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
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id, Authentication authentication) {
        try {
            boolean isAdmin = authentication != null &&
                    authentication.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN"));
            return ResponseEntity.ok(ticketService.getTicketById(id, isAdmin));
        } catch (RuntimeException e) {
            logger.warn("Ticket not found for ID: {}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/tickets/{id}/assign")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<Void> assignTicket(@PathVariable Long id, @RequestBody AssignTicketRequest req) {
        try {
            ticketService.assignTicket(id, req.getAssignedToId(), req.getAssignedToUsername());
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            logger.error("Error assigning ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/admins")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<List<AdminUserDto>> getAdmins() {
        List<AdminUserDto> admins = ticketService.getAdminUsers().stream()
                .map(u -> new AdminUserDto(u.getId(), u.getUsername(), u.getEmail()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(admins);
    }

    // --- NEW ENDPOINT: GENERATE AND SUBMIT RCA ---
    @PostMapping("/tickets/{id}/rca")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<Void> submitRcaAndClose(
            @PathVariable Long id,
            @RequestBody RcaRequestDto rcaDto,
            Authentication authentication) {
        try {
            if (authentication == null || !(authentication.getPrincipal() instanceof ClientUserDetails)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
            rcaDto.setTicketId(id);

            // Delegate to service to generate PDF, upload to S3, save DB, update status,
            // and add chat msg
            rcaService.generateAndSubmitRca(id, rcaDto, userDetails.getId());

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error generating RCA for ticket {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
    // ---------------------------------------------

    // --- UPDATED: FILE ATTACHMENT ENDPOINT ---
    @PostMapping(value = "/tickets/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TicketDto> addTicketReply(
            @PathVariable Long id,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "isInternal", required = false, defaultValue = "false") boolean isInternal,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getPrincipal())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            // Only admins can post internal notes
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN"));

            String username = authentication.getName();

            TicketReplyDto replyDto = new TicketReplyDto();
            replyDto.setAuthorUsername(username);

            String safeMessage = (message != null) ? message : "";
            replyDto.setMessage(safeMessage);
            replyDto.setInternal(isInternal && isAdmin);

            if (authentication.getPrincipal() instanceof ClientUserDetails) {
                replyDto.setAuthorId(((ClientUserDetails) authentication.getPrincipal()).getId());
            }

            if (file != null) {
                logger.info("Received file for ticket reply: {}, size: {}, type: {}",
                        file.getOriginalFilename(), file.getSize(), file.getContentType());
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
                // Only the author can edit their own message
                if (!reply.getAuthor().getId().equals(userId)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                }
                if (reply.isDeleted()) {
                    return ResponseEntity.status(HttpStatus.GONE).build();
                }
                // 15-minute edit window
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
                // Author can delete own message, admin can delete any
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

    // ───────────────────────────────────────────────────────────────────────

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

    @PostMapping("/tickets/{id}/email")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<Void> sendTicketEmail(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        try {
            List<String> recipients = new java.util.ArrayList<>();

            // Support both { recipientEmails: [...] } and legacy { recipientEmail: "..." }
            Object emailsField = body.get("recipientEmails");
            if (emailsField instanceof java.util.List) {
                ((java.util.List<?>) emailsField).forEach(e -> {
                    if (e instanceof String) recipients.add((String) e);
                });
            } else {
                Object singleEmail = body.get("recipientEmail");
                if (singleEmail instanceof String && !((String) singleEmail).isBlank()) {
                    recipients.add((String) singleEmail);
                }
            }

            if (recipients.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            String sendMode = body.getOrDefault("sendMode", "separate") instanceof String
                    ? (String) body.get("sendMode")
                    : "separate";

            ticketService.sendTicketThreadEmail(id, recipients, sendMode);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            logger.error("Error sending ticket email for ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    // ─── ADMIN TICKET INITIATION ─────────────────────────────────────────────

    /**
     * Returns all clients (id + name) — for admin ticket initiation flow.
     */
    @GetMapping("/clients")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getAllClients() {
        List<Map<String, Object>> clients = clientRepository.findAll().stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(clients);
    }

    /**
     * Returns cloud accounts for a specific client — for admin ticket initiation flow.
     */
    @GetMapping("/clients/{clientId}/accounts")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> getAccountsForClient(@PathVariable Long clientId) {
        List<Map<String, Object>> accounts = cloudAccountRepository.findByClientId(clientId).stream()
                .map(ca -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", ca.getId());
                    m.put("accountName", ca.getAccountName());
                    m.put("provider", ca.getProvider());
                    String providerId = null;
                    if ("AWS".equalsIgnoreCase(ca.getProvider())) providerId = ca.getAwsAccountId();
                    else if ("GCP".equalsIgnoreCase(ca.getProvider())) providerId = ca.getGcpProjectId();
                    else if ("Azure".equalsIgnoreCase(ca.getProvider())) providerId = ca.getAzureSubscriptionId();
                    m.put("accountIdentifier", providerId);
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(accounts);
    }

    /**
     * Admin initiates a ticket on behalf of a client.
     * The target client is set via clientId in the request body.
     */
    @PostMapping("/tickets/admin-create")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<TicketDto> adminCreateTicket(@RequestBody TicketDto ticketDto, Authentication authentication) {
        try {
            return ResponseEntity.ok(ticketService.adminCreateTicket(ticketDto, authentication));
        } catch (RuntimeException e) {
            logger.error("Error in admin ticket creation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

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