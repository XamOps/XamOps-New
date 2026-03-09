package com.xammer.cloud.controller;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.SupportMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public BillOpsSupportController(SupportMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
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
}
