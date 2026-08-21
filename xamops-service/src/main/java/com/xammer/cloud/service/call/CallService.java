package com.xammer.cloud.service.call;

import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import com.xammer.cloud.dto.call.CallSignal;
import com.xammer.cloud.dto.call.CallTokenResponse;
import com.xammer.cloud.repository.TicketRepository;
import com.xammer.cloud.repository.TicketReplyRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.call.CallParticipantRepository;
import com.xammer.cloud.repository.call.CallSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The call lifecycle: ring a group, let the first responder win, keep the room
 * open for stragglers, and make sure an unanswered call always lands somewhere.
 *
 * All state transitions go through conditional updates in
 * {@link CallSessionRepository}, so concurrent accepts and a racing ring timeout
 * cannot both succeed. Nothing here trusts the browser for timing — a client that
 * disappears mid-ring still produces a missed call, because the sweeper owns the
 * deadline.
 */
@Service
public class CallService {

    private static final Logger log = LoggerFactory.getLogger(CallService.class);

    private static final Set<CallSession.State> LIVE_STATES =
            Set.of(CallSession.State.RINGING, CallSession.State.ACTIVE);

    /** Matches ROLE_BILLOPS_ADMIN and ROLE_BILLOPS_ADMIN_SUB alike. */
    private static final String ADMIN_ROLE_FRAGMENT = "BILLOPS_ADMIN";

    /**
     * Thrown when a caller has no business touching a ticket or a call.
     *
     * Distinct from {@link IllegalStateException}, which the controller maps to
     * 409/CONFLICT for legitimate-but-unavailable situations. Authorization
     * failures must surface as 403 and must not leak the reason back to the
     * browser — a message naming user ids and call ids was previously being
     * rendered straight into a toast.
     */
    public static class CallAccessDeniedException extends RuntimeException {
        public CallAccessDeniedException(String internalDetail) {
            super(internalDetail);
        }
    }

    private final CallSessionRepository sessionRepository;
    private final CallParticipantRepository participantRepository;
    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final UserRepository userRepository;
    private final CallRecipientResolver recipientResolver;
    private final CallPresenceService presenceService;
    private final LiveKitTokenService liveKitTokenService;
    private final CallNotificationService callNotificationService;
    private final CallSignalRelay signalRelay;

    @Value("${xamops.calls.ring-seconds:40}")
    private long ringSeconds;

    /**
     * How long an occupant may go without a heartbeat before the room is treated as
     * empty.
     *
     * Must comfortably exceed the client's heartbeat interval, or a brief socket
     * reconnect during a real conversation would tear the call down. It also has to
     * stay short enough that an abandoned call frees its ticket quickly, since the
     * ticket cannot be called again until it does.
     */
    @Value("${xamops.calls.active-stale-seconds:120}")
    private long activeStaleSeconds;

    /**
     * Absolute ceiling on a live session, applied regardless of heartbeats.
     *
     * Backstop for the heartbeat mechanism itself failing — an old client build, or
     * a bug here. Generous enough that no genuine support call is ever cut off.
     */
    @Value("${xamops.calls.max-session-hours:12}")
    private long maxSessionHours;

    public CallService(CallSessionRepository sessionRepository,
            CallParticipantRepository participantRepository,
            TicketRepository ticketRepository,
            TicketReplyRepository ticketReplyRepository,
            UserRepository userRepository,
            CallRecipientResolver recipientResolver,
            CallPresenceService presenceService,
            LiveKitTokenService liveKitTokenService,
            CallNotificationService callNotificationService,
            CallSignalRelay signalRelay) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.ticketRepository = ticketRepository;
        this.ticketReplyRepository = ticketReplyRepository;
        this.userRepository = userRepository;
        this.recipientResolver = recipientResolver;
        this.presenceService = presenceService;
        this.liveKitTokenService = liveKitTokenService;
        this.callNotificationService = callNotificationService;
        this.signalRelay = signalRelay;
    }

    // ─── Initiation ──────────────────────────────────────────────────────────

    /**
     * Starts a call on a ticket, or hands back the one already in flight.
     *
     * Only one room ever exists per ticket at a time: a second caller joins the
     * live call rather than opening a competing room nobody can see.
     */
    @Transactional
    public CallSession initiate(CallUserPrincipal caller, Long ticketId, String mediaKindRaw) {
        // Checked before anything is created: without LiveKit the call can ring
        // people who then have no room to join, and the only way out is the ring
        // timeout. Failing here keeps the misconfiguration visible to the caller
        // instead of turning it into a mystery missed call for everyone else.
        if (!liveKitTokenService.isConfigured()) {
            throw new IllegalStateException(
                    "Calling is unavailable: LiveKit is not configured on this environment");
        }

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));

        // Nothing previously stopped a customer from starting a call on another
        // tenant's ticket id. That created a real session against a ticket they
        // cannot see and echoed its subject back in the signal.
        requireTicketAccess(caller, ticket);

        List<CallSession> existing = sessionRepository
                .findByTicketIdAndStateInOrderByIdDesc(ticketId, LIVE_STATES);
        if (!existing.isEmpty()) {
            return attachToLiveCall(caller, existing.get(0), ticket);
        }

        boolean callerIsAdmin = caller.isBillOpsAdmin();
        CallSession.Direction direction = callerIsAdmin
                ? CallSession.Direction.ADMIN_TO_USER
                : CallSession.Direction.USER_TO_ADMIN;

        List<User> recipients = callerIsAdmin
                ? recipientResolver.resolveTicketAudience(ticket, caller.getUserId())
                : recipientResolver.resolveAdminPool(ticket, caller.getUserId());

        CallSession session = new CallSession();
        session.setRoomName("ticket-" + ticketId + "-" + UUID.randomUUID().toString().substring(0, 8));
        session.setTicketId(ticketId);
        session.setClientId(ticket.getClient() != null ? ticket.getClient().getId() : null);
        session.setAccountId(ticket.getAccountId());
        session.setDirection(direction);
        session.setMediaKind(parseMediaKind(mediaKindRaw));
        session.setInitiatorUserId(caller.getUserId());
        session.setInitiatorUsername(caller.getUsername());
        session.setInitiatorDisplayName(caller.getDisplayName());
        session.setCreatedAt(LocalDateTime.now());
        session.setRingExpiresAt(LocalDateTime.now().plusSeconds(ringSeconds));
        session.setState(CallSession.State.RINGING);
        session = sessionRepository.save(session);

        // The initiator is a participant too, already in the room.
        CallParticipant initiator = new CallParticipant();
        initiator.setCallSessionId(session.getId());
        initiator.setUserId(caller.getUserId());
        initiator.setUsername(caller.getUsername());
        initiator.setDisplayName(caller.getDisplayName());
        initiator.setParty(CallParticipant.Party.INITIATOR);
        initiator.setWasRung(false);
        initiator.setOutcome(CallParticipant.Outcome.JOINED);
        initiator.setJoinedAt(LocalDateTime.now());
        // Seeded so the staleness sweeper has a baseline from the very first moment.
        // Without it the initiator would look like an occupant who has never checked
        // in, and a call could be reaped before its first heartbeat arrived.
        initiator.setLastSeenAt(LocalDateTime.now());
        participantRepository.save(initiator);

        CallParticipant.Party recipientParty = callerIsAdmin
                ? CallParticipant.Party.CUSTOMER
                : CallParticipant.Party.ADMIN;

        List<CallParticipant> rung = new ArrayList<>();
        for (User u : recipients) {
            CallParticipant p = new CallParticipant();
            p.setCallSessionId(session.getId());
            p.setUserId(u.getId());
            p.setUsername(u.getUsername());
            p.setDisplayName(displayNameOf(u));
            p.setParty(recipientParty);
            p.setWasRung(true);
            rung.add(p);
        }
        participantRepository.saveAll(rung);

        // Ring only those with a socket open. Everyone else stays in the participant
        // table with a null outcome and is settled as MISSED when the call closes,
        // so an offline agent still shows the call in their missed list.
        Set<Long> online = presenceService.filterOnline(
                rung.stream().map(CallParticipant::getUserId).toList());

        if (online.isEmpty()) {
            log.info("Call {} on ticket {} had no reachable recipients; closing immediately",
                    session.getId(), ticketId);
            closeCall(session, CallSession.State.MISSED, "no_recipients_online",
                    CallParticipant.Outcome.MISSED, ticket);
            CallSignal signal = buildSignal(CallSignal.Type.NO_ANSWER_AVAILABLE, session, ticket);
            signal.setReason("no_recipients_online");
            notify(caller.getUserId(), signal);
            return session;
        }

        CallSignal incoming = buildSignal(CallSignal.Type.INCOMING, session, ticket);
        for (Long userId : online) {
            notify(userId, incoming);
        }

        CallSignal ringing = buildSignal(CallSignal.Type.RINGING, session, ticket);
        ringing.setActiveParticipants(online.size());
        notify(caller.getUserId(), ringing);

        log.info("Call {} ringing {} recipient(s) on ticket {} ({})",
                session.getId(), online.size(), ticketId, direction);
        return session;
    }

    /**
     * Puts a caller into the call already running on their ticket.
     *
     * One room per ticket is the right rule, but previously the second caller was
     * handed the existing session without being made a participant — so the very
     * next thing their browser did, ask for a join token, was rejected as "not
     * invited". The visible result was an error toast quoting internal ids, and in
     * an admin-initiated call a second admin had no way into the room at all,
     * because JOINABLE only ever reaches people who were rung and that direction
     * rings customers.
     *
     * Anyone who passed {@link #requireTicketAccess} belongs on their ticket's call,
     * so they are admitted as a present occupant and the room is told its roster
     * grew.
     */
    private CallSession attachToLiveCall(CallUserPrincipal caller, CallSession live, Ticket ticket) {
        Optional<CallParticipant> existing =
                participantRepository.findByCallSessionIdAndUserId(live.getId(), caller.getUserId());

        if (existing.isPresent() && existing.get().isInRoom()) {
            // Already in the room — a second tab, or a reload. Refresh liveness so
            // this does not look like an abandoned seat, and hand back the session so
            // the caller simply re-enters.
            participantRepository.touchHeartbeat(live.getId(), caller.getUserId(),
                    LocalDateTime.now(), CallParticipant.Outcome.JOINED);
            return live;
        }

        CallParticipant p = existing.orElseGet(CallParticipant::new);
        if (existing.isEmpty()) {
            p.setCallSessionId(live.getId());
            p.setUserId(caller.getUserId());
            p.setUsername(caller.getUsername());
            p.setDisplayName(caller.getDisplayName());
            p.setParty(caller.isBillOpsAdmin()
                    ? CallParticipant.Party.ADMIN
                    : CallParticipant.Party.CUSTOMER);
        }
        // wasRung stays false for someone who walked in of their own accord: they
        // were never rung, so they must not appear in the missed-call feed if this
        // call later expires.
        p.setOutcome(CallParticipant.Outcome.JOINED);
        p.setLeftAt(null);
        if (p.getJoinedAt() == null) {
            p.setJoinedAt(LocalDateTime.now());
        }
        p.setLastSeenAt(LocalDateTime.now());
        participantRepository.save(p);

        log.info("User {} joined the call {} already live on ticket {}",
                caller.getUserId(), live.getId(), live.getTicketId());

        CallSignal changed = buildSignal(CallSignal.Type.PARTICIPANT_CHANGED, live, ticket);
        changed.setReason("joined:" + caller.getDisplayName());
        broadcastToParticipants(live.getId(), changed, null);
        return live;
    }

    /**
     * Gate for every ticket-scoped call operation.
     *
     * BillOps admins are the support organisation and legitimately reach every
     * ticket. Everyone else is confined to their own client. Sub-user account
     * scoping is deliberately *not* applied here: it decides who gets rung, which is
     * a routing question, but using it to gate access would lock a sub-user out of a
     * call on a ticket their own UI shows them.
     */
    private void requireTicketAccess(CallUserPrincipal caller, Ticket ticket) {
        if (caller.isBillOpsAdmin()) {
            return;
        }
        Long ticketClientId = ticket.getClient() != null ? ticket.getClient().getId() : null;
        if (ticketClientId == null || !ticketClientId.equals(caller.getClientId())) {
            throw new CallAccessDeniedException("User " + caller.getUserId()
                    + " may not access calls on ticket " + ticket.getId());
        }
    }

    // ─── Accept / join ───────────────────────────────────────────────────────

    /**
     * Accepts a ringing call. Exactly one caller wins; the rest are told the call
     * was already answered and may join it instead.
     *
     * @return true if this user became the answerer
     */
    @Transactional
    public boolean accept(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        requireWasInvited(session, user.getUserId());

        int claimed = sessionRepository.claimAnswer(callId, user.getUserId(), user.getUsername(),
                LocalDateTime.now(), CallSession.State.RINGING, CallSession.State.ACTIVE);
        if (claimed == 0) {
            return false;
        }

        CallSession fresh = requireSession(callId);
        Ticket ticket = ticketRepository.findById(fresh.getTicketId()).orElse(null);
        markJoined(callId, user.getUserId());

        CallSignal answered = buildSignal(CallSignal.Type.ANSWERED, fresh, ticket);
        answered.setAnsweredByUserId(user.getUserId());
        answered.setAnsweredByName(user.getDisplayName());
        notify(fresh.getInitiatorUserId(), answered);

        // Also tell the answerer. User destinations fan out to every session that
        // user has open, so this is what stops their *other* browser tabs ringing
        // — those tabs were rung too and would otherwise keep going for the whole
        // duration of the call, since an answered call never expires.
        if (!user.getUserId().equals(fresh.getInitiatorUserId())) {
            notify(user.getUserId(), answered);
        }

        // Everyone else stops ringing but keeps a way in.
        CallSignal joinable = buildSignal(CallSignal.Type.JOINABLE, fresh, ticket);
        joinable.setAnsweredByUserId(user.getUserId());
        joinable.setAnsweredByName(user.getDisplayName());
        for (CallParticipant p : participantRepository.findByCallSessionId(callId)) {
            if (p.isWasRung() && !p.getUserId().equals(user.getUserId())) {
                notify(p.getUserId(), joinable);
            }
        }

        log.info("Call {} answered by user {}", callId, user.getUserId());
        return true;
    }

    /**
     * Joins a call that is already live — the "you can still hop in" path for
     * people who were rung but did not answer first.
     */
    @Transactional
    public void join(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        if (session.getState() != CallSession.State.ACTIVE) {
            throw new IllegalStateException("Call " + callId + " is not active");
        }
        requireWasInvited(session, user.getUserId());
        markJoined(callId, user.getUserId());

        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        CallSignal changed = buildSignal(CallSignal.Type.PARTICIPANT_CHANGED, session, ticket);
        changed.setReason("joined:" + user.getDisplayName());
        broadcastToParticipants(callId, changed, null);
    }

    /**
     * Issues a LiveKit token for someone currently in the room.
     *
     * "Currently in the room" is stricter than "was invited", and deliberately so.
     * {@code wasRung} stays true after a decline, so an invitation check alone let a
     * participant who had declined call this endpoint directly and walk into the
     * conversation — with no {@code markJoined}, no roster event, and therefore no
     * indication to anyone already there that they had arrived. Every legitimate
     * path to a token ({@link #accept}, {@link #join}, {@link #initiate}) records
     * presence first, so requiring presence here costs nothing and closes that door.
     */
    @Transactional(readOnly = true)
    public CallTokenResponse issueToken(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        if (!session.isLive()) {
            throw new IllegalStateException("Call " + callId + " has ended");
        }

        boolean present = participantRepository
                .findByCallSessionIdAndUserId(callId, user.getUserId())
                .map(CallParticipant::isInRoom)
                .orElse(false);
        if (!present) {
            throw new CallAccessDeniedException("User " + user.getUserId()
                    + " is not currently in call " + callId);
        }

        String identity = String.valueOf(user.getUserId());
        String token = liveKitTokenService.createJoinToken(
                session.getRoomName(), identity, user.getDisplayName(), true);
        return new CallTokenResponse(liveKitTokenService.getLivekitUrl(), token,
                session.getRoomName(), identity, session.getId());
    }

    // ─── Decline / cancel / leave ────────────────────────────────────────────

    /** Dismisses a ring. When the last recipient declines, the call closes. */
    @Transactional
    public void decline(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        // Previously unguarded, unlike accept/join. A stranger could not read
        // anything, but the "has everyone decided?" check below closes the call when
        // it finds no undecided recipient, so an outsider could help terminate a ring
        // they were never part of.
        requireWasInvited(session, user.getUserId());
        participantRepository.findByCallSessionIdAndUserId(callId, user.getUserId())
                .ifPresent(p -> {
                    if (p.getOutcome() == null) {
                        p.setOutcome(CallParticipant.Outcome.DECLINED);
                        participantRepository.save(p);
                    }
                });

        if (session.getState() != CallSession.State.RINGING) {
            return;
        }

        boolean anyoneLeft = participantRepository.findByCallSessionId(callId).stream()
                .anyMatch(p -> p.isWasRung() && p.getOutcome() == null);
        if (anyoneLeft) {
            return;
        }

        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        if (closeCall(session, CallSession.State.DECLINED, "all_recipients_declined", null, ticket)) {
            CallSignal declined = buildSignal(CallSignal.Type.DECLINED, session, ticket);
            notify(session.getInitiatorUserId(), declined);
        }
    }

    /** Initiator hangs up while it is still ringing. */
    @Transactional
    public void cancel(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        if (!session.getInitiatorUserId().equals(user.getUserId())) {
            throw new CallAccessDeniedException("Only the initiator can cancel call " + callId);
        }
        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        if (closeCall(session, CallSession.State.CANCELLED, "cancelled_by_initiator",
                CallParticipant.Outcome.CANCELLED, ticket)) {
            CallSignal cancelled = buildSignal(CallSignal.Type.CANCELLED, session, ticket);
            broadcastToParticipants(callId, cancelled, null);
        }
    }

    /**
     * Leaves an active room. The call ends once the last person is out — the room
     * should not linger open after everyone has gone.
     */
    @Transactional
    public void leave(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        // Also previously unguarded: leaving a call you were never in broadcast a
        // "left:<your name>" roster event to everyone in it, and could close the
        // session outright if it found nobody marked present.
        requireParticipant(session, user.getUserId());

        // Hanging up an unanswered call you placed cancels it for everyone; hanging
        // up any other call only removes you. That decision is made here rather than
        // in the browser because only the server knows both the session state and who
        // placed the call. The client used to choose between /leave and /cancel from
        // its own view of the phase, and got it wrong whenever someone joined a call
        // that was still ringing — it would call /cancel without being the initiator,
        // take a 403, and silently leave itself marked as present.
        if (session.getState() == CallSession.State.RINGING
                && session.getInitiatorUserId().equals(user.getUserId())) {
            cancel(user, callId);
            return;
        }

        participantRepository.findByCallSessionIdAndUserId(callId, user.getUserId())
                .ifPresent(p -> {
                    p.setLeftAt(LocalDateTime.now());
                    participantRepository.save(p);
                });

        boolean anyoneStillIn = participantRepository.findByCallSessionId(callId).stream()
                .anyMatch(CallParticipant::isInRoom);

        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        if (anyoneStillIn) {
            CallSignal changed = buildSignal(CallSignal.Type.PARTICIPANT_CHANGED, session, ticket);
            changed.setReason("left:" + user.getDisplayName());
            broadcastToParticipants(callId, changed, user.getUserId());
            return;
        }

        if (closeCall(session, CallSession.State.ENDED, "all_participants_left", null, ticket)) {
            recordCallSummaryNote(session, ticket, LocalDateTime.now());
            CallSignal ended = buildSignal(CallSignal.Type.ENDED, session, ticket);
            broadcastToParticipants(callId, ended, null);
        }
    }

    // ─── Ring expiry ─────────────────────────────────────────────────────────

    /**
     * Expires rings nobody answered.
     *
     * This is the reason missed calls are reliable: the deadline lives on the
     * server, so a caller who closes the tab and an agent who never looked both
     * still end up with a MISSED record.
     *
     * Driven by {@link CallSweeperScheduler} rather than {@code @Scheduled}, so it
     * runs on a dedicated thread instead of queueing behind the long cloud-scan jobs
     * that share the application scheduler.
     */
    @Transactional
    public void sweepExpiredRings() {
        List<CallSession> expired = sessionRepository.findByStateAndRingExpiresAtBefore(
                CallSession.State.RINGING, LocalDateTime.now());
        for (CallSession session : expired) {
            Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
            if (!closeCall(session, CallSession.State.MISSED, "ring_timeout",
                    CallParticipant.Outcome.MISSED, ticket)) {
                continue;
            }
            log.info("Call {} on ticket {} expired unanswered", session.getId(), session.getTicketId());
            CallSignal missed = buildSignal(CallSignal.Type.MISSED, session, ticket);
            missed.setReason("ring_timeout");
            broadcastToParticipants(session.getId(), missed, null);
        }
    }

    // ─── Abandoned call recovery ─────────────────────────────────────────────

    /**
     * Records that a client is still in a room.
     *
     * The counterpart to the sweeper below. Cheap by design — one targeted column
     * update — because it runs every few seconds for every participant on every live
     * call.
     *
     * @return false if this user is no longer considered present, which tells the
     *         client to stop heartbeating and reconcile
     */
    @Transactional
    public boolean heartbeat(CallUserPrincipal user, Long callId) {
        return participantRepository.touchHeartbeat(callId, user.getUserId(),
                LocalDateTime.now(), CallParticipant.Outcome.JOINED) > 0;
    }

    /**
     * Closes calls whose participants have all vanished.
     *
     * This is the fix for the worst failure this feature had: nothing ended a call
     * except someone pressing hang up. A closed tab, a killed browser or a dropped
     * connection left every occupant recorded as present, so the session stayed
     * ACTIVE indefinitely — and since {@link #initiate} hands back any live session
     * on the ticket, that ticket could never host another call again. One closed tab
     * was enough, and the damage was permanent.
     *
     * Two independent conditions close a session, because relying on only the first
     * would mean a client that never heartbeats (an old build, or a bug here)
     * recreates the original bug:
     *
     * <ol>
     * <li>every occupant has gone quiet for longer than the staleness window; or</li>
     * <li>the session has simply been live too long, whatever its heartbeats say.</li>
     * </ol>
     *
     * Deliberately conservative about what counts as abandoned: a session is only
     * closed when <em>no</em> occupant is still checking in, so a caller who sits in
     * a room alone waiting for someone to join is left alone.
     *
     * Driven by {@link CallSweeperScheduler}, on a thread of its own.
     */
    @Transactional
    public void sweepAbandonedCalls() {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> toClose = new HashSet<>();

        // (1) Answered calls whose occupants have all stopped checking in. Only
        // sessions already older than the window are considered, so a call answered a
        // second ago is never a candidate.
        List<CallSession> candidates = sessionRepository.findByStateAndAnsweredAtBefore(
                CallSession.State.ACTIVE, now.minusSeconds(activeStaleSeconds));
        if (!candidates.isEmpty()) {
            List<Long> candidateIds = candidates.stream().map(CallSession::getId).toList();
            Set<Long> stillAlive = new HashSet<>(participantRepository.findSessionIdsWithLiveOccupant(
                    candidateIds, now.minusSeconds(activeStaleSeconds),
                    CallParticipant.Outcome.JOINED));
            for (Long id : candidateIds) {
                if (!stillAlive.contains(id)) {
                    toClose.add(id);
                }
            }
        }

        // (2) Absolute age cap. Catches the case where the heartbeat itself is what
        // broke, which is precisely when the guarantee matters most.
        for (CallSession old : sessionRepository.findByStateInAndCreatedAtBefore(
                LIVE_STATES, now.minusHours(maxSessionHours))) {
            toClose.add(old.getId());
        }

        for (Long sessionId : toClose) {
            sessionRepository.findById(sessionId).ifPresent(this::closeAbandoned);
        }
    }

    /**
     * Ends one abandoned session and tells anyone still listening.
     *
     * A call that was genuinely answered still earns its timeline note: the
     * conversation happened, and losing the record because the last person's browser
     * died rather than clicking hang up would be arbitrary.
     */
    private void closeAbandoned(CallSession session) {
        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        boolean wasAnswered = session.getAnsweredAt() != null;

        // Read before closing: closeCall stamps left_at on everyone still present, and
        // the last heartbeat is when the conversation really stopped. Falling back to
        // now only matters when no heartbeat was ever recorded.
        LocalDateTime lastContact = participantRepository.findByCallSessionId(session.getId()).stream()
                .map(CallParticipant::getLastSeenAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(LocalDateTime.now());

        // An unanswered session reaped by the age cap is a missed call, not an ended
        // one, so its recipients still get the record they are owed.
        CallSession.State terminal = wasAnswered ? CallSession.State.ENDED : CallSession.State.MISSED;
        CallParticipant.Outcome undecided = wasAnswered ? null : CallParticipant.Outcome.MISSED;

        if (!closeCall(session, terminal, "abandoned_no_heartbeat", undecided, ticket)) {
            return;
        }
        log.info("Call {} on ticket {} closed as {} — no participant heartbeat within {}s",
                session.getId(), session.getTicketId(), terminal, activeStaleSeconds);

        if (wasAnswered) {
            recordCallSummaryNote(session, ticket, lastContact);
        }
        CallSignal signal = buildSignal(
                wasAnswered ? CallSignal.Type.ENDED : CallSignal.Type.MISSED, session, ticket);
        signal.setReason("abandoned");
        broadcastToParticipants(session.getId(), signal, null);
    }

    // ─── Queries ─────────────────────────────────────────────────────────────

    /**
     * Call history for a ticket.
     *
     * The authorization check is the point. This endpoint previously verified only
     * that the requester was logged in, so any authenticated user could walk ticket
     * ids and read initiator names, call directions, room names and timestamps
     * belonging to other tenants.
     */
    @Transactional(readOnly = true)
    public List<CallSession> historyForTicket(CallUserPrincipal caller, Long ticketId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketId));
        requireTicketAccess(caller, ticket);
        return sessionRepository.findByTicketIdOrderByCreatedAtDesc(ticketId);
    }

    /**
     * The call this user is currently in, if any.
     *
     * Exists so a page reload does not strand someone. The browser holds the only
     * copy of "I am on a call", so refreshing mid-conversation used to drop the UI
     * entirely while the server still counted the user as present — leaving them
     * invisibly occupying a room they could no longer see or hear.
     */
    @Transactional(readOnly = true)
    public Optional<CallSession> activeCallFor(Long userId) {
        for (CallParticipant p : participantRepository.findInRoomByUserId(
                userId, CallParticipant.Outcome.JOINED)) {
            Optional<CallSession> session = sessionRepository.findById(p.getCallSessionId());
            if (session.isPresent() && session.get().isLive()) {
                return session;
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<CallParticipant> missedFor(Long userId) {
        return participantRepository.findByUserIdAndOutcomeOrderByIdDesc(
                userId, CallParticipant.Outcome.MISSED);
    }

    @Transactional(readOnly = true)
    public Optional<CallSession> find(Long callId) {
        return sessionRepository.findById(callId);
    }

    // ─── Invite / add participant ─────────────────────────────────────────────

    /**
     * The users the caller may pull into this call, scoped by role.
     *
     * A customer sees only their own client's users; a BillOps admin sees the
     * ticket client's users plus the whole admin pool. The current initiator and
     * anyone already in the call are filtered out, so the picker only offers
     * people who can actually be added.
     */
    @Transactional(readOnly = true)
    public List<User> invitableUsers(CallUserPrincipal caller, Long callId) {
        CallSession session = requireSession(callId);
        requireParticipant(session, caller.getUserId());

        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        Long ticketClientId = (ticket != null && ticket.getClient() != null)
                ? ticket.getClient().getId()
                : null;

        // Insertion-ordered + de-duplicated: an admin can appear both as a client
        // user (unlikely) and in the admin pool, and we want one entry.
        LinkedHashMap<Long, User> eligible = new LinkedHashMap<>();
        if (caller.isBillOpsAdmin()) {
            if (ticketClientId != null) {
                for (User u : userRepository.findByClientId(ticketClientId)) {
                    eligible.put(u.getId(), u);
                }
            }
            for (User u : userRepository.findByRoleContainingIgnoreCase(ADMIN_ROLE_FRAGMENT)) {
                eligible.put(u.getId(), u);
            }
        } else if (caller.getClientId() != null) {
            for (User u : userRepository.findByClientId(caller.getClientId())) {
                eligible.put(u.getId(), u);
            }
        }

        // Exclude only people currently IN the room — not everyone the initial
        // fan-out rang. A customer→admin call rings the whole admin pool (so every
        // admin already has a participant row), and an admin→customer call rings
        // the whole client audience; removing all participants would hide exactly
        // the people you'd want to add. People rung earlier who never joined stay
        // eligible so they can be re-rung.
        eligible.remove(caller.getUserId());
        for (CallParticipant p : participantRepository.findByCallSessionId(callId)) {
            boolean inRoom = p.getOutcome() == CallParticipant.Outcome.JOINED && p.getLeftAt() == null;
            if (inRoom) {
                eligible.remove(p.getUserId());
            }
        }
        return new ArrayList<>(eligible.values());
    }

    /**
     * Pulls specific users into a live call: rings the ones who are online and
     * emails all of them a "join" invite. Every id is re-checked against the
     * caller's allowed set, so a crafted request cannot ring someone outside the
     * caller's scope.
     */
    @Transactional
    public void invite(CallUserPrincipal caller, Long callId, List<Long> userIds) {
        CallSession session = requireSession(callId);
        if (!session.isLive()) {
            throw new IllegalStateException("Call " + callId + " has ended");
        }
        requireParticipant(session, caller.getUserId());

        List<Long> targets = userIds == null ? List.of()
                : userIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (targets.isEmpty()) {
            return;
        }

        Set<Long> allowed = invitableUsers(caller, callId).stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        for (Long id : targets) {
            if (!allowed.contains(id)) {
                throw new CallAccessDeniedException("Not allowed to invite user " + id);
            }
        }

        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        List<CallParticipant> added = new ArrayList<>();
        List<String> adminEmails = new ArrayList<>();
        List<String> customerEmails = new ArrayList<>();

        for (User u : userRepository.findAllById(targets)) {
            boolean admin = u.getRole() != null && u.getRole().toUpperCase().contains(ADMIN_ROLE_FRAGMENT);

            Optional<CallParticipant> existing =
                    participantRepository.findByCallSessionIdAndUserId(callId, u.getId());
            CallParticipant p;
            if (existing.isPresent()) {
                p = existing.get();
                // Already in the room → nothing to do. Otherwise they were rung
                // earlier but never joined (or declined/left) — reset to pending
                // and ring again.
                if (p.getOutcome() == CallParticipant.Outcome.JOINED && p.getLeftAt() == null) {
                    continue;
                }
                p.setOutcome(null);
                p.setWasRung(true);
                p.setLeftAt(null);
            } else {
                p = new CallParticipant();
                p.setCallSessionId(callId);
                p.setUserId(u.getId());
                p.setUsername(u.getUsername());
                p.setDisplayName(u.getUsername());
                p.setParty(admin ? CallParticipant.Party.ADMIN : CallParticipant.Party.CUSTOMER);
                p.setWasRung(true);
            }
            added.add(p);

            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                (admin ? adminEmails : customerEmails).add(u.getEmail());
            }
        }
        if (added.isEmpty()) {
            return;
        }
        participantRepository.saveAll(added);

        // Ring the ones who are online, exactly like the initial fan-out.
        CallSignal incoming = buildSignal(CallSignal.Type.INCOMING, session, ticket);
        Set<Long> online = presenceService.filterOnline(
                added.stream().map(CallParticipant::getUserId).toList());
        for (Long userId : online) {
            notify(userId, incoming);
        }

        // Email everyone invited (durable "click to join"), with the detail route
        // that matches each invitee's side.
        boolean video = session.getMediaKind() == CallSession.MediaKind.VIDEO;
        String callerName = caller.getDisplayName();
        String subject = ticket != null ? ticket.getSubject() : null;
        if (!adminEmails.isEmpty()) {
            callNotificationService.sendCallInviteEmails(adminEmails, callerName, session.getTicketId(),
                    subject, "/billops-ticket-detail", video);
        }
        if (!customerEmails.isEmpty()) {
            callNotificationService.sendCallInviteEmails(customerEmails, callerName, session.getTicketId(),
                    subject, "/ticket-detail", video);
        }

        // Let everyone already in the room see the roster grow.
        CallSignal changed = buildSignal(CallSignal.Type.PARTICIPANT_CHANGED, session, ticket);
        changed.setReason("invited:" + added.size());
        broadcastToParticipants(callId, changed, null);

        log.info("User {} invited {} participant(s) to call {}", caller.getUserId(), added.size(), callId);
    }

    // ─── Internals ───────────────────────────────────────────────────────────

    /**
     * @return false if another thread already moved the call to a terminal state,
     *         in which case the caller must not send duplicate notifications
     */
    private boolean closeCall(CallSession session, CallSession.State newState, String reason,
            CallParticipant.Outcome undecidedOutcome, Ticket ticket) {
        int updated = sessionRepository.terminate(session.getId(), newState, reason,
                LocalDateTime.now(), LIVE_STATES);
        if (updated == 0) {
            return false;
        }
        if (undecidedOutcome != null) {
            participantRepository.settleUndecided(session.getId(), undecidedOutcome);
        }
        // No row may claim to occupy a room that no longer exists. Beyond tidiness
        // this is what stops a closed call from being resurrected: the sweeper and
        // the reload-recovery lookup both key off "present in room", and a leftover
        // present row would keep pointing at a dead session.
        participantRepository.evictRemaining(session.getId(), LocalDateTime.now(),
                CallParticipant.Outcome.JOINED);
        session.setState(newState);
        session.setEndReason(reason);

        // A call that ended MISSED rang people who may not be in the app at all;
        // email them so it does not vanish into the badge until they next log in.
        if (newState == CallSession.State.MISSED) {
            emailMissedRecipients(session, ticket);
        }
        return true;
    }

    /**
     * Notifies by email everyone settled as MISSED on this call.
     *
     * Only reached when the whole call missed — if any online colleague had
     * answered, those rung would carry a non-MISSED outcome and none of this runs,
     * so a group ring that someone picked up never spams the rest. Recipient data
     * is resolved here, on the transaction thread, and handed to the async sender
     * as plain values.
     */
    private void emailMissedRecipients(CallSession session, Ticket ticket) {
        try {
            List<Long> missedUserIds = participantRepository.findByCallSessionId(session.getId()).stream()
                    .filter(p -> p.isWasRung() && p.getOutcome() == CallParticipant.Outcome.MISSED)
                    .map(CallParticipant::getUserId)
                    .collect(Collectors.toList());
            if (missedUserIds.isEmpty()) {
                return;
            }

            List<String> emails = new ArrayList<>();
            for (User u : userRepository.findAllById(missedUserIds)) {
                if (u.getEmail() != null && !u.getEmail().isBlank()) {
                    emails.add(u.getEmail());
                }
            }
            if (emails.isEmpty()) {
                return;
            }

            // A customer's missed call was rung at admins, so their callback link
            // points at the admin ticket view; an admin's missed call points the
            // customer at their own.
            String ticketPath = session.getDirection() == CallSession.Direction.USER_TO_ADMIN
                    ? "/billops-ticket-detail"
                    : "/ticket-detail";
            String callerName = session.getInitiatorDisplayName() != null
                    ? session.getInitiatorDisplayName()
                    : session.getInitiatorUsername();
            boolean video = session.getMediaKind() == CallSession.MediaKind.VIDEO;

            callNotificationService.sendMissedCallEmails(emails, callerName, session.getTicketId(),
                    ticket != null ? ticket.getSubject() : null, ticketPath, video);
        } catch (Exception e) {
            // Never let a notification failure roll back the call's terminal state.
            log.warn("Could not queue missed-call emails for call {} on ticket {}: {}",
                    session.getId(), session.getTicketId(), e.getMessage());
        }
    }

    /**
     * Drops a call summary onto the ticket timeline once a real conversation ends.
     *
     * A call that never connected leaves nothing here — the call history strip and
     * the missed-call badge already cover unanswered rings, and a timeline entry for
     * every dropped ring would be noise. Only answered calls (a non-null answeredAt)
     * produce a note, so the ticket record reflects conversations that actually
     * happened.
     *
     * Written directly through the reply repository rather than the normal reply
     * path on purpose: a system-generated call record must not flip the ticket's
     * unread indicator or reset the response-SLA reminder clock.
     */
    private void recordCallSummaryNote(CallSession session, Ticket ticket, LocalDateTime endedAt) {
        if (ticket == null || session.getAnsweredAt() == null) {
            return;
        }
        try {
            User author = userRepository.findById(session.getInitiatorUserId()).orElse(null);
            if (author == null) {
                return;
            }

            String participants = participantRepository.findByCallSessionId(session.getId()).stream()
                    .filter(p -> p.getOutcome() == CallParticipant.Outcome.JOINED)
                    .map(p -> p.getDisplayName() != null ? p.getDisplayName() : p.getUsername())
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .collect(Collectors.joining(", "));

            // The end time is supplied rather than assumed to be "now": a call reaped
            // as abandoned actually ended at its last heartbeat, and using the sweep
            // time would pad every such call by up to the staleness window.
            String durationLabel = formatDuration(
                    Duration.between(session.getAnsweredAt(), endedAt));
            String mediaLabel = session.getMediaKind() == CallSession.MediaKind.VIDEO ? "Video" : "Audio";

            StringBuilder message = new StringBuilder("\uD83D\uDCDE <em>")
                    .append(mediaLabel).append(" call ended");
            if (durationLabel != null) {
                message.append(" · ").append(durationLabel);
            }
            if (!participants.isBlank()) {
                message.append(" · ").append(escapeHtml(participants));
            }
            message.append("</em>");

            TicketReply note = new TicketReply();
            note.setTicket(ticket);
            note.setAuthor(author);
            note.setMessage(message.toString());
            note.setCreatedAt(LocalDateTime.now());
            note.setInternal(false);
            ticketReplyRepository.save(note);
        } catch (Exception e) {
            // A missing note must never break call teardown — the call itself is done.
            log.warn("Could not record call summary note for call {} on ticket {}: {}",
                    session.getId(), session.getTicketId(), e.getMessage());
        }
    }

    /** "6m 32s", "45s", or null when the span is not meaningful. */
    private String formatDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return null;
        }
        long totalSeconds = duration.getSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    /**
     * The ticket UI renders reply bodies as HTML, so participant names — which are
     * user-controlled — are escaped before they land in the timeline.
     */
    private String escapeHtml(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void markJoined(Long callId, Long userId) {
        participantRepository.findByCallSessionIdAndUserId(callId, userId).ifPresent(p -> {
            p.setOutcome(CallParticipant.Outcome.JOINED);
            if (p.getJoinedAt() == null) {
                p.setJoinedAt(LocalDateTime.now());
            }
            p.setLeftAt(null);
            // Fresh liveness on entry, so the sweeper never sees a brand-new occupant
            // as one that has never checked in.
            p.setLastSeenAt(LocalDateTime.now());
            participantRepository.save(p);
        });
    }

    private CallSession requireSession(Long callId) {
        return sessionRepository.findById(callId)
                .orElseThrow(() -> new IllegalArgumentException("Call not found: " + callId));
    }

    /**
     * A call may only be accepted or joined by someone it was actually offered to.
     * Without this, knowing a call id would be enough to walk into another
     * client's support conversation.
     */
    private void requireWasInvited(CallSession session, Long userId) {
        if (session.getInitiatorUserId().equals(userId)) {
            return;
        }
        boolean invited = participantRepository.findByCallSessionIdAndUserId(session.getId(), userId)
                .map(CallParticipant::isWasRung)
                .orElse(false);
        if (!invited) {
            throw new CallAccessDeniedException(
                    "User " + userId + " was not invited to call " + session.getId());
        }
    }

    /**
     * Only someone already in the call may pull others in. Without this, knowing a
     * call id would let an outsider ring arbitrary users.
     */
    private void requireParticipant(CallSession session, Long userId) {
        if (session.getInitiatorUserId().equals(userId)) {
            return;
        }
        boolean member = participantRepository
                .findByCallSessionIdAndUserId(session.getId(), userId)
                .isPresent();
        if (!member) {
            throw new CallAccessDeniedException(
                    "User " + userId + " is not part of call " + session.getId());
        }
    }

    private void broadcastToParticipants(Long callId, CallSignal signal, Long excludeUserId) {
        for (CallParticipant p : participantRepository.findByCallSessionId(callId)) {
            if (excludeUserId != null && excludeUserId.equals(p.getUserId())) {
                continue;
            }
            notify(p.getUserId(), signal);
        }
    }

    /**
     * Routed through the relay rather than the broker directly, so a signal reaches
     * its recipient regardless of which instance holds their socket. See
     * {@link CallSignalRelay} for why that matters.
     */
    private void notify(Long userId, CallSignal signal) {
        signalRelay.send(userId, signal);
    }

    private CallSignal buildSignal(CallSignal.Type type, CallSession session, Ticket ticket) {
        CallSignal s = CallSignal.of(type, session.getId());
        s.setRoomName(session.getRoomName());
        s.setTicketId(session.getTicketId());
        s.setTicketSubject(ticket != null ? ticket.getSubject() : null);
        // Category and severity ride along so an admin can tell a billing question
        // from a production incident before deciding to pick up. The ring set is the
        // same either way; what the answerer knows when they answer should not be.
        s.setTicketCategory(ticket != null ? ticket.getCategory() : null);
        s.setTicketSeverity(ticket != null ? ticket.getSeverity() : null);
        s.setDirection(session.getDirection() != null ? session.getDirection().name() : null);
        s.setMediaKind(session.getMediaKind() != null ? session.getMediaKind().name() : null);
        s.setInitiatorUserId(session.getInitiatorUserId());
        s.setInitiatorName(session.getInitiatorDisplayName() != null
                ? session.getInitiatorDisplayName()
                : session.getInitiatorUsername());
        s.setAnsweredByUserId(session.getAnsweredByUserId());
        s.setAnsweredByName(session.getAnsweredByUsername());
        if (session.getState() == CallSession.State.RINGING && session.getRingExpiresAt() != null) {
            long secondsLeft = Duration.between(LocalDateTime.now(), session.getRingExpiresAt()).getSeconds();
            s.setRingExpiresInSeconds(Math.max(0, secondsLeft));
        }
        return s;
    }

    private CallSession.MediaKind parseMediaKind(String raw) {
        if (raw == null) {
            return CallSession.MediaKind.AUDIO;
        }
        return Arrays.stream(CallSession.MediaKind.values())
                .filter(k -> k.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElse(CallSession.MediaKind.AUDIO);
    }

    /** User has no separate display-name column, so the username is the label. */
    private String displayNameOf(User u) {
        return u.getUsername();
    }
}
