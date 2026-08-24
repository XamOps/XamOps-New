package com.xammer.cloud.repository.call;

import com.xammer.cloud.domain.call.CallSession;
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
public interface CallSessionRepository extends JpaRepository<CallSession, Long> {

    Optional<CallSession> findByRoomName(String roomName);

    /**
     * Calls ringing or in progress on a ticket. Used to stop a second caller from
     * opening a competing room on the same ticket — they join the existing one.
     */
    List<CallSession> findByTicketIdAndStateInOrderByIdDesc(Long ticketId,
            Collection<CallSession.State> states);

    /**
     * Calls whose loud ring window has elapsed but which have not yet been moved into
     * the quiet queue phase.
     *
     * The {@code queued = false} predicate is what makes the escalation fire exactly
     * once: the ring deadline stays in the past forever, so without it every sweep
     * would re-announce the same call.
     */
    List<CallSession> findByStateAndQueuedFalseAndRingExpiresAtBefore(CallSession.State state,
            LocalDateTime cutoff);

    /**
     * Calls nobody took within the overall wait window. This is the deadline that
     * actually gives up and records a missed call.
     */
    List<CallSession> findByStateAndQueueExpiresAtBefore(CallSession.State state, LocalDateTime cutoff);

    /**
     * Calls still waiting for somebody to answer.
     *
     * Lets an agent who logs in partway through a queue window see what is holding.
     * Without this, only agents who happened to be connected at the moment of dialling
     * ever learned about a call — which is most of the point of having a queue.
     */
    List<CallSession> findByStateOrderByCreatedAtAsc(CallSession.State state);

    List<CallSession> findByTicketIdOrderByCreatedAtDesc(Long ticketId);

    /**
     * Answered calls old enough to be worth checking for abandonment.
     *
     * Only a candidate list — the sweeper still confirms via participant
     * heartbeats before closing anything, because a long call that is genuinely in
     * progress looks identical to an abandoned one from the session row alone.
     */
    List<CallSession> findByStateAndAnsweredAtBefore(CallSession.State state, LocalDateTime cutoff);

    /**
     * Live sessions past an absolute age cap, regardless of heartbeats.
     *
     * Backstop for the case the heartbeat mechanism itself is what failed — a
     * client version that never sends one, or a bug in this service. Without it a
     * single such session would block its ticket forever, which is the exact
     * failure this whole mechanism exists to prevent.
     */
    List<CallSession> findByStateInAndCreatedAtBefore(Collection<CallSession.State> states,
            LocalDateTime cutoff);

    /**
     * First-answer-wins, decided by the database.
     *
     * Two admins hitting Accept in the same instant both reach this statement; the
     * {@code state = RINGING} predicate means exactly one row update succeeds. The
     * winner sees 1, everyone else sees 0 and is offered a join instead. Doing the
     * claim as a single conditional UPDATE rather than an external lock removes any
     * window where the lock and the row could disagree.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallSession c SET c.state = :activeState, c.answeredByUserId = :userId, "
            + "c.answeredByUsername = :username, c.answeredAt = :now "
            + "WHERE c.id = :callId AND c.state = :ringingState")
    int claimAnswer(@Param("callId") Long callId,
            @Param("userId") Long userId,
            @Param("username") String username,
            @Param("now") LocalDateTime now,
            @Param("ringingState") CallSession.State ringingState,
            @Param("activeState") CallSession.State activeState);

    /**
     * Moves a call to a terminal state only if it is still in one of the states the
     * caller expected. Guards against a hangup and a ring timeout racing to close
     * the same call and both writing an end reason.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CallSession c SET c.state = :newState, c.endedAt = :now, c.endReason = :reason "
            + "WHERE c.id = :callId AND c.state IN :fromStates")
    int terminate(@Param("callId") Long callId,
            @Param("newState") CallSession.State newState,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now,
            @Param("fromStates") Collection<CallSession.State> fromStates);
}
