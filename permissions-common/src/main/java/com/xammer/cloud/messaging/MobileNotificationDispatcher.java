package com.xammer.cloud.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fans one notification out to the mobile channels for a set of recipients.
 *
 * <p>The entry point for callers: alert delivery, report delivery and the BillOps support
 * auto-ping all need the same sequence — resolve the configured template for this
 * {@link MessageKind}, try WhatsApp, try SMS, optionally retry a failed WhatsApp over SMS, and
 * never let one bad recipient stop the rest. Implemented once here rather than three times.
 *
 * <p><b>Never throws.</b> A notification is not worth failing an alert scan or a report run over,
 * which is the same contract the existing email loops follow by catching per recipient.
 *
 * <p><b>Call this outside a transaction.</b> These are network calls on a {@code @Scheduled}
 * thread. {@code SupportAutoNotificationScheduler} already documents why that matters for SMTP:
 * holding a database connection across external I/O is what exhausts the pool.
 */
@Service
public class MobileNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MobileNotificationDispatcher.class);

    /** Channels this dispatcher owns. EMAIL is handled by the existing EmailService path. */
    private static final Set<NotificationChannel> MOBILE_CHANNELS =
            EnumSet.of(NotificationChannel.WHATSAPP, NotificationChannel.SMS);

    private final SmsWhatsAppClient client;
    private final MessagingProperties properties;

    public MobileNotificationDispatcher(SmsWhatsAppClient client, MessagingProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * True when at least one mobile channel could actually send. Callers use this to avoid
     * building message variables, and running the extra recipient query, for nothing.
     */
    public boolean isActive() {
        return properties.isUsable()
                && (properties.getSms().isEnabled() || properties.getWhatsapp().isEnabled());
    }

    /**
     * Sends to every recipient.
     *
     * @param rawContacts stored contact strings; nulls, blanks and duplicates are tolerated
     * @param variables   template values, see the {@code *TextBuilder} variable contracts
     * @param kind        selects the configured template and body pattern
     * @param context     short label for logs, e.g. the account ID
     */
    public List<MessageResult> dispatchAll(Collection<String> rawContacts,
                                           Map<String, String> variables,
                                           MessageKind kind,
                                           String context) {
        List<MessageResult> results = new ArrayList<>();
        if (rawContacts == null || rawContacts.isEmpty()) {
            return results;
        }
        if (!isActive()) {
            log.debug("Mobile notifications inactive — skipping {} dispatch for {}", kind, context);
            return results;
        }

        // Distinct so two schedules sharing a phone number do not send the same person the
        // same message twice, matching how the email path groups by address.
        List<String> distinct = rawContacts.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

        for (String contact : distinct) {
            results.addAll(dispatch(contact, variables, kind));
        }

        long sent = results.stream().filter(MessageResult::isSent).count();
        long failed = results.stream()
                .filter(r -> r.status() == MessageResult.Status.FAILED)
                .count();
        if (sent > 0 || failed > 0) {
            log.info("{} mobile dispatch for {}: {} sent, {} failed across {} recipient(s)",
                    kind, context, sent, failed, distinct.size());
        }
        return results;
    }

    /** Sends to one recipient on every channel that configuration has enabled. */
    public List<MessageResult> dispatch(String rawContact, Map<String, String> variables, MessageKind kind) {
        return dispatch(rawContact, variables, kind, MOBILE_CHANNELS);
    }

    /**
     * Sends to one recipient, restricted to {@code allowed}.
     *
     * <p>Two independent switches apply and both must permit a channel. Configuration decides
     * what the deployment <em>can</em> send; {@code allowed} carries what the caller <em>asked
     * for</em> — for the support auto-ping that is the per-conversation channel list an admin
     * ticked. An admin who chose SMS only should not receive WhatsApp merely because WhatsApp is
     * also configured.
     *
     * @return one result per channel attempted; empty when no channel is both enabled and allowed
     */
    public List<MessageResult> dispatch(String rawContact,
                                        Map<String, String> variables,
                                        MessageKind kind,
                                        Set<NotificationChannel> allowed) {
        List<MessageResult> results = new ArrayList<>();
        if (rawContact == null || rawContact.isBlank()) {
            return results;
        }
        Set<NotificationChannel> permitted = allowed == null ? MOBILE_CHANNELS : allowed;

        MessagingProperties.WhatsApp whatsappCfg = properties.getWhatsapp();
        MessagingProperties.Sms smsCfg = properties.getSms();

        boolean whatsAppEnabled = whatsappCfg.isEnabled()
                && permitted.contains(NotificationChannel.WHATSAPP);
        boolean smsEnabled = smsCfg.isEnabled()
                && permitted.contains(NotificationChannel.SMS);

        boolean whatsAppDelivered = false;

        if (whatsAppEnabled) {
            MessageResult result = client.sendWhatsApp(
                    rawContact, kind.whatsAppTemplateId(whatsappCfg), variables);
            results.add(result);
            whatsAppDelivered = result.isSent();
            logSkip(result, kind);
        }

        // SMS is either a channel in its own right or WhatsApp's backup, and the difference
        // decides whether it sends after a successful WhatsApp. As a backup it must not, or the
        // recipient gets the same alert twice and both are billed. The fallback flag is only
        // meaningful while WhatsApp is enabled — with WhatsApp off, SMS is always primary.
        boolean sendSms;
        if (!smsEnabled) {
            sendSms = false;
        } else if (whatsAppEnabled && whatsappCfg.isSmsFallback()) {
            sendSms = !whatsAppDelivered;
        } else {
            sendSms = true;
        }

        if (sendSms) {
            String body = MessageTemplate.render(kind.smsBodyPattern(smsCfg), variables);
            MessageResult result = client.sendSms(rawContact, body, kind.smsTemplateId(smsCfg));
            results.add(result);
            logSkip(result, kind);
        }

        return results;
    }

    /**
     * A skip is usually a configuration gap that looks like silence in production — an unset
     * template ID, or a contact number that is not dialable. Logged at debug so it is
     * discoverable without adding noise to a normal run.
     */
    private void logSkip(MessageResult result, MessageKind kind) {
        if (result.status() == MessageResult.Status.SKIPPED) {
            log.debug("{} {} skipped: {}", kind, result.channel(), result.detail());
        }
    }
}
