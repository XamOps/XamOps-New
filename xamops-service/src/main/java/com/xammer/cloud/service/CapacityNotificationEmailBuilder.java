package com.xammer.cloud.service;

import com.xammer.cloud.dto.flexcompute.CapacityDescriptionResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;

/**
 * Service for building XamOps-themed HTML email templates for capacity threshold notifications.
 */
@Service
public class CapacityNotificationEmailBuilder {

    // XamOps Brand Colors
    private static final String PRIMARY_GRADIENT = "linear-gradient(135deg, #667eea 0%, #764ba2 100%)";
    private static final String WARNING_COLOR = "#d97706";
    private static final String WARNING_BG = "#fffbeb";
    private static final String CRITICAL_COLOR = "#dc2626";
    private static final String CRITICAL_BG = "#fef2f2";
    private static final String INFO_COLOR = "#0284c7";
    private static final String INFO_BG = "#e0f2fe";

    // Icons
    private static final String CAPACITY_ICON = "&#128202;"; // Chart increasing
    private static final String WARNING_ICON = "&#9888;";    // Warning sign
    private static final String CLOCK_ICON = "&#128337;";    // Clock
    private static final String EXTEND_ICON = "&#128260;";   // Extension icon
    private static final String SUCCESS_ICON = "&#9989;";    // Check mark
    private static final String ROCKET_ICON = "&#128640;";   // Rocket
    private static final String MONEY_ICON = "&#128176;";    // Money bag

    /**
     * Build HTML email for capacity threshold notification.
     */
    public String buildCapacityThresholdEmail(CapacityDescriptionResponse capacity,
                                              Double consumptionPercent,
                                              Integer thresholdPercent,
                                              Boolean wasExtended,
                                              String userEmail) {
        StringBuilder sb = new StringBuilder();
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        sb.append(getHtmlHeader("XamOps Capacity Alert - " + thresholdPercent + "% Threshold Reached"));
        sb.append(getBodyStart());
        sb.append(getMainContainerStart());
        sb.append(getEmailCardStart());

        // Header with threshold color
        sb.append(getCapacityHeader(capacity, consumptionPercent, thresholdPercent, wasExtended));

        // Capacity Details Section
        sb.append(getCapacityDetailsSection(capacity, consumptionPercent, thresholdPercent, wasExtended));

        // Usage Progress Bar
        sb.append(getProgressBarSection(consumptionPercent, thresholdPercent));

        // Action Section
        sb.append(getActionSection(capacity, consumptionPercent, thresholdPercent, wasExtended));

        // Footer
        sb.append(getFooter(generatedDate));

        sb.append(getEmailCardEnd());
        sb.append(getMainContainerEnd());
        sb.append(getBodyEnd());

        return sb.toString();
    }

    /**
     * Build HTML email for capacity purchase confirmation.
     */
    public String buildCapacityPurchaseEmail(CapacityDescriptionResponse capacity, String userEmail) {
        StringBuilder sb = new StringBuilder();
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        sb.append(getHtmlHeader("XamOps FlexCompute - Capacity Purchase Confirmation"));
        sb.append(getBodyStart());
        sb.append(getMainContainerStart());
        sb.append(getEmailCardStart());

        // Success Header
        sb.append(getPurchaseHeader(capacity));

        // Purchase Details
        sb.append(getPurchaseDetailsSection(capacity));

        // Cost Summary
        sb.append(getCostSummarySection(capacity));

        // Next Steps
        sb.append(getNextStepsSection(capacity));

        // Footer
        sb.append(getFooter(generatedDate));

        sb.append(getEmailCardEnd());
        sb.append(getMainContainerEnd());
        sb.append(getBodyEnd());

        return sb.toString();
    }

