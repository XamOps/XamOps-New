package com.xammer.cloud.service.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.TicketEmailMessage;
import com.xammer.cloud.domain.TicketReply;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.TicketEmailMessageRepository;
import com.xammer.cloud.repository.TicketRepository;
import com.xammer.cloud.repository.UserRepository;
import com.xammer.cloud.service.FileStorageService;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.util.TicketAttachmentPolicy;
import com.xammer.cloud.util.TicketHtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a mail that mentioned one of the support group addresses into a ticket, or into a reply
 * on the ticket its thread already opened.
 *
 * <h2>Which mails count</h2>
 * The address has to appear in To <em>or</em> Cc — being CC'd onto a conversation between other
 * people is the case this exists for, and it is indistinguishable from being the sole recipient
 * once the message is in the mailbox. Which of the two addresses was mentioned picks the
 * category, reusing the same routing configuration the outbound notifications already use.
 *
 * <h2>Which client the ticket belongs to</h2>
 * {@code ticket.client_id} is NOT NULL, but a mail carries an address, not a user. Resolution
 * runs sender address → sender's domain → the intake client, and never fails: an unrecognised
 * sender still gets a real ticket, parked for an admin to reassign. Silently dropping a
 * customer's mail is the one outcome worth designing against.
 */
@Service
public class EmailTicketIngestService {

    private static final Logger logger = LoggerFactory.getLogger(EmailTicketIngestService.class);

    /** What a ticket raised by mail is filed as when nothing better is known. */
    private static final String DEFAULT_SEVERITY = "Normal";
    private static final String CATEGORY_BILLING = "Account and Billing";
    private static final String CATEGORY_TECHNICAL = "Technical";
    /** Both are existing options on their category's dropdown, so filters keep working. */
    private static final String DEFAULT_BILLING_SERVICE = "Billing";
    private static final String DEFAULT_TECHNICAL_SERVICE = "Other";

    /**
     * Ticket number in a subject, for threading a reply whose client dropped the References
     * header. Matches the "[ID: 42]" our notifications emit and the "#42" people type by hand.
     */
    private static final Pattern SUBJECT_TICKET_ID =
            Pattern.compile("\\[ID:\\s*(\\d{1,18})\\]|#(\\d{1,18})\\b");

    /** Leading Re:/Fwd:/Fw: chains, stripped from the subject of a *new* ticket. */
    private static final Pattern REPLY_PREFIX =
            Pattern.compile("^\\s*(?:(?:re|fw|fwd|aw|sv|vs|antw)\\s*(?:\\[\\d+\\])?\\s*:\\s*)+",
                    Pattern.CASE_INSENSITIVE);

