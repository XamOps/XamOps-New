package com.xammer.cloud.repository;

import com.xammer.cloud.domain.ScriptRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence operations for {@link ScriptRating}.
 *
 * Requirements: REQ-2.4.1 (Script Rating System)
 */
@Repository
public interface ScriptRatingRepository extends JpaRepository<ScriptRating, Long> {

    /**
     * The rating a single user left for a script, if any. Enforces the
     * one-rating-per-user-per-script rule (update existing instead of inserting).
     */
    Optional<ScriptRating> findByScriptIdAndUserId(Long scriptId, String userId);

    /**
     * All ratings for a script, used to (re)compute the average rating and count.
     */
    List<ScriptRating> findByScriptId(Long scriptId);

    /**
     * Count of ratings for a script.
     */
    long countByScriptId(Long scriptId);
}
