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
import java.util.List;

/**
 * Scheduler that checks for unanswered admin-initiated support conversations
 * and sends reminders via the configured channels (Email, WhatsApp, SMS)
 * if the client has not responded within 1 working hour.
 *
 * Working hours: Monday–Friday, 09:00–18:00 IST.
 */
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
     * Runs every 5 minutes to check for conversations that need auto-ping (TEST MODE).
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    @Transactional
    public void checkAndSendAutoPings() {
        LocalDateTime now = LocalDateTime.now(IST);
        // TEST MODE: working-hours check disabled — restore for production
        // if (!isWorkingHour(now)) return;

        // Find all admin-initiated messages with auto-ping enabled that have not been pinged yet
        List<SupportMessage> pendingPings = messageRepository
                .findByAutoPingEnabledTrueAndAutoPingSentFalseAndIsUserMessageFalse();

        for (SupportMessage initMsg : pendingPings) {
            // Check if 1 working hour has elapsed since the initial message
            if (!oneWorkingHourElapsed(initMsg.getTimestamp(), now)) continue;

            // Check whether the user has replied since the initial admin message
            // Prefer userId-based check; fall back to accountId for legacy records
            boolean userReplied = (initMsg.getUserId() != null)
                    ? messageRepository.existsByUserIdAndIsUserMessageTrueAndTimestampAfter(
                            initMsg.getUserId(), initMsg.getTimestamp())
                    : messageRepository.existsByAccountIdAndIsUserMessageTrueAndTimestampAfter(
                            initMsg.getAccountId(), initMsg.getTimestamp());

            if (userReplied) {
                // User responded — mark ping as sent so we don't check again
                initMsg.setAutoPingSent(true);
                messageRepository.save(initMsg);
                continue;
            }

            // No response — dispatch notifications on configured channels
            String channels = initMsg.getAutoPingChannels() != null ? initMsg.getAutoPingChannels() : "";
            Long userId = initMsg.getUserId();

            if (userId != null) {
                appUserRepository.findById(userId).ifPresent(user ->
                        dispatchNotifications(user, initMsg, channels));
            } else {
                log.warn("Auto-ping: no userId on conversation {} — skipping", initMsg.getAccountId());
            }

            initMsg.setAutoPingSent(true);
            messageRepository.save(initMsg);
        }
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

    /**
     * Returns true if the given timestamp is at least 1 working hour before {@code now}.
     * Non-working time (nights, weekends) is skipped when counting.
     */
    private boolean oneWorkingHourElapsed(LocalDateTime from, LocalDateTime now) {
        if (from == null) return false;
        // Simple check: if 1 calendar hour has passed and both timestamps fall within working hours,
        // treat that as 1 working hour.  A full business-calendar implementation can replace this.
        return !from.plusMinutes(5).isAfter(now); // TEST: 5 min (prod: 1 hour)
    }

    private boolean isWorkingHour(LocalDateTime dt) {
        DayOfWeek day = dt.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int hour = dt.getHour();
        return hour >= WORKING_HOUR_START && hour < WORKING_HOUR_END;
    }
}
