package com.xammer.billops.controller;

import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.repository.SupportMessageRepository;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billops/support")
@CrossOrigin(origins = "*") // Allow XamOps frontend to potentially verify status if needed
public class SupportAdminController {

    private final SupportMessageRepository messageRepository;

    public SupportAdminController(SupportMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    // 1. Get Inbox (List of all conversations)
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    @GetMapping("/inbox")
    public List<Map<String, Object>> getInbox() {
        List<Object[]> results = messageRepository.findDistinctConversations();
        List<Map<String, Object>> inbox = new ArrayList<>();

        for (Object[] row : results) {
            String accountId = (String) row[0];
            Map<String, Object> conversation = new HashMap<>();
            conversation.put("accountId", accountId);
            conversation.put("accountName", (String) row[1]);
            conversation.put("provider", (String) row[2]);
            conversation.put("senderUsername", (String) row[3]);

            // Add Extras for WhatsApp-like UI
            long unreadCount = messageRepository.countByAccountIdAndIsUserMessageTrueAndStatus(accountId, "UNREAD");
            conversation.put("unreadCount", unreadCount);

            SupportMessage lastMsg = messageRepository.findFirstByAccountIdOrderByTimestampDesc(accountId);
            if (lastMsg != null) {
                conversation.put("lastMessage", lastMsg.getContent());
                conversation.put("lastMessageTime", lastMsg.getTimestamp().toString());
            }

            inbox.add(conversation);
        }
        return inbox;
    }

    // Get Chat Sessions (for notification system)
    @PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN')")
    @GetMapping("/chat-sessions")
    public List<Map<String, String>> getChatSessions() {
        List<Object[]> results = messageRepository.findDistinctConversations();
        List<Map<String, String>> sessions = new ArrayList<>();

        for (Object[] row : results) {
            Map<String, String> session = new HashMap<>();
            session.put("accountId", (String) row[0]);
            session.put("accountName", (String) row[1]);
            session.put("provider", (String) row[2]);
            sessions.add(session);
        }
        return sessions;
    }

    // 2. Get Chat History for a specific Account
    @GetMapping("/chat/{accountId}")
    public List<SupportMessage> getChatHistory(@PathVariable String accountId) {
        // Mark all unread messages from user as READ
        List<SupportMessage> unreadMessages = messageRepository.findByAccountIdAndIsUserMessageTrueAndStatus(accountId,
                "UNREAD");
        if (!unreadMessages.isEmpty()) {
            for (SupportMessage msg : unreadMessages) {
                msg.setStatus("READ");
            }
            messageRepository.saveAll(unreadMessages);
        }

        return messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
    }

    // 3. Send Reply (Admin -> User)
    @PostMapping("/reply")
    public SupportMessage sendReply(@RequestBody Map<String, String> payload) {
        String accountId = payload.get("accountId");
        String messageContent = payload.get("message");

        // We need to fetch the basic details from the last message of this account
        // to ensure consistency (or you could pass them in the payload)
        List<SupportMessage> history = messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
        if (history.isEmpty()) {
            throw new RuntimeException("Conversation not found");
        }
        SupportMessage lastMsg = history.get(0); // Get metadata from first message

        SupportMessage reply = new SupportMessage();
        reply.setAccountId(accountId);
        reply.setAccountName(lastMsg.getAccountName());
        reply.setCloudProvider(lastMsg.getCloudProvider());
        reply.setUserId(lastMsg.getUserId()); // Replying to this user
        reply.setContent(messageContent);
        reply.setUserMessage(false); // FALSE because this is ADMIN speaking
        reply.setTimestamp(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        reply.setStatus("DELIVERED");
        reply.setSenderUsername("BillOps Admin");

        return messageRepository.save(reply);
    }
}