package com.xammer.cloud.service.cur;

/**
 * Which source the cost data for the current request should come from.
 *
 * <h2>Why a context rather than a parameter</h2>
 * The choice has to reach {@link CurSourceResolver}, which sits at the bottom of a call
 * chain the request never touches directly: controller → CostService/FinOpsService →
 * a dozen {@code @Async} methods → resolver. Threading a flag through every one of those
 * signatures would touch far more code than the behaviour warrants, and every new cost
 * method would have to remember to pass it along.
 *
 * <p>Propagated across the async boundary by the same {@code tenantTaskDecorator} that
 * already carries tenant and MDC context — see {@code AsyncConfig}. A plain ThreadLocal
 * would be lost the moment work moved onto the {@code awsTaskExecutor} pool, which is
 * where nearly all cost work actually happens.
 *
 * <h2>Why not simply key on forceRefresh</h2>
 * The nightly job and the UI's Refresh button both force a refresh, and they now want
 * opposite sources: the job should read the report, the button should hit the live Cost
 * Explorer API. {@code forceRefresh} cannot tell them apart, so the intent is stated
 * explicitly instead of inferred.
 */
public enum CostSourceContext {

    /**
     * Prefer the Cost and Usage Report, falling back to Cost Explorer when the report
     * cannot serve the request. The default, used by page loads and the nightly job.
     */
    AUTO,

    /**
     * Use the Cost Explorer API, even when a report could answer. What the UI's ordinary
     * Refresh button asks for: a live mid-day figure, independent of when the report was
     * last delivered.
     */
    COST_EXPLORER;

    private static final ThreadLocal<CostSourceContext> CURRENT = new ThreadLocal<>();

    /** The preference for the current request, defaulting to {@link #AUTO}. */
    public static CostSourceContext current() {
        CostSourceContext value = CURRENT.get();
        return value != null ? value : AUTO;
    }

    /** Raw value, for the task decorator to copy across an async boundary. */
    public static CostSourceContext peek() {
        return CURRENT.get();
    }

    public static void set(CostSourceContext preference) {
        CURRENT.set(preference);
    }

    /**
     * Clears the preference.
     *
     * <p>Must be called in a finally block on every request that sets it: these threads
     * are pooled, and a leaked value would silently send an unrelated later request to
     * the wrong source.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /** Parses a request parameter, treating anything unrecognised as {@link #AUTO}. */
    public static CostSourceContext parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return switch (raw.trim().toUpperCase()) {
            case "CE", "COST_EXPLORER", "COSTEXPLORER" -> COST_EXPLORER;
            default -> AUTO;
        };
    }
}
