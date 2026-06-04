package com.xammer.cloud.service;

import com.xammer.cloud.dto.AwsSecurityFinding;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.FinOpsReportDto;
import com.xammer.cloud.dto.XamOpsRightsizingRecommendation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MonthlyDigestEmailBuilder {

    public String build(
            FinOpsReportDto report,
            List<AwsSecurityFinding> securityFindings,
            List<XamOpsRightsizingRecommendation> ec2Recommendations,
            List<Map<String, Object>> diskRecommendations,
            String accountName,
            String dateRange) {

        StringBuilder sb = new StringBuilder();

        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'></head>");
        sb.append("<body style='margin:0;padding:24px;font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;background:#f1f5f9;line-height:1.5;'>");

        sb.append("<div style='max-width:700px;margin:0 auto;background:#ffffff;border-radius:16px;overflow:hidden;border:1px solid rgba(0,0,0,0.08);box-shadow:0 2px 16px rgba(0,0,0,0.06);'>");

        appendHeader(sb, accountName, dateRange);
        appendStatsStrip(sb, report);
        appendSecurityRow(sb, securityFindings);
        appendWasteRow(sb, report);
        appendResizeRow(sb, ec2Recommendations);
        if (!diskRecommendations.isEmpty()) {
            appendDiskRow(sb, diskRecommendations);
        }
        appendFooter(sb);

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private void appendHeader(StringBuilder sb, String accountName, String dateRange) {
        sb.append("<div style='background:#000000;padding:32px 36px 28px;border-bottom:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<div style='display:flex;align-items:center;justify-content:space-between;margin-bottom:18px;'>");
        sb.append("<span style='background:#ffffff;color:#000000;font-size:11px;font-weight:900;letter-spacing:2px;padding:5px 14px;border-radius:6px;'>XAMOPS</span>");
        sb.append("</div>");
        sb.append("<h1 style='color:#ffffff;font-size:26px;font-weight:900;letter-spacing:-0.5px;margin:0 0 4px;'>Monthly Cloud Digest</h1>");
        sb.append("<p style='color:rgba(255,255,255,0.40);font-size:13px;margin:0;'>")
          .append(escapeHtml(accountName))
          .append(" &nbsp;&middot;&nbsp; ")
          .append(escapeHtml(dateRange))
          .append("</p>");
        sb.append("</div>");
    }

    private void appendStatsStrip(StringBuilder sb, FinOpsReportDto report) {
        double mtd       = report.getKpis().getMonthToDateSpend();
        double lastMonth = report.getKpis().getLastMonthSpend();
        double savings   = report.getKpis().getPotentialSavings();

        String deltaHtml = "";
        if (lastMonth > 0) {
            double pct = ((mtd - lastMonth) / lastMonth) * 100.0;
            boolean up = pct >= 0;
            String arrow = up ? "&#9650;" : "&#9660;";
            String color = up ? "#dc2626" : "#16A34A";
            deltaHtml = String.format("<span style='font-size:11px;font-weight:700;color:%s;'>%s %.1f%% vs last month ($%s)</span>",
                    color, arrow, Math.abs(pct), formatAmount(lastMonth));
        }

        String topServiceName = "—";
        String topServiceSub  = "";
        List<Map<String, Object>> byService = report.getCostBreakdown() != null
                ? report.getCostBreakdown().getByService() : List.of();
        if (byService != null && !byService.isEmpty()) {
            Map<String, Object> top = byService.get(0);
            // byService map uses "service" (name) and "cost" (amount) keys
            topServiceName = String.valueOf(top.getOrDefault("service", top.getOrDefault("name", "—")));
            Object amt = top.getOrDefault("cost", top.getOrDefault("amount", 0.0));
            double topAmt = amt instanceof Number ? ((Number) amt).doubleValue() : 0;
            double pct = mtd > 0 ? (topAmt / mtd) * 100.0 : 0;
            topServiceSub = String.format("$%s &middot; %.0f%% of total", formatAmount(topAmt), pct);
        }

        sb.append("<table role='presentation' style='width:100%;border-collapse:collapse;border-bottom:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<tr>");

        sb.append("<td style='padding:22px 0 22px 36px;width:33%;border-right:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<p style='margin:0 0 6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:rgba(0,0,0,0.35);'>Total Spend</p>");
        sb.append("<p style='margin:0 0 5px;font-size:32px;font-weight:900;color:rgba(0,0,0,0.88);letter-spacing:-1px;line-height:1;'>$").append(formatAmount(mtd)).append("</p>");
        sb.append(deltaHtml);
        sb.append("</td>");

        sb.append("<td style='padding:22px 0 22px 24px;width:33%;border-right:1px solid rgba(0,0,0,0.06);'>");
        sb.append("<p style='margin:0 0 6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:rgba(0,0,0,0.35);'>Potential Savings</p>");
        sb.append("<p style='margin:0 0 5px;font-size:32px;font-weight:900;color:#16A34A;letter-spacing:-1px;line-height:1;'>$").append(formatAmount(savings)).append("</p>");
        sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.35);'>across all recommendations</span>");
        sb.append("</td>");

        sb.append("<td style='padding:22px 0 22px 24px;width:33%;'>");
        sb.append("<p style='margin:0 0 6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:0.1em;color:rgba(0,0,0,0.35);'>Top Service</p>");
        sb.append("<p style='margin:0 0 5px;font-size:22px;font-weight:900;color:rgba(0,0,0,0.88);letter-spacing:-0.5px;line-height:1;padding-top:5px;'>")
          .append(escapeHtml(topServiceName)).append("</p>");
        if (!topServiceSub.isEmpty()) {
            sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.35);'>").append(topServiceSub).append("</span>");
        }
        sb.append("</td>");

        sb.append("</tr></table>");
    }

    private void appendSecurityRow(StringBuilder sb, List<AwsSecurityFinding> findings) {
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.getSeverity())).count();
        long high     = findings.stream().filter(f -> "HIGH".equals(f.getSeverity())).count();
        long medium   = findings.stream().filter(f -> "MEDIUM".equals(f.getSeverity())).count();
        long low      = findings.stream().filter(f -> "LOW".equals(f.getSeverity())).count();

        String topFinding = findings.stream()
                .filter(f -> "CRITICAL".equals(f.getSeverity()) || "HIGH".equals(f.getSeverity()))
                .findFirst()
                .map(f -> escapeHtml(f.getTitle()) + " &middot; " + escapeHtml(f.getRegion()))
                .orElse(findings.isEmpty() ? "No active findings detected." : "");

        sb.append(rowOpen("Security"));
        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;margin-bottom:10px;'>");
        appendSevPill(sb, critical + " Critical", "#fef2f2", "#fecaca", "#dc2626");
        appendSevPill(sb, high     + " High",     "#fff7ed", "#fed7aa", "#ea580c");
        appendSevPill(sb, medium   + " Medium",   "#fefce8", "#fef08a", "#ca8a04");
        appendSevPill(sb, low      + " Low",       "#f0fdf4", "#bbf7d0", "#16a34a");
        sb.append("</div>");
        if (!topFinding.isEmpty()) {
            sb.append("<p style='font-size:12px;color:rgba(0,0,0,0.40);margin:0;'>Top finding: <strong style='color:rgba(0,0,0,0.60);'>")
              .append(topFinding).append("</strong></p>");
        }
        sb.append(rowClose());
    }

    private void appendSevPill(StringBuilder sb, String label, String bg, String border, String color) {
        sb.append("<span style='display:inline-flex;align-items:center;gap:5px;border-radius:7px;padding:7px 12px;background:")
          .append(bg).append(";border:1px solid ").append(border).append(";font-size:12px;font-weight:700;color:").append(color).append(";'>")
          .append("<span style='width:6px;height:6px;border-radius:50%;background:currentColor;display:inline-block;'></span>")
          .append(label)
          .append("</span>");
    }

    private void appendWasteRow(StringBuilder sb, FinOpsReportDto report) {
        List<DashboardData.WastedResource> items = report.getWastedResources();
        if (items == null) items = List.of();

        List<DashboardData.WastedResource> top5 = items.stream()
                .sorted((a, b) -> Double.compare(b.getMonthlySavings(), a.getMonthlySavings()))
                .limit(5)
                .collect(Collectors.toList());

        sb.append(rowOpen("Waste"));
        if (top5.isEmpty()) {
            sb.append("<p style='font-size:13px;color:rgba(0,0,0,0.40);margin:0;'>No waste identified this month.</p>");
        } else {
            for (DashboardData.WastedResource w : top5) {
                sb.append("<div style='display:flex;align-items:center;justify-content:space-between;padding:9px 0;border-bottom:1px solid rgba(0,0,0,0.04);'>");
                sb.append("<div>");
                sb.append("<span style='font-family:monospace;font-size:12px;color:rgba(0,0,0,0.75);font-weight:600;display:block;'>")
                  .append(escapeHtml(w.getResourceId())).append("</span>");
                sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.38);'>")
                  .append(escapeHtml(w.getResourceType()))
                  .append(" &middot; ").append(escapeHtml(w.getReason()))
                  .append("</span>");
                sb.append("</div>");
                sb.append("<span style='font-size:13px;font-weight:800;color:#16A34A;white-space:nowrap;'>$")
                  .append(formatAmount(w.getMonthlySavings())).append("/mo</span>");
                sb.append("</div>");
            }
        }
        sb.append(rowClose());
    }

    private void appendResizeRow(StringBuilder sb, List<XamOpsRightsizingRecommendation> recs) {
        List<XamOpsRightsizingRecommendation> top5 = recs.stream().limit(5).collect(Collectors.toList());

        sb.append(rowOpen("Resize"));
        if (top5.isEmpty()) {
            sb.append("<p style='font-size:13px;color:rgba(0,0,0,0.40);margin:0;'>No rightsizing recommendations.</p>");
        } else {
            for (XamOpsRightsizingRecommendation r : top5) {
                String recommended = r.getIntelRecommendation() != null && !r.getIntelRecommendation().isEmpty()
                        ? r.getIntelRecommendation() : r.getAmdRecommendation();
                sb.append("<div style='display:flex;align-items:center;justify-content:space-between;padding:9px 0;border-bottom:1px solid rgba(0,0,0,0.04);'>");
                sb.append("<div>");
                sb.append("<span style='font-family:monospace;font-size:12px;color:rgba(0,0,0,0.75);font-weight:600;display:block;'>")
                  .append(escapeHtml(r.getInstanceId())).append("</span>");
                sb.append("<span style='font-size:11px;color:rgba(0,0,0,0.38);'>")
                  .append(escapeHtml(r.getCurrentInstance()))
                  .append(" <span style='color:rgba(0,0,0,0.25);'>&#8594;</span> ")
                  .append(escapeHtml(recommended != null ? recommended : "—"))
                  .append(" &middot; ").append(escapeHtml(r.getRegion()))
                  .append("</span>");
                sb.append("</div>");
                if (r.getApproxCostSavings() != null && !r.getApproxCostSavings().isBlank()) {
                    sb.append("<span style='font-size:13px;font-weight:800;color:#16A34A;white-space:nowrap;'>")
                      .append(escapeHtml(r.getApproxCostSavings())).append("/mo</span>");
                }
                sb.append("</div>");
            }
        }
        sb.append(rowClose());
    }

    private void appendDiskRow(StringBuilder sb, List<Map<String, Object>> vols) {
        sb.append(rowOpen("Disk"));
        sb.append("<div style='display:flex;gap:8px;flex-wrap:wrap;'>");
        for (Map<String, Object> vol : vols) {
            String volumeId    = String.valueOf(vol.getOrDefault("volumeId", "—"));
            String region      = String.valueOf(vol.getOrDefault("region", "—"));
            int    sizeGb      = vol.containsKey("sizeGb") ? ((Number) vol.get("sizeGb")).intValue() : 0;
            double usedPct     = vol.containsKey("usedPercent") ? ((Number) vol.get("usedPercent")).doubleValue() : 0;
            String rec         = String.valueOf(vol.getOrDefault("recommendation", ""));
            boolean nearFull   = "EXPAND".equals(rec);
            String bg          = nearFull ? "#fef2f2" : "#fefce8";
            String border      = nearFull ? "#fecaca" : "#fef08a";
            String statusColor = nearFull ? "#dc2626"  : "#ca8a04";
            String statusText  = nearFull ? String.format("%.0f%% full", usedPct) : String.format("%.0f%% used", usedPct);
            String action      = nearFull ? "Expand" : "Shrink";

            sb.append("<div style='flex:1;min-width:160px;border-radius:9px;padding:11px 14px;border:1px solid ")
              .append(border).append(";background:").append(bg).append(";'>");
            sb.append("<div style='font-family:monospace;font-size:11px;font-weight:700;color:rgba(0,0,0,0.70);margin-bottom:3px;'>")
              .append(escapeHtml(volumeId)).append("</div>");
            sb.append("<div style='font-size:11px;color:rgba(0,0,0,0.40);'>")
              .append(escapeHtml(region)).append(" &middot; ").append(sizeGb).append(" GB &middot; ")
              .append("<strong style='color:").append(statusColor).append(";'>").append(statusText).append("</strong>")
              .append(" &middot; ").append(action)
              .append("</div>");
            sb.append("</div>");
        }
        sb.append("</div>");
        sb.append(rowClose());
    }

    private void appendFooter(StringBuilder sb) {
        sb.append("<div style='background:rgba(0,0,0,0.02);padding:22px 36px;border-top:1px solid rgba(0,0,0,0.06);text-align:center;'>");
        sb.append("<div style='display:inline-block;background:#000000;color:#ffffff;font-size:11px;font-weight:900;letter-spacing:2px;padding:5px 16px;border-radius:6px;margin-bottom:10px;'>XAMOPS</div><br>");
        sb.append("<p style='color:rgba(0,0,0,0.35);font-size:12px;margin:0 0 3px;'>Cloud Cost Management &amp; FinOps Platform</p>");
        sb.append("<p style='color:rgba(0,0,0,0.20);font-size:11px;margin:8px 0 0;padding-top:8px;border-top:1px solid rgba(0,0,0,0.05);'>");
        sb.append("&copy; 2026 XamOps &nbsp;&middot;&nbsp; Sent automatically on the 30th of each month &nbsp;&middot;&nbsp; This is an automated report. Please do not reply to this email.");
        sb.append("</p></div>");
    }

    private String rowOpen(String label) {
        return "<table role='presentation' style='width:100%;border-collapse:collapse;border-bottom:1px solid rgba(0,0,0,0.05);'>" +
               "<tr>" +
               "<td style='width:110px;padding:22px 0 22px 28px;border-right:1px solid rgba(0,0,0,0.05);vertical-align:top;'>" +
               "<span style='display:inline-flex;align-items:center;font-size:10px;font-weight:800;text-transform:uppercase;" +
               "letter-spacing:0.07em;color:rgba(0,0,0,0.35);background:rgba(0,0,0,0.03);border:1px solid rgba(0,0,0,0.07);" +
               "border-radius:5px;padding:4px 9px;'>" + label + "</span>" +
               "</td>" +
               "<td style='padding:20px 28px 20px 20px;vertical-align:top;'>";
    }

    private String rowClose() {
        return "</td></tr></table>";
    }

    private String formatAmount(double amount) {
        if (amount >= 1000) {
            return String.format("%,.0f", amount);
        }
        return String.format("%.0f", amount);
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
