package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData.BudgetDetails;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;

/**
 * Builds XamOps-branded HTML email templates for budget notifications.
 */
@Service
public class BudgetEmailBuilder {

    private static final String PRIMARY_GRADIENT = "linear-gradient(135deg, #667eea 0%, #764ba2 100%)";

    // Threshold level colors
    private static final String INFO_COLOR    = "#2563eb";
    private static final String INFO_BG       = "#eff6ff";
    private static final String WARNING_COLOR = "#d97706";
    private static final String WARNING_BG    = "#fffbeb";
    private static final String DANGER_COLOR  = "#dc2626";
    private static final String DANGER_BG     = "#fef2f2";

    /**
     * Email sent immediately when a budget is first created.
     */
    public String buildBudgetCreatedEmail(BudgetDetails budget, String accountName, String accountId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String subject = "Budget Created: " + budget.getBudgetName();

        return buildWrapper(subject,
                headerSection(PRIMARY_GRADIENT, "&#127775;", "Budget Created",
                        "Your new budget has been set up and is now active."),
                summarySection(budget, accountName, accountId),
                infoBox(INFO_COLOR, INFO_BG,
                        "What happens next?",
                        "XamOps will monitor your spend against this budget and alert you at "
                        + "<strong>50%</strong>, <strong>80%</strong>, and <strong>100%</strong> of the limit. "
                        + "You will also receive a forecasted overage warning before you actually exceed the budget."),
                ctaButton("View Budget in XamOps"),
                footer(date));
    }

    /**
     * Email sent when actual spend crosses a threshold (50, 80, 100 %).
     */
    public String buildBudgetThresholdEmail(BudgetDetails budget, String accountName, String accountId,
                                            double thresholdPct, boolean isForecasted) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        String gradientColor;
        String badgeBg;
        String badgeColor;
        String icon;
        String title;
        String bodyLine;

        if (thresholdPct >= 100) {
            gradientColor = "linear-gradient(135deg, #dc2626 0%, #991b1b 100%)";
            badgeBg = DANGER_BG; badgeColor = DANGER_COLOR;
            icon = "&#128680;"; // siren
            title = isForecasted ? "Budget Forecast: Overspend Expected" : "Budget Exceeded";
            bodyLine = isForecasted
                    ? "AWS forecasts your spend will <strong>exceed 100%</strong> of your budget before the month ends."
                    : "Your actual spend has <strong>exceeded your budget limit</strong> for this month.";
        } else if (thresholdPct >= 80) {
            gradientColor = "linear-gradient(135deg, #d97706 0%, #92400e 100%)";
            badgeBg = WARNING_BG; badgeColor = WARNING_COLOR;
            icon = "&#9888;"; // warning
            title = isForecasted ? "Budget Forecast: 80% Warning" : "Budget Alert: 80% Reached";
            bodyLine = isForecasted
                    ? "AWS forecasts your spend will reach <strong>80%</strong> of your budget."
                    : "Your actual spend has reached <strong>80%</strong> of your budget limit.";
        } else {
            gradientColor = "linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%)";
            badgeBg = INFO_BG; badgeColor = INFO_COLOR;
            icon = "&#128200;"; // chart
            title = isForecasted ? "Budget Forecast: 50% Notice" : "Budget Alert: 50% Reached";
            bodyLine = isForecasted
                    ? "AWS forecasts your spend will reach <strong>50%</strong> of your budget."
                    : "Your actual spend has reached <strong>50%</strong> of your budget limit.";
        }

        String spendLine = isForecasted
                ? "Forecasted: <strong>$" + fmt(budget.getForecastedSpend()) + "</strong> / Limit: <strong>$" + fmt(budget.getBudgetLimit()) + "</strong>"
                : "Spent: <strong>$" + fmt(budget.getActualSpend()) + "</strong> / Limit: <strong>$" + fmt(budget.getBudgetLimit()) + "</strong>";

        double pct = isForecasted ? computePct(budget.getForecastedSpend(), budget.getBudgetLimit())
                                  : computePct(budget.getActualSpend(),     budget.getBudgetLimit());

