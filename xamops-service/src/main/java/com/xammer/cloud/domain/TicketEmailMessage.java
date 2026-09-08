package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One mail read from the support mailbox.
 *
 * <p>A row is written for <em>every</em> message the poller processes, including the ones it
 * deliberately throws away. That is what makes ingestion idempotent: {@code messageId} is unique,
 * so the two backend replicas racing on the same unread message resolve to one ticket, and a
 * connection lost before the IMAP {@code \Seen} flag is set cannot produce a duplicate on the
 * next poll.
 *
 * <p>It is also the record of the mail shown on the ticket — who sent it, who else was on it,
 * and which group address they mentioned.
 */
@Entity
@Table(name = "ticket_email_message")
@Getter
@Setter
@NoArgsConstructor
public class TicketEmailMessage {

    /** Message produced a new ticket. */
    public static final String STATUS_TICKET_CREATED = "TICKET_CREATED";
    /** Message threaded onto an existing ticket as a reply. */
    public static final String STATUS_REPLY_APPENDED = "REPLY_APPENDED";
    /** Our own outbound notification, read back out of the mailbox. */
    public static final String STATUS_SKIPPED_LOOP = "SKIPPED_LOOP";
    /** Vacation responder, bounce, or anything else marked as machine-generated. */
    public static final String STATUS_SKIPPED_AUTO_REPLY = "SKIPPED_AUTO_REPLY";
    /** Neither group address appeared in To or Cc — nobody asked us for anything. */
    public static final String STATUS_SKIPPED_NO_MATCH = "SKIPPED_NO_MATCH";
    /** Processing threw. Recorded so the message is not retried forever. */
    public static final String STATUS_FAILED = "FAILED";

    /** Sender address matched a registered user directly. */
    public static final String MATCH_USER_EMAIL = "USER_EMAIL";
    /** Sender's domain matched a registered user's domain. */
    public static final String MATCH_DOMAIN = "DOMAIN";
    /** No match — ticket is parked under the intake client for an admin to assign. */
    public static final String MATCH_UNIDENTIFIED = "UNIDENTIFIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when the message was processed but produced no ticket. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    /** Set when the message threaded onto an existing ticket rather than opening one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_reply_id")
    private TicketReply ticketReply;

    /**
     * Unique here as well as in the migration, so the constraint exists however the table was
     * created. It is the concurrency control between the two backend replicas, not a tidiness
     * measure — a table built by {@code ddl-auto=update} without it would silently double-post
     * every message.
     */
    @Column(name = "message_id", nullable = false, length = 998, unique = true)
    private String messageId;

    @Column(name = "in_reply_to", length = 998)
    private String inReplyTo;

    @Column(name = "references_header", columnDefinition = "TEXT")
    private String referencesHeader;

    @Column(name = "from_address", nullable = false, length = 320)
    private String fromAddress;

    @Column(name = "from_name", length = 255)
    private String fromName;

    /** Comma-separated, as it appeared on the header. */
    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    @Column(name = "cc_addresses", columnDefinition = "TEXT")
    private String ccAddresses;

    /** The group address that was mentioned, which is what chose the category. */
    @Column(name = "mailbox_address", length = 320)
    private String mailboxAddress;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    /** The Date header — when the sender sent it, not when we read it. */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "match_method", length = 32)
    private String matchMethod;
}
