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
 * One mail read from the support mailbox — the record of how an email-sourced ticket arrived.
 *
 * <p>Read-only here. Rows are written by the ingestion in xamops-service, which owns this table
 * and shares the database; BillOps needs them only to show the mail behind a ticket in the admin
 * console.
 */
@Entity
@Table(name = "ticket_email_message")
@Getter
@Setter
@NoArgsConstructor
public class TicketEmailMessage {

    public static final String STATUS_TICKET_CREATED = "TICKET_CREATED";
    public static final String MATCH_UNIDENTIFIED = "UNIDENTIFIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_reply_id")
    private TicketReply ticketReply;

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

    @Column(name = "to_addresses", columnDefinition = "TEXT")
    private String toAddresses;

    @Column(name = "cc_addresses", columnDefinition = "TEXT")
    private String ccAddresses;

    /** The group address that was mentioned, which is what chose the category. */
    @Column(name = "mailbox_address", length = 320)
    private String mailboxAddress;

    @Column(name = "subject", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt = LocalDateTime.now();

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "match_method", length = 32)
    private String matchMethod;
}
