package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.SupportMessageRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.SupportChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BillOpsSupportController
 *
 * Admin-facing support inbox endpoints. Returns a conversation-per-user view
 * of all customer support messages for the BillOps Admin dashboard.
 */
@RestController
@RequestMapping("/api/billops/support")
@PreAuthorize("hasAuthority('ROLE_BILLOPS_ADMIN') or hasAuthority('ROLE_SUPER_ADMIN')")
public class BillOpsSupportController {

    private static final Logger log = LoggerFactory.getLogger(BillOpsSupportController.class);

    private final SupportMessageRepository messageRepository;
    private final SupportChatService supportChatService;
    private final UserRepository userRepository;

    public BillOpsSupportController(SupportMessageRepository messageRepository,
                                     SupportChatService supportChatService,
                                     UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.supportChatService = supportChatService;
        this.userRepository = userRepository;
    }

    /**
     * GET /api/billops/support/inbox
     *
     * Returns one SupportConversation entry per user (sender), ordered by the
     * most recent message time. Includes unread count (user messages with
     * status = "UNREAD") so the admin notification listener can detect new
     * messages without polling the full chat history.
     */
    @GetMapping("/inbox")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getInbox() {
        log.debug("Fetching admin support inbox");

        List<SupportMessage> allUserMessages =
                messageRepository.findAllUserMessagesWithSenderOrderByTimestampDesc();

        // Group by sender (one conversation thread per user), preserving desc order
        Map<Long, List<SupportMessage>> bySenderId = new LinkedHashMap<>();
        for (SupportMessage msg : allUserMessages) {
            if (msg.getSender() == null) continue;
            bySenderId.computeIfAbsent(msg.getSender().getId(), k -> new ArrayList<>()).add(msg);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (List<SupportMessage> msgs : bySenderId.values()) {
            SupportMessage latest = msgs.get(0); // already sorted desc
            User sender = latest.getSender();

            long unreadCount = msgs.stream()
                    .filter(m -> "UNREAD".equals(m.getStatus()))
                    .count();

            Map<String, Object> conv = new LinkedHashMap<>();
            conv.put("userId", sender.getId());
            conv.put("username", sender.getUsername());
            conv.put("email", sender.getEmail());
            conv.put("senderUsername", sender.getUsername());
            conv.put("accountId", latest.getAccountId());
            conv.put("accountName", latest.getAccountName());
            conv.put("provider", latest.getCloudProvider());
            conv.put("lastMessage", latest.isDeleted() ? "(deleted)" : latest.getContent());
            conv.put("lastMessageTime", latest.getTimestamp().toString());
            conv.put("unreadCount", (int) unreadCount);
            result.add(conv);
        }

        log.debug("Admin inbox: {} conversations", result.size());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/billops/support/chat/user/{userId}
     *
     * Returns full chat history for a specific user (all messages, both directions),
     * ordered by timestamp ascending.
     */
    @GetMapping("/chat/user/{userId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<SupportMessage>> getChatForUser(@PathVariable Long userId) {
        return userRepository.findById(userId)
                .map(user -> ResponseEntity.ok(messageRepository.findBySenderOrderByTimestampAsc(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PATCH /api/billops/support/messages/{id}
     *
     * Admin edit: updates message content (no time restriction).
     */
    @PatchMapping("/messages/{id}")
    @Transactional
    public ResponseEntity<SupportMessage> editMessage(@PathVariable Long id,
                                                       @RequestBody Map<String, String> payload) {
        return messageRepository.findById(id).map(msg -> {
            String content = payload.getOrDefault("content", "");
            if (content.isBlank()) return ResponseEntity.badRequest().<SupportMessage>build();
            msg.setContent(content);
            msg.setEdited(true);
            msg.setEditedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
            return ResponseEntity.ok(messageRepository.save(msg));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/billops/support/messages/{id}
     *
     * Admin soft-delete: marks any message as deleted (no time restriction).
     */
    @DeleteMapping("/messages/{id}")
    public ResponseEntity<SupportMessage> deleteMessage(@PathVariable Long id) {
        return supportChatService.deleteAdminMessage(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
