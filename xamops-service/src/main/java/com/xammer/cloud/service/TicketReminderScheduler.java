package com.xammer.cloud.service;

import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.cloud.repository.TicketRepository;
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
public class TicketReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(TicketReminderScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int WORKING_HOUR_START = 9;
    private static final int WORKING_HOUR_END = 18;

    private final TicketRepository ticketRepository;
    private final EmailService emailService;

    public TicketReminderScheduler(TicketRepository ticketRepository, EmailService emailService) {
        this.ticketRepository = ticketRepository;
        this.emailService = emailService;
    }

    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void checkAndSendReminders() {
        LocalDateTime now = LocalDateTime.now(IST);
        if (!isWorkingHour(now)) return;

        LocalDateTime cutoff = now.minusMinutes(45);
        List<PendingReminder> toNotify = collectAndMarkSent(cutoff, now);
        for (PendingReminder r : toNotify) {
            sendReminderEmail(r);
        }
    }

    @Transactional
    public List<PendingReminder> collectAndMarkSent(LocalDateTime cutoff, LocalDateTime now) {
        List<Ticket> tickets = ticketRepository.findTicketsNeedingReminder(cutoff);
        List<PendingReminder> result = new ArrayList<>();
        for (Ticket ticket : tickets) {
            User creator = ticket.getCreator();
            if (creator == null || creator.getEmail() == null || creator.getEmail().isBlank()) continue;
            ticket.setReminderSentAt(now);
            ticketRepository.save(ticket);
            result.add(new PendingReminder(
                    ticket.getId(), ticket.getSubject(),
                    creator.getEmail(), creator.getUsername(),
                    ticket.getLastAdminReplyAt()));
        }
        return result;
    }

    private void sendReminderEmail(PendingReminder r) {
        try {
            String subject = String.format(
                    "Reminder: Our support team is waiting for your reply — Ticket #%d", r.ticketId);
            emailService.sendHtmlEmail(r.creatorEmail, subject, buildReminderEmailHtml(r));
            log.info("Reminder email sent to {} for ticket #{}", r.creatorEmail, r.ticketId);
        } catch (Exception e) {
            log.error("Failed to send reminder email for ticket #{}: {}", r.ticketId, e.getMessage());
        }
    }

    private String buildReminderEmailHtml(PendingReminder r) {
        String safeSubject = r.ticketSubject != null
                ? r.ticketSubject.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                : "";
        String safeName = r.creatorName != null
                ? r.creatorName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                : "";
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1.0'>"
            + "<title>Reply Reminder</title></head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;"
            + "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>"
            + "<tr><td style='padding:32px 16px;'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
            + "style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;"
            + "overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
            + "<tr><td style='background:#000000;padding:40px;'>"
            + "  <div style='margin-bottom:16px;'><span style='display:inline-block;"
            + "background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;"
            + "font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
            + "  <div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.bell(40) + "</div>"
            + "  <h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>Reminder: Reply Needed</h1>"
            + "  <p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>"
            + "Ticket #" + r.ticketId + ": " + safeSubject + "</p>"
            + "</td></tr>"
            + "<tr><td style='padding:32px 40px;'>"
            + "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>Hi <strong>"
            + safeName + "</strong>,</p>"
            + "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>"
            + "Our support team replied to your ticket over 45 minutes ago and we're still waiting to hear back.</p>"
            + "<div style='background:#fef3c7;border-left:4px solid #f59e0b;"
            + "border-radius:0 8px 8px 0;padding:14px 18px;margin-bottom:24px;'>"
            + "  <p style='margin:0;color:#92400e;font-size:14px;'>"
            + "Please log in to the XamOps portal to view and respond to the latest update on your ticket.</p>"
            + "</div>"
            + "</td></tr>"
            + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;"
            + "padding:24px 40px;text-align:center;'>"
            + "  <span style='display:inline-block;background:#000000;"
            + "padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;"
            + "font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
            + "  <p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>"
            + "Cloud Cost Management &amp; Security Platform</p>"
            + "  <p style='margin:0;color:#cbd5e1;font-size:11px;'>"
            + "This is a one-time automated reminder. &copy; 2026 XamOps. All rights reserved.</p>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }

    boolean isWorkingHour(LocalDateTime dt) {
        DayOfWeek day = dt.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        int hour = dt.getHour();
        return hour >= WORKING_HOUR_START && hour < WORKING_HOUR_END;
    }

    public static class PendingReminder {
        public final Long ticketId;
        public final String ticketSubject;
        public final String creatorEmail;
        public final String creatorName;
        public final LocalDateTime lastAdminReplyAt;

        public PendingReminder(Long ticketId, String ticketSubject, String creatorEmail,
                               String creatorName, LocalDateTime lastAdminReplyAt) {
            this.ticketId = ticketId;
            this.ticketSubject = ticketSubject;
            this.creatorEmail = creatorEmail;
            this.creatorName = creatorName;
            this.lastAdminReplyAt = lastAdminReplyAt;
        }
    }
}