        return buildWrapper(title,
                headerSection(gradientColor, icon, title, bodyLine),
                summarySection(budget, accountName, accountId),
                progressBarSection(pct, badgeColor, badgeBg, spendLine),
                infoBox(badgeColor, badgeBg,
                        "Recommended Action",
                        thresholdPct >= 100
                                ? "Review recent deployments and resource changes. Consider cleaning up unused resources or adjusting your budget limit."
                                : "Monitor your spend closely. Review the FinOps dashboard for cost breakdown and optimization opportunities."),
                ctaButton("Review in XamOps FinOps"),
                footer(date));
    }

    /**
     * Email sent when a GCP budget is created. Takes raw request params since
     * GCP budget creation does not return a full BudgetDetails object.
     */
    public String buildGcpBudgetCreatedEmail(String budgetName, double limit, String currency, String projectId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String title = "GCP Budget Created";
        return buildWrapper(title,
                headerSection("linear-gradient(135deg,#4285f4 0%,#1a73e8 100%)", "&#127975;", title,
                        "Your new GCP budget is active and monitoring has started."),
                simpleDetailSection(budgetName, currency + " " + String.format("%.2f", limit), projectId, "GCP Project"),
                infoBox(INFO_COLOR, INFO_BG,
                        "What happens next?",
                        "XamOps will monitor your GCP spend against this budget and alert you at "
                        + "<strong>50%</strong>, <strong>80%</strong>, <strong>90%</strong>, and <strong>100%</strong> of the limit."),
                ctaButton("View Budget in XamOps"),
                footer(date));
    }

    /**
     * Email sent when an Azure budget is created.
     */
    public String buildAzureBudgetCreatedEmail(String budgetName, double limit, String currency, String subscriptionId) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String title = "Azure Budget Created";
        return buildWrapper(title,
                headerSection("linear-gradient(135deg,#0078d4 0%,#005a9e 100%)", "&#128176;", title,
                        "Your new Azure budget is active and monitoring has started."),
                simpleDetailSection(budgetName, currency + " " + String.format("%.2f", limit), subscriptionId, "Subscription"),
                infoBox(INFO_COLOR, INFO_BG,
                        "What happens next?",
                        "XamOps will monitor your Azure spend against this budget and alert you at "
                        + "<strong>50%</strong>, <strong>80%</strong>, and <strong>100%</strong> of the limit."),
                ctaButton("View Budget in XamOps"),
                footer(date));
    }

    // ─────────────────────────────────────────────
    // Private building blocks
    // ─────────────────────────────────────────────

    private String buildWrapper(String title, String... sections) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='en'><head>")
          .append("<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'>")
          .append("<title>").append(esc(title)).append("</title></head>")
          .append("<body style='margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Arial,sans-serif;background:#f4f7fa;line-height:1.6;'>")
          .append("<table role='presentation' style='width:100%;border-collapse:collapse;'>")
          .append("<tr><td style='padding:40px 20px;'>")
          .append("<table role='presentation' style='max-width:680px;margin:0 auto;background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);'>");
        for (String s : sections) sb.append(s);
        sb.append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    private String headerSection(String gradient, String icon, String title, String subtitle) {
        return "<tr><td style='background:" + gradient + ";padding:40px;'>"
                + "<div style='margin-bottom:16px;'>"
                + "<span style='display:inline-block;background:rgba(255,255,255,0.2);padding:7px 16px;border-radius:8px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
                + "</div>"
                + "<div style='font-size:44px;margin-bottom:14px;'>" + icon + "</div>"
                + "<h1 style='margin:0 0 8px 0;color:#fff;font-size:24px;font-weight:700;'>" + esc(title) + "</h1>"
                + "<p style='margin:0;color:rgba(255,255,255,0.85);font-size:15px;'>" + subtitle + "</p>"
                + "</td></tr>";
    }

    private String summarySection(BudgetDetails budget, String accountName, String accountId) {
        return "<tr><td style='padding:30px 40px 0 40px;'>"
                + "<h2 style='margin:0 0 16px 0;color:#1f2937;font-size:17px;font-weight:600;'>Budget Details</h2>"
                + "<table role='presentation' style='width:100%;border-collapse:collapse;'>"
                + detailRow("Budget Name",   esc(budget.getBudgetName()))
                + detailRow("Account",       esc(accountName) + " <span style='color:#9ca3af;font-size:12px;'>(" + esc(accountId) + ")</span>")
                + detailRow("Limit",         "<strong>$" + fmt(budget.getBudgetLimit()) + " " + esc(budget.getBudgetUnit()) + "</strong>")
                + detailRow("Actual Spend",  "$" + fmt(budget.getActualSpend()))
                + detailRow("Forecasted",    "$" + fmt(budget.getForecastedSpend()))
                + detailRow("Period",        "Monthly (resets on the 1st)")
                + "</table></td></tr>";
    }

    private String simpleDetailSection(String budgetName, String limit, String accountRef, String accountLabel) {
        return "<tr><td style='padding:30px 40px 0 40px;'>"
                + "<h2 style='margin:0 0 16px 0;color:#1f2937;font-size:17px;font-weight:600;'>Budget Details</h2>"
                + "<table role='presentation' style='width:100%;border-collapse:collapse;'>"
                + detailRow("Budget Name", esc(budgetName))
                + detailRow("Limit",       "<strong>" + esc(limit) + "</strong>")
                + detailRow(esc(accountLabel), esc(accountRef))
                + detailRow("Period", "Monthly (resets on the 1st)")
                + "</table></td></tr>";
    }

    private String progressBarSection(double pct, String color, String bg, String spendLine) {
        double barPct = Math.min(pct, 100.0);
        return "<tr><td style='padding:24px 40px;'>"
                + "<div style='background:" + bg + ";border-radius:12px;padding:20px;border:1px solid " + color + "33;'>"
                + "<div style='display:flex;justify-content:space-between;margin-bottom:8px;'>"
                + "<span style='font-size:13px;font-weight:600;color:" + color + ";'>" + String.format("%.1f", pct) + "% used</span>"
                + "<span style='font-size:13px;color:#6b7280;'>" + spendLine + "</span>"
                + "</div>"
                + "<div style='background:#e5e7eb;border-radius:9999px;height:10px;overflow:hidden;'>"
                + "<div style='background:" + color + ";height:10px;width:" + String.format("%.1f", barPct) + "%;border-radius:9999px;'></div>"
                + "</div>"
                + "</div></td></tr>";
    }

    private String infoBox(String color, String bg, String heading, String body) {
        return "<tr><td style='padding:0 40px 28px 40px;'>"
                + "<div style='background:" + bg + ";border-radius:10px;padding:18px 20px;border-left:4px solid " + color + ";'>"
                + "<h3 style='margin:0 0 6px 0;color:#1f2937;font-size:14px;font-weight:600;'>" + esc(heading) + "</h3>"
                + "<p style='margin:0;color:#374151;font-size:14px;'>" + body + "</p>"
                + "</div></td></tr>";
    }

    private String ctaButton(String label) {
        return "<tr><td style='padding:0 40px 32px 40px;text-align:center;'>"
                + "<a href='#' style='display:inline-block;background:" + PRIMARY_GRADIENT + ";color:#fff;padding:13px 32px;"
                + "border-radius:8px;text-decoration:none;font-size:14px;font-weight:600;letter-spacing:0.3px;'>"
                + esc(label) + "</a></td></tr>";
    }

    private String footer(String date) {
        return "<tr><td style='background:#f8fafc;padding:28px 40px;border-top:1px solid #e5e7eb;text-align:center;'>"
                + "<span style='display:inline-block;background:" + PRIMARY_GRADIENT + ";padding:7px 18px;border-radius:6px;color:#fff;font-size:13px;font-weight:700;letter-spacing:1px;'>XAMOPS</span>"
                + "<p style='margin:12px 0 4px 0;color:#6b7280;font-size:13px;'>Cloud Cost Management &amp; FinOps Platform</p>"
                + "<div style='border-top:1px solid #e5e7eb;margin:14px 0;'></div>"
                + "<p style='margin:0;color:#9ca3af;font-size:12px;'>&copy; " + Year.now().getValue() + " XamOps. All rights reserved.</p>"
                + "<p style='margin:6px 0 0 0;color:#9ca3af;font-size:11px;'>Sent on " + esc(date) + " &middot; You are receiving this because you created a budget alert.</p>"
                + "</td></tr>";
    }

    private String detailRow(String label, String value) {
        return "<tr>"
                + "<td style='padding:10px 0;border-bottom:1px solid #e5e7eb;color:#6b7280;font-size:14px;width:38%;'>" + esc(label) + "</td>"
                + "<td style='padding:10px 0;border-bottom:1px solid #e5e7eb;color:#1f2937;font-size:14px;font-weight:500;'>" + value + "</td>"
                + "</tr>";
    }

    private String fmt(BigDecimal val) {
        if (val == null) return "0.00";
        return val.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private double computePct(BigDecimal spend, BigDecimal limit) {
        if (spend == null || limit == null || limit.compareTo(BigDecimal.ZERO) == 0) return 0.0;
        return spend.divide(limit, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                   .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
