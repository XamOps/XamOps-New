package com.xammer.cloud.controller;

import com.xammer.cloud.dto.TicketDto;
import com.xammer.cloud.dto.TicketReplyDto;
import com.xammer.cloud.service.TicketService;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/xamops")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @PostMapping("/tickets")
    public ResponseEntity<TicketDto> createTicket(@RequestBody TicketDto ticketDto) {
        return ResponseEntity.ok(ticketService.createTicket(ticketDto));
    }

    @GetMapping("/tickets")
    public ResponseEntity<List<TicketDto>> getAllTickets() {
        return ResponseEntity.ok(ticketService.getAllTickets());
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<TicketDto> getTicketById(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.getTicketById(id));
    }

    @PostMapping("/tickets/{id}/replies")
    public ResponseEntity<TicketDto> addTicketReply(@PathVariable Long id, @RequestBody TicketReplyDto replyDto) {
        return ResponseEntity.ok(ticketService.addReplyToTicket(id, replyDto));
    }

    @PostMapping("/tickets/{id}/close")
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    public ResponseEntity<TicketDto> closeTicket(@PathVariable Long id) {
        return ResponseEntity.ok(ticketService.closeTicket(id));
    }
    // In TicketController.java
@GetMapping("/tickets/category/{category}")
public ResponseEntity<List<TicketDto>> getTicketsByCategory(@PathVariable String category) {
    return ResponseEntity.ok(ticketService.getTicketsByCategory(category));
}
}