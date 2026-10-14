import React, { useId, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchChargeTypes, type ChargeTypeAmount, type CostBasis } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { useMoneyIn } from './costCurrency';
import { amortizedZeroedLabels, chargeTypeLabel, type ChargeTypeLabel } from './chargeTypeLabels';

/**
 * Proof that the invoice tree above this adds up — the charge types that make up its total.
 *
 * <p>The card used to assert it in a footnote: *"Includes tax, fees and credits, so this reconciles
 * to your invoice."* True, and unverifiable. A bill that does not match a dashboard is almost
 * always credits, tax or a Savings Plan negation, and nothing in the product could show that split,
 * so the one sentence a reader most wanted to check was the one they had to take on trust.
 *
 * <p>Not a third tab, deliberately. `CostDetailTabs` documents at length why "Charges by resource"
 * being a sibling tab put the figure off-screen from its explanation; a ledger whose whole job is
 * "this column sums to the total above it" must not repeat that. Collapsed by default, and the
 * query is not issued until it is first opened, so a reader who only reads the invoice pays for one
 * Athena scan rather than two.
 */

/** The wording that names the billing source, and the per-code labels it uses. */
export interface ChargeTypeReconciliationCopy {
  /** The disclosure trigger. Defaults to naming the invoice total above it. */
  triggerLabel?: string;
  /** One line under the trigger, once open, saying what the ledger is. */
  explanation?: React.ReactNode;
  /** Why a readable source can still report nothing for a month. */
  emptyHint?: React.ReactNode;
  /**
   * Per-code label overrides, merged over the AWS defaults.
   *
   * <p>A provider that renames two codes keeps the other thirteen, and anything unmapped renders as
   * its own code rather than as a blank row.
   */
  labels?: Record<string, ChargeTypeLabel>;
}

const AWS_COPY: Required<Omit<ChargeTypeReconciliationCopy, 'labels'>> = {
  triggerLabel: 'Reconcile this total by charge type',
  explanation: (
    <>
      Every line on the bill carries a charge type. These are those types, summed — so the figures
      below are the same money as the breakdown above, cut by what kind of charge it was rather than
      by which service incurred it.
    </>
  ),
  emptyHint: <>Nothing was billed in this month, so there is nothing to reconcile.</>,
};

interface ChargeTypeReconciliationProps {
  /** Account, subscription or project, or null to leave the query unissued. */
  accountId: string | null;
  /** Billing month, `YYYY-MM`, owned by the card's own month selector. */
  period: string;
  /** That month written out, for the empty state's sentence. */
  monthLabel: string;
  /** The basis the tree above is on. Part of the query key, so the two cannot disagree. */
  basis: CostBasis;
  /** The invoice tree's own total, which this ledger must reconcile to. */
  invoiceTotal: number;
  /**
   * Where the ledger reads from. Defaults to the AWS report; `null` renders nothing.
   *
   * <p>Defaulted like its siblings so `fetcher(a, p, basis)` is a call on a function rather than on
   * `fn | null | undefined`, and so `null` and the prop omitted cannot mean two different things.
   */
  fetcher?:
    | ((accountId: string, period?: string, basis?: CostBasis) => Promise<ChargeTypeAmount[] | null>)
    | null;
  /** Cache-key discriminator. The parent always passes one; see the default. */
  insightName?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  copy?: ChargeTypeReconciliationCopy;
}