    /** Not a key segment we want to build from attacker-supplied text. */
    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    /**
     * Consumer mail providers. A sender at one of these tells us nothing about which company
     * they belong to, so domain matching must not run for them — it would hand a stranger at
     * gmail.com whichever client happens to have a gmail user, and with it that client's ticket
     * history.
     */
    private static final Set<String> PUBLIC_MAIL_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "ymail.com",
            "outlook.com", "hotmail.com", "live.com", "msn.com",
            "icloud.com", "me.com", "mac.com",
            "aol.com", "protonmail.com", "proton.me", "pm.me",
            "gmx.com", "gmx.net", "mail.com", "zoho.com", "yandex.com",
            "rediffmail.com", "qq.com", "163.com", "126.com");

    private final TicketRepository ticketRepository;
    private final TicketEmailMessageRepository emailMessageRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final FileStorageService fileStorageService;
    private final RedisCacheService redisCacheService;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${xamops.tickets.admin.technical-emails:techtickets@xamops.com}")
    private String technicalAdminEmailsCsv;

    @Value("${xamops.tickets.admin.billing-email:bizfin@xammer.com}")
    private String billingAdminEmail;

    /** Our own outbound sender. Mail from it is our notification coming back — never a ticket. */
    @Value("${spring.mail.username:notifications@xamops.com}")
    private String selfEmail;

    /** Extra addresses that must never open a ticket, e.g. a mailer-daemon or a monitoring bot. */
    @Value("${xamops.tickets.email-ingest.ignored-senders:}")
    private String ignoredSendersCsv;

    /**
     * Our own domains. Excluded from *domain* matching: an internal sender resolves by their
     * exact address or not at all, because "someone at xammer.in" does not identify a customer.
     */
    @Value("${xamops.tickets.email-ingest.internal-domains:xamops.com,xammer.com,xammer.in}")
    private String internalDomainsCsv;

    /** Client that owns tickets from senders we could not identify. Created on first use. */
    @Value("${xamops.tickets.email-ingest.intake-client-name:Email Intake}")
    private String intakeClientName;

    public EmailTicketIngestService(TicketRepository ticketRepository,
                                    TicketEmailMessageRepository emailMessageRepository,
                                    UserRepository userRepository,
                                    ClientRepository clientRepository,
                                    FileStorageService fileStorageService,
                                    RedisCacheService redisCacheService,
                                    SimpMessagingTemplate messagingTemplate) {
        this.ticketRepository = ticketRepository;
        this.emailMessageRepository = emailMessageRepository;
        this.userRepository = userRepository;
        this.clientRepository = clientRepository;
        this.fileStorageService = fileStorageService;
        this.redisCacheService = redisCacheService;
        this.messagingTemplate = messagingTemplate;
    }

    /** What ingestion did with a message, so the poller can decide whether to acknowledge it. */
    public record Result(String status, Long ticketId, String recipientForAck) {
        public boolean createdTicket() {
            return TicketEmailMessage.STATUS_TICKET_CREATED.equals(status);
        }
    }

    /**
     * Ingests one message.
     *
     * <p>Runs as a single transaction so the {@code ticket_email_message.message_id} unique
     * constraint arbitrates the race between the two backend replicas: both may process the same
     * unread message, one commits, the other's insert violates the constraint and takes its
     * half-built ticket down with it. That makes the constraint the concurrency control, which
     * is why the marker row is written last and flushed explicitly.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when another replica won
     *                                                                the race; the caller treats
     *                                                                it as an ordinary skip.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "tickets", allEntries = true),
            @CacheEvict(value = "tickets_by_category", allEntries = true)
    })
    public Result ingest(ParsedEmail mail) {
        if (mail.messageId() == null || mail.fromAddress() == null) {
            logger.warn("Discarding a message with no usable Message-ID or From header");
            return new Result(TicketEmailMessage.STATUS_FAILED, null, null);
        }

        // Cheap pre-check. The unique constraint is the real guarantee; this just avoids doing
        // the work in the common case where the poller re-reads a message it already handled.
        if (emailMessageRepository.existsByMessageId(mail.messageId())) {
            logger.debug("Message {} already ingested", mail.messageId());
            return new Result(TicketEmailMessage.STATUS_SKIPPED_NO_MATCH, null, null);
        }

        if (isLoop(mail)) {
            logger.info("Ignoring mail from {} — it is our own notification traffic", mail.fromAddress());
            return record(mail, null, null, null, TicketEmailMessage.STATUS_SKIPPED_LOOP, null);
        }

        if (mail.machineSent()) {
            logger.info("Ignoring machine-generated mail from {}", mail.fromAddress());
            return record(mail, null, null, null, TicketEmailMessage.STATUS_SKIPPED_AUTO_REPLY, null);
        }

        String mentioned = mentionedGroupAddress(mail);

        // Threading is checked BEFORE the group-address rule, and that order matters.
        //
        // The acknowledgement we send goes out from notifications@ to the sender alone. When
        // they reply to it — the obvious thing to do, and what the acknowledgement tells them to
        // do — their reply carries neither group address, because our mail never put one there.
        // Requiring a mention first would drop exactly the replies this feature exists to
        // collect, and drop them silently.
        //
        // A mail that provably continues a thread we already turned into a ticket belongs on
        // that ticket, whoever else is or is not copied.
        Optional<Ticket> existing = findThreadedTicket(mail);
        if (existing.isPresent()) {
            return appendReply(mail, existing.get(), mentioned);
        }

        // Opening a *new* ticket still requires the mention. Without it, anything at all that
        // reaches this mailbox — newsletters, vendor mail, a bounce — would become a ticket.
        if (mentioned == null) {
            logger.debug("No support address mentioned in message {}, and it continues no known thread",
                    mail.messageId());
            return record(mail, null, null, null, TicketEmailMessage.STATUS_SKIPPED_NO_MATCH, null);
        }
        return openTicket(mail, mentioned);
    }

    /**
     * Marks a message as processed after {@link #ingest} threw.
     *
     * <p>Runs in its own transaction because the failed one has been rolled back. Without this
     * the message stays unread and every poll retries it forever — one malformed mail would
     * stall the mailbox and fill the log. Recording it as FAILED means an operator can find it,
     * and the sender's next mail still gets through.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordFailure(ParsedEmail mail) {
        try {
            if (emailMessageRepository.existsByMessageId(mail.messageId())) {
                return;
            }
            record(mail, null, null, null, TicketEmailMessage.STATUS_FAILED, null);
        } catch (Exception e) {
            logger.error("Could not even record the failure for message {}", mail.messageId(), e);
        }
    }

    // ---------------------------------------------------------------- filtering

    /**
     * Whether this mail originated from us.
     *
     * <p>Every ticket already sends a notification from {@code notifications@xamops.com} to the
     * group addresses. With the ingest mailbox subscribed to those groups, that notification
     * comes straight back — and without this check each ticket would create the next one
     * indefinitely.
     */
    private boolean isLoop(ParsedEmail mail) {
        String from = mail.fromAddress();
        if (from.equalsIgnoreCase(selfEmail)) {
            return true;
        }
        // A group address as the *sender* means the list reflected something back at us.
        if (groupAddresses().stream().anyMatch(from::equalsIgnoreCase)) {
            return true;
        }
        return csv(ignoredSendersCsv).stream().anyMatch(from::equalsIgnoreCase);
    }

    /**
     * The support address the sender mentioned, or null if they mentioned none.
     *
     * <p>To and Cc are treated identically on purpose: "someone mentions that mail id" is
     * usually a CC on a conversation that is mostly between other people.
     */
    String mentionedGroupAddress(ParsedEmail mail) {
        Set<String> recipients = new HashSet<>(mail.allRecipients());
        // Billing first: a mail addressed to both is a billing question that copied the
        // technical list, and it needs to reach the team that can act on it.
        if (recipients.contains(billingAdminEmail.toLowerCase(Locale.ROOT))) {
            return billingAdminEmail.toLowerCase(Locale.ROOT);
        }
        for (String technical : csv(technicalAdminEmailsCsv)) {
            if (recipients.contains(technical.toLowerCase(Locale.ROOT))) {
                return technical.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String categoryFor(String mentionedAddress) {
        return billingAdminEmail.equalsIgnoreCase(mentionedAddress) ? CATEGORY_BILLING : CATEGORY_TECHNICAL;
    }

    // ---------------------------------------------------------------- threading

    /**
     * The ticket this mail continues, if any.
     *
     * <p>Header threading first, because it survives subject edits and localised "Re:" prefixes.
     * The subject token is the fallback for clients that strip References — and it is checked
     * against a ticket that already has mail on it, so a customer writing "#42" in a fresh mail
     * cannot graft themselves onto someone else's ticket.
     */
    private Optional<Ticket> findThreadedTicket(ParsedEmail mail) {
        List<String> ancestors = mail.ancestorMessageIds();
        if (!ancestors.isEmpty()) {
            List<TicketEmailMessage> known = emailMessageRepository.findByMessageIdInWithTicket(ancestors);
            // findByMessageIdInWithTicket returns newest first; prefer the closest ancestor the
            // mail actually names.
            for (String ancestor : ancestors) {
                for (TicketEmailMessage candidate : known) {
                    if (ancestor.equals(candidate.getMessageId()) && candidate.getTicket() != null) {
                        return Optional.of(candidate.getTicket());
                    }
                }
            }
        }

        Matcher matcher = SUBJECT_TICKET_ID.matcher(mail.subject());
        if (matcher.find()) {
            String digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            try {
                Ticket ticket = ticketRepository.findById(Long.parseLong(digits)).orElse(null);
                if (ticket != null && sameSenderAlreadyOnTicket(ticket, mail.fromAddress())) {
                    return Optional.of(ticket);
                }
            } catch (NumberFormatException e) {
                logger.debug("Subject looked like it carried a ticket id but did not: {}", mail.subject());
            }
        }
        return Optional.empty();
    }

    /**
     * Guards the subject-token fallback. Without it, quoting "#17" in a subject line would attach
     * your mail to another customer's ticket and expose its thread to you.
     */
    private boolean sameSenderAlreadyOnTicket(Ticket ticket, String fromAddress) {
        User creator = ticket.getCreator();
        if (creator != null && fromAddress.equalsIgnoreCase(creator.getEmail())) {
            return true;
        }
        return ticket.getReplies() != null && ticket.getReplies().stream().anyMatch(reply ->
                fromAddress.equalsIgnoreCase(reply.getAuthorEmail())
                        || (reply.getAuthor() != null && fromAddress.equalsIgnoreCase(reply.getAuthor().getEmail())));
    }

    // ---------------------------------------------------------------- writing

    private Result openTicket(ParsedEmail mail, String mentionedAddress) {
        SenderMatch sender = resolveSender(mail.fromAddress());
        String category = categoryFor(mentionedAddress);

        Ticket ticket = new Ticket();
        ticket.setClient(sender.client());
        ticket.setCreator(sender.user());
        ticket.setSubject(subjectForNewTicket(mail));
        ticket.setDescription(safeBody(mail));
        ticket.setStatus("OPEN");
        ticket.setCategory(category);
        ticket.setService(CATEGORY_BILLING.equals(category) ? DEFAULT_BILLING_SERVICE : DEFAULT_TECHNICAL_SERVICE);
        ticket.setSeverity(DEFAULT_SEVERITY);
        ticket.setSource(Ticket.SOURCE_EMAIL);
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        // The customer has spoken and nobody has answered — same state a web ticket starts in.
        ticket.setHasNewUserReply(true);

        Ticket saved = ticketRepository.save(ticket);

        // Attachments have nowhere to live on a ticket itself, so they arrive as a reply with no
        // message. The UI already renders that as "(Attachment only)".
        TicketReply attachmentReply = null;
        String attachmentsJson = uploadAttachments(mail);
        if (attachmentsJson != null) {
            attachmentReply = new TicketReply();
            attachmentReply.setTicket(saved);
            attachmentReply.setAuthor(sender.user());
            attachmentReply.setAuthorEmail(mail.fromAddress());
            attachmentReply.setAuthorName(mail.fromName());
            attachmentReply.setMessage("");
            attachmentReply.setAttachmentsJson(attachmentsJson);
            attachmentReply.setCreatedAt(LocalDateTime.now());
            saved.getReplies().add(attachmentReply);
            saved = ticketRepository.save(saved);
            attachmentReply = saved.getReplies().get(saved.getReplies().size() - 1);
        }

        evictCountCache(category);
        broadcastTicketChange();

        logger.info("Ticket #{} opened from mail {} (sender {}, match {}, category {})",
                saved.getId(), mail.messageId(), mail.fromAddress(), sender.method(), category);

        return record(mail, saved, attachmentReply, mentionedAddress,
                TicketEmailMessage.STATUS_TICKET_CREATED, sender.method());
    }

    private Result appendReply(ParsedEmail mail, Ticket ticket, String mentionedAddress) {
        SenderMatch sender = resolveSender(mail.fromAddress());

        // A closed ticket receiving mail is a customer saying it is not finished. Reopening beats
        // both alternatives: rejecting loses the message, and opening a fresh ticket severs it
        // from the history the agent needs.
        if ("CLOSED".equalsIgnoreCase(ticket.getStatus())) {
            ticket.setStatus("OPEN");
            ticket.setClosedAt(null);
            logger.info("Reopening ticket #{} — new mail arrived on a closed thread", ticket.getId());
        } else {
            ticket.setStatus("IN_PROGRESS");
        }

        TicketReply reply = new TicketReply();
        reply.setTicket(ticket);
        reply.setAuthor(sender.user());
        reply.setAuthorEmail(mail.fromAddress());
        reply.setAuthorName(mail.fromName());
        reply.setMessage(safeBody(mail));
        reply.setCreatedAt(LocalDateTime.now());
        reply.setInternal(false);
        reply.setAttachmentsJson(uploadAttachments(mail));

        ticket.getReplies().add(reply);

        if (ticket.getChatStartedAt() == null) {
            ticket.setChatStartedAt(LocalDateTime.now());
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        // Mail from a participant is never an admin reply, so it always flips the ticket to
        // "waiting on us" and clears the reminder the scheduler would otherwise send.
        ticket.setHasNewUserReply(true);
        ticket.setLastAdminReplyAt(null);
        ticket.setReminderSentAt(null);

        Ticket saved = ticketRepository.save(ticket);
        TicketReply persisted = saved.getReplies().get(saved.getReplies().size() - 1);

        evictCountCache(saved.getCategory());
        broadcastTicketChange();

        logger.info("Mail {} appended to ticket #{} from {}",
                mail.messageId(), saved.getId(), mail.fromAddress());

        return record(mail, saved, persisted, mentionedAddress,
                TicketEmailMessage.STATUS_REPLY_APPENDED, sender.method());
    }

    /**
     * Writes the ledger row and flushes it, so a duplicate Message-ID fails here rather than at
     * commit time where the cause would be harder to attribute.
     */
    private Result record(ParsedEmail mail, Ticket ticket, TicketReply reply,
                          String mentionedAddress, String status, String matchMethod) {
        TicketEmailMessage record = new TicketEmailMessage();
        record.setTicket(ticket);
        record.setTicketReply(reply);
        record.setMessageId(truncate(mail.messageId(), 998));
        record.setInReplyTo(truncate(mail.inReplyTo(), 998));
        record.setReferencesHeader(String.join(" ", mail.references()));
        record.setFromAddress(truncate(mail.fromAddress(), 320));
        record.setFromName(truncate(mail.fromName(), 255));
        record.setToAddresses(String.join(", ", mail.to()));
        record.setCcAddresses(String.join(", ", mail.cc()));
        record.setMailboxAddress(truncate(mentionedAddress, 320));
        record.setSubject(mail.subject());
        record.setSentAt(mail.sentAt());
        record.setReceivedAt(LocalDateTime.now());
        record.setStatus(status);
        record.setMatchMethod(matchMethod);

        emailMessageRepository.saveAndFlush(record);

        Long ticketId = ticket != null ? ticket.getId() : null;
        boolean acknowledge = TicketEmailMessage.STATUS_TICKET_CREATED.equals(status);
        return new Result(status, ticketId, acknowledge ? mail.fromAddress() : null);
    }

    // ---------------------------------------------------------------- sender → client

    /**
     * Which client and user a sender address belongs to.
     *
     * @param client never null — resolution falls through to the intake client
     * @param user   null when the sender has no account, which is allowed:
     *               {@code ticket.creator_user_id} is nullable and every read path already
     *               guards it
     */
    private record SenderMatch(Client client, User user, String method) {
    }

    private SenderMatch resolveSender(String fromAddress) {
        List<User> exact = userRepository.findByEmailIgnoreCaseOrderByIdAsc(fromAddress);
        for (User user : exact) {
            if (user.getClient() != null) {
                return new SenderMatch(user.getClient(), user, TicketEmailMessage.MATCH_USER_EMAIL);
            }
        }

        String domain = domainOf(fromAddress);
        if (domain != null && !PUBLIC_MAIL_DOMAINS.contains(domain)
                && !csv(internalDomainsCsv).contains(domain)) {
            List<User> colleagues = userRepository.findByEmailEndingWithIgnoreCaseOrderByIdAsc("@" + domain);
            for (User colleague : colleagues) {
                if (colleague.getClient() != null) {
                    // The colleague is evidence of the company, not the author of the mail — the
                    // ticket gets their client but no creator, so it is never attributed to a
                    // person who did not write it.
                    return new SenderMatch(colleague.getClient(), null, TicketEmailMessage.MATCH_DOMAIN);
                }
            }
        }

        return new SenderMatch(intakeClient(), null, TicketEmailMessage.MATCH_UNIDENTIFIED);
    }

    /**
     * The client that holds tickets from senders we could not place, created on first use.
     *
     * <p>It exists only because {@code ticket.client_id} is NOT NULL and dropping the mail is not
     * an option. Tickets under it are a triage queue: an admin moves them to the right client.
     */
    private Client intakeClient() {
        return clientRepository.findByName(intakeClientName).orElseGet(() -> {
            Client client = new Client(intakeClientName);
            logger.info("Creating the '{}' client to hold tickets from unidentified senders", intakeClientName);
            return clientRepository.save(client);
        });
    }

    private String domainOf(String address) {
        int at = address.lastIndexOf('@');
        if (at < 0 || at == address.length() - 1) {
            return null;
        }
        return address.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- content

    /**
     * The mail body, safe to store and render.
     *
     * <p>An HTML mail is attacker-controlled markup that a support agent's browser will render,
     * so it goes through the same sanitiser as a typed reply. A plain-text body is escaped for
     * the same reason — the UI renders both through the same component.
     */
    private String safeBody(ParsedEmail mail) {
        String body = mail.body();
        if (body == null || body.isBlank()) {
            return "(This email had no readable text content.)";
        }
        if (mail.bodyIsHtml()) {
            return TicketHtmlSanitizer.sanitize(body);
        }
        return TicketHtmlSanitizer.sanitize(escapeHtml(body).replace("\n", "<br/>"));
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String subjectForNewTicket(ParsedEmail mail) {
        String subject = REPLY_PREFIX.matcher(mail.subject()).replaceAll("").trim();
        if (subject.isBlank()) {
            subject = "(No subject) — from " + mail.fromAddress();
        }
        // ticket.subject is a plain varchar; keep it inside a sane bound.
        return truncate(subject, 255);
    }

    /**
     * Uploads what the mail carried, returning the JSON the reply stores, or null for none.
     *
     * <p>A file that the attachment policy rejects is dropped rather than failing the message:
     * losing the customer's text because they attached a .zip would be the worse outcome, and
     * the rejection is logged.
     */
    private String uploadAttachments(ParsedEmail mail) {
        if (mail.attachments().isEmpty()) {
            return null;
        }
        List<Map<String, String>> uploaded = new ArrayList<>();
        for (ParsedEmail.Attachment attachment : mail.attachments()) {
            try {
                TicketAttachmentPolicy.validate(
                        attachment.filename(), attachment.contentType(), attachment.content().length);
            } catch (IllegalArgumentException e) {
                logger.warn("Dropping attachment '{}' on message {}: {}",
                        attachment.filename(), mail.messageId(), e.getMessage());
                continue;
            }
            try {
                String key = "tickets/" + UUID.randomUUID() + "/"
                        + UNSAFE_KEY_CHARS.matcher(attachment.filename()).replaceAll("_");
                fileStorageService.uploadFileBytes(key, attachment.content(), attachment.contentType());
                uploaded.add(Map.of(
                        "key", key,
                        "name", attachment.filename(),
                        "type", attachment.contentType()));
            } catch (Exception e) {
                logger.error("Failed to store attachment '{}' from message {}",
                        attachment.filename(), mail.messageId(), e);
            }
        }
        if (uploaded.isEmpty()) {
            return null;
        }
        try {
            return new ObjectMapper().writeValueAsString(uploaded);
        } catch (Exception e) {
            logger.error("Failed to serialise attachments for message {}", mail.messageId(), e);
            return null;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private List<String> groupAddresses() {
        List<String> all = new ArrayList<>(csv(technicalAdminEmailsCsv));
        if (billingAdminEmail != null && !billingAdminEmail.isBlank()) {
            all.add(billingAdminEmail.trim().toLowerCase(Locale.ROOT));
        }
        return all;
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void evictCountCache(String category) {
        if (category != null) {
            redisCacheService.evict("ticket_count_" + category);
        }
    }

    /** Mirrors TicketService: notify the UI only once the write is actually visible. */
    private void broadcastTicketChange() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send();
                }
            });
        } else {
            send();
        }
    }

    private void send() {
        try {
            messagingTemplate.convertAndSend("/topic/tickets", "updated");
        } catch (Exception e) {
            logger.warn("Failed to broadcast ticket change via WebSocket: {}", e.getMessage());
        }
    }
}
