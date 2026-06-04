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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final Logger logger = LoggerFactory.getLogger(TicketService.class);
    private static final java.util.List<String> ADMIN_TECHNICAL_EMAILS = java.util.List.of(
            "techtickets@xamops.com");
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
            String subject = String.format("New Ticket [ID: %d]: %s", savedTicket.getId(), savedTicket.getSubject());
            String html = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>New Support Ticket</title></head>" +
                "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>" +
                "<tr><td style='background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:40px;'>" +
                "  <div style='margin-bottom:16px;'><span style='display:inline-block;background:rgba(255,255,255,0.2);padding:6px 14px;border-radius:20px;color:#fff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>" +
                "  <div style='font-size:40px;margin-bottom:14px;'>&#127914;</div>" +
                "  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>New Support Ticket</h1>" +
                "  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>A new ticket has been submitted</p>" +
                "</td></tr>" +
                "<tr><td style='padding:32px 40px;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='margin-bottom:20px;'>" +
                "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>Creator</td><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>" + creator.getUsername() + "</td></tr>" +
                "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>Client</td><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>" + client.getName() + "</td></tr>" +
                "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>Category</td><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>" + ticket.getCategory() + "</td></tr>" +
                "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>Service</td><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>" + ticket.getService() + "</td></tr>" +
                "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>Severity</td><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>" + ticket.getSeverity() + "</td></tr>" +
                "</table>" +
                "<h3 style='margin:0 0 8px;color:#1e293b;font-size:14px;font-weight:700;'>Description</h3>" +
                "<p style='margin:0;color:#374151;font-size:14px;line-height:1.6;background:#f8fafc;border-radius:8px;padding:14px 16px;'>" + ticket.getDescription() + "</p>" +
                "</td></tr>" +
                "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>" +
                "  <span style='display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>" +
                "  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>" +
                "  <p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>" +
                "</td></tr>" +
                "</table></td></tr></table></body></html>";

            if ("Account and Billing".equalsIgnoreCase(savedTicket.getCategory())) {
                emailService.sendHtmlEmail(ADMIN_BILLING_EMAIL, subject, html);
            } else {
                for (String email : ADMIN_TECHNICAL_EMAILS) {
                    emailService.sendHtmlEmail(email, subject, html);
                }
            }
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
    public TicketDto addReplyToTicket(Long ticketId, TicketReplyDto replyDto, List<MultipartFile> files) {
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
        reply.setMessage(replyDto.getMessage() != null ? replyDto.getMessage() : "");
        reply.setCreatedAt(java.time.LocalDateTime.now());
        reply.setInternal(replyDto.isInternal());

        if (ticket.getChatStartedAt() == null) {
            ticket.setChatStartedAt(java.time.LocalDateTime.now());
        }

        // Upload all files and store as JSON array in a single reply
        List<MultipartFile> nonEmpty = (files != null)
                ? files.stream().filter(f -> f != null && !f.isEmpty()).toList()
                : List.of();

        if (!nonEmpty.isEmpty()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<java.util.Map<String, String>> attachmentList = new ArrayList<>();
                for (MultipartFile f : nonEmpty) {
                    String key = fileStorageService.uploadFile(f);
                    attachmentList.add(java.util.Map.of(
                            "key", key,
                            "name", f.getOriginalFilename() != null ? f.getOriginalFilename() : "attachment",
                            "type", f.getContentType() != null ? f.getContentType() : "application/octet-stream"
                    ));
                }
                reply.setAttachmentsJson(mapper.writeValueAsString(attachmentList));
            } catch (Exception e) {
                logger.error("Failed to upload attachments for ticket " + ticketId, e);
                throw new RuntimeException("Failed to upload attachments.", e);
            }
        }

        ticket.getReplies().add(reply);
        ticket.setStatus("IN_PROGRESS");

        boolean isAdminReply = author.getRole() != null && author.getRole().contains("ADMIN");
        if (isAdminReply && (ticket.getAssignedToId() == null || ticket.getAssignedToId() == 0)) {
            ticket.setAssignedToId(author.getId());
            ticket.setAssignedToUsername(author.getUsername());
        }

        if (!replyDto.isInternal()) {
            ticket.setHasNewUserReply(!isAdminReply);
        }

        ticket.setUpdatedAt(java.time.LocalDateTime.now());
        // Set/clear reminder-tracking fields for the 45-min scheduler
        if (isAdminReply && !replyDto.isInternal()) {
            ticket.setLastAdminReplyAt(java.time.LocalDateTime.now());
            ticket.setReminderSentAt(null);
        } else if (!isAdminReply) {
            ticket.setLastAdminReplyAt(null);
            ticket.setReminderSentAt(null);
        }

        Ticket updatedTicket = ticketRepository.save(ticket);
        evictCountCache(updatedTicket.getCategory());

        try {
            User ticketCreator = ticket.getCreator();
            String subject = String.format("New Reply on Ticket [ID: %d]: %s", ticket.getId(), ticket.getSubject());
            String displayMessage = (reply.getMessage() == null || reply.getMessage().isBlank())
                    ? "(Attachment only)"
                    : reply.getMessage();
            String html = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>New Reply on Ticket</title></head>" +
                "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>" +
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>" +
                "<tr><td style='background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:40px;'>" +
                "  <div style='margin-bottom:16px;'><span style='display:inline-block;background:rgba(255,255,255,0.2);padding:6px 14px;border-radius:20px;color:#fff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>" +
                "  <div style='font-size:40px;margin-bottom:14px;'>&#128172;</div>" +
                "  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>New Reply on Ticket</h1>" +
                "  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Ticket #" + ticket.getId() + ": " + ticket.getSubject() + "</p>" +
                "</td></tr>" +
                "<tr><td style='padding:32px 40px;'>" +
                "<p style='margin:0 0 16px;color:#374151;font-size:15px;'><strong>" + author.getUsername() + "</strong> has replied to this ticket.</p>" +
                "<div style='background:#f8fafc;border-left:4px solid #667eea;border-radius:0 8px 8px 0;padding:14px 18px;margin-bottom:16px;'>" +
                "  <p style='margin:0;color:#1e293b;font-size:14px;line-height:1.6;'>" + displayMessage + "</p>" +
                "</div>" +
                (!nonEmpty.isEmpty() ? "<p style='margin:0;color:#64748b;font-size:13px;'>[" + nonEmpty.size() + " attachment(s)]</p>" : "") +
                "</td></tr>" +
                "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>" +
                "  <span style='display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>" +
                "  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>" +
                "  <p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>" +
                "</td></tr>" +
                "</table></td></tr></table></body></html>";
            if (isAdminReply) {
                if (ticketCreator != null && ticketCreator.getEmail() != null) {
                    emailService.sendHtmlEmail(ticketCreator.getEmail(), subject, html);
                }
            } else {
                if ("Account and Billing".equalsIgnoreCase(ticket.getCategory())) {
                    emailService.sendHtmlEmail(ADMIN_BILLING_EMAIL, subject, html);
                } else {
                    for (String email : ADMIN_TECHNICAL_EMAILS) {
                        emailService.sendHtmlEmail(email, subject, html);
                    }
                }
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
        ticket.setUpdatedAt(java.time.LocalDateTime.now());
        ticket.setLastAdminReplyAt(null);
        ticket.setReminderSentAt(null);
        Ticket updatedTicket = ticketRepository.save(ticket);
        evictCountCache(updatedTicket.getCategory());
        broadcastTicketChange();

        try {
            User creator = ticket.getCreator();
            if (creator != null && creator.getEmail() != null && !creator.getEmail().isBlank()) {
                String subject = String.format("Your ticket has been closed — #%d: %s",
                        ticket.getId(), ticket.getSubject());
                String html = buildTicketClosedEmailHtml(ticket, creator);
                emailService.sendHtmlEmail(creator.getEmail(), subject, html);
            }
        } catch (Exception e) {
            logger.error("Failed to send ticket-closed email for ticket {}", ticket.getId(), e);
        }

        return convertToDto(updatedTicket);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "tickets", allEntries = true),
        @CacheEvict(value = "tickets_by_category", allEntries = true)
    })
    public TicketDto reopenTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if (!"CLOSED".equalsIgnoreCase(ticket.getStatus())) {
            throw new IllegalStateException("Only closed tickets can be reopened.");
        }

        ticket.setStatus("OPEN");
        ticket.setClosedAt(null);
        ticket.setUpdatedAt(java.time.LocalDateTime.now());
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

        // Build attachments list: prefer new JSON field, fall back to legacy single-file columns
        if (reply.getAttachmentsJson() != null && !reply.getAttachmentsJson().isBlank()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                List<java.util.Map<String, String>> raw = mapper.readValue(
                        reply.getAttachmentsJson(),
                        mapper.getTypeFactory().constructCollectionType(List.class, java.util.Map.class));
                List<TicketReplyDto.AttachmentInfo> infos = new ArrayList<>();
                for (java.util.Map<String, String> entry : raw) {
                    TicketReplyDto.AttachmentInfo info = new TicketReplyDto.AttachmentInfo();
                    info.setName(entry.get("name"));
                    info.setType(entry.get("type"));
                    info.setUrl(fileStorageService.generatePresignedUrl(entry.get("key")));
                    infos.add(info);
                }
                dto.setAttachments(infos);
            } catch (Exception e) {
                logger.error("Failed to parse attachmentsJson for reply {}", reply.getId(), e);
            }
        } else if (reply.getAttachmentKey() != null) {
            // legacy single-file row
            TicketReplyDto.AttachmentInfo info = new TicketReplyDto.AttachmentInfo();
            info.setName(reply.getAttachmentName());
            info.setType(reply.getAttachmentType());
            info.setUrl(fileStorageService.generatePresignedUrl(reply.getAttachmentKey()));
            dto.setAttachments(List.of(info));
            // also set legacy fields so old clients still work
            dto.setAttachmentName(reply.getAttachmentName());
            dto.setAttachmentType(reply.getAttachmentType());
            dto.setAttachmentUrl(info.getUrl());
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
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>Ticket Thread Summary</title></head>");
        sb.append("<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>");
        sb.append("<tr><td style='background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:40px;'>");
        sb.append("  <div style='margin-bottom:16px;'><span style='display:inline-block;background:rgba(255,255,255,0.2);padding:6px 14px;border-radius:20px;color:#fff;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>");
        sb.append("  <div style='font-size:40px;margin-bottom:14px;'>&#128196;</div>");
        sb.append("  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Ticket Thread Summary</h1>");
        sb.append("  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Ticket #").append(ticket.getId()).append(": ").append(ticket.getSubject()).append("</p>");
        sb.append("</td></tr>");
        sb.append("<tr><td style='padding:32px 40px;'>");
        sb.append("<div style='background:#f8fafc;border:1px solid #e2e8f0;border-radius:16px;padding:24px;margin-bottom:24px'>");
        sb.append("<h2 style='margin:0 0 8px;color:#1e293b;'>Ticket #").append(ticket.getId()).append(": ").append(ticket.getSubject()).append("</h2>");
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
        sb.append("</td></tr>");
        sb.append("<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>");
        sb.append("  <span style='display:inline-block;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>");
        sb.append("  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>");
        sb.append("  <p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>");
        sb.append("</td></tr>");
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private String buildTicketClosedEmailHtml(Ticket ticket, User creator) {
        String severityVal = ticket.getSeverity() != null ? escapeHtml(ticket.getSeverity()) : "—";
        String categoryVal = ticket.getCategory() != null ? escapeHtml(ticket.getCategory()) : "—";
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1.0'>"
            + "<title>Ticket Closed</title></head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;"
            + "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>"
            + "<tr><td style='padding:32px 16px;'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
            + "style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;"
            + "overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
            + "<tr><td style='background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:40px;'>"
            + "  <div style='margin-bottom:16px;'><span style='display:inline-block;"
            + "background:rgba(255,255,255,0.2);padding:6px 14px;border-radius:20px;color:#fff;"
            + "font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
            + "  <div style='font-size:40px;margin-bottom:14px;'>&#10003;</div>"
            + "  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Ticket Closed</h1>"
            + "  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Ticket #"
            + ticket.getId() + ": " + escapeHtml(ticket.getSubject()) + "</p>"
            + "</td></tr>"
            + "<tr><td style='padding:32px 40px;'>"
            + "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>Hi <strong>"
            + escapeHtml(creator.getUsername()) + "</strong>,</p>"
            + "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>"
            + "Your support ticket has been resolved and closed by our team.</p>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
            + "style='margin-bottom:20px;'>"
            + "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;"
            + "font-size:13px;width:38%;'>Ticket ID</td>"
            + "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;"
            + "font-size:13px;font-weight:500;'>#" + ticket.getId() + "</td></tr>"
            + "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;"
            + "font-size:13px;'>Category</td>"
            + "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;"
            + "font-size:13px;font-weight:500;'>" + categoryVal + "</td></tr>"
            + "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;"
            + "font-size:13px;'>Severity</td>"
            + "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;"
            + "font-size:13px;font-weight:500;'>" + severityVal + "</td></tr>"
            + "<tr><td style='padding:10px 0;color:#64748b;font-size:13px;'>Status</td>"
            + "<td style='padding:10px 0;'>"
            + "<span style='background:#dc2626;color:white;padding:2px 10px;border-radius:12px;"
            + "font-size:11px;font-weight:700;'>CLOSED</span></td></tr>"
            + "</table>"
            + "<p style='margin:0;color:#64748b;font-size:13px;border:1px solid #e2e8f0;"
            + "border-radius:8px;padding:14px;background:#f8fafc;'>"
            + "If you believe this was closed in error, you can reopen it from your support portal.</p>"
            + "</td></tr>"
            + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;"
            + "padding:24px 40px;text-align:center;'>"
            + "  <span style='display:inline-block;background:linear-gradient(135deg,#667eea 0%,"
            + "#764ba2 100%);padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;"
            + "font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
            + "  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>"
            + "Cloud Cost Management &amp; Security Platform</p>"
            + "  <p style='margin:0;color:#cbd5e1;font-size:11px;'>"
            + "&copy; 2026 XamOps. All rights reserved.</p>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}