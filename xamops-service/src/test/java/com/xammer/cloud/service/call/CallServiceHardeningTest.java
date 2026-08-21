package com.xammer.cloud.service.call;

import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
