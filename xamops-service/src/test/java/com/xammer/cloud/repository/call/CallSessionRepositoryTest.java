package com.xammer.cloud.repository.call;

import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import com.xammer.cloud.repository.JpaSliceTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the transitions the call layer depends on being atomic. The
 * first-answer-wins claim in particular is the whole reason two admins hitting
 * Accept together cannot both end up owning the call.
 */
@DataJpaTest
@ContextConfiguration(classes = JpaSliceTestConfig.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.autoconfigure.exclude=",
        "spring.flyway.enabled=false"
})
class CallSessionRepositoryTest {

    private static final Set<CallSession.State> LIVE =
            Set.of(CallSession.State.RINGING, CallSession.State.ACTIVE);

    @Autowired
    CallSessionRepository sessionRepository;

    @Autowired
    CallParticipantRepository participantRepository;

    private CallSession ringingCall(long ticketId, LocalDateTime ringExpiry) {
        // Queue deadline well past the ring deadline, matching production: passing the
        // ring window only quiets the ringtone, it does not end the call.
        return ringingCall(ticketId, ringExpiry, ringExpiry.plusMinutes(10));
    }

    private CallSession ringingCall(long ticketId, LocalDateTime ringExpiry,
            LocalDateTime queueExpiry) {
        CallSession s = new CallSession();
        s.setRoomName("ticket-" + ticketId + "-" + System.nanoTime());
        s.setTicketId(ticketId);
        s.setClientId(1L);
        s.setAccountId("123456789012");
        s.setDirection(CallSession.Direction.USER_TO_ADMIN);
        s.setState(CallSession.State.RINGING);
        s.setMediaKind(CallSession.MediaKind.AUDIO);
        s.setInitiatorUserId(10L);
        s.setInitiatorUsername("customer@example.com");
        s.setInitiatorDisplayName("Customer");
        s.setCreatedAt(LocalDateTime.now());
        s.setRingExpiresAt(ringExpiry);
        s.setQueueExpiresAt(queueExpiry);
        return sessionRepository.save(s);
    }

