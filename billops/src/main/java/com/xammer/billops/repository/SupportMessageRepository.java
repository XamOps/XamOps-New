package com.xammer.billops.repository;

import com.xammer.billops.domain.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
    
    // Find all messages, ordered by time (Admin Inbox - Raw View)
    List<SupportMessage> findAllByOrderByTimestampDesc();
    
    // Find conversation for a specific account (Chat Window)
    List<SupportMessage> findByAccountIdOrderByTimestampAsc(String accountId);

    // Get unique list of accounts that have opened tickets (The "Inbox" Sidebar)
    // Only show conversations started by users (isUserMessage = true), not admin replies
    @Query("SELECT DISTINCT m.accountId, m.accountName, m.cloudProvider, m.senderUsername FROM SupportMessage m WHERE m.isUserMessage = true AND m.senderUsername IS NOT NULL")
    List<Object[]> findDistinctConversations();
}