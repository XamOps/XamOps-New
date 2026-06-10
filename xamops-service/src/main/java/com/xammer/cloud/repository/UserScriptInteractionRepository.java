package com.xammer.cloud.repository;

import com.xammer.cloud.domain.UserScriptInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Persistence operations for {@link UserScriptInteraction} events.
 *
 * Requirements: REQ-2.4.2 (Favorite Scripts), REQ-2.4.3 (Usage Tracking),
 *               REQ-2.4.4 (Trending Scripts)
 */
@Repository
public interface UserScriptInteractionRepository extends JpaRepository<UserScriptInteraction, Long> {

    /**
     * All interactions recorded within the given (inclusive) time window. Used by
     * the trending calculation to score recent activity across all scripts.
     */
    List<UserScriptInteraction> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    /**
     * Interactions for a single script within a time window.
     */
    List<UserScriptInteraction> findByScriptIdAndTimestampBetween(Long scriptId,
                                                                  LocalDateTime start,
                                                                  LocalDateTime end);

    /**
     * Count of a specific interaction type a user has recorded against a script.
     * Used to derive the current favorite state (favorites minus unfavorites).
     */
    long countByUserIdAndScriptIdAndInteractionType(String userId, Long scriptId, String interactionType);
}