    @Test
    void onlyTheFirstAccepterClaimsTheCall() {
        CallSession call = ringingCall(500L, LocalDateTime.now().plusSeconds(40));

        int firstAdmin = sessionRepository.claimAnswer(call.getId(), 20L, "admin.one",
                LocalDateTime.now(), CallSession.State.RINGING, CallSession.State.ACTIVE);
        int secondAdmin = sessionRepository.claimAnswer(call.getId(), 21L, "admin.two",
                LocalDateTime.now(), CallSession.State.RINGING, CallSession.State.ACTIVE);

        assertThat(firstAdmin).isEqualTo(1);
        assertThat(secondAdmin).isZero();

        CallSession reloaded = sessionRepository.findById(call.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(CallSession.State.ACTIVE);
        assertThat(reloaded.getAnsweredByUserId()).isEqualTo(20L);
        assertThat(reloaded.getAnsweredByUsername()).isEqualTo("admin.one");
    }

    @Test
    void terminateOnlyAppliesFromExpectedStates() {
        CallSession call = ringingCall(501L, LocalDateTime.now().plusSeconds(40));

        int closed = sessionRepository.terminate(call.getId(), CallSession.State.MISSED,
                "ring_timeout", LocalDateTime.now(), LIVE);
        int closedAgain = sessionRepository.terminate(call.getId(), CallSession.State.ENDED,
                "all_participants_left", LocalDateTime.now(), LIVE);

        assertThat(closed).isEqualTo(1);
        // A hangup racing the sweeper must not overwrite the recorded end reason.
        assertThat(closedAgain).isZero();

        CallSession reloaded = sessionRepository.findById(call.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(CallSession.State.MISSED);
        assertThat(reloaded.getEndReason()).isEqualTo("ring_timeout");
    }

    @Test
    void findsOnlyRingsReadyToStopRinging() {
        CallSession pastRingWindow = ringingCall(502L, LocalDateTime.now().minusSeconds(5));
        ringingCall(503L, LocalDateTime.now().plusMinutes(5));

        List<CallSession> toQuieten = sessionRepository
                .findByStateAndQueuedFalseAndRingExpiresAtBefore(
                        CallSession.State.RINGING, LocalDateTime.now());

        assertThat(toQuieten).extracting(CallSession::getId)
                .containsExactly(pastRingWindow.getId());
    }

    @Test
    void alreadyQueuedCallsAreNotEscalatedTwice() {
        CallSession call = ringingCall(506L, LocalDateTime.now().minusSeconds(5));
        call.setQueued(true);
        sessionRepository.save(call);

        List<CallSession> toQuieten = sessionRepository
                .findByStateAndQueuedFalseAndRingExpiresAtBefore(
                        CallSession.State.RINGING, LocalDateTime.now());

        // The ring deadline stays in the past forever, so without the queued flag the
        // sweeper would re-announce this call on every pass — telling the caller they
        // had been moved to the queue every ten seconds.
        assertThat(toQuieten).isEmpty();
    }

    @Test
    void passingTheRingWindowDoesNotMakeACallGiveUp() {
        // Ring window gone, queue window still open: this is a caller holding, and
        // closing it here is exactly the behaviour that cut calls off after 40 seconds.
        ringingCall(507L, LocalDateTime.now().minusSeconds(30),
                LocalDateTime.now().plusMinutes(9));
        CallSession outOfPatience = ringingCall(508L, LocalDateTime.now().minusMinutes(11),
                LocalDateTime.now().minusSeconds(5));

        List<CallSession> giveUp = sessionRepository.findByStateAndQueueExpiresAtBefore(
                CallSession.State.RINGING, LocalDateTime.now());

        assertThat(giveUp).extracting(CallSession::getId)
                .containsExactly(outOfPatience.getId());
    }

    @Test
    void liveCallLookupIgnoresFinishedCalls() {
        CallSession finished = ringingCall(504L, LocalDateTime.now().plusSeconds(40));
        sessionRepository.terminate(finished.getId(), CallSession.State.ENDED, "done",
                LocalDateTime.now(), LIVE);
        CallSession live = ringingCall(504L, LocalDateTime.now().plusSeconds(40));

        List<CallSession> found = sessionRepository.findByTicketIdAndStateInOrderByIdDesc(504L, LIVE);

        assertThat(found).extracting(CallSession::getId).containsExactly(live.getId());
    }

    @Test
    void settlingUndecidedLeavesDecidedParticipantsAlone() {
        CallSession call = ringingCall(505L, LocalDateTime.now().plusSeconds(40));

        CallParticipant declined = participant(call.getId(), 30L, "admin.declined");
        declined.setOutcome(CallParticipant.Outcome.DECLINED);
        CallParticipant silent = participant(call.getId(), 31L, "admin.silent");
        participantRepository.saveAll(List.of(declined, silent));

        int settled = participantRepository.settleUndecided(call.getId(), CallParticipant.Outcome.MISSED);

        assertThat(settled).isEqualTo(1);
        assertThat(participantRepository.findByCallSessionIdAndUserId(call.getId(), 30L))
                .get().extracting(CallParticipant::getOutcome)
                .isEqualTo(CallParticipant.Outcome.DECLINED);
        assertThat(participantRepository.findByCallSessionIdAndUserId(call.getId(), 31L))
                .get().extracting(CallParticipant::getOutcome)
                .isEqualTo(CallParticipant.Outcome.MISSED);
    }

    @Test
    void missedFeedReturnsOnlyMissedRows() {
        CallSession call = ringingCall(506L, LocalDateTime.now().plusSeconds(40));
        CallParticipant missed = participant(call.getId(), 40L, "admin.missed");
        missed.setOutcome(CallParticipant.Outcome.MISSED);
        CallParticipant joined = participant(call.getId(), 41L, "admin.joined");
        joined.setOutcome(CallParticipant.Outcome.JOINED);
        participantRepository.saveAll(List.of(missed, joined));

        assertThat(participantRepository.findByUserIdAndOutcomeOrderByIdDesc(
                40L, CallParticipant.Outcome.MISSED)).hasSize(1);
        assertThat(participantRepository.findByUserIdAndOutcomeOrderByIdDesc(
                41L, CallParticipant.Outcome.MISSED)).isEmpty();
    }

    private CallParticipant participant(Long callId, Long userId, String username) {
        CallParticipant p = new CallParticipant();
        p.setCallSessionId(callId);
        p.setUserId(userId);
        p.setUsername(username);
        p.setDisplayName(username);
        p.setParty(CallParticipant.Party.ADMIN);
        p.setWasRung(true);
        return p;
    }
}
