package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import com.xammer.cloud.domain.SreNotification;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.SreNotificationRepository;
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

        return "<html><body style='font-family:sans-serif;color:#1e293b;'>" +
            "<h2 style='color:#3b82f6;'>SRE Investigation Complete</h2>" +
            "<p><strong>Resource:</strong> " + inv.getResourceName() + " (" + inv.getResourceType() + ")</p>" +
            "<h3>Summary</h3><p>" + summary + "</p>" +
            "<h3>Root Cause</h3><p>" + rootCause + "</p>" +
            "<p><a href='#' style='color:#3b82f6;'>View full report in XamOps</a></p>" +
            "</body></html>";
    }
}
