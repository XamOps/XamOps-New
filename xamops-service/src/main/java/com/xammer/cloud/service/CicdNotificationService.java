package com.xammer.cloud.service;

import com.xammer.cloud.domain.CicdNotificationConfig;
import com.xammer.cloud.domain.CicdRunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;

import java.util.Map;

@Service
public class CicdNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(CicdNotificationService.class);

    private final EmailService emailService;
    private final RestTemplate restTemplate;

    public CicdNotificationService(EmailService emailService, RestTemplate restTemplate) {
        this.emailService = emailService;
        this.restTemplate = restTemplate;
    }

    public void notifyFailure(CicdRunRecord record, CicdNotificationConfig config, String userEmail) {
        String subject = "[XamOps] Pipeline failed: " + record.getWorkflowName()
                + (record.getRepoName() != null ? " in " + record.getRepoName() : "");

        String htmlBody = buildEmailBody(record);

        if (config.isEmailEnabled() && userEmail != null) {
            try {
                emailService.sendHtmlEmail(userEmail, subject, htmlBody);
                logger.info("Failure notification email sent for run {}", record.getRunId());
            } catch (Exception e) {
                logger.error("Failed to send failure email: {}", e.getMessage());
                throw e; // Caller decides whether to set notificationSent
            }
        }

        if (config.getSlackWebhookUrl() != null && !config.getSlackWebhookUrl().isBlank()) {
            sendSlackNotification(config.getSlackWebhookUrl(), record);
        }
    }

    private void sendSlackNotification(String webhookUrl, CicdRunRecord record) {
        try {
            String safeSlackUrl = record.getHtmlUrl() != null ? record.getHtmlUrl().replace(">", "") : null;
            String text = ":x: *Pipeline failed*: `" + record.getWorkflowName() + "`"
                    + (record.getRepoName() != null ? " in `" + record.getRepoName() + "`" : "")
                    + "\n*Category:* " + (record.getFailureCategory() != null ? record.getFailureCategory() : "UNKNOWN")
                    + (record.getFailedStage() != null ? "\n*Failed stage:* " + record.getFailedStage() : "")
                    + (safeSlackUrl != null ? "\n<" + safeSlackUrl + "|View run>" : "");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> req = new HttpEntity<>(Map.of("text", text), headers);
            restTemplate.postForEntity(webhookUrl, req, String.class);
            logger.info("Slack notification sent for run {}", record.getRunId());
        } catch (Exception e) {
            logger.warn("Slack notification failed for run {}: {}", record.getRunId(), e.getMessage());
        }
    }

    private String buildEmailBody(CicdRunRecord record) {
        String safeUrl = safeUrl(record.getHtmlUrl());
        return "<html><body style='font-family:sans-serif;'>"
                + "<h2 style='color:#e53e3e;'>Pipeline Failed</h2>"
                + "<table style='border-collapse:collapse;'>"
                + row("Workflow", HtmlUtils.htmlEscape(record.getWorkflowName()))
                + (record.getRepoName() != null ? row("Repository",
                    HtmlUtils.htmlEscape(record.getRepoOwner()) + "/" + HtmlUtils.htmlEscape(record.getRepoName())) : "")
                + row("Category", HtmlUtils.htmlEscape(record.getFailureCategory() != null ? record.getFailureCategory() : "UNKNOWN"))
                + (record.getFailedStage() != null ? row("Failed Stage", HtmlUtils.htmlEscape(record.getFailedStage())) : "")
                + "</table>"
                + (safeUrl != null
                    ? "<br><a href=\"" + safeUrl + "\" style='background:#3182ce;color:white;padding:8px 16px;border-radius:4px;text-decoration:none;'>View Run</a>"
                    : "")
                + "</body></html>";
    }

    private String safeUrl(String url) {
        if (url == null) return null;
        return (url.startsWith("http://") || url.startsWith("https://")) ? HtmlUtils.htmlEscape(url) : null;
    }

    private String row(String label, String value) {
        return "<tr><td style='padding:4px 12px 4px 0;font-weight:bold;'>" + label + "</td>"
                + "<td style='padding:4px 0;'>" + value + "</td></tr>";
    }
}
