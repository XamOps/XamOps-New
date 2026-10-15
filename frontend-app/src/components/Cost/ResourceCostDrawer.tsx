import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import type { CostBasis, ResourceChange, ResourceLineChange } from '../../api/curInsightsApi';
import { fetchResourceChange } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { lockPageScroll } from '../../utils/scrollLock';
import { useMoneyIn } from './costCurrency';
import ResourceConnectivityTab, {
  awsConnectivity,
  type ConnectivityFetchers,
} from './ResourceConnectivityTab';
import ResourceSecurityTab, {
  awsSecurity,
  type SecurityFetchers,
} from './ResourceSecurityTab';

/**
 * One resource, in depth, in a panel over the page.
 *
 * <h2>Why a panel and not another section</h2>
 * The change decomposition used to be a table of services stacked below the others, so reading it meant
 * scrolling past everything else and then scrolling back to the resource it referred to. Stacked
 * sections also make depth cost page height: every new grain of detail pushed the page longer, and the
 * reader lost their place each time they went looking for it.
 *
 * <p>A panel inverts that. The resource table stays where it was and keeps the reader's position; the
 * detail arrives over the top, in front of the row it belongs to. Closing it returns them to exactly
 * the row they clicked, which a scroll never does. The page gets shorter rather than longer as the
 * product gets deeper.
 *
 * <h2>Why three tabs</h2>
 * They answer three different questions about the same resource, and a reader has one of them at a time:
 *
 * <ul>
 *   <li><b>Change</b> — did this cost more because it was used more, or because the rate moved? The
 *       headline, and the reason the panel exists.</li>
 *   <li><b>Breakdown</b> — <em>which</em> part of it moved. A disk that grew and a disk whose storage
 *       tier repriced look identical on the Change tab and completely different here.</li>
 *   <li><b>Trend</b> — <em>when</em> it moved. A step on one day is a deployment; a slope is growth.
 *       That distinction decides who gets asked about it.</li>
 *   <li><b>Connectivity</b> — <em>what it is attached to</em>, and what each of those things costs in
 *       the same month. The question a reader has next and which no per-resource figure can reach: a
 *       NAT gateway at $344 is meaningless until you know which five instances egress through it.
 *       AWS only, for the reasons on the {@code connectivity} prop.</li>
 * </ul>
 *
 * <p>Provider-agnostic: the three cost pages pass their own fetcher and currency, and the payloads are
 * field-identical, so there is one implementation of this panel rather than three that drift.
 */

type Tab = 'change' | 'breakdown' | 'trend' | 'connectivity' | 'security';

interface ResourceCostDrawerProps {
  /** The resource to describe, or null when the panel is closed. */
  resourceId: string | null;
  /** Fallback label while the payload loads, so the header is never blank. */
  resourceName?: string;
  /** Account, subscription or project the resource belongs to. */
  accountId: string | null;
  /** Billing month, `YYYY-MM`. Compared against the month before it. */
  period: string;
  onClose: () => void;
  /**
   * Where the detail comes from. Defaults to the AWS Cost and Usage Report; GCP and Azure pass their
   * own, which return the identical payload shape.
   */
  fetcher?: (
    accountId: string,
    resourceId: string,
    period?: string,
    scope?: { service?: string; region?: string },
  ) => Promise<ResourceChange | null>;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  /** What the panel calls the billing source when it cannot answer. */
  unavailableDetail?: React.ReactNode;
  /**
   * The service and region of the row that opened this.
   *
   * <p>Scopes the panel to the row's own figure. Without it the panel reports the resource's cost
   * across every service and region, which is a different and larger number than the row the reader
   * clicked — two answers for "this resource" on adjacent surfaces, with nothing explaining the gap.
   */
  scope?: { service?: string; region?: string };
  /**
   * The basis the row that opened this panel was priced on. Omit for unblended.
   *
   * <p>This panel's own figures are always unblended, whatever the card above it is on, and that is
   * deliberate rather than unfinished — see {@code BasisNote} for why. The prop exists so the
   * difference is stated instead of being left as a discrepancy the reader finds by subtracting.
   */
  basis?: CostBasis;
  /** That row's figure on that basis, so the difference can be named rather than implied. */
  rowCost?: number;
  /**
   * Where the Connectivity tab reads from, or `null` to omit the tab entirely.
   *
   * <p>Defaulted to the AWS trio, like every other fetcher here, so `null` and an omitted prop
   * cannot mean two different things. GCP and Azure pass `null`: there is no Azure topology
   * endpoint at all, and while GCP has a graph it has no batch per-resource cost query and no
   * identifier reconciler, so the tab there could only apologise — the same reason `labels` is
   * already off for GCP.
   */
  connectivity?: ConnectivityFetchers | null;
  /**
   * Where the Security &amp; Alerts tab reads from, or `null` to omit the tab entirely.
   *
   * <p>Defaulted to the AWS fetcher, like every other fetcher here, so `null` and an omitted prop
   * cannot mean two different things. GCP and Azure pass `null`: per-resource security findings are
   * AWS-only in v1 (Security Center, ComplianceOps and Performance Insights all read AWS surfaces).
   */
  security?: SecurityFetchers | null;
}

