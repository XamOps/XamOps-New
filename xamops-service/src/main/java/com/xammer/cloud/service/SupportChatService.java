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

import java.util.List;

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
     */
    private String getAccountIdForCurrentUser(String selectedAccountId) {
        String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

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

        return messageRepository.save(supportMessage);
    }

    public List<SupportMessage> getMessagesForCurrentUser(String selectedAccountId) {
        String accountId = getAccountIdForCurrentUser(selectedAccountId);
        return messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
    }

    /**
     * Get count of unread admin messages (messages from admin to user that are not yet read)
     * Admin messages have isUserMessage = false and status = "DELIVERED"
     */
    public long getUnreadAdminMessageCount(String selectedAccountId) {
        String accountId = getAccountIdForCurrentUser(selectedAccountId);
        return messageRepository.countByAccountIdAndIsUserMessageFalseAndStatus(accountId, "DELIVERED");
    }

    /**
     * Mark all admin messages as read for the current user's conversation.
     * Changes status from "DELIVERED" to "READ"
     */
    @Transactional
    public void markAdminMessagesAsRead(String selectedAccountId) {
        String accountId = getAccountIdForCurrentUser(selectedAccountId);
        List<SupportMessage> unreadMessages = messageRepository.findByAccountIdAndIsUserMessageFalseAndStatus(accountId, "DELIVERED");
        if (!unreadMessages.isEmpty()) {
            for (SupportMessage msg : unreadMessages) {
                msg.setStatus("READ");
            }
            messageRepository.saveAll(unreadMessages);
        }
    }
}