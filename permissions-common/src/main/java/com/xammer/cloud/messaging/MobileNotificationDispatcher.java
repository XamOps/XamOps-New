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
    private final MessageThrottle throttle;

    public MobileNotificationDispatcher(SmsWhatsAppClient client,
                                        MessagingProperties properties,
                                        MessageThrottle throttle) {
        this.client = client;
        this.properties = properties;
        this.throttle = throttle;
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
                                           List<String> variables,
                                           MessageKind kind,
                                           String context) {
        return dispatchAll(rawContacts, variables, kind, context, MOBILE_CHANNELS);
    }

    /**
     * Sends to every recipient, restricted to {@code allowed}.
     *
     * <p>The allow-set is how per-customer entitlement reaches this layer. SMS and WhatsApp are
     * billed per message and granted separately per contract, so a caller passes only the channels
     * that customer is entitled to; configuration still decides what the deployment can send at
     * all. An empty set sends nothing and costs no provider call.
     */
    public List<MessageResult> dispatchAll(Collection<String> rawContacts,
                                           List<String> variables,
                                           MessageKind kind,
                                           String context,
                                           Set<NotificationChannel> allowed) {
        List<MessageResult> results = new ArrayList<>();
        if (rawContacts == null || rawContacts.isEmpty()) {
            return results;
        }
        if (allowed == null || allowed.isEmpty()) {
            log.debug("No entitled mobile channels — skipping {} dispatch for {}", kind, context);
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

        // Throttle per (kind, context, recipient). The alert scan runs every 4 hours and a resource
        // sitting on its threshold re-breaches on most scans, so without this one stubborn disk
        // bills six WhatsApp messages a day to every recipient. Email is unaffected and still
        // carries the full detail each scan, so nothing is hidden by a suppressed message.
        //
        // `context` participates in the key, so it must be stable per account and kind — callers
        // pass a deterministic label like "account 123456789012", not anything timestamped.
        List<String> due = distinct.stream()
                .filter(c -> throttle.tryAcquire(kind + ":" + context + ":" + c, properties.getCooldown()))
                .toList();

        int suppressed = distinct.size() - due.size();
        if (suppressed > 0) {
            log.info("{} mobile dispatch for {}: {} of {} recipient(s) suppressed by the {} cooldown",
                    kind, context, suppressed, distinct.size(), properties.getCooldown());
        }
        if (due.isEmpty()) {
            return results;
        }

        for (String contact : due) {
            results.addAll(dispatch(contact, variables, kind, allowed));
        }

        long sent = results.stream().filter(MessageResult::isSent).count();
        long failed = results.stream()
                .filter(r -> r.status() == MessageResult.Status.FAILED)
                .count();
        if (sent > 0 || failed > 0) {
            log.info("{} mobile dispatch for {}: {} sent, {} failed across {} recipient(s)",
                    kind, context, sent, failed, due.size());
        }
        return results;
    }

    /** Sends to one recipient on every channel that configuration has enabled. */
    public List<MessageResult> dispatch(String rawContact, List<String> variables, MessageKind kind) {
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
                                        List<String> variables,
                                        MessageKind kind,
                                        Set<NotificationChannel> allowed) {
        List<MessageResult> results = new ArrayList<>();
        if (rawContact == null || rawContact.isBlank()) {
            return results;
        }
        Set<NotificationChannel> permitted = allowed == null ? MOBILE_CHANNELS : allowed;

        MessagingProperties.WhatsApp whatsappCfg = properties.getWhatsapp();
        MessagingProperties.Sms smsCfg = properties.getSms();

        // Three conditions per channel: the channel is on, the caller allowed it for this customer,
        // and this kind of notification is permitted on it. The last one is how a kind is withheld
        // without un-configuring its template — REPORT is currently off for WhatsApp.
        boolean whatsAppEnabled = whatsappCfg.isEnabled()
                && permitted.contains(NotificationChannel.WHATSAPP)
                && whatsappCfg.getEnabledKinds().contains(kind);
        boolean smsEnabled = smsCfg.isEnabled()
                && permitted.contains(NotificationChannel.SMS)
                && smsCfg.getEnabledKinds().contains(kind);

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
