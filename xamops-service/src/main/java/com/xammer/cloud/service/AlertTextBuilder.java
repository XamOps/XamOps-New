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
 * <p><b>Variable contract</b> — positional, must match the {@code xamops_alert} template body
 * "XamOps has detected issues in {{1}}. {{2}} resource(s) need attention, {{3}} of them critical.":
 * <ol>
 *   <li>account name</li>
 *   <li>total alert count</li>
 *   <li>count at CRITICAL</li>
 * </ol>
 *
 * <p>A positional list rather than a named map because that is what both APIs take: WhatsApp wants
 * {@code sample: {"bodyvar": [...]}} and DLT templates spell every placeholder {@code {#var#}}.
 *
 * <p>The portal link is static text in the template rather than a variable. Meta commonly rejects
 * templates that pass URLs through variables, and it is the same URL on every send anyway.
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
     * @return ordered positional values for WhatsApp {@code sample.bodyvar} or for rendering an SMS
     *         body with {@link com.xammer.cloud.messaging.MessageTemplate}
     */
    public List<String> buildAlertVariables(List<AlertDto> alerts, String accountName) {
        long critical = alerts == null ? 0 : alerts.stream()
                .filter(a -> "CRITICAL".equalsIgnoreCase(a.getStatus()))
                .count();

        // Order is the contract — see the class javadoc. Both channels fill positionally, so
        // swapping two entries here silently sends wrong values rather than failing.
        return List.of(
                safeAccountName(accountName),
                String.valueOf(alerts == null ? 0 : alerts.size()),
                String.valueOf(critical));
    }

    /** Alerts deep link, for callers that need it outside a template. */
    public String alertsUrl() {
        return frontendUrl + "/alerts";
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
