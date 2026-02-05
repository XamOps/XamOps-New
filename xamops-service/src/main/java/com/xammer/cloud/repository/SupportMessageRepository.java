package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
    // Find chat history for a specific user, ordered by time
    List<SupportMessage> findBySenderOrderByTimestampAsc(User sender);

    List<SupportMessage> findByAccountIdOrderByTimestampAsc(String accountId);

    // Count unread admin messages (isUserMessage = false means admin sent it)
    long countByAccountIdAndIsUserMessageFalseAndStatus(String accountId, String status);

    // Find unread admin messages for marking as read
    List<SupportMessage> findByAccountIdAndIsUserMessageFalseAndStatus(String accountId, String status);
}
