package com.xammer.cloud.service.war;

import com.xammer.cloud.dto.war.WarFinding;
import com.xammer.cloud.dto.war.WarReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds the HTML for the WAR report emailed when an account is onboarded.
 *
 * <p>
 * Table-based layout with inline styles, matching the house style established by
 * {@code TrialEmailService} and the other builders in this package: black header band,
 * white card on a slate floor, black CTA, cream-free neutral palette. Flexbox and grid
 * are avoided because Outlook does not implement either.
 *
 * <p>
 * The one rule this builder exists to enforce: <b>an unassessable report never renders
 * as a score.</b> {@link WarReport#isAssessable()} false means the scan could not read
 * the account, and {@code warScore} is forced to 0 with {@code riskLevel} UNKNOWN.
 * Mailing "Security score: 0 — CRITICAL" to someone thirty seconds after they connected
 * their account would blame them for our own blind scan, on the single worst occasion to
 * do it. That case gets a different email entirely — see
 * {@link #buildBlocked(WarReport, String)}.
 */
@Component
public class WarEmailBuilder {

    /** Findings listed in the body before the reader is sent to the dashboard. */
    private static final int TOP_RISK_LIMIT = 5;

    /** Attack paths listed in the exposure section. */
    private static final int PATH_LIMIT = 4;

    @Value("${app.frontend.url:https://app.xamops.com}")
    private String frontendUrl;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * The report email for a scan that produced real verdicts.
     *
     * @param report      an assessable report
     * @param accountName display name of the newly connected account
     */
    public String build(WarReport report, String accountName) {
        if (!report.isAssessable()) {
            return buildBlocked(report, accountName);
        }

        String riskLabel = riskLabel(report.getRiskLevel());
        String riskColour = riskColour(report.getRiskLevel());

        StringBuilder content = new StringBuilder();

        content.append("<p style='margin:0 0 24px;color:#374151;font-size:15px;line-height:1.6;'>")
                .append("We finished the first Workload Attack-surface Review of <strong>")
                .append(safe(accountName))
                .append("</strong>. Here is what an attacker could use today, and what to close first.")
                .append("</p>");

        content.append(scoreBlock(report, riskLabel, riskColour));
        content.append(countsTable(report));
        content.append(topRisksBlock(report.getTopRisks()));
        content.append(attackPathsBlock(report));
        content.append(coverageNote(report));

        content.append(ctaButton("Open the full review", dashboardUrl(report.getProvider())));

        content.append("<p style='margin:20px 0 0;color:#64748b;font-size:13px;line-height:1.6;'>")
                .append("The dashboard carries all ")
                .append(report.getTotalControls())
                .append(" controls with the evidence behind each verdict — the specific users, ")
                .append("buckets, security groups and keys involved.")
                .append("</p>");

        return shell(
                riskLabel,
                "First review of " + safe(accountName) + " · " + safe(report.getGeneratedAt()),
                content.toString());
    }

    /**
     * The email for a scan that could not read the account.
     *
     * <p>
     * Leads with the cause and the fix, and states plainly that this is not a verdict on
     * their security. No score, no risk level, no ranked findings — there is nothing to
     * rank, and presenting the forced zero would be a lie told in a large font.
     */
    public String buildBlocked(WarReport report, String accountName) {
        StringBuilder content = new StringBuilder();

        content.append("<p style='margin:0 0 20px;color:#374151;font-size:15px;line-height:1.6;'>")
                .append("<strong>")
                .append(safe(accountName))
                .append("</strong> is connected, but the first Workload Attack-surface Review could not ")
                .append("read enough of it to report on. <strong>This is a scan problem, not a verdict ")
                .append("on your security posture.</strong>")
                .append("</p>");

        if (report.getBlockedReason() != null && !report.getBlockedReason().isBlank()) {
            content.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' ")
                    .append("style='margin:0 0 20px;background:#fef2f2;border:1px solid #fecaca;border-radius:10px;'>")
                    .append("<tr><td style='padding:16px 18px;'>")
                    .append("<div style='color:#991b1b;font-size:11px;font-weight:700;")
                    .append("text-transform:uppercase;letter-spacing:1px;margin-bottom:8px;'>")
                    .append(providerLabel(report.getProvider()))
                    .append(" reported</div>")
                    .append("<div style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;")
                    .append("font-size:12.5px;color:#7f1d1d;line-height:1.55;word-break:break-word;'>")
                    .append(safe(report.getBlockedReason()))
                    .append("</div></td></tr></table>");
        }

        content.append(metaTable(
                "Controls in the baseline", String.valueOf(report.getTotalControls()),
                "Verified", String.valueOf(report.getVerifiedControls()),
                "Could not be checked", String.valueOf(count(report, "UNKNOWN"))));

        if (report.getDeniedActions() != null && !report.getDeniedActions().isEmpty()) {
            content.append("<p style='margin:20px 0 8px;color:#374151;font-size:14px;font-weight:600;'>")
                    .append("Permissions to grant the XamOps role")
                    .append("</p>")
                    .append("<p style='margin:0 0 10px;color:#64748b;font-size:13px;line-height:1.6;'>")
                    .append("These calls were explicitly denied. Granting them lets the next scan ")
                    .append("produce a real score.")
                    .append("</p>")
                    .append(codeList(report.getDeniedActions()));
        }

        content.append(ctaButton("Fix the connection", dashboardUrl(report.getProvider())));

        return shell(
                "Unable to assess",
                "First review of " + safe(accountName) + " · " + safe(report.getGeneratedAt()),
                content.toString());
    }

    /** Subject line for the report email. */
    public String subject(WarReport report, String accountName) {
        if (!report.isAssessable()) {
            return "Action needed: XamOps could not scan " + accountName;
        }
        int critical = severity(report, "Critical");
        String lead = critical > 0
                ? critical + " critical exposure" + (critical == 1 ? "" : "s")
                : "Security score " + report.getWarScore() + "/100";
        return "Your first security review of " + accountName + " — " + lead;
    }

    // ── Content blocks ──────────────────────────────────────────────────────

    /**
     * Score, risk verdict and the one-line explanation of what the number means.
     *
     * <p>
     * No progress ring: an SVG arc would be stripped by most clients and a background
     * image would not load. The number carries a coloured left edge instead, which is
     * the same visual language as the dashboard's anchor card and survives Outlook.
     */
    private String scoreBlock(WarReport report, String riskLabel, String riskColour) {
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                + "style='margin:0 0 20px;background:#f8fafc;border:1px solid #e2e8f0;"
                + "border-left:4px solid " + riskColour + ";border-radius:10px;'>"
                + "<tr><td style='padding:20px 22px;'>"
                + "<div style='font-size:40px;font-weight:700;color:#0f172a;line-height:1;'>"
                + report.getWarScore()
                + "<span style='font-size:17px;font-weight:600;color:#94a3b8;'>/100</span></div>"
                + "<div style='margin-top:8px;font-size:14px;font-weight:700;color:" + riskColour + ";'>"
                + riskLabel + "</div>"
                + "<div style='margin-top:8px;color:#64748b;font-size:12.5px;line-height:1.55;'>"
                + "Weighted pass rate across the " + report.getVerifiedControls()
                + " controls we verified against live account state. Manual and "
                + "not-applicable controls are excluded."
                + "</div>"
                + "</td></tr></table>";
    }

    /** Status counts as a two-column table — Outlook-safe, no flex. */
    private String countsTable(WarReport report) {
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                + "style='margin:0 0 22px;border-collapse:collapse;'>"
                + countRow("Exposed", count(report, "FAIL"), "Usable today", "#dc2626")
                + countRow("Weakened", count(report, "WARN"), "Partly mitigated", "#d97706")
                + countRow("Unverified", count(report, "UNKNOWN"), "We could not check these", "#475569")
                + countRow("Protected", count(report, "PASS"), "Verified compliant", "#059669")
                + countRow("Manual review", count(report, "MANUAL"), "No API can prove these", "#0284c7")
                + "</table>";
    }

    private String countRow(String label, int value, String hint, String colour) {
        return "<tr>"
                + "<td style='padding:10px 0;border-bottom:1px solid #eef2f6;'>"
                + "<span style='display:inline-block;width:8px;height:8px;border-radius:50%;"
                + "background:" + colour + ";margin-right:9px;'></span>"
                + "<span style='font-size:13.5px;font-weight:600;color:#1f2937;'>" + label + "</span>"
                + "<span style='font-size:12px;color:#94a3b8;margin-left:8px;'>" + hint + "</span>"
                + "</td>"
                + "<td align='right' style='padding:10px 0;border-bottom:1px solid #eef2f6;"
                + "font-size:19px;font-weight:700;color:#0f172a;'>" + value + "</td>"
                + "</tr>";
    }

    /** The ranked fix list, capped so the email stays skimmable. */
    private String topRisksBlock(List<WarFinding> topRisks) {
        if (topRisks == null || topRisks.isEmpty()) {
            return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                    + "style='margin:0 0 22px;background:#ecfdf5;border:1px solid #a7f3d0;"
                    + "border-radius:10px;'><tr><td style='padding:16px 18px;"
                    + "color:#065f46;font-size:13.5px;line-height:1.6;'>"
                    + "<strong>No confirmed exposures.</strong> Every control we could verify passed. "
                    + "Manual-review controls still need a human sign-off."
                    + "</td></tr></table>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(sectionTitle("Fix these first"));
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' ")
                .append("style='margin:0 0 22px;border-collapse:collapse;'>");

        int rank = 1;
        for (WarFinding finding : topRisks.stream().limit(TOP_RISK_LIMIT).toList()) {
            String severityColour = severityColour(finding.getSeverity());
            sb.append("<tr><td style='padding:12px 0;border-bottom:1px solid #eef2f6;'>")
                    .append("<table role='presentation' cellpadding='0' cellspacing='0' width='100%'><tr>")
                    .append("<td width='26' valign='top' style='padding-top:2px;'>")
                    .append("<span style='display:inline-block;width:20px;height:20px;border-radius:5px;")
                    .append("background:#0f172a;color:#fff;font-size:11px;font-weight:700;")
                    .append("text-align:center;line-height:20px;'>").append(rank++).append("</span>")
                    .append("</td><td valign='top'>")
                    .append("<div style='font-size:13.5px;font-weight:600;color:#111827;line-height:1.45;'>")
                    .append(safe(finding.getTitle()))
                    .append("</div>");

            if (finding.getSummary() != null && !finding.getSummary().isBlank()) {
                sb.append("<div style='margin-top:4px;color:#64748b;font-size:12.5px;line-height:1.5;'>")
                        .append(safe(finding.getSummary()))
                        .append("</div>");
            }

            sb.append("<div style='margin-top:6px;font-size:11px;'>")
                    .append("<span style='color:").append(severityColour).append(";font-weight:700;")
                    .append("text-transform:uppercase;letter-spacing:0.5px;'>")
                    .append(safe(finding.getSeverity())).append("</span>")
                    .append("<span style='color:#cbd5e1;margin:0 7px;'>|</span>")
                    .append("<span style='font-family:ui-monospace,SFMono-Regular,Menlo,monospace;")
                    .append("color:#94a3b8;'>").append(safe(finding.getControlId())).append("</span>");

            if (finding.getAffectedCount() > 0) {
                sb.append("<span style='color:#cbd5e1;margin:0 7px;'>|</span>")
                        .append("<span style='color:#dc2626;font-weight:600;'>")
                        .append(finding.getAffectedCount()).append(" affected</span>");
            }

            sb.append("</div></td></tr></table></td></tr>");
        }

        sb.append("</table>");
        return sb.toString();
    }

    /** Attack paths with something open, worst first. */
    private String attackPathsBlock(WarReport report) {
        List<WarReport.AttackPathSummary> open = report.getAttackPaths() == null
                ? List.of()
                : report.getAttackPaths().stream()
                        .filter(p -> p.getFailed() + p.getWarned() > 0)
                        .sorted((a, b) -> Integer.compare(
                                b.getFailed() + b.getWarned(), a.getFailed() + a.getWarned()))
                        .limit(PATH_LIMIT)
                        .toList();

        if (open.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(sectionTitle("How a failed control gets monetised"));
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' ")
                .append("style='margin:0 0 22px;border-collapse:collapse;'>");

        for (WarReport.AttackPathSummary path : open) {
            sb.append("<tr>")
                    .append("<td style='padding:9px 0;border-bottom:1px solid #eef2f6;")
                    .append("font-size:13px;font-weight:600;color:#1f2937;'>")
                    .append(safe(path.getLabel()))
                    .append("</td>")
                    .append("<td align='right' style='padding:9px 0;border-bottom:1px solid #eef2f6;")
                    .append("font-size:12.5px;color:#64748b;white-space:nowrap;'>")
                    .append(path.getFailed() + path.getWarned())
                    .append(" of ").append(path.getTotal()).append(" open")
                    .append("</td></tr>");
        }

        sb.append("</table>");
        return sb.toString();
    }

    /**
     * Coverage, stated even on a healthy report.
     *
     * <p>
     * A score without its denominator invites the reader to assume the whole baseline was
     * checked. Partial coverage above the assessable threshold is still partial.
     */
    private String coverageNote(WarReport report) {
        int unverified = count(report, "UNKNOWN");
        if (unverified == 0) {
            return "";
        }
        int scoreable = report.getVerifiedControls() + unverified;
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                + "style='margin:0 0 22px;background:#f8fafc;border:1px solid #e2e8f0;"
                + "border-radius:10px;'><tr><td style='padding:14px 16px;color:#475569;"
                + "font-size:12.5px;line-height:1.6;'>"
                + "The score covers " + report.getVerifiedControls() + " of " + scoreable
                + " checkable controls. The other " + unverified
                + " could not be verified — they were blocked, not failed. "
                + "Granting the XamOps role full read access closes that gap."
                + "</td></tr></table>";
    }

    // ── Primitives ──────────────────────────────────────────────────────────

    private String dashboardUrl(String provider) {
        String base = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
        return base + switch (provider == null ? "AWS" : provider) {
            case "GCP" -> "/gcp-war";
            case "Azure" -> "/azure-war";
            default -> "/war";
        };
    }

    private static String providerLabel(String provider) {
        return switch (provider == null ? "AWS" : provider) {
            case "GCP" -> "Google Cloud";
            case "Azure" -> "Azure";
            default -> "AWS";
        };
    }

    private static int count(WarReport report, String status) {
        if (report.getStatusCounts() == null) {
            return 0;
        }
        return report.getStatusCounts().getOrDefault(status, 0);
    }

    private static int severity(WarReport report, String severity) {
        if (report.getSeverityCounts() == null) {
            return 0;
        }
        return report.getSeverityCounts().getOrDefault(severity, 0);
    }

    private static String riskLabel(String riskLevel) {
        return switch (riskLevel == null ? "" : riskLevel) {
            case "CRITICAL" -> "Critical risk";
            case "HIGH" -> "High risk";
            case "MEDIUM" -> "Medium risk";
            case "LOW" -> "Low risk";
            default -> "Unable to assess";
        };
    }

    private static String riskColour(String riskLevel) {
        return switch (riskLevel == null ? "" : riskLevel) {
            case "CRITICAL" -> "#dc2626";
            case "HIGH" -> "#ea580c";
            case "MEDIUM" -> "#d97706";
            case "LOW" -> "#059669";
            default -> "#64748b";
        };
    }

    private static String severityColour(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase()) {
            case "critical" -> "#dc2626";
            case "high" -> "#ea580c";
            case "medium" -> "#d97706";
            default -> "#64748b";
        };
    }

    private static String sectionTitle(String label) {
        return "<div style='margin:0 0 10px;color:#94a3b8;font-size:11px;font-weight:700;"
                + "text-transform:uppercase;letter-spacing:1.4px;'>" + label + "</div>";
    }

    private static String codeList(List<String> actions) {
        StringBuilder sb = new StringBuilder("<div style='margin:0 0 4px;'>");
        for (String action : actions) {
            sb.append("<code style='display:inline-block;font-family:ui-monospace,")
                    .append("SFMono-Regular,Menlo,monospace;font-size:11.5px;background:#f8fafc;")
                    .append("border:1px solid #e2e8f0;border-radius:4px;padding:3px 7px;")
                    .append("margin:0 4px 5px 0;color:#475569;'>")
                    .append(safe(action))
                    .append("</code>");
        }
        return sb.append("</div>").toString();
    }

    /**
     * A label/value metadata table.
     *
     * <p>
     * Takes all its rows at once rather than exposing an open-table helper. The earlier
     * shape emitted a fresh {@code <table>} per row and closed only the last one, which
     * Gmail tolerated and Outlook did not.
     *
     * @param labelsAndValues alternating label, value pairs
     */
    private static String metaTable(String... labelsAndValues) {
        StringBuilder sb = new StringBuilder(
                "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                        + "style='margin:0 0 4px;border-collapse:collapse;'>");
        for (int i = 0; i + 1 < labelsAndValues.length; i += 2) {
            sb.append("<tr><td style='padding:8px 0;border-bottom:1px solid #eef2f6;")
                    .append("font-size:13px;color:#64748b;'>")
                    .append(labelsAndValues[i])
                    .append("</td><td align='right' style='padding:8px 0;")
                    .append("border-bottom:1px solid #eef2f6;font-size:13px;font-weight:700;")
                    .append("color:#0f172a;'>")
                    .append(labelsAndValues[i + 1])
                    .append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    private static String ctaButton(String label, String href) {
        return "<div style='text-align:center;margin:24px 0 4px;'>"
                + "<a href='" + href + "' style='display:inline-block;background:#000;color:#fff;"
                + "text-decoration:none;padding:13px 30px;border-radius:8px;font-weight:700;"
                + "font-size:15px;'>" + label + "</a></div>";
    }

    /**
     * HTML-escapes untrusted text.
     *
     * <p>
     * Findings carry evidence strings built from live cloud resource names — bucket
     * names, IAM user names, security group descriptions — all of which are
     * customer-controlled and none of which are validated for HTML safety upstream.
     */
    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String shell(String heading, String subheading, String content) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1.0'></head>"
                + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:-apple-system,"
                + "BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0'>"
                + "<tr><td style='padding:32px 16px;'>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                + "style='max-width:680px;margin:0 auto;background:#fff;border-radius:16px;"
                + "overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);border:1px solid #e2e8f0;'>"
                + "<tr><td style='background:#000;padding:40px;'>"
                + "<div style='margin-bottom:16px;'><span style='display:inline-block;background:#fff;"
                + "color:#000;padding:6px 14px;border-radius:20px;font-size:12px;font-weight:700;"
                + "letter-spacing:1.5px;text-transform:uppercase;'>XamOps</span></div>"
                + "<h1 style='margin:0 0 6px;color:#fff;font-size:24px;font-weight:700;'>"
                + heading + "</h1>"
                + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>"
                + subheading + "</p>"
                + "</td></tr>"
                + "<tr><td style='padding:32px 40px;'>" + content + "</td></tr>"
                + "<tr><td style='background:#f8fafc;border-top:1px solid #e2e8f0;padding:24px 40px;"
                + "text-align:center;'>"
                + "<span style='display:inline-block;background:#000;padding:7px 18px;"
                + "border-radius:6px;color:#fff;font-size:13px;font-weight:700;"
                + "letter-spacing:1px;'>XAMOPS</span>"
                + "<p style='margin:12px 0 4px;color:#64748b;font-size:12px;'>"
                + "Cloud Cost Management &amp; Security Platform</p>"
                + "<p style='margin:0;color:#cbd5e1;font-size:11px;'>"
                + "&copy; 2026 XamOps. All rights reserved.</p>"
                + "</td></tr></table></td></tr></table></body></html>";
    }
}
