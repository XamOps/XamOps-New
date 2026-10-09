package com.xammer.cloud.domain.type;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses inline values that look like a large-object OID reference.
 *
 * <p>Once reads resolve OID text through {@link LegacyLobTextType}, storing another object's
 * OID as a plain value would let a caller read that large object. These checks apply in both
 * write modes. Values are never logged.
 *
 * <p>This file is byte-identical in both services ({@code LobTextSourceParityTest}).
 */
public final class OidShapeGuard {

    private static final Logger log = LoggerFactory.getLogger(OidShapeGuard.class);

    private OidShapeGuard() {
    }

    /**
     * Entity-callback check. Logs one WARN naming the table and key, then refuses the write.
     *
     * @param table the physical table name
     * @param key   the row key, or {@code null} while an IDENTITY id is not yet assigned
     * @param value the value about to be written
     * @throws IllegalArgumentException if the value is OID-shaped
     */
    public static void checkEntityWrite(String table, Object key, String value) {
        if (!LegacyLobTextType.isCandidate(value)) {
            return;
        }
        Object keyLabel = key == null ? "new" : key;
        log.warn("Refused OID-shaped value table={} key={}", table, keyLabel);
        throw new IllegalArgumentException("Refused OID-shaped value table=" + table + " key=" + keyLabel);
    }

    /**
     * API-input check, for request fields stored in one of the guarded columns.
     *
     * @throws IllegalArgumentException if the value is OID-shaped
     */
    public static void requireNotOidShaped(String field, String value) {
        if (LegacyLobTextType.isCandidate(value)) {
            throw new IllegalArgumentException(field + " must not be a bare number between 16384 and 4294967295");
        }
    }
}