/** Month names for the two periods being compared, from a `YYYY-MM` string. */
const monthLabels = (period: string): { current: string; prior: string } => {
  const [year, month] = period.split('-').map(Number);
  if (!year || !month) return { current: 'this month', prior: 'last month' };
  const current = new Date(year, month - 1, 1);
  const prior = new Date(year, month - 2, 1);
  const name = (d: Date) => d.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  return { current: name(current), prior: name(prior) };
};

/** A usage quantity at a readable precision. Quantities here span hours to hundreds of millions. */
const quantity = (v: number): string => {
  if (v === 0) return '0';
  const abs = Math.abs(v);
  if (abs >= 1000) return v.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (abs >= 1) return v.toLocaleString('en-US', { maximumFractionDigits: 3 });
  return v.toLocaleString('en-US', { maximumFractionDigits: 6 });
};

/** "2026-08-14" → "14 Aug". Axis labels, so short and unambiguous. */
const dayLabel = (iso: string): string => {
  const d = new Date(`${iso}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-US', { day: 'numeric', month: 'short' });
};

/** Rows below this are rounding, not a story. One unit of whatever the account is billed in. */
const MIN_LINE_CHANGE = 0.01;

const ResourceCostDrawer: React.FC<ResourceCostDrawerProps> = ({
  resourceId,
  resourceName,
  accountId,
  period,
  onClose,
  fetcher = fetchResourceChange,
  insightName = 'resource-change',
  currency = 'USD',
  unavailableDetail,
  scope,
  basis,
  rowCost,
  connectivity = awsConnectivity,
  security = awsSecurity,
}) => {
  const [tab, setTab] = useState<Tab>('change');
  const connectivityActive = tab === 'connectivity';
  const securityActive = tab === 'security';
  /*
   * Mounted on first selection, kept mounted after that, so the focus trail and the scroll
   * position of a long peer list survive a glance at Trend.
   *
   * A ref and not state: flipping it must not itself cause a render, and it only ever goes false
   * -> true. The body is keyed on `resourceId` below, which is what discards a previous
   * resource's trail — synchronously, rather than one render later as an effect would.
   */
  const everConnectivity = useRef(false);
  /** The same mount-once-then-keep latch as Connectivity, for the Security tab's body. */
  const everSecurity = useRef(false);
  /*
   * Latched during render, which costs one extra read in one case, measured and accepted.
   *
   * When `resourceId` changes while Connectivity is the selected tab, the commit carrying the new
   * id still has `tab === 'connectivity'` — the reset below is an effect and lands after it — so
   * this re-latches and the keyed body mounts once with `active` true, issuing one
   * /resource-identities call for the new resource. The graph query hits its own 30-minute cache
   * entry (same account, same region), and no cost query can fire because the tab's `settled` gate
   * needs both prior queries successful. So the cost is a single indexed Postgres read and no
   * Athena scan, and criterion 18a — no stale peer row, no cost call on the previous focus — still
   * holds. Deriving the latch from state set in the same commit would trade that read for an extra
   * render on every selection of the tab.
   */
  if (connectivityActive) everConnectivity.current = true;
  if (securityActive) everSecurity.current = true;
  const format = useMoneyIn(currency);
  // Signed, with the sign outside the amount, so a column of changes scans as +/− rather than having
  // the minus buried inside the currency symbol.
  const money = (v: number): string => (v < 0 ? '-' : '') + format(Math.abs(v));
  const months = useMemo(() => monthLabels(period), [period]);

  /*
   * Fetched through the same cache as every other insight section, keyed on the resource.
   *
   * These queries are narrow — one resource predicate — but they are not free, and a reader comparing
   * two resources will open, close and reopen. Without the cache each reopen would re-scan.
   */
  const { data, isPending, isError } = useCurInsight(
    // The scope is part of the key: one resource can have a row under two services, and each panel
    // shows a different figure, so they must not share a cache entry.
    `${insightName}:${resourceId ?? ''}:${scope?.service ?? ''}:${scope?.region ?? ''}`,
    // Only enabled once both are known, which is also what stops the query firing while closed.
    resourceId && accountId ? accountId : null,
    period,
    (account, p) => fetcher(account, resourceId as string, p, scope),
  );
  const change = isError ? null : (data ?? null);
  const loading = Boolean(resourceId) && isPending;

  // Reset to the headline tab whenever a different resource is opened: the reader's last tab choice was
  // about the previous resource, and landing on Trend for a new one buries the answer they came for.
  useEffect(() => {
    setTab('change');
    // So a new resource starts with the connectivity body unmounted rather than inheriting a
    // mount from the previous one. The `key` below is what discards the trail itself, which is
    // why this is tidiness rather than the guard.
    everConnectivity.current = false;
    everSecurity.current = false;
  }, [resourceId]);

  useEffect(() => {
    if (!resourceId) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [resourceId, onClose]);

  /*
   * Freeze the page behind the panel, through the shared counted lock.
   *
   * <p>This used to set `overflow: hidden` on <body> and the real scroll container directly, and
   * clear both on close. That broke once this panel started opening *over* another one: this is now
   * reached from the service resource list, which is itself a drawer holding the same lock, and a
   * cleanup that cleared the style outright handed the scroll back while that list was still
   * covering the page. The count means the last overlay to close is the one that restores it.
   */
  useEffect(() => (resourceId ? lockPageScroll() : undefined), [resourceId]);

  const heading = change?.name || resourceName || resourceId || '';
  const lines = useMemo(
    () =>
      (change?.lines ?? []).filter(
        (l) =>
          Math.abs(l.costChange) >= MIN_LINE_CHANGE ||
          Math.abs(l.currentCost) >= MIN_LINE_CHANGE,
      ),
    [change],
  );

  return createPortal(
    <AnimatePresence>
      {resourceId && (
        <React.Fragment key="resource-cost-drawer">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            /* Above the service resource list, which opens this and stays behind it at z-50. */
            className="fixed inset-0 bg-black/30 z-[60]"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            role="dialog"
            aria-label="Resource cost detail"
            /*
             * Narrower than the resource list this opens over, deliberately — the sliver of that
             * list left showing on the left is what says this sits on top of it rather than
             * having replaced it. Scaled to the viewport for the same reason the list is (see
             * ServiceResourcesDrawer), so the inset stays about the same at every window size
             * instead of swallowing the list on a laptop and vanishing on a wide monitor.
             */
            className="fixed right-0 top-0 h-full w-full sm:w-[58vw] sm:max-w-[960px] bg-white shadow-2xl z-[60] flex flex-col"
          >
            {/* Header */}
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-start justify-between gap-4">
              <div className="min-w-0">
                <p className="text-[15px] font-bold text-black truncate" title={heading}>
                  {heading}
                </p>
                <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5">
                  {change?.service && <span>{change.service}</span>}
                  {change?.region && change.region !== 'Global' && (
                    <>
                      <span className="text-[rgba(0,0,0,0.2)]">·</span>
                      <span>{change.region}</span>
                    </>
                  )}
                  {change?.instanceType && (
                    <>
                      <span className="text-[rgba(0,0,0,0.2)]">·</span>
                      <span className="px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] text-[10.5px]">
                        {change.instanceType}
                      </span>
                    </>
                  )}
                </p>
                {/* The full id, which the heading truncates to a name. Selectable, because the next
                    thing a reader does with it is paste it into a console or a ticket. */}
                <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] mt-1 font-mono break-all select-all">
                  {resourceId}
                </p>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="Close"
                className="w-8 h-8 flex-shrink-0 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
              >
                <Icon icon="mdi:close" width={15} />
              </button>
            </div>

            {/* Tabs. Plain buttons rather than the AriaTabGroup used on page-level cards: this is a
                dialog, and its tab strip should not read as page navigation. */}
            <nav
              role="tablist"
              aria-label="Resource detail"
              className="flex items-center gap-1 px-4 border-b border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.015)]"
            >
              {(
                [
                  { key: 'change' as Tab, label: 'Change' },
                  { key: 'breakdown' as Tab, label: `Breakdown${lines.length ? ` (${lines.length})` : ''}` },
                  { key: 'trend' as Tab, label: 'Trend' },
                  // No count on the label: the peer count is not known until two requests have
                  // landed, so it would be the only label on the strip that moves — and it would
                  // read "(0)" for every resource the topology does not know, which looks like
                  // "no connections" before anything has been read.
                  ...(connectivity ? [{ key: 'connectivity' as Tab, label: 'Connectivity' }] : []),
                  // After Connectivity: what SecOps knows about this resource. Omitted when the
                  // provider passes null, the same way Connectivity is.
                  ...(security ? [{ key: 'security' as Tab, label: 'Security & Alerts' }] : []),
                ]
              ).map((t) => (
                <button
                  key={t.key}
                  type="button"
                  role="tab"
                  aria-selected={tab === t.key}
                  onClick={() => setTab(t.key)}
                  className={`px-3 py-2.5 text-[12.5px] font-medium border-b-2 -mb-px transition-colors ${
                    tab === t.key
                      ? 'border-black text-black'
                      : 'border-transparent text-[rgba(0,0,0,0.45)] hover:text-[rgba(0,0,0,0.7)]'
                  }`}
                >
                  {t.label}
                </button>
              ))}
            </nav>

            <div className="flex-1 overflow-y-auto p-5">
              {/* Both of these describe the resource-change payload, which the Connectivity tab
                  does not read — so neither may cover a tab that works. In demo mode, where
                  /cur-insights/resource-change has no fixture, that payload is null for every
                  resource. */}
              {loading && !connectivityActive && (
                <div className="space-y-2">
                  {[0, 1, 2, 3, 4].map((i) => (
                    <div
                      key={i}
                      className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[44px] rounded-lg"
                    />
                  ))}
                </div>
              )}

              {/* Cannot answer — distinct from "no movement". The reader followed a link from a table
                  that worked, so an unexplained blank panel would read as a fault. */}
              {!loading && !change && !connectivityActive && (
                <div className="text-center py-12">
                  <Icon
                    icon="mdi:file-remove-outline"
                    width={26}
                    className="text-[rgba(0,0,0,0.2)] mx-auto mb-2"
                  />
                  <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
                    No detail available for this resource
                  </p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-[420px] mx-auto leading-relaxed">
                    {unavailableDetail ??
                      'The billing source could not be read for this resource and the month before it.'}
                  </p>
                </div>
              )}

              {!loading && change && tab === 'change' && (
                <ChangeTab
                  change={change}
                  months={months}
                  money={money}
                  format={format}
                  basis={basis}
                  rowCost={rowCost}
                />
              )}

              {!loading && change && tab === 'breakdown' && (
                <BreakdownTab
                  lines={lines}
                  months={months}
                  money={money}
                  format={format}
                  comparisonAvailable={change.comparisonAvailable}
                />
              )}

              {!loading && change && tab === 'trend' && (
                <TrendTab change={change} months={months} money={money} />
              )}

              {/* Gated on neither `loading` nor `change`: this answer does not come from the
                  resource-change payload, so gating on either would hide a working tab for a
                  resource whose CUR comparison cannot be read. */}
              {connectivity && everConnectivity.current && resourceId && (
                <div hidden={!connectivityActive}>
                  <ResourceConnectivityTab
                    key={resourceId}
                    active={connectivityActive}
                    accountId={accountId}
                    resourceId={resourceId}
                    resourceName={resourceName}
                    period={period}
                    basis={basis}
                    // "Global" is this section's own word for a row the report gave no region, not
                    // a region the topology can be scoped to.
                    region={scope?.region && scope.region !== 'Global' ? scope.region : undefined}
                    insightName={insightName}
                    currency={currency}
                    connectivity={connectivity}
                    change={change}
                  />
                </div>
              )}

              {/* Gated on neither `loading` nor `change`, like Connectivity: this answer does not
                  come from the resource-change payload, so gating on either would hide a working
                  tab for a resource whose CUR comparison cannot be read. */}
              {security && everSecurity.current && resourceId && (
                <div hidden={!securityActive}>
                  <ResourceSecurityTab
                    key={resourceId}
                    active={securityActive}
                    accountId={accountId}
                    resourceId={resourceId}
                    resourceName={resourceName}
                    period={period}
                    region={scope?.region && scope.region !== 'Global' ? scope.region : undefined}
                    security={security}
                  />
                </div>
              )}
            </div>
          </motion.aside>
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

// ── Change ───────────────────────────────────────────────────────────────────

interface TabProps {
  months: { current: string; prior: string };
  money: (v: number) => string;
  format: (v: number) => string;
  /** The basis the row that opened the panel was on, and its figure on that basis. */
  basis?: CostBasis;
  rowCost?: number;
}

/**
 * Why this panel's figures are unblended when the row that opened it was not.
 *
 * <p>Renders only on the amortized basis. The panel decomposes a change into rate and usage, and
 * cost ÷ quantity is only a real unit rate on unblended cost: on a reserved instance the amortized
 * cost comes from the reservation's effective rate while the quantity is instance-hours at
 * on-demand grain, so the ratio is an accounting artefact — and because the price effect is the
 * residual, the entire commitment treatment would be reported as "the rate changed". The panel
 * would be stating, in its own words, that a reservation purchase was a price increase. It also
 * preserves `priceEffect + volumeEffect == costChange`, which this panel renders visually.
 *
 * <p>Rendered *instead of* `ExcludedNote` rather than beside it. On the amortized basis a
 * commitment-covered resource is exactly the case where `excludedCost` is large, so the two would
 * stack as two notes in the identical treatment about the same gap — which is the shape the card's
 * own amortized note was designed to avoid. The excluded figure is folded into the sentence below
 * instead, so nothing is lost by the substitution.
 */
const BasisNote: React.FC<{
  basis?: CostBasis;
  rowCost?: number;
  excludedCost?: number;
  usageCost: number;
  month: string;
  format: (v: number) => string;
}> = ({ basis, rowCost, excludedCost, usageCost, month, format }) => {
  // Both props are optional because the fallback path (GCP, Azure, any caller without a basis)
  // supplies neither, and the sentence interpolates rowCost — "the row you opened reads $NaN" is
  // worse than silence.
  if (basis !== 'amortized' || rowCost === undefined) return null;
  const hasExcluded = excludedCost !== undefined && Math.abs(excludedCost) >= 0.01;

  return (
    <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
      <Icon
        icon="mdi:information-outline"
        width={15}
        className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
      />
      <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
        <span className="font-medium text-black">
          The row you opened reads {format(rowCost)} on the amortized basis.
        </span>{' '}
        The figures here are unblended — this panel separates a rate change from a usage change, and
        only unblended cost divided by quantity is a real unit rate.{' '}
        {hasExcluded
          ? `${format(usageCost)} of ${month}'s spend is metered usage, which is what the figures above compare; the other ${format(
              Math.abs(excludedCost as number),
            )} is reserved or Savings Plan coverage, commitment fees, tax or credits, which carry no quantity to measure a rate against.`
          : `The figures above cover ${month}'s metered usage.`}{' '}
        The <span className="font-medium">Trend</span> tab shows every charge.
      </div>
    </div>
  );
};

