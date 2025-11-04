package com.xammer.cloud.service;

import com.xammer.cloud.dto.DetailedCostDto;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class FinOpsReportEmailBuilder {

    /**
     * Builds a simple HTML email for a cost report (AWS or GCP).
     * @param reportData The list of cost data (service, region, cost).
     * @param accountName The name of the account.
     * @param frequency The report frequency (e.g., "Daily", "Weekly").
     * @param dateRange The date range for the report (e.g., "2025-11-02").
     * @return An HTML string for the email body.
     */
    public String buildSimpleReportEmail(List<DetailedCostDto> reportData, String accountName, String frequency, String dateRange) {
        StringBuilder sb = new StringBuilder();

        // --- STYLING ---
        sb.append("<style>")
          .append("body { font-family: Arial, sans-serif; margin: 0; padding: 0; color: #333; }")
          .append(".container { width: 90%; max-width: 800px; margin: 20px auto; border: 1px solid #ddd; border-radius: 8px; overflow: hidden; }")
          .append(".header { background-color: #667eea; color: #ffffff; padding: 20px; text-align: center; }")
          .append(".header h2 { margin: 0; }")
          .append(".content { padding: 30px; }")
          .append(".content p { line-height: 1.6; }")
          .append("table { width: 100%; border-collapse: collapse; margin-top: 20px; }")
          .append("th, td { border: 1px solid #ddd; padding: 12px; text-align: left; }")
          .append("th { background-color: #f4f4f4; color: #555; }")
          .append("tbody tr:nth-child(even) { background-color: #f9f9f9; }")
          .append(".footer { text-align: center; padding: 20px; font-size: 12px; color: #999; background-color: #f9f9f9; border-top: 1px solid #ddd; }")
          .append(".footer p { margin: 5px 0; }")
          .append("</style>");
          
        // --- EMAIL BODY ---
        sb.append("<div class='container'>");
        
        // Header
        sb.append("<div class='header'>");
        sb.append(String.format("<h2>Your %s FinOps Report</h2>", frequency));
        sb.append("</div>");
        
        // Content
        sb.append("<div class='content'>");
        sb.append(String.format("<p>Here is your %s FinOps report for account <b>%s</b> for the period <b>%s</b>.</p>", 
                frequency, accountName, dateRange));

        // Cost Table
        if (reportData == null || reportData.isEmpty()) {
            sb.append("<p>No cost data found for this period.</p>");
        } else {
            sb.append("<h3>Cost Breakdown by Service and Region</h3>");
            sb.append("<table>");
            sb.append("<thead><tr><th>Service</th><th>Region</th><th>Cost (USD)</th></tr></thead>");
            sb.append("<tbody>");
            
            double totalCost = 0.0;
            for (DetailedCostDto item : reportData) {
                sb.append(String.format("<tr><td>%s</td><td>%s</td><td>$%.2f</td></tr>",
                        item.getService(), item.getRegion(), item.getCost()));
                totalCost += item.getCost();
            }
            
            sb.append("</tbody>");
            // Total Row
            sb.append(String.format("<tfoot><tr style='background-color: #f4f4f4; font-weight: bold;'><td>Total</td><td></td><td>$%.2f</td></tr></tfoot>", totalCost));
            sb.append("</table>");
        }

        sb.append("</div>");

        // Footer / Watermark
        sb.append("<div class='footer'>");
        sb.append("<p><b>Generated from XamOps</b></p>");
        sb.append("<p>&copy; ").append(java.time.Year.now().getValue()).append(" XamOps. All rights reserved.</p>");
        sb.append("</div>");
        
        sb.append("</div>");

        return sb.toString();
    }
}