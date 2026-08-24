package com.xammer.cloud.service.call;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import com.xammer.cloud.dto.call.CallSignal;
import com.xammer.cloud.dto.call.CallTokenResponse;
import com.xammer.cloud.repository.TicketReplyRepository;
import com.xammer.cloud.repository.TicketRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.repository.call.CallParticipantRepository;
import com.xammer.cloud.repository.call.CallSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression cover for the call bugs that could strand a ticket or leak across
 * tenants.
 *
 * These are the cases that were reachable in normal use and had no test:
 *
 * <ul>
 * <li>a call abandoned by a closed tab stayed ACTIVE forever, and because a new
 * call always defers to a live session on the same ticket, that ticket could
 * never be called again;</li>
 * <li>a second person calling on a ticket that already had a live call was handed
 * the session without being made a participant, so their very next request — for
 * a join token — was rejected;</li>
 * <li>ticket-scoped call operations did not check the caller could see the
 * ticket;</li>
 * <li>a token could be obtained by someone who had declined, letting them enter
 * the room with no roster event.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallServiceHardeningTest {

    private static final long CLIENT_A = 10L;
    private static final long CLIENT_B = 20L;
    private static final long TICKET_ID = 500L;

    @Mock private CallSessionRepository sessionRepository;
    @Mock private CallParticipantRepository participantRepository;
    @Mock private TicketRepository ticketRepository;
    @Mock private TicketReplyRepository ticketReplyRepository;
    @Mock private UserRepository userRepository;
    @Mock private CallRecipientResolver recipientResolver;
    @Mock private CallPresenceService presenceService;
    @Mock private LiveKitTokenService liveKitTokenService;
    @Mock private CallNotificationService callNotificationService;
    @Mock private CallSignalRelay signalRelay;

    private CallService service;

    @BeforeEach
    void setUp() {
        service = new CallService(sessionRepository, participantRepository, ticketRepository,
                ticketReplyRepository, userRepository, recipientResolver, presenceService,
                liveKitTokenService, callNotificationService, signalRelay);

        // @Value fields are not populated outside a Spring context.
        ReflectionTestUtils.setField(service, "ringSeconds", 40L);
        ReflectionTestUtils.setField(service, "activeStaleSeconds", 120L);
        ReflectionTestUtils.setField(service, "maxSessionHours", 12L);

        when(liveKitTokenService.isConfigured()).thenReturn(true);
        when(liveKitTokenService.getLivekitUrl()).thenReturn("wss://livekit.test");
        when(liveKitTokenService.createJoinToken(any(), any(), any(), eq(true))).thenReturn("jwt");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Ticket ticket(long clientId) {
        Client client = new Client();
        client.setId(clientId);
        Ticket t = new Ticket();
        t.setId(TICKET_ID);
        t.setClient(client);
        t.setSubject("Billing discrepancy");
        t.setCategory("Account and Billing");
        return t;
    }

    private CallSession session(long id, CallSession.State state, long initiatorUserId) {
        CallSession s = new CallSession();
        s.setId(id);
        s.setTicketId(TICKET_ID);
        s.setRoomName("ticket-" + TICKET_ID + "-abc12345");
        s.setState(state);
        s.setDirection(CallSession.Direction.ADMIN_TO_USER);
        s.setMediaKind(CallSession.MediaKind.AUDIO);
        s.setInitiatorUserId(initiatorUserId);
        s.setInitiatorUsername("agent");
        s.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        if (state == CallSession.State.ACTIVE) {
            s.setAnsweredAt(LocalDateTime.now().minusMinutes(4));
        }
        return s;
    }

    private CallParticipant participant(long userId, CallParticipant.Outcome outcome, boolean wasRung) {
        CallParticipant p = new CallParticipant();
        p.setId(userId * 100);
        p.setCallSessionId(1L);
        p.setUserId(userId);
        p.setUsername("u" + userId);
        p.setParty(CallParticipant.Party.CUSTOMER);
        p.setWasRung(wasRung);
        p.setOutcome(outcome);
        if (outcome == CallParticipant.Outcome.JOINED) {
            p.setJoinedAt(LocalDateTime.now().minusMinutes(3));
            p.setLastSeenAt(LocalDateTime.now().minusMinutes(3));
        }
        return p;
    }

    private CallUserPrincipal customer(long userId, long clientId) {
        return new CallUserPrincipal(userId, "cust" + userId, "Customer " + userId,
                Set.of("ROLE_XAMOPS"), clientId);
    }

    private CallUserPrincipal admin(long userId) {
        return new CallUserPrincipal(userId, "admin" + userId, "Admin " + userId,
                Set.of("ROLE_BILLOPS_ADMIN"), null);
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    // ─── Abandoned calls ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("abandoned call sweep")
    class AbandonedSweep {

        @Test
        @DisplayName("closes an ACTIVE call whose occupants have all stopped heartbeating")
        void closesSilentSession() {
            CallSession stale = session(1L, CallSession.State.ACTIVE, 99L);
            when(sessionRepository.findByStateAndAnsweredAtBefore(eq(CallSession.State.ACTIVE), any()))
                    .thenReturn(List.of(stale));
            // Nobody reported in within the window.
            when(participantRepository.findSessionIdsWithLiveOccupant(anyCollection(), any(), any()))
                    .thenReturn(List.of());
            when(sessionRepository.findByStateInAndCreatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of());
            when(sessionRepository.findById(1L)).thenReturn(Optional.of(stale));
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(participantRepository.findByCallSessionId(1L)).thenReturn(List.of());
            when(sessionRepository.terminate(eq(1L), any(), any(), any(), anyCollection())).thenReturn(1);

            service.sweepAbandonedCalls();

            // An answered call that was abandoned ended; it is not a missed call.
            verify(sessionRepository).terminate(eq(1L), eq(CallSession.State.ENDED),
                    eq("abandoned_no_heartbeat"), any(), anyCollection());
            // And no row is left claiming to occupy a room that no longer exists —
            // which is what would otherwise keep the ticket locked.
            verify(participantRepository).evictRemaining(eq(1L), any(),
                    eq(CallParticipant.Outcome.JOINED));
        }

        @Test
        @DisplayName("leaves a call alone while somebody is still checking in")
        void spacesLiveSession() {
            CallSession live = session(2L, CallSession.State.ACTIVE, 99L);
            when(sessionRepository.findByStateAndAnsweredAtBefore(eq(CallSession.State.ACTIVE), any()))
                    .thenReturn(List.of(live));
            when(participantRepository.findSessionIdsWithLiveOccupant(anyCollection(), any(), any()))
                    .thenReturn(List.of(2L));
            when(sessionRepository.findByStateInAndCreatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of());

            service.sweepAbandonedCalls();

            verify(sessionRepository, never()).terminate(anyLong(), any(), any(), any(), anyCollection());
        }

        @Test
        @DisplayName("an unanswered session caught by the age cap becomes a missed call")
        void ageCappedRingBecomesMissed() {
            CallSession forgotten = session(3L, CallSession.State.RINGING, 99L);
            when(sessionRepository.findByStateAndAnsweredAtBefore(eq(CallSession.State.ACTIVE), any()))
                    .thenReturn(List.of());
            when(sessionRepository.findByStateInAndCreatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of(forgotten));
            when(sessionRepository.findById(3L)).thenReturn(Optional.of(forgotten));
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(participantRepository.findByCallSessionId(3L)).thenReturn(List.of());
            when(sessionRepository.terminate(eq(3L), any(), any(), any(), anyCollection())).thenReturn(1);

            service.sweepAbandonedCalls();

            verify(sessionRepository).terminate(eq(3L), eq(CallSession.State.MISSED),
                    any(), any(), anyCollection());
            // Recipients who were rung still get the missed-call record they are owed.
            verify(participantRepository).settleUndecided(3L, CallParticipant.Outcome.MISSED);
        }
    }

    // ─── Joining a call already in progress ──────────────────────────────────

    @Nested
    @DisplayName("second caller on a ticket with a live call")
    class SecondCaller {

        @Test
        @DisplayName("is added to the room instead of being refused a token")
        void attachesAsParticipant() {
            CallSession live = session(4L, CallSession.State.ACTIVE, 99L);
            Ticket t = ticket(CLIENT_A);
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(t));
            when(sessionRepository.findByTicketIdAndStateInOrderByIdDesc(eq(TICKET_ID), anyCollection()))
                    .thenReturn(List.of(live));
            when(participantRepository.findByCallSessionIdAndUserId(4L, 7L)).thenReturn(Optional.empty());
            when(participantRepository.findByCallSessionId(4L)).thenReturn(List.of());

            CallSession returned = service.initiate(customer(7L, CLIENT_A), TICKET_ID, "AUDIO");

            assertSame(live, returned, "the existing room is reused, not a competing one");

            ArgumentCaptor<CallParticipant> saved = ArgumentCaptor.forClass(CallParticipant.class);
            verify(participantRepository).save(saved.capture());
            CallParticipant added = saved.getValue();

            assertEquals(7L, added.getUserId());
            assertEquals(CallParticipant.Outcome.JOINED, added.getOutcome());
            assertTrue(added.isInRoom(), "must be present, or issueToken will refuse them");
            assertNotNull(added.getLastSeenAt(), "seeded so the sweeper does not reap the call");
            // Never rung, so this must not surface in their missed-call feed if the
            // call later expires.
            assertTrue(!added.isWasRung());
        }

        @Test
        @DisplayName("can then obtain a join token")
        void canGetTokenAfterAttaching() {
            CallSession live = session(5L, CallSession.State.ACTIVE, 99L);
            when(sessionRepository.findById(5L)).thenReturn(Optional.of(live));
            when(participantRepository.findByCallSessionIdAndUserId(5L, 7L))
                    .thenReturn(Optional.of(participant(7L, CallParticipant.Outcome.JOINED, false)));

            CallTokenResponse token = service.issueToken(customer(7L, CLIENT_A), 5L);

            assertEquals("jwt", token.getToken());
            assertEquals("wss://livekit.test", token.getLivekitUrl());
        }
    }

    // ─── Authorization ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("ticket access")
    class TicketAccess {

        @Test
        @DisplayName("a customer cannot start a call on another tenant's ticket")
        void deniesCrossTenantInitiate() {
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));

            assertThrows(CallService.CallAccessDeniedException.class,
                    () -> service.initiate(customer(7L, CLIENT_B), TICKET_ID, "AUDIO"));

            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a customer cannot read another tenant's call history")
        void deniesCrossTenantHistory() {
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));

            assertThrows(CallService.CallAccessDeniedException.class,
                    () -> service.historyForTicket(customer(7L, CLIENT_B), TICKET_ID));
        }

        @Test
        @DisplayName("a BillOps admin reaches any tenant's ticket, as support must")
        void adminReachesAnyTicket() {
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(sessionRepository.findByTicketIdOrderByCreatedAtDesc(TICKET_ID)).thenReturn(List.of());

            assertEquals(List.of(), service.historyForTicket(admin(1L), TICKET_ID));
        }
    }

    // ─── Token entitlement ───────────────────────────────────────────────────

    @Nested
    @DisplayName("join tokens")
    class Tokens {

        @Test
        @DisplayName("are refused to someone who declined, so they cannot slip in unannounced")
        void refusesDeclinedParticipant() {
            CallSession live = session(6L, CallSession.State.ACTIVE, 99L);
            when(sessionRepository.findById(6L)).thenReturn(Optional.of(live));
            // wasRung stays true after a decline, which is exactly why an
            // "was invited" check was not enough here.
            when(participantRepository.findByCallSessionIdAndUserId(6L, 8L))
                    .thenReturn(Optional.of(participant(8L, CallParticipant.Outcome.DECLINED, true)));

            assertThrows(CallService.CallAccessDeniedException.class,
                    () -> service.issueToken(customer(8L, CLIENT_A), 6L));
        }

        @Test
        @DisplayName("are refused to someone who has left the room")
        void refusesDepartedParticipant() {
            CallSession live = session(7L, CallSession.State.ACTIVE, 99L);
            CallParticipant gone = participant(9L, CallParticipant.Outcome.JOINED, true);
            gone.setLeftAt(LocalDateTime.now());
            when(sessionRepository.findById(7L)).thenReturn(Optional.of(live));
            when(participantRepository.findByCallSessionIdAndUserId(7L, 9L)).thenReturn(Optional.of(gone));

            assertThrows(CallService.CallAccessDeniedException.class,
                    () -> service.issueToken(customer(9L, CLIENT_A), 7L));
        }
    }

    // ─── One call at a time (Issue 2) ────────────────────────────────────────

    @Nested
    @DisplayName("one call at a time")
    class OneCallAtATime {

        /** Marks this user as currently occupying {@code liveCallId}. */
        private void onCall(long userId, Long liveCallId) {
            when(participantRepository.findLiveCallIdsByUserId(eq(userId), any(), anyCollection()))
                    .thenReturn(liveCallId == null ? List.of() : List.of(liveCallId));
        }

        @Test
        @DisplayName("accepting a second call is refused, so two tabs cannot land in two rooms")
        void acceptRefusedWhenAlreadyOnAnotherCall() {
            CallSession other = session(30L, CallSession.State.RINGING, 99L);
            when(sessionRepository.findById(30L)).thenReturn(Optional.of(other));
            when(participantRepository.findByCallSessionIdAndUserId(30L, 8L))
                    .thenReturn(Optional.of(participant(8L, null, true)));
            onCall(8L, 21L); // already in call 21

            assertThrows(CallService.AlreadyOnAnotherCallException.class,
                    () -> service.accept(admin(8L), 30L));

            // Rejected before the claim, so the ring is untouched and still answerable
            // by a colleague who is actually free.
            verify(sessionRepository, never()).claimAnswer(anyLong(), anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("re-accepting the call you are already in is allowed")
        void acceptAllowedForSameCall() {
            CallSession same = session(21L, CallSession.State.RINGING, 99L);
            when(sessionRepository.findById(21L)).thenReturn(Optional.of(same));
            when(participantRepository.findByCallSessionIdAndUserId(21L, 8L))
                    .thenReturn(Optional.of(participant(8L, null, true)));
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(participantRepository.findByCallSessionId(21L)).thenReturn(List.of());
            when(sessionRepository.claimAnswer(eq(21L), eq(8L), any(), any(), any(), any()))
                    .thenReturn(1);
            onCall(8L, 21L); // the live call IS this one

            assertTrue(service.accept(admin(8L), 21L));
        }

        @Test
        @DisplayName("joining a second call is refused")
        void joinRefusedWhenAlreadyOnAnotherCall() {
            CallSession other = session(31L, CallSession.State.ACTIVE, 99L);
            when(sessionRepository.findById(31L)).thenReturn(Optional.of(other));
            when(participantRepository.findByCallSessionIdAndUserId(31L, 8L))
                    .thenReturn(Optional.of(participant(8L, null, true)));
            onCall(8L, 21L);

            assertThrows(CallService.AlreadyOnAnotherCallException.class,
                    () -> service.join(admin(8L), 31L));
        }

        @Test
        @DisplayName("starting a brand new call while on one is refused")
        void initiateRefusedWhenAlreadyOnAnotherCall() {
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(sessionRepository.findByTicketIdAndStateInOrderByIdDesc(eq(TICKET_ID), anyCollection()))
                    .thenReturn(List.of());
            onCall(8L, 21L);

            assertThrows(CallService.AlreadyOnAnotherCallException.class,
                    () -> service.initiate(admin(8L), TICKET_ID, "AUDIO"));

            verify(sessionRepository, never()).save(any());
        }
    }

    // ─── Call waiting (Issue 1) ──────────────────────────────────────────────

    @Nested
    @DisplayName("call waiting")
    class CallWaiting {

        @Test
        @DisplayName("a busy recipient is still rung, but flagged so their UI does not interrupt")
        void busyRecipientIsFlaggedWaiting() {
            Ticket t = ticket(CLIENT_A);
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(t));
            when(sessionRepository.findByTicketIdAndStateInOrderByIdDesc(eq(TICKET_ID), anyCollection()))
                    .thenReturn(List.of());
            // Caller is free.
            when(participantRepository.findLiveCallIdsByUserId(eq(7L), any(), anyCollection()))
                    .thenReturn(List.of());

            User freeAdmin = user(101L, "meera");
            User busyAdmin = user(102L, "raj");
            when(recipientResolver.resolveAdminPool(any(), eq(7L)))
                    .thenReturn(List.of(freeAdmin, busyAdmin));

            when(sessionRepository.save(any(CallSession.class))).thenAnswer(inv -> {
                CallSession s = inv.getArgument(0);
                s.setId(40L);
                return s;
            });
            when(presenceService.filterOnline(anyCollection()))
                    .thenReturn(new LinkedHashSet<>(List.of(101L, 102L)));
            // Raj is mid-call on something else.
            when(participantRepository.findUserIdsOnLiveCall(anyCollection(), any(), anyCollection()))
                    .thenReturn(List.of(102L));

            service.initiate(customer(7L, CLIENT_A), TICKET_ID, "AUDIO");

            ArgumentCaptor<Long> targets = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<CallSignal> signals = ArgumentCaptor.forClass(CallSignal.class);
            verify(signalRelay, org.mockito.Mockito.atLeast(3))
                    .send(targets.capture(), signals.capture());

            CallSignal toFree = signalFor(targets.getAllValues(), signals.getAllValues(),
                    101L, CallSignal.Type.INCOMING);
            CallSignal toBusy = signalFor(targets.getAllValues(), signals.getAllValues(),
                    102L, CallSignal.Type.INCOMING);
            CallSignal toCaller = signalFor(targets.getAllValues(), signals.getAllValues(),
                    7L, CallSignal.Type.RINGING);

            assertNotNull(toFree, "the free admin must be rung");
            assertNotNull(toBusy, "the busy admin must still be rung, not skipped");
            assertNotNull(toCaller, "the caller must be told the ring started");

            assertFalse(toFree.isWaiting(), "a free admin gets a normal interrupting ring");
            assertTrue(toBusy.isWaiting(),
                    "a busy admin's ring must be marked waiting, or their UI mislabels it");

            // The caller is told how many can actually pick up, not how many have a tab
            // open — one of the two is mid-call.
            assertEquals(1, toCaller.getActiveParticipants());
        }

        private CallSignal signalFor(List<Long> targets, List<CallSignal> signals,
                Long userId, CallSignal.Type type) {
            for (int i = 0; i < targets.size(); i++) {
                if (userId.equals(targets.get(i)) && signals.get(i).getType() == type) {
                    return signals.get(i);
                }
            }
            return null;
        }
    }

    // ─── Queue behaviour ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("queue")
    class Queue {

        @Test
        @DisplayName("nobody online holds the caller in the queue instead of hanging up on them")
        void noAgentsOnlineHoldsInQueue() {
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(sessionRepository.findByTicketIdAndStateInOrderByIdDesc(eq(TICKET_ID), anyCollection()))
                    .thenReturn(List.of());
            when(participantRepository.findLiveCallIdsByUserId(eq(7L), any(), anyCollection()))
                    .thenReturn(List.of());
            when(recipientResolver.resolveAdminPool(any(), eq(7L)))
                    .thenReturn(List.of(user(101L, "meera")));
            when(sessionRepository.save(any(CallSession.class))).thenAnswer(inv -> {
                CallSession s = inv.getArgument(0);
                s.setId(50L);
                return s;
            });
            // Nobody has a tab open.
            when(presenceService.filterOnline(anyCollection())).thenReturn(Set.of());

            CallSession session = service.initiate(customer(7L, CLIENT_A), TICKET_ID, "AUDIO");

            // This used to close the call on the spot with "nobody is available", which
            // is what made a call die within seconds of dialling.
            assertEquals(CallSession.State.RINGING, session.getState());
            verify(sessionRepository, never()).terminate(anyLong(), any(), any(), any(), anyCollection());
            assertNotNull(session.getQueueExpiresAt(), "the caller needs a wait deadline");
            assertTrue(session.getQueueExpiresAt().isAfter(session.getRingExpiresAt()),
                    "holding must outlast the ringtone, or there is no queue at all");
        }

        @Test
        @DisplayName("passing the ring window quiets the call but keeps it answerable")
        void ringWindowElapsedMovesToQueue() {
            CallSession holding = session(60L, CallSession.State.RINGING, 7L);
            holding.setRingExpiresAt(LocalDateTime.now().minusSeconds(5));
            holding.setQueueExpiresAt(LocalDateTime.now().plusMinutes(9));

            when(sessionRepository.findByStateAndQueuedFalseAndRingExpiresAtBefore(
                    eq(CallSession.State.RINGING), any())).thenReturn(List.of(holding));
            when(sessionRepository.findByStateAndQueueExpiresAtBefore(
                    eq(CallSession.State.RINGING), any())).thenReturn(List.of());
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(participantRepository.findByCallSessionId(60L)).thenReturn(List.of());

            service.sweepExpiredRings();

            // Quietened, not closed.
            verify(sessionRepository, never()).terminate(anyLong(), any(), any(), any(), anyCollection());
            assertTrue(holding.isQueued(), "must be marked queued so it is not re-announced");

            // The caller is the one who most needs to hear "you are still holding".
            ArgumentCaptor<Long> targets = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<CallSignal> signals = ArgumentCaptor.forClass(CallSignal.class);
            verify(signalRelay, org.mockito.Mockito.atLeastOnce())
                    .send(targets.capture(), signals.capture());
            assertTrue(
                    signals.getAllValues().stream()
                            .anyMatch(s -> s.getType() == CallSignal.Type.QUEUED),
                    "recipients and caller must be told the call moved to the queue");
            assertTrue(targets.getAllValues().contains(7L), "the caller must be notified");
        }

        @Test
        @DisplayName("only the queue deadline gives up and records a missed call")
        void queueWindowElapsedRecordsMissed() {
            CallSession outOfPatience = session(61L, CallSession.State.RINGING, 7L);
            outOfPatience.setQueued(true);
            outOfPatience.setQueueExpiresAt(LocalDateTime.now().minusSeconds(5));

            when(sessionRepository.findByStateAndQueuedFalseAndRingExpiresAtBefore(
                    eq(CallSession.State.RINGING), any())).thenReturn(List.of());
            when(sessionRepository.findByStateAndQueueExpiresAtBefore(
                    eq(CallSession.State.RINGING), any())).thenReturn(List.of(outOfPatience));
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(participantRepository.findByCallSessionId(61L)).thenReturn(List.of());
            when(sessionRepository.terminate(eq(61L), any(), any(), any(), anyCollection()))
                    .thenReturn(1);

            service.sweepExpiredRings();

            verify(sessionRepository).terminate(eq(61L), eq(CallSession.State.MISSED),
                    eq("queue_timeout"), any(), anyCollection());
            verify(participantRepository).settleUndecided(61L, CallParticipant.Outcome.MISSED);
        }
    }

    // ─── Hang up ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("leaving")
    class Leaving {

        @Test
        @DisplayName("the initiator hanging up an unanswered call cancels it for everyone")
        void ringingInitiatorLeaveCancels() {
            CallSession ringing = session(8L, CallSession.State.RINGING, 7L);
            when(sessionRepository.findById(8L)).thenReturn(Optional.of(ringing));
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));
            when(participantRepository.findByCallSessionId(8L)).thenReturn(List.of());
            when(sessionRepository.terminate(eq(8L), any(), any(), any(), anyCollection())).thenReturn(1);

            service.leave(customer(7L, CLIENT_A), 8L);

            // The client no longer has to choose between /leave and /cancel — and so can
            // no longer choose wrong.
            verify(sessionRepository).terminate(eq(8L), eq(CallSession.State.CANCELLED),
                    eq("cancelled_by_initiator"), any(), anyCollection());
        }

        @Test
        @DisplayName("someone who joined a ringing call only removes themselves")
        void nonInitiatorLeaveDoesNotCancel() {
            CallSession ringing = session(9L, CallSession.State.RINGING, 99L);
            CallParticipant me = participant(7L, CallParticipant.Outcome.JOINED, false);
            when(sessionRepository.findById(9L)).thenReturn(Optional.of(ringing));
            when(participantRepository.findByCallSessionIdAndUserId(9L, 7L)).thenReturn(Optional.of(me));
            when(participantRepository.findByCallSessionId(9L))
                    .thenReturn(List.of(participant(99L, CallParticipant.Outcome.JOINED, false)));
            when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket(CLIENT_A)));

            service.leave(customer(7L, CLIENT_A), 9L);

            verify(sessionRepository, never()).terminate(anyLong(), eq(CallSession.State.CANCELLED),
                    any(), any(), anyCollection());
        }
    }
}