    /**
     * Build HTML email for capacity deployment notification (queued/scheduled -> active/running).
     */
    public String buildCapacityDeployedEmail(CapacityDescriptionResponse capacity, String userEmail) {
        StringBuilder sb = new StringBuilder();
        String generatedDate = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));

        sb.append(getHtmlHeader("XamOps FlexCompute - Capacity Now Active"));
        sb.append(getBodyStart());
        sb.append(getMainContainerStart());
        sb.append(getEmailCardStart());

        // Success Header
        sb.append(getDeployedHeader(capacity));

        // Capacity Details
        sb.append(getDeployedDetailsSection(capacity));

        // Next Steps
        sb.append(getDeployedNextStepsSection());

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

    private String getCapacityHeader(CapacityDescriptionResponse capacity,
                                     Double consumptionPercent,
                                     Integer thresholdPercent,
                                     Boolean wasExtended) {
        String bgColor = thresholdPercent >= 90 ? "linear-gradient(135deg, #dc2626 0%, #991b1b 100%)" :
                        thresholdPercent >= 80 ? "linear-gradient(135deg, #d97706 0%, #92400e 100%)" :
                        PRIMARY_GRADIENT;
        String statusText = thresholdPercent >= 90 ? "CRITICAL" : "WARNING";
        String icon = thresholdPercent >= 90 ? WARNING_ICON : CAPACITY_ICON;

        return "<tr>" +
                "<td style='background: " + bgColor + "; padding: 40px 40px 30px 40px;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td>" +
                // Brand
                "<div style='margin-bottom: 20px;'>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.3); padding: 6px 12px; border-radius: 6px; color: #ffffff; font-size: 12px; font-weight: 600; margin-left: 10px;'>" + statusText + "</span>" +
                (wasExtended ? "<span style='display: inline-block; background-color: rgba(255,255,255,0.3); padding: 6px 12px; border-radius: 6px; color: #ffffff; font-size: 12px; font-weight: 600; margin-left: 10px;'>" + EXTEND_ICON + " EXTENDED</span>" : "") +
                "</div>" +
                // Alert Icon and Title
                "<div style='font-size: 48px; margin-bottom: 16px;'>" + icon + "</div>" +
                "<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 26px; font-weight: 700;'>Capacity " + thresholdPercent + "% Threshold Reached</h1>" +
                "<p style='margin: 0; color: rgba(255,255,255,0.9); font-size: 16px; font-weight: 500;'>" + escapeHtml(capacity.getPolicyName()) + "</p>" +
                "<p style='margin: 8px 0 0 0; color: rgba(255,255,255,0.85); font-size: 15px;'>" + escapeHtml(capacity.getInstanceType()) + " | " + escapeHtml(capacity.getRegionZoneId()) + "</p>" +
                "</td></tr></table>" +
                "</td></tr>";
    }

    private String getCapacityDetailsSection(CapacityDescriptionResponse capacity,
                                             Double consumptionPercent,
                                             Integer thresholdPercent,
                                             Boolean wasExtended) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding: 30px 40px;'>");

        // Alert Message
        String alertColor = thresholdPercent >= 90 ? CRITICAL_COLOR : WARNING_COLOR;
        String alertBg = thresholdPercent >= 90 ? CRITICAL_BG : WARNING_BG;
        String alertMessage = wasExtended ?
                "Your extended capacity reservation has reached " + String.format("%.1f%%", consumptionPercent) + " consumption. " +
                (thresholdPercent >= 90 ?
                        "This is a critical threshold - consider extending the capacity end time to avoid service interruption." :
                        "Consider extending the capacity end time if you need more runtime.") :
                "Your capacity reservation has reached " + String.format("%.1f%%", consumptionPercent) + " consumption. " +
                (thresholdPercent >= 90 ?
                        "This is a critical threshold - you may want to extend the capacity end time." :
                        "You may want to extend the capacity end time if needed.");

        sb.append("<div style='background-color: ").append(alertBg).append("; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; border-left: 4px solid ")
                .append(alertColor).append(";'>");
        sb.append("<p style='margin: 0; color: #1e293b; font-size: 15px;'>")
                .append(alertMessage).append("</p>");
        sb.append("</div>");

        // Details Table
        sb.append("<h2 style='margin: 0 0 16px 0; color: #1f2937; font-size: 18px; font-weight: 600;'>Capacity Details</h2>");
        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse;'>");

        sb.append(getDetailRow("Capacity Name", escapeHtml(capacity.getPolicyName())));
        sb.append(getDetailRow("Capacity ID", escapeHtml(capacity.getCidrId())));
        sb.append(getDetailRow("Account", escapeHtml(capacity.getCspAccountName())));
        sb.append(getDetailRow("Instance Type", escapeHtml(capacity.getInstanceType())));
        sb.append(getDetailRow("Quantity", String.valueOf(capacity.getQuantities())));
        sb.append(getDetailRow("Region / Zone", escapeHtml(capacity.getRegionZoneId())));
        sb.append(getDetailRow("Status", "<span style='color: " + alertColor + "; font-weight: 600;'>" + escapeHtml(capacity.getPolicyStatus()) + "</span>"));
        sb.append(getDetailRow("Start Date", formatDateTime(capacity.getStartDate())));
        sb.append(getDetailRow("End Date", formatDateTime(capacity.getEndDate())));
        sb.append(getDetailRow("Total Hours", String.format("%.1f hours", capacity.getTotalUsageHours())));
        sb.append(getDetailRow("Remaining Hours", String.format("%.1f hours", capacity.getRemainingHours())));
        sb.append(getDetailRow("Consumption", "<span style='color: " + alertColor + "; font-weight: 700; font-size: 16px;'>" +
                String.format("%.1f%%", consumptionPercent) + "</span>"));
        sb.append(getDetailRow("Auto-Renewal", capacity.getAutoRenewal() ? "Enabled" : "Disabled"));

        if (wasExtended) {
            sb.append(getDetailRow("Extended", "<span style='background-color: " + INFO_BG + "; color: " + INFO_COLOR +
                    "; padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: 600;'>" + EXTEND_ICON + " YES</span>"));
        }

        sb.append("</table>");
        sb.append("</td></tr>");

        return sb.toString();
    }

    private String getProgressBarSection(Double consumptionPercent, Integer thresholdPercent) {
        String barColor = thresholdPercent >= 90 ? CRITICAL_COLOR : WARNING_COLOR;
        String barBg = thresholdPercent >= 90 ? CRITICAL_BG : WARNING_BG;

        return "<tr><td style='padding: 0 40px 30px 40px;'>" +
                "<div style='background-color: " + barBg + "; border-radius: 12px; padding: 20px;'>" +
                "<h3 style='margin: 0 0 12px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>" + CLOCK_ICON + " Capacity Consumption Progress</h3>" +
                "<div style='background-color: #e5e7eb; border-radius: 8px; height: 24px; overflow: hidden; position: relative;'>" +
                "<div style='background: linear-gradient(90deg, " + barColor + " 0%, " + darkenColor(barColor) + " 100%); height: 100%; width: " +
                String.format("%.1f", Math.min(consumptionPercent, 100)) + "%; transition: width 0.3s ease;'></div>" +
                "<div style='position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); color: #1f2937; font-size: 12px; font-weight: 700;'>" +
                String.format("%.1f%%", consumptionPercent) + "</div>" +
                "</div>" +
                "<div style='margin-top: 12px; display: flex; justify-content: space-between;'>" +
                "<span style='color: #6b7280; font-size: 13px;'>0%</span>" +
                "<span style='color: " + barColor + "; font-size: 13px; font-weight: 600;'>Current: " + String.format("%.1f%%", consumptionPercent) + "</span>" +
                "<span style='color: #6b7280; font-size: 13px;'>100%</span>" +
                "</div>" +
                "</div></td></tr>";
    }

    private String getActionSection(CapacityDescriptionResponse capacity,
                                    Double consumptionPercent,
                                    Integer thresholdPercent,
                                    Boolean wasExtended) {
        String recommendation = thresholdPercent >= 90 ?
                "Your capacity is critically close to expiration. We strongly recommend extending the end time immediately to avoid service interruption. You can extend the capacity directly from the XamOps FlexCompute dashboard." :
                "Your capacity consumption is at " + thresholdPercent + "%. Consider extending the end time if you need additional runtime. Monitor your usage closely to ensure uninterrupted service.";

        return "<tr><td style='padding: 0 40px 30px 40px;'>" +
                "<div style='background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%); border-radius: 12px; padding: 24px; border: 1px solid #e2e8f0;'>" +
                "<h3 style='margin: 0 0 12px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>Recommended Action</h3>" +
                "<p style='margin: 0 0 16px 0; color: #475569; font-size: 14px;'>" + recommendation + "</p>" +
                "<div style='display: flex; gap: 12px;'>" +
                "<a href='#' style='display: inline-block; background: " + PRIMARY_GRADIENT + "; color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;'>Extend Capacity</a>" +
                "<a href='#' style='display: inline-block; background: #f1f5f9; color: #475569; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;'>View Dashboard</a>" +
                "</div>" +
                "</div></td></tr>";
    }

    private String getFooter(String generatedDate) {
        return "<tr><td style='background-color: #f8fafc; padding: 30px 40px; border-top: 1px solid #e5e7eb;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td style='text-align: center;'>" +
                "<p style='margin: 0 0 12px 0;'>" +
                "<span style='display: inline-block; background: " + PRIMARY_GRADIENT + "; padding: 8px 20px; border-radius: 6px; color: #ffffff; font-size: 14px; font-weight: 700; letter-spacing: 1px;'>XAMOPS</span>" +
                "</p>" +
                "<p style='margin: 0 0 8px 0; color: #6b7280; font-size: 13px;'>FlexCompute Capacity Management</p>" +
                "<div style='border-top: 1px solid #e5e7eb; margin: 16px 0;'></div>" +
                "<p style='margin: 0; color: #9ca3af; font-size: 12px;'>&copy; " + Year.now().getValue() + " XamOps. All rights reserved.</p>" +
                "<p style='margin: 8px 0 0 0; color: #9ca3af; font-size: 11px;'>Generated on " + escapeHtml(generatedDate) + "</p>" +
                "<p style='margin: 8px 0 0 0; color: #9ca3af; font-size: 11px;'>This is an automated notification from XamOps FlexCompute monitoring.</p>" +
                "</td></tr></table></td></tr>";
    }

    private String getDetailRow(String label, String value) {
        return "<tr>" +
                "<td style='padding: 12px 0; border-bottom: 1px solid #e5e7eb; color: #6b7280; font-size: 14px; width: 40%;'>" + escapeHtml(label) + "</td>" +
                "<td style='padding: 12px 0; border-bottom: 1px solid #e5e7eb; color: #1f2937; font-size: 14px; font-weight: 500;'>" + value + "</td>" +
                "</tr>";
    }

    // ============ Purchase Email Helper Methods ============

    private String getPurchaseHeader(CapacityDescriptionResponse capacity) {
        return "<tr>" +
                "<td style='background: linear-gradient(135deg, #059669 0%, #047857 100%); padding: 40px 40px 30px 40px;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td>" +
                // Brand
                "<div style='margin-bottom: 20px;'>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.3); padding: 6px 12px; border-radius: 6px; color: #ffffff; font-size: 12px; font-weight: 600; margin-left: 10px;'>" + SUCCESS_ICON + " PURCHASED</span>" +
                "</div>" +
                // Success Icon and Title
                "<div style='font-size: 48px; margin-bottom: 16px;'>" + ROCKET_ICON + "</div>" +
                "<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 26px; font-weight: 700;'>Capacity Purchase Successful!</h1>" +
                "<p style='margin: 0; color: rgba(255,255,255,0.9); font-size: 16px; font-weight: 500;'>" + escapeHtml(capacity.getPolicyName()) + "</p>" +
                "<p style='margin: 8px 0 0 0; color: rgba(255,255,255,0.85); font-size: 15px;'>Your FlexCompute capacity reservation is now active</p>" +
                "</td></tr></table>" +
                "</td></tr>";
    }

    private String getPurchaseDetailsSection(CapacityDescriptionResponse capacity) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding: 30px 40px;'>");

        // Success Message
        sb.append("<div style='background-color: #ecfdf5; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; border-left: 4px solid #059669;'>");
        sb.append("<p style='margin: 0; color: #1e293b; font-size: 15px;'>");
        sb.append(SUCCESS_ICON).append(" <strong>Congratulations!</strong> Your capacity reservation has been successfully purchased and is now active. ");
        sb.append("You can start using your reserved instances immediately.");
        sb.append("</p>");
        sb.append("</div>");

        // Details Table
        sb.append("<h2 style='margin: 0 0 16px 0; color: #1f2937; font-size: 18px; font-weight: 600;'>Capacity Details</h2>");
        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse;'>");

        sb.append(getDetailRow("Capacity Name", escapeHtml(capacity.getPolicyName())));
        sb.append(getDetailRow("Capacity ID", "<code style='background-color: #f3f4f6; padding: 4px 8px; border-radius: 4px; font-size: 13px;'>" + escapeHtml(capacity.getCidrId()) + "</code>"));
        sb.append(getDetailRow("Account", escapeHtml(capacity.getCspAccountName())));
        sb.append(getDetailRow("Instance Type", "<strong>" + escapeHtml(capacity.getInstanceType()) + "</strong>"));
        sb.append(getDetailRow("Quantity", "<strong>" + capacity.getQuantities() + " instances</strong>"));
        sb.append(getDetailRow("Region / Zone", escapeHtml(capacity.getRegionZoneId())));

        if (capacity.getPlatform() != null) {
            sb.append(getDetailRow("Platform", escapeHtml(capacity.getPlatform())));
        }
        if (capacity.getVcpu() != null && capacity.getMemory() != null) {
            sb.append(getDetailRow("Resources", capacity.getVcpu() + " vCPU, " + capacity.getMemory() + " GB Memory"));
        }

        sb.append(getDetailRow("Start Date", formatDateTime(capacity.getStartDate())));
        sb.append(getDetailRow("End Date", formatDateTime(capacity.getEndDate())));
        sb.append(getDetailRow("Duration", String.format("%.1f hours", capacity.getTotalUsageHours())));
        sb.append(getDetailRow("Status", "<span style='background-color: #ecfdf5; color: #059669; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600;'>" +
                escapeHtml(capacity.getPolicyStatus()) + "</span>"));
        sb.append(getDetailRow("Auto-Renewal", capacity.getAutoRenewal() ?
                "<span style='color: #059669; font-weight: 600;'>" + SUCCESS_ICON + " Enabled</span>" :
                "<span style='color: #6b7280;'>Disabled</span>"));

        sb.append("</table>");
        sb.append("</td></tr>");

        return sb.toString();
    }

    private String getCostSummarySection(CapacityDescriptionResponse capacity) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding: 0 40px 30px 40px;'>");

        sb.append("<div style='background: linear-gradient(135deg, #f0fdf4 0%, #dcfce7 100%); border-radius: 12px; padding: 24px; border: 2px solid #86efac;'>");
        sb.append("<h3 style='margin: 0 0 16px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>" + MONEY_ICON + " Cost Summary</h3>");

        sb.append("<table role='presentation' style='width: 100%;'>");

        if (capacity.getAwsPrice() != null && capacity.getAwsPrice() > 0) {
            sb.append("<tr><td style='padding: 8px 0; color: #6b7280; font-size: 14px;'>AWS On-Demand Price</td>");
            sb.append("<td style='padding: 8px 0; text-align: right; color: #6b7280; font-size: 14px; text-decoration: line-through;'>$")
              .append(String.format("%.2f", capacity.getAwsPrice())).append("</td></tr>");
        }

        if (capacity.getOurPrice() != null && capacity.getOurPrice() > 0) {
            sb.append("<tr><td style='padding: 8px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>Your Price (CloudiDR)</td>");
            sb.append("<td style='padding: 8px 0; text-align: right; color: #059669; font-size: 18px; font-weight: 700;'>$")
              .append(String.format("%.2f", capacity.getOurPrice())).append("</td></tr>");
        }

        if (capacity.getSavingsPercent() != null && capacity.getSavingsPercent() > 0) {
            sb.append("<tr style='border-top: 2px solid #86efac;'><td style='padding: 12px 0 0 0; color: #059669; font-size: 15px; font-weight: 600;'>" + SUCCESS_ICON + " Total Savings</td>");
            sb.append("<td style='padding: 12px 0 0 0; text-align: right; color: #059669; font-size: 15px; font-weight: 700;'>")
              .append(String.format("%.1f%%", capacity.getSavingsPercent()));

            if (capacity.getTotalSavings() != null && capacity.getTotalSavings() > 0) {
                sb.append(" ($").append(String.format("%.2f", capacity.getTotalSavings())).append(")");
            }
            sb.append("</td></tr>");
        }

        sb.append("</table>");
        sb.append("</div>");
        sb.append("</td></tr>");

        return sb.toString();
    }

    private String getNextStepsSection(CapacityDescriptionResponse capacity) {
        return "<tr><td style='padding: 0 40px 30px 40px;'>" +
                "<div style='background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%); border-radius: 12px; padding: 24px; border: 1px solid #e2e8f0;'>" +
                "<h3 style='margin: 0 0 12px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>Next Steps</h3>" +
                "<ul style='margin: 0 0 16px 0; padding-left: 20px; color: #475569; font-size: 14px; line-height: 1.8;'>" +
                "<li>Your capacity reservation is now active and ready to use</li>" +
                "<li>Launch EC2 instances using your configured AMI and settings</li>" +
                "<li>Monitor your capacity usage in the XamOps FlexCompute dashboard</li>" +
                "<li>You'll receive email notifications at 80% and 90% capacity consumption</li>" +
                (capacity.getAutoRenewal() ? "<li>Auto-renewal is enabled - your capacity will be automatically extended</li>" : "<li>Remember to extend your capacity before it expires if needed</li>") +
                "</ul>" +
                "<div style='display: flex; gap: 12px;'>" +
                "<a href='#' style='display: inline-block; background: " + PRIMARY_GRADIENT + "; color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;'>View in Dashboard</a>" +
                "<a href='#' style='display: inline-block; background: #f1f5f9; color: #475569; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;'>Launch Instances</a>" +
                "</div>" +
                "</div></td></tr>";
    }

    // ============ Deployed Email Helper Methods ============

    private String getDeployedHeader(CapacityDescriptionResponse capacity) {
        return "<tr>" +
                "<td style='background: linear-gradient(135deg, #2563eb 0%, #1d4ed8 100%); padding: 40px 40px 30px 40px;'>" +
                "<table role='presentation' style='width: 100%;'><tr><td>" +
                "<div style='margin-bottom: 20px;'>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.2); padding: 8px 16px; border-radius: 8px; color: #ffffff; font-size: 14px; font-weight: 600; letter-spacing: 1px;'>XAMOPS</span>" +
                "<span style='display: inline-block; background-color: rgba(255,255,255,0.3); padding: 6px 12px; border-radius: 6px; color: #ffffff; font-size: 12px; font-weight: 600; margin-left: 10px;'>" + SUCCESS_ICON + " DEPLOYED</span>" +
                "</div>" +
                "<div style='font-size: 48px; margin-bottom: 16px;'>" + ROCKET_ICON + "</div>" +
                "<h1 style='margin: 0 0 8px 0; color: #ffffff; font-size: 26px; font-weight: 700;'>Capacity Is Now Active!</h1>" +
                "<p style='margin: 0; color: rgba(255,255,255,0.9); font-size: 16px; font-weight: 500;'>" + escapeHtml(capacity.getPolicyName()) + "</p>" +
                "<p style='margin: 8px 0 0 0; color: rgba(255,255,255,0.85); font-size: 15px;'>" + escapeHtml(capacity.getInstanceType()) + " | " + escapeHtml(capacity.getRegionZoneId()) + "</p>" +
                "</td></tr></table>" +
                "</td></tr>";
    }

    private String getDeployedDetailsSection(CapacityDescriptionResponse capacity) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr><td style='padding: 30px 40px;'>");

        // Success Message
        sb.append("<div style='background-color: #ecfdf5; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; border-left: 4px solid #059669;'>");
        sb.append("<p style='margin: 0; color: #1e293b; font-size: 15px;'>");
        sb.append(SUCCESS_ICON).append(" Your FlexCompute capacity reservation has been successfully deployed and is now <strong>active</strong>. ");
        sb.append("Your reserved EC2 instances are ready to use.");
        sb.append("</p>");
        sb.append("</div>");

        // Details Table
        sb.append("<h2 style='margin: 0 0 16px 0; color: #1f2937; font-size: 18px; font-weight: 600;'>Capacity Details</h2>");
        sb.append("<table role='presentation' style='width: 100%; border-collapse: collapse;'>");

        sb.append(getDetailRow("Capacity Name", escapeHtml(capacity.getPolicyName())));
        sb.append(getDetailRow("Capacity ID", "<code style='background-color: #f3f4f6; padding: 4px 8px; border-radius: 4px; font-size: 13px;'>" + escapeHtml(capacity.getCidrId()) + "</code>"));
        sb.append(getDetailRow("Account", escapeHtml(capacity.getCspAccountName())));
        sb.append(getDetailRow("Instance Type", "<strong>" + escapeHtml(capacity.getInstanceType()) + "</strong>"));
        sb.append(getDetailRow("Quantity", "<strong>" + capacity.getQuantities() + " instances</strong>"));
        sb.append(getDetailRow("Region / Zone", escapeHtml(capacity.getRegionZoneId())));
        sb.append(getDetailRow("Start Date", formatDateTime(capacity.getStartDate())));
        sb.append(getDetailRow("End Date", formatDateTime(capacity.getEndDate())));
        sb.append(getDetailRow("Duration", String.format("%.1f hours", capacity.getTotalUsageHours())));
        sb.append(getDetailRow("Status", "<span style='background-color: #ecfdf5; color: #059669; padding: 4px 12px; border-radius: 9999px; font-size: 12px; font-weight: 600;'>" +
                escapeHtml(capacity.getPolicyStatus()) + "</span>"));

        sb.append("</table>");
        sb.append("</td></tr>");

        return sb.toString();
    }

    private String getDeployedNextStepsSection() {
        return "<tr><td style='padding: 0 40px 30px 40px;'>" +
                "<div style='background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%); border-radius: 12px; padding: 24px; border: 1px solid #e2e8f0;'>" +
                "<h3 style='margin: 0 0 12px 0; color: #1f2937; font-size: 16px; font-weight: 600;'>What Happens Next?</h3>" +
                "<ul style='margin: 0 0 16px 0; padding-left: 20px; color: #475569; font-size: 14px; line-height: 1.8;'>" +
                "<li>Your EC2 instances are now running with the reserved capacity</li>" +
                "<li>Monitor your capacity usage in the XamOps FlexCompute dashboard</li>" +
                "<li>You'll receive email notifications at 80% and 90% capacity consumption</li>" +
                "<li>Extend your capacity end time if needed before it expires</li>" +
                "</ul>" +
                "<div style='display: flex; gap: 12px;'>" +
                "<a href='#' style='display: inline-block; background: " + PRIMARY_GRADIENT + "; color: #ffffff; padding: 12px 24px; border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;'>View in Dashboard</a>" +
                "</div>" +
                "</div></td></tr>";
    }

    // ============ Utility Methods ============

    private String formatDateTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isEmpty()) {
            return "N/A";
        }
        try {
            // Parse ISO 8601 and format to readable format
            return isoDateTime.replace("T", " ").substring(0, 19);
        } catch (Exception e) {
            return isoDateTime;
        }
    }

    private String darkenColor(String color) {
        // Simple color darkening for gradients
        if (color.equals(CRITICAL_COLOR)) return "#991b1b";
        if (color.equals(WARNING_COLOR)) return "#92400e";
        return "#5b21b6";
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
