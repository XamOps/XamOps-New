import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  fetchPriceVsVolume,
  fetchReportCapabilities,
  type PriceVolumeChange,
  type PriceVolumeCoverageGap,
} from '../../api/curInsightsApi';
import { useMoneyIn } from './costCurrency';
import { useCurInsight } from '../../hooks/useCurInsight';

/** The fetcher may now answer with a coverage gap, which the AWS default returns and the card renders. */
type PriceVsVolumeResult = PriceVolumeChange[] | PriceVolumeCoverageGap | null;

/** True when a fetch result is the readable-but-out-of-coverage case rather than rows or null. */
const isCoverageGap = (
  result: PriceVsVolumeResult | undefined,
): result is PriceVolumeCoverageGap =>
  !!result && !Array.isArray(result) && result.unavailable === 'coverage';

/**
 * Explains *why* spend changed, by splitting each service's movement into the part caused
 * by using more or less, and the part caused by paying a different rate.
 *
 * Only a Cost and Usage Report can answer this — it needs usage quantity sitting beside
 * cost on every line, which the Cost Explorer API does not expose in a comparable form.
 *
 * <h3>Why it compares two CLOSED months</h3>
 * The obvious default — this month against last — compares a partial month with a
 * complete one, so every service looks like it collapsed. That is not a smaller bill, it
 * is fewer days, and no amount of labelling stops people reading it as a real drop. Two
 * finished months is the only comparison that means what it appears to mean.
 */

interface PriceVsVolumeSectionProps {
  /** Single account, project or subscription with a usable billing source, or null to render nothing. */
  accountId: string | null;
  /**
   * Where the comparison comes from. Defaults to the AWS Cost and Usage Report; GCP and Azure pass
   * their own fetchers, which return the identical payload shape.
   */
  fetcher?: (accountId: string, period?: string) => Promise<PriceVsVolumeResult>;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName?: string;
  /**
   * ISO currency code the billing source reports in. Defaults to USD, which every AWS report uses; a
   * GCP billing account or an Azure subscription can be set to anything.
   */
  currency?: string;
  /**
   * Replaces this section's own `h3`.
   *
   * <p>Used to hoist the sub-tab control into the card header when this renders as a tab of
   * {@code CostDetailTabs}, so the active tab label reads as the title. Its presence also tells this
   * component it is inside that card, which changes how it handles having nothing to show — see below.
   */
  heading?: React.ReactNode;
}

/**
 * Below this a change is noise — rounding on a service costing a few cents, not something
 * worth a row. Without it the table fills with $0.00 movements and the real ones are lost.
 *
 * <p>Deliberately one unit of whatever the account is billed in rather than one dollar converted.
 * The point is "too small to bother with", and on an INR-billed subscription ₹1 is exactly as
 * ignorable as $1 is on a dollar one — a converted threshold would hide eighty rupees of real
 * movement or show a cent of noise, depending which way it was applied.
 */
const MIN_CHANGE = 1;

/** How many rows before it stops being a summary. */
const MAX_ROWS = 8;

/** How many comparisons the selector offers. Matches the other sections' 12-month window. */
const MONTHS_OFFERED = 12;

/**
 * A usage quantity, at a readable precision.
 *
 * <p>Quantities span a huge range here — 2,075 hours, 0.96 GB-months, 147 million requests — so a
 * fixed number of decimals is unreadable at one end and rounds to zero at the other.
 */
const quantity = (v: number): string => {
  if (v === 0) return '0';
  const abs = Math.abs(v);
  if (abs >= 1000) return v.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (abs >= 1) return v.toLocaleString('en-US', { maximumFractionDigits: 3 });
  return v.toLocaleString('en-US', { maximumFractionDigits: 6 });
};

/** One selectable comparison: a month and the one before it. */
interface Comparison {
  /** `YYYY-MM` of the later month, which is what the endpoint takes. */
  value: string;
  /** "August 2026 vs July 2026" — both named, so the pair is never ambiguous. */
  label: string;
  /** Month names for the subheading. */
  current: string;
  prior: string;
}

/**
 * The last twelve complete months, each paired with its predecessor, newest first.
 *
 * <p>Complete months only. The current month is deliberately absent: comparing a part-finished month
 * against a finished one makes every service look like it collapsed, which is not a smaller bill but
 * fewer days, and no amount of labelling stops people reading it as a real drop. Offering it in a
 * dropdown would be offering a reading that is wrong by construction.
 */
