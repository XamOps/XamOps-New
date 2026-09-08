package com.xammer.billops.dto;

import java.time.LocalDateTime;

/**
 * The mail a ticket was raised from, as shown on the ticket.
 *
 * <p>An agent answering an email-sourced ticket needs things a web ticket never carries: who else
 * was on the thread and will expect to stay on it, which support address was mentioned, and —
 * when the sender matched no account — that the ticket is parked under the intake client until
 * somebody assigns it.
 */
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

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getToAddresses() {
        return toAddresses;
    }

    public void setToAddresses(String toAddresses) {
        this.toAddresses = toAddresses;
    }

    public String getCcAddresses() {
        return ccAddresses;
    }

    public void setCcAddresses(String ccAddresses) {
        this.ccAddresses = ccAddresses;
    }

    public String getMailboxAddress() {
        return mailboxAddress;
    }

    public void setMailboxAddress(String mailboxAddress) {
        this.mailboxAddress = mailboxAddress;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getMatchMethod() {
        return matchMethod;
    }

    public void setMatchMethod(String matchMethod) {
        this.matchMethod = matchMethod;
    }
}
