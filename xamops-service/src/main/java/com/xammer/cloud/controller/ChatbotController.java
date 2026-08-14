package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.dto.ChatRequest;
import com.xammer.cloud.dto.ChatResponse;
import com.xammer.cloud.service.SupportChatService;
import com.xammer.cloud.service.faq.FeatureFaqEntry;
import com.xammer.cloud.service.faq.FeatureFaqMatcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai") // Keeping path same for now to match frontend
public class ChatbotController {

    private static final String FAQ_FOLLOW_UP =
            " Did this answer your question? If not, just send another message and I'll loop in our support team.";

    private final SupportChatService supportChatService;
    private final FeatureFaqMatcher featureFaqMatcher;

    public ChatbotController(SupportChatService supportChatService, FeatureFaqMatcher featureFaqMatcher) {
        this.supportChatService = supportChatService;
        this.featureFaqMatcher = featureFaqMatcher;
    }

    @PostMapping("/chat")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        Optional<FeatureFaqEntry> match = featureFaqMatcher.match(request.getMessage());

        if (match.isPresent()) {
            // Question matched a known feature/tab — auto-answer instead of queuing for a human.
            supportChatService.recordAutoResolvedQuestion(request.getMessage(), request.getAccountId());
            String answer = match.get().description() + FAQ_FOLLOW_UP;
            supportChatService.sendBotAnswer(answer);
            return new ChatResponse(answer);
        }

        // No feature/tab match — forward to human support (still flags the question as
        // UNREAD for the BillOps admin inbox, unchanged from before this feature), and
        // persist a visible acknowledgement so the widget never looks silently unresponsive.
        supportChatService.sendUserMessage(request.getMessage(), request.getAccountId());
        String ack = "Request received. An agent will be with you shortly.";
        supportChatService.sendBotAnswer(ack);
        return new ChatResponse(ack);
    }

    /**
     * Recent chat history for the current user, oldest first.
     *
     * {@code limit} bounds the result set (default 200, hard cap 500). The unbounded
     * version of this endpoint was being polled every 5s by two separate frontend hooks
     * and returned the user's entire message history each time.
     */
    @GetMapping("/chat/history")
    @PreAuthorize("hasAuthority('ROLE_XAMOPS')")
    public List<SupportMessage> getChatHistory(@RequestParam(required = false) String accountId,
                                              @RequestParam(defaultValue = "200") int limit) {
        return supportChatService.getMessagesForCurrentUser(accountId, limit);
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

        // Include just enough of the newest unread reply for the notification toast, so the
        // global poller never has to download the full history to detect a new message.
        supportChatService.getLatestUnreadAdminMessage().ifPresent(latest -> {
            response.put("latestMessage", latest.getContent());
            response.put("latestTimestamp", latest.getTimestamp());
            response.put("latestId", latest.getId());
        });
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
