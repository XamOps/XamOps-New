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
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
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

    @Transactional
    @CacheEvict(value = "tickets", allEntries = true)
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
    @Cacheable(value = "tickets", key = "#id")
    public TicketDto getTicketById(Long id) {
        Ticket ticket = ticketRepository.findByIdWithRepliesAndAuthors(id)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));
        return convertToDto(ticket);
    }

    @Transactional
    @CacheEvict(value = "tickets", allEntries = true)
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

            boolean isAdminReply = author.getRole() != null && (author.getRole().contains("ADMIN"));
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

    @Transactional(readOnly = true)
    @Cacheable(value = "tickets", key = "#category")
    public List<TicketDto> getTicketsByCategory(String category) {
        return ticketRepository.findAllByCategoryWithRepliesAndAuthors(category).stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
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