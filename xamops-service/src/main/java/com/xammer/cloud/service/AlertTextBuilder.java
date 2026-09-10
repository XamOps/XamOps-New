package com.xammer.cloud.service;

import com.xammer.cloud.dto.AlertDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the template variables for an alert digest delivered over SMS or WhatsApp.
 *
 * <p>Companion to {@link AlertEmailBuilder}, which produces a full HTML digest with a table per
 * alert type. That cannot be reused here for two reasons: SMS and WhatsApp carry no markup, and
 * the provider is DLT-registered so neither channel accepts free-form text at all — only an
 * approved template plus values. This class therefore emits a small ordered variable map, not a
 * finished message.
 *
 * <p><b>Variable contract</b> (must match the registered SMS and WhatsApp templates):
 * <ul>
 *   <li>{@code var1} — total alert count</li>
 *   <li>{@code var2} — account name</li>
 *   <li>{@code var3} — count at CRITICAL</li>
 *   <li>{@code var4} — deep link to the alerts page</li>
 * </ul>
 *
 * <p>The detail of <em>which</em> volumes are affected is deliberately left to the email and the
 * portal. A 160-character segment cannot carry it, and truncating a list of resource IDs produces
 * a message that alarms without informing.
 */
@Service
public class AlertTextBuilder {

    private final String frontendUrl;

    public AlertTextBuilder(@Value("${app.frontend.url:https://live.xamops.com}") String frontendUrl) {
        // Trailing slash would double up when the path is appended.
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
    }

    /**
     * @param alerts      the digest being sent; must not be empty
     * @param accountName human-readable cloud account name
     * @return ordered {@code var1..var4} map, safe to pass as WhatsApp {@code sample} or to
     *         render an SMS body with {@link com.xammer.cloud.messaging.MessageTemplate}
     */
    public Map<String, String> buildAlertVariables(List<AlertDto> alerts, String accountName) {
        long critical = alerts == null ? 0 : alerts.stream()
                .filter(a -> "CRITICAL".equalsIgnoreCase(a.getStatus()))
                .count();

        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("var1", String.valueOf(alerts == null ? 0 : alerts.size()));
        vars.put("var2", safeAccountName(accountName));
        vars.put("var3", String.valueOf(critical));
        vars.put("var4", frontendUrl + "/alerts");
        return vars;
    }

    /**
     * Account names are customer-supplied and unbounded, while an SMS segment is 160 characters.
     * Clipping here keeps the rendered body inside one segment instead of silently doubling the
     * per-message cost.
     */
    private String safeAccountName(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return "your cloud account";
        }
        String trimmed = accountName.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 39) + "…";
    }
}
