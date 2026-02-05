package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for building XamOps-themed HTML email templates for alerts.
 */
@Service
public class AlertEmailBuilder {

    // XamOps Brand Colors
    private static final String PRIMARY_GRADIENT = "linear-gradient(135deg, #667eea 0%, #764ba2 100%)";
    private static final String CRITICAL_COLOR = "#dc2626";
    private static final String CRITICAL_BG = "#fef2f2";
    private static final String WARNING_COLOR = "#d97706";
    private static final String WARNING_BG = "#fffbeb";
    private static final String OK_COLOR = "#059669";
    private static final String OK_BG = "#ecfdf5";

    // Alert Type Icons (HTML entities)
    private static final String QUOTA_ICON = "&#128200;";      // Chart icon
    private static final String ANOMALY_ICON = "&#128176;";    // Money icon
    private static final String SECURITY_ICON = "&#128737;";   // Shield icon
    private static final String ALERT_ICON = "&#9888;";        // Warning icon

    /**
     * Build HTML email for a single alert notification.
     */
    public String buildSingleAlertEmail(AlertDto alert, String accountName, String accountId) {
        StringBuilder sb = new StringBuilder();
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        sb.append(getHtmlHeader("XamOps Alert - " + alert.getName()));
        sb.append(getBodyStart());
        sb.append(getMainContainerStart());
        sb.append(getEmailCardStart());

        // Header with alert severity color
        sb.append(getAlertHeader(alert));

        // Alert Details Card
        sb.append(getAlertDetailsSection(alert, accountName, accountId));

        // Action Section
        sb.append(getActionSection(alert));

        // Footer
        sb.append(getFooter(generatedDate));

        sb.append(getEmailCardEnd());
        sb.append(getMainContainerEnd());
        sb.append(getBodyEnd());

        return sb.toString();
    }

    /**
     * Build HTML email for multiple alerts (digest format).
     */
    public String buildAlertDigestEmail(List<AlertDto> alerts, String accountName, String accountId) {
        StringBuilder sb = new StringBuilder();
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        // Group alerts by type
        Map<String, List<AlertDto>> alertsByType = alerts.stream()
                .collect(Collectors.groupingBy(AlertDto::getType));

        long criticalCount = alerts.stream().filter(a -> "CRITICAL".equals(a.getStatus()) || "HIGH".equals(a.getStatus())).count();
        long warningCount = alerts.stream().filter(a -> "WARNING".equals(a.getStatus())).count();

        sb.append(getHtmlHeader("XamOps Alert Digest - " + accountName));
        sb.append(getBodyStart());
        sb.append(getMainContainerStart());
        sb.append(getEmailCardStart());

        // Digest Header
        sb.append(getDigestHeader(accountName, alerts.size(), criticalCount, warningCount));

        // Summary Cards
        sb.append(getSummaryCards(alerts));

        // Content Section - Alerts by Type
        sb.append("<tr><td style='padding: 30px 40px;'>");

        for (Map.Entry<String, List<AlertDto>> entry : alertsByType.entrySet()) {
            sb.append(getAlertTypeSection(entry.getKey(), entry.getValue()));
        }

        sb.append("</td></tr>");

        // Footer
        sb.append(getFooter(generatedDate));

        sb.append(getEmailCardEnd());
        sb.append(getMainContainerEnd());
        sb.append(getBodyEnd());

        return sb.toString();
    }

    // ============ Private Helper Methods ============

    private String getHtmlHeader(String title) {
        return "<!DOCTYPE html>" +
                "<html lang='en'>" +
                "<head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<title>" + escapeHtml(title) + "</title>" +
                "</head>";
    }

    private String getBodyStart() {
        return "<body style='margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif; background-color: #f4f7fa; line-height: 1.6;'>";
    }

    private String getBodyEnd() {
        return "</body></html>";
    }

    private String getMainContainerStart() {
        return "<table role='presentation' style='width: 100%; border-collapse: collapse;'>" +
                "<tr><td style='padding: 40px 20px;'>";
    }

    private String getMainContainerEnd() {
        return "</td></tr></table>";
    }

    private String getEmailCardStart() {
        return "<table role='presentation' style='max-width: 700px; margin: 0 auto; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);'>";
    }

    private String getEmailCardEnd() {
        return "</table>";
    }