/**
 * What this panel's figures leave out, and why.
 *
 * <p>The comparison counts usage rows only — they are the only ones carrying a quantity, and without a
 * quantity there is no unit rate to split a change into price and volume. That is the right restriction
 * for the arithmetic and the wrong one for a total: a Savings-Plan-covered instance bills almost
 * entirely as `SavingsPlanCoveredUsage`, so the panel showed a few dollars where the row that opened it
 * showed a few hundred. Two numbers for one resource, on screens one click apart, with nothing
 * accounting for the difference — which reads as the product being wrong even when both figures are
 * right.
 *
 * <p>So the gap is stated and added back. Rendered only when there is one, and only on the AWS report,
 * which is the only source that reports it.
 */
const ExcludedNote: React.FC<{
  excludedCost?: number;
  usageCost: number;
  month: string;
  format: (v: number) => string;
}> = ({ excludedCost, usageCost, month, format }) => {
  if (excludedCost === undefined || Math.abs(excludedCost) < 0.01) return null;
  const credit = excludedCost < 0;

  return (
    <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
      <Icon
        icon="mdi:information-outline"
        width={15}
        className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
      />
      <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
        <span className="font-medium text-black">
          {format(usageCost + excludedCost)} billed in total
        </span>{' '}
        for {month} — {format(usageCost)} of it as metered usage, which is what the figures above
        compare, plus {format(Math.abs(excludedCost))}{' '}
        {credit ? 'in credits and refunds' : 'in charges that carry no usage quantity'}
        {credit
          ? '.'
          : ' — reserved or Savings Plan coverage, commitment fees, tax or support.'}{' '}
        Those have no quantity to measure a rate against, so they cannot be split into usage and rate,
        but they are on the bill. The <span className="font-medium">Trend</span> tab shows every charge.
      </div>
    </div>
  );
};

