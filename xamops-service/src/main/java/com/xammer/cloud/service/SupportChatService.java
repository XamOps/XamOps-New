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

    @Transactional
    public SupportMessage sendUserMessage(String messageContent) {
        // 1. Get Logged-in User
        String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 2. Identify Context (Account ID, Name, Cloud)
        // We pick the first available account linked to the user's client for context
        String accountId = "N/A";
        String accountName = "Unknown";
        String provider = "Unknown";

        if (user.getClient() != null && user.getClient().getCloudAccounts() != null && !user.getClient().getCloudAccounts().isEmpty()) {
            CloudAccount account = user.getClient().getCloudAccounts().get(0);
            accountId = account.getProviderAccountId(); // Helper method from CloudAccount
            accountName = account.getAccountName();
            provider = account.getProvider();
        }

        // 3. Create and Save Message
        SupportMessage supportMessage = new SupportMessage(user, messageContent, true);
        supportMessage.setAccountId(accountId);
        supportMessage.setAccountName(accountName);
        supportMessage.setCloudProvider(provider);

        return messageRepository.save(supportMessage);
    }

    public List<SupportMessage> getMessagesForCurrentUser() {
    String username = ((UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername();
    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));

    // Get the user's account ID (same logic as sendUserMessage)
    String accountId = "N/A";
    if (user.getClient() != null && user.getClient().getCloudAccounts() != null && !user.getClient().getCloudAccounts().isEmpty()) {
        accountId = user.getClient().getCloudAccounts().get(0).getProviderAccountId();
    }

    return messageRepository.findByAccountIdOrderByTimestampAsc(accountId);
}


}