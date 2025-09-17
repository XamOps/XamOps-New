package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.Ticket;
import com.xammer.billops.dto.TicketDto;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TicketService {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private EmailService emailService;

    @Transactional
    public TicketDto createTicket(TicketDto ticketDto) {
        Client client = clientRepository.findById(ticketDto.getClientId())
                .orElseThrow(() -> new RuntimeException("Client not found"));

        Ticket ticket = new Ticket();
        ticket.setSubject(ticketDto.getSubject());
        ticket.setDescription(ticketDto.getDescription());
        ticket.setStatus("OPEN");
        ticket.setClient(client);

        // Set new fields
        ticket.setCategory(ticketDto.getCategory());
        ticket.setService(ticketDto.getService());
        ticket.setSeverity(ticketDto.getSeverity());
        ticket.setAccountId(ticketDto.getAccountId());
        ticket.setRegion(ticketDto.getRegion());

        Ticket savedTicket = ticketRepository.save(ticket);

        // Send email notification
        String emailSubject = "New Ticket Created: " + savedTicket.getSubject();
        String emailText = "A new ticket has been created with the following details:\n\n" +
                "Subject: " + savedTicket.getSubject() + "\n" +
                "Description: " + savedTicket.getDescription() + "\n" +
                "Category: " + savedTicket.getCategory() + "\n" +
                "Service: " + savedTicket.getService() + "\n" +
                "Severity: " + savedTicket.getSeverity() + "\n" +
                "Account ID: " + savedTicket.getAccountId() + "\n" +
                "Region: " + savedTicket.getRegion();
        emailService.sendSimpleMessage("aditya@xammer.in", emailSubject, emailText);


        return convertToDto(savedTicket);
    }

    @Transactional(readOnly = true)
    public List<TicketDto> getAllTickets() {
        return ticketRepository.findAll().stream()
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

        // Map new fields
        dto.setCategory(ticket.getCategory());
        dto.setService(ticket.getService());
        dto.setSeverity(ticket.getSeverity());
        dto.setAccountId(ticket.getAccountId());
        dto.setRegion(ticket.getRegion());

        if (ticket.getClient() != null) {
            dto.setClientId(ticket.getClient().getId());
        }
        return dto;
    }
}