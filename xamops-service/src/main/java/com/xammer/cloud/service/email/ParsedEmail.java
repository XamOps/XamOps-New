package com.xammer.cloud.service.email;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A message from the support mailbox, reduced to the parts ingestion cares about.
 *
 * <p>Separated from the IMAP plumbing so the ingest rules — routing, threading, loop detection —
 * can be exercised without a mail server.
 *
 * @param messageId      RFC 5322 Message-ID with the angle brackets stripped. The idempotency key.
 * @param inReplyTo      Message-ID this is a direct reply to, if any.
 * @param references     Every Message-ID named in the References header, oldest first.
 * @param fromAddress    Sender, lowercased.
 * @param fromName       Sender's display name, or null.
 * @param to             To recipients, lowercased.
 * @param cc             Cc recipients, lowercased. A "mention" counts here as much as in To.
 * @param subject        Subject header, or "" — never null, so ticket subjects have a value.
 * @param sentAt         The Date header, i.e. when the sender sent it.
 * @param body           Best available body, already stripped of quoted history.
 * @param bodyIsHtml     True when {@link #body} is HTML and must be sanitised before storage.
 * @param machineSent    True for vacation responders, bounces and other automation.
 * @param attachments    Real attachments; inline images embedded in the body are excluded.
 */
public record ParsedEmail(
        String messageId,
        String inReplyTo,
        List<String> references,
        String fromAddress,
        String fromName,
        List<String> to,
        List<String> cc,
        String subject,
        LocalDateTime sentAt,
        String body,
        boolean bodyIsHtml,
        boolean machineSent,
        List<Attachment> attachments) {

    /**
     * @param filename    As given on the Content-Disposition, already checked for path separators
     *                    by {@link com.xammer.cloud.util.TicketAttachmentPolicy}.
     * @param contentType MIME type declared by the sender.
     * @param content     Full bytes. Bounded by the poller's per-message size cap before we get here.
     */
    public record Attachment(String filename, String contentType, byte[] content) {
    }

    /** Every Message-ID this mail claims as an ancestor, closest first. */
    public List<String> ancestorMessageIds() {
        List<String> ids = new java.util.ArrayList<>();
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            ids.add(inReplyTo);
        }
        // References runs oldest → newest; the nearest ancestor is the most likely match.
        for (int i = references.size() - 1; i >= 0; i--) {
            String ref = references.get(i);
            if (ref != null && !ref.isBlank() && !ids.contains(ref)) {
                ids.add(ref);
            }
        }
        return ids;
    }

    /** To and Cc together — the addresses that count as "mentioned". */
    public List<String> allRecipients() {
        List<String> all = new java.util.ArrayList<>(to);
        all.addAll(cc);
        return all;
    }
}