/**
 * The headline: what it cost, what changed, and which of usage or rate drove it.
 *
 * <p>Deliberately shows the working rather than only the result. The two effects are derived figures,
 * and a reader with no way to check them has to either trust the number or ignore it.
 */
const ChangeTab: React.FC<TabProps & { change: ResourceChange }> = ({
  change,
  months,
  money,
  format,
  basis,
  rowCost,
}) => {
  const usageDriven = Math.abs(change.volumeEffect) >= Math.abs(change.priceEffect);
  const isNew = change.priorCost === 0 && change.priorQuantity === 0;
  const priorRate = change.priorQuantity > 0 ? change.priorCost / change.priorQuantity : 0;
  const up = change.costChange >= 0;

  /*
   * No earlier month in the billing source at all.
   *
   * Reported instead of the split, not alongside it. The prior figures are zero because the report does
   * not reach that far, and running them through the decomposition would show this resource's entire
   * cost as "used more" — a statement about the report's age presented as a statement about the
   * infrastructure. The month's own figures are real and still worth showing.
   */
  if (!change.comparisonAvailable) {
    return (
      <div className="space-y-5">
        <div className="rounded-xl border border-[rgba(0,0,0,0.1)] p-4">
          <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium">
            {months.current}
          </p>
          <p className="text-[26px] font-bold mt-1 tabular-nums text-black">
            {format(change.currentCost)}
          </p>
          {change.currentQuantity > 0 && (
            <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-1">
              {quantity(change.currentQuantity)} used ·{' '}
              {format(change.currentCost / change.currentQuantity)} per unit
            </p>
          )}
        </div>

        {/* One or the other, never both: see BasisNote. */}
        {basis === 'amortized' && rowCost !== undefined ? (
          <BasisNote
            basis={basis}
            rowCost={rowCost}
            excludedCost={change.excludedCost}
            usageCost={change.currentCost}
            month={months.current}
            format={format}
          />
        ) : (
          <ExcludedNote
            excludedCost={change.excludedCost}
            usageCost={change.currentCost}
            month={months.current}
            format={format}
          />
        )}

        <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            <span className="font-medium">No comparison against {months.prior}.</span> The billing source
            does not cover that month — a report or export begins when it was created and does not
            backfill — so there is no earlier cost or rate to measure this against. The{' '}
            <span className="font-medium">Breakdown</span> and <span className="font-medium">Trend</span>{' '}
            tabs describe {months.current} and are unaffected.
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* The answer, before any of the arithmetic. */}
      <div className="rounded-xl border border-[rgba(0,0,0,0.1)] p-4">
        <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium">
          {months.current} vs {months.prior}
        </p>
        <p
          className={`text-[26px] font-bold mt-1 tabular-nums ${
            up ? 'text-[#DC2626]' : 'text-[#158045]'
          }`}
        >
          {up ? '+' : ''}
          {money(change.costChange)}
        </p>
        <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-1">
          {format(change.priorCost)} → <span className="font-semibold">{format(change.currentCost)}</span>
        </p>
      </div>

      {/* One or the other, never both: see BasisNote. */}
      {basis === 'amortized' && rowCost !== undefined ? (
        <BasisNote
          basis={basis}
          rowCost={rowCost}
          excludedCost={change.excludedCost}
          usageCost={change.currentCost}
          month={months.current}
          format={format}
        />
      ) : (
        <ExcludedNote
          excludedCost={change.excludedCost}
          usageCost={change.currentCost}
          month={months.current}
          format={format}
        />
      )}

      <div className="grid grid-cols-2 gap-3">
        <EffectCard
          label="Used more / less"
          value={money(change.volumeEffect)}
          dominant={usageDriven}
          icon="mdi:chart-bar"
          tint="#4285F4"
        />
        <EffectCard
          label="Rate change"
          value={money(change.priceEffect)}
          dominant={!usageDriven}
          icon="mdi:tag-outline"
          tint="#F4A429"
        />
      </div>

      {/* What was billed, both months — the four numbers everything above comes from. */}
      <div>
        <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium mb-2">
          What was billed
        </p>
        <table className="w-full text-[12px]">
          <thead>
            <tr className="text-[rgba(0,0,0,0.4)] text-[10.5px] uppercase tracking-wide">
              <th className="text-left font-medium pb-1.5"></th>
              <th className="text-right font-medium pb-1.5">Cost</th>
              <th className="text-right font-medium pb-1.5">Usage</th>
              <th className="text-right font-medium pb-1.5">Per unit</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-t border-[rgba(0,0,0,0.06)]">
              <td className="py-1.5 text-[rgba(0,0,0,0.6)]">{months.prior}</td>
              <td className="py-1.5 text-right tabular-nums">{format(change.priorCost)}</td>
              <td className="py-1.5 text-right tabular-nums">{quantity(change.priorQuantity)}</td>
              <td className="py-1.5 text-right tabular-nums">
                {change.priorQuantity > 0 ? format(priorRate) : '—'}
              </td>
            </tr>
            <tr className="border-t border-[rgba(0,0,0,0.06)]">
              <td className="py-1.5 text-[rgba(0,0,0,0.6)]">{months.current}</td>
              <td className="py-1.5 text-right tabular-nums font-semibold">
                {format(change.currentCost)}
              </td>
              <td className="py-1.5 text-right tabular-nums">{quantity(change.currentQuantity)}</td>
              <td className="py-1.5 text-right tabular-nums">
                {change.currentQuantity > 0
                  ? format(change.currentCost / change.currentQuantity)
                  : '—'}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      {/* The arithmetic, with this resource's own numbers in it. */}
      <div className="rounded-lg bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.06)] p-3.5">
        <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium mb-2">
          How the split is worked out
        </p>
        {isNew ? (
          <p className="text-[11.5px] text-[rgba(0,0,0,0.6)] leading-relaxed">
            Nothing was billed for this resource in {months.prior}, so there is no earlier rate to
            compare against and the whole <span className="font-medium">{money(change.costChange)}</span>{' '}
            counts as usage.
          </p>
        ) : (
          <div className="text-[11.5px] text-[rgba(0,0,0,0.6)] space-y-1.5 leading-relaxed">
            <p>
              <span className="font-medium text-black">Used more / less</span> = usage change ×{' '}
              {months.prior}&apos;s rate
              <br />
              <span className="tabular-nums">
                ({quantity(change.currentQuantity)} − {quantity(change.priorQuantity)}) ×{' '}
                {format(priorRate)} = {money(change.volumeEffect)}
              </span>
            </p>
            <p>
              <span className="font-medium text-black">Rate change</span> = whatever the total change
              leaves after that
              <br />
              <span className="tabular-nums">
                {money(change.costChange)} − ({money(change.volumeEffect)}) ={' '}
                {money(change.priceEffect)}
              </span>
            </p>
          </div>
        )}
        <p className="text-[10.5px] text-[rgba(0,0,0,0.38)] mt-2.5 leading-relaxed">
          This resource&apos;s usage is summed across everything it bills for, so where that mixes units
          the per-unit figure is blended. The <span className="font-medium">Breakdown</span> tab splits it
          per line, where each rate is exact.
        </p>
      </div>
    </div>
  );
};

