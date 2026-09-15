package com.xammer.billops.service;

import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.cloud.domain.AppUser;
import com.xammer.cloud.messaging.MessageKind;
import com.xammer.cloud.messaging.MobileNotificationDispatcher;
import com.xammer.cloud.messaging.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class SupportAutoNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupportAutoNotificationScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int WORKING_HOUR_START = 9;
    private static final int WORKING_HOUR_END = 18;

    private final SupportMessageRepository messageRepository;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;
    private final MobileNotificationDispatcher mobileNotificationDispatcher;

    /** Same property the Slack/Teams/Google Chat reply buttons use, so links stay consistent. */
    @Value("${messaging.portal.base-url:https://app.xamops.com}")
    private String portalBaseUrl;

    public SupportAutoNotificationScheduler(SupportMessageRepository messageRepository,
                                            AppUserRepository appUserRepository,
                                            EmailService emailService,
                                            MobileNotificationDispatcher mobileNotificationDispatcher) {
        this.messageRepository = messageRepository;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
        this.mobileNotificationDispatcher = mobileNotificationDispatcher;
    }

    /**
     * Runs every 5 minutes during working hours.
     * DB work happens inside collectAndMarkPending(); email dispatch runs after
     * the transaction is committed so the connection is not held during SMTP I/O.
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void checkAndSendAutoPings() {
        LocalDateTime now = LocalDateTime.now(IST);
        if (!isWorkingHour(now)) return;

        List<PendingNotification> toNotify = collectAndMarkPending(now);
        for (PendingNotification n : toNotify) {
            dispatchNotifications(n.user, n.msg, n.channels, n.entitledChannels);
        }
    }

    /**
     * Loads pending pings, marks them as sent, and returns notification data —
     * all within a single short-lived transaction. Email sending must NOT happen
     * inside this method to avoid holding the connection during SMTP I/O.
     */
    @Transactional
    public List<PendingNotification> collectAndMarkPending(LocalDateTime now) {
        List<SupportMessage> pendingPings = messageRepository
                .findByAutoPingEnabledTrueAndAutoPingSentFalseAndIsUserMessageFalse();

        List<PendingNotification> result = new ArrayList<>();

        for (SupportMessage initMsg : pendingPings) {
            if (!oneWorkingHourElapsed(initMsg.getTimestamp(), now)) continue;

            boolean userReplied = (initMsg.getUserId() != null)
                    ? messageRepository.existsByUserIdAndIsUserMessageTrueAndTimestampAfter(
                            initMsg.getUserId(), initMsg.getTimestamp())
                    : messageRepository.existsByAccountIdAndIsUserMessageTrueAndTimestampAfter(
                            initMsg.getAccountId(), initMsg.getTimestamp());

            initMsg.setAutoPingSent(true);
            messageRepository.save(initMsg);

            if (userReplied) continue;

            if (initMsg.getUserId() == null) {
                log.warn("Auto-ping: no userId on conversation {} — skipping", initMsg.getAccountId());
                continue;
            }

            appUserRepository.findById(initMsg.getUserId()).ifPresent(user -> {
                String channels = initMsg.getAutoPingChannels() != null ? initMsg.getAutoPingChannels() : "";
                // Resolved here, while the session is still open — AppUser.client is LAZY and
                // dispatch runs post-commit. SMS and WhatsApp are billed per message, so a client
                // with neither granted gets email only.
                Set<NotificationChannel> entitled = (user.getClient() != null)
                        ? NotificationChannel.entitled(
                                user.getClient().isSmsNotificationsEnabled(),
                                user.getClient().isWhatsappNotificationsEnabled())
                        : EnumSet.noneOf(NotificationChannel.class);
                result.add(new PendingNotification(user, initMsg, channels, entitled));
            });
        }

        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void dispatchNotifications(AppUser user, SupportMessage msg, String channels,
                                       Set<NotificationChannel> entitledChannels) {
        String accountName = msg.getAccountName() != null ? msg.getAccountName() : msg.getAccountId();
        String subject = "Reminder: Unread support message from XamOps – " + accountName;
        String body = buildEmailBody(user.getUsername(), accountName);

        // Exact parse of the stored CSV. The previous String.contains() checks also matched any
        // channel name containing another's, and gave no list of what was actually supported.
        Set<NotificationChannel> selected = NotificationChannel.fromCsv(channels);

        if (selected.contains(NotificationChannel.EMAIL)
                && user.getEmail() != null && !user.getEmail().isBlank()) {
            try {
                emailService.sendHtmlEmail(user.getEmail(), subject, body);
                log.info("Auto-ping email sent to {} for account {}", user.getEmail(), accountName);
            } catch (Exception e) {
                log.error("Auto-ping email failed for {}: {}", user.getEmail(), e.getMessage());
            }
        }

        // Three independent gates, all of which must permit a channel: what the admin ticked for
        // this conversation (selected), what this customer is entitled to and billed for
        // (entitledChannels), and what the deployment is configured to send at all (inside the
        // dispatcher). The recipient's number is never logged in full.
        Set<NotificationChannel> mobile = EnumSet.noneOf(NotificationChannel.class);
        mobile.addAll(selected);
        mobile.retainAll(entitledChannels);

        if (!mobile.isEmpty() && user.getContact() != null && !user.getContact().isBlank()) {
            // Positional, matching the xamops_support_reminder template: "Hi {{1}}, you have an
            // unread support message ... regarding {{2}}." The portal link is static text in the
            // template rather than a variable — Meta often rejects URLs passed as variables.
            List<String> variables = List.of(user.getUsername(), accountName);

            mobileNotificationDispatcher.dispatch(user.getContact(), variables, MessageKind.SUPPORT, mobile);
        }
    }

    private String buildEmailBody(String username, String accountName) {
        return "<div style='font-family:Arial,sans-serif;max-width:600px'>"
                + "<h2 style='color:#4f46e5'>You have an unread support message</h2>"
                + "<p>Hi <strong>" + username + "</strong>,</p>"
                + "<p>Our support team sent you a message regarding account <strong>" + accountName + "</strong> "
                + "over an hour ago and we haven't heard back yet.</p>"
                + "<p>Please log in to the XamOps portal to view and respond to the message.</p>"
                + "<p style='color:#6b7280;font-size:12px'>This is an automated reminder. "
                + "If you have already responded, please disregard this email.</p>"
                + "</div>";
    }

    private boolean oneWorkingHourElapsed(LocalDateTime from, LocalDateTime now) {
        if (from == null) return false;
        return !from.plusHours(1).isAfter(now);
    }

    private boolean isWorkingHour(LocalDateTime dt) {
        DayOfWeek day = dt.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int hour = dt.getHour();
        return hour >= WORKING_HOUR_START && hour < WORKING_HOUR_END;
    }

    static class PendingNotification {
        final AppUser user;
        final SupportMessage msg;
        final String channels;
        /**
         * Mobile channels this user's client is entitled to, resolved inside the transaction.
         *
         * <p>Carried on this record rather than read at dispatch time because {@code AppUser.client}
         * is {@code FetchType.LAZY} and dispatch deliberately runs after the transaction has
         * committed — touching the association there would throw
         * {@code LazyInitializationException}.
         */
        final Set<NotificationChannel> entitledChannels;

        PendingNotification(AppUser user, SupportMessage msg, String channels,
                            Set<NotificationChannel> entitledChannels) {
            this.user = user;
            this.msg = msg;
            this.channels = channels;
            this.entitledChannels = entitledChannels;
        }
    }
}
