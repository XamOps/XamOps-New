package com.xammer.cloud.xampulse.service.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.xampulse.exception.XamPulseException;
import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@code Idempotency-Key} support for creating POSTs. The header is optional: without it the action simply runs.
 *
 * <p>The store key is a SHA-256 of {@code userId|route|key}, so two users with the same key never collide and the raw
 * key is never stored. The value is {@code PENDING|<bodyHash>} while the action runs and {@code DONE|<bodyHash>|<json>}
 * afterwards, for 10 minutes. Same key and same body replays the stored response without a second side effect; the
 * same key with a different body is a 409; the same key while the first call is still running is a 409; a failed
 * action clears the key so a retry can run. Neither user ids nor responses are logged.
 */
@Service("xpIdempotencyService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class XpIdempotencyService {

    public static final Duration TTL = Duration.ofMinutes(10);
    static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_.:-]{8,80}$");
    private static final String PREFIX = "xp:idem:";

    private final TicketStore store;
    private final ObjectMapper mapper;

    public XpIdempotencyService(TicketStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.mapper = objectMapper;
    }

    public <T> T execute(long userId, String route, String key, Object requestBody, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw XamPulseException.invalid("The Idempotency-Key header is not valid.");
        }
        String storeKey = PREFIX + sha256(userId + "|" + route + "|" + key);
        String bodyHash = sha256(canonical(requestBody));
        for (int attempt = 0; attempt < 2; attempt++) {
            if (store.putIfAbsent(storeKey, "PENDING|" + bodyHash, TTL)) {
                return runAndRemember(storeKey, bodyHash, action);
            }
            Optional<String> existing = store.get(storeKey);
            if (existing.isEmpty()) {
                continue; // expired between the two calls: try to claim it again
            }
            return replay(existing.get(), bodyHash, type);
        }
        throw XamPulseException.conflict("A request with this Idempotency-Key is in progress.");
    }

    private <T> T runAndRemember(String storeKey, String bodyHash, Supplier<T> action) {
        T result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            store.getAndDelete(storeKey); // let the caller retry
            throw e;
        }
        try {
            store.put(storeKey, "DONE|" + bodyHash + "|" + mapper.writeValueAsString(result), TTL);
        } catch (JsonProcessingException | RuntimeException e) {
            // The side effect happened; without a stored response a replay would repeat it, so keep it blocked.
            store.put(storeKey, "PENDING|" + bodyHash, TTL);
        }
        return result;
    }

    private <T> T replay(String stored, String bodyHash, Class<T> type) {
        String[] parts = stored.split("\\|", 3);
        if (parts.length < 2 || !parts[1].equals(bodyHash)) {
            throw XamPulseException.idempotencyKeyReused();
        }
        if (!"DONE".equals(parts[0]) || parts.length < 3) {
            throw XamPulseException.conflict("A request with this Idempotency-Key is in progress.");
        }
        try {
            return mapper.readValue(parts[2], type);
        } catch (JsonProcessingException e) {
            throw XamPulseException.conflict("A request with this Idempotency-Key is in progress.");
        }
    }

    private String canonical(Object body) {
        try {
            return body == null ? "" : mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw XamPulseException.invalid("The request could not be read.");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
