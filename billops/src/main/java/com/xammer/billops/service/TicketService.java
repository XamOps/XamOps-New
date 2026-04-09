package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.repository.UserCloudAccountAccessRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.billops.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final Logger logger = LoggerFactory.getLogger(TicketService.class);

    @PostConstruct
    public void init() {
        logger.info("✅ TicketService initialized");
    }

    private static final String ADMIN_TECHNICAL_EMAIL = "sahil@xammer.in";
    private static final String ADMIN_BILLING_EMAIL = "nirbhay@xammer.in";

    // Cache Keys
    private static final String TICKETS_ALL_CACHE_KEY = "tickets:all";
    private static final String TICKETS_CATEGORY_PREFIX = "tickets:category:";
    private static final long CACHE_TTL_MINUTES = 60;

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
    private RedisCacheService redisCache;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private UserCloudAccountAccessRepository userCloudAccountAccessRepository;
    @Autowired
    private CloudAccountRepository cloudAccountRepository;

    @Transactional
    public TicketDto createTicket(TicketDto ticketDto) {
        // 1. Validate Creator
        if (ticketDto.getCreatorId() == null) {
            throw new RuntimeException("Creator ID is required to create a ticket.");
        }

        User creator = userRepository.findById(ticketDto.getCreatorId())
                .orElseThrow(() -> new RuntimeException("Creator (user) not found"));

        // 2. Resolve Client from Creator
        Client client = creator.getClient();
        if (client == null) {
            if (ticketDto.getClientId() != null) {
                client = clientRepository.findById(ticketDto.getClientId())
                        .orElseThrow(() -> new RuntimeException("Client not found"));
            } else {
                throw new RuntimeException("User does not have an associated Client, and no Client ID was provided.");
            }
        }

        // 3. Create Ticket
        Ticket ticket = new Ticket();
        ticket.setSubject(ticketDto.getSubject());
        ticket.setDescription(ticketDto.getDescription());
        ticket.setStatus("OPEN");
        ticket.setClient(client);
        ticket.setCategory(ticketDto.getCategory());
        ticket.setService(ticketDto.getService());
        ticket.setSeverity(ticketDto.getSeverity());
        ticket.setAccountId(ticketDto.getAccountId());
        ticket.setRegion(ticketDto.getRegion());
        ticket.setCreator(creator);

        Ticket savedTicket = ticketRepository.save(ticket);

        // 4. Send Email
        try {
            String adminEmail = "Account and Billing".equalsIgnoreCase(savedTicket.getCategory()) ? ADMIN_BILLING_EMAIL
                    : ADMIN_TECHNICAL_EMAIL;

            String subject = String.format("New Ticket [ID: %d]: %s", savedTicket.getId(), savedTicket.getSubject());

            // Create HTML email template matching UI design
            String htmlBody = buildTicketEmailTemplate(savedTicket, creator, client);

            emailService.sendHtmlEmail(adminEmail, subject, htmlBody);
        } catch (Exception e) {
            logger.error("Failed to send new ticket notification email for ticket {}", savedTicket.getId(), e);
        }

        // 5. Evict Cache (Includes the specific category)
        evictTicketCaches(savedTicket.getCategory());

        return convertToDto(savedTicket, true);
    }

    /**
     * Admin-initiated ticket creation on behalf of a client.
     * Bypasses creator→client resolution — uses clientId directly.
     * The admin user becomes the ticket creator for audit purposes.
     */
    @Transactional
    public TicketDto adminCreateTicket(TicketDto ticketDto, org.springframework.security.core.Authentication authentication) {
        if (ticketDto.getClientId() == null) {
            throw new RuntimeException("clientId is required for admin ticket creation");
        }

        Client client = clientRepository.findById(ticketDto.getClientId())
                .orElseThrow(() -> new RuntimeException("Client not found: " + ticketDto.getClientId()));

        // Use the admin as the creator (audit trail)
        User creator = null;
        if (authentication != null && authentication.getPrincipal() instanceof com.xammer.cloud.security.ClientUserDetails) {
            Long adminId = ((com.xammer.cloud.security.ClientUserDetails) authentication.getPrincipal()).getId();
            creator = userRepository.findById(adminId).orElse(null);
        }

        Ticket ticket = new Ticket();
        ticket.setSubject(ticketDto.getSubject());
        ticket.setDescription(ticketDto.getDescription());
        ticket.setStatus("OPEN");
        ticket.setClient(client);
        ticket.setCategory(ticketDto.getCategory());
        ticket.setService(ticketDto.getService());
        ticket.setSeverity(ticketDto.getSeverity());
        ticket.setAccountId(ticketDto.getAccountId());
        ticket.setRegion(ticketDto.getRegion());
        ticket.setCreator(creator);

        Ticket savedTicket = ticketRepository.save(ticket);

        try {
            String adminEmail = "Account and Billing".equalsIgnoreCase(savedTicket.getCategory())
                    ? ADMIN_BILLING_EMAIL : ADMIN_TECHNICAL_EMAIL;
            String subject = String.format("[Admin] New Ticket [ID: %d]: %s", savedTicket.getId(), savedTicket.getSubject());
            String htmlBody = buildTicketEmailTemplate(savedTicket, creator, client);
            emailService.sendHtmlEmail(adminEmail, subject, htmlBody);
        } catch (Exception e) {
            logger.error("Failed to send email for admin-created ticket {}", savedTicket.getId(), e);
        }

        evictTicketCaches(savedTicket.getCategory());
        return convertToDto(savedTicket, true);
    }

    /**
     * Helper to generate tenant-aware cache key
     */
    private String getTenantCacheKey(String key) {
        String tenantId = com.xammer.billops.config.multitenancy.TenantContext.getCurrentTenant();
        if (tenantId == null)
            tenantId = "default";
        return key + ":" + tenantId;
    }

    /**
     * Unified method to get all tickets.
     * For sub-users (createdBy != null), tickets are filtered to only those
     * belonging to their allowed cloud accounts.
     */
    @Transactional(readOnly = true)
    public List<TicketDto> getAllTickets(boolean forceRefresh) {
        String cacheKey = getTenantCacheKey(TICKETS_ALL_CACHE_KEY);
        List<TicketDto> allTickets;

        if (!forceRefresh) {
            Optional<List<TicketDto>> cached = redisCache.get(cacheKey,
                    new TypeReference<List<TicketDto>>() {
                    });
            if (cached.isPresent()) {
                logger.debug("Returning CACHED all tickets for tenant: {}", cacheKey);
                allTickets = cached.get();
                enrichWithAccountNames(allTickets);
                return applyAccountFilter(allTickets);
            }
        }

        logger.debug("Fetching FRESH all tickets and updating cache for tenant: {}", cacheKey);
        long now = System.currentTimeMillis();
        allTickets = ticketRepository.findAllForAdminList().stream()
                .map(t -> {
                    TicketDto dto = this.convertToListDto(t);
                    dto.setLastUpdated(now);
                    return dto;
                })
                .collect(Collectors.toList());

        enrichWithAccountNames(allTickets);
        redisCache.put(cacheKey, allTickets, CACHE_TTL_MINUTES);
        return applyAccountFilter(allTickets);
    }

    /**
     * Filters tickets by the current user's allowed cloud accounts.
     * Primary admins (createdBy == null) see all tickets unfiltered.
     * Sub-users see only tickets belonging to their permitted accounts.
     */
    private List<TicketDto> applyAccountFilter(List<TicketDto> tickets) {
        Long userId = SecurityUtils.getCurrentUserId();
        AppUser currentUser = appUserRepository.findById(userId).orElse(null);

        // Primary admin — no restriction
        if (currentUser == null || currentUser.getCreatedBy() == null) {
            return tickets;
        }

        // Sub-user: resolve allowed provider account ID strings
        List<Long> allowedDbIds = userCloudAccountAccessRepository.findCloudAccountIdsByUserId(userId);
        if (allowedDbIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        List<CloudAccount> allowedAccounts = cloudAccountRepository.findAllById(allowedDbIds);
        java.util.Set<String> allowedProviderIds = allowedAccounts.stream()
                .map(this::resolveProviderAccountId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(java.util.stream.Collectors.toSet());

        logger.debug("Sub-user {} allowed provider account IDs: {}", userId, allowedProviderIds);

        return tickets.stream()
                .filter(t -> t.getAccountId() != null && allowedProviderIds.contains(t.getAccountId()))
                .collect(Collectors.toList());
    }

    /**
     * Returns the cloud-provider-specific account identifier for a CloudAccount,
     * which is what gets stored in Ticket.accountId.
     */
    private String resolveProviderAccountId(CloudAccount account) {
        if (account == null) return null;
        String provider = account.getProvider();
        if ("AWS".equalsIgnoreCase(provider)) return account.getAwsAccountId();
        if ("GCP".equalsIgnoreCase(provider)) return account.getGcpProjectId();
        if ("Azure".equalsIgnoreCase(provider)) return account.getAzureSubscriptionId();
        return null;
    }

    @Transactional(readOnly = true)
    public TicketDto getTicketById(Long ticketId, boolean isAdmin) {
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(ticketId);
        if (ticket == null) {
            throw new RuntimeException("Ticket not found");
        }
        return convertToDto(ticket, isAdmin);
    }

    /**
     * Unified method to get tickets by category.
     */
    @Transactional(readOnly = true)
    public List<TicketDto> getTicketsByCategory(String category, boolean forceRefresh) {
        String cacheKey = getTenantCacheKey(TICKETS_CATEGORY_PREFIX + category);

        if (!forceRefresh) {
            Optional<List<TicketDto>> cached = redisCache.get(cacheKey, new TypeReference<List<TicketDto>>() {
            });
            if (cached.isPresent()) {
                logger.debug("Returning CACHED tickets for category: {}", cacheKey);
                return cached.get();
            }
        }

        logger.debug("Fetching FRESH tickets for category: {} and updating cache", cacheKey);
        long now = System.currentTimeMillis();
        List<TicketDto> freshData = ticketRepository.findAllByCategoryWithRepliesAndAuthors(category).stream()
                .map(t -> {
                    TicketDto dto = this.convertToDto(t, false);
                    dto.setLastUpdated(now);
                    return dto;
                })
                .collect(Collectors.toList());

        redisCache.put(cacheKey, freshData, CACHE_TTL_MINUTES);
        return freshData;
    }

    @Transactional
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
            throw new RuntimeException("Author details missing");
        }

        TicketReply reply = new TicketReply();
        reply.setTicket(ticket);
        reply.setAuthor(author);

        String message = replyDto.getMessage() != null ? replyDto.getMessage() : "";
        reply.setMessage(message);

        reply.setCreatedAt(java.time.LocalDateTime.now());

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

        reply.setInternal(replyDto.isInternal());

        ticket.getReplies().add(reply);

        // Track when the first reply (chat start) is made
        if (ticket.getChatStartedAt() == null) {
            ticket.setChatStartedAt(java.time.LocalDateTime.now());
        }

        ticket.setStatus("IN_PROGRESS");

        // Auto-assign: when an admin replies and no admin is currently assigned, auto-assign to this admin
        boolean isAdminReply = author.getRole() != null && author.getRole().contains("ADMIN");
        if (isAdminReply && (ticket.getAssignedToId() == null || ticket.getAssignedToId() == 0)) {
            ticket.setAssignedToId(author.getId());
            ticket.setAssignedToUsername(author.getUsername());
        }

        // Track read/unread: admin reply marks ticket as read; user reply marks as unread
        if (!replyDto.isInternal()) {
            ticket.setHasNewUserReply(!isAdminReply);
        }

        Ticket updatedTicket = ticketRepository.save(ticket);

        // Skip email notifications for internal admin notes
        if (!replyDto.isInternal()) {
            try {
                User ticketCreator = ticket.getCreator();
                String subject = String.format("New Reply on Ticket [ID: %d]: %s", ticket.getId(), ticket.getSubject());

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
                        emailService.sendSimpleMessage(ticketCreator.getEmail(), subject, text);
                    }
                } else {
                    String adminEmail = "Account and Billing".equalsIgnoreCase(ticket.getCategory()) ? ADMIN_BILLING_EMAIL
                            : ADMIN_TECHNICAL_EMAIL;
                    emailService.sendSimpleMessage(adminEmail, subject, text);
                }
            } catch (Exception e) {
                logger.error("Failed to send reply notification email for ticket {}", ticket.getId(), e);
            }
        }

        // Evict Cache for this specific category + Admin list
        evictTicketCaches(updatedTicket.getCategory());

        return convertToDto(updatedTicket, true);
    }

    @Transactional
    public TicketDto closeTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        ticket.setStatus("CLOSED");
        ticket.setClosedAt(java.time.LocalDateTime.now());
        Ticket updatedTicket = ticketRepository.save(ticket);

        // Evict Cache for this specific category + Admin list
        evictTicketCaches(updatedTicket.getCategory());

        return convertToDto(updatedTicket, true);
    }

    @Transactional
    public void assignTicket(Long ticketId, Long adminId, String adminUsername) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        ticket.setAssignedToId(adminId);
        ticket.setAssignedToUsername(adminUsername);
        ticketRepository.save(ticket);

        evictTicketCaches(ticket.getCategory());
    }

    public List<User> getAdminUsers() {
        return userRepository.findAll().stream()
                .filter(u -> u.getRole() != null && u.getRole().contains("ROLE_BILLOPS_ADMIN"))
                .collect(Collectors.toList());
    }

    /**
     * Send the full ticket conversation thread as a formatted HTML email.
     * sendMode: "separate" → one email per recipient; "combined" → single email with all in TO.
     */
    @Transactional(readOnly = true)
    public void sendTicketThreadEmail(Long ticketId, List<String> recipientEmails, String sendMode) {
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(ticketId);
        if (ticket == null) {
            throw new RuntimeException("Ticket not found");
        }

        List<String> validEmails = recipientEmails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());

        if (validEmails.isEmpty()) return;

        String subject = String.format("Ticket Thread [#%d]: %s", ticket.getId(), ticket.getSubject());
        String htmlBody = buildTicketThreadEmailTemplate(ticket);

        if ("combined".equalsIgnoreCase(sendMode)) {
            emailService.sendHtmlEmailToMultiple(validEmails.toArray(new String[0]), subject, htmlBody);
        } else {
            for (String email : validEmails) {
                emailService.sendHtmlEmail(email, subject, htmlBody);
            }
        }
    }

    /**
     * Build a full chat-thread email for a ticket (sent by admin on demand).
     */
    private String buildTicketThreadEmailTemplate(Ticket ticket) {
        StringBuilder replies = new StringBuilder();
        if (ticket.getReplies() != null) {
            List<TicketReply> sorted = ticket.getReplies().stream()
                    .filter(r -> !r.isInternal())
                    .sorted(Comparator.comparing(TicketReply::getCreatedAt))
                    .collect(Collectors.toList());

            for (TicketReply reply : sorted) {
                String username = reply.getAuthor() != null ? reply.getAuthor().getUsername() : "Unknown";
                boolean isAdmin = reply.getAuthor() != null && reply.getAuthor().getRole() != null
                        && reply.getAuthor().getRole().contains("ADMIN");
                String label = isAdmin ? "Support Team" : username;
                String bgColor = isAdmin ? "#eef2ff" : "#f9fafb";
                String borderColor = isAdmin ? "#6366f1" : "#e5e7eb";
                String labelColor = isAdmin ? "#4f46e5" : "#374151";
                String time = reply.getCreatedAt() != null ? reply.getCreatedAt().toString().replace("T", " ").substring(0, 16) : "";

                replies.append("<div style=\"margin-bottom:16px;padding:16px 20px;background:")
                        .append(bgColor)
                        .append(";border-left:4px solid ")
                        .append(borderColor)
                        .append(";border-radius:8px;\">")
                        .append("<div style=\"margin-bottom:8px;display:flex;justify-content:space-between;\">")
                        .append("<span style=\"font-weight:700;font-size:13px;color:")
                        .append(labelColor)
                        .append(";\">")
                        .append(escapeHtml(label))
                        .append("</span>")
                        .append("<span style=\"font-size:11px;color:#9ca3af;\">")
                        .append(escapeHtml(time))
                        .append("</span></div>")
                        .append("<div style=\"font-size:14px;color:#374151;line-height:1.6;\">")
                        .append(reply.getMessage() != null ? reply.getMessage() : "")
                        .append("</div>");

                if (reply.getAttachmentName() != null) {
                    replies.append("<div style=\"margin-top:8px;font-size:12px;color:#6b7280;\">")
                            .append("📎 ").append(escapeHtml(reply.getAttachmentName()))
                            .append("</div>");
                }
                replies.append("</div>");
            }
        }

        String creatorName = ticket.getCreator() != null ? ticket.getCreator().getUsername() : "Unknown";
        String statusColor = getStatusColor(ticket.getStatus());

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f3f4f6;\">"
                + "<div style=\"max-width:640px;margin:32px auto;background:white;border-radius:16px;overflow:hidden;box-shadow:0 4px 16px rgba(0,0,0,0.08);\">"
                + "<div style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:28px 24px;\">"
                + "<h1 style=\"margin:0;color:white;font-size:20px;font-weight:700;\">Ticket Thread</h1>"
                + "<p style=\"margin:6px 0 0 0;color:rgba(255,255,255,0.85);font-size:13px;\">Case #" + ticket.getId() + " — " + escapeHtml(ticket.getSubject()) + "</p>"
                + "</div>"
                + "<div style=\"padding:24px;border-bottom:1px solid #e5e7eb;\">"
                + "<table style=\"width:100%;border-collapse:collapse;font-size:13px;\">"
                + "<tr><td style=\"padding:4px 12px 4px 0;color:#6b7280;font-weight:600;\">Status</td>"
                + "<td><span style=\"background:" + statusColor + ";color:white;padding:2px 10px;border-radius:12px;font-size:11px;font-weight:700;\">" + escapeHtml(ticket.getStatus()) + "</span></td></tr>"
                + "<tr><td style=\"padding:4px 12px 4px 0;color:#6b7280;font-weight:600;\">Created by</td><td style=\"color:#111827;\">" + escapeHtml(creatorName) + "</td></tr>"
                + "<tr><td style=\"padding:4px 12px 4px 0;color:#6b7280;font-weight:600;\">Category</td><td style=\"color:#111827;\">" + escapeHtml(ticket.getCategory() != null ? ticket.getCategory() : "") + "</td></tr>"
                + "<tr><td style=\"padding:4px 12px 4px 0;color:#6b7280;font-weight:600;\">Service</td><td style=\"color:#111827;\">" + escapeHtml(ticket.getService() != null ? ticket.getService() : "") + "</td></tr>"
                + "</table></div>"
                + "<div style=\"padding:24px;\">"
                + "<h2 style=\"margin:0 0 16px 0;font-size:14px;font-weight:800;color:#374151;text-transform:uppercase;letter-spacing:0.05em;\">Conversation</h2>"
                + replies.toString()
                + "</div>"
                + "<div style=\"padding:16px 24px;background:#f9fafb;border-top:1px solid #e5e7eb;text-align:center;\">"
                + "<p style=\"margin:0;font-size:11px;color:#9ca3af;\">This conversation was exported from <strong style=\"color:#667eea;\">XamOps Support</strong></p>"
                + "</div>"
                + "</div></body></html>";
    }

    /**
     * Evicts the global "All Tickets" list (for Admins) AND the specific category
     * cache (for Users).
     * 
     * @param category The category of the ticket that was modified. Can be null if
     *                 unknown.
     */
    public void evictTicketCaches(String category) {
        // 1. Evict Admin List (Always)
        String allTicketsKey = getTenantCacheKey(TICKETS_ALL_CACHE_KEY);
        redisCache.evict(allTicketsKey);

        // 2. Evict Specific Category Cache (If known)
        if (category != null && !category.isEmpty()) {
            String categoryKey = getTenantCacheKey(TICKETS_CATEGORY_PREFIX + category);
            redisCache.evict(categoryKey);
            logger.info("Evicted ticket caches: {} AND {}", allTicketsKey, categoryKey);
        } else {
            logger.info("Evicted ticket cache: {}", allTicketsKey);
        }
    }

    /**
     * Build HTML email template for new ticket notification
     * Matches the UI design with modern gradient theme
     */
    private String buildTicketEmailTemplate(Ticket ticket, User creator, Client client) {
        String statusColor = getStatusColor(ticket.getStatus());
        String severityColor = getSeverityColor(ticket.getSeverity());

        return "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head>" +
                "    <meta charset=\"UTF-8\">" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "    <title>New Support Ticket</title>" +
                "</head>" +
                "<body style=\"margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Inter', 'Segoe UI', sans-serif; background: linear-gradient(135deg, #f5f7fa 0%, #e9ecef 100%);\">" +
                "    <div style=\"max-width: 600px; margin: 40px auto; background: white; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);\">" +
                "        " +
                "        <!-- Header -->" +
                "        <div style=\"background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 32px 24px; text-align: center;\">" +
                "            <h1 style=\"margin: 0; color: white; font-size: 24px; font-weight: 700; letter-spacing: -0.5px;\">New Support Ticket</h1>" +
                "            <p style=\"margin: 8px 0 0 0; color: rgba(255, 255, 255, 0.9); font-size: 14px;\">Ticket ID: #" + ticket.getId() + "</p>" +
                "        </div>" +
                "        " +
                "        <!-- Content -->" +
                "        <div style=\"padding: 32px 24px;\">" +
                "            " +
                "            <!-- Status Badge -->" +
                "            <div style=\"text-align: center; margin-bottom: 24px;\">" +
                "                <span style=\"display: inline-block; background: " + statusColor + "; color: white; padding: 8px 16px; border-radius: 20px; font-size: 12px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px;\">" + ticket.getStatus() + "</span>" +
                "            </div>" +
                "            " +
                "            <!-- Ticket Details Card -->" +
                "            <div style=\"background: #f9fafb; border-radius: 12px; padding: 20px; margin-bottom: 24px; border-left: 4px solid #667eea;\">" +
                "                <h2 style=\"margin: 0 0 16px 0; color: #1a202c; font-size: 18px; font-weight: 700;\">" + escapeHtml(ticket.getSubject()) + "</h2>" +
                "                <p style=\"margin: 0; color: #4b5563; line-height: 1.6; white-space: pre-wrap;\">" + escapeHtml(ticket.getDescription()) + "</p>" +
                "            </div>" +
                "            " +
                "            <!-- Information Grid -->" +
                "            <div style=\"background: white; border: 1px solid #e5e7eb; border-radius: 12px; overflow: hidden;\">" +
                "                " +
                "                <!-- Row: Creator -->" +
                "                <div style=\"display: table; width: 100%; border-bottom: 1px solid #e5e7eb;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">👤</span> Created By" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #1a202c; font-size: 14px;\">" +
                "                        " + escapeHtml(creator.getUsername()) + "" +
                "                    </div>" +
                "                </div>" +
                "                " +
                "                <!-- Row: Client -->" +
                "                <div style=\"display: table; width: 100%; border-bottom: 1px solid #e5e7eb;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">🏢</span> Client" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #1a202c; font-size: 14px;\">" +
                "                        " + escapeHtml(client.getName()) + "" +
                "                    </div>" +
                "                </div>" +
                "                " +
                "                <!-- Row: Category -->" +
                "                <div style=\"display: table; width: 100%; border-bottom: 1px solid #e5e7eb;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">📁</span> Category" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #1a202c; font-size: 14px;\">" +
                "                        " + escapeHtml(ticket.getCategory()) + "" +
                "                    </div>" +
                "                </div>" +
                "                " +
                "                <!-- Row: Service -->" +
                "                <div style=\"display: table; width: 100%; border-bottom: 1px solid #e5e7eb;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">⚙️</span> Service" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #1a202c; font-size: 14px;\">" +
                "                        " + escapeHtml(ticket.getService()) + "" +
                "                    </div>" +
                "                </div>" +
                "                " +
                "                <!-- Row: Severity -->" +
                "                <div style=\"display: table; width: 100%; border-bottom: 1px solid #e5e7eb;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">⚡</span> Severity" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px;\">" +
                "                        <span style=\"color: " + severityColor + "; font-weight: 700; font-size: 14px;\">" + escapeHtml(ticket.getSeverity()) + "</span>" +
                "                    </div>" +
                "                </div>" +
                "                " +
                "                <!-- Row: Account ID (if present) -->" +
                (ticket.getAccountId() != null && !ticket.getAccountId().isEmpty() ?
                "                <div style=\"display: table; width: 100%; border-bottom: 1px solid #e5e7eb;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">🔑</span> Account ID" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #1a202c; font-size: 14px; font-family: 'Courier New', monospace;\">" +
                "                        " + escapeHtml(ticket.getAccountId()) + "" +
                "                    </div>" +
                "                </div>" : "") +
                "                " +
                "                <!-- Row: Region (if present) -->" +
                (ticket.getRegion() != null && !ticket.getRegion().isEmpty() ?
                "                <div style=\"display: table; width: 100%;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">🌍</span> Region" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #1a202c; font-size: 14px; font-family: 'Courier New', monospace;\">" +
                "                        " + escapeHtml(ticket.getRegion()) + "" +
                "                    </div>" +
                "                </div>" :
                "                <div style=\"display: table; width: 100%;\">" +
                "                    <div style=\"display: table-cell; padding: 16px; background: #f9fafb; width: 35%; font-weight: 600; color: #374151; font-size: 13px;\">" +
                "                        <span style=\"margin-right: 8px;\">🌍</span> Region" +
                "                    </div>" +
                "                    <div style=\"display: table-cell; padding: 16px; color: #9ca3af; font-size: 14px;\">" +
                "                        Global" +
                "                    </div>" +
                "                </div>") +
                "            </div>" +
                "            " +
                "        </div>" +
                "        " +
                "        <!-- Footer -->" +
                "        <div style=\"background: #f9fafb; padding: 20px 24px; border-top: 1px solid #e5e7eb; text-align: center;\">" +
                "            <p style=\"margin: 0; color: #6b7280; font-size: 12px; line-height: 1.6;\">" +
                "                This ticket was automatically generated by <strong style=\"color: #667eea;\">XamOps Support System</strong><br>" +
                "                Please respond promptly to ensure customer satisfaction." +
                "            </p>" +
                "        </div>" +
                "    </div>" +
                "</body>" +
                "</html>";
    }

    /**
     * Get status color for email template
     */
    private String getStatusColor(String status) {
        if ("OPEN".equalsIgnoreCase(status)) {
            return "#10b981"; // Green
        } else if ("IN_PROGRESS".equalsIgnoreCase(status)) {
            return "#f59e0b"; // Orange
        } else if ("CLOSED".equalsIgnoreCase(status)) {
            return "#6b7280"; // Gray
        }
        return "#667eea"; // Default purple
    }

    /**
     * Get severity color for email template
     */
    private String getSeverityColor(String severity) {
        if (severity != null) {
            if (severity.equalsIgnoreCase("High") || severity.equalsIgnoreCase("Critical") || severity.equalsIgnoreCase("Urgent")) {
                return "#dc2626"; // Red
            } else if (severity.equalsIgnoreCase("Normal") || severity.equalsIgnoreCase("Medium")) {
                return "#f59e0b"; // Orange
            }
        }
        return "#3b82f6"; // Blue
    }

    /**
     * Escape HTML special characters
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Lightweight conversion for list views — does NOT access replies (avoids N+1) */
    /**
     * Batch-enriches a list of TicketDtos with human-readable accountName.
     * Uses a single DB query to look up all unique accountIds at once.
     */
    private void enrichWithAccountNames(List<TicketDto> dtos) {
        Set<String> ids = dtos.stream()
                .map(TicketDto::getAccountId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return;

        Map<String, String> nameMap = new HashMap<>();
        cloudAccountRepository.findByAnyCloudIdIn(ids).forEach(ca -> {
            if (ca.getAwsAccountId() != null) nameMap.put(ca.getAwsAccountId(), ca.getAccountName());
            if (ca.getGcpProjectId() != null) nameMap.put(ca.getGcpProjectId(), ca.getAccountName());
            if (ca.getAzureSubscriptionId() != null) nameMap.put(ca.getAzureSubscriptionId(), ca.getAccountName());
        });

        dtos.forEach(dto -> {
            if (dto.getAccountId() != null) {
                dto.setAccountName(nameMap.get(dto.getAccountId()));
            }
        });
    }

    private TicketDto convertToListDto(Ticket ticket) {
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
            dto.setCreatorEmail(ticket.getCreator().getEmail());
        }
        dto.setHasUnreadUserReply(ticket.isHasNewUserReply());
        dto.setHasUnreadAdminReply(!ticket.isHasNewUserReply() && ticket.getChatStartedAt() != null);
        // Replies intentionally omitted for list view performance
        return dto;
    }

    private TicketDto convertToDto(Ticket ticket, boolean isAdmin) {
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
            dto.setCreatorEmail(ticket.getCreator().getEmail());
        }

        if (ticket.getReplies() != null) {
            dto.setReplies(ticket.getReplies().stream()
                    .filter(reply -> isAdmin || !reply.isInternal()) // Non-admins never see internal notes
                    .map(reply -> convertReplyToDto(reply, isAdmin))
                    .sorted(Comparator.comparing(TicketReplyDto::getCreatedAt))
                    .collect(Collectors.toList()));
        }

        dto.setHasUnreadUserReply(ticket.isHasNewUserReply());
        dto.setHasUnreadAdminReply(!ticket.isHasNewUserReply() && ticket.getChatStartedAt() != null);

        return dto;
    }

    private TicketReplyDto convertReplyToDto(TicketReply reply, boolean viewerIsAdmin) {
        TicketReplyDto dto = new TicketReplyDto();
        dto.setId(reply.getId());
        dto.setTicketId(reply.getTicket().getId());
        dto.setMessage(reply.getMessage());
        dto.setCreatedAt(reply.getCreatedAt());
        dto.setInternal(reply.isInternal());
        if (reply.getAuthor() != null) {
            dto.setAuthorId(reply.getAuthor().getId());

            // Map admin status
            boolean isAdminAuthor = reply.getAuthor().getRole() != null &&
                    reply.getAuthor().getRole().contains("ADMIN");
            dto.setAdmin(isAdminAuthor);

            // Non-admin viewers see "Support Team" instead of the actual admin username
            if (!viewerIsAdmin && isAdminAuthor) {
                dto.setAuthorUsername("Support Team");
            } else {
                dto.setAuthorUsername(reply.getAuthor().getUsername());
            }
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
}