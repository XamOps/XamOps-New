package com.xammer.cloud.service.call;

import com.xammer.cloud.dto.call.CallSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Guards the wire format of the cross-instance signal bus.
 *
 * This relay is why a call works when the two parties' sockets land on different
 * instances — the in-memory STOMP broker only reaches sessions on the local JVM,
 * so without it a fan-out on one pod is invisible to everyone on another and the
 * call dies as a missed call with no error logged anywhere.
 *
 * That failure mode is entirely silent, which is what makes these tests worth
 * having: if the envelope stops round-tripping, nothing throws and nothing logs at
 * error level. Calls simply stop connecting for some fraction of users.
 */
@ExtendWith(MockitoExtension.class)
class CallSignalRelayTest {

    private static final String USER_QUEUE = "/queue/calls";

    @Mock private StringRedisTemplate redis;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks private CallSignalRelay relay;

    private CallSignal sampleSignal() {
        CallSignal signal = CallSignal.of(CallSignal.Type.INCOMING, 42L);
        signal.setTicketId(500L);
        signal.setTicketSubject("Unexpected charge on the June invoice");
        signal.setTicketCategory("Account and Billing");
        signal.setTicketSeverity("High");
        signal.setRoomName("ticket-500-a1b2c3d4");
        signal.setDirection("USER_TO_ADMIN");
        signal.setMediaKind("AUDIO");
        signal.setInitiatorUserId(7L);
        signal.setInitiatorName("Customer 7");
        signal.setRingExpiresInSeconds(38L);
        return signal;
    }

    @Test
    @DisplayName("a published envelope survives the round trip and reaches the right user")
    void envelopeRoundTrips() {
        relay.send(99L, sampleSignal());

        ArgumentCaptor<String> published = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(CallSignalRelay.CHANNEL), published.capture());

        // Feed exactly what went onto the channel back in, as another instance would
        // receive it.
        relay.onMessage(
                new DefaultMessage(CallSignalRelay.CHANNEL.getBytes(StandardCharsets.UTF_8),
                        published.getValue().getBytes(StandardCharsets.UTF_8)),
                null);

        ArgumentCaptor<CallSignal> delivered = ArgumentCaptor.forClass(CallSignal.class);
        verify(messagingTemplate).convertAndSendToUser(eq("99"), eq(USER_QUEUE), delivered.capture());

        CallSignal out = delivered.getValue();
        assertEquals(CallSignal.Type.INCOMING, out.getType());
        assertEquals(42L, out.getCallId());
        assertEquals(500L, out.getTicketId());
        assertEquals("Unexpected charge on the June invoice", out.getTicketSubject());
        // Category and severity are what let an admin judge a call before answering;
        // dropping them in transit would be invisible in every other test.
        assertEquals("Account and Billing", out.getTicketCategory());
        assertEquals("High", out.getTicketSeverity());
        assertEquals("ticket-500-a1b2c3d4", out.getRoomName());
        assertEquals("USER_TO_ADMIN", out.getDirection());
        assertEquals(38L, out.getRingExpiresInSeconds());
        assertEquals("Customer 7", out.getInitiatorName());
    }

    @Test
    @DisplayName("falls back to local delivery when Redis is unreachable")
    void fallsBackToLocalDeliveryOnPublishFailure() {
        doThrow(new RuntimeException("redis down"))
                .when(redis).convertAndSend(anyString(), any());

        relay.send(99L, sampleSignal());

        // A single-instance deployment stays fully working; a multi-instance one
        // degrades to local-only rather than dropping the ring entirely.
        verify(messagingTemplate).convertAndSendToUser(eq("99"), eq(USER_QUEUE), any(CallSignal.class));
    }

    @Test
    @DisplayName("a corrupt payload is dropped rather than thrown")
    void ignoresUnreadablePayload() {
        relay.onMessage(
                new DefaultMessage(CallSignalRelay.CHANNEL.getBytes(StandardCharsets.UTF_8),
                        "not json".getBytes(StandardCharsets.UTF_8)),
                null);

        // Throwing here would surface as an unhandled error on the Redis listener
        // thread, which serves every call on the instance.
        verify(messagingTemplate, org.mockito.Mockito.never())
                .convertAndSendToUser(anyString(), anyString(), any(CallSignal.class));
    }
}