    private String getAlertHeader(AlertDto alert) {
        String bgColor = getStatusGradient(alert.getStatus());
        String icon = getAlertTypeIcon(alert.getType());

        return "<tr>" +
                "<td style='background: " + bgColor + "; padding: 40px 40px 30px 40px;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td>" +
                // Brand
                "<div style='margin-bottom: 20px;'>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.3); padding: 6px 12px; border-radius: 6px; color: #ffffff; font-size: 12px; font-weight: 600; margin-left: 10px;'>" + escapeHtml(alert.getStatus()) + "</span>" +
                "</div>" +
                // Alert Icon and Title
                "<div style='font-size: 48px; margin-bottom: 16px;'>" + icon + "</div>" +
                "<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 24px; font-weight: 700;'>" + escapeHtml(alert.getName()) + "</h1>" +
                "<p style='margin: 0; color: rgba(255,255,255,0.85); font-size: 15px;'>" + escapeHtml(alert.getService()) + " | " + escapeHtml(alert.getRegion()) + "</p>" +
                "</td></tr></table>" +
                "</td></tr>";
    }

    private String getDigestHeader(String accountName, int totalAlerts, long criticalCount, long warningCount) {
        return "<tr>" +
                "<td style='background: " + PRIMARY_GRADIENT + "; padding: 40px 40px 30px 40px;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td>" +
                "<div style='margin-bottom: 20px;'>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>" +
                "</div>" +
                "<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 28px; font-weight: 700;'>Alert Digest</h1>" +
                "<p style='margin: 0; color: rgba(255,255,255,0.85); font-size: 16px;'>" + escapeHtml(accountName) + "</p>" +
                "<p style='margin: 8px 0 0 0; color: rgba(255,255,255,0.7); font-size: 14px;'>" +
                totalAlerts + " alerts | " + criticalCount + " critical | " + warningCount + " warnings</p>" +
                "</td></tr></table></td></tr>";
    }

    private String getSummaryCards(List<AlertDto> alerts) {
        long quotaCount = alerts.stream().filter(a -> "QUOTA".equals(a.getType())).count();
        long anomalyCount = alerts.stream().filter(a -> "ANOMALY".equals(a.getType())).count();
        long securityCount = alerts.stream().filter(a -> "SECURITY".equals(a.getType())).count();

        return "<tr><td style='padding: 0 40px;'>" +
                "<table role='presentation' style='width: 100%; margin-top: -30px;'><tr>" +
                // Quota Card
                getSummaryCard("Quota Alerts", String.valueOf(quotaCount), QUOTA_ICON) +
                // Anomaly Card
                getSummaryCard("Cost Anomalies", String.valueOf(anomalyCount), ANOMALY_ICON) +
                // Security Card
                getSummaryCard("Security Findings", String.valueOf(securityCount), SECURITY_ICON) +
                "</tr></table></td></tr>";
    }

    private String getSummaryCard(String label, String value, String icon) {
        return "<td style='width: 33%; padding: 0 8px;'>" +
                "<div style='background: #ffffff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); border: 1px solid #e8eef3;'>" +
                "<p style='margin: 0; font-size: 24px;'>" + icon + "</p>" +
                "<p style='margin: 8px 0 4px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>" + escapeHtml(label) + "</p>" +
                "<p style='margin: 0; color: #1f2937; font-size: 24px; font-weight: 700;'>" + value + "</p>" +
                "</div></td>";
    }

