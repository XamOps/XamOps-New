package com.xammer.cloud.service.matters;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Translations between what the Matters API returns and what the Matters UI shows — plus the two
 * places where the live API disagrees with its own documentation.
 *
 * Centralised here rather than done inline so both the list mapper and the filter mapper apply the
 * same rules. A severity that round-trips one way in the table and another way in the filter rail is
 * the bug this file exists to prevent.
 */
public final class DspmLabels {

    private DspmLabels() {
    }

    /**
     * The sentinel the API uses for "no policy has been evaluated".
     *
     * <p>This is the first documented/actual mismatch. The docs describe {@code severity} as
     * {@code CRITICAL · HIGH · MEDIUM · LOW · null}. The live service instead reports an explicit
     * string {@code "NONE"} in {@code /assets/filters} — 109 of 132 assets in the test project — while
     * the corresponding list rows carry {@code risk.level: null}. Filtering {@code ?severity=NONE}
     * works and returns exactly those rows.
     *
     * <p>So the two surfaces use different representations for the same state, and both have to be
     * handled: rows need null folded to {@code NONE} so a filter chip can be built from them, and a
     * chip selection needs to be sent back as the literal {@code NONE}. Treating null as
     * unfilterable would drop 83% of the estate out of the filter rail.
     */
    public static final String SEVERITY_NONE = "NONE";

    /** Severity bands that represent an actual finding, highest first. */
    private static final Set<String> REAL_SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /**
     * Display names for sensitive-data categories, taken from the Matters console.
     *
     * Matters' own UI does not show the API's category codes — it shows "Personal" where the API says
     * {@code PII}, and "IT &amp; Security" where the API says {@code SECRETS}. Customers see the
     * vendor console and XamOps side by side, so showing the raw codes would make the same asset look
     * like it had different findings in each product.
     *
     * Unmapped codes fall through to {@link #prettify}, because the docs are explicit that these are
     * open sets and new detectors get added.
     */
    private static final Map<String, String> SENSITIVE_CATEGORY_LABELS = Map.of(
            "PII", "Personal",
            "SECRETS", "IT & Security",
            "PCI", "Financial",
            "PHI", "Health");

    /**
     * Scan statuses under which a sensitive-record count of zero means "nobody has looked", not
     * "nothing is there".
     *
     * <p>This is the second and more consequential mismatch. The documented enum
     * ({@code SCHEDULED}/{@code SCANNING}/{@code CLASSIFIED}/{@code FAILED}/{@code STOPPED}, plus
     * {@code ACTIVE}/{@code SUSPENDED} for SaaS and endpoints) does not match the live service, which
     * returns {@code NOT_SCANNED} — a value absent from the docs entirely — for the majority of
     * assets, and never returns {@code SCHEDULED} at all.
     *
     * <p>Why it matters: in the test project, 71 of 100 rows reported {@code record_count: 0}, but 59
     * were {@code NOT_SCANNED}, 11 {@code FAILED} and 1 {@code STOPPED}. Only 11 were genuinely
     * scanned and clean. Rendering the raw zero would tell a customer that 71% of their estate holds
     * no sensitive data when 11% had actually been checked — and the three highest-risk assets in that
     * project are among the stopped ones.
     *
     * @see #sensitivesKnown(String)
     */
    private static final Set<String> UNSCANNED_STATUSES =
            Set.of("NOT_SCANNED", "SCHEDULED", "SCANNING", "FAILED", "STOPPED");

    /**
     * Folds a row's null severity to the {@link #SEVERITY_NONE} sentinel the filter dimension uses.
     *
     * @return {@code NONE} for null or blank, otherwise the value uppercased.
     */
    public static String normaliseSeverity(String raw) {
        if (raw == null || raw.isBlank()) {
            return SEVERITY_NONE;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /** True when a severity represents a real finding rather than "not evaluated". */
    public static boolean isRealSeverity(String raw) {
        return raw != null && REAL_SEVERITIES.contains(raw.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Whether a sensitive-record count from this scan status can be believed.
     *
     * The single rule behind every "Not scanned" cell in the UI. Exposed as a boolean on each row so
     * the frontend never has to re-derive it — a rule re-implemented per component is a rule that will
     * eventually be implemented differently in one of them.
     *
     * Only {@code CLASSIFIED} counts. An unrecognised status is treated as <em>not</em> known, which
     * is the safe direction: a new vendor status would otherwise start silently asserting that unread
     * assets are clean.
     */
    public static boolean sensitivesKnown(String scanStatus) {
        return "CLASSIFIED".equalsIgnoreCase(scanStatus == null ? "" : scanStatus.trim());
    }

    /** True when the status means a scan has not produced results — for the UI's explanatory text. */
    public static boolean isUnscanned(String scanStatus) {
        return scanStatus == null
                || UNSCANNED_STATUSES.contains(scanStatus.trim().toUpperCase(Locale.ROOT));
    }

    /** Console display name for a sensitive-data category code. */
    public static String sensitiveCategoryLabel(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String key = category.trim().toUpperCase(Locale.ROOT);
        return SENSITIVE_CATEGORY_LABELS.getOrDefault(key, prettify(key));
    }

    /**
     * Human label for a scan status.
     *
     * Matches the vendor console's wording ("Scanned", "Scan Stopped") rather than echoing the raw
     * enum, for the same side-by-side reason as the category labels.
     */
    public static String scanStatusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "Unknown";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "CLASSIFIED" -> "Scanned";
            case "NOT_SCANNED" -> "Not scanned";
            case "SCANNING" -> "Scanning";
            case "FAILED" -> "Scan failed";
            case "STOPPED" -> "Scan stopped";
            case "SCHEDULED" -> "Scheduled";
            case "ACTIVE" -> "Active";
            case "SUSPENDED" -> "Suspended";
            default -> prettify(status);
        };
    }

    /**
     * Label for a filter option value, so the rail reads like prose rather than like an enum.
     *
     * {@code severity} deliberately passes through unchanged apart from {@code NONE}: the bands are
     * already the words the console uses, and title-casing "CRITICAL" to "Critical" is the frontend's
     * styling decision, not a data one.
     */
    public static String filterOptionLabel(String dimension, String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            // The live data-element dimension really does contain an empty-string value with a
            // non-zero count. Naming it is better than rendering a blank, unclickable row.
            return "Unclassified";
        }
        return switch (dimension) {
            case "sensitive_type" -> sensitiveCategoryLabel(value);
            case "scan_status" -> scanStatusLabel(value);
            case "severity" -> SEVERITY_NONE.equals(value) ? "No findings" : prettify(value);
            // data_element values already arrive human-readable ("PERSON NAME", "ACCESS KEY AWS"), and
            // prettifying them is what makes "ACCESS KEY AWS" render as "Access Key Aws". Left alone.
            case "data_element" -> value;
            default -> prettify(value);
        };
    }

    /**
     * {@code SELF_HOSTED_POSTGRES} → {@code Self Hosted Postgres}.
     *
     * Underscores to spaces, then title case. Applied only where a value is a machine enum; see the
     * {@code data_element} case above for why it is not applied blindly.
     */
    public static String prettify(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String[] words = raw.trim().replace('_', ' ').toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Maps a list of raw category codes to their display labels, preserving order. */
    public static Map<String, String> sensitiveCategoryLabels(Iterable<String> categories) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String category : categories) {
            if (category != null && !category.isBlank()) {
                labels.put(category, sensitiveCategoryLabel(category));
            }
        }
        return labels;
    }
}
