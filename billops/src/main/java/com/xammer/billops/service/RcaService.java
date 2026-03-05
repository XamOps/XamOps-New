package com.xammer.billops.service;

import com.xammer.billops.domain.TicketRca;
import com.xammer.billops.dto.RcaRequestDto;
import com.xammer.billops.repository.TicketRcaRepository; // You'll need to create this simple interface
import com.xammer.billops.repository.TicketRepository;
import com.xammer.billops.repository.UserRepository;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class RcaService {

    private final TicketRepository ticketRepository;
    private final TicketRcaRepository ticketRcaRepository;
    private final UserRepository userRepository;
    private final PdfGenerationService pdfGenerationService;
    private final FileStorageService fileStorageService;
    private final TicketService ticketService; // Reuse existing cache eviction/logic

    public RcaService(TicketRepository ticketRepository,
                      TicketRcaRepository ticketRcaRepository,
                      UserRepository userRepository,
                      PdfGenerationService pdfGenerationService,
                      FileStorageService fileStorageService,
                      TicketService ticketService) {
        this.ticketRepository = ticketRepository;
        this.ticketRcaRepository = ticketRcaRepository;
        this.userRepository = userRepository;
        this.pdfGenerationService = pdfGenerationService;
        this.fileStorageService = fileStorageService;
        this.ticketService = ticketService;
    }

    @Transactional
    public void generateAndSubmitRca(Long ticketId, RcaRequestDto rcaData, Long adminUserId) {
        // 1. Fetch Ticket & User
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found: " + ticketId));
        
        User adminUser = userRepository.findById(adminUserId)
                .orElseThrow(() -> new RuntimeException("Admin user not found"));

        // 2. Generate PDF
        byte[] pdfBytes = pdfGenerationService.generateRcaPdf(rcaData, ticket.getSubject());
        
        // 3. Upload to S3
        String timestamp = String.valueOf(System.currentTimeMillis());
        String fileName = "rca_ticket_" + ticketId + "_" + timestamp + ".pdf";
        String s3Key = fileStorageService.uploadFileBytes(fileName, pdfBytes, "application/pdf");

        // 4. Save RCA Entity (COMMENTED OUT FOR TESTING)
        // TicketRca rca = new TicketRca(ticket, adminUser, s3Key, fileName);
        // ticketRcaRepository.save(rca);

        // 5. Close Ticket
        ticket.setStatus("CLOSED");
        ticket.setClosedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        // 6. Add System Message to Chat
        TicketReply systemReply = new TicketReply();
        systemReply.setTicket(ticket);
        systemReply.setAuthor(adminUser); 
        systemReply.setMessage("Ticket Closed. A Root Cause Analysis (RCA) report has been generated.");
        systemReply.setCreatedAt(LocalDateTime.now());
        
        // The chat system uses TicketReply table (which exists), so the download link WILL WORK 
        // even without the TicketRca table!
        systemReply.setAttachmentKey(s3Key);
        systemReply.setAttachmentName(fileName);
        systemReply.setAttachmentType("application/pdf");
        
        ticket.getReplies().add(systemReply);
        ticketRepository.save(ticket);
        
        // 7. Evict Caches
        ticketService.evictTicketCaches(ticket.getCategory());
    }
}