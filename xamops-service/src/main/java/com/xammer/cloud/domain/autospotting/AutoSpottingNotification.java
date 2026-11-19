package com.xammer.cloud.domain.autospotting;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code autospotting_notification} (design-ux-ops.md section 7.1). A plain record, not a JPA
 * entity: the table is written and read with JdbcTemplate only, and its migration is the single place it is
 * created in every environment. {@code payload} is the redacted render input, a JSON object of ids, codes and
 * short labels; it never holds raw provider text.
 */
public record AutoSpottingNotification(
        long id,
        String eventType,
        String audience,
        boolean mandatory,
        long clientId,
        long cloudAccountId,
        UUID groupId,
        UUID operationId,
        String dedupeKey,
        String payload,
        Instant createdAt,
        Instant emailSentAt,
        int attempts,
        String lastError,
        String readBy) {

    public boolean emailSent() {
        return emailSentAt != null;
    }
}
