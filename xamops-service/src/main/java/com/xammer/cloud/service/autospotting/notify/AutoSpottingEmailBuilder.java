package com.xammer.cloud.service.autospotting.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.AutoSpottingNotification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the AutoSpotting notice email and the in-app line from an outbox row (design-ux-ops.md section 7.4,
 * PD-10). Hand-built markup like {@code AlertEmailBuilder}: the same black header frame, but black, white and
 * grey only (the severity colours of the alert emails are dropped), an inline SVG icon, ASCII text only (no
 * emoji, AC-OBS-03), and a deep link to {@code /autospotting/groups/:groupId}.
 *
 * <p>The builder reads only the row's {@code payload}: ids, stable codes and short labels. It never sees a raw
 * provider message, a credential or a stack trace (FR-SEC-06). Every value is HTML-escaped. Amounts are USD with
 * two decimals, rounded HALF_UP, formatted from a {@link BigDecimal}.
 */
@Component
public class AutoSpottingEmailBuilder {

    /** Every colour the template may use: black, white and greys. The unit test pins this set (AC-OBS-03). */
    public static final List<String> ALLOWED_COLORS = List.of("#000000", "#ffffff", "#111111", "#333333",
            "#555555", "#666666", "#888888", "#aaaaaa", "#cccccc", "#dddddd", "#e5e5e5", "#eeeeee", "#f5f5f5",
            "#fafafa");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** A built email: subject, HTML body and the plain-text equivalent. */
    public record Email(String subject, String html, String text) {
    }

    private final String frontendUrl;

    public AutoSpottingEmailBuilder(@Value("${app.frontend.url:https://live.xamops.com}") String frontendUrl) {
        this.frontendUrl = frontendUrl == null ? "" : frontendUrl.replaceAll("/+$", "");
    }

