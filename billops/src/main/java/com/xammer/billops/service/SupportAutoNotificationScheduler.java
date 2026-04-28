package com.xammer.billops.service;

import com.xammer.billops.domain.SupportMessage;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.SupportMessageRepository;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Component
public class SupportAutoNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SupportAutoNotificationScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int WORKING_HOUR_START = 9;
    private static final int WORKING_HOUR_END = 18;

    private final SupportMessageRepository messageRepository;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;

    public SupportAutoNotificationScheduler(SupportMessageRepository messageRepository,
                                            AppUserRepository appUserRepository,
                                            EmailService emailService) {
        this.messageRepository = messageRepository;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
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
            dispatchNotifications(n.user, n.msg, n.channels);
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
                result.add(new PendingNotification(user, initMsg, channels));
            });
        }

        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void dispatchNotifications(AppUser user, SupportMessage msg, String channels) {
        String accountName = msg.getAccountName() != null ? msg.getAccountName() : msg.getAccountId();
        String subject = "Reminder: Unread support message from XamOps – " + accountName;
        String body = buildEmailBody(user.getUsername(), accountName);

        if (channels.contains("EMAIL") && user.getEmail() != null && !user.getEmail().isBlank()) {
            try {
                emailService.sendHtmlEmail(user.getEmail(), subject, body);
                log.info("Auto-ping email sent to {} for account {}", user.getEmail(), accountName);
            } catch (Exception e) {
                log.error("Auto-ping email failed for {}: {}", user.getEmail(), e.getMessage());
            }
        }

        if (channels.contains("WHATSAPP") && user.getContact() != null && !user.getContact().isBlank()) {
            // TODO: integrate WhatsApp Business API / Twilio WhatsApp
            log.info("Auto-ping WhatsApp stub — would send to {} for account {}", user.getContact(), accountName);
        }

        if (channels.contains("SMS") && user.getContact() != null && !user.getContact().isBlank()) {
            // TODO: integrate Twilio SMS / AWS SNS
            log.info("Auto-ping SMS stub — would send to {} for account {}", user.getContact(), accountName);
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

        PendingNotification(AppUser user, SupportMessage msg, String channels) {
            this.user = user;
            this.msg = msg;
            this.channels = channels;
        }
    }
}
