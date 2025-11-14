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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class TicketService {

    // --- START: ADDED/MODIFIED FIELDS ---
    private static final Logger logger = LoggerFactory.getLogger(TicketService.class);
    private static final String ADMIN_TECHNICAL_EMAIL = "sahil@xammer.in";
    private static final String ADMIN_BILLING_EMAIL = "akshay@xammer.in";
    // --- END: ADDED/MODIFIED FIELDS ---

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

    @Transactional
    @CacheEvict(value = "tickets", allEntries = true)
    public TicketDto createTicket(TicketDto ticketDto) {
        Client client = clientRepository.findById(ticketDto.getClientId())
                .orElseThrow(() -> new RuntimeException("Client not found"));

        // --- START: ADDED LOGIC TO GET CREATOR ---
        if (ticketDto.getCreatorId() == null) {
            throw new RuntimeException("Creator ID is required to create a ticket.");
        }
        User creator = userRepository.findById(ticketDto.getCreatorId())
                .orElseThrow(() -> new RuntimeException("Creator (user) not found"));
        // --- END: ADDED LOGIC ---

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
        
        ticket.setCreator(creator); // <-- Set the creator

        Ticket savedTicket = ticketRepository.save(ticket);

        // --- START: EMAIL NOTIFICATION LOGIC ---
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
            
            emailService.sendHtmlEmail(adminEmail, subject, text);
        } catch (Exception e) {
            // Log the email error but don't fail the transaction
            logger.error("Failed to send new ticket notification email for ticket {}", savedTicket.getId(), e);
        }
        // --- END: EMAIL NOTIFICATION LOGIC ---

        return convertToDto(savedTicket);
    }

    @Transactional(readOnly = true)
    @Cacheable("tickets")
    public List<TicketDto> getAllTickets() {
        return ticketRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

@Transactional(readOnly = true)
    @Cacheable(value = "tickets", key = "#id") // <-- FIX: Changed to match the parameter name
    public TicketDto getTicketById(Long id) {
        // --- START: MODIFIED LINE ---
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(id) // <-- This is correct
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        // --- END: MODIFIED LINE ---
        return convertToDto(ticket);
    }

    @Transactional
    @CacheEvict(value = "tickets", allEntries = true)
    public TicketDto addReplyToTicket(Long ticketId, TicketReplyDto replyDto) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        if ("CLOSED".equalsIgnoreCase(ticket.getStatus())) {
            throw new IllegalStateException("Cannot add a reply to a closed ticket.");
        }

        User author = userRepository.findById(replyDto.getAuthorId())
                .orElseThrow(() -> new RuntimeException("Author (user) not found"));

        TicketReply reply = new TicketReply();
        reply.setTicket(ticket);
        reply.setAuthor(author);
        reply.setMessage(replyDto.getMessage());
        reply.setCreatedAt(java.time.LocalDateTime.now()); // ✅ ADD THIS LINE
        ticket.getReplies().add(reply);
        ticket.setStatus("IN_PROGRESS"); // Update status on reply

        Ticket updatedTicket = ticketRepository.save(ticket);

        // --- START: EMAIL NOTIFICATION LOGIC ---
        try {
            User ticketCreator = ticket.getCreator(); // Get the original creator
            
            String subject = String.format("New Reply on Ticket [ID: %d]: %s", ticket.getId(), ticket.getSubject());
            String text = String.format(
                "%s has replied to ticket %d:\n\n---\n%s\n---",
                author.getUsername(),
                ticket.getId(),
                reply.getMessage()
            );

            // Check if the replier is an admin
            boolean isAdminReply = author.getRole() != null && 
                                        (author.getRole().contains("ADMIN"));            
            if (isAdminReply) {
                // ADMIN replied. Send email to the ticket creator.
                if (ticketCreator != null && ticketCreator.getEmail() != null) {
                    emailService.sendHtmlEmail(ticketCreator.getEmail(), subject, text);
                } else {
                    logger.warn("Cannot notify ticket creator for ticket {}: Creator or email is null.", ticket.getId());
                }
            } else {
                // USER replied. Send email to the appropriate admin.
                String adminEmail = "Account and Billing".equalsIgnoreCase(ticket.getCategory()) ? 
                                    ADMIN_BILLING_EMAIL : ADMIN_TECHNICAL_EMAIL;
                emailService.sendHtmlEmail(adminEmail, subject, text);
            }
            
        } catch (Exception e) {
            // Log the email error
            logger.error("Failed to send reply notification email for ticket {}", ticket.getId(), e);
        }
        // --- END: EMAIL NOTIFICATION LOGIC ---
        
        return convertToDto(updatedTicket);
    }

    @Transactional
    @CacheEvict(value = "tickets", allEntries = true)
    public TicketDto closeTicket(Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        ticket.setStatus("CLOSED");
        Ticket updatedTicket = ticketRepository.save(ticket);
        return convertToDto(updatedTicket);
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

        // --- START: ADDED CREATOR ID ---
        if (ticket.getCreator() != null) {
            dto.setCreatorId(ticket.getCreator().getId());
        }
        // --- END: ADDED CREATOR ID ---

        if (ticket.getReplies() != null) {
            dto.setReplies(ticket.getReplies().stream()
                    .map(this::convertReplyToDto)
                    .sorted(Comparator.comparing(TicketReplyDto::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))) // Ensure chronological order
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
        return dto;
    }


    // In TicketService.java
@Transactional(readOnly = true)
@Cacheable(value = "tickets", key = "#category")
public List<TicketDto> getTicketsByCategory(String category) {
    return ticketRepository.findAllByCategoryWithRepliesAndAuthors(category).stream()
            .map(this::convertToDto)
            .collect(Collectors.toList());
}
    
}