    private String getAlertDetailsSection(AlertDto alert, String accountName, String accountId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding: 30px 40px;'>");

        // Description
        sb.append("<div style='background-color: #f8fafc; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; border-left: 4px solid ")
                .append(getStatusColor(alert.getStatus())).append(";'>");
        sb.append("<p style='margin: 0; color: #1e293b; font-size: 15px;'>")
                .append(escapeHtml(alert.getDescription())).append("</p>");
        sb.append("</div>");

        // Details Table
        sb.append("<h2 style='margin: 0 0 16px 0; color: #1f2937; font-size: 18px; font-weight: 600;'>Alert Details</h2>");
        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse;'>");

        sb.append(getDetailRow("Account", escapeHtml(accountName) + " (" + escapeHtml(accountId) + ")"));
        sb.append(getDetailRow("Service", escapeHtml(alert.getService())));
        sb.append(getDetailRow("Region", escapeHtml(alert.getRegion())));
        sb.append(getDetailRow("Alert Type", formatAlertType(alert.getType())));
        sb.append(getDetailRow("Severity", "<span style='color: " + getStatusColor(alert.getStatus()) + "; font-weight: 600;'>" + escapeHtml(alert.getStatus()) + "</span>"));

        if ("QUOTA".equals(alert.getType()) && alert.getLimit() > 0) {
            double utilization = (alert.getUsage() / alert.getLimit()) * 100;
            sb.append(getDetailRow("Current Usage", String.format("%.0f / %.0f (%.1f%%)",
                    alert.getUsage(), alert.getLimit(), utilization)));
        }

        sb.append("</table>");
        sb.append("</td></tr>");

        return sb.toString();
    }

    private String getDetailRow(String label, String value) {
        return "<tr>" +
                "<td style='padding: 12px 0; border-bottom: 1px solid #e5e7eb; color: #6b7280; font-size: 14px; width: 40%;'>" + escapeHtml(label) + "</td>" +
                "<td style='padding: 12px 0; border-bottom: 1px solid #e5e7eb; color: #1f2937; font-size: 14px; font-weight: 500;'>" + value + "</td>" +
                "</tr>";
    }

    private String getAlertTypeSection(String type, List<AlertDto> alerts) {
        StringBuilder sb = new StringBuilder();
        String icon = getAlertTypeIcon(type);
        String title = formatAlertType(type);

        sb.append("<div style='margin-bottom: 24px;'>");
        sb.append("<h3 style='margin: 0 0 16px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>")
                .append(icon).append(" ").append(title).append(" (").append(alerts.size()).append(")</h3>");

        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.05);'>");

        // Table Header
        sb.append("<thead><tr style='background: ").append(PRIMARY_GRADIENT).append(";'>");
        sb.append("<th style='padding: 12px 16px; text-align: left; color: #ffffff; font-size: 12px; font-weight: 600; text-transform: uppercase;'>Alert</th>");
        sb.append("<th style='padding: 12px 16px; text-align: left; color: #ffffff; font-size: 12px; font-weight: 600; text-transform: uppercase;'>Service</th>");
        sb.append("<th style='padding: 12px 16px; text-align: left; color: #ffffff; font-size: 12px; font-weight: 600; text-transform: uppercase;'>Region</th>");
        sb.append("<th style='padding: 12px 16px; text-align: center; color: #ffffff; font-size: 12px; font-weight: 600; text-transform: uppercase;'>Severity</th>");
        sb.append("</tr></thead>");

        // Table Body
        sb.append("<tbody>");
        int rowIndex = 0;
        for (AlertDto alert : alerts) {
            String bgColor = rowIndex % 2 == 0 ? "#ffffff" : "#f8fafc";
            sb.append("<tr style='background-color: ").append(bgColor).append(";'>");
            sb.append("<td style='padding: 14px 16px; color: #374151; font-size: 14px; border-bottom: 1px solid #e5e7eb;'>")
                    .append(escapeHtml(alert.getName())).append("</td>");
            sb.append("<td style='padding: 14px 16px; color: #6b7280; font-size: 14px; border-bottom: 1px solid #e5e7eb;'>")
                    .append(escapeHtml(alert.getService())).append("</td>");
            sb.append("<td style='padding: 14px 16px; color: #6b7280; font-size: 14px; border-bottom: 1px solid #e5e7eb;'>")
                    .append(escapeHtml(alert.getRegion())).append("</td>");
            sb.append("<td style='padding: 14px 16px; text-align: center; border-bottom: 1px solid #e5e7eb;'>")
                    .append("<span style='display: inline-block; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600; background-color: ")
                    .append(getStatusBackgroundColor(alert.getStatus())).append("; color: ")
                    .append(getStatusColor(alert.getStatus())).append(";'>")
                    .append(escapeHtml(alert.getStatus())).append("</span></td>");
            sb.append("</tr>");
            rowIndex++;
        }
        sb.append("</tbody></table></div>");

