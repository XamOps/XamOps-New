package com.xammer.cloud.service.email;

import com.xammer.cloud.service.EmailIcons;
import com.xammer.cloud.service.EmailService;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the support mailbox over IMAP and hands each new message to {@link EmailTicketIngestService}.
 *
 * <h2>Why one mailbox and not two</h2>
 * {@code techtickets@xamops.com} and {@code bizfin@xammer.com} are groups, and a group is not an
 * IMAP mailbox. A dedicated account subscribed to both receives everything sent to either, so
 * there is one connection to run and one place to look when something goes missing. Which group
 * a message was for is read off its To/Cc headers, which survive group delivery — see
 * {@link EmailTicketIngestService#mentionedGroupAddress}.
 *
 * <h2>Running on two replicas</h2>
 * The deployment runs two backend pods with no scheduler locking, so both poll and both see the
 * same unread message. That is safe rather than merely tolerated: ingestion is arbitrated by a
 * unique constraint on the message id, and the loser of the race takes a
 * {@link DataIntegrityViolationException} which is handled here as an ordinary skip.
 *
 * <p>Disabled by default. It stays off until a mailbox is configured, because a misconfigured
 * poller that authenticates as the wrong account would read mail it has no business reading.
 */
@Component
@ConditionalOnProperty(name = "xamops.tickets.email-ingest.enabled", havingValue = "true")
public class InboundEmailPoller {

    private static final Logger logger = LoggerFactory.getLogger(InboundEmailPoller.class);

    private final InboundEmailParser parser;
    private final EmailTicketIngestService ingestService;
    private final EmailService emailService;

    @Value("${xamops.tickets.email-ingest.host:imap.gmail.com}")
    private String host;

    @Value("${xamops.tickets.email-ingest.port:993}")
    private int port;

    @Value("${xamops.tickets.email-ingest.username:}")
    private String username;

    @Value("${xamops.tickets.email-ingest.password:}")
    private String password;

    @Value("${xamops.tickets.email-ingest.folder:INBOX}")
    private String folderName;

    /**
     * How many messages one pass handles. A backlog drains over several passes rather than
     * holding a transaction and an IMAP connection open for an unbounded batch.
     */
    @Value("${xamops.tickets.email-ingest.max-messages-per-poll:25}")
    private int maxMessagesPerPoll;

    /** Per-attachment cap, matching what the ticket attachment policy allows. */
    @Value("${xamops.tickets.email-ingest.max-attachment-bytes:26214400}")
    private long maxAttachmentBytes;

    /** Whether the sender gets told which ticket their mail became. */
    @Value("${xamops.tickets.email-ingest.acknowledge-sender:true}")
    private boolean acknowledgeSender;

    /**
     * How far back to look. Bounds the first run against a mailbox that already has history;
     * 0 disables the bound. See {@link #unreadWithinWindow()}.
     */
    @Value("${xamops.tickets.email-ingest.lookback-days:7}")
    private int lookbackDays;

    @Value("${xamops.tickets.email-ingest.connection-timeout-ms:20000}")
    private String connectionTimeoutMs;

    public InboundEmailPoller(InboundEmailParser parser,
                              EmailTicketIngestService ingestService,
                              EmailService emailService) {
        this.parser = parser;
        this.ingestService = ingestService;
        this.emailService = emailService;
    }

    /**
     * Says on startup that ingestion is live, and against which mailbox.
     *
     * <p>Without this the only way to tell an enabled poller from a disabled one is the absence
     * of log lines — and a healthy poller watching an empty mailbox is also silent. That
     * ambiguity is not something anyone should have to debug twice.
     */
    @jakarta.annotation.PostConstruct
    void logConfiguration() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            logger.warn("Ticket email ingestion is ENABLED but has no mailbox credentials "
                    + "(xamops.tickets.email-ingest.username/password) — no mail will be read");
            return;
        }
        logger.info("Ticket email ingestion ACTIVE — polling {} at {}:{} folder '{}' every {}ms, "
                        + "reading unread mail from the last {}",
                username, host, port, folderName, pollIntervalMs,
                lookbackDays <= 0 ? "(no date limit — whole mailbox)" : lookbackDays + " day(s)");
    }

    @Value("${xamops.tickets.email-ingest.poll-interval-ms:60000}")
    private String pollIntervalMs;

    @Scheduled(fixedDelayString = "${xamops.tickets.email-ingest.poll-interval-ms:60000}",
            initialDelayString = "${xamops.tickets.email-ingest.initial-delay-ms:30000}")
    public void poll() {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            logger.warn("Email ingestion is enabled but no mailbox credentials are configured — skipping");
            return;
        }

        Store store = null;
        Folder folder = null;
        try {
            store = connect();
            folder = store.getFolder(folderName);
            // READ_WRITE: the \Seen flag is how the mailbox stops re-offering a message. It is a
            // convenience, not the correctness guarantee — that is the unique constraint.
            folder.open(Folder.READ_WRITE);

            jakarta.mail.Message[] unread = folder.search(unreadWithinWindow());
            if (unread.length == 0) {
                return;
            }

            // Oldest first, so a thread's messages are ingested in the order they were sent and
            // replies land on a ticket that already exists.
            jakarta.mail.Message[] batch = Arrays.stream(unread)
                    .sorted(Comparator.comparingInt(jakarta.mail.Message::getMessageNumber))
                    .limit(maxMessagesPerPoll)
                    .toArray(jakarta.mail.Message[]::new);

            logger.info("Support mailbox has {} unread message(s); processing {}", unread.length, batch.length);

            for (jakarta.mail.Message message : batch) {
                if (message instanceof MimeMessage mime) {
                    handleOne(mime);
                } else {
                    logger.warn("Skipping a non-MIME message in the support mailbox");
                    message.setFlag(Flags.Flag.SEEN, true);
                }
            }
        } catch (Exception e) {
            // Network trouble, an expired app password, a folder rename. The next pass retries;
            // nothing was lost because unhandled messages keep their unread flag.
            logger.error("Support mailbox poll failed: {}", e.getMessage(), e);
        } finally {
            closeQuietly(folder, store);
        }
    }

    /**
     * Unread, and recent enough to be worth reading.
     *
     * <p>The date bound is what makes it safe to point this at a mailbox that already has a
     * history. An established address accumulates thousands of unread messages — the account
     * used for the first live test had 1552 — and without a bound the poller works through all
     * of them oldest-first before it ever reaches today's mail, which looks exactly like the
     * feature being broken.
     *
     * <p>It bounds the first run only. Afterwards the {@code \Seen} flag and the message-id
     * ledger prevent reprocessing, so the window can stay generous enough that mail arriving
     * during a deployment is never missed.
     *
     * <p>Set {@code lookback-days} to 0 to disable the bound and ingest the whole mailbox.
     */
    private jakarta.mail.search.SearchTerm unreadWithinWindow() {
        FlagTerm unread = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
        if (lookbackDays <= 0) {
            return unread;
        }
        Date cutoff = Date.from(java.time.Instant.now().minus(java.time.Duration.ofDays(lookbackDays)));
        // Server-side, so the backlog is never transferred in the first place.
        return new jakarta.mail.search.AndTerm(unread,
                new jakarta.mail.search.ReceivedDateTerm(jakarta.mail.search.ComparisonTerm.GE, cutoff));
    }

    /**
     * Ingests one message and marks it read.
     *
     * <p>The message is flagged {@code \Seen} on every path that reaches a decision — success,
     * a lost race, or a recorded failure — because leaving it unread would mean reprocessing it
     * on every pass for as long as it sits in the mailbox.
     */
    private void handleOne(MimeMessage message) {
        ParsedEmail mail = null;
        try {
            mail = parser.parse(message, maxAttachmentBytes);
            EmailTicketIngestService.Result result = ingestService.ingest(mail);
            message.setFlag(Flags.Flag.SEEN, true);

            if (acknowledgeSender && result.createdTicket() && result.recipientForAck() != null) {
                sendAcknowledgement(result, mail);
            }
        } catch (DataIntegrityViolationException e) {
            // The other replica ingested this message first. Its transaction committed; ours
            // rolled back cleanly, including the ticket it had built.
            logger.debug("Message already ingested by another instance — skipping");
            setSeenQuietly(message);
        } catch (Exception e) {
            logger.error("Failed to ingest a message from the support mailbox: {}", e.getMessage(), e);
            if (mail != null) {
                ingestService.recordFailure(mail);
                setSeenQuietly(message);
            }
            // A message that could not even be parsed keeps its unread flag: there is no id to
            // record it under, and an operator needs to be able to find it in the mailbox.
        }
    }

    /**
     * Tells the sender their mail became a ticket.
     *
     * <p>Threaded onto their original message so the acknowledgement appears in the conversation
     * they started, and so their reply to it carries that Message-ID in References — which is
     * what lets ingestion put the reply on the same ticket.
     */
    private void sendAcknowledgement(EmailTicketIngestService.Result result, ParsedEmail mail) {
        try {
            String subject = String.format("Received — Ticket [ID: %d]: %s",
                    result.ticketId(), mail.subject());
            String angled = "<" + mail.messageId() + ">";
            emailService.sendHtmlEmail(
                    result.recipientForAck(),
                    subject,
                    acknowledgementHtml(result.ticketId(), mail),
                    Map.of(
                            "In-Reply-To", angled,
                            "References", angled,
                            // RFC 3834: stops the recipient's out-of-office answering this.
                            "Auto-Submitted", "auto-generated"));
        } catch (Exception e) {
            // The ticket exists; failing to confirm it must not undo that.
            logger.error("Failed to acknowledge ticket #{} to {}",
                    result.ticketId(), result.recipientForAck(), e);
        }
    }

    private String acknowledgementHtml(Long ticketId, ParsedEmail mail) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1.0'>"
                + "<title>Ticket Received</title></head>"
                + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
                + "<tr><td style='background:#000000;padding:40px;'>"
                + "  <div style='margin-bottom:16px;'><span style='display:inline-block;background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
                + "  <div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.ticket(40) + "</div>"
                + "  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>We have your request</h1>"
                + "  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Ticket #" + ticketId + "</p>"
                + "</td></tr>"
                + "<tr><td style='padding:32px 40px;'>"
                + "<p style='margin:0 0 16px;color:#374151;font-size:15px;line-height:1.6;'>"
                + "Your email has been logged as ticket <strong>#" + ticketId + "</strong> and our support team "
                + "has been notified. Reply to this email to add to the ticket — everything you send on this "
                + "thread reaches the same place.</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='margin-bottom:4px;'>"
                + "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>Subject</td>"
                + "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>"
                + escapeHtml(mail.subject()) + "</td></tr>"
                + "<tr><td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;'>Ticket ID</td>"
                + "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>#"
                + ticketId + "</td></tr>"
                + "</table>"
                + "</td></tr>"
                + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>"
                + "  <span style='display:inline-block;background:#000000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
                + "  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>"
                + "  <p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>"
                + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Store connect() throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        // Without an explicit timeout a wedged connection holds the scheduler thread for as long
        // as the OS keeps the socket, which silently stops ingestion.
        props.put("mail.imaps.connectiontimeout", connectionTimeoutMs);
        props.put("mail.imaps.timeout", connectionTimeoutMs);
        props.put("mail.imaps.writetimeout", connectionTimeoutMs);

        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        store.connect(host, port, username, password);
        return store;
    }

    private void setSeenQuietly(jakarta.mail.Message message) {
        try {
            message.setFlag(Flags.Flag.SEEN, true);
        } catch (Exception e) {
            logger.warn("Could not flag a message as read: {}", e.getMessage());
        }
    }

    private void closeQuietly(Folder folder, Store store) {
        try {
            if (folder != null && folder.isOpen()) {
                // expunge=false: nothing here deletes mail, and the mailbox stays a readable
                // record of what customers actually sent.
                folder.close(false);
            }
        } catch (Exception e) {
            logger.debug("Error closing mail folder: {}", e.getMessage());
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (Exception e) {
            logger.debug("Error closing mail store: {}", e.getMessage());
        }
    }
}
