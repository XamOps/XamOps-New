import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchDataTransferCosts, type DataTransferBreakdown } from '../../api/curInsightsApi';
import { CostCurrencyContext, useMoneyIn } from './costCurrency';
import { useCurInsight } from '../../hooks/useCurInsight';
import { buildGroups, type Group } from './dataTransferModel';
import { CATEGORIES, fallbackMeta, quantity, RouteLabel } from './dataTransferView';
import DataTransferCategoryPanel from './DataTransferCategoryPanel';

/**
 * Network spend, grouped by what drives it, then down to the resource and the route together.
 *
 * <p>This is normally the largest cost nobody can see. A service breakdown files NAT
 * gateway charges under EC2 and cross-AZ traffic under whichever service moved the bytes,
 * so the network total never appears anywhere as one number — it is scattered across every
 * other line of the bill. Only a Cost and Usage Report can reassemble it, because only the
 * report carries {@code line_item_usage_type} on every row.
 *
 * <p>A category total alone still names no decision. "Inter-region: $2.44" becomes
 * actionable only as "this bucket, Mumbai → N. Virginia, 29 GB, $2.44" — one row carrying
 * the resource, the route and the price. The panel opens on exactly that story, Routes
 * first, so the region → region question the user actually has is the first thing answered.
 *
 * <p>The limit is worth stating plainly, because the obvious next question runs into it: a
 * billing line names one resource, the one being charged, so the far end of a flow is a
 * region or the internet and never a peer resource id. Naming both ends needs VPC Flow
 * Logs. The section says so rather than leaving a blank that reads as a bug.
 */

/**
 * The wording that differs between providers.
 *
 * <p>One object rather than six props, and overridable rather than branched on a provider enum:
 * the arithmetic and the layout are identical for AWS and GCP — a GCP zone *is* an availability
 * zone and Cloud NAT *is* a NAT gateway — so only the sentences change. Branching inside the
 * component would invite the two providers' behaviour to drift apart, which is the thing sharing
 * it is meant to prevent.
 */
export interface DataTransferCopy {
  heading?: string;
  subheading?: string;
  footnote?: string;
  /** What an empty *past* month might mean, which differs by billing source. */
  emptyHint?: string;
  /**
   * What an empty month-in-progress means — a different reason, so a different sentence.
   *
   * <p>{@code emptyHint} explains missing coverage. An open month is covered; its data has just
   * not arrived yet, and saying "the report does not backfill" there describes a problem the
   * reader does not have.
   */
  currentMonthHint?: string;
  /** Replaces the explanation under a category, where the fix differs by provider. */
  categoryBlurbs?: Record<string, string>;
  /** Why per-resource attribution is unavailable, which is a different setting on each provider. */
  noResourceDetail?: React.ReactNode;
}

interface DataTransferSectionProps {
  /** Single account or project with a usable billing source, or null to render nothing. */
  accountId: string | null;
  /**
   * Where the breakdown comes from. Defaults to the AWS Cost and Usage Report; GCP passes its
   * BigQuery billing-export fetcher, which returns the identical payload shape.
   */
  fetcher?: (accountId: string, period?: string) => Promise<DataTransferBreakdown | null>;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName?: string;
  copy?: DataTransferCopy;
  /**
   * ISO currency code the billing source reports in. Defaults to USD, which every AWS report
   * uses; a GCP billing account can be set to anything.
   */
  currency?: string;
}