const comparisonOptions = (earliest?: string): Comparison[] => {
  const out: Comparison[] = [];
  const later = new Date();
  later.setDate(1);
  // Start at last month, not this one.
  later.setMonth(later.getMonth() - 1);

  // The first month the report covers, as a `YYYY-MM` string comparison. A pair is answerable only
  // when its EARLIER month is at or after this — the endpoint needs both months inside coverage, and
  // the earlier one is the binding end. When the report's start is unknown (no AWS capabilities, or
  // a report whose coverage has not been established) every pair is offered and the backend's 422
  // explains any that turn out to be out of range.
  const earliestKey = earliest && /^\d{4}-\d{2}$/.test(earliest) ? earliest : null;

  for (let i = 0; i < MONTHS_OFFERED; i++) {
    const earlier = new Date(later);
    earlier.setMonth(earlier.getMonth() - 1);
    const earlierKey = `${earlier.getFullYear()}-${String(earlier.getMonth() + 1).padStart(2, '0')}`;
    const monthYear = (d: Date) =>
      d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    if (!earliestKey || earlierKey >= earliestKey) {
      out.push({
        value: `${later.getFullYear()}-${String(later.getMonth() + 1).padStart(2, '0')}`,
        label: `${monthYear(later)} vs ${monthYear(earlier)}`,
        current: later.toLocaleDateString('en-US', { month: 'long' }),
        prior: earlier.toLocaleDateString('en-US', { month: 'long' }),
      });
    }
    later.setMonth(later.getMonth() - 1);
  }
  // A report whose start is newer than every earlier-month in the window leaves nothing answerable.
  // Keep the newest pair anyway so the card has a valid selection to render against — the backend
  // 422s it and the coverage banner explains why, rather than the component crashing on an empty
  // selector. Only reachable when the report begins within the last month or two.
  if (out.length === 0) {
    const newer = new Date();
    newer.setDate(1);
    newer.setMonth(newer.getMonth() - 1);
    const older = new Date(newer);
    older.setMonth(older.getMonth() - 1);
    const monthYear = (d: Date) =>
      d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
    out.push({
      value: `${newer.getFullYear()}-${String(newer.getMonth() + 1).padStart(2, '0')}`,
      label: `${monthYear(newer)} vs ${monthYear(older)}`,
      current: newer.toLocaleDateString('en-US', { month: 'long' }),
      prior: older.toLocaleDateString('en-US', { month: 'long' }),
    });
  }
  return out;
};