const ChargeTypeReconciliation: React.FC<ChargeTypeReconciliationProps> = ({
  accountId,
  period,
  monthLabel,
  basis,
  invoiceTotal,
  fetcher = fetchChargeTypes,
  // The parent derives this from its own insightName, which already carries the provider prefix.
  // This default exists so the component stands alone in a test; it is always overridden in the app.
  insightName = 'charge-types',
  currency = 'USD',
  copy,
}) => {
  const text = { ...AWS_COPY, ...copy };
  const money = useMoneyIn(currency);
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();

  const read = fetcher ?? null;

  /*
   * The basis is in the key.
   *
   * `useCurInsight` holds an answer for 30 minutes, so without this an open ledger switched to the
   * amortized basis would re-render the cached *unblended* rows under the amortized heading — the
   * mislabelling the backend's 422 exists to prevent, arriving by cache instead. No suffix on the
   * default basis, the same rule the charges query uses, so the two cannot diverge.
   */
  const ledgerKey = basis === 'unblended' ? insightName : `${insightName}:${basis}`;
  const { data, isPending, isError } = useCurInsight(
    ledgerKey,
    // Null until first expanded, which is how a collapsed disclosure costs nothing. The pattern
    // ServiceResourcesDrawer uses while closed.
    expanded && read ? accountId : null,
    period,
    (a, p) => (read ? read(a, p, basis) : Promise.resolve(null)),
  );
  const rows: ChargeTypeAmount[] | null = isError ? null : (data ?? null);

  /*
   * In flight, which is not the same fact as failed.
   *
   * react-query reports `data: undefined` for the whole fetch, and the query does not start until
   * the first expand — so flattening that into `rows === null` means the first thing a click
   * produces is "could not be read", for as long as the Athena scan takes, withdrawn on arrival.
   *
   * Mirrors the query's own enabled condition rather than using `isPending` alone: a disabled query
   * is pending forever, so with a null accountId this would be a sentence that never resolves.
   */
  const loading = expanded && Boolean(read) && accountId !== null && isPending;

  const groups = useMemo(() => {
    const present = (rows ?? []).filter((r) => Math.abs(r.cost) >= 0.005);
    const byMagnitude = (a: ChargeTypeAmount, b: ChargeTypeAmount) =>
      Math.abs(b.cost) - Math.abs(a.cost);
    return {
      // Grouped by the sign of the amount rather than by a static per-code sign: a reversal can
      // flip one, and a hard-coded sign would file a positive Credit under subtractions and get
      // the arithmetic backwards.
      charges: present.filter((r) => r.cost > 0).sort(byMagnitude),
      credits: present.filter((r) => r.cost < 0).sort(byMagnitude),
      // No Math.abs: a credit-only month has a negative total and must render as one.
      total: present.reduce((sum, r) => sum + r.cost, 0),
    };
  }, [rows]);

  if (!read) return null;

  const difference = groups.total - invoiceTotal;

  /*
   * How close the two totals have to be to count as reconciled.
   *
   * <p>They cannot be identical, and a one-cent tolerance made that a reported fault. The invoice
   * tree drops every group whose cost rounds past a tenth of a cent (`HAVING ABS(SUM(...)) > 0.001`
   * in `getChargesByService`, which is there so a bill does not list hundreds of $0.00 rows). This
   * ledger groups by charge type alone, so those same fractions survive inside their type's total.
   * The ledger is therefore slightly *more* complete than the figure it is checked against, and the
   * gap grows with the number of sub-cent groups the account happens to have.
   *
   * <p>Measured on a live report — account 605134457560, October 2026 — the ledger totalled
   * $3,367.5452 against the tree's $3,367.5188: a 2.6 cent gap on a month that reconciles perfectly
   * well. At a flat cent that rendered "the ledger and the tree disagree" on a healthy account,
   * which is worse than saying nothing: it teaches the reader to distrust a figure that is right.
   *
   * <p>Relative, with an absolute floor, because the error scales with the bill rather than being
   * fixed, and because the failures this check exists to catch are nothing like this size — a
   * charge type missing from the ledger or a sign read backwards moves the total by dollars or by
   * whole percentages, not by thousandths of one.
   *
   * <p>Five parts in a hundred thousand, which is six times the gap measured above and still tight
   * enough to fail on a dollar: on that same month it allows 17 cents, and on a $17,000 month it
   * allows 87. Deliberately not looser — a tolerance generous enough to hide a real discrepancy
   * would make this whole section decorative.
   */
  const tolerance = Math.max(0.01, Math.abs(invoiceTotal) * 0.00005);
  const matches = Math.abs(difference) <= tolerance;

  return (
    <div className="mt-4 pt-3 border-t border-[rgba(0,0,0,0.08)]">
      <button
        type="button"
        onClick={() => setExpanded((open) => !open)}
        aria-expanded={expanded}
        aria-controls={panelId}
        className="no-print inline-flex items-center gap-1.5 text-[11.5px] font-medium text-[#4285F4] hover:underline decoration-[rgba(66,133,244,0.4)] underline-offset-2"
      >
        <Icon icon={expanded ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={14} />
        {text.triggerLabel}
      </button>

      <div id={panelId} hidden={!expanded}>
        <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-2 leading-relaxed max-w-[640px]">
          {text.explanation}
        </p>

        {/* Said in the same shape as the failure below it, so the two cannot be confused — and said
            at all, because the scan takes seconds and a silent panel reads as a dead control. */}
        {loading && (
          <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-3">
            Reading the charge type split for {monthLabel}…
          </p>
        )}

        {/* A failed read is "cannot answer", which must not be dressed up as "nothing was billed" —
            nor announced while the answer is still on its way. */}
        {rows === null && expanded && !loading && (
          <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-3">
            The charge type split could not be read for {monthLabel}.
          </p>
        )}

        {rows !== null && groups.charges.length === 0 && groups.credits.length === 0 && (
          <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-3 leading-relaxed">
            {text.emptyHint}
          </p>
        )}

        {rows !== null && (groups.charges.length > 0 || groups.credits.length > 0) && (
          <>
            {basis === 'amortized' && (
              <div className="flex items-start gap-2.5 px-3.5 py-3 mt-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
                <Icon
                  icon="mdi:information-outline"
                  width={15}
                  className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
                />
                <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
                  {/* Templated over the shared tuple, so a fifth zeroed type needs no copy edit and
                      this sentence and the tree's cannot describe different sets. */}
                  {amortizedZeroedLabels(copy?.labels).join(', ')} are not listed on the amortized
                  basis. Their cost is already inside Reserved usage and Savings Plan usage, priced
                  at the rate the commitment actually bought — listing both would count the same
                  commitment twice.
                </div>
              </div>
            )}

            <table className="w-full text-[12.5px] mt-3">
              <caption className="sr-only">
                Charge types making up the {monthLabel} total, with credits and adjustments listed
                separately.
              </caption>
              {groups.charges.length > 0 && (
                <ChargeTypeGroup
                  heading="Charges"
                  rows={groups.charges}
                  money={money}
                  labels={copy?.labels}
                />
              )}
              {groups.credits.length > 0 && (
                <ChargeTypeGroup
                  heading="Credits and adjustments"
                  rows={groups.credits}
                  money={money}
                  labels={copy?.labels}
                />
              )}
              {/* Its own body, so neither sign's rowgroup heading claims the total as one of its
                  rows — the figure is the sum of both groups, not a member of either. */}
              <tbody>
                <tr className="border-t-2 border-[rgba(0,0,0,0.12)]">
                  <th scope="row" className="px-2 py-2 text-left font-bold text-black">
                    Total
                  </th>
                  <td className="px-2 py-2 text-right font-bold text-black tabular-nums whitespace-nowrap">
                    {money(groups.total)}
                  </td>
                </tr>
              </tbody>
            </table>

            <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-2 leading-relaxed">
              {matches ? (
                <>Matches the {money(invoiceTotal)} above.</>
              ) : (
                <>
                  {/* The mechanism is known exactly, so it is named rather than described as a
                      mystery: the tree groups by service, region, description, usage type and unit
                      and drops any group netting to under a tenth of a cent, while this query drops
                      nothing. */}
                  {/* The preposition is branched on the sign and the magnitude formatted: the
                      dropped groups can net negative, and "-$1.25 more than" is not a sentence. */}
                  {money(Math.abs(difference))} {difference > 0 ? 'more' : 'less'} than the{' '}
                  {money(invoiceTotal)} above. The breakdown groups charges by service, region,
                  description and usage type and drops any group netting to less than a tenth of a
                  cent; this ledger drops nothing, so the difference is those sub-cent groups.
                </>
              )}
            </p>
          </>
        )}
      </div>
    </div>
  );
};

/**
 * One sign's worth of rows, under its own heading.
 *
 * <p>The subtract group's heading is the same weight as the add group's and nothing here is red: a
 * credit is good news, and colour-coding it as a warning would say otherwise. The sign and the
 * heading carry the meaning, so nothing depends on colour alone.
 *
 * <p>Each sign owns a `tbody` and its heading is `scope="rowgroup"`: the heading heads a group of
 * rows, not a group of columns, and `colgroup` would associate it along the wrong axis — a screen
 * reader would announce "Tax" under a column heading of "Credits and adjustments".
 */
const ChargeTypeGroup: React.FC<{
  heading: string;
  rows: ChargeTypeAmount[];
  money: (v: number) => string;
  labels?: Record<string, ChargeTypeLabel>;
}> = ({ heading, rows, money, labels }) => (
  <tbody>
    <tr>
      <th
        scope="rowgroup"
        colSpan={2}
        className="px-2 pt-3 pb-1 text-left text-[10.5px] uppercase tracking-wide font-medium text-[rgba(0,0,0,0.4)]"
      >
        {heading}
      </th>
    </tr>
    {rows.map((r) => {
      const label = chargeTypeLabel(r.chargeType, labels);
      return (
        <tr key={r.chargeType} className="border-t border-[rgba(0,0,0,0.12)]">
          <th scope="row" className="px-2 py-1.5 text-left font-normal">
            <span className="text-[rgba(0,0,0,0.8)] font-medium">{label.label}</span>
            {label.detail && (
              <span className="block text-[10.5px] text-[rgba(0,0,0,0.4)] leading-relaxed">
                {label.detail}
              </span>
            )}
          </th>
          <td className="px-2 py-1.5 text-right text-[rgba(0,0,0,0.8)] tabular-nums whitespace-nowrap align-top">
            {money(r.cost)}
          </td>
        </tr>
      );
    })}
  </tbody>
);

export default ChargeTypeReconciliation;
