package com.xammer.cloud.service;

import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class CloudsitterEmailBuilder {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a z");

    public String subject(String action, String instanceId, String policyName) {
        // Subject lines are plain text, so no HTML escaping. Strip CR/LF instead, which
        // would otherwise permit header injection into the outgoing message.
        return String.format("[XamOps CloudSitter] Instance %s %s by policy \"%s\"",
                stripNewlines(instanceId),
                "STARTED".equals(action) ? "started" : "stopped",
                stripNewlines(policyName));
    }

    /** Removes CR/LF so a value cannot inject additional email headers. */
    private static String stripNewlines(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", " ").trim();
    }

    public String buildHtml(String action, String instanceId, String instanceName,
                            String region, String policyName, String timezone,
                            ZonedDateTime actionTime) {

        boolean isStart = "STARTED".equals(action);
        String accentColor = isStart ? "#10b981" : "#f59e0b";
        String accentLight = isStart ? "#d1fae5" : "#fef3c7";
        String accentText  = isStart ? "#065f46" : "#92400e";
        String icon        = isStart ? EmailIcons.play(24) : EmailIcons.stop(24);
        String actionLabel = isStart ? "Started" : "Stopped";
        String reasonText  = isStart
                ? "The schedule indicates this instance should be <strong>running</strong> at this hour."
                : "The schedule indicates this instance should be <strong>stopped</strong> at this hour.";

        // Escape every caller-supplied value. Instance names come from EC2 tags and are
        // fully attacker-controlled by anyone who can tag a resource, so injecting them raw
        // into an HTML email body was a stored-XSS vector in any web-based mail client.
        String safeInstanceId = escapeHtml(instanceId);
        String safeInstanceName = escapeHtml(instanceName);
        String displayName = (instanceName != null && !instanceName.isBlank())
                ? safeInstanceName + " (" + safeInstanceId + ")"
                : safeInstanceId;

        String timeStr = actionTime != null ? actionTime.format(TIME_FMT) : "—";

        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">" +
               "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
               "<body style=\"margin:0;padding:0;background:#f3f4f6;font-family:'Segoe UI',Arial,sans-serif\">" +
               "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f3f4f6;padding:32px 0\">" +
               "<tr><td align=\"center\">" +
               "<table width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 16px rgba(0,0,0,.08)\">" +

               // Header
               "<tr><td style=\"background:#000000;padding:28px 36px\">" +
               "<table width=\"100%\"><tr>" +
               "<td><span style=\"font-size:22px;font-weight:700;color:#fff;letter-spacing:-.5px\">XamOps</span>" +
               "<span style=\"font-size:13px;color:rgba(255,255,255,.75);margin-left:8px\">CloudSitter</span></td>" +
               "<td align=\"right\"><span style=\"background:rgba(255,255,255,.2);color:#fff;font-size:12px;font-weight:600;" +
               "padding:4px 12px;border-radius:20px\">Instance State Change</span></td>" +
               "</tr></table></td></tr>" +

               // Action badge
               "<tr><td style=\"padding:28px 36px 0\">" +
               "<div style=\"background:" + accentLight + ";border-left:4px solid " + accentColor + ";border-radius:6px;" +
               "padding:14px 18px;display:flex;align-items:center\">" +
               "<span style=\"margin-right:12px;vertical-align:middle;\">" + icon + "</span>" +
               "<div>" +
               "<div style=\"font-size:18px;font-weight:700;color:" + accentText + "\">" +
               "Instance " + actionLabel + "</div>" +
               "<div style=\"font-size:13px;color:" + accentText + ";margin-top:2px\">" + reasonText + "</div>" +
               "</div></div></td></tr>" +

               // Details table
               "<tr><td style=\"padding:24px 36px\">" +
               "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" " +
               "style=\"border:1px solid #e5e7eb;border-radius:8px;overflow:hidden\">" +
               // displayName is pre-escaped above; the rest are escaped here.
               rowRaw("Instance", displayName) +
               row("Region", region) +
               // actionLabel is derived internally, so this markup is trusted.
               rowRaw("Action", "<span style=\"background:" + accentLight + ";color:" + accentText +
                   ";padding:2px 10px;border-radius:12px;font-size:12px;font-weight:600\">" + actionLabel + "</span>") +
               row("Policy", policyName) +
               row("Timezone", timezone) +
               row("Time", timeStr) +
               "</table></td></tr>" +

               // Footer
               "<tr><td style=\"background:#f9fafb;border-top:1px solid #e5e7eb;padding:20px 36px;text-align:center\">" +
               "<p style=\"margin:0;font-size:12px;color:#9ca3af\">" +
               "This notification was triggered automatically by the <strong>CloudSitter</strong> scheduling engine.<br>" +
               "To manage notifications, update the policy settings in XamOps." +
               "</p>" +
               "<p style=\"margin:10px 0 0;font-size:11px;color:#d1d5db\">" +
               "© XamOps &mdash; Cloud FinOps Platform" +
               "</p></td></tr>" +

               "</table></td></tr></table></body></html>";
    }

    /** Renders a row, HTML-escaping the value. Use for anything caller-supplied. */
    private String row(String label, String value) {
        return rowRaw(label, escapeHtml(value));
    }

    /**
     * Renders a row without escaping the value. Only for markup this class builds itself, or
     * values already escaped by the caller.
     */
    private String rowRaw(String label, String value) {
        return "<tr style=\"border-bottom:1px solid #f3f4f6\">" +
               "<td style=\"padding:12px 16px;font-size:13px;font-weight:600;color:#6b7280;" +
               "background:#f9fafb;width:38%;border-right:1px solid #e5e7eb\">" + escapeHtml(label) + "</td>" +
               "<td style=\"padding:12px 16px;font-size:13px;color:#111827\">" + value + "</td>" +
               "</tr>";
    }

    /**
     * Escapes the five XML/HTML metacharacters. Sufficient here because every interpolation
     * point is HTML element text or a double-quoted attribute value.
     */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