/** One of the two effects. The larger one is marked, because that is the reader's takeaway. */
const EffectCard: React.FC<{
  label: string;
  value: string;
  dominant: boolean;
  icon: string;
  tint: string;
}> = ({ label, value, dominant, icon, tint }) => (
  <div
    className={`rounded-xl border p-3.5 ${
      dominant ? 'border-[rgba(0,0,0,0.18)] bg-[rgba(0,0,0,0.015)]' : 'border-[rgba(0,0,0,0.08)]'
    }`}
  >
    <p className="text-[10.5px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium flex items-center gap-1.5">
      <Icon icon={icon} width={12} color={tint} />
      {label}
    </p>
    <p className="text-[17px] font-bold text-black mt-1 tabular-nums">{value}</p>
    {dominant && (
      <p className="text-[10.5px] text-[rgba(0,0,0,0.45)] mt-0.5">Main driver</p>
    )}
  </div>
);

// ── Breakdown ────────────────────────────────────────────────────────────────

/**
 * Which line inside the resource moved.
 *
 * <p>The tab that earns the panel. "This disk cost ₹400 more" is where the table stops; this says the
 * provisioned capacity is flat and the snapshot line tripled, which is a different action entirely.
 * Each line bills in one unit, so unlike the Change tab these rates are exact.
 */