    /** The email for {@code n}. */
    public Email build(AutoSpottingNotification n) {
        NotificationEvent event = eventOf(n);
        JsonNode p = payload(n);
        Group g = group(p);
        String account = text(p, "accountName");
        String title = heading(event, p);
        List<String[]> rows = rows(event, n, p, g);
        String link = link(n);

        String subject = "[XamOps] AutoSpotting: " + event.title()
                + (g.name.isEmpty() ? "" : " - " + g.name)
                + (account.isEmpty() ? "" : " (" + account + ")");

        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1.0'>")
                .append("<title>").append(esc(subject)).append("</title></head>");
        html.append("<body style='margin:0;padding:0;background:#f5f5f5;font-family:-apple-system,"
                + "BlinkMacSystemFont,Arial,sans-serif;'>");
        html.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0'><tr>"
                + "<td style='padding:32px 16px;'>");
        html.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                + "style='max-width:640px;margin:0 auto;background:#ffffff;border:1px solid #dddddd;'>");
        // Header frame: black bar with the product name and a monochrome SVG icon.
        html.append("<tr><td style='background:#000000;padding:28px 36px;'>")
                .append("<table role='presentation' cellpadding='0' cellspacing='0'><tr>")
                .append("<td style='vertical-align:middle;padding-right:12px;'>").append(icon()).append("</td>")
                .append("<td style='vertical-align:middle;color:#ffffff;font-size:13px;font-weight:700;"
                        + "letter-spacing:2px;text-transform:uppercase;'>AutoSpotting</td>")
                .append("</tr></table></td></tr>");
        html.append("<tr><td style='padding:32px 36px 8px;'>")
                .append("<h1 style='margin:0 0 20px;color:#000000;font-size:22px;font-weight:700;'>")
                .append(esc(title)).append("</h1>");
        html.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' "
                + "style='border-top:1px solid #dddddd;'>");
        for (String[] row : rows) {
            html.append("<tr><td style='padding:10px 0;border-bottom:1px solid #eeeeee;color:#666666;"
                            + "font-size:13px;width:34%;vertical-align:top;'>").append(esc(row[0])).append("</td>")
                    .append("<td style='padding:10px 0;border-bottom:1px solid #eeeeee;color:#111111;"
                            + "font-size:13px;font-weight:500;vertical-align:top;'>").append(esc(row[1]))
                    .append("</td></tr>");
        }
        html.append("</table></td></tr>");
        html.append("<tr><td style='padding:20px 36px 28px;'><a href='").append(esc(link))
                .append("' style='display:inline-block;background:#000000;color:#ffffff;padding:11px 22px;"
                        + "font-size:13px;font-weight:600;text-decoration:none;'>")
                .append(g.name.isEmpty() ? "Open AutoSpotting" : "View group").append("</a></td></tr>");
        html.append("<tr><td style='background:#fafafa;border-top:1px solid #dddddd;padding:20px 36px;"
                        + "color:#666666;font-size:12px;'>")
                .append(esc(footerLine(event))).append("</td></tr>");
        html.append("</table></td></tr></table></body></html>");

        StringBuilder text = new StringBuilder();
        text.append("AutoSpotting\n").append(title).append("\n\n");
        for (String[] row : rows) {
            text.append(row[0]).append(": ").append(row[1]).append('\n');
        }
        text.append('\n').append(g.name.isEmpty() ? "Open AutoSpotting" : "View group").append(": ").append(link)
                .append("\n\n").append(footerLine(event)).append('\n');
        return new Email(subject, html.toString(), text.toString());
    }

    /** The short heading shown in the email and as the in-app title. */
    public String title(AutoSpottingNotification n) {
        return heading(eventOf(n), payload(n));
    }

    /** One plain line for the in-app list: the group, the account and the key fact. */
    public String summary(AutoSpottingNotification n) {
        NotificationEvent event = eventOf(n);
        JsonNode p = payload(n);
        Group g = group(p);
        List<String> parts = new ArrayList<>();
        if (!g.name.isEmpty()) {
            parts.add(g.name);
        }
        String account = text(p, "accountName");
        if (!account.isEmpty()) {
            parts.add(account);
        }
        parts.add(resultLine(event, p));
        return String.join(" - ", parts);
    }

    // ── content ──────────────────────────────────────────────────────────────

    private String heading(NotificationEvent event, JsonNode p) {
        return switch (event) {
            case PLAN_APPLIED -> "Plan applied";
            case APPLY_FAILED -> "restored".equals(outcomeWord(p)) ? "Apply failed and was rolled back"
                    : "Apply failed";
            case FALLBACK_ENTERED -> "Group moved to On-Demand fallback";
            case CONFIG_DEFERRED_BY_FALLBACK -> "Configuration change deferred";
            case FALLBACK_EXITED -> "Group returned to Spot";
            case DEGRADED_LONG -> "Group is degraded";
            case REVERT_STARTED -> "Revert started";
            case REVERT_COMPLETED -> "Revert completed";
            case REVERT_FAILED -> "Revert failed";
            case DRIFT_PAUSED -> "Group paused: configuration changed outside XamOps";
            case ADMIN_ENABLED -> "AutoSpotting was enabled by an administrator";
            case STATEMENT_READY -> "Statement ready";
            case DISPUTE_UPDATED -> "Dispute updated";
        };
    }

    private List<String[]> rows(NotificationEvent event, AutoSpottingNotification n, JsonNode p, Group g) {
        List<String[]> rows = new ArrayList<>();
        if (!g.name.isEmpty()) {
            rows.add(new String[] {"Group", g.name + (g.kind.isEmpty() ? "" : "  (" + g.kind
                    + (g.region.isEmpty() ? "" : ", " + g.region) + ")")});
        }
        String account = text(p, "accountName");
        rows.add(new String[] {"Account", account.isEmpty() ? "Account " + n.cloudAccountId() : account});
        rows.add(new String[] {"When", when(p, n)});
        switch (event) {
            case APPLY_FAILED, REVERT_FAILED -> {
                String code = text(p, "errorCode");
                rows.add(new String[] {"What failed", event == NotificationEvent.APPLY_FAILED
                        ? "The operation did not finish" + (code.isEmpty() ? "" : ". Code: " + code)
                        : "The revert did not finish" + (code.isEmpty() ? "" : ". Code: " + code)});
                rows.add(new String[] {"Result", resultLine(event, p)});
            }
            case STATEMENT_READY -> {
                rows.add(new String[] {"Period", text(p, "period")});
                String amount = money(text(p, "totalBillable"), text(p, "currency"));
                if (!amount.isEmpty()) {
                    rows.add(new String[] {"Amount", amount});
                }
            }
            case DISPUTE_UPDATED -> {
                rows.add(new String[] {"Period", text(p, "period")});
                rows.add(new String[] {"Status", disputeStatus(text(p, "status"))});
            }
            case ADMIN_ENABLED -> {
                rows.add(new String[] {"Enabled by", text(p, "confirmedBy")});
                rows.add(new String[] {"Reason", text(p, "reason")});
                rows.add(new String[] {"Result", resultLine(event, p)});
            }
            case REVERT_STARTED, REVERT_COMPLETED -> {
                String by = text(p, "confirmedBy");
                if (!by.isEmpty()) {
                    rows.add(new String[] {"Confirmed by", by});
                    rows.add(new String[] {"Reason", text(p, "reason")});
                }
                rows.add(new String[] {"Result", resultLine(event, p)});
            }
            default -> rows.add(new String[] {"Result", resultLine(event, p)});
        }
        String request = text(p, "requestId");
        if (!request.isEmpty()) {
            rows.add(new String[] {"Request id", request});
        }
        return rows;
    }

    private String resultLine(NotificationEvent event, JsonNode p) {
        return switch (event) {
            case PLAN_APPLIED -> switch (text(p, "operation")) {
                case "ADOPT" -> "The group was adopted by AutoSpotting.";
                case "CONFIG_APPLY" -> "The new configuration is applied.";
                default -> "The group is now managed by AutoSpotting.";
            };
            case APPLY_FAILED -> switch (outcomeWord(p)) {
                case "restored" -> "Original configuration restored.";
                case "no_change" -> "No change was made to the group.";
                default -> "The restore did not complete. Open the group to review it.";
            };
            case FALLBACK_ENTERED -> "AutoSpotting moved this group to its On-Demand fallback.";
            case CONFIG_DEFERRED_BY_FALLBACK -> "Your change is kept and applies when the group leaves fallback.";
            case FALLBACK_EXITED -> "The group is back on Spot.";
            case DEGRADED_LONG -> "The group has been degraded for more than " + number(p, "minutes", "the set number of")
                    + " minutes.";
            case REVERT_STARTED -> "The group is being returned to its original configuration.";
            case REVERT_COMPLETED -> "The group is back to its original configuration.";
            case REVERT_FAILED -> switch (outcomeWord(p)) {
                case "no_change" -> "No change was made to the group.";
                default -> "The revert did not complete. Open the group to review it.";
            };
            case DRIFT_PAUSED -> "AutoSpotting paused this group until you review the change.";
            case ADMIN_ENABLED -> "An administrator enabled AutoSpotting on your behalf.";
            case STATEMENT_READY -> "The statement for " + text(p, "period") + " is ready.";
            case DISPUTE_UPDATED -> "Dispute " + disputeStatus(text(p, "status")).toLowerCase(Locale.ROOT) + ".";
        };
    }

    private static String outcomeWord(JsonNode p) {
        return switch (text(p, "outcome")) {
            case "RESTORED" -> "restored";
            case "NO_CHANGE" -> "no_change";
            default -> "review";
        };
    }

    private static String disputeStatus(String status) {
        return switch (status) {
            case "HELD" -> "Held for review";
            case "RESOLVED_ACCEPTED" -> "Resolved, accepted";
            case "RESOLVED_REJECTED" -> "Resolved, not accepted";
            default -> "Updated";
        };
    }

    private static String footerLine(NotificationEvent event) {
        return event.mandatory()
                ? "You receive failure and safety notices for every account you can access."
                : "You can turn off AutoSpotting email in your profile. Failure notices are always sent.";
    }

    private String link(AutoSpottingNotification n) {
        return n.groupId() == null ? frontendUrl + "/autospotting"
                : frontendUrl + "/autospotting/groups/" + n.groupId();
    }

    /** A monochrome mark: a white circle outline with a lightning bolt. Inline SVG, no external image. */
    private static String icon() {
        return "<svg xmlns='http://www.w3.org/2000/svg' width='22' height='22' viewBox='0 0 24 24' fill='none' "
                + "stroke='#ffffff' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'>"
                + "<circle cx='12' cy='12' r='10'/><path d='M13 6l-5 7h4l-1 5 5-7h-4z'/></svg>";
    }

    // ── parsing and formatting ───────────────────────────────────────────────

    private static NotificationEvent eventOf(AutoSpottingNotification n) {
        return NotificationEvent.valueOf(n.eventType());
    }

    private static JsonNode payload(AutoSpottingNotification n) {
        try {
            return JSON.readTree(n.payload() == null ? "{}" : n.payload());
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }

    private record Group(String name, String kind, String region) {
    }

    /** AWS keys are {@code region/asgName}; GCP keys {@code project/location/migName}. */
    private static Group group(JsonNode p) {
        String key = text(p, "groupKey");
        if (key.isEmpty()) {
            return new Group("", "", "");
        }
        String provider = text(p, "provider");
        String[] parts = key.split("/");
        if ("GCP".equals(provider)) {
            return new Group(parts[parts.length - 1], "Managed Instance Group",
                    parts.length >= 3 ? parts[parts.length - 2] : "");
        }
        return new Group(parts.length >= 2 ? key.substring(key.indexOf('/') + 1) : key, "Auto Scaling Group",
                parts.length >= 2 ? parts[0] : "");
    }

    private static String when(JsonNode p, AutoSpottingNotification n) {
        String raw = text(p, "occurredAt");
        try {
            return WHEN.format(raw.isEmpty() ? n.createdAt() : Instant.parse(raw));
        } catch (Exception e) {
            return n.createdAt() == null ? "" : WHEN.format(n.createdAt());
        }
    }

    private static String number(JsonNode p, String field, String fallback) {
        JsonNode v = p.path(field);
        return v.isNumber() ? String.valueOf(v.asLong()) : fallback;
    }

    private static String money(String amount, String currency) {
        if (amount.isEmpty()) {
            return "";
        }
        try {
            BigDecimal value = new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
            DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
            return (currency.isEmpty() ? "USD" : currency) + " " + format.format(value);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** A payload string as printable ASCII only: no control character (header injection) and no emoji. */
    private static String text(JsonNode p, String field) {
        JsonNode v = p.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return "";
        }
        String raw = v.asText("");
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** HTML-escape a value; non-ASCII is dropped so no emoji can reach a message through a label. */
    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> {
                    if (c >= 0x20 && c < 0x7f) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
