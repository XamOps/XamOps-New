package com.xammer.cloud.service;

import com.xammer.cloud.dto.DetailedCostDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the template variables for a FinOps report delivered over SMS or WhatsApp.
 *
 * <p>Companion to {@link FinOpsReportEmailBuilder}. The email embeds the full per-service,
 * per-region cost breakdown as an inline HTML table; there is no version of that which fits a
 * text message. So the mobile channels carry the one number that makes a person act — total
 * spend for the period — plus a deep link to the full report in the portal. This is a summary
 * with a pointer, not a replacement for the emailed report.
 *
 * <p><b>Variable contract</b> — positional, must match the {@code xamops_finops_report} template:
 * <ol>
 *   <li>frequency label, e.g. {@code Daily}</li>
 *   <li>account name</li>
 *   <li>date range</li>
 *   <li>total spend, formatted with currency</li>
 * </ol>
 *
 * <p>The portal link is static text in the template, not a variable — Meta commonly rejects
 * templates that pass URLs through variables, and the target never varies.
 */
@Service
public class FinOpsReportTextBuilder {

    private final String frontendUrl;

    public FinOpsReportTextBuilder(@Value("${app.frontend.url:https://live.xamops.com}") String frontendUrl) {
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
    }

    /**
     * @param reportData  cost rows for the period; may be null or empty, which yields a zero total
     * @param accountName human-readable cloud account name
     * @param frequency   {@code Daily} | {@code Weekly} | {@code Monthly}
     * @param dateRange   pre-formatted range as used in the email subject
     */
    public List<String> buildReportVariables(List<DetailedCostDto> reportData,
                                             String accountName,
                                             String frequency,
                                             String dateRange) {
        double total = reportData == null ? 0.0 : reportData.stream()
                .filter(java.util.Objects::nonNull)
                .mapToDouble(DetailedCostDto::getCost)
                .sum();

        // Order is the contract. Swapping date range and spend renders "total spend 2026-09-14",
        // which is exactly the mistake the SMS pattern originally shipped with.
        return List.of(
                frequency == null ? "Scheduled" : frequency,
                safeAccountName(accountName),
                dateRange == null ? "" : dateRange,
                formatSpend(total));
    }

    /** Report deep link, for callers that need it outside a template. */
    public String reportUrl() {
        return frontendUrl + "/finops";
    }

    /**
     * Whole dollars. Cents in a cost summary add characters to a length-capped message without
     * changing any decision the reader might make.
     */
    private String formatSpend(double total) {
        return String.format("$%,.0f", total);
    }

    private String safeAccountName(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return "your cloud account";
        }
        String trimmed = accountName.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 39) + "…";
    }
}
