package com.xammer.cloud.repository.call;

import com.xammer.cloud.domain.call.CallParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallParticipantRepository extends JpaRepository<CallParticipant, Long> {

    List<CallParticipant> findByCallSessionId(Long callSessionId);

    Optional<CallParticipant> findByCallSessionIdAndUserId(Long callSessionId, Long userId);

    /**
     * Closes out everyone still undecided when a call finishes. Applied in one
     * statement so a large ring set does not turn into N updates.
     *
     * Clears the persistence context afterwards: this is a bulk update that
     * bypasses the first-level cache, so without it a caller reading participants
     * later in the same transaction would still see the pre-update outcomes.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallParticipant p SET p.outcome = :outcome "
            + "WHERE p.callSessionId = :callSessionId AND p.outcome IS NULL")
    int settleUndecided(@Param("callSessionId") Long callSessionId,
            @Param("outcome") CallParticipant.Outcome outcome);

    List<CallParticipant> findByUserIdAndOutcomeOrderByIdDesc(Long userId, CallParticipant.Outcome outcome);

    /**
     * Records a heartbeat from someone in the room.
     *
     * Deliberately a targeted UPDATE rather than a load-mutate-save: this runs every
     * few seconds per participant per live call, and one column changes. The
     * "still present" predicate means a straggling heartbeat cannot resurrect a
     * participant who has already left, which would otherwise keep an empty room
     * alive indefinitely.
     *
     * The enum arrives as a bound parameter rather than being written inline in the
     * query. Inline enum literals in JPQL only fail when Hibernate parses the query
     * at startup, which turns a typo into a boot failure instead of a compile error.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallParticipant p SET p.lastSeenAt = :now "
            + "WHERE p.callSessionId = :callSessionId AND p.userId = :userId "
            + "AND p.outcome = :joined AND p.leftAt IS NULL")
    int touchHeartbeat(@Param("callSessionId") Long callSessionId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now,
            @Param("joined") CallParticipant.Outcome joined);

    /**
     * Marks everyone still in the room as gone, used when a session is force-closed.
     * No row may be left claiming to occupy a room that no longer exists.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallParticipant p SET p.leftAt = :now "
            + "WHERE p.callSessionId = :callSessionId AND p.leftAt IS NULL AND p.outcome = :joined")
    int evictRemaining(@Param("callSessionId") Long callSessionId,
            @Param("now") LocalDateTime now,
            @Param("joined") CallParticipant.Outcome joined);

    /**
     * Sessions that still have at least one occupant checking in recently.
     *
     * The sweeper inverts this: a candidate session absent from the result has
     * nobody alive in it and is safe to close. One query covers a whole sweep rather
     * than a round trip per session.
     */
    @Query("SELECT DISTINCT p.callSessionId FROM CallParticipant p "
            + "WHERE p.callSessionId IN :sessionIds AND p.outcome = :joined AND p.leftAt IS NULL "
            + "AND p.lastSeenAt IS NOT NULL AND p.lastSeenAt > :cutoff")
    List<Long> findSessionIdsWithLiveOccupant(@Param("sessionIds") Collection<Long> sessionIds,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("joined") CallParticipant.Outcome joined);

    /** This user's live participation, if any — powers reload recovery. */
    @Query("SELECT p FROM CallParticipant p WHERE p.userId = :userId "
            + "AND p.outcome = :joined AND p.leftAt IS NULL ORDER BY p.id DESC")
    List<CallParticipant> findInRoomByUserId(@Param("userId") Long userId,
            @Param("joined") CallParticipant.Outcome joined);
}
