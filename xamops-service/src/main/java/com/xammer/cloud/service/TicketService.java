package com.xammer.cloud.service;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.dto.TicketDto;
import com.xammer.cloud.dto.TicketReplyDto;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.TicketReplyRepository;
import com.xammer.cloud.repository.TicketRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final Logger logger = LoggerFactory.getLogger(TicketService.class);
    private static final String ADMIN_TECHNICAL_EMAIL = "sahil@xammer.in";
    private static final String ADMIN_BILLING_EMAIL = "nirbhay@xammer.in";

    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private ClientRepository clientRepository;
    @Autowired
    private EmailService emailService;
    @Autowired
    private TicketReplyRepository ticketReplyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FileStorageService fileStorageService;
    @Autowired
    private RedisCacheService redisCacheService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private void broadcastTicketChange() {
        // Fire after the transaction commits AND after Spring's @CacheEvict AOP advice
        // has run (both happen after the method returns). Registering an after-commit
        // synchronization ensures the WebSocket message is sent only when the DB write
        // and cache eviction are both complete, so the subsequent frontend refetch hits
        // a cold cache and reads fresh data from the DB.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        messagingTemplate.convertAndSend("/topic/tickets", "updated");
                    } catch (Exception e) {
                        logger.warn("Failed to broadcast ticket change via WebSocket: {}", e.getMessage());
                    }
                }
            });
        } else {
            try {
                messagingTemplate.convertAndSend("/topic/tickets", "updated");
            } catch (Exception e) {
                logger.warn("Failed to broadcast ticket change via WebSocket: {}", e.getMessage());
            }
        }
    }

    private void evictCountCache(String category) {
        if (category != null) {
            redisCacheService.evict("ticket_count_" + category);
        }
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets", allEntries = true),
        @CacheEvict(value = "tickets_by_category", allEntries = true)
    })
    public TicketDto createTicket(TicketDto ticketDto) {
        // 1. Validate Creator ID is present
        if (ticketDto.getCreatorId() == null) {
            throw new RuntimeException("Creator ID is required to create a ticket.");
        }

        // 2. Fetch the Creator (User)
        User creator = userRepository.findById(ticketDto.getCreatorId())
                .orElseThrow(() -> new RuntimeException("Creator (user) not found"));

        // 3. Resolve Client from the User entity (Smart Resolution)
        Client client = creator.getClient();
        if (client == null) {
            // Fallback: Try to use the ID from DTO if User object doesn't have it
            // loaded/set
            if (ticketDto.getClientId() != null) {
                client = clientRepository.findById(ticketDto.getClientId())
                        .orElseThrow(() -> new RuntimeException("Client not found"));
            } else {
                throw new RuntimeException("User does not have an associated Client, and no Client ID was provided.");
            }
        }

        // 4. Create the Ticket
        Ticket ticket = new Ticket();
        ticket.setSubject(ticketDto.getSubject());
        ticket.setDescription(ticketDto.getDescription());
        ticket.setStatus("OPEN");
        ticket.setClient(client); // Use the resolved client
        ticket.setCategory(ticketDto.getCategory());
        ticket.setService(ticketDto.getService());
        ticket.setSeverity(ticketDto.getSeverity());
        ticket.setAccountId(ticketDto.getAccountId());
        ticket.setRegion(ticketDto.getRegion());

        ticket.setCreator(creator);

        Ticket savedTicket = ticketRepository.save(ticket);
        evictCountCache(savedTicket.getCategory());

        // 5. Send Notification Email
        try {
            String adminEmail = "Account and Billing".equalsIgnoreCase(savedTicket.getCategory()) ? ADMIN_BILLING_EMAIL
                    : ADMIN_TECHNICAL_EMAIL;

            String subject = String.format("New Ticket [ID: %d]: %s", savedTicket.getId(), savedTicket.getSubject());
            String text = String.format(
                    "A new ticket has been created by %s (Client: %s).\n\n" +
                            "Category: %s\n" +
                            "Service: %s\n" +
                            "Severity: %s\n\n" +
                            "Description:\n%s",
                    creator.getUsername(),
                    client.getName(),
                    ticket.getCategory(),
                    ticket.getService(),
                    ticket.getSeverity(),
                    ticket.getDescription());

            emailService.sendHtmlEmail(adminEmail, subject, text);
        } catch (Exception e) {
            logger.error("Failed to send new ticket notification email for ticket {}", savedTicket.getId(), e);
        }

        broadcastTicketChange();
        return convertToDto(savedTicket);
    }

    @Transactional(readOnly = true)
    public List<TicketDto> getAllTickets() {
        List<Ticket> allTickets = ticketRepository.findAll();

        // Scope tickets for regular users (ROLE_XAMOPS / ROLE_BILLOPS with createdBy set)
        // Admins and primary users (createdBy == null) see everything
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String effectiveRole = (auth != null) ? auth.getAuthorities().stream()
                    .map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_"))
                    .findFirst().orElse(null) : null;

            // Primary admins see all tickets
            if (effectiveRole != null && (effectiveRole.contains("SUPER_ADMIN") || effectiveRole.contains("BILLOPS_ADMIN"))) {
                return allTickets.stream().map(this::convertToDto).collect(Collectors.toList());
            }

            // Look up the user — fall back to username if ID doesn't match
            User currentUser = userRepository.findById(userId).orElse(null);
            if (currentUser == null && auth != null) {
                currentUser = userRepository.findByUsername(auth.getName()).orElse(null);
                logger.debug("[TicketService] getAllTickets — user not found by ID {}, username fallback: {}",
                        userId, currentUser != null ? "found id=" + currentUser.getId() : "not found");
            }

            if (currentUser == null) {
                return java.util.Collections.emptyList();
            }

            // Non-admin users (ROLE_XAMOPS, ROLE_BILLOPS) always see only their own tickets
            boolean isAdmin = effectiveRole != null && effectiveRole.contains("ADMIN");
            if (isAdmin) {
                return allTickets.stream().map(this::convertToDto).collect(Collectors.toList());
            }

            // Regular user: only their own tickets
            final Long creatorId = currentUser.getId();
            logger.debug("[TicketService] getAllTickets — non-admin id={} scoping to own tickets only", creatorId);
            return allTickets.stream()
                    .filter(t -> t.getCreator() != null && creatorId.equals(t.getCreator().getId()))
                    .map(this::convertToDto)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("[TicketService] getAllTickets scoping failed, returning empty list: {}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "tickets", key = "#id")
    public TicketDto getTicketById(Long id) {
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(id)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        return convertToDto(ticket);
    }

    /**
     * Viewer-aware ticket detail fetch — masks admin usernames as "Support Team"
     * for non-admin viewers. Not cached (viewer-dependent output).
     */
    @Transactional(readOnly = true)
    public TicketDto getTicketByIdForViewer(Long id, boolean viewerIsAdmin) {
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(id)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        return convertToDto(ticket, viewerIsAdmin);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets", allEntries = true),
        @CacheEvict(value = "tickets_by_category", allEntries = true)
    })
    public TicketDto addReplyToTicket(Long ticketId, TicketReplyDto replyDto, MultipartFile file) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if ("CLOSED".equalsIgnoreCase(ticket.getStatus())) {
            throw new IllegalStateException("Cannot add a reply to a closed ticket.");
        }

        User author;
        if (replyDto.getAuthorId() != null) {
            author = userRepository.findById(replyDto.getAuthorId())
                    .orElseThrow(() -> new RuntimeException("Author (user) not found"));
        } else if (replyDto.getAuthorUsername() != null) {
            author = userRepository.findByUsername(replyDto.getAuthorUsername())
                    .orElseThrow(() -> new RuntimeException("Author (user) not found"));
        } else {
            throw new RuntimeException("Author ID or Username is required");
        }

        TicketReply reply = new TicketReply();
        reply.setTicket(ticket);
        reply.setAuthor(author);

        // FIX: Ensure message is never null for DB persistence
        String messageContent = replyDto.getMessage() != null ? replyDto.getMessage() : "";
        reply.setMessage(messageContent);

        reply.setCreatedAt(java.time.LocalDateTime.now());
        reply.setInternal(replyDto.isInternal());

        // Track when chat first started
        if (ticket.getChatStartedAt() == null) {
            ticket.setChatStartedAt(java.time.LocalDateTime.now());
        }

        if (file != null && !file.isEmpty()) {
            try {
                String key = fileStorageService.uploadFile(file);
                reply.setAttachmentKey(key);
                reply.setAttachmentName(file.getOriginalFilename());
                reply.setAttachmentType(file.getContentType());
            } catch (Exception e) {
                logger.error("Failed to upload attachment for ticket " + ticketId, e);
                throw new RuntimeException("Failed to upload attachment.", e);
            }
        }

        ticket.getReplies().add(reply);
        ticket.setStatus("IN_PROGRESS");

        // Auto-assign ticket to the first admin who replies
        boolean isAdminReply = author.getRole() != null && author.getRole().contains("ADMIN");
        if (isAdminReply && (ticket.getAssignedToId() == null || ticket.getAssignedToId() == 0)) {
            ticket.setAssignedToId(author.getId());
            ticket.setAssignedToUsername(author.getUsername());
        }

        // Track read/unread: admin reply marks as read; user reply marks as unread
        if (!replyDto.isInternal()) {
            ticket.setHasNewUserReply(!isAdminReply);
        }

        Ticket updatedTicket = ticketRepository.save(ticket);
        evictCountCache(updatedTicket.getCategory());

        try {
            User ticketCreator = ticket.getCreator();

            String subject = String.format("New Reply on Ticket [ID: %d]: %s", ticket.getId(), ticket.getSubject());

            // FIX: Graceful fallback for email body if message is empty
            String displayMessage = (reply.getMessage() == null || reply.getMessage().isBlank())
                    ? "(Attachment only)"
                    : reply.getMessage();

            String text = String.format(
                    "%s has replied to ticket %d:\n\n---\n%s\n---",
                    author.getUsername(),
                    ticket.getId(),
                    displayMessage);

            if (reply.getAttachmentName() != null) {
                text += "\n\n[Attachment: " + reply.getAttachmentName() + "]";
            }

            if (isAdminReply) {
                if (ticketCreator != null && ticketCreator.getEmail() != null) {
                    emailService.sendHtmlEmail(ticketCreator.getEmail(), subject, text);
                } else {
                    logger.warn("Cannot notify ticket creator for ticket {}: Creator or email is null.",
                            ticket.getId());
                }
            } else {
                String adminEmail = "Account and Billing".equalsIgnoreCase(ticket.getCategory()) ? ADMIN_BILLING_EMAIL
                        : ADMIN_TECHNICAL_EMAIL;
                emailService.sendHtmlEmail(adminEmail, subject, text);
            }
        } catch (Exception e) {
            logger.error("Failed to send reply notification email for ticket {}", ticket.getId(), e);
        }

        broadcastTicketChange();
        return convertToDto(updatedTicket);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets", allEntries = true),
        @CacheEvict(value = "tickets_by_category", allEntries = true)
    })
    public TicketDto closeTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        ticket.setStatus("CLOSED");
        ticket.setClosedAt(java.time.LocalDateTime.now());
        Ticket updatedTicket = ticketRepository.save(ticket);
        evictCountCache(updatedTicket.getCategory());
        broadcastTicketChange();
        return convertToDto(updatedTicket);
    }

    // User-scoped cache key: tickets are filtered per user so each user gets their own Redis entry.
    @Transactional(readOnly = true)
    @Cacheable(value = "tickets_by_category", key = "#category + ':user:' + T(com.xammer.cloud.util.SecurityUtils).getCurrentUserId()")
    public List<TicketDto> getTicketsByCategory(String category) {
        List<Ticket> allTickets = ticketRepository.findAllByCategoryWithRepliesAndAuthors(category);

        try {
            Long userId = SecurityUtils.getCurrentUserId();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String effectiveRole = (auth != null) ? auth.getAuthorities().stream()
                    .map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_"))
                    .findFirst().orElse(null) : null;

            if (effectiveRole != null && (effectiveRole.contains("SUPER_ADMIN") || effectiveRole.contains("BILLOPS_ADMIN"))) {
                return allTickets.stream().map(this::convertToDto).collect(Collectors.toList());
            }

            User currentUser = userRepository.findById(userId).orElse(null);
            if (currentUser == null && auth != null) {
                currentUser = userRepository.findByUsername(auth.getName()).orElse(null);
            }

            if (currentUser == null) {
                return java.util.Collections.emptyList();
            }

            boolean isAdmin = effectiveRole != null && effectiveRole.contains("ADMIN");
            if (isAdmin) {
                return allTickets.stream().map(this::convertToDto).collect(Collectors.toList());
            }

            final Long creatorId = currentUser.getId();
            return allTickets.stream()
                    .filter(t -> t.getCreator() != null && creatorId.equals(t.getCreator().getId()))
                    .map(this::convertToDto)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("[TicketService] getTicketsByCategory scoping failed: {}", e.getMessage(), e);
            return java.util.Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public long getActiveTicketCount(String category) {
        String cacheKey = "ticket_count_" + category;
        Optional<Long> cached = redisCacheService.get(cacheKey, Long.class);
        if (cached.isPresent()) {
            logger.info("Active ticket count for '{}' served from Redis cache", category);
            return cached.get();
        }
        long count = ticketRepository.countActiveByCategoryName(category);
        redisCacheService.put(cacheKey, count, 1440); // 24 hours TTL
        logger.info("Active ticket count for '{}' cached in Redis: {}", category, count);
        return count;
    }

    private TicketDto convertToDto(Ticket ticket) {
        TicketDto dto = new TicketDto();
        dto.setId(ticket.getId());
        dto.setSubject(ticket.getSubject());
        dto.setDescription(ticket.getDescription());
        dto.setStatus(ticket.getStatus());
        dto.setCreatedAt(ticket.getCreatedAt());
        dto.setCategory(ticket.getCategory());
        dto.setService(ticket.getService());
        dto.setSeverity(ticket.getSeverity());
        dto.setAccountId(ticket.getAccountId());
        dto.setRegion(ticket.getRegion());

        dto.setClosedAt(ticket.getClosedAt());
        dto.setChatStartedAt(ticket.getChatStartedAt());
        dto.setAssignedToId(ticket.getAssignedToId());
        dto.setAssignedToUsername(ticket.getAssignedToUsername());

        if (ticket.getClient() != null) {
            dto.setClientId(ticket.getClient().getId());
        }

        if (ticket.getCreator() != null) {
            dto.setCreatorId(ticket.getCreator().getId());
            dto.setCreatorUsername(ticket.getCreator().getUsername());
        }

        if (ticket.getReplies() != null) {
            dto.setReplies(ticket.getReplies().stream()
                    .map(this::convertReplyToDto)
                    .sorted(Comparator.comparing(TicketReplyDto::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList()));
        }

        return dto;
    }

    private TicketReplyDto convertReplyToDto(TicketReply reply) {
        TicketReplyDto dto = new TicketReplyDto();
        dto.setId(reply.getId());
        dto.setTicketId(reply.getTicket().getId());
        dto.setMessage(reply.getMessage());
        dto.setCreatedAt(reply.getCreatedAt());
        dto.setInternal(reply.isInternal());
        if (reply.getAuthor() != null) {
            dto.setAuthorId(reply.getAuthor().getId());
            dto.setAuthorUsername(reply.getAuthor().getUsername());
            boolean isAdmin = reply.getAuthor().getRole() != null && reply.getAuthor().getRole().contains("ADMIN");
            dto.setAdmin(isAdmin);
        }

        if (reply.getAttachmentKey() != null) {
            dto.setAttachmentName(reply.getAttachmentName());
            dto.setAttachmentType(reply.getAttachmentType());
            String presignedUrl = fileStorageService.generatePresignedUrl(reply.getAttachmentKey());
            dto.setAttachmentUrl(presignedUrl);
        }

        dto.setEdited(reply.isEdited());
        dto.setEditedAt(reply.getEditedAt());
        dto.setDeleted(reply.isDeleted());

        return dto;
    }

    /** Viewer-aware reply conversion — masks admin usernames for non-admin viewers */
    private TicketReplyDto convertReplyToDto(TicketReply reply, boolean viewerIsAdmin) {
        TicketReplyDto dto = convertReplyToDto(reply);
        if (!viewerIsAdmin && dto.isAdmin()) {
            dto.setAuthorUsername("Support Team");
        }
        return dto;
    }

    /** Viewer-aware ticket DTO conversion */
    private TicketDto convertToDto(Ticket ticket, boolean viewerIsAdmin) {
        TicketDto dto = new TicketDto();
        dto.setId(ticket.getId());
        dto.setSubject(ticket.getSubject());
        dto.setDescription(ticket.getDescription());
        dto.setStatus(ticket.getStatus());
        dto.setCreatedAt(ticket.getCreatedAt());
        dto.setCategory(ticket.getCategory());
        dto.setService(ticket.getService());
        dto.setSeverity(ticket.getSeverity());
        dto.setAccountId(ticket.getAccountId());
        dto.setRegion(ticket.getRegion());
        dto.setClosedAt(ticket.getClosedAt());
        dto.setChatStartedAt(ticket.getChatStartedAt());
        dto.setAssignedToId(ticket.getAssignedToId());
        dto.setAssignedToUsername(ticket.getAssignedToUsername());

        if (ticket.getClient() != null) {
            dto.setClientId(ticket.getClient().getId());
        }
        if (ticket.getCreator() != null) {
            dto.setCreatorId(ticket.getCreator().getId());
            dto.setCreatorUsername(ticket.getCreator().getUsername());
        }
        if (ticket.getReplies() != null) {
            dto.setReplies(ticket.getReplies().stream()
                    .map(r -> convertReplyToDto(r, viewerIsAdmin))
                    .sorted(Comparator.comparing(TicketReplyDto::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList()));
        }
        return dto;
    }

    /** Send the full ticket conversation thread as a formatted HTML email */
    @Transactional(readOnly = true)
    public void sendTicketThreadEmail(Long ticketId, String recipientEmail) {
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        String subject = String.format("[Ticket #%d] %s — Thread Summary", ticket.getId(), ticket.getSubject());
        String htmlBody = buildTicketThreadEmailTemplate(ticket);
        emailService.sendHtmlEmail(recipientEmail, subject, htmlBody);
    }

    private String buildTicketThreadEmailTemplate(Ticket ticket) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><body style='font-family:sans-serif;color:#1e293b;max-width:680px;margin:0 auto;padding:24px'>");
        sb.append("<div style='background:#f8fafc;border:1px solid #e2e8f0;border-radius:16px;padding:24px;margin-bottom:24px'>");
        sb.append("<h2 style='margin:0 0 8px'>Ticket #").append(ticket.getId()).append(": ").append(ticket.getSubject()).append("</h2>");
        sb.append("<p style='margin:0;font-size:12px;color:#64748b'>Category: ").append(ticket.getCategory())
          .append(" &nbsp;|&nbsp; Severity: ").append(ticket.getSeverity())
          .append(" &nbsp;|&nbsp; Status: ").append(ticket.getStatus()).append("</p>");
        if (ticket.getCreator() != null) {
            sb.append("<p style='margin:4px 0 0;font-size:12px;color:#64748b'>Raised by: ").append(ticket.getCreator().getUsername()).append("</p>");
        }
        sb.append("</div>");
        sb.append("<h3 style='font-size:13px;color:#64748b;text-transform:uppercase;letter-spacing:0.1em'>Conversation Thread</h3>");

        if (ticket.getReplies() != null) {
            ticket.getReplies().stream()
                .filter(r -> !r.isInternal())
                .sorted(Comparator.comparing(TicketReply::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(reply -> {
                    boolean isAdminAuthor = reply.getAuthor() != null && reply.getAuthor().getRole() != null
                            && reply.getAuthor().getRole().contains("ADMIN");
                    String displayName = isAdminAuthor ? "Support Team" : (reply.getAuthor() != null ? reply.getAuthor().getUsername() : "Unknown");
                    String bgColor = isAdminAuthor ? "#eef2ff" : "#f8fafc";
                    String borderColor = isAdminAuthor ? "#818cf8" : "#e2e8f0";
                    sb.append("<div style='border:1px solid ").append(borderColor)
                      .append(";border-radius:12px;padding:16px;margin-bottom:16px;background:").append(bgColor).append("'>");
                    sb.append("<p style='margin:0 0 8px;font-size:11px;color:#64748b;font-weight:bold;text-transform:uppercase'>")
                      .append(displayName).append(" &nbsp;·&nbsp; ").append(reply.getCreatedAt() != null ? reply.getCreatedAt().toString() : "").append("</p>");
                    sb.append("<div style='font-size:14px'>").append(reply.getMessage()).append("</div>");
                    if (reply.getAttachmentName() != null) {
                        sb.append("<p style='margin:8px 0 0;font-size:11px;color:#6366f1'>[Attachment: ").append(reply.getAttachmentName()).append("]</p>");
                    }
                    sb.append("</div>");
                });
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}