const BreakdownTab: React.FC<
  TabProps & { lines: ResourceLineChange[]; comparisonAvailable: boolean }
> = ({ lines, months, money, format, comparisonAvailable }) => {
  if (lines.length === 0) {
    return (
      <div className="text-center py-10">
        <Icon icon="mdi:receipt-text-outline" width={24} className="text-[rgba(0,0,0,0.2)] mx-auto mb-2" />
        <p className="text-[13px] text-[rgba(0,0,0,0.55)]">No billed lines in either month</p>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.38)] mt-1">
          The comparison covers usage lines only, so a resource whose charges were all credits, tax or
          purchases has nothing to list here.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] leading-relaxed">
        {comparisonAvailable ? (
          <>
            Each line is one thing the resource is billed for, {months.current} against {months.prior},
            largest movement first. A line bills in a single unit, so its rate is exact — this is where a
            rate change can be pinned to the part of the resource it happened in.
          </>
        ) : (
          <>
            Each line is one thing the resource is billed for in {months.current}, largest first. There is
            no earlier month in the billing source to compare them against, so these are {months.current}
            &apos;s figures rather than movements.
          </>
        )}
      </p>

      {lines.map((l) => {
        const usageDriven = Math.abs(l.volumeEffect) >= Math.abs(l.priceEffect);
        const up = l.costChange >= 0;
        const priorRate = l.priorQuantity > 0 ? l.priorCost / l.priorQuantity : 0;
        const currentRate = l.currentQuantity > 0 ? l.currentCost / l.currentQuantity : 0;
        const flat = Math.abs(l.costChange) < MIN_LINE_CHANGE;

        return (
          <div
            key={l.usageType || l.description}
            className="rounded-xl border border-[rgba(0,0,0,0.1)] p-3.5"
          >
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-[12.5px] font-medium text-black">{l.description}</p>
                {l.usageType && l.usageType !== l.description && (
                  <p className="text-[10.5px] text-[rgba(0,0,0,0.38)] font-mono mt-0.5 break-all">
                    {l.usageType}
                  </p>
                )}
              </div>
              <div className="text-right flex-shrink-0">
                <p
                  className={`text-[13.5px] font-bold tabular-nums ${
                    flat ? 'text-[rgba(0,0,0,0.45)]' : up ? 'text-[#DC2626]' : 'text-[#158045]'
                  }`}
                >
                  {flat ? 'no change' : `${up ? '+' : ''}${money(l.costChange)}`}
                </p>
                <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] tabular-nums">
                  {format(l.currentCost)} now
                </p>
              </div>
            </div>

            <div className="mt-2.5 grid grid-cols-2 gap-x-4 gap-y-1 text-[11px]">
              <span className="text-[rgba(0,0,0,0.45)]">
                Usage{' '}
                <span className="tabular-nums text-[rgba(0,0,0,0.7)]">
                  {quantity(l.priorQuantity)} → {quantity(l.currentQuantity)}
                  {l.unit ? ` ${l.unit}` : ''}
                </span>
              </span>
              <span className="text-[rgba(0,0,0,0.45)]">
                Rate{' '}
                <span className="tabular-nums text-[rgba(0,0,0,0.7)]">
                  {l.priorQuantity > 0 ? format(priorRate) : '—'} →{' '}
                  {l.currentQuantity > 0 ? format(currentRate) : '—'}
                </span>
              </span>
            </div>

            {!flat && (
              <div className="mt-2.5 flex items-center gap-2 flex-wrap">
                <span
                  className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10.5px] font-medium ${
                    usageDriven ? 'bg-[#4285F414] text-[#1A56C4]' : 'bg-[#F4A42914] text-[#9A6800]'
                  }`}
                >
                  <Icon icon={usageDriven ? 'mdi:chart-bar' : 'mdi:tag-outline'} width={11} />
                  {usageDriven
                    ? l.volumeEffect >= 0
                      ? 'Used more'
                      : 'Used less'
                    : l.priceEffect >= 0
                      ? 'Higher rate'
                      : 'Lower rate'}
                </span>
                <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] tabular-nums">
                  usage {money(l.volumeEffect)} · rate {money(l.priceEffect)}
                </span>
              </div>
            )}
          </div>
        );
      })}

      <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] leading-relaxed">
        Lines present in only one of the two months count entirely as usage — there was no earlier rate to
        compare them against. These lines cover <span className="font-medium">usage charges only</span>:
        tax, credits, refunds and purchases carry a cost but no comparable quantity, so averaging them
        into a rate would be meaningless. That is why this list can total less than the{' '}
        <span className="font-medium">Trend</span> tab, which is the resource&apos;s whole spend.
      </p>
    </div>
  );
};

// ── Trend ────────────────────────────────────────────────────────────────────

/**
 * When the change happened.
 *
 * <p>A bar per day, drawn as plain elements rather than a chart library. The series is at most 31 points
 * and needs no axes, tooltips or legend — a Chart.js instance here would be more code, a heavier bundle
 * on a panel that may never open, and another canvas to destroy on close.
 *
 * <p>The shape is the information: a step on one day is a deployment or a setting change, a slope is
 * growth, and a single spike is usually a one-off job. Those three point at different people.
 */
const TrendTab: React.FC<Omit<TabProps, 'format'> & { change: ResourceChange }> = ({
  change,
  months,
  money,
}) => {
  const daily = change.daily ?? [];

  if (daily.length === 0) {
    return (
      <div className="text-center py-10">
        <Icon icon="mdi:chart-line" width={24} className="text-[rgba(0,0,0,0.2)] mx-auto mb-2" />
        <p className="text-[13px] text-[rgba(0,0,0,0.55)]">No daily series for {months.current}</p>
        <p className="text-[11.5px] text-[rgba(0,0,0,0.38)] mt-1 max-w-[380px] mx-auto leading-relaxed">
          The month-over-month figures on the other tabs are unaffected — only the day-by-day shape is
          missing, which some billing sources will not break down per resource.
        </p>
      </div>
    );
  }

  const peak = Math.max(...daily.map((d) => Math.abs(d.cost)), 0);
  const total = daily.reduce((sum, d) => sum + d.cost, 0);
  const busiest = daily.reduce((max, d) => (Math.abs(d.cost) > Math.abs(max.cost) ? d : max), daily[0]);

  return (
    <div className="space-y-4">
      <div className="flex items-baseline justify-between gap-3 flex-wrap">
        <div>
          <p className="text-[11px] uppercase tracking-wide text-[rgba(0,0,0,0.4)] font-medium">
            {months.current}, day by day
          </p>
          <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-0.5">
            {money(total)} across {daily.length} day{daily.length === 1 ? '' : 's'}
          </p>
        </div>
        <p className="text-[11px] text-[rgba(0,0,0,0.45)]">
          Busiest day <span className="font-medium text-black">{dayLabel(busiest.date)}</span> ·{' '}
          <span className="tabular-nums">{money(busiest.cost)}</span>
        </p>
      </div>

      <div className="flex items-end gap-[3px] h-40 border-b border-[rgba(0,0,0,0.08)] pb-px">
        {daily.map((d) => {
          // Floored at 2px so a day with a small but non-zero cost is still visible; a true zero stays
          // invisible, which is the distinction worth preserving.
          const height = peak > 0 ? Math.max((Math.abs(d.cost) / peak) * 100, d.cost === 0 ? 0 : 2) : 0;
          return (
            <div
              key={d.date}
              className="flex-1 min-w-[3px] rounded-t transition-colors hover:opacity-80"
              style={{
                height: `${height}%`,
                backgroundColor: d.cost < 0 ? '#158045' : 'rgba(66,133,244,0.75)',
              }}
              title={`${dayLabel(d.date)} · ${money(d.cost)}`}
            />
          );
        })}
      </div>

      <div className="flex items-center justify-between text-[10.5px] text-[rgba(0,0,0,0.4)]">
        <span>{dayLabel(daily[0].date)}</span>
        <span>{dayLabel(daily[daily.length - 1].date)}</span>
      </div>

      <p className="text-[10.5px] text-[rgba(0,0,0,0.35)] leading-relaxed">
        Hover a bar for its date and cost. A step means something changed on that day — a deployment, a
        resize, a setting. A steady slope is growth. A single spike is usually a one-off job. Green bars
        are credits or refunds landing on that day. This total is every charge type, so it matches the
        resource&apos;s figure in the table and can exceed the usage-only lines on{' '}
        <span className="font-medium">Breakdown</span>.
      </p>
    </div>
  );
};

export default ResourceCostDrawer;