/** The last 12 months, newest first. Matches the Charges by service selector. */
const monthOptions = (): { value: string; label: string }[] => {
  const out: { value: string; label: string }[] = [];
  const d = new Date();
  d.setDate(1);
  for (let i = 0; i < 12; i++) {
    out.push({
      value: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`,
      label: d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' }),
    });
    d.setMonth(d.getMonth() - 1);
  }
  return out;
};

const DataTransferSection: React.FC<DataTransferSectionProps> = ({
  accountId,
  fetcher = fetchDataTransferCosts,
  insightName = 'data-transfer',
  copy = {},
  currency = 'USD',
}) => {
  /*
   * `useMoneyIn(currency)`, not `useMoney()`.
   *
   * This component provides CostCurrencyContext further down its own return value, and a component
   * cannot consume a context it provides there — the hook reads whatever was above it, which is the
   * 'USD' default. So the category totals and the header total this component renders itself were
   * formatted in dollars while the drill-down rows beneath them, which are separate components inside
   * the provider, were formatted correctly. Invisible on AWS, where the default happens to be right;
   * on a rupee-billed GCP project it put "$0.00" beside "₹2,397.87" on the same screen.
   *
   * The provider stays for the child components, which do legitimately read it — including the
   * category panel, which renders inside the provider and so uses useMoney().
   */
  const money = useMoneyIn(currency);
  // The single open category, or null when the panel is closed. Opening another category replaces
  // it — only one panel is ever open, keyed by category.
  const [openCategory, setOpenCategory] = useState<string | null>(null);

  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);
  const monthLabel = months.find((m) => m.value === period)?.label ?? 'this period';
  const isCurrentMonth = period === months[0].value;

  // Cached across mounts, so returning to this page does not re-scan the billing source.
  const { data, isPending, isError } = useCurInsight(insightName, accountId, period, fetcher);
  const breakdown = isError ? null : (data ?? null);
  // Only the first load blocks; a background revalidation keeps the rows on screen.
  const loading = Boolean(accountId) && isPending;

  const groups = useMemo<Group[]>(
    () => (breakdown ? buildGroups(breakdown.charges, breakdown.flows) : []),
    [breakdown],
  );

  const total = groups.reduce((sum, g) => sum + g.cost, 0);

  if (!accountId) return null;

  /*
   * A null breakdown is the billing source declining to answer — the 409 the resolver returns
   * when no report covers this account or this range. Nothing to show, so the section goes.
   *
   * An empty breakdown is an answer: the source was read and no network charges were found.
   * That is routine on the 1st, before the new billing period has been delivered, and hiding
   * the section then removed the month selector along with it — so the reader could neither see
   * that network costs exist as a feature nor reach a month that has any. It now stays and says
   * which of the two situations it is in.
   */
  if (!loading && breakdown === null) return null;
  const isEmpty = !loading && groups.length === 0;

  const openGroup = groups.find((g) => g.category === openCategory) ?? null;

  return (
    <CostCurrencyContext.Provider value={currency}>
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
        <div>
          <h3 className="text-[15px] font-bold text-black">
            {copy.heading ?? 'Data transfer & network costs'}
          </h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            {copy.subheading ??
              'Network charges pulled out of every service they are billed under. Open a category to see the routes, resources and costs behind it.'}
          </p>
        </div>

        <div className="flex items-center gap-4">
          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            aria-label="Billing month"
            className="text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg px-2.5 py-1.5 bg-white text-black focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
          >
            {months.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
          <div className="text-right">
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Total</p>
            <p className="text-[18px] font-bold text-black leading-tight">{money(total)}</p>
          </div>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[52px] rounded-lg" />
          ))}
        </div>
      ) : isEmpty ? (
        <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          {/* Their wording kept as the AWS default, but behind `copy.emptyHint` — Azure has no Cost
              and Usage Report and GCP has no backfill rule, so stating one as fact would send those
              customers looking for something their cloud does not have. */}
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            No network charges found for {monthLabel}.{' '}
            {isCurrentMonth
              ? (copy.currentMonthHint ??
                  'AWS delivers the first report for a new billing month a few hours after the'
                    + ' month opens, then refreshes it several times a day — so a month that has'
                    + ' only just started reads as empty until that first delivery lands. Pick'
                    + ' last month to see a complete bill.')
              : (copy.emptyHint ??
                  'A Cost and Usage Report only covers months from when it was created onward — it'
                    + ' does not backfill, so earlier months are not in it.')}
          </div>
        </div>
      ) : (
        <div className="space-y-1.5">
          {groups.map((g) => {
            const meta = CATEGORIES[g.category] ?? fallbackMeta;
            const share = total > 0 ? (g.cost / total) * 100 : 0;
            const topRoute = g.routes[0];

            return (
              <button
                key={g.category}
                type="button"
                onClick={() => setOpenCategory(g.category)}
                aria-haspopup="dialog"
                aria-expanded={openCategory === g.category}
                className="w-full flex items-center gap-3 px-3 py-2.5 border border-[rgba(0,0,0,0.08)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors text-left"
              >
                <Icon
                  icon={meta.icon}
                  width={17}
                  style={{ color: meta.color }}
                  className="flex-shrink-0"
                />

                <div className="flex-1 min-w-0">
                  <div className="flex items-baseline gap-2 flex-wrap">
                    <span className="text-[13px] font-semibold text-black">{g.category}</span>
                    {g.gigabytes !== null && (
                      <span className="text-[11.5px] text-[rgba(0,0,0,0.45)]">
                        {quantity(g.gigabytes, 'GB')}
                      </span>
                    )}
                    {/* The busiest route on the collapsed row: the point of the section is
                        that nobody should have to open the panel to learn where the money went. */}
                    {topRoute && (
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">
                        <RouteLabel from={topRoute.from} to={topRoute.to} />
                        {g.routes.length > 1 && ` +${g.routes.length - 1} more`}
                      </span>
                    )}
                    {!topRoute && g.regions.length > 0 && (
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)] truncate">
                        {g.regions.slice(0, 2).join(', ')}
                        {g.regions.length > 2 && ` +${g.regions.length - 2}`}
                      </span>
                    )}
                  </div>
                  <div className="h-1 bg-[rgba(0,0,0,0.05)] rounded-full mt-1.5 overflow-hidden">
                    <div
                      className="h-full rounded-full"
                      style={{ width: `${share}%`, backgroundColor: meta.color }}
                    />
                  </div>
                </div>

                <div className="text-right flex-shrink-0">
                  <p className="text-[13px] font-bold text-black">{money(g.cost)}</p>
                  <p className="text-[10.5px] text-[rgba(0,0,0,0.4)]">{share.toFixed(0)}%</p>
                </div>

                {/* A static affordance that this row opens a panel, rather than the chevron the
                    inline accordion used to swap for expand/collapse. */}
                <Icon
                  icon="mdi:chevron-right"
                  width={16}
                  className="text-[rgba(0,0,0,0.3)] flex-shrink-0"
                />
              </button>
            );
          })}
        </div>
      )}

      {groups.length > 0 && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
          {copy.footnote ??
            'NAT Gateway, VPC endpoint and Transit Gateway include their hourly fees as well as per-GB processing — the two are billed separately, and the hourly one is usually the smaller of the pair.'}
        </p>
      )}

      {/* The depth view, over the top of the list rather than pushed below it. Rendered inside the
          provider so its money formats in the section's currency. */}
      <DataTransferCategoryPanel
        group={openGroup}
        total={total}
        routeDataAvailable={breakdown?.routeDataAvailable ?? false}
        resourceDataAvailable={breakdown?.resourceDataAvailable ?? false}
        copy={copy}
        onClose={() => setOpenCategory(null)}
      />
    </div>
    </CostCurrencyContext.Provider>
  );
};

export default DataTransferSection;
