package com.xammer.cloud.xampulse.service.agent;

import com.xammer.cloud.xampulse.service.stream.TicketStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Single-use enrolment nonces: 128 random bits, kept 60 seconds, bound to the region the agent asked about, consumed
 * atomically (get-and-delete) so a captured identity proof cannot be replayed.
 */
@Service("xpEnrollmentNonceService")
@ConditionalOnProperty(name = "xampulse.enabled", havingValue = "true")
public class EnrollmentNonceService {

    public static final Duration TTL = Duration.ofSeconds(60);
    private static final String PREFIX = "xp:enroll-nonce:";

    private final TicketStore store;
    private final SecureRandom random = new SecureRandom();

    public EnrollmentNonceService(TicketStore store) {
        this.store = store;
    }

    public String issue(String region) {
        byte[] b = new byte[16];
        random.nextBytes(b);
        String nonce = HexFormat.of().formatHex(b);
        store.put(PREFIX + nonce, region, TTL);
        return nonce;
    }

    /** True exactly once per issued nonce, and only for the region it was issued for. */
    public boolean consume(String nonce, String region) {
        if (nonce == null || !nonce.matches("^[0-9a-f]{32}$")) {
            return false;
        }
        Optional<String> bound = store.getAndDelete(PREFIX + nonce);
        return bound.isPresent() && bound.get().equals(region);
    }
}
