package com.xammer.cloud.repository.call;

import com.xammer.cloud.domain.call.CallParticipant;
import com.xammer.cloud.domain.call.CallSession;
import com.xammer.cloud.repository.JpaSliceTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
    void findsOnlyRingsPastTheirDeadline() {
        CallSession stale = ringingCall(502L, LocalDateTime.now().minusSeconds(5));
        ringingCall(503L, LocalDateTime.now().plusMinutes(5));

        List<CallSession> expired = sessionRepository.findByStateAndRingExpiresAtBefore(
                CallSession.State.RINGING, LocalDateTime.now());

        assertThat(expired).extracting(CallSession::getId).containsExactly(stale.getId());
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
