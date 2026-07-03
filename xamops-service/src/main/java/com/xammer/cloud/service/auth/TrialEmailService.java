package com.xammer.cloud.service.auth;

import com.xammer.cloud.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

/**
 * Builds and sends the trial lifecycle emails (welcome, 5-day reminder,
 * last-day, and contact-sales lead) using the shared EmailService and the
 * XamOps HTML styling. All sends are best-effort — failures are logged by the
 * caller and never block signup or the scheduler.
 */
@Service
@Slf4j
public class TrialEmailService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    @Autowired
    private EmailService emailService;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${app.sales-email:sales@xamops.com}")
    private String salesEmail;

    public void sendWelcome(String toEmail, String name, LocalDateTime trialEndAt) {
        String endDate = trialEndAt != null ? trialEndAt.format(DATE_FMT) : "";
        String body = shell(
                "Welcome to XamOps",
                "Your 14-day free trial has started",
                "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>Hi " + safe(name) + ",</p>"
                + "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>Your XamOps account is ready. "
                + "You now have full access to cloud FinOps, security, and DevOps tooling across AWS, Azure, and GCP.</p>"
                + "<p style='margin:0 0 20px;color:#374151;font-size:15px;'>Your free trial runs until "
                + "<strong>" + endDate + "</strong> (14 days).</p>"
                + ctaButton("Go to your dashboard", frontendUrl + "/unified-dashboard"));
        safeSend(toEmail, "Welcome to XamOps — your 14-day trial has started", body);
    }

    public void sendFiveDayReminder(String toEmail, String name, long daysRemaining) {
        String body = shell(
                "5 days left in your trial",
                "Keep your XamOps access going",
                "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>Hi " + safe(name) + ",</p>"
                + "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>You have <strong>" + daysRemaining
                + " days</strong> left in your XamOps free trial. To continue without interruption, reach out to our "
                + "team to extend your trial or move to a paid plan.</p>"
                + ctaButton("Contact our team", "mailto:" + salesEmail + "?subject=Extend%20my%20XamOps%20trial")
                + "<p style='margin:16px 0 0;color:#64748b;font-size:13px;'>Or email us at " + salesEmail + ".</p>");
        safeSend(toEmail, "5 days left in your XamOps trial", body);
    }

    public void sendLastDay(String toEmail, String name) {
        String feedbackUrl = frontendUrl + "/feedback";
        String body = shell(
                "Your trial ends today",
                "We'd love your feedback",
                "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>Hi " + safe(name) + ",</p>"
                + "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>Today is the last day of your XamOps free trial. "
                + "We'd really appreciate a moment of your time to tell us how it went — and if you'd like to keep going, "
                + "our team can extend your trial or set you up on a paid plan.</p>"
                + ctaButton("Share your feedback", feedbackUrl)
                + "<p style='margin:16px 0 0;color:#374151;font-size:15px;'>To extend or upgrade, contact us at "
                + "<a href='mailto:" + salesEmail + "?subject=Extend%20my%20XamOps%20trial' style='color:#000;'>"
                + salesEmail + "</a>.</p>");
        safeSend(toEmail, "Your XamOps trial ends today", body);
    }

    /** Sends a lead to the sales inbox when a user clicks "Contact Sales". */
    public void sendContactSalesLead(String userName, String userEmail, String companyName, long daysRemaining) {
        String body = shell(
                "New Contact Sales request",
                "Trial user wants to get in touch",
                "<table style='width:100%;border-collapse:collapse;font-size:15px;color:#374151;'>"
                + row("Name", safe(userName))
                + row("Email", safe(userEmail))
                + row("Company", safe(companyName))
                + row("Trial days remaining", String.valueOf(daysRemaining))
                + "</table>");
        // Throwing here lets the controller return an error so the user can retry.
        emailService.sendHtmlEmail(salesEmail, "XamOps — Contact Sales request from " + safe(companyName), body);
    }

    private void safeSend(String to, String subject, String html) {
        try {
            emailService.sendHtmlEmail(to, subject, html);
        } catch (Exception e) {
            log.error("Failed to send trial email '{}' to {}: {}", subject, to, e.getMessage());
        }
    }

    private static String row(String label, String value) {
        return "<tr><td style='padding:8px 12px;border-bottom:1px solid #e2e8f0;font-weight:600;'>" + label
                + "</td><td style='padding:8px 12px;border-bottom:1px solid #e2e8f0;'>" + value + "</td></tr>";
    }

    private static String ctaButton(String label, String href) {
        return "<div style='text-align:center;margin:8px 0 4px;'>"
                + "<a href='" + href + "' style='display:inline-block;background:#000;color:#fff;text-decoration:none;"
                + "padding:12px 28px;border-radius:8px;font-weight:700;font-size:15px;'>" + label + "</a></div>";
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String shell(String heading, String subheading, String content) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1.0'></head>"
                + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
                + "<tr><td style='background:#000;padding:40px;'>"
                + "<div style='margin-bottom:16px;'><span style='display:inline-block;background:#fff;color:#000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
                + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>" + heading + "</h1>"
                + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>" + subheading + "</p>"
                + "</td></tr>"
                + "<tr><td style='padding:32px 40px;'>" + content + "</td></tr>"
                + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>"
                + "<span style='display:inline-block;background:#000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
                + "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>"
                + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>"
                + "</td></tr></table></td></tr></table></body></html>";
    }
}
