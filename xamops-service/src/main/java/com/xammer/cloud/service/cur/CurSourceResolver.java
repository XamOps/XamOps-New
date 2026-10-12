package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.repository.CurSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Decides, per query, whether a CUR can answer it or Cost Explorer must.
 *
 * <p>The decision is per query rather than per account because the reasons a CUR
 * cannot answer are mostly about the <em>range</em> asked for, not the account: a
 * legacy CUR does not backfill, so history before the report was created simply
 * is not there, and a monthly report cannot produce a daily series. An account
 * flagged as CUR-backed will still route some queries to CE, and that is the
 * intended behaviour, not a degraded mode.
 *
 * <p>Returning empty here is always safe — the caller falls back to the existing
 * Cost Explorer path, which is what every account did before CUR support existed.
 */
@Service
public class CurSourceResolver {

    private static final Logger logger = LoggerFactory.getLogger(CurSourceResolver.class);

    private final CurSourceRepository curSourceRepository;

    /**
     * Asked to repair a source this class has just rejected as stale. Injected rather
     * than scheduled because staleness is only ever discovered here.
     */
    private final CurIngestionService ingestionService;

    /**
     * Master switch. Off by default so the migration can land, sources can be
     * configured and validated, and the read path can be turned on per
     * environment once a source has actually delivered data.
     */
    @Value("${xamops.cur.enabled:false}")
    private boolean curEnabled;

    /**
     * How recently the source must have been validated to be trusted, in hours.
     *
     * <p>A CUR that has stopped being delivered goes stale silently: the table is
     * still there and still queryable, it just stops gaining rows, so the current
     * month quietly flattens instead of erroring. Past this age the source is
     * treated as unusable and CE takes over — a visibly different number beats a
     * number that stopped moving.
     */
    @Value("${xamops.cur.max-validation-age-hours:48}")
    private long maxValidationAgeHours;

    public CurSourceResolver(CurSourceRepository curSourceRepository,
                             CurIngestionService ingestionService) {
        this.curSourceRepository = curSourceRepository;
        this.ingestionService = ingestionService;
    }

    /**
     * The CUR source able to answer a query over {@code start}..{@code end} for
     * this account, or empty when Cost Explorer should be used.
     *
     * @param requiresDaily true when the caller needs per-day buckets, which a
     *                      MONTHLY report cannot provide
     */
    public Optional<CurSource> resolve(CloudAccount account, LocalDate start, LocalDate end,
                                       boolean requiresDaily) {
        // The caller asked for Cost Explorer explicitly — the UI's ordinary Refresh
        // button, which exists to give a live mid-day figure regardless of when the
        // report was last delivered. Declining here sends the query down the CE path
        // exactly as it did before CUR support existed.
        //
        // Checked before every other condition so it cannot be overridden by any of
        // them, and so the reason a request went to CE is unambiguous in the logs.
        //
        // Kept here rather than in describeCoverage because the two answer different
        // questions: this one is "what should this request use", while coverage is
        // "what could the report answer", which must not change with the caller's
        // preference — the FinOps page asks about coverage precisely so it can decide
        // whether to offer Cost Explorer.
        if (CostSourceContext.current() == CostSourceContext.COST_EXPLORER) {
            logger.debug("[CUR] Cost Explorer requested explicitly for {} — skipping the report",
                    account == null ? "(no account)" : account.getAwsAccountId());
            return Optional.empty();
        }
        return describeCoverage(account, start, end, requiresDaily).source();
    }

