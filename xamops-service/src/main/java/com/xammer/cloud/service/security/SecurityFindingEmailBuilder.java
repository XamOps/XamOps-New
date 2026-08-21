package com.xammer.cloud.service.security;

import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.service.EmailIcons;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HTML for the immediate security finding email.
 *
 * <p>
 * Hand-built markup rather than a template, matching {@code AlertEmailBuilder} and the other
 * {@code *EmailBuilder} classes — there is no template engine wired into this service and
 * introducing one for a single email would be a bigger change than the feature.
 *
 * <p>
 * The content is shaped around one question: what does the reader do in the next five minutes.
 * So the resource and the remediation lead, the severity is a badge rather than a sentence, and
 * the batch is capped at {@link #MAX_ROWS} with an explicit overflow line — an email listing two
 * hundred findings is not read, it is filed.
 */
@Service
public class SecurityFindingEmailBuilder {

    /**
     * Findings listed in full before the email switches to a count.
     *
     * <p>
     * An active incident can produce hundreds in minutes. Past roughly this many rows the
     * email stops being a call to action and starts being a log dump, and some mail clients
     * begin clipping the message — which would hide the footer and the link to the page that
     * shows all of them.
     */
    private static final int MAX_ROWS = 25;

    private static final String BRAND = "#000000";
    private static final String CRITICAL_COLOR = "#dc2626";
    private static final String CRITICAL_BG = "#fef2f2";
    private static final String CRITICAL_BORDER = "#fca5a5";
    private static final String HIGH_COLOR = "#ea580c";
    private static final String HIGH_BG = "#fff7ed";
    private static final String HIGH_BORDER = "#fdba74";
    private static final String MEDIUM_COLOR = "#d97706";
    private static final String MEDIUM_BG = "#fffbeb";
    private static final String MEDIUM_BORDER = "#fcd34d";
    private static final String LOW_COLOR = "#0284c7";
    private static final String LOW_BG = "#f0f9ff";
    private static final String LOW_BORDER = "#7dd3fc";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("MMM dd, yyyy 'at' HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    public String build(List<CloudSecurityFinding> findings, String accountName, String accountId) {
        long critical = findings.stream()
                .filter(f -> SecuritySeverity.CRITICAL.equals(f.getSeverity())).count();
        long high = findings.stream()
                .filter(f -> SecuritySeverity.HIGH.equals(f.getSeverity())).count();
        String worst = SecuritySeverity.worstOf(findings.stream()
                .map(CloudSecurityFinding::getSeverity)
                .collect(Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1.0'>")
                .append("<title>").append(esc(subjectFor(findings, accountName))).append("</title></head>");
        sb.append("<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,")
                .append("BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>")
                .append("<tr><td style='padding:32px 16px;'>");
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' ")
                .append("style='max-width:680px;margin:0 auto;background:#ffffff;border-radius:16px;")
                .append("overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>");

        sb.append(header(accountName, accountId, findings.size(), critical, high, worst));
        sb.append(findingRows(findings));
        sb.append(bySourceStrip(findings));
        sb.append(footer());

        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    /**
     * Subject line.
     *
     * <p>
     * Leads with the severity and the count because that is all that survives a notification
     * preview on a phone, and puts the account name before the id — a reader recognises
     * "production" faster than twelve digits.
     */
    public String subjectFor(List<CloudSecurityFinding> findings, String accountName) {
        String worst = SecuritySeverity.worstOf(findings.stream()
                .map(CloudSecurityFinding::getSeverity)
                .collect(Collectors.toList()));

        if (findings.size() == 1) {
            return String.format("[XamOps] %s: %s — %s",
                    SecuritySeverity.toTitleCase(worst), findings.get(0).getTitle(), accountName);
        }
        return String.format("[XamOps] %d new %s security finding%s — %s",
                findings.size(), SecuritySeverity.toTitleCase(worst),
                findings.size() == 1 ? "" : "s", accountName);
    }

    // ── Sections ────────────────────────────────────────────────────────────

    private String header(String accountName, String accountId, int total, long critical,
            long high, String worst) {
        return "<tr><td style='background:" + BRAND + ";padding:40px 40px 32px;'>"
                + "<div style='margin-bottom:20px;'>"
                + "<span style='display:inline-block;background:#ffffff;color:#000000;padding:5px 14px;"
                + "border-radius:6px;font-size:11px;font-weight:900;letter-spacing:2px;"
                + "text-transform:uppercase;'>XamOps</span>"
                + "<span style='display:inline-block;background:" + severityColor(worst) + ";"
                + "padding:5px 12px;border-radius:6px;color:#fff;font-size:11px;font-weight:700;"
                + "margin-left:8px;letter-spacing:0.5px;'>" + esc(worst) + "</span>"
                + "</div>"
                + "<div style='margin-bottom:14px;color:#ffffff;'>" + EmailIcons.shield(36) + "</div>"
                + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;"
                + "letter-spacing:-0.5px;'>Security findings detected</h1>"
                + "<p style='margin:0 0 4px;color:rgba(255,255,255,0.8);font-size:15px;'>"
                + esc(accountName) + "</p>"
                + "<p style='margin:0 0 20px;color:rgba(255,255,255,0.55);font-size:12px;"
                + "font-family:monospace;'>" + esc(accountId) + "</p>"
                + "<table role='presentation' cellpadding='0' cellspacing='0'><tr>"
                + statPill(String.valueOf(total), "New", "rgba(255,255,255,0.25)", "#fff")
                + statPill(String.valueOf(critical), "Critical", CRITICAL_BG, CRITICAL_COLOR)
                + statPill(String.valueOf(high), "High", HIGH_BG, HIGH_COLOR)
                + "</tr></table>"
                // Why this arrived now, in the header rather than the footer: the first
                // question a 3am email provokes is "is this live or a scheduled report".
                + "<p style='margin:18px 0 0;color:rgba(255,255,255,0.6);font-size:12px;'>"
                + "Detected from your AWS event stream &middot; " + esc(STAMP.format(java.time.Instant.now()))
                + "</p>"
                + "</td></tr>";
    }

    private String statPill(String value, String label, String bg, String color) {
        return "<td style='padding-right:8px;'>"
                + "<div style='background:" + bg + ";border-radius:8px;padding:10px 16px;text-align:center;'>"
                + "<div style='color:" + color + ";font-size:20px;font-weight:700;line-height:1;'>"
                + esc(value) + "</div>"
                + "<div style='color:" + color + ";font-size:11px;font-weight:500;margin-top:2px;"
                + "opacity:0.85;'>" + esc(label) + "</div></div></td>";
    }

    private String findingRows(List<CloudSecurityFinding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding:32px 40px 8px;'>");

        int shown = 0;
        for (CloudSecurityFinding f : findings) {
            if (shown >= MAX_ROWS) {
                break;
            }
            sb.append(findingCard(f));
            shown++;
        }

        if (findings.size() > shown) {
            sb.append("<div style='background:#f8fafc;border:1px dashed #cbd5e1;border-radius:10px;")
                    .append("padding:16px 20px;text-align:center;margin-bottom:16px;'>")
                    .append("<p style='margin:0;color:#475569;font-size:13px;'>")
                    .append("and <strong>").append(findings.size() - shown)
                    .append("</strong> more finding").append(findings.size() - shown == 1 ? "" : "s")
                    .append(" — open the Security Center to see all of them.</p></div>");
        }

        sb.append("</td></tr>");
        return sb.toString();
    }

    /**
     * One finding.
     *
     * <p>
     * Resource first, then remediation. The severity and title tell the reader whether to care;
     * the resource tells them where to look; the remediation is the only part that changes the
     * outcome, so it gets its own visually distinct block rather than being a sentence in a
     * paragraph they skim.
     */
    private String findingCard(CloudSecurityFinding f) {
        String color = severityColor(f.getSeverity());
        String bg = severityBg(f.getSeverity());
        String border = severityBorder(f.getSeverity());

        StringBuilder sb = new StringBuilder();
        sb.append("<div style='border:1px solid #e2e8f0;border-left:4px solid ").append(color)
                .append(";border-radius:0 10px 10px 0;padding:18px 20px;margin-bottom:14px;'>");

        sb.append("<div style='margin-bottom:8px;'>")
                .append("<span style='display:inline-block;padding:3px 10px;border-radius:999px;")
                .append("font-size:10px;font-weight:700;background:").append(bg).append(";color:")
                .append(color).append(";border:1px solid ").append(border).append(";'>")
                .append(esc(f.getSeverity())).append("</span>")
                .append("<span style='display:inline-block;margin-left:8px;color:#64748b;")
                .append("font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;'>")
                .append(esc(sourceLabel(f))).append("</span>")
                .append("</div>");

        sb.append("<p style='margin:0 0 8px;color:#1e293b;font-size:15px;font-weight:700;'>")
                .append(esc(f.getTitle())).append("</p>");

        if (notBlank(f.getDescription())) {
            sb.append("<p style='margin:0 0 12px;color:#475569;font-size:13px;line-height:1.6;'>")
                    .append(esc(trim(f.getDescription(), 400))).append("</p>");
        }

        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' ")
                .append("style='font-size:12px;margin-bottom:").append(notBlank(f.getRemediation()) ? "12px" : "0")
                .append(";'>");
        if (notBlank(f.getResourceArn())) {
            sb.append(kv("Resource", "<span style='font-family:monospace;font-size:11px;color:#1e293b;'>"
                    + esc(trim(f.getResourceArn(), 160)) + "</span>"));
        } else {
            // Said plainly rather than left blank: an empty resource field reads as "nothing
            // affected", which is the opposite of what an unattributed finding means.
            sb.append(kv("Resource", "<span style='color:#b45309;'>not identified from the event"
                    + " — check recent changes in this region</span>"));
        }
        if (notBlank(f.getRegion())) {
            sb.append(kv("Region", esc(f.getRegion())));
        }
        if (notBlank(f.getControlId())) {
            sb.append(kv("Control", esc(f.getControlId())));
        }
        sb.append("</table>");

        if (notBlank(f.getRemediation())) {
            sb.append("<div style='background:#f8fafc;border-radius:8px;padding:12px 14px;'>")
                    .append("<p style='margin:0 0 4px;color:#64748b;font-size:10px;font-weight:700;")
                    .append("text-transform:uppercase;letter-spacing:0.5px;'>What to do</p>")
                    .append("<p style='margin:0;color:#374151;font-size:12px;line-height:1.6;'>")
                    .append(esc(trim(f.getRemediation(), 500))).append("</p></div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private String kv(String label, String valueHtml) {
        return "<tr><td style='padding:3px 0;color:#64748b;width:30%;vertical-align:top;'>"
                + esc(label) + "</td>"
                + "<td style='padding:3px 0;color:#1e293b;'>" + valueHtml + "</td></tr>";
    }

    /**
     * Counts per source.
     *
     * <p>
     * Worth showing because it answers a question the findings themselves do not: a batch that
     * is entirely {@code XamOps} means the customer has GuardDuty and Security Hub switched
     * off, and is seeing only what we can derive ourselves.
     */
    private String bySourceStrip(List<CloudSecurityFinding> findings) {
        Map<String, Long> bySource = findings.stream()
                .collect(Collectors.groupingBy(this::sourceLabel, Collectors.counting()));
        if (bySource.size() <= 1) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding:8px 40px 24px;'>")
                .append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' ")
                .append("style='border:1px solid #e2e8f0;border-radius:10px;'><tr>");
        for (Map.Entry<String, Long> entry : bySource.entrySet()) {
            sb.append("<td style='padding:14px;text-align:center;'>")
                    .append("<div style='color:#1e293b;font-size:18px;font-weight:700;'>")
                    .append(entry.getValue()).append("</div>")
                    .append("<div style='color:#64748b;font-size:11px;font-weight:500;")
                    .append("text-transform:uppercase;letter-spacing:0.5px;'>")
                    .append(esc(entry.getKey())).append("</div></td>");
        }
        sb.append("</tr></table></td></tr>");
        return sb.toString();
    }

    private String footer() {
        return "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:28px 40px;"
                + "text-align:center;'>"
                + "<div style='margin-bottom:12px;'>"
                + "<span style='display:inline-block;background:" + BRAND + ";padding:7px 18px;"
                + "border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>"
                + "XAMOPS</span></div>"
                + "<p style='margin:0 0 6px;color:#64748b;font-size:12px;'>Cloud Cost Management "
                + "&amp; Security Platform</p>"
                // Says what this email is, because "why am I getting this" is otherwise
                // answered by marking it spam.
                + "<p style='margin:0 0 14px;color:#94a3b8;font-size:11px;'>"
                + "You receive this because immediate alerts are enabled for findings at or above "
                + "your severity threshold. Lower-severity findings appear in the periodic digest."
                + "</p>"
                + "<div style='border-top:1px solid #e2e8f0;padding-top:14px;'>"
                + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>&copy; " + Year.now().getValue()
                + " XamOps</p></div></td></tr>";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String sourceLabel(CloudSecurityFinding f) {
        switch (f.getSource() == null ? "" : f.getSource()) {
            case CloudSecurityFinding.SOURCE_SECURITY_HUB:
                return "Security Hub";
            case CloudSecurityFinding.SOURCE_GUARDDUTY:
                return "GuardDuty";
            case CloudSecurityFinding.SOURCE_CONFIG:
                return "AWS Config";
            case CloudSecurityFinding.SOURCE_XAMOPS_DETECTOR:
                return "XamOps";
            default:
                return "Security";
        }
    }

    private String severityColor(String s) {
        switch (SecuritySeverity.normalize(s)) {
            case SecuritySeverity.CRITICAL:
                return CRITICAL_COLOR;
            case SecuritySeverity.HIGH:
                return HIGH_COLOR;
            case SecuritySeverity.MEDIUM:
                return MEDIUM_COLOR;
            default:
                return LOW_COLOR;
        }
    }

    private String severityBg(String s) {
        switch (SecuritySeverity.normalize(s)) {
            case SecuritySeverity.CRITICAL:
                return CRITICAL_BG;
            case SecuritySeverity.HIGH:
                return HIGH_BG;
            case SecuritySeverity.MEDIUM:
                return MEDIUM_BG;
            default:
                return LOW_BG;
        }
    }

    private String severityBorder(String s) {
        switch (SecuritySeverity.normalize(s)) {
            case SecuritySeverity.CRITICAL:
                return CRITICAL_BORDER;
            case SecuritySeverity.HIGH:
                return HIGH_BORDER;
            case SecuritySeverity.MEDIUM:
                return MEDIUM_BORDER;
            default:
                return LOW_BORDER;
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * HTML-escapes.
     *
     * <p>
     * Applied to every interpolated value without exception. Titles, descriptions and resource
     * names here originate from AWS event payloads and from resource names the customer chose,
     * so they are untrusted input on a path that ends in rendered HTML — a bucket named
     * {@code <img onerror=...>} would otherwise execute in a colleague's mail client.
     */
    private String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
