package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;

import java.time.Duration;
import java.util.UUID;

/**
 * What a {@link Step} needs to run (design-core.md §9.1): the group lease handle, the deterministic
 * idempotency token {@code uuidv5(operation_id, seq)}, the mutating client, and the call timeouts.
 *
 * <p>The idempotency token is derived deterministically from {@code (operationId, seq)} by
 * {@link #idempotencyToken(UUID, int)}, so a re-issued step uses the same token and the cloud treats
 * the repeat as idempotent (§9.3). Before any mutating cloud call the step calls
 * {@code leaseHandle().assertHeld(callTimeout())} (§3.4), so a lost lease stops it before the call.
 *
 * <p>{@code mutatingClient} is typed as {@code Object} at the SPI boundary: each provider casts it to
 * its own mutating client. A read-only step (probe, verify) ignores it. The provider's read-only
 * view, used in planning, is {@link ReadOnlyCloudView} — never present here as a mutation surface.
 */
public record StepContext(
        UUID operationId,
        int seq,
        LeaseHandle leaseHandle,
        Object mutatingClient,
        Duration callTimeout) {

    /** The DNS namespace UUID (RFC 4122 §C), used as the fixed namespace for the step token. */
    private static final UUID NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    public StepContext {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (callTimeout == null) {
            throw new IllegalArgumentException("callTimeout is required");
        }
    }

    /** This step's deterministic idempotency token {@code uuidv5(operation_id, seq)} (§9.1, §9.3). */
    public String idempotencyToken() {
        return idempotencyToken(operationId, seq).toString();
    }

    /**
     * {@code uuidv5(operation_id, seq)}: a name-based (SHA-1) UUID over the operation id's 16 bytes
     * followed by the step {@code seq}, in a fixed namespace. Deterministic, so a re-issued step
     * produces the same token.
     */
    public static UUID idempotencyToken(UUID operationId, int seq) {
        byte[] name = new byte[16 + 4];
        long hi = operationId.getMostSignificantBits();
        long lo = operationId.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            name[i] = (byte) (hi >>> (8 * (7 - i)));
            name[8 + i] = (byte) (lo >>> (8 * (7 - i)));
        }
        name[16] = (byte) (seq >>> 24);
        name[17] = (byte) (seq >>> 16);
        name[18] = (byte) (seq >>> 8);
        name[19] = (byte) seq;
        return uuidV5(NAMESPACE, name);
    }

    private static UUID uuidV5(UUID namespace, byte[] name) {
        java.security.MessageDigest sha1;
        try {
            sha1 = java.security.MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
        byte[] nsBytes = new byte[16];
        long nsHi = namespace.getMostSignificantBits();
        long nsLo = namespace.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            nsBytes[i] = (byte) (nsHi >>> (8 * (7 - i)));
            nsBytes[8 + i] = (byte) (nsLo >>> (8 * (7 - i)));
        }
        sha1.update(nsBytes);
        sha1.update(name);
        byte[] hash = sha1.digest();
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // IETF variant
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (hash[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (hash[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }
}
