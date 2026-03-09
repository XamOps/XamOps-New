package com.xammer.cloud.repository;

import com.xammer.cloud.domain.SupportMessage;
import com.xammer.cloud.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {
    // Find chat history for a specific user, ordered by time
    List<SupportMessage> findBySenderOrderByTimestampAsc(User sender);

    List<SupportMessage> findByAccountIdOrderByTimestampAsc(String accountId);

    // ── Sender-only queries (user-based conversations — no accountId filter) ──

    long countBySenderAndIsUserMessageFalseAndStatus(User sender, String status);

    List<SupportMessage> findBySenderAndIsUserMessageFalseAndStatus(User sender, String status);

    // ── Sender+account scoped queries (kept for legacy / backward compat) ──

    List<SupportMessage> findByAccountIdAndSenderOrderByTimestampAsc(String accountId, User sender);

    long countByAccountIdAndSenderAndIsUserMessageFalseAndStatus(String accountId, User sender, String status);

    List<SupportMessage> findByAccountIdAndSenderAndIsUserMessageFalseAndStatus(String accountId, User sender, String status);

    // ── Legacy fallbacks (used when sender is unknown) ──

    // Count unread admin messages (isUserMessage = false means admin sent it)
    long countByAccountIdAndIsUserMessageFalseAndStatus(String accountId, String status);

    // Find unread admin messages for marking as read
    List<SupportMessage> findByAccountIdAndIsUserMessageFalseAndStatus(String accountId, String status);

    // ── Admin inbox: all user-sent messages with sender eagerly loaded ──
    @Query("SELECT m FROM SupportMessage m JOIN FETCH m.sender WHERE m.isUserMessage = true ORDER BY m.timestamp DESC")
    List<SupportMessage> findAllUserMessagesWithSenderOrderByTimestampDesc();
}
