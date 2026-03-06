package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.SupportChatService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;

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
        supportChatService.sendUserMessage(request.getMessage(), request.getAccountId());

        // 2. Return a standard acknowledgement
        // In a real real-time app, this might be empty, but we return a system note.
        return new ChatResponse("Request received. An agent will be with you shortly.");
    }

    @GetMapping("/chat/history")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public List<SupportMessage> getChatHistory(@RequestParam(required = false) String accountId) {
        return supportChatService.getMessagesForCurrentUser(accountId);
    }

    /**
     * Get unread count of admin messages for the current user.
     * This allows the frontend to efficiently check for new messages without fetching full history.
     */
    @GetMapping("/chat/unread")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public Map<String, Object> getUnreadCount(@RequestParam(required = false) String accountId) {
        long unreadCount = supportChatService.getUnreadAdminMessageCount(accountId);
        Map<String, Object> response = new HashMap<>();
        response.put("unreadCount", unreadCount);
        return response;
    }

    /**
     * Mark all admin messages as read for the current user's conversation.
     * Called when the user opens the chat widget.
     */
    @PostMapping("/chat/mark-read")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public Map<String, Object> markMessagesAsRead(@RequestParam(required = false) String accountId) {
        supportChatService.markAdminMessagesAsRead(accountId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        return response;
    }

    /** Edit own message within 15-minute window */
    @PatchMapping("/chat/messages/{id}")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public ResponseEntity<SupportMessage> editMessage(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload) {
        return supportChatService.editMessage(id, payload.getOrDefault("content", ""))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(403).build());
    }

    /** Soft-delete own message within 60-minute window */
    @DeleteMapping("/chat/messages/{id}")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public ResponseEntity<SupportMessage> deleteMessage(@PathVariable Long id) {
        return supportChatService.deleteMessage(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(403).build());
    }
}