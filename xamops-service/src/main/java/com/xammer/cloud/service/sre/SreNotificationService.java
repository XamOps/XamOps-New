package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.SreNotificationRepository;
import com.xammer.cloud.service.EmailIcons;
import com.xammer.cloud.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SreNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(SreNotificationService.class);

    private final SreNotificationRepository notificationRepo;
    private final EmailService emailService;
    private final ClientRepository clientRepo;

    public SreNotificationService(SreNotificationRepository notificationRepo,
                                  EmailService emailService,
                                  ClientRepository clientRepo) {
        this.notificationRepo = notificationRepo;
        this.emailService = emailService;
        this.clientRepo = clientRepo;
    }

    public void notifyComplete(SreInvestigation inv, Long clientId) {
        SreNotification notif = new SreNotification();
        notif.setClientId(clientId);
        notif.setInvestigationId(inv.getId());
        notif.setTitle("Investigation Complete: " + inv.getResourceName());
        notif.setMessage(inv.getReportSummary() != null ? inv.getReportSummary() : "Root cause analysis is ready.");
        notificationRepo.save(notif);

        clientRepo.findById(clientId).ifPresent(client -> {
            if (client.getEmail() != null && !client.getEmail().isBlank()) {
                try {
                    emailService.sendHtmlEmail(
                        client.getEmail(),
                        "SRE Investigation Complete: " + inv.getResourceName(),
                        buildCompleteEmailHtml(inv)
                    );
                } catch (Exception e) {
                    logger.warn("Failed to send SRE notification email to {}: {}", client.getEmail(), e.getMessage());
                }
            }
        });
    }

    public void notifyFailed(SreInvestigation inv, Long clientId) {
        SreNotification notif = new SreNotification();
        notif.setClientId(clientId);
        notif.setInvestigationId(inv.getId());
        notif.setTitle("Investigation Failed: " + inv.getResourceName());
        notif.setMessage("Investigation could not be completed: " + inv.getErrorMessage());
        notificationRepo.save(notif);
    }

    private String buildCompleteEmailHtml(SreInvestigation inv) {
        String summary = inv.getReportSummary() != null ? inv.getReportSummary() : "No summary available.";
        String rootCause = inv.getReportRootCause() != null ? inv.getReportRootCause() : "No root cause identified.";

        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>SRE Investigation Complete</title></head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td style='padding:32px 16px;'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
            + "<tr><td style='background:#000000;padding:40px;'>"
            + "<div style='margin-bottom:16px;'><span style='display:inline-block;background:#ffffff;color:#000000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
            + "<div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.search(40) + "</div>"
            + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>SRE Investigation Complete</h1>"
            + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>Root cause analysis is ready</p>"
            + "</td></tr>"
            + "<tr><td style='padding:32px 40px;'>"
            + "<p style='margin:0 0 16px;color:#374151;font-size:15px;'>The automated investigation for the resource below has completed.</p>"
            + "<div style='background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;padding:18px 20px;margin-bottom:24px;'>"
            + "<p style='margin:0 0 4px;color:#64748b;font-size:12px;text-transform:uppercase;letter-spacing:0.5px;font-weight:600;'>Resource</p>"
            + "<p style='margin:0;color:#1e293b;font-size:15px;font-weight:600;'>" + inv.getResourceName() + " <span style='color:#64748b;font-size:13px;font-weight:400;'>(" + inv.getResourceType() + ")</span></p>"
            + "</div>"
            + "<h3 style='margin:0 0 8px;color:#1e293b;font-size:14px;font-weight:700;'>Summary</h3>"
            + "<p style='margin:0 0 20px;color:#374151;font-size:14px;line-height:1.6;'>" + summary + "</p>"
            + "<h3 style='margin:0 0 8px;color:#1e293b;font-size:14px;font-weight:700;'>Root Cause</h3>"
            + "<p style='margin:0 0 20px;color:#374151;font-size:14px;line-height:1.6;'>" + rootCause + "</p>"
            + "<a href='#' style='display:inline-block;background:#000000;color:#fff;padding:11px 22px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600;'>View Full Report in XamOps</a>"
            + "</td></tr>"
            + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;text-align:center;'>"
            + "<span style='display:inline-block;background:#000000;padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
            + "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>"
            + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; 2026 XamOps. All rights reserved.</p>"
            + "</td></tr>"
            + "</table></td></tr></table></body></html>";
    }
}
