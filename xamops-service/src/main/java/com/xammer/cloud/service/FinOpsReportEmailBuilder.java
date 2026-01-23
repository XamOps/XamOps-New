package com.xammer.cloud.service;

import com.xammer.cloud.dto.DetailedCostDto;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FinOpsReportEmailBuilder {

    /**
     * Builds a professional HTML email for a cost report (AWS or GCP).
     * @param reportData The list of cost data (service, region, cost).
     * @param accountName The name of the account.
     * @param frequency The report frequency (e.g., "Daily", "Weekly").
     * @param dateRange The date range for the report (e.g., "2025-11-02").
     * @return An HTML string for the email body.
     */
    public String buildSimpleReportEmail(List<DetailedCostDto> reportData, String accountName, String frequency, String dateRange) {
        return buildProfessionalReportEmail(reportData, accountName, frequency, dateRange, null);
    }

    /**
     * Builds a professional HTML email for a cost report with optional cloud provider.
     * @param reportData The list of cost data (service, region, cost).
     * @param accountName The name of the account.
     * @param frequency The report frequency (e.g., "Daily", "Weekly").
     * @param dateRange The date range for the report (e.g., "2025-11-02").
     * @param cloudProvider The cloud provider (AWS, GCP, or null).
     * @return An HTML string for the email body.
     */
    public String buildProfessionalReportEmail(List<DetailedCostDto> reportData, String accountName,
            String frequency, String dateRange, String cloudProvider) {

        StringBuilder sb = new StringBuilder();

        // Calculate summary statistics
        double totalCost = 0.0;
        int serviceCount = 0;
        int regionCount = 0;

        if (reportData != null && !reportData.isEmpty()) {
            totalCost = reportData.stream().mapToDouble(DetailedCostDto::getCost).sum();
            serviceCount = (int) reportData.stream().map(DetailedCostDto::getService).distinct().count();
            regionCount = (int) reportData.stream().map(DetailedCostDto::getRegion).distinct().count();
        }

        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String providerBadge = getProviderBadge(cloudProvider);

        // --- HTML START ---
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang='en'>");
        sb.append("<head>");
        sb.append("<meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        sb.append("<title>FinOps Report - ").append(accountName).append("</title>");
        sb.append("</head>");
        sb.append("<body style='margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif; background-color: #f4f7fa; line-height: 1.6;'>");

        // Main Container
        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse;'>");
        sb.append("<tr><td style='padding: 40px 20px;'>");

        // Email Card
        sb.append("<table role='presentation' style='max-width: 700px; margin: 0 auto; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);'>");

        // --- HEADER ---
        sb.append("<tr>");
        sb.append("<td style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 40px 40px 30px 40px;'>");
        sb.append("<table role='presentation' style='width: 100%;'>");
        sb.append("<tr>");
        sb.append("<td>");
        // Logo/Brand
        sb.append("<div style='margin-bottom: 20px;'>");
        sb.append("<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>");
        if (providerBadge != null) {
            sb.append(providerBadge);
        }
        sb.append("</div>");
        // Title
        sb.append("<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 28px; font-weight: 700;'>").append(frequency).append(" Cost Report</h1>");
        sb.append("<p style='margin: 0; color: rgba(255,255,255,0.85); font-size: 16px;'>").append(accountName).append("</p>");
        sb.append("</td>");
        sb.append("</tr>");
        sb.append("</table>");
        sb.append("</td>");
        sb.append("</tr>");

        // --- SUMMARY CARDS ---
        sb.append("<tr>");
        sb.append("<td style='padding: 0 40px;'>");
        sb.append("<table role='presentation' style='width: 100%; margin-top: -30px;'>");
        sb.append("<tr>");

        // Total Cost Card
        sb.append("<td style='width: 33%; padding: 0 8px 0 0;'>");
        sb.append("<div style='background: #ffffff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); border: 1px solid #e8eef3;'>");
        sb.append("<p style='margin: 0 0 4px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>Total Cost</p>");
        sb.append("<p style='margin: 0; color: #1f2937; font-size: 24px; font-weight: 700;'>$").append(String.format("%,.2f", totalCost)).append("</p>");
        sb.append("</div>");
        sb.append("</td>");

        // Services Card
        sb.append("<td style='width: 33%; padding: 0 4px;'>");
        sb.append("<div style='background: #ffffff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); border: 1px solid #e8eef3;'>");
        sb.append("<p style='margin: 0 0 4px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>Services</p>");
        sb.append("<p style='margin: 0; color: #1f2937; font-size: 24px; font-weight: 700;'>").append(serviceCount).append("</p>");
        sb.append("</div>");
        sb.append("</td>");

        // Regions Card
        sb.append("<td style='width: 33%; padding: 0 0 0 8px;'>");
        sb.append("<div style='background: #ffffff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 12px rgba(0,0,0,0.1); border: 1px solid #e8eef3;'>");
        sb.append("<p style='margin: 0 0 4px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>Regions</p>");
        sb.append("<p style='margin: 0; color: #1f2937; font-size: 24px; font-weight: 700;'>").append(regionCount).append("</p>");
        sb.append("</div>");
        sb.append("</td>");

        sb.append("</tr>");
        sb.append("</table>");
        sb.append("</td>");
        sb.append("</tr>");

        // --- CONTENT ---
        sb.append("<tr>");
        sb.append("<td style='padding: 30px 40px;'>");

        // Report Period Info
        sb.append("<div style='background-color: #f8fafc; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; border-left: 4px solid #667eea;'>");
        sb.append("<p style='margin: 0; color: #475569; font-size: 14px;'>");
        sb.append("<strong style='color: #1e293b;'>Report Period:</strong> ").append(dateRange);
        sb.append(" &nbsp;|&nbsp; <strong style='color: #1e293b;'>Generated:</strong> ").append(generatedDate);
        sb.append("</p>");
        sb.append("</div>");

        // Cost Breakdown Section
        if (reportData == null || reportData.isEmpty()) {
            sb.append("<div style='text-align: center; padding: 40px 20px; background-color: #f8fafc; border-radius: 12px;'>");
            sb.append("<p style='margin: 0; color: #6b7280; font-size: 16px;'>📊 No cost data found for this period.</p>");
            sb.append("</div>");
        } else {
            // Section Title
            sb.append("<h2 style='margin: 0 0 16px 0; color: #1f2937; font-size: 18px; font-weight: 600;'>Cost Breakdown by Service & Region</h2>");

            // Cost Table
            sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse; border-radius: 12px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.05);'>");

            // Table Header
            sb.append("<thead>");
            sb.append("<tr style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);'>");
            sb.append("<th style='padding: 14px 16px; text-align: left; color: #ffffff; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;'>Service</th>");
            sb.append("<th style='padding: 14px 16px; text-align: left; color: #ffffff; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;'>Region</th>");
            sb.append("<th style='padding: 14px 16px; text-align: right; color: #ffffff; font-size: 13px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px;'>Cost (USD)</th>");
            sb.append("</tr>");
            sb.append("</thead>");

            // Table Body
            sb.append("<tbody>");
            int rowIndex = 0;
            for (DetailedCostDto item : reportData) {
                String bgColor = rowIndex % 2 == 0 ? "#ffffff" : "#f8fafc";
                String costColor = item.getCost() > 100 ? "#dc2626" : (item.getCost() > 50 ? "#d97706" : "#059669");

                sb.append("<tr style='background-color: ").append(bgColor).append(";'>");
                sb.append("<td style='padding: 14px 16px; color: #374151; font-size: 14px; border-bottom: 1px solid #e5e7eb;'>").append(item.getService()).append("</td>");
                sb.append("<td style='padding: 14px 16px; color: #6b7280; font-size: 14px; border-bottom: 1px solid #e5e7eb;'>").append(item.getRegion()).append("</td>");
                sb.append("<td style='padding: 14px 16px; text-align: right; font-size: 14px; font-weight: 600; color: ").append(costColor).append("; border-bottom: 1px solid #e5e7eb;'>$").append(String.format("%,.2f", item.getCost())).append("</td>");
                sb.append("</tr>");
                rowIndex++;
            }
            sb.append("</tbody>");

            // Table Footer (Total)
            sb.append("<tfoot>");
            sb.append("<tr style='background: linear-gradient(135deg, #1f2937 0%, #374151 100%);'>");
            sb.append("<td colspan='2' style='padding: 16px; color: #ffffff; font-size: 15px; font-weight: 700;'>Total</td>");
            sb.append("<td style='padding: 16px; text-align: right; color: #ffffff; font-size: 18px; font-weight: 700;'>$").append(String.format("%,.2f", totalCost)).append("</td>");
            sb.append("</tr>");
            sb.append("</tfoot>");

            sb.append("</table>");

            // Top Services Summary (if more than 3 services)
            if (serviceCount > 3) {
                sb.append("<div style='margin-top: 24px;'>");
                sb.append("<h3 style='margin: 0 0 12px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>Top Spending Services</h3>");
                sb.append("<table role='presentation' style='width: 100%;'>");

                // Group by service and get top 3
                Map<String, Double> serviceMap = reportData.stream()
                    .collect(Collectors.groupingBy(DetailedCostDto::getService, Collectors.summingDouble(DetailedCostDto::getCost)));

                final double finalTotalCost = totalCost;
                serviceMap.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(3)
                    .forEach(entry -> {
                        double percentage = (entry.getValue() / finalTotalCost) * 100;
                        int barWidth = (int) Math.min(percentage, 100);

                        sb.append("<tr>");
                        sb.append("<td style='padding: 8px 0;'>");
                        sb.append("<div style='display: flex; justify-content: space-between; margin-bottom: 4px;'>");
                        sb.append("<span style='color: #374151; font-size: 14px;'>").append(entry.getKey()).append("</span>");
                        sb.append("<span style='color: #6b7280; font-size: 14px;'>$").append(String.format("%,.2f", entry.getValue())).append(" (").append(String.format("%.1f", percentage)).append("%)</span>");
                        sb.append("</div>");
                        sb.append("<div style='background-color: #e5e7eb; border-radius: 4px; height: 8px; overflow: hidden;'>");
                        sb.append("<div style='background: linear-gradient(90deg, #667eea 0%, #764ba2 100%); width: ").append(barWidth).append("%; height: 100%; border-radius: 4px;'></div>");
                        sb.append("</div>");
                        sb.append("</td>");
                        sb.append("</tr>");
                    });

                sb.append("</table>");
                sb.append("</div>");
            }
        }

        sb.append("</td>");
        sb.append("</tr>");

        // --- FOOTER ---
        sb.append("<tr>");
        sb.append("<td style='background-color: #f8fafc; padding: 30px 40px; border-top: 1px solid #e5e7eb;'>");
        sb.append("<table role='presentation' style='width: 100%;'>");
        sb.append("<tr>");
        sb.append("<td style='text-align: center;'>");
        // Logo
        sb.append("<p style='margin: 0 0 12px 0;'>");
        sb.append("<span style='display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 8px 20px; border-radius: 6px; color: #ffffff; font-size: 14px; font-weight: 700; letter-spacing: 1px;'>XAMOPS</span>");
        sb.append("</p>");
        sb.append("<p style='margin: 0 0 8px 0; color: #6b7280; font-size: 13px;'>Cloud Cost Management & FinOps Platform</p>");
        sb.append("<p style='margin: 0 0 16px 0; color: #9ca3af; font-size: 12px;'>Optimize your cloud spend with intelligent insights</p>");
        // Divider
        sb.append("<div style='border-top: 1px solid #e5e7eb; margin: 16px 0;'></div>");
        // Copyright
        sb.append("<p style='margin: 0; color: #9ca3af; font-size: 12px;'>&copy; ").append(java.time.Year.now().getValue()).append(" XamOps. All rights reserved.</p>");
        sb.append("<p style='margin: 8px 0 0 0; color: #9ca3af; font-size: 11px;'>This is an automated report. Please do not reply to this email.</p>");
        sb.append("</td>");
        sb.append("</tr>");
        sb.append("</table>");
        sb.append("</td>");
        sb.append("</tr>");

        sb.append("</table>"); // End Email Card

        sb.append("</td></tr>");
        sb.append("</table>"); // End Main Container

        sb.append("</body>");
        sb.append("</html>");

        return sb.toString();
    }

    /**
     * Builds a professional HTML email for schedule confirmation.
     * @param accountName The name of the cloud account.
     * @param accountId The provider account ID.
     * @param recipientEmail The email address where reports will be sent.
     * @param frequency The report frequency (DAILY, WEEKLY, MONTHLY).
     * @param cloudProvider The cloud provider (AWS, GCP, or null).
     * @return An HTML string for the confirmation email body.
     */
    public String buildScheduleConfirmationEmail(String accountName, String accountId,
            String recipientEmail, String frequency, String cloudProvider) {

        StringBuilder sb = new StringBuilder();
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        String providerBadge = getProviderBadge(cloudProvider);
        String frequencyDisplay = formatFrequency(frequency);
        String scheduleDescription = getScheduleDescription(frequency);

        // --- HTML START ---
        sb.append("<!DOCTYPE html>");
        sb.append("<html lang='en'>");
        sb.append("<head>");
        sb.append("<meta charset='UTF-8'>");
        sb.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        sb.append("<title>Report Schedule Confirmed - XamOps</title>");
        sb.append("</head>");
        sb.append("<body style='margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif; background-color: #f4f7fa; line-height: 1.6;'>");

        // Main Container
        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse;'>");
        sb.append("<tr><td style='padding: 40px 20px;'>");

        // Email Card
        sb.append("<table role='presentation' style='max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 16px; overflow: hidden; box-shadow: 0 4px 24px rgba(0, 0, 0, 0.08);'>");

        // --- HEADER ---
        sb.append("<tr>");
        sb.append("<td style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 40px 40px 30px 40px; text-align: center;'>");
        // Logo/Brand
        sb.append("<div style='margin-bottom: 20px;'>");
        sb.append("<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>");
        sb.append("</div>");
        // Success Icon
        sb.append("<div style='margin-bottom: 16px;'>");
        sb.append("<span style='display: inline-block; background-color: rgba(255,255,255,0.2); width: 64px; height: 64px; border-radius: 50%; line-height: 64px; font-size: 32px;'>&#10003;</span>");
        sb.append("</div>");
        // Title
        sb.append("<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 24px; font-weight: 700;'>Report Schedule Confirmed</h1>");
        sb.append("<p style='margin: 0; color: rgba(255,255,255,0.85); font-size: 15px;'>Your FinOps report has been scheduled successfully</p>");
        sb.append("</td>");
        sb.append("</tr>");

        // --- CONTENT ---
        sb.append("<tr>");
        sb.append("<td style='padding: 30px 40px;'>");

        // Greeting
        sb.append("<p style='margin: 0 0 24px 0; color: #374151; font-size: 15px;'>Hello,</p>");
        sb.append("<p style='margin: 0 0 24px 0; color: #374151; font-size: 15px;'>Great news! Your automated FinOps report has been set up successfully. Here are the details of your schedule:</p>");

        // Schedule Details Card
        sb.append("<div style='background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%); border-radius: 12px; padding: 24px; margin-bottom: 24px; border: 1px solid #e2e8f0;'>");

        // Account Row
        sb.append("<div style='display: flex; margin-bottom: 16px; padding-bottom: 16px; border-bottom: 1px solid #e2e8f0;'>");
        sb.append("<table role='presentation' style='width: 100%;'><tr>");
        sb.append("<td style='width: 40px; vertical-align: top;'>");
        sb.append("<span style='display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); width: 36px; height: 36px; border-radius: 8px; text-align: center; line-height: 36px; color: #ffffff; font-size: 16px;'>&#9729;</span>");
        sb.append("</td>");
        sb.append("<td style='padding-left: 12px;'>");
        sb.append("<p style='margin: 0 0 2px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>Cloud Account</p>");
        sb.append("<p style='margin: 0; color: #1f2937; font-size: 15px; font-weight: 600;'>").append(accountName).append("</p>");
        sb.append("<p style='margin: 2px 0 0 0; color: #9ca3af; font-size: 13px;'>").append(accountId).append("</p>");
        if (providerBadge != null) {
            sb.append("<div style='margin-top: 8px;'>").append(providerBadge).append("</div>");
        }
        sb.append("</td>");
        sb.append("</tr></table>");
        sb.append("</div>");

        // Email Row
        sb.append("<div style='display: flex; margin-bottom: 16px; padding-bottom: 16px; border-bottom: 1px solid #e2e8f0;'>");
        sb.append("<table role='presentation' style='width: 100%;'><tr>");
        sb.append("<td style='width: 40px; vertical-align: top;'>");
        sb.append("<span style='display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); width: 36px; height: 36px; border-radius: 8px; text-align: center; line-height: 36px; color: #ffffff; font-size: 16px;'>&#9993;</span>");
        sb.append("</td>");
        sb.append("<td style='padding-left: 12px;'>");
        sb.append("<p style='margin: 0 0 2px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>Recipient Email</p>");
        sb.append("<p style='margin: 0; color: #1f2937; font-size: 15px; font-weight: 600;'>").append(recipientEmail).append("</p>");
        sb.append("</td>");
        sb.append("</tr></table>");
        sb.append("</div>");

        // Frequency Row
        sb.append("<div style='display: flex;'>");
        sb.append("<table role='presentation' style='width: 100%;'><tr>");
        sb.append("<td style='width: 40px; vertical-align: top;'>");
        sb.append("<span style='display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); width: 36px; height: 36px; border-radius: 8px; text-align: center; line-height: 36px; color: #ffffff; font-size: 16px;'>&#128197;</span>");
        sb.append("</td>");
        sb.append("<td style='padding-left: 12px;'>");
        sb.append("<p style='margin: 0 0 2px 0; color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;'>Report Frequency</p>");
        sb.append("<p style='margin: 0; color: #1f2937; font-size: 15px; font-weight: 600;'>").append(frequencyDisplay).append("</p>");
        sb.append("<p style='margin: 4px 0 0 0; color: #6b7280; font-size: 13px;'>").append(scheduleDescription).append("</p>");
        sb.append("</td>");
        sb.append("</tr></table>");
        sb.append("</div>");

        sb.append("</div>"); // End Schedule Details Card

        // Info Box
        sb.append("<div style='background-color: #eff6ff; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; border-left: 4px solid #667eea;'>");
        sb.append("<p style='margin: 0; color: #1e40af; font-size: 14px;'>");
        sb.append("<strong>What's next?</strong> You will receive your first report at the next scheduled time. Each report will include a detailed breakdown of your cloud costs by service and region.");
        sb.append("</p>");
        sb.append("</div>");

        sb.append("</td>");
        sb.append("</tr>");

        // --- FOOTER ---
        sb.append("<tr>");
        sb.append("<td style='background-color: #f8fafc; padding: 30px 40px; border-top: 1px solid #e5e7eb;'>");
        sb.append("<table role='presentation' style='width: 100%;'>");
        sb.append("<tr>");
        sb.append("<td style='text-align: center;'>");
        // Logo
        sb.append("<p style='margin: 0 0 12px 0;'>");
        sb.append("<span style='display: inline-block; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 8px 20px; border-radius: 6px; color: #ffffff; font-size: 14px; font-weight: 700; letter-spacing: 1px;'>XAMOPS</span>");
        sb.append("</p>");
        sb.append("<p style='margin: 0 0 8px 0; color: #6b7280; font-size: 13px;'>Cloud Cost Management & FinOps Platform</p>");
        // Divider
        sb.append("<div style='border-top: 1px solid #e5e7eb; margin: 16px 0;'></div>");
        // Copyright
        sb.append("<p style='margin: 0; color: #9ca3af; font-size: 12px;'>&copy; ").append(java.time.Year.now().getValue()).append(" XamOps. All rights reserved.</p>");
        sb.append("<p style='margin: 8px 0 0 0; color: #9ca3af; font-size: 11px;'>Schedule created on ").append(generatedDate).append("</p>");
        sb.append("</td>");
        sb.append("</tr>");
        sb.append("</table>");
        sb.append("</td>");
        sb.append("</tr>");

        sb.append("</table>"); // End Email Card

        sb.append("</td></tr>");
        sb.append("</table>"); // End Main Container

        sb.append("</body>");
        sb.append("</html>");

        return sb.toString();
    }

    /**
     * Formats frequency enum to display string.
     */
    private String formatFrequency(String frequency) {
        if (frequency == null) return "Unknown";
        switch (frequency.toUpperCase()) {
            case "DAILY": return "Daily";
            case "WEEKLY": return "Weekly";
            case "MONTHLY": return "Monthly";
            default: return frequency;
        }
    }

    /**
     * Returns a human-readable schedule description.
     */
    private String getScheduleDescription(String frequency) {
        if (frequency == null) return "";
        switch (frequency.toUpperCase()) {
            case "DAILY": return "Reports sent every day at 6:00 PM IST";
            case "WEEKLY": return "Reports sent every Friday at 6:00 PM IST";
            case "MONTHLY": return "Reports sent on the 30th of each month at 6:00 PM IST";
            default: return "";
        }
    }

    /**
     * Returns an HTML badge for the cloud provider.
     */
    private String getProviderBadge(String provider) {
        if (provider == null) return null;

        String bgColor;
        String icon;

        switch (provider.toUpperCase()) {
            case "AWS":
                bgColor = "#ff9900";
                icon = "AWS";
                break;
            case "GCP":
                bgColor = "#4285f4";
                icon = "GCP";
                break;
            case "AZURE":
                bgColor = "#0078d4";
                icon = "Azure";
                break;
            default:
                return null;
        }

        return String.format(
            "<span style='display: inline-block; background-color: %s; padding: 6px 12px; border-radius: 6px; color: #ffffff; font-size: 12px; font-weight: 600; margin-left: 10px;'>%s</span>",
            bgColor, icon
        );
    }
}