const PriceVsVolumeSection: React.FC<PriceVsVolumeSectionProps> = ({
  accountId,
  fetcher = fetchPriceVsVolume,
  insightName = 'price-vs-volume',
  currency = 'USD',
  heading,
}) => {
  const format = useMoneyIn(currency);
  // Signed, with the sign outside the amount: a change reads as "+₹1,240" / "-₹1,240", and the
  // Intl-placed minus inside the symbol ("₹-1,240") is harder to scan in a column.
  const money = (v: number): string => (v < 0 ? '-' : '') + format(Math.abs(v));

  /*
   * Which two months to compare.
   *
   * The pair used to be fixed at the last two complete months, computed once and unchangeable — the
   * only section on the page without a period control, while its endpoint has always taken one. So
   * "did that rate rise happen in August or has it been running since June" was unanswerable here,
   * which is the obvious next question after reading a single month's split.
   */
  /*
   * How far back the report reaches, when it can be known.
   *
   * Fetched from the AWS /capabilities endpoint, which GCP and Azure do not have — so it is asked
   * for ONLY on the default AWS fetcher. When a caller passes its own fetcher (GCP/Azure), the
   * capabilities probe is skipped and every pair is offered, because there is no equivalent endpoint
   * to narrow them and the backend still refuses any genuinely out-of-range pair.
   */
  const isAwsDefault = fetcher === fetchPriceVsVolume;
  const [earliest, setEarliest] = useState<string | undefined>(undefined);
  useEffect(() => {
    if (!accountId || !isAwsDefault) {
      setEarliest(undefined);
      return;
    }
    let cancelled = false;
    void fetchReportCapabilities(accountId).then((caps) => {
      if (!cancelled) setEarliest(caps?.earliestBillingPeriod);
    });
    return () => {
      cancelled = true;
    };
  }, [accountId, isAwsDefault]);

  // Only the month-pairs the report can answer, newest first; all twelve when the start is unknown.
  const comparisons = useMemo(() => comparisonOptions(earliest), [earliest]);
  const [period, setPeriod] = useState(comparisons[0]?.value ?? '');
  // Keep the selection valid as the offered pairs change (e.g. once earliest arrives): fall back to
  // the newest covered pair when the current choice is no longer offered.
  useEffect(() => {
    if (comparisons.length > 0 && !comparisons.some((c) => c.value === period)) {
      setPeriod(comparisons[0].value);
    }
  }, [comparisons, period]);
  const selected = comparisons.find((c) => c.value === period) ?? comparisons[0];

  /**
   * Services whose working is open.
   *
   * <p>The two effect columns are derived figures, and until now the table showed only the results —
   * so a reader had no way to see where "+₹530 rate change" came from, or to check it. The four raw
   * numbers behind every row (both months' cost and both months' quantity) are already in the payload
   * and were being discarded. Expanding a row shows them and the arithmetic, so the split is
   * verifiable rather than something the page asserts.
   */
  const [openRows, setOpenRows] = useState<Set<string>>(new Set());
  const toggleRow = (service: string) =>
    setOpenRows((prev) => {
      const next = new Set(prev);
      if (next.has(service)) next.delete(service);
      else next.add(service);
      return next;
    });

  // Cached across mounts and per period, so switching back to a month already viewed does not
  // re-scan the billing source.
  const { data, isPending, isError } = useCurInsight(
    insightName,
    accountId,
    period,
    fetcher,
  );
  const loading = Boolean(accountId) && isPending;
  // The report is readable but does not reach the earlier month of this pair. A distinct case from
  // both rows and null: the card stays and explains the real limit, rather than hiding or blaming a
  // missing usage quantity. Can still happen on an answerable-looking pair when the report's start
  // is unknown and the backend refuses it.
  const coverageGap = isError ? null : isCoverageGap(data) ? data : null;
  // Movements under a dollar are rounding on a service costing pennies, not a story.
  const rows = useMemo(
    () =>
      isError || !data || isCoverageGap(data)
        ? null
        : data.filter((r) => Math.abs(r.costChange) >= MIN_CHANGE).slice(0, MAX_ROWS),
    [data, isError],
  );

  if (!accountId || loading) return null;
  /*
   * Null means the billing source cannot answer this at all — no report, no readable export, a scope
   * that will not aggregate usage quantity. No month will ever work, so as a standalone section this
   * hides entirely.
   *
   * As a *tab*, it must not: the tab control lives in this panel's heading, so returning null would
   * delete the control that got the reader here and strand them on a blank card. Exactly the mistake
   * CostDetailTabs documents having made once already. When a heading is supplied it stays and explains
   * itself instead.
   *
   * An empty list is a third case — the source answered and nothing moved by more than a unit — and is
   * always kept on screen, because the month selector is the thing the reader needs next.
   */
  // A coverage gap keeps the card even as a standalone section: unlike the true "cannot answer this
  // at all" null, a different month-pair would work, and the selector that reaches one is the whole
  // point of staying on screen.
  if (!rows && !coverageGap && !heading) return null;

  const totalChange = (rows ?? []).reduce((sum, r) => sum + r.costChange, 0);

  // "September 2026" from "2026-09", for the coverage sentence; the raw string if it will not parse.
  const earliestLabel = (ym?: string): string => {
    if (!ym || !/^\d{4}-\d{2}$/.test(ym)) return ym ?? '';
    const [y, m] = ym.split('-').map(Number);
    return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  };
  const coverageSentence = coverageGap
    ? coverageGap.earliest
      ? `This report starts at ${earliestLabel(coverageGap.earliest)}, so it can't compare ${selected.prior}. Pick a later pair the report covers.`
      : coverageGap.detail ||
        'This report does not reach back far enough to compare those two months. Pick a later pair.'
    : null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex items-start justify-between gap-4 mb-1 flex-wrap">
        <div>
          {heading ?? <h3 className="text-[15px] font-bold text-black">Why did spend change?</h3>}
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            {selected.current} vs {selected.prior} — whether each service cost more because it
            was <span className="font-medium">used more</span>, or because the{' '}
            <span className="font-medium">rate changed</span>.
          </p>
        </div>
        <div className="flex items-center gap-4 flex-shrink-0">
          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            aria-label="Months to compare"
            className="px-3 py-1.5 text-[12.5px] border border-[rgba(0,0,0,0.15)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5"
          >
            {comparisons.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>

          <div className="text-right">
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Net change</p>
            <p
              className={`text-[18px] font-bold ${
                totalChange >= 0 ? 'text-[#DC2626]' : 'text-[#158045]'
              }`}
            >
              {totalChange >= 0 ? '+' : ''}
              {money(totalChange)}
            </p>
          </div>
        </div>
      </div>

      {/* The report is readable but does not reach the earlier month of this pair. Names the real
          limit and points at the selector, rather than the generic "usage quantity" banner below —
          which would be a claim about the billing source when the truth is the report's age. */}
      {coverageGap && (
        <div className="mt-4 flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            {coverageSentence}
          </div>
        </div>
      )}

      {/* The billing source cannot answer this at all — no report, no readable export, a scope that
          will not aggregate usage quantity. Distinct from the coverage case above: no month would
          work. Only reachable as a tab — see above. */}
      {!rows && !coverageGap && (
        <div className="mt-4 flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            This comparison is not available for this account. It needs usage quantity alongside cost on
            every billed line, which not every billing source reports — without it the split would
            attribute every movement to the rate, which would describe the query rather than the bill.
          </div>
        </div>
      )}

      {/* The source answered and nothing moved. Said rather than hidden, so the selector above stays
          reachable — this is the one case where a reader wants to try a different month. */}
      {rows && rows.length === 0 && (
        <div className="mt-4 flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            No service moved by more than {format(MIN_CHANGE)} between {selected.prior} and{' '}
            {selected.current}. Either spend was flat, or this billing source does not reach back to
            both months — a report or export only covers months from when it was created onward.
          </div>
        </div>
      )}

      {rows && rows.length > 0 && (
      <div className="overflow-x-auto -mx-2 mt-4">
        <table className="w-full min-w-[560px] text-[12.5px]">
          <thead>
            <tr className="text-left text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)]">
              <th className="font-medium px-2 pb-2">Service</th>
              {/* Says the rows are expandable. The chevron alone is easy to miss on a table that
                  did not used to have one. */}
              <th className="font-medium px-2 pb-2 text-right">Change</th>
              <th className="font-medium px-2 pb-2 text-right">Used more / less</th>
              <th className="font-medium px-2 pb-2 text-right">Rate change</th>
              <th className="font-medium px-2 pb-2">Mainly because</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              // Whichever effect is larger is the story; the other is a footnote. Saying
              // "mainly more usage" is what a reader actually takes away, rather than
              // leaving them to compare two signed numbers themselves.
              const usageDriven = Math.abs(r.volumeEffect) >= Math.abs(r.priceEffect);
              const reason = usageDriven
                ? r.volumeEffect >= 0
                  ? 'Used more'
                  : 'Used less'
                : r.priceEffect >= 0
                  ? 'Higher rate'
                  : 'Lower rate';

              const open = openRows.has(r.service);
              // A service with no usage in the earlier month is priced off its own rate, so the
              // whole change is usage. Worth naming in the working, because "used more" on a service
              // that did not exist is otherwise a confusing way to say "this is new".
              const isNew = r.priorQuantity === 0 && r.priorCost === 0;
              const priorRate = r.priorQuantity > 0 ? r.priorCost / r.priorQuantity : 0;
              const currentRate = r.currentQuantity > 0 ? r.currentCost / r.currentQuantity : 0;

              return (
                <React.Fragment key={r.service}>
                <tr
                  className="border-t border-[rgba(0,0,0,0.06)] cursor-pointer hover:bg-[rgba(0,0,0,0.015)] transition-colors"
                  onClick={() => toggleRow(r.service)}
                  title="Show how this was calculated"
                >
                  <td className="px-2 py-2.5 font-medium text-black">
                    <span className="inline-flex items-center gap-1.5">
                      <Icon
                        icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                        width={14}
                        className="text-[rgba(0,0,0,0.35)] flex-shrink-0"
                      />
                      {r.service}
                    </span>
                  </td>
                  <td
                    className={`px-2 py-2.5 text-right font-semibold ${
                      r.costChange >= 0 ? 'text-[#DC2626]' : 'text-[#158045]'
                    }`}
                  >
                    {r.costChange >= 0 ? '+' : ''}
                    {money(r.costChange)}
                  </td>
                  <td className="px-2 py-2.5 text-right text-[rgba(0,0,0,0.6)]">
                    {r.volumeEffect >= 0 ? '+' : ''}
                    {money(r.volumeEffect)}
                  </td>
                  <td className="px-2 py-2.5 text-right text-[rgba(0,0,0,0.6)]">
                    {r.priceEffect >= 0 ? '+' : ''}
                    {money(r.priceEffect)}
                  </td>
                  <td className="px-2 py-2.5">
                    <span
                      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium ${
                        usageDriven
                          ? 'bg-[#4285F414] text-[#1A56C4]'
                          : 'bg-[#F4A42914] text-[#9A6800]'
                      }`}
                    >
                      <Icon icon={usageDriven ? 'mdi:chart-bar' : 'mdi:tag-outline'} width={12} />
                      {reason}
                    </span>
                  </td>
                </tr>

                {open && (
                  <tr className="bg-[rgba(0,0,0,0.015)]">
                    <td colSpan={5} className="px-2 pb-4 pt-1">
                      <div className="ml-5 grid grid-cols-1 lg:grid-cols-2 gap-x-8 gap-y-3">

                        {/* What was billed, both months — the numbers everything below comes from. */}
                        <div>
                          <p className="text-[10.5px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium mb-1.5">
                            What was billed
                          </p>
                          <table className="text-[11.5px] w-full">
                            <tbody>
                              <tr className="text-[rgba(0,0,0,0.4)]">
                                <td className="pr-3"></td>
                                <td className="pr-3 text-right font-medium">Cost</td>
                                <td className="pr-3 text-right font-medium">Usage</td>
                                <td className="text-right font-medium">Per unit</td>
                              </tr>
                              <tr>
                                <td className="pr-3 py-0.5 text-[rgba(0,0,0,0.6)]">{selected.prior}</td>
                                <td className="pr-3 py-0.5 text-right tabular-nums">{format(r.priorCost)}</td>
                                <td className="pr-3 py-0.5 text-right tabular-nums">{quantity(r.priorQuantity)}</td>
                                <td className="py-0.5 text-right tabular-nums">
                                  {r.priorQuantity > 0 ? format(priorRate) : '—'}
                                </td>
                              </tr>
                              <tr>
                                <td className="pr-3 py-0.5 text-[rgba(0,0,0,0.6)]">{selected.current}</td>
                                <td className="pr-3 py-0.5 text-right tabular-nums">{format(r.currentCost)}</td>
                                <td className="pr-3 py-0.5 text-right tabular-nums">{quantity(r.currentQuantity)}</td>
                                <td className="py-0.5 text-right tabular-nums">
                                  {r.currentQuantity > 0 ? format(currentRate) : '—'}
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        </div>

                        {/* The arithmetic, written out with this row's own numbers. */}
                        <div>
                          <p className="text-[10.5px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium mb-1.5">
                            How the split is worked out
                          </p>
                          {isNew ? (
                            <p className="text-[11.5px] text-[rgba(0,0,0,0.6)] leading-relaxed">
                              Nothing was billed for this service in {selected.prior}, so there is no
                              earlier rate to compare against and the whole{' '}
                              <span className="font-medium">{money(r.costChange)}</span> counts as
                              usage.
                            </p>
                          ) : (
                            <div className="text-[11.5px] text-[rgba(0,0,0,0.6)] space-y-1 leading-relaxed">
                              <p>
                                <span className="font-medium text-black">Used more / less</span> ={' '}
                                usage change × {selected.prior}&apos;s rate
                                <br />
                                <span className="tabular-nums">
                                  ({quantity(r.currentQuantity)} − {quantity(r.priorQuantity)}) ×{' '}
                                  {format(priorRate)} = {money(r.volumeEffect)}
                                </span>
                              </p>
                              <p>
                                <span className="font-medium text-black">Rate change</span> = whatever
                                the total change leaves after that
                                <br />
                                <span className="tabular-nums">
                                  {money(r.costChange)} − ({money(r.volumeEffect)}) ={' '}
                                  {money(r.priceEffect)}
                                </span>
                              </p>
                            </div>
                          )}
                        </div>

                        {/* Stated on the row it affects, not only in the footnote. A service billed in
                            one unit has a real per-unit price; a broad one does not. */}
                        <p className="lg:col-span-2 text-[10.5px] text-[rgba(0,0,0,0.38)] leading-relaxed">
                          &quot;Per unit&quot; is this service&apos;s cost divided by its total usage,
                          not a price from a rate card. Where a service bills in several units at once
                          — gigabytes, hours and operations together — that average is a blended figure,
                          so read the split as a direction rather than an exact amount.
                        </p>
                      </div>
                    </td>
                  </tr>
                )}
                </React.Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      )}

      {rows && rows.length > 0 && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
          The two middle columns add up to the change. <span className="font-medium">Click a row</span>{' '}
          to see both months&apos; cost and usage and the arithmetic behind its split. A service that
          only appeared in {selected.current} counts entirely as usage, since it had no earlier rate to
          compare against. Only complete months are offered — comparing a part-finished month against a
          finished one makes every service look like it collapsed.
        </p>
      )}
    </div>
  );
};

export default PriceVsVolumeSection;
