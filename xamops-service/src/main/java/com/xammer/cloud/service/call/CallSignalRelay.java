package com.xammer.cloud.service.call;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xammer.cloud.dto.call.CallSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Delivers a call signal to a user wherever they happen to be connected.
 *
 * The STOMP broker here is the in-memory simple broker, so
 * {@code convertAndSendToUser} only ever reaches sessions attached to the JVM
 * that calls it. That is fine on one instance and silently catastrophic on more
 * than one: a customer whose socket landed on pod A rings admins, the fan-out
 * runs on pod A, and every admin connected to pod B is simply never told. The
 * call then expires as a missed call with no error anywhere. Sticky sessions do
 * not help — the two parties are different users, so pinning each of them to a
 * pod does not put them on the *same* pod.
 *
 * So signals go out over Redis pub/sub instead, and every instance delivers to
 * its own local sessions on receipt. The publishing instance does not shortcut to
 * a local send: it receives its own message back through the same subscription,
 * which keeps delivery to exactly one code path rather than two that can drift.
 *
 * Redis being unavailable must not take calling down with it, so a failed publish
 * falls back to local delivery. On a single-instance deployment that is complete;
 * on several it degrades to the old behaviour, which is the best available answer
 * when the shared bus is gone.
 */
@Service
public class CallSignalRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CallSignalRelay.class);

    /** Pub/sub channel every instance publishes to and subscribes on. */
    public static final String CHANNEL = "xamops:call:signal";

    private static final String USER_QUEUE = "/queue/calls";

    /**
     * Plain mapper, deliberately not the shared {@code redisObjectMapper}: that one
     * has default typing switched on for session serialisation, which would embed
     * Java class names in the envelope and couple the wire format to package names.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;

    public CallSignalRelay(StringRedisTemplate redis, SimpMessagingTemplate messagingTemplate) {
        this.redis = redis;
        this.messagingTemplate = messagingTemplate;
    }

    /** Envelope: the signal plus who it is for. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Envelope {
        private Long userId;
        private CallSignal signal;

        public Envelope() {
        }

        public Envelope(Long userId, CallSignal signal) {
            this.userId = userId;
            this.signal = signal;
        }

        public Long getUserId() {
            return userId;
        }

        public void setUserId(Long userId) {
            this.userId = userId;
        }

        public CallSignal getSignal() {
            return signal;
        }

        public void setSignal(CallSignal signal) {
            this.signal = signal;
        }
    }

    /**
     * Fans a signal out to whichever instance holds this user's socket.
     *
     * Never throws: a signalling hiccup must not roll back the call state
     * transition that produced it. A caller that has already committed a call to
     * ACTIVE cannot be un-committed because a notification failed.
     */
    public void send(Long userId, CallSignal signal) {
        if (userId == null || signal == null) {
            return;
        }
        try {
            redis.convertAndSend(CHANNEL, mapper.writeValueAsString(new Envelope(userId, signal)));
        } catch (Exception e) {
            log.warn("Call signal {} for user {} could not be published to Redis ({}); "
                    + "delivering locally only", signal.getType(), userId, e.getMessage());
            deliverLocally(userId, signal);
        }
    }

    /**
     * Handles a signal arriving from any instance, including this one.
     *
     * Implements {@link MessageListener} directly and decodes the bytes here rather
     * than going through {@code MessageListenerAdapter}. The adapter deserialises the
     * body with its own serializer, which defaults to JDK serialization — and this
     * payload is JSON written by a {@link StringRedisTemplate}, so every message
     * would have failed to decode. Reading the raw bytes leaves no room for the two
     * ends to disagree about the format.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Envelope envelope = mapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), Envelope.class);
            if (envelope.getUserId() == null || envelope.getSignal() == null) {
                return;
            }
            deliverLocally(envelope.getUserId(), envelope.getSignal());
        } catch (Exception e) {
            log.warn("Unreadable call signal envelope on {}: {}", CHANNEL, e.getMessage());
        }
    }

    /**
     * Pushes to this instance's own sessions. A user with no socket here is not an
     * error — on a multi-instance deployment most users are somewhere else, and the
     * instance holding them handles the same message.
     */
    private void deliverLocally(Long userId, CallSignal signal) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), USER_QUEUE, signal);
        } catch (Exception e) {
            log.warn("Failed to push call signal {} to local sessions of user {}: {}",
                    signal.getType(), userId, e.getMessage());
        }
    }
}