    /**
     * Whether the report can answer a query over this range, and if not, why.
     *
     * <p>Same checks as {@link #resolve}, which delegates here — the reason has to be the
     * real one, so there is deliberately no second copy of this logic to drift out of step.
     *
     * <p>Exists because "no data" is not a good enough answer for someone who has just
     * picked a date range. A report that begins in July cannot answer a question about
     * April, and saying so — with the date it does begin — is the difference between a
     * user understanding the limit and assuming the product is broken.
     */
    public Coverage describeCoverage(CloudAccount account, LocalDate start, LocalDate end,
                                     boolean requiresDaily) {
        if (!curEnabled) {
            return Coverage.unavailable("DISABLED",
                    "Cost and Usage Report reading is turned off for this environment.", null);
        }
        if (account == null || !"AWS".equalsIgnoreCase(account.getProvider())) {
            return Coverage.unavailable("NOT_AWS",
                    "Cost and Usage Reports apply to AWS accounts only.", null);
        }

        AwsBillingSource billingSource = account.resolveAwsBillingSource();
        if (!billingSource.usesCur()) {
            // COST_EXPLORER_ONLY. Terminal, not pending — no CUR will ever exist for
            // this account, so this is not worth logging per query.
            return Coverage.unavailable("NOT_CUR_BACKED",
                    "This account is configured to use Cost Explorer rather than a Cost and Usage Report.",
                    null);
        }

        // Selected directly rather than read off the account: curSource is a LAZY
        // @ManyToOne and the cost getters run on awsTaskExecutor threads with no open
        // session, where touching the association throws LazyInitializationException.
        List<CurSource> sources = curSourceRepository.findActiveByAwsAccountId(account.getAwsAccountId());
        if (sources.isEmpty()) {
            // Flagged as CUR-backed but nothing usable is attached. This is the state
            // that silently reported zero on the GCP side, so it is a warning, not debug.
            logger.warn("[CUR] Account {} is {} but has no ACTIVE cur_source — falling back to Cost Explorer",
                    account.getAwsAccountId(), billingSource);
            return Coverage.unavailable("NO_ACTIVE_SOURCE",
                    "No active Cost and Usage Report is connected to this account yet.", null);
        }

        CurSource source = sources.get(0);
        String earliest = source.getEarliestBillingPeriod();

        if (requiresDaily && !source.supportsDailyGranularity()) {
            logger.debug("[CUR] Source {} is {} granularity; daily query for {} goes to Cost Explorer",
                    source.getId(), source.getTimeUnit(), account.getAwsAccountId());
            return Coverage.unavailable("GRANULARITY",
                    "This report is delivered at " + source.getTimeUnit()
                            + " granularity and cannot produce a day-by-day breakdown.",
                    earliest);
        }

        if (!coversRange(source, start)) {
            logger.debug("[CUR] Source {} starts at {}; range from {} predates it, using Cost Explorer",
                    source.getId(), earliest, start);
            return Coverage.unavailable("RANGE_BEFORE_START",
                    earliest == null || earliest.isBlank()
                            ? "How far back this report goes is not known yet, so it cannot be trusted for this range."
                            : "This report starts at " + earliest + ", which is after the start of the range you picked.",
                    earliest);
        }

        if (isStale(source)) {
            logger.warn("[CUR] Source {} last validated {} — stale beyond {}h, using Cost Explorer for {}",
                    source.getId(), source.getLastValidatedAt(), maxValidationAgeHours,
                    account.getAwsAccountId());

            // Staleness is the one unavailable reason that may be nothing to do with the
            // report. "Not revalidated within the limit" and "no longer being delivered"
            // are the same state here, and only the 01:45 job tells them apart — so an
            // instance that was not running at 01:45 refuses a healthy report until it
            // next happens to be up at that minute, which on a laptop is never.
            //
            // Asking for the check now, rather than returning and leaving it stale, is
            // what stops that becoming permanent. This request still falls back: the
            // answer cannot be waited for on a cost query. The next one reads the report.
            ingestionService.requestRevalidation(source.getId());

            return Coverage.unavailable("STALE",
                    "This report has not been delivered recently, so its figures would be out of date.",
                    earliest);
        }

        return new Coverage(Optional.of(source), "OK", null, earliest,
                source.getTimeUnit() == null ? null : String.valueOf(source.getTimeUnit()));
    }

    /**
     * The outcome of a coverage check: the usable source, or the reason there isn't one.
     *
     * @param source   the report that can answer, empty when none can
     * @param reason   a stable code for callers to branch on ({@code OK} when covered)
     * @param message  a sentence intended to be shown to the user, null when covered
     * @param earliestBillingPeriod  how far back the report goes, when that is known
     * @param timeUnit the report's granularity, when a source was found
     */
    public record Coverage(Optional<CurSource> source, String reason, String message,
                           String earliestBillingPeriod, String timeUnit) {

        public boolean covered() {
            return source.isPresent();
        }

        static Coverage unavailable(String reason, String message, String earliestBillingPeriod) {
            return new Coverage(Optional.empty(), reason, message, earliestBillingPeriod, null);
        }
    }

    /** Convenience overload for queries that do not need day buckets. */
    public Optional<CurSource> resolve(CloudAccount account, LocalDate start, LocalDate end) {
        return resolve(account, start, end, false);
    }

    /**
     * True when the report contains data going back to {@code start}.
     *
     * <p>A legacy CUR begins at the month it was created and never backfills, so a
     * range starting earlier would return a real-looking but short sum rather than
     * an error — the worst possible failure for a cost figure.
     */
    private boolean coversRange(CurSource source, LocalDate start) {
        String earliest = source.getEarliestBillingPeriod();
        if (earliest == null || earliest.isBlank()) {
            // Unknown coverage. Trusting it risks a short sum; the cost of being wrong
            // the other way is one Cost Explorer call.
            return false;
        }
        try {
            return !YearMonth.from(start).isBefore(YearMonth.parse(earliest));
        } catch (Exception e) {
            logger.warn("[CUR] Source {} has unparseable earliest_billing_period '{}'", source.getId(), earliest);
            return false;
        }
    }

    private boolean isStale(CurSource source) {
        if (source.getLastValidatedAt() == null) {
            return true;
        }
        return source.getLastValidatedAt()
                .isBefore(java.time.LocalDateTime.now().minusHours(maxValidationAgeHours));
    }
}
