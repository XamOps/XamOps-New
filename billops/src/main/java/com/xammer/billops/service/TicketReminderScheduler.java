package com.xammer.billops.service;

import com.xammer.cloud.domain.Ticket;
import com.xammer.cloud.domain.User;
import com.xammer.billops.repository.TicketRepository;
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
        String safeSubject = escapeHtml(r.ticketSubject);
        String safeName    = escapeHtml(r.creatorName);
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>"
                + "<body style=\"margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#f3f4f6;\">"
                + "<div style=\"max-width:620px;margin:32px auto;background:white;border-radius:16px;"
                + "overflow:hidden;box-shadow:0 4px 16px rgba(0,0,0,0.08);\">"
                + "<div style=\"background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:24px;\">"
                + "<p style=\"margin:0;color:rgba(255,255,255,.75);font-size:12px;font-weight:600;"
                + "text-transform:uppercase;letter-spacing:.06em;\">Ticket #" + r.ticketId + "</p>"
                + "<h1 style=\"margin:4px 0 0;color:white;font-size:18px;font-weight:700;\">"
                + safeSubject + "</h1>"
                + "</div>"
                + "<div style=\"padding:24px;\">"
                + "<p style=\"margin:0 0 16px;color:#374151;font-size:15px;\">Hi <strong>"
                + safeName + "</strong>,</p>"
                + "<p style=\"margin:0 0 16px;color:#374151;font-size:15px;\">"
                + "Our support team replied to your ticket over 45 minutes ago and we're still waiting to hear back.</p>"
                + "<div style=\"background:#fef3c7;border-left:4px solid #f59e0b;"
                + "border-radius:0 8px 8px 0;padding:14px 18px;margin-bottom:16px;\">"
                + "<p style=\"margin:0;color:#92400e;font-size:14px;\">Please log in to the XamOps portal to view and respond.</p>"
                + "</div>"
                + "</div>"
                + "<div style=\"padding:14px 24px;background:#f9fafb;border-top:1px solid #f3f4f6;text-align:center;\">"
                + "<p style=\"margin:0;font-size:11px;color:#9ca3af;\">This is a one-time automated reminder from "
                + "<strong style=\"color:#667eea;\">XamOps Support</strong>.</p>"
                + "</div>"
                + "</div></body></html>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
