package com.xammer.cloud.service.call;

import com.xammer.cloud.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reaches people a call could not.
 *
 * A ticket call only rings someone with the app open. When nobody answers — no
 * agent was online, or the ring simply timed out — the missed party may not be
 * logged in at all, and the in-app badge is no help until they next return. This
 * closes that gap with an email, so a call that fell on an empty room still
 * surfaces as something to follow up on.
 *
 * Runs on a background thread: sending mail must never sit inside the call's
 * teardown transaction, and one recipient's dead mailbox must not stop the
 * others from being told. Everything it needs is passed in as plain values, so
 * nothing here touches the database or a lazy association off the request thread.
 */
@Service
public class CallNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CallNotificationService.class);

    private final EmailService emailService;

    /** Public site URL, used to build a deep link to the ticket. Blank disables the link. */
    @Value("${app.frontend.url:}")
    private String frontendUrl;

    public CallNotificationService(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Emails everyone who missed a call.
     *
     * @param toEmails      recipient addresses, already filtered to non-blank
     * @param callerName    who placed the call, for the subject and body
     * @param ticketId      the ticket the call was about
     * @param ticketSubject the ticket subject, may be null
     * @param ticketPath    role-appropriate detail route, e.g. "/billops-ticket-detail"
     * @param video         true for a video call, false for audio
     */
    @Async
    public void sendMissedCallEmails(List<String> toEmails, String callerName, Long ticketId,
            String ticketSubject, String ticketPath, boolean video) {
        if (toEmails == null || toEmails.isEmpty()) {
            return;
        }
        String caller = (callerName == null || callerName.isBlank()) ? "A caller" : callerName;
        String subject = "Missed call on Ticket #" + ticketId;
        String html = buildHtml(caller, ticketId, ticketSubject, ticketPath, video);

        for (String to : toEmails) {
            try {
                emailService.sendHtmlEmail(to, subject, html);
            } catch (Exception e) {
                // One bad address should not deny everyone else the notification.
                log.warn("Failed to send missed-call email to {} for ticket {}: {}",
                        to, ticketId, e.getMessage());
            }
        }
        log.info("Sent {} missed-call email(s) for ticket {}", toEmails.size(), ticketId);
    }

    private String buildHtml(String caller, Long ticketId, String ticketSubject,
            String ticketPath, boolean video) {
        String kind = video ? "video" : "audio";
        String safeCaller = escapeHtml(caller);
        String safeSubject = ticketSubject != null && !ticketSubject.isBlank()
                ? escapeHtml(ticketSubject)
                : null;

        StringBuilder body = new StringBuilder();
        body.append("<div style=\"font-family:Arial,Helvetica,sans-serif;color:#111;max-width:520px;margin:0 auto;\">");
        body.append("<h2 style=\"font-size:18px;margin:0 0 12px;\">You missed a ").append(kind).append(" call</h2>");
        body.append("<p style=\"font-size:14px;line-height:1.5;margin:0 0 8px;\"><strong>")
                .append(safeCaller).append("</strong> tried to reach you about ");
        body.append("<strong>Ticket #").append(ticketId).append("</strong>");
        if (safeSubject != null) {
            body.append(" — ").append(safeSubject);
        }
        body.append(".</p>");
        body.append("<p style=\"font-size:14px;line-height:1.5;margin:0 0 16px;color:#555;\">"
                + "Open the ticket to call back or reply.</p>");

        String link = ticketLink(ticketPath, ticketId);
        if (link != null) {
            body.append("<a href=\"").append(link).append("\" ")
                    .append("style=\"display:inline-block;background:#4f46e5;color:#fff;text-decoration:none;")
                    .append("font-size:14px;font-weight:600;padding:10px 18px;border-radius:6px;\">")
                    .append("Open ticket #").append(ticketId).append("</a>");
        }
        body.append("<p style=\"font-size:12px;color:#999;margin:20px 0 0;\">"
                + "This is an automated notification from XamOps support.</p>");
        body.append("</div>");
        return body.toString();
    }

    /** Null when no public URL is configured, so the email degrades to text only. */
    private String ticketLink(String ticketPath, Long ticketId) {
        if (frontendUrl == null || frontendUrl.isBlank() || ticketPath == null || ticketPath.isBlank()) {
            return null;
        }
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return base + ticketPath + "?id=" + ticketId;
    }

    private String escapeHtml(String raw) {
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
