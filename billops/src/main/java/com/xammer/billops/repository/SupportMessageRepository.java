package com.xammer.billops.repository;

import com.xammer.billops.domain.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    // Find all messages, ordered by time (Admin Inbox - Raw View)
    List<SupportMessage> findAllByOrderByTimestampDesc();

    // Find conversation for a specific account (Chat Window)
    List<SupportMessage> findByAccountIdOrderByTimestampAsc(String accountId);

    // ── Inbox query: grouped by userId (one conversation per user) ──
    @Query(value = "SELECT user_id, " +
                   "MAX(CASE WHEN is_user_message = true THEN sender_username END) AS sender_username " +
                   "FROM support_message WHERE user_id IS NOT NULL " +
                   "GROUP BY user_id " +
                   "ORDER BY MAX(timestamp) DESC",
           nativeQuery = true)
    List<Object[]> findDistinctUserConversations();

    // ── userId-based queries (primary conversation key) ──

    List<SupportMessage> findByUserIdOrderByTimestampAsc(Long userId);

    List<SupportMessage> findByUserIdAndIsUserMessageTrueAndStatus(Long userId, String status);

    long countByUserIdAndIsUserMessageTrueAndStatus(Long userId, String status);

    long countByUserIdAndIsUserMessageFalseAndStatus(Long userId, String status);

    SupportMessage findFirstByUserIdOrderByTimestampDesc(Long userId);

    // ── accountId+userId scoped queries (kept for legacy / backward compat) ──

    List<SupportMessage> findByAccountIdAndUserIdOrderByTimestampAsc(String accountId, Long userId);

    List<SupportMessage> findByAccountIdAndUserIdAndIsUserMessageTrueAndStatus(
            String accountId, Long userId, String status);

    long countByAccountIdAndUserIdAndIsUserMessageTrueAndStatus(
            String accountId, Long userId, String status);

    SupportMessage findFirstByAccountIdAndUserIdOrderByTimestampDesc(String accountId, Long userId);

    // ── Legacy fallbacks (userId unknown / null) ──

    long countByAccountIdAndIsUserMessageTrueAndStatus(String accountId, String status);

    SupportMessage findFirstByAccountIdOrderByTimestampDesc(String accountId);

    List<SupportMessage> findByAccountIdAndIsUserMessageTrueAndStatus(String accountId, String status);

    // Auto-ping: find admin-initiated messages that have auto-ping enabled and not yet sent
    List<SupportMessage> findByAutoPingEnabledTrueAndAutoPingSentFalseAndIsUserMessageFalse();

    // Auto-ping: check whether the user has replied after a given timestamp (userId-based)
    boolean existsByUserIdAndIsUserMessageTrueAndTimestampAfter(Long userId, LocalDateTime after);

    // Auto-ping: legacy accountId-based check (fallback for old records)
    boolean existsByAccountIdAndIsUserMessageTrueAndTimestampAfter(String accountId, LocalDateTime after);

    // Deduplication: check if an inbound message from an external platform was already saved
    boolean existsByExternalMessageId(String externalMessageId);
}