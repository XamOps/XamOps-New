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

    // ✅ NEW: Get unique list of accounts that have opened tickets (The "Inbox" Sidebar)
    // This allows the admin to see "Account A", "Account B" instead of 100 mixed messages.
    @Query("SELECT DISTINCT s.accountId, s.accountName, s.cloudProvider FROM SupportMessage s")
    List<Object[]> findDistinctConversations();
}