        return sb.toString();
    }

    private String getActionSection(AlertDto alert) {
        return "<tr><td style='padding: 0 40px 30px 40px;'>" +
                "<div style='background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%); border-radius: 12px; padding: 24px; border: 1px solid #e2e8f0;'>" +
                "<h3 style='margin: 0 0 12px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>Recommended Action</h3>" +
                "<p style='margin: 0 0 16px 0; color: #475569; font-size: 14px;'>" + getRecommendation(alert) + "</p>" +
                "<a href='#' style='display: inline-block; background: " + PRIMARY_GRADIENT + "; color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;'>View in XamOps Dashboard</a>" +
                "</div></td></tr>";
    }

    private String getFooter(String generatedDate) {
        return "<tr><td style='background-color: #f8fafc; padding: 30px 40px; border-top: 1px solid #e5e7eb;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td style='text-align: center;'>" +
                "<p style='margin: 0 0 12px 0;'>" +
                "<span style='display: inline-block; background: " + PRIMARY_GRADIENT + "; padding: 8px 20px; border-radius: 6px; color: #ffffff; font-size: 14px; font-weight: 700; letter-spacing: 1px;'>XAMOPS</span>" +
                "</p>" +
                "<p style='margin: 0 0 8px 0; color: #6b7280; font-size: 13px;'>Cloud Cost Management &amp; Security Platform</p>" +
                "<div style='border-top: 1px solid #e5e7eb; margin: 16px 0;'></div>" +
                "<p style='margin: 0; color: #9ca3af; font-size: 12px;'>&copy; " + Year.now().getValue() + " XamOps. All rights reserved.</p>" +
                "<p style='margin: 8px 0 0 0; color: #9ca3af; font-size: 11px;'>Generated on " + escapeHtml(generatedDate) + "</p>" +
                "</td></tr></table></td></tr>";
    }

    // ============ Utility Methods ============

    private String getStatusColor(String status) {
        if (status == null) return OK_COLOR;
        switch (status.toUpperCase()) {
            case "CRITICAL":
            case "HIGH":
                return CRITICAL_COLOR;
            case "WARNING":
                return WARNING_COLOR;
            default:
                return OK_COLOR;
        }
    }

    private String getStatusBackgroundColor(String status) {
        if (status == null) return OK_BG;
        switch (status.toUpperCase()) {
            case "CRITICAL":
            case "HIGH":
                return CRITICAL_BG;
            case "WARNING":
                return WARNING_BG;
            default:
                return OK_BG;
        }
    }

    private String getStatusGradient(String status) {
        if (status == null) return PRIMARY_GRADIENT;
        switch (status.toUpperCase()) {
            case "CRITICAL":
            case "HIGH":
                return "linear-gradient(135deg, #dc2626 0%, #991b1b 100%)";
            case "WARNING":
                return "linear-gradient(135deg, #d97706 0%, #92400e 100%)";
            default:
                return PRIMARY_GRADIENT;
        }
    }

    private String getAlertTypeIcon(String type) {
        if (type == null) return ALERT_ICON;
        switch (type.toUpperCase()) {
            case "QUOTA":
                return QUOTA_ICON;
            case "ANOMALY":
                return ANOMALY_ICON;
            case "SECURITY":
                return SECURITY_ICON;
            default:
                return ALERT_ICON;
        }
    }

    private String formatAlertType(String type) {
        if (type == null) return "Alert";
        switch (type.toUpperCase()) {
            case "QUOTA":
                return "Resource Quota Alerts";
            case "ANOMALY":
                return "Cost Anomalies";
            case "SECURITY":
                return "Security Findings";
            default:
                return type;
        }
    }

    private String getRecommendation(AlertDto alert) {
        if (alert == null || alert.getType() == null) {
            return "Review this alert in your XamOps dashboard for detailed information and recommended actions.";
        }
        switch (alert.getType().toUpperCase()) {
            case "QUOTA":
                return "Your resource usage is approaching the quota limit. Consider requesting a quota increase through AWS Service Quotas console, or review and clean up unused resources.";
            case "ANOMALY":
                return "An unexpected cost increase has been detected. Review recent deployments and resource changes. Check for idle or over-provisioned resources.";
            case "SECURITY":
                return "A security finding requires attention. Review the affected resources and apply the recommended remediation steps to maintain your security posture.";
            default:
                return "Review this alert in your XamOps dashboard for detailed information and recommended actions.";
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
