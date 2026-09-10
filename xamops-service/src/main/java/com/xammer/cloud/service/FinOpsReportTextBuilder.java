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
 * <p><b>Variable contract</b> (must match the registered SMS and WhatsApp templates):
 * <ul>
 *   <li>{@code var1} — frequency label, e.g. {@code Daily}</li>
 *   <li>{@code var2} — account name</li>
 *   <li>{@code var3} — date range</li>
 *   <li>{@code var4} — total spend, formatted with currency</li>
 *   <li>{@code var5} — deep link to the FinOps report page</li>
 * </ul>
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
    public Map<String, String> buildReportVariables(List<DetailedCostDto> reportData,
                                                    String accountName,
                                                    String frequency,
                                                    String dateRange) {
        double total = reportData == null ? 0.0 : reportData.stream()
                .filter(java.util.Objects::nonNull)
                .mapToDouble(DetailedCostDto::getCost)
                .sum();

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("var1", frequency == null ? "Scheduled" : frequency);
        vars.put("var2", safeAccountName(accountName));
        vars.put("var3", dateRange == null ? "" : dateRange);
        vars.put("var4", formatSpend(total));
        vars.put("var5", frontendUrl + "/finops");
        return vars;
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
