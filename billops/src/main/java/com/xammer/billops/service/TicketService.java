package com.xammer.billops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.billops.domain.Client;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.dto.TicketReplyDto;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.TicketReplyRepository;
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
            String adminEmail = "Account and Billing".equalsIgnoreCase(savedTicket.getCategory()) ? 
                                ADMIN_BILLING_EMAIL : ADMIN_TECHNICAL_EMAIL;
            
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
                ticket.getDescription()
            );
            
            emailService.sendSimpleMessage(adminEmail, subject, text);
        } catch (Exception e) {
            logger.error("Failed to send new ticket notification email for ticket {}", savedTicket.getId(), e);
        }
        
        // 5. Evict Cache (Includes the specific category)
        evictTicketCaches(savedTicket.getCategory(), client.getId());

        return convertToDto(savedTicket);
    }

    /**
     * Unified method to get all tickets.
     */
    @Transactional(readOnly = true)
    public List<TicketDto> getAllTickets(boolean forceRefresh, Long clientId, boolean isSuperAdmin) {
        String cacheKey = isSuperAdmin ? TICKETS_ALL_CACHE_KEY : "tickets:client:" + clientId + ":all";

        if (!forceRefresh) {
            Optional<List<TicketDto>> cached = redisCache.get(cacheKey, new TypeReference<List<TicketDto>>() {});
            if (cached.isPresent()) {
                logger.debug("Returning CACHED all tickets for cacheKey: {}", cacheKey);
                return cached.get();
            }
        }

        logger.debug("Fetching FRESH all tickets for cacheKey: {}", cacheKey);
        List<Ticket> tickets = isSuperAdmin ?
                ticketRepository.findAllWithRepliesAndAuthors() :
                ticketRepository.findAllByClientIdWithRepliesAndAuthors(clientId);

        List<TicketDto> freshData = tickets.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        redisCache.put(cacheKey, freshData, CACHE_TTL_MINUTES);
        return freshData;
    }

    @Transactional(readOnly = true)
    public TicketDto getTicketById(Long ticketId, Long clientId, boolean isSuperAdmin) {
        Ticket ticket = isSuperAdmin ?
                ticketRepository.findByIdWithRepliesAndAuthors(ticketId) :
                ticketRepository.findByIdAndClientIdWithRepliesAndAuthors(ticketId, clientId);

        if (ticket == null) {
            throw new RuntimeException("Ticket not found");
        }
        return convertToDto(ticket);
    }
    
    /**
     * Unified method to get tickets by category.
     */
    @Transactional(readOnly = true)
    public List<TicketDto> getTicketsByCategory(String category, boolean forceRefresh, Long clientId, boolean isSuperAdmin) {
        String cacheKey = isSuperAdmin ? TICKETS_CATEGORY_PREFIX + category :
                "tickets:client:" + clientId + ":category:" + category;
        
        if (!forceRefresh) {
            Optional<List<TicketDto>> cached = redisCache.get(cacheKey, new TypeReference<List<TicketDto>>() {});
            if (cached.isPresent()) {
                logger.debug("Returning CACHED tickets for category: {} and cacheKey: {}", category, cacheKey);
                return cached.get();
            }
        }

        logger.debug("Fetching FRESH tickets for category: {} and cacheKey: {}", category, cacheKey);
        List<Ticket> tickets = isSuperAdmin ?
                ticketRepository.findAllByCategoryWithRepliesAndAuthors(category) :
                ticketRepository.findAllByCategoryAndClientIdWithRepliesAndAuthors(category, clientId);

        List<TicketDto> freshData = tickets.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        redisCache.put(cacheKey, freshData, CACHE_TTL_MINUTES);
        return freshData;
    }

    @Transactional
    public TicketDto addReplyToTicket(Long ticketId, TicketReplyDto replyDto, MultipartFile file, Long clientId, boolean isSuperAdmin) {
        Ticket ticket = isSuperAdmin ?
                ticketRepository.findById(ticketId).orElseThrow(() -> new RuntimeException("Ticket not found")) :
                Optional.ofNullable(ticketRepository.findByIdAndClientIdWithRepliesAndAuthors(ticketId, clientId))
                        .orElseThrow(() -> new RuntimeException("Ticket not found or access denied"));

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
        
        // FIX: Ensure non-null message for DB
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

        ticket.getReplies().add(reply);
        ticket.setStatus("IN_PROGRESS");

        Ticket updatedTicket = ticketRepository.save(ticket);

        try {
            User ticketCreator = ticket.getCreator();
            String subject = String.format("New Reply on Ticket [ID: %d]: %s", ticket.getId(), ticket.getSubject());
            
            // FIX: Handle empty message gracefully in email
            String displayMessage = (reply.getMessage() == null || reply.getMessage().isBlank()) 
                                    ? "(Attachment only)" 
                                    : reply.getMessage();

            String text = String.format(
                "%s has replied to ticket %d:\n\n---\n%s\n---",
                author.getUsername(),
                ticket.getId(),
                displayMessage
            );

            if (reply.getAttachmentName() != null) {
                text += "\n\n[Attachment: " + reply.getAttachmentName() + "]";
            }

            boolean isAdminReply = author.getRole() != null && (author.getRole().contains("ADMIN"));            
            if (isAdminReply) {
                if (ticketCreator != null && ticketCreator.getEmail() != null) {
                    emailService.sendSimpleMessage(ticketCreator.getEmail(), subject, text);
                }
            } else {
                String adminEmail = "Account and Billing".equalsIgnoreCase(ticket.getCategory()) ? 
                                    ADMIN_BILLING_EMAIL : ADMIN_TECHNICAL_EMAIL;
                emailService.sendSimpleMessage(adminEmail, subject, text);
            }
        } catch (Exception e) {
            logger.error("Failed to send reply notification email for ticket {}", ticket.getId(), e);
        }
        
        // Evict Cache for this specific category + Admin list
        evictTicketCaches(updatedTicket.getCategory(), updatedTicket.getClient() != null ? updatedTicket.getClient().getId() : null);
        
        return convertToDto(updatedTicket);
    }

    @Transactional
    public TicketDto closeTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        ticket.setStatus("CLOSED");
        Ticket updatedTicket = ticketRepository.save(ticket);
        
        // Evict Cache for this specific category + Admin list
        evictTicketCaches(updatedTicket.getCategory(), updatedTicket.getClient() != null ? updatedTicket.getClient().getId() : null);
        
        return convertToDto(updatedTicket);
    }

    // --- Helper Methods ---
    
    /**
     * Evicts the global "All Tickets" list (for Admins) AND the specific category cache (for Users).
     * @param category The category of the ticket that was modified. Can be null if unknown.
     * @param clientId The client ID whose cache should be evicted.
     */
    public void evictTicketCaches(String category, Long clientId) {
        // 1. Evict Admin List (Always)
        redisCache.evict(TICKETS_ALL_CACHE_KEY);
        if (category != null && !category.isEmpty()) {
            redisCache.evict(TICKETS_CATEGORY_PREFIX + category);
        }

        // 2. Evict Client Specific Caches
        if (clientId != null) {
            redisCache.evict("tickets:client:" + clientId + ":all");
            if (category != null && !category.isEmpty()) {
                redisCache.evict("tickets:client:" + clientId + ":category:" + category);
            }
        }
        logger.info("Evicted ticket caches for category: {} and clientId: {}", category, clientId);
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

        if (ticket.getClient() != null) {
            dto.setClientId(ticket.getClient().getId());
        }
        if (ticket.getCreator() != null) {
            dto.setCreatorId(ticket.getCreator().getId());
        }

        if (ticket.getReplies() != null) {
            dto.setReplies(ticket.getReplies().stream()
                    .map(this::convertReplyToDto)
                    .sorted(Comparator.comparing(TicketReplyDto::getCreatedAt))
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
        if (reply.getAuthor() != null) {
            dto.setAuthorId(reply.getAuthor().getId());
            dto.setAuthorUsername(reply.getAuthor().getUsername());
        }

        if (reply.getAttachmentKey() != null) {
            dto.setAttachmentName(reply.getAttachmentName());
            dto.setAttachmentType(reply.getAttachmentType());
            String presignedUrl = fileStorageService.generatePresignedUrl(reply.getAttachmentKey());
            dto.setAttachmentUrl(presignedUrl);
        }

        return dto;
    }
}