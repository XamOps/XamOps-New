package com.xammer.cloud.controller;

import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.SupportChatService;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai") // Keeping path same for now to match frontend
public class ChatbotController {

    private final SupportChatService supportChatService;

    public ChatbotController(SupportChatService supportChatService) {
        this.supportChatService = supportChatService;
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    
    public ChatResponse chat(@RequestBody ChatRequest request) {
        // 1. Send message to Human Support (Database)
        supportChatService.sendUserMessage(request.getMessage());

        // 2. Return a standard acknowledgement
        // In a real real-time app, this might be empty, but we return a system note.
        return new ChatResponse("Request received. An agent will be with you shortly.");
    }
}