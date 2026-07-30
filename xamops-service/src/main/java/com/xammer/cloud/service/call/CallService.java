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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String USER_QUEUE = "/queue/calls";

    private static final Set<CallSession.State> LIVE_STATES =
            Set.of(CallSession.State.RINGING, CallSession.State.ACTIVE);

    /** Matches ROLE_BILLOPS_ADMIN and ROLE_BILLOPS_ADMIN_SUB alike. */
    private static final String ADMIN_ROLE_FRAGMENT = "BILLOPS_ADMIN";

    private final CallSessionRepository sessionRepository;
    private final CallParticipantRepository participantRepository;
    private final TicketRepository ticketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final UserRepository userRepository;
    private final CallRecipientResolver recipientResolver;
    private final CallPresenceService presenceService;
    private final LiveKitTokenService liveKitTokenService;
    private final CallNotificationService callNotificationService;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${xamops.calls.ring-seconds:40}")
    private long ringSeconds;

    public CallService(CallSessionRepository sessionRepository,
            CallParticipantRepository participantRepository,
            TicketRepository ticketRepository,
            TicketReplyRepository ticketReplyRepository,
            UserRepository userRepository,
            CallRecipientResolver recipientResolver,
            CallPresenceService presenceService,
            LiveKitTokenService liveKitTokenService,
            CallNotificationService callNotificationService,
            SimpMessagingTemplate messagingTemplate) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.ticketRepository = ticketRepository;
        this.ticketReplyRepository = ticketReplyRepository;
        this.userRepository = userRepository;
        this.recipientResolver = recipientResolver;
        this.presenceService = presenceService;
        this.liveKitTokenService = liveKitTokenService;
        this.callNotificationService = callNotificationService;
        this.messagingTemplate = messagingTemplate;
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

        List<CallSession> existing = sessionRepository
                .findByTicketIdAndStateInOrderByIdDesc(ticketId, LIVE_STATES);
        if (!existing.isEmpty()) {
            CallSession live = existing.get(0);
            log.debug("Ticket {} already has live call {}; caller {} joins it instead",
                    ticketId, live.getId(), caller.getUserId());
            return live;
        }

        boolean callerIsAdmin = caller.isBillOpsAdmin();
        CallSession.Direction direction = callerIsAdmin
                ? CallSession.Direction.ADMIN_TO_USER
                : CallSession.Direction.USER_TO_ADMIN;

        List<User> recipients = callerIsAdmin
                ? recipientResolver.resolveTicketAudience(ticket, caller.getUserId())
                : recipientResolver.resolveAdminPool(caller.getUserId());

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

    /** Issues a LiveKit token for someone entitled to be in the room. */
    @Transactional(readOnly = true)
    public CallTokenResponse issueToken(CallUserPrincipal user, Long callId) {
        CallSession session = requireSession(callId);
        if (!session.isLive()) {
            throw new IllegalStateException("Call " + callId + " has ended");
        }
        requireWasInvited(session, user.getUserId());

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
            throw new IllegalStateException("Only the initiator can cancel call " + callId);
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
        participantRepository.findByCallSessionIdAndUserId(callId, user.getUserId())
                .ifPresent(p -> {
                    p.setLeftAt(LocalDateTime.now());
                    participantRepository.save(p);
                });

        boolean anyoneStillIn = participantRepository.findByCallSessionId(callId).stream()
                .anyMatch(p -> p.getOutcome() == CallParticipant.Outcome.JOINED && p.getLeftAt() == null);

        Ticket ticket = ticketRepository.findById(session.getTicketId()).orElse(null);
        if (anyoneStillIn) {
            CallSignal changed = buildSignal(CallSignal.Type.PARTICIPANT_CHANGED, session, ticket);
            changed.setReason("left:" + user.getDisplayName());
            broadcastToParticipants(callId, changed, user.getUserId());
            return;
        }

        if (closeCall(session, CallSession.State.ENDED, "all_participants_left", null, ticket)) {
            recordCallSummaryNote(session, ticket);
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
     */
    @Scheduled(fixedDelayString = "${xamops.calls.sweep-interval-ms:10000}")
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

    // ─── Queries ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CallSession> historyForTicket(Long ticketId) {
        return sessionRepository.findByTicketIdOrderByCreatedAtDesc(ticketId);
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
                throw new IllegalStateException("Not allowed to invite user " + id);
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
    private void recordCallSummaryNote(CallSession session, Ticket ticket) {
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

            String durationLabel = formatDuration(
                    Duration.between(session.getAnsweredAt(), LocalDateTime.now()));
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
            throw new IllegalStateException(
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
            throw new IllegalStateException(
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

    private void notify(Long userId, CallSignal signal) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), USER_QUEUE, signal);
        } catch (Exception e) {
            log.warn("Failed to push call signal {} to user {}: {}",
                    signal.getType(), userId, e.getMessage());
        }
    }

    private CallSignal buildSignal(CallSignal.Type type, CallSession session, Ticket ticket) {
        CallSignal s = CallSignal.of(type, session.getId());
        s.setRoomName(session.getRoomName());
        s.setTicketId(session.getTicketId());
        s.setTicketSubject(ticket != null ? ticket.getSubject() : null);
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
