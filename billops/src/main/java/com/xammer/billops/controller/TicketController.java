package com.xammer.billops.controller;

import com.xammer.billops.dto.AdminUserDto;
import com.xammer.billops.dto.AssignTicketRequest;
import com.xammer.billops.dto.RcaRequestDto;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.domain.Client;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.service.RcaService;
import com.xammer.billops.service.TicketService;
import com.xammer.billops.util.TicketHtmlSanitizer;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.TimeUnit;
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

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private com.xammer.billops.service.AdminAccessScope adminAccessScope;

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

    /**
     * Chat-content search — returns the IDs of tickets whose conversation contains the given
     * term (case-insensitive). The admin list is lightweight and carries neither the ticket's
     * opening message nor its replies, so the frontend merges these IDs into its search filter.
     *
     * <p>A ticket's chat is its original description (the first bubble) plus every reply, so this
     * searches both {@code Ticket.description} and {@code TicketReply.message} and unions the hits.
     */
    @GetMapping("/tickets/search-replies")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<List<Long>> searchReplyBodies(@RequestParam("q") String q) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        String term = q.trim();
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        ids.addAll(ticketReplyRepository.findTicketIdsByReplyMessageContaining(term));
        ids.addAll(ticketRepository.findTicketIdsByDescriptionContaining(term));
        return ResponseEntity.ok(new java.util.ArrayList<>(ids));
    }

    /**
     * Chat-content search WITH snippets — like {@link #searchReplyBodies} but returns, for each
     * matching message, the ticket id plus a short plain-text snippet centred on the match. The
     * admin UI uses this to render a dedicated "Chats" results section (keyword found inside the
     * conversation) separate from the "Tickets" section (keyword in id/subject/account/etc.).
     *
     * <p>Searches both the ticket's opening description and every reply. At most one snippet per
     * ticket is returned (the first match wins) so the Chats list has one row per ticket.
     */
    @GetMapping("/tickets/search-chats")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<List<com.xammer.billops.dto.ChatSearchMatchDto>> searchChats(@RequestParam("q") String q) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        String term = q.trim();

        // One snippet per ticket — the opening message is considered first, then replies, so a
        // match in the description is preferred when both match.
        java.util.LinkedHashMap<Long, com.xammer.billops.dto.ChatSearchMatchDto> byTicket = new java.util.LinkedHashMap<>();

        for (Object[] row : ticketRepository.findDescriptionMatches(term)) {
            Long ticketId = ((Number) row[0]).longValue();
            String text = row[1] != null ? row[1].toString() : "";
            byTicket.putIfAbsent(ticketId,
                    new com.xammer.billops.dto.ChatSearchMatchDto(ticketId, buildSnippet(text, term), "description", toIso(row[2])));
        }

        for (Object[] row : ticketReplyRepository.findReplyMatches(term)) {
            Long ticketId = ((Number) row[0]).longValue();
            String text = row[1] != null ? row[1].toString() : "";
            byTicket.putIfAbsent(ticketId,
                    new com.xammer.billops.dto.ChatSearchMatchDto(ticketId, buildSnippet(text, term), "reply", toIso(row[2])));
        }

        return ResponseEntity.ok(new java.util.ArrayList<>(byTicket.values()));
    }

    /**
     * Normalises the {@code created_at} value from a native-query row to an ISO-8601 string the
     * frontend can format. JDBC may hand this back as {@link java.sql.Timestamp} or
     * {@link java.time.LocalDateTime} depending on driver/version, so both are handled; anything
     * else falls back to {@code toString()}, and null yields null.
     */
    private static String toIso(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toString();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toString();
        }
        return value.toString();
    }

    /** Max characters of context shown on each side of the matched term in a chat snippet. */
    private static final int SNIPPET_PADDING = 60;

    /**
     * Produces a plain-text snippet centred on the first occurrence of {@code term} within
     * {@code rawHtml}: strips HTML tags, collapses whitespace, then returns a window of up to
     * {@link #SNIPPET_PADDING} characters either side of the match with ellipses where trimmed.
     * Falls back to the leading characters when the term isn't found in the stripped text (e.g.
     * the match was inside markup).
     */
    private static String buildSnippet(String rawHtml, String term) {
        if (rawHtml == null) return "";
        // Drop tags and decode the few entities the editor emits, then collapse whitespace.
        String text = rawHtml
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();

        int idx = text.toLowerCase().indexOf(term.toLowerCase());
        if (idx < 0) {
            return text.length() <= SNIPPET_PADDING * 2
                    ? text
                    : text.substring(0, SNIPPET_PADDING * 2) + "…";
        }

        int start = Math.max(0, idx - SNIPPET_PADDING);
        int end = Math.min(text.length(), idx + term.length() + SNIPPET_PADDING);
        String snippet = text.substring(start, end);
        if (start > 0) snippet = "…" + snippet;
        if (end < text.length()) snippet = snippet + "…";
        return snippet;
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
                            .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN") || a.getAuthority().equals("ROLE_BILLOPS_ADMIN_SUB"));
            return ResponseEntity.ok(ticketService.getTicketById(id, isAdmin));
        } catch (RuntimeException e) {
            logger.warn("Ticket not found for ID: {}", id);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/tickets/{id}/assign")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<List<AdminUserDto>> getAdmins() {
        List<AdminUserDto> admins = ticketService.getAdminUsers().stream()
                .map(u -> new AdminUserDto(u.getId(), u.getUsername(), u.getEmail()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(admins);
    }

    // --- NEW ENDPOINT: GENERATE AND SUBMIT RCA ---
    @PostMapping("/tickets/{id}/rca")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
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
            @RequestParam(value = "files", required = false) java.util.List<MultipartFile> files,
            @RequestParam(value = "isInternal", required = false, defaultValue = "false") boolean isInternal,
            Authentication authentication) {
        try {
            if (authentication == null || !authentication.isAuthenticated()
                    || "anonymousUser".equals(authentication.getPrincipal())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            // Only admins can post internal notes
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN") || a.getAuthority().equals("ROLE_BILLOPS_ADMIN_SUB"));

            String username = authentication.getName();

            TicketReplyDto replyDto = new TicketReplyDto();
            replyDto.setAuthorUsername(username);

            // Reply bodies are rich text typed by customers and agents, and are
            // rendered back to every participant — strip active content on the way in.
            String safeMessage = TicketHtmlSanitizer.sanitize(message);
            replyDto.setMessage(safeMessage);
            replyDto.setInternal(isInternal && isAdmin);

            if (authentication.getPrincipal() instanceof ClientUserDetails) {
                replyDto.setAuthorId(((ClientUserDetails) authentication.getPrincipal()).getId());
            }

            // Merge the multi-file param ("files") with the legacy single-file param
            // ("file") so both new and old clients work.
            java.util.List<MultipartFile> allFiles = new java.util.ArrayList<>();
            if (files != null) allFiles.addAll(files);
            if (file != null) allFiles.add(file);

            if (!allFiles.isEmpty()) {
                logger.info("Received {} file(s) for ticket reply on ticket {}", allFiles.size(), id);
            }

            return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto, allFiles));
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
            // avoids lazy-loading on a detached entity (which was causing HTTP 500).
            java.util.List<com.xammer.cloud.domain.TicketReply> siblings =
                    ticketReplyRepository.findSiblingsByReplyId(replyId);
            com.xammer.cloud.domain.TicketReply target = siblings.stream()
                    .filter(r -> r.getId().equals(replyId)).findFirst().orElse(null);
            if (target == null) {
                return ResponseEntity.notFound().build();
            }
            // Only the author can edit their own message
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

            String editedMessage = payload.get("message");
            if (editedMessage != null) {
                target.setMessage(TicketHtmlSanitizer.sanitize(editedMessage));
            }
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
                    .anyMatch(a -> a.getAuthority().equals("ROLE_BILLOPS_ADMIN") || a.getAuthority().equals("ROLE_BILLOPS_ADMIN_SUB"));

            return ticketReplyRepository.findById(replyId).map(reply -> {
                // Author can delete own message, admin can delete any
                // A reply ingested from email has no author user, so there is no "own reply" to
                // compare against — only an admin can remove one.
                boolean ownReply = reply.getAuthor() != null && reply.getAuthor().getId().equals(userId);
                if (!ownReply && !isAdmin) {
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
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ticketService.closeTicket(id));
        } catch (RuntimeException e) {
            logger.error("Error closing ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/tickets/{id}/transfer-account-billing")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<TicketDto> transferToAccountBilling(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ticketService.transferToAccountBilling(id));
        } catch (RuntimeException e) {
            logger.error("Error transferring ticket {} to Account & Billing: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/tickets/{id}/transfer-technical")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<TicketDto> transferToTechnical(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ticketService.transferToTechnical(id));
        } catch (RuntimeException e) {
            logger.error("Error transferring ticket {} to Technical: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/tickets/{id}/reopen")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<TicketDto> reopenTicket(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ticketService.reopenTicket(id));
        } catch (IllegalStateException e) {
            logger.warn("Cannot reopen ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RuntimeException e) {
            logger.error("Error reopening ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/tickets/{id}/email")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
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

    @PostMapping("/tickets/{id}/meeting")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<Void> sendMeetingInvite(
            @PathVariable Long id,
            @RequestBody com.xammer.billops.dto.MeetingInviteRequest req) {
        try {
            if (req.getRecipientEmails() == null || req.getRecipientEmails().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            ticketService.sendMeetingInviteEmail(id, req);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            logger.error("Error sending meeting invite for ticket {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ─── ADMIN TICKET INITIATION ─────────────────────────────────────────────

    /**
     * Returns all clients (id + name) — for admin ticket initiation flow.
     */
    @GetMapping("/clients")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
    public ResponseEntity<List<Map<String, Object>>> getAllClients(Authentication authentication) {
        // Scope to the clients the caller manages, derived from their granted cloud accounts.
        // Null = no restriction.
        java.util.Set<Long> allowedClientIds = adminAccessScope.clientIdsForCurrentUser();
        List<Client> clients = adminAccessScope.filterClients(
                clientRepository.findAll(), allowedClientIds);

        return ResponseEntity.ok(clients.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    return m;
                })
                .collect(Collectors.toList()));
    }

    /**
     * Returns cloud accounts for a specific client — for admin ticket initiation flow.
     */
    @GetMapping("/clients/{clientId}/accounts")
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
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
    @PreAuthorize("hasAnyAuthority('ROLE_BILLOPS_ADMIN', 'ROLE_BILLOPS_ADMIN_SUB')")
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
        return getTicketsByCategory(category, false, null);
    }

    @GetMapping("/tickets/category/{category}")
    public ResponseEntity<List<TicketDto>> getTicketsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        try {
            String decodedCategory = java.net.URLDecoder.decode(category, java.nio.charset.StandardCharsets.UTF_8);
            logger.debug("GET /tickets/category/{} called. ForceRefresh: {}", decodedCategory, forceRefresh);
            List<TicketDto> tickets = ticketService.getTicketsByCategory(decodedCategory, forceRefresh);
            String eTag = "\"" + tickets.hashCode() + "\"";
            if (!forceRefresh && eTag.equals(ifNoneMatch)) {
                return ResponseEntity.status(304).build();
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).mustRevalidate())
                    .eTag(eTag)
                    .body(tickets);
        } catch (Exception e) {
            logger.error("Error fetching tickets by category {}: {}", category, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}