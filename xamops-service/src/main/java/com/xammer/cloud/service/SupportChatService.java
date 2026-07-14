package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.SupportMessageRepository;
import com.xammer.cloud.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class SupportChatService {

    private final SupportMessageRepository messageRepository;
    private final UserRepository userRepository;

    public SupportChatService(SupportMessageRepository messageRepository, UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
    }

    /**
     * Get the account ID for the current user based on selected account or default.
     * Returns null if the user record cannot be found in the current tenant DB
     * (e.g. tenant context not yet resolved). Callers must null-check the return value.
     */
    private String getAccountIdForCurrentUser(String selectedAccountId) {
        String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return null;
        }

        String accountId = "N/A";
        if (user.getClient() != null && user.getClient().getCloudAccounts() != null && !user.getClient().getCloudAccounts().isEmpty()) {
            if (selectedAccountId != null && !selectedAccountId.isEmpty()) {
                // Verify the selected account belongs to this user's client
                boolean validAccount = user.getClient().getCloudAccounts().stream()
                        .anyMatch(a -> a.getProviderAccountId() != null && a.getProviderAccountId().equals(selectedAccountId));
                if (validAccount) {
                    accountId = selectedAccountId;
                } else {
                    accountId = user.getClient().getCloudAccounts().get(0).getProviderAccountId();
                }
            } else {
                accountId = user.getClient().getCloudAccounts().get(0).getProviderAccountId();
            }
        }
        return accountId;
    }

    @Transactional
    public SupportMessage sendUserMessage(String messageContent, String selectedAccountId) {
        return saveUserMessage(messageContent, selectedAccountId, "UNREAD");
    }

    /**
     * Records a user's question that the chatbot auto-answered from the feature FAQ.
     * Saved with status "READ" (not the default "UNREAD") so it does not count toward
     * the BillOps admin inbox's unread/needs-attention badge — the question was already
     * resolved automatically, not escalated to a human.
     */
    @Transactional
    public SupportMessage recordAutoResolvedQuestion(String messageContent, String selectedAccountId) {
        return saveUserMessage(messageContent, selectedAccountId, "READ");
    }

    private SupportMessage saveUserMessage(String messageContent, String selectedAccountId, String status) {
        // 1. Get Logged-in User
        String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 2. Identify Context (Account ID, Name, Cloud)
        String accountId = "N/A";
        String accountName = "Unknown";
        String provider = "Unknown";

        if (user.getClient() != null && user.getClient().getCloudAccounts() != null && !user.getClient().getCloudAccounts().isEmpty()) {
            // Find the account matching the selected accountId, or fall back to first account
            CloudAccount account = user.getClient().getCloudAccounts().stream()
                    .filter(a -> a.getProviderAccountId() != null && a.getProviderAccountId().equals(selectedAccountId))
                    .findFirst()
                    .orElse(user.getClient().getCloudAccounts().get(0));

            accountId = account.getProviderAccountId();
            accountName = account.getAccountName();
            provider = account.getProvider();
        }

        // 3. Create and Save Message
        SupportMessage supportMessage = new SupportMessage(user, messageContent, true);
        supportMessage.setAccountId(accountId);
        supportMessage.setAccountName(accountName);
        supportMessage.setCloudProvider(provider);
        supportMessage.setSenderUsername(user.getUsername());
        supportMessage.setStatus(status);

        return messageRepository.save(supportMessage);
    }

    /**
     * Persists an automated bot reply (chatbot FAQ auto-answer) for the current user.
     * Uses status "DELIVERED" so it participates in the existing unread-badge and
     * read-receipt flow the same way a human admin reply does.
     */
    @Transactional
    public SupportMessage sendBotAnswer(String content) {
        User user = getCurrentUser();
        if (user == null) {
            throw new RuntimeException("User not found");
        }

        SupportMessage botMessage = new SupportMessage(user, content, false);
        botMessage.setStatus("DELIVERED");
        botMessage.setSenderUsername("XamOps Assistant");

        return messageRepository.save(botMessage);
    }

    private User getCurrentUser() {
        String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        return userRepository.findByUsername(username).orElse(null);
    }

    public List<SupportMessage> getMessagesForCurrentUser(String selectedAccountId) {
        User user = getCurrentUser();
        if (user == null) return Collections.emptyList();
        // Return ALL messages for this user regardless of account (user-based conversations)
        return messageRepository.findBySenderOrderByTimestampAsc(user);
    }

    /**
     * Get count of unread admin messages (messages from admin to user that are not yet read).
     * Admin messages have isUserMessage = false and status = "DELIVERED".
     * Scoped by sender (user) only — covers all accounts.
     */
    public long getUnreadAdminMessageCount(String selectedAccountId) {
        User user = getCurrentUser();
        if (user == null) return 0L;
        return messageRepository.countBySenderAndIsUserMessageFalseAndStatus(user, "DELIVERED");
    }

    /**
     * Edit a user's own message within 15 minutes of sending.
     */
    @Transactional
    public Optional<SupportMessage> editMessage(Long id, String newContent) {
        Optional<SupportMessage> opt = messageRepository.findById(id);
        if (opt.isEmpty()) return Optional.empty();

        SupportMessage msg = opt.get();
        User user = getCurrentUser();
        if (user == null || msg.getSender() == null || !user.getId().equals(msg.getSender().getId()))
            return Optional.empty();
        if (!msg.isUserMessage()) return Optional.empty();

        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(15);
        if (msg.getTimestamp().isBefore(cutoff)) return Optional.empty();

        msg.setContent(newContent);
        msg.setEdited(true);
        msg.setEditedAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")));
        return Optional.of(messageRepository.save(msg));
    }

    /**
     * Admin soft-delete: deletes any message by id (no time restriction).
     */
    @Transactional
    public Optional<SupportMessage> deleteAdminMessage(Long id) {
        Optional<SupportMessage> opt = messageRepository.findById(id);
        if (opt.isEmpty()) return Optional.empty();
        SupportMessage msg = opt.get();
        msg.setDeleted(true);
        return Optional.of(messageRepository.save(msg));
    }

    /**
     * Soft-delete a user's own message within 60 minutes of sending.
     */
    @Transactional
    public Optional<SupportMessage> deleteMessage(Long id) {
        Optional<SupportMessage> opt = messageRepository.findById(id);
        if (opt.isEmpty()) return Optional.empty();

        SupportMessage msg = opt.get();
        User user = getCurrentUser();
        if (user == null || msg.getSender() == null || !user.getId().equals(msg.getSender().getId()))
            return Optional.empty();
        if (!msg.isUserMessage()) return Optional.empty();

        LocalDateTime cutoff = LocalDateTime.now(ZoneId.of("Asia/Kolkata")).minusMinutes(60);
        if (msg.getTimestamp().isBefore(cutoff)) return Optional.empty();

        msg.setDeleted(true);
        return Optional.of(messageRepository.save(msg));
    }

    /**
     * Mark all admin messages as read for the current user's conversation.
     * Changes status from "DELIVERED" to "READ"
     */
    @Transactional
    public void markAdminMessagesAsRead(String selectedAccountId) {
        User user = getCurrentUser();
        if (user == null) return;
        // Mark all admin messages as read for this user (all accounts)
        List<SupportMessage> unreadMessages = messageRepository.findBySenderAndIsUserMessageFalseAndStatus(user, "DELIVERED");
        if (!unreadMessages.isEmpty()) {
            for (SupportMessage msg : unreadMessages) {
                msg.setStatus("READ");
            }
            messageRepository.saveAll(unreadMessages);
        }
    }
}