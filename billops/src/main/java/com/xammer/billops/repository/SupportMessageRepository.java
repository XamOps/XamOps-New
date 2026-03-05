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

    // Get unique list of accounts that have any messages (inbox sidebar)
    // Includes admin-initiated conversations, not just user-started ones
    @Query(value = "SELECT account_id, account_name, cloud_provider, " +
                   "MAX(CASE WHEN is_user_message = true THEN sender_username END) AS sender_username " +
                   "FROM support_message WHERE account_id IS NOT NULL " +
                   "GROUP BY account_id, account_name, cloud_provider",
           nativeQuery = true)
    List<Object[]> findDistinctConversations();

    long countByAccountIdAndIsUserMessageTrueAndStatus(String accountId, String status);

    SupportMessage findFirstByAccountIdOrderByTimestampDesc(String accountId);

    List<SupportMessage> findByAccountIdAndIsUserMessageTrueAndStatus(String accountId, String status);
}