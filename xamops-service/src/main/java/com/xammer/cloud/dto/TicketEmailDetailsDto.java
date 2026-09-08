package com.xammer.cloud.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The mail a ticket was raised from, as shown on the ticket.
 *
 * <p>An agent answering an email-sourced ticket needs to know things that have no equivalent on a
 * web ticket: who else was on the thread and will expect to be kept on it, which support address
 * was mentioned, and — when the sender matched no account — that the ticket is sitting under the
 * intake client until somebody assigns it.
 */
@Getter
@Setter
public class TicketEmailDetailsDto {

    private String fromAddress;
    private String fromName;
    /** Comma-separated, as it appeared on the header. */
    private String toAddresses;
    private String ccAddresses;
    /** The group address that was mentioned, which is what chose the category. */
    private String mailboxAddress;
    private String subject;
    /** When the sender sent it. */
    private LocalDateTime sentAt;
    /** When the poller read it. */
    private LocalDateTime receivedAt;
    /** USER_EMAIL | DOMAIN | UNIDENTIFIED */
    private String matchMethod;

    /** True when nothing tied the sender to a client, so the ticket needs triage. */
    public boolean isUnidentifiedSender() {
        return "UNIDENTIFIED".equals(matchMethod);
    }
}
