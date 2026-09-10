package com.xammer.cloud.messaging;

/**
 * Outcome of one provider send attempt.
 *
 * <p>The existing {@code ExternalMessagingService} returns a bare {@code String} message ID
 * or {@code null}, which cannot distinguish "not configured" from "provider rejected it" from
 * "the number was unusable". Alert delivery needs that distinction: a skip is normal and
 * should stay quiet, a failure is operational and should be logged loudly.
 *
 * @param channel     channel attempted
 * @param recipient   masked recipient, safe to log
 * @param status      what happened
 * @param messageId   provider transaction ID when it returned one, else null
 * @param detail      short human-readable reason for a SKIPPED or FAILED result, else null
 */
public record MessageResult(
        NotificationChannel channel,
        String recipient,
        Status status,
        String messageId,
        String detail) {

    public enum Status {
        /** Provider accepted the message. */
        SENT,
        /** Not attempted: channel disabled, credentials or template missing, or bad number. */
        SKIPPED,
        /** Attempted and rejected, or the call errored. */
        FAILED
    }

    public static MessageResult sent(NotificationChannel channel, String recipient, String messageId) {
        return new MessageResult(channel, recipient, Status.SENT, messageId, null);
    }

    public static MessageResult skipped(NotificationChannel channel, String recipient, String detail) {
        return new MessageResult(channel, recipient, Status.SKIPPED, null, detail);
    }

    public static MessageResult failed(NotificationChannel channel, String recipient, String detail) {
        return new MessageResult(channel, recipient, Status.FAILED, null, detail);
    }

    public boolean isSent() {
        return status == Status.SENT;
    }
}
