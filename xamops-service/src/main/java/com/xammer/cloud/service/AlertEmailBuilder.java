package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AlertEmailBuilder {

    // ── Brand ──────────────────────────────────────────────────────────────────
    private static final String BRAND_GRADIENT   = "#000000";
    private static final String BRAND_ACCENT     = "#000000";

    // ── Severity colours ───────────────────────────────────────────────────────
    private static final String CRITICAL_COLOR   = "#dc2626";
    private static final String CRITICAL_BG      = "#fef2f2";
    private static final String CRITICAL_BORDER  = "#fca5a5";
    private static final String WARNING_COLOR    = "#d97706";
    private static final String WARNING_BG       = "#fffbeb";
    private static final String WARNING_BORDER   = "#fcd34d";
    private static final String OK_COLOR         = "#059669";
    private static final String OK_BG            = "#ecfdf5";
    private static final String OK_BORDER        = "#6ee7b7";


    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    public String buildAlertDigestEmail(List<AlertDto> alerts, String accountName, String accountId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        Map<String, List<AlertDto>> byType = alerts.stream()
                .collect(Collectors.groupingBy(AlertDto::getType));

        long critical = alerts.stream()
                .filter(a -> "CRITICAL".equalsIgnoreCase(a.getStatus()) || "HIGH".equalsIgnoreCase(a.getStatus()))
                .count();
        long warning  = alerts.stream().filter(a -> "WARNING".equalsIgnoreCase(a.getStatus())).count();

        StringBuilder sb = new StringBuilder();
        sb.append(htmlHead("XamOps Alert Digest — " + accountName));
        sb.append("<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>");
        sb.append("<tr><td style='padding:32px 16px;'>");

        // ── Outer card ────────────────────────────────────────────────────────
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>");

        sb.append(digestHeader(accountName, accountId, date, alerts.size(), critical, warning));
        sb.append(summaryStrip(alerts));
        sb.append(alertSections(byType));
        sb.append(footer(date));

        sb.append("</table>"); // outer card
        sb.append("</td></tr></table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    public String buildSingleAlertEmail(AlertDto alert, String accountName, String accountId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        StringBuilder sb = new StringBuilder();
        sb.append(htmlHead("XamOps Alert — " + alert.getName()));
        sb.append("<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>");
        sb.append("<tr><td style='padding:32px 16px;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>");
        sb.append(singleAlertHeader(alert));
        sb.append(singleAlertBody(alert, accountName, accountId));
        sb.append(actionSection(alert));
        sb.append(footer(date));
        sb.append("</table>");
        sb.append("</td></tr></table>");
        sb.append("</body></html>");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Digest sections
    // ─────────────────────────────────────────────────────────────────────────

    private String digestHeader(String accountName, String accountId, String date, int total, long critical, long warning) {
        return "<tr><td style='background:" + BRAND_GRADIENT + ";padding:40px 40px 32px;'>" +
               "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr><td>" +
               // Logo pill
               "<div style='margin-bottom:20px;'>" +
               "<span style='display:inline-block;background:#ffffff;color:#000000;padding:5px 14px;border-radius:6px;font-size:11px;font-weight:900;letter-spacing:2px;text-transform:uppercase;'>XamOps</span>" +
               "</div>" +
               // Title
               "<h1 style='margin:0 0 6px;color:#fff;font-size:26px;font-weight:700;letter-spacing:-0.5px;'>Alert Digest</h1>" +
               "<p style='margin:0 0 4px;color:rgba(255,255,255,0.8);font-size:15px;'>" + esc(accountName) + "</p>" +
               "<p style='margin:0 0 20px;color:rgba(255,255,255,0.55);font-size:12px;font-family:monospace;'>" + esc(accountId) + "</p>" +
               // Inline stat row
               "<table role='presentation' cellpadding='0' cellspacing='0'><tr>" +
               statPill(String.valueOf(total),    "Total",    "rgba(255,255,255,0.25)", "#fff") +
               statPill(String.valueOf(critical), "Critical", "#fef2f2",               CRITICAL_COLOR) +
               statPill(String.valueOf(warning),  "Warnings", "#fffbeb",               WARNING_COLOR) +
               "</tr></table>" +
               "</td><td style='text-align:right;vertical-align:top;'>" +
               "<p style='margin:0;color:rgba(255,255,255,0.6);font-size:12px;'>" + esc(date) + "</p>" +
               "</td></tr></table>" +
               "</td></tr>";
    }

    private String statPill(String value, String label, String bg, String color) {
        return "<td style='padding-right:8px;'>" +
               "<div style='background:" + bg + ";border-radius:8px;padding:10px 16px;text-align:center;'>" +
               "<div style='color:" + color + ";font-size:20px;font-weight:700;line-height:1;'>" + esc(value) + "</div>" +
               "<div style='color:" + color + ";font-size:11px;font-weight:500;margin-top:2px;opacity:0.85;'>" + esc(label) + "</div>" +
               "</div></td>";
    }

    private String summaryStrip(List<AlertDto> alerts) {
        long quota    = alerts.stream().filter(a -> "QUOTA".equals(a.getType())).count();
        long anomaly  = alerts.stream().filter(a -> "ANOMALY".equals(a.getType())).count();
        long security = alerts.stream().filter(a -> "SECURITY".equals(a.getType())).count();

        return "<tr><td style='padding:0 40px;'>" +
               "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='border-radius:12px;overflow:hidden;border:1px solid #e2e8f0;margin-top:-16px;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,0.06);'>" +
               "<tr>" +
               summaryCell(EmailIcons.barChart(22),   "Quota Alerts",      quota) +
               summaryDivider() +
               summaryCell(EmailIcons.dollarSign(22), "Cost Anomalies",    anomaly) +
               summaryDivider() +
               summaryCell(EmailIcons.shield(22),     "Security Findings", security) +
               "</tr></table></td></tr>";
    }

    private String summaryCell(String icon, String label, long count) {
        return "<td style='padding:20px;text-align:center;width:33%;'>" +
               "<div style='margin-bottom:6px;color:#374151;'>" + icon + "</div>" +
               "<div style='color:#1e293b;font-size:22px;font-weight:700;'>" + count + "</div>" +
               "<div style='color:#64748b;font-size:12px;font-weight:500;margin-top:2px;text-transform:uppercase;letter-spacing:0.5px;'>" + esc(label) + "</div>" +
               "</td>";
    }

    private String summaryDivider() {
        return "<td style='width:1px;background:#e2e8f0;padding:0;'></td>";
    }

    private String alertSections(Map<String, List<AlertDto>> byType) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding:32px 40px 24px;'>");
        for (Map.Entry<String, List<AlertDto>> entry : byType.entrySet()) {
            sb.append(alertTypeSection(entry.getKey(), entry.getValue()));
        }
        sb.append("</td></tr>");
        return sb.toString();
    }

    private String alertTypeSection(String type, List<AlertDto> alerts) {
        String icon  = typeIcon(type);
        String title = typeLabel(type);

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='margin-bottom:28px;'>");
        sb.append("<div style='display:flex;align-items:center;margin-bottom:12px;'>");
        sb.append("<span style='margin-right:8px;vertical-align:middle;color:#374151;'>" + icon + "</span>");
        sb.append("<h3 style='margin:0;color:#1e293b;font-size:15px;font-weight:700;'>" + esc(title) + " <span style='color:#64748b;font-weight:500;'>(" + alerts.size() + ")</span></h3>");
        sb.append("</div>");

        // Table
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' style='border-radius:10px;overflow:hidden;border:1px solid #e2e8f0;font-size:13px;'>");

        // Header row
        sb.append("<thead><tr style='background:" + BRAND_GRADIENT + ";'>");
        sb.append(th("Alert"));
        sb.append(th("Service"));
        sb.append(th("Region"));
        if ("QUOTA".equals(type)) sb.append(th("Usage"));
        sb.append(thCenter("Severity"));
        sb.append("</tr></thead><tbody>");

        int i = 0;
        for (AlertDto a : alerts) {
            String rowBg = (i % 2 == 0) ? "#ffffff" : "#f8fafc";
            sb.append("<tr style='background:" + rowBg + ";'>");
            sb.append(td("<span style='font-weight:600;color:#1e293b;'>" + esc(a.getName()) + "</span>" +
                         "<br><span style='color:#64748b;font-size:12px;'>" + esc(a.getDescription()) + "</span>"));
            sb.append(td(esc(a.getService())));
            sb.append(td(esc(a.getRegion())));
            if ("QUOTA".equals(type) && a.getLimit() > 0) {
                double pct = (a.getUsage() / a.getLimit()) * 100;
                sb.append(td(usageBar(a.getUsage(), a.getLimit(), pct)));
            }
            sb.append("<td style='padding:12px 14px;text-align:center;border-bottom:1px solid #e2e8f0;'>" +
                      severityBadge(a.getStatus()) + "</td>");
            sb.append("</tr>");
            i++;
        }

        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private String usageBar(double usage, double limit, double pct) {
        String barColor = pct >= 90 ? CRITICAL_COLOR : pct >= 75 ? WARNING_COLOR : OK_COLOR;
        int barWidth    = Math.min((int) pct, 100);
        return "<div style='font-size:12px;color:#374151;margin-bottom:4px;'>" +
               String.format("%.0f / %.0f (%.1f%%)", usage, limit, pct) +
               "</div>" +
               "<table role='presentation' cellpadding='0' cellspacing='0' width='100%'><tr>" +
               "<td style='background:#e2e8f0;border-radius:4px;height:6px;'>" +
               "<div style='width:" + barWidth + "%;background:" + barColor + ";height:6px;border-radius:4px;'></div>" +
               "</td></tr></table>";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single-alert sections
    // ─────────────────────────────────────────────────────────────────────────

    private String singleAlertHeader(AlertDto alert) {
        String icon = typeIcon(alert.getType());
        return "<tr><td style='background:#000000;padding:40px 40px 32px;'>" +
               "<div style='margin-bottom:16px;'>" +
               "<span style='display:inline-block;background:#ffffff;color:#000000;padding:5px 14px;border-radius:6px;font-size:11px;font-weight:900;letter-spacing:2px;text-transform:uppercase;'>XamOps</span>" +
               "<span style='display:inline-block;background:rgba(255,255,255,0.3);padding:5px 12px;border-radius:6px;color:#fff;font-size:11px;font-weight:600;margin-left:8px;'>" + esc(alert.getStatus()) + "</span>" +
               "</div>" +
               "<div style='font-size:40px;margin-bottom:14px;'>" + icon + "</div>" +
               "<h1 style='margin:0 0 6px;color:#fff;font-size:22px;font-weight:700;'>" + esc(alert.getName()) + "</h1>" +
               "<p style='margin:0;color:rgba(255,255,255,0.8);font-size:14px;'>" + esc(alert.getService()) + " · " + esc(alert.getRegion()) + "</p>" +
               "</td></tr>";
    }

    private String singleAlertBody(AlertDto alert, String accountName, String accountId) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding:28px 40px;'>");

        // Description callout
        sb.append("<div style='background:" + statusBg(alert.getStatus()) + ";border-left:4px solid " + statusColor(alert.getStatus()) + ";border-radius:0 8px 8px 0;padding:14px 18px;margin-bottom:24px;'>");
        sb.append("<p style='margin:0;color:#1e293b;font-size:14px;line-height:1.6;'>" + esc(alert.getDescription()) + "</p>");
        sb.append("</div>");

        // Detail grid
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>");
        sb.append(detailRow("Account",    esc(accountName) + " <span style='color:#94a3b8;'>(" + esc(accountId) + ")</span>"));
        sb.append(detailRow("Service",    esc(alert.getService())));
        sb.append(detailRow("Region",     esc(alert.getRegion())));
        sb.append(detailRow("Alert Type", typeLabel(alert.getType())));
        sb.append(detailRow("Severity",   "<span style='color:" + statusColor(alert.getStatus()) + ";font-weight:600;'>" + esc(alert.getStatus()) + "</span>"));
        if ("QUOTA".equals(alert.getType()) && alert.getLimit() > 0) {
            double pct = (alert.getUsage() / alert.getLimit()) * 100;
            sb.append(detailRow("Usage", String.format("%.0f / %.0f (%.1f%%)", alert.getUsage(), alert.getLimit(), pct)));
        }
        sb.append("</table>");
        sb.append("</td></tr>");
        return sb.toString();
    }

    private String actionSection(AlertDto alert) {
        return "<tr><td style='padding:0 40px 28px;'>" +
               "<div style='background:#f8fafc;border:1px solid #e2e8f0;border-radius:12px;padding:22px 24px;'>" +
               "<h3 style='margin:0 0 10px;color:#1e293b;font-size:14px;font-weight:700;'>Recommended Action</h3>" +
               "<p style='margin:0 0 16px;color:#475569;font-size:13px;line-height:1.6;'>" + recommendation(alert) + "</p>" +
               "<a href='#' style='display:inline-block;background:" + BRAND_GRADIENT + ";color:#fff;padding:11px 22px;border-radius:8px;text-decoration:none;font-size:13px;font-weight:600;'>View in XamOps Dashboard</a>" +
               "</div></td></tr>";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Footer
    // ─────────────────────────────────────────────────────────────────────────

    private String footer(String date) {
        return "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:28px 40px;text-align:center;'>" +
               "<div style='margin-bottom:12px;'>" +
               "<span style='display:inline-block;background:" + BRAND_GRADIENT + ";padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>" +
               "</div>" +
               "<p style='margin:0 0 6px;color:#64748b;font-size:12px;'>Cloud Cost Management &amp; Security Platform</p>" +
               "<p style='margin:0 0 14px;color:#94a3b8;font-size:11px;'>Next digest in ~24 hours &nbsp;·&nbsp; " +
               "<a href='#' style='color:" + BRAND_ACCENT + ";text-decoration:none;'>Manage notification settings</a></p>" +
               "<div style='border-top:1px solid #e2e8f0;padding-top:14px;'>" +
               "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; " + Year.now().getValue() + " XamOps. Generated on " + esc(date) + "</p>" +
               "</div>" +
               "</td></tr>";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String htmlHead(String title) {
        return "<!DOCTYPE html><html lang='en'><head>" +
               "<meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width,initial-scale=1.0'>" +
               "<title>" + esc(title) + "</title>" +
               "</head>";
    }

    private String th(String text) {
        return "<th style='padding:10px 14px;text-align:left;color:#fff;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;white-space:nowrap;'>" + esc(text) + "</th>";
    }

    private String thCenter(String text) {
        return "<th style='padding:10px 14px;text-align:center;color:#fff;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;'>" + esc(text) + "</th>";
    }

    private String td(String html) {
        return "<td style='padding:12px 14px;color:#374151;font-size:13px;border-bottom:1px solid #e2e8f0;vertical-align:top;'>" + html + "</td>";
    }

    private String detailRow(String label, String valueHtml) {
        return "<tr>" +
               "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#64748b;font-size:13px;width:38%;'>" + esc(label) + "</td>" +
               "<td style='padding:10px 0;border-bottom:1px solid #f1f5f9;color:#1e293b;font-size:13px;font-weight:500;'>" + valueHtml + "</td>" +
               "</tr>";
    }

    private String severityBadge(String status) {
        String color  = statusColor(status);
        String bg     = statusBg(status);
        String border = statusBorder(status);
        return "<span style='display:inline-block;padding:3px 10px;border-radius:999px;font-size:11px;font-weight:700;background:" + bg + ";color:" + color + ";border:1px solid " + border + ";'>" + esc(status) + "</span>";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status / type helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String statusColor(String s) {
        if (s == null) return OK_COLOR;
        return switch (s.toUpperCase()) {
            case "CRITICAL", "HIGH" -> CRITICAL_COLOR;
            case "WARNING"          -> WARNING_COLOR;
            default                 -> OK_COLOR;
        };
    }

    private String statusBg(String s) {
        if (s == null) return OK_BG;
        return switch (s.toUpperCase()) {
            case "CRITICAL", "HIGH" -> CRITICAL_BG;
            case "WARNING"          -> WARNING_BG;
            default                 -> OK_BG;
        };
    }

    private String statusBorder(String s) {
        if (s == null) return OK_BORDER;
        return switch (s.toUpperCase()) {
            case "CRITICAL", "HIGH" -> CRITICAL_BORDER;
            case "WARNING"          -> WARNING_BORDER;
            default                 -> OK_BORDER;
        };
    }

    private String typeIcon(String type) {
        if (type == null) return EmailIcons.warning(18);
        return switch (type.toUpperCase()) {
            case "QUOTA"    -> EmailIcons.barChart(18);
            case "ANOMALY"  -> EmailIcons.dollarSign(18);
            case "SECURITY" -> EmailIcons.shield(18);
            default         -> EmailIcons.warning(18);
        };
    }

    private String typeLabel(String type) {
        if (type == null) return "Alert";
        return switch (type.toUpperCase()) {
            case "QUOTA"    -> "Resource Quota Alerts";
            case "ANOMALY"  -> "Cost Anomalies";
            case "SECURITY" -> "Security Findings";
            default         -> type;
        };
    }

    private String recommendation(AlertDto alert) {
        if (alert == null || alert.getType() == null) {
            return "Review this alert in your XamOps dashboard for detailed information and recommended actions.";
        }
        return switch (alert.getType().toUpperCase()) {
            case "QUOTA"    -> "Your resource usage is approaching the quota limit. Consider requesting a quota increase through the AWS Service Quotas console, or review and clean up unused resources.";
            case "ANOMALY"  -> "An unexpected cost increase has been detected. Review recent deployments and resource changes, and check for idle or over-provisioned resources.";
            case "SECURITY" -> "A security finding requires attention. Review the affected resources and apply the recommended remediation steps to maintain your security posture.";
            default         -> "Review this alert in your XamOps dashboard for detailed information and recommended actions.";
        };
    }

    private String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
