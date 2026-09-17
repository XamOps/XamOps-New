package com.xammer.cloud.service.azure;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code "MMM yyyy"} month label used across the Azure cost pipeline, and a parser for it.
 *
 * <p>
 * Exists because that label was being produced and consumed as a bare string with the JVM
 * default locale, and the two do not always agree.
 * {@code DateTimeFormatter.ofPattern("MMM yyyy")} resolves September to <b>"Sept 2026"</b> under
 * {@code en_GB} and <b>"Sep 2026"</b> under {@code en_US} and {@code Locale.ROOT} — CLDR gives
 * September, October and December a four-letter short form in some locales and a three-letter one
 * in others. Verified on a development machine whose default is {@code en_GB}, against stored
 * labels that read {@code ["Mar 2026", ..., "Aug 2026", "Sept 2026", "Sept 2026 (Forecast)"]}.
 *
 * <p>
 * The consequence was a silent zero. {@code AzureDashboardService} derived month-to-date spend by
 * comparing {@code LocalDate.now().format(ofPattern("MMM yyyy"))} to the labels in the stored cost
 * history with {@code String.equals}. Producer and consumer share a JVM so they normally agree —
 * but the labels are persisted, in Redis for a day and in {@code cached_data} forever, so anything
 * that changes the default locale between the write and the read leaves every stored label
 * unmatchable: a base image built with a different locale, a JDK or CLDR upgrade, a stray
 * {@code -Duser.language}. The dashboard then reports MTD as 0.00 while the cost chart beside it
 * renders the full history correctly, which is close to undiagnosable from the symptom, and would
 * appear to come and go with the calendar because only three months differ.
 *
 * <p>
 * Two defences, because the labels already in the field cannot be re-spelled retroactively:
 * <ul>
 * <li>{@link #of} pins the formatter to {@link Locale#ROOT}, so newly written labels no longer
 * depend on the host.</li>
 * <li>{@link #parse} accepts every spelling, so comparisons go through {@link YearMonth} rather
 * than string equality and existing {@code "Sept 2026"} rows keep resolving.</li>
 * </ul>
 * Callers should compare parsed {@code YearMonth} values, never the raw strings.
 */
public final class AzureMonthLabel {

    /** Suffix the ingestion job appends to a projected month. */
    public static final String FORECAST_SUFFIX = " (Forecast)";

    /**
     * The canonical form, pinned to {@link Locale#ROOT} so it is the same on every host.
     *
     * <p>
     * Kept as {@code "MMM yyyy"} rather than moved to something unambiguous like {@code "yyyy-MM"}
     * on purpose: the label is rendered directly as a chart axis label in the frontend, and it is
     * also the map key in the {@code azure:service-monthly} cache, so changing the shape would
     * break both the UI and every stored entry. Pinning the locale fixes the defect without
     * changing the contract.
     */
    private static final DateTimeFormatter CANONICAL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ROOT);

    /**
     * Every spelling a stored label might carry.
     *
     * <p>
     * {@code MMM} under {@code Locale.ROOT} parses "Sep" but rejects "Sept"; under {@code en_GB} it
     * parses "Sept" but rejects "Sep". {@code MMMM} covers a label written with a full month name.
     * Tried in order, so the canonical form costs one attempt.
     */
    private static final List<DateTimeFormatter> ACCEPTED = List.of(
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK),
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.US),
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK));

    private AzureMonthLabel() {
    }

    /** Canonical label for a month, e.g. {@code "Sep 2026"}. */
    public static String of(YearMonth month) {
        return month.atDay(1).format(CANONICAL);
    }

    /** Canonical label for the month containing a date. */
    public static String of(LocalDate date) {
        return date.format(CANONICAL);
    }

    /** Canonical forecast label for a month, e.g. {@code "Sep 2026 (Forecast)"}. */
    public static String forecastOf(YearMonth month) {
        return of(month) + FORECAST_SUFFIX;
    }

    /** Whether a label denotes a projected month rather than an observed one. */
    public static boolean isForecast(String label) {
        return label != null && label.contains("(Forecast)");
    }

    /**
     * Resolves a stored label to a month, whatever spelling or locale wrote it.
     *
     * <p>
     * The forecast suffix is stripped first, so {@code "Sept 2026 (Forecast)"} and
     * {@code "Sep 2026"} both resolve to the same {@link YearMonth} — which is what lets a caller
     * distinguish the two by {@link #isForecast} rather than by parsing failure.
     *
     * @return the month, or empty when the label is not a month label at all
     */
    public static Optional<YearMonth> parse(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String cleaned = label.replace(FORECAST_SUFFIX, "").replace("(Forecast)", "").trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        for (DateTimeFormatter format : ACCEPTED) {
            try {
                return Optional.of(YearMonth.parse(cleaned, format));
            } catch (Exception ignored) {
                // Try the next spelling. A label that matches none is genuinely not a month.
            }
        }
        return Optional.empty();
    }

    /** True when the label denotes the given month, regardless of how it was spelled. */
    public static boolean isMonth(String label, YearMonth month) {
        return parse(label).map(month::equals).orElse(false);
    }
}
