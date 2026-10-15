import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  fetchChargeTypes,
  fetchChargesByService,
  fetchReportCapabilities,
  type ChargeTypeAmount,
  type CostBasis,
  type ReportCapabilities,
  type ResourceChange,
  type ServiceCharge,
  type TopResources,
} from '../../api/curInsightsApi';
import { useMoneyIn } from './costCurrency';
import { useCurInsight } from '../../hooks/useCurInsight';
import { awsRegionName } from '../../utils/awsRegionNames';
import { amortizedZeroedLabels } from './chargeTypeLabels';
import ChargeTypeReconciliation, {
  type ChargeTypeReconciliationCopy,
} from './ChargeTypeReconciliation';
import CostBasisToggle from './CostBasisToggle';
import type { ConnectivityFetchers } from './ResourceConnectivityTab';
import ServiceResourcesDrawer, {
  type ServiceResourcesCopy,
  type ServiceSelection,
} from './ServiceResourcesDrawer';

/**
 * Charges by service, in the exact shape of the AWS invoice:
 *
 * <pre>
 *   Simple Storage Service                                    USD 2,527.13
 *     Asia Pacific (Mumbai)                                   USD 2,527.13
 *       ...APS3-Requests-Tier1                                USD   735.64
 *         $0.005 per 1,000 PUT, COPY, POST or LIST requests
 *                                    147,128,739 Requests     USD   735.64
 * </pre>
 *
 * Only a Cost and Usage Report can produce this. Cost Explorer reports dollars, and the
 * usage quantity it exposes cannot be lined up against the same rows — here quantity, unit
 * and cost sit on every line already.
 *
 * <p>Four levels rather than a flat table because that is how the bill reads, and because
 * a real account produces hundreds of rate lines: without the hierarchy, S3 alone fills
 * the screen before EC2 appears.
 */

/**
 * The wording that names the billing source.
 *
 * <p>Every sentence here said "your AWS bill" or "the Cost and Usage Report" because this section was
 * written for AWS and nothing else rendered it. Once GCP and Azure began passing their own fetchers,
 * those sentences became wrong on two of the three pages — an Azure subscription has no Cost and
 * Usage Report to read, so telling a customer one could not be read points them at a thing that does
 * not exist on their cloud.
 *
 * <p>Overridable rather than branched on a provider enum, matching {@code DataTransferCopy}: the
 * arithmetic and the layout are identical everywhere, and only the name of the source changes.
 */
export interface ChargesByServiceCopy {
  /** Follows "September 2026 so far, ". Defaults to naming the AWS bill. */
  subheading?: string;
  /** Shown while the query is in flight. */
  loading?: string;
  /** Why a *past* month can legitimately hold nothing, which differs by billing source. */
  emptyHint?: React.ReactNode;
  /**
   * Why the month still in progress can hold nothing, which is a different reason entirely.
   *
   * <p>Separate from {@code emptyHint} because that one explains an absence of coverage — the
   * report does not reach back that far — and an open month is the opposite situation: it is
   * covered, the data has simply not been delivered yet. Showing the backfill sentence for the
   * current month sends the reader to look for a setup problem that is not there.
   */
  currentMonthHint?: React.ReactNode;
  /**
   * What the amortized basis changes about this table. Rendered above it on that basis only.
   *
   * <p>One note carrying both amortized facts — the dropped fee rows and the on-demand-grain
   * quantity column — because two notices for one toggle reads as two problems. Built at module
   * scope from the shared zeroed-type tuple, so adding a fifth type updates this sentence and the
   * ledger's without a copy edit.
   */
  amortizedNote?: React.ReactNode;
  /** Shown when a non-default basis could not be read, in place of the table. */
  basisFailedNote?: React.ReactNode;
}

const AWS_COPY: Required<ChargesByServiceCopy> = {
  subheading: 'in the same breakdown as your AWS bill.',
  loading: 'Reading the Cost and Usage Report…',
  emptyHint: (
    <>
      A Cost and Usage Report only covers months from when it was created onward — it does not
      backfill, so earlier months are not in it.
    </>
  ),
  currentMonthHint: (
    <>
      AWS delivers the first report for a new billing month a few hours after the month opens, then
      refreshes it several times a day — so a month that has only just started reads as empty until
      that first delivery lands. Pick last month to see a complete bill.
    </>
  ),
  amortizedNote: (
    <>
      {amortizedZeroedLabels().join(', ')} are not listed on this basis — their cost is already
      inside Reserved usage and Savings Plan usage. Quantities and units are metered usage at
      on-demand grain, so on this basis the amount beside them is what the commitment actually cost
      and amount ÷ quantity is not the rate in the description.
    </>
  ),
  basisFailedNote: (
    <>
      This report could not be read on the amortized basis. Showing nothing rather than unblended
      figures under an amortized label — switch back to unblended to see the bill.
    </>
  ),
};

interface ChargesByServiceSectionProps {
  /** Single account or project with a usable billing source, or null to render nothing. */
  accountId: string | null;
  /**
   * Defaults to the AWS Cost and Usage Report; GCP passes its billing-export fetcher.
   *
   * <p>The third parameter is the cost basis. A two-parameter function satisfies this type, so the
   * GCP and Azure fetchers need no change — but the type has to accept it or calling it with the
   * basis is a TS2554 and the build fails.
   */
  fetcher?: (
    accountId: string,
    period?: string,
    basis?: CostBasis,
  ) => Promise<ServiceCharge[] | null>;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  /** What this section calls the billing source. Defaults to AWS wording. */
  copy?: ChargesByServiceCopy;
  /**
   * Replaces this section's own `h3`.
   *
   * <p>Used to hoist the sub-tab control into the card header, so switching between this and
   * the change decomposition happens where the title is rather than in a strip above the card.
   * The control's own active label reads as the heading, which is why this substitutes for the
   * title rather than sitting beside it — two things both saying "Charges by service" is one
   * too many.
   */
  heading?: React.ReactNode;

  /*
   * ── The per-service resource panel ──────────────────────────────────────────────────────
   *
   * Per-resource cost was a sibling tab of this section until it became this drill-down. The
   * props below are what that panel needs; they are passed through rather than read here,
   * because the only thing this section contributes is *which* service was clicked.
   */

  /**
   * Where the clicked service's resources come from. Defaults to the AWS Cost and Usage Report.
   *
   * <p>Pass `null` to leave service rows as plain expanders — for a billing source that cannot
   * attribute cost to a resource at all, where every row would open a panel that can only
   * apologise.
   */
  resourcesFetcher?: ((accountId: string, period?: string) => Promise<TopResources | null>) | null;
  /** What that panel calls the billing source. Defaults to AWS wording. */
  resourcesCopy?: ServiceResourcesCopy;
  /** Cache-key discriminator for the resource query behind the panel. */
  resourcesInsightName?: string;
  /** Whether resource rows in the panel offer the label control. Off for GCP. */
  labels?: boolean;
  /** Where the panel's per-resource drill-down's Connectivity tab reads from. Null omits it. */
  connectivity?: ConnectivityFetchers | null;
  /** Where the panel's per-resource drill-down reads from. */
  detailFetcher?: (
    accountId: string,
    resourceId: string,
    period?: string,
    scope?: { service?: string; region?: string },
  ) => Promise<ResourceChange | null>;

  /*
   * ── Depth: the basis toggle and the reconciliation ledger ────────────────────────────────
   *
   * Two separate gates, not one. `capabilitiesFetcher === null` turns off the basis toggle and
   * `chargeTypesFetcher === null` turns off the ledger, because GCP and Azure want the first off
   * and the second on — a single gate over both would delete the reconciliation from the two
   * providers that are the only reason it is three-provider at all.
   */

  /**
   * Where the basis capability probe reads from. Null for a source with no such concept.
   *
   * <p>Gates the cost basis toggle, and that only. Unblended-versus-amortized is a CUR construct:
   * GCP's export has no equivalent, and Azure's Cost Management query already returns amortized
   * cost by its own choice, so a toggle there would be a control over a decision made upstream.
   */
  capabilitiesFetcher?:
    | ((accountId: string, period?: string) => Promise<ReportCapabilities | null>)
    | null;
  /** Where the charge-type ledger reads from. Null to omit the reconciliation entirely. */
  chargeTypesFetcher?:
    | ((accountId: string, period?: string, basis?: CostBasis) => Promise<ChargeTypeAmount[] | null>)
    | null;
  /**
   * Where one service's resources come from, scoped server-side.
   *
   * <p>Null keeps the account-wide fallback — `resourcesFetcher` plus the drawer's client-side
   * filter. Nullable rather than merely optional because `CostDetailTabs` defaults it to the AWS
   * endpoint, and a GCP project id or Azure subscription id sent there 404s, which would empty
   * every panel. Deliberately not defaulted *here*, so there is exactly one place the AWS default
   * is decided.
   */
  scopedResourcesFetcher?:
    | ((
        accountId: string,
        service: string,
        period?: string,
        basis?: CostBasis,
      ) => Promise<TopResources | null>)
    | null;
  /** What the reconciliation ledger calls the billing source. Defaults to AWS wording. */
  chargeTypesCopy?: ChargeTypeReconciliationCopy;
}

/** The last 12 months, newest first. */
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

/**
 * Prints this section alone, via the browser's own PDF export.
 *
 * Chosen over a PDF library because a bill runs to hundreds of rows across four nesting
 * levels: page breaks, repeated headers and long-table pagination all come free from the
 * print engine, where a generated PDF would need each hand-built. It also keeps the
 * on-screen styling rather than reimplementing it.
 *
 * <p>The card is marked with `data-printing` and everything else is hidden with
 * `display: none` — not `visibility: hidden`. Visibility toggling keeps the hidden nodes
 * in the layout, so the printed card still had to share the page with a full-height,
 * invisible app; combined with the reveal animation's `transform` on an ancestor (which
 * re-roots `position: absolute` and clips overflow), only the on-screen viewport made it
 * onto paper. Removing the other nodes from layout and neutralising every ancestor's
 * transform, overflow and height lets the full, expanded card flow across as many pages
 * as it needs.
 */
const PRINT_STYLE_ID = 'charges-print-style';
const PRINT_CSS = `
@media print {
  /* Hide everything, then reveal only the card and its ancestors. Ancestors must stay
     rendered so the card is not inside a display:none subtree. */
  body * { display: none !important; }
  html, body { background: #fff !important; }
  [data-charges-print-ancestor],
  [data-charges-print-ancestor] > #charges-by-service {
    display: block !important;
  }
  #charges-by-service, #charges-by-service * { display: revert !important; }
  #charges-by-service table { display: table !important; width: 100% !important; }
  #charges-by-service thead { display: table-header-group !important; }
  #charges-by-service tbody { display: table-row-group !important; }
  #charges-by-service tr { display: table-row !important; }
  #charges-by-service td, #charges-by-service th { display: table-cell !important; }

  /* Neutralise anything on an ancestor that would clip or re-root the card:
     the reveal animation's transform/opacity, scroll containers, fixed heights. */
  [data-charges-print-ancestor] {
    position: static !important;
    transform: none !important;
    opacity: 1 !important;
    overflow: visible !important;
    max-height: none !important;
    height: auto !important;
    filter: none !important;
    clip-path: none !important;
    margin: 0 !important;
    padding: 0 !important;
    width: auto !important;
  }

  #charges-by-service {
    border: none !important; box-shadow: none !important; padding: 0 !important;
    width: 100% !important;
  }
  /* Controls are meaningless on paper. */
  #charges-by-service .no-print { display: none !important; }
  /* Keep a service and its first rows together rather than orphaning a heading. */
  #charges-by-service tr { page-break-inside: avoid; }
  @page { margin: 14mm; }
}`;

// Money is formatted through useMoney inside the component, so the section follows whatever
// currency its billing source reports. See ./costCurrency for why this is no longer a constant.

/**
 * Usage quantities span a huge range — 5.033 Tag-Mo to 147,128,739 Requests — so a fixed
 * number of decimals is unreadable at one end and misleading at the other.
 */
const quantity = (v: number): string => {
  if (v === 0) return '0';
  const abs = Math.abs(v);
  if (abs >= 1000) return v.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (abs >= 1) return v.toLocaleString('en-US', { maximumFractionDigits: 3 });
  // Below 1, fewer decimals would render most values as "0.00".
  return v.toLocaleString('en-US', { maximumFractionDigits: 6 });
};

/**
 * CUR carries region codes; the invoice shows display names. Shared with the BillOps
 * invoice detail page, which lists these same lines.
 */
const regionLabel = awsRegionName;

interface UsageTypeNode {
  usageType: string;
  cost: number;
  lines: ServiceCharge[];
}
interface RegionNode {
  region: string;
  cost: number;
  usageTypes: UsageTypeNode[];
}
interface ServiceNode {
  service: string;
  cost: number;
  regions: RegionNode[];
}

const ChargesByServiceSection: React.FC<ChargesByServiceSectionProps> = ({
  accountId,
  heading,
  fetcher = fetchChargesByService,
  insightName = 'charges-by-service',
  currency = 'USD',
  copy,
  resourcesFetcher,
  resourcesCopy,
  resourcesInsightName = 'top-resources',
  labels = true,
  detailFetcher,
  connectivity,
  capabilitiesFetcher = fetchReportCapabilities,
  chargeTypesFetcher = fetchChargeTypes,
  scopedResourcesFetcher,
  chargeTypesCopy,
}) => {
  const text = { ...AWS_COPY, ...copy };
  const money = useMoneyIn(currency);
  const [open, setOpen] = useState<Set<string>>(new Set());
  /**
   * The service whose resource panel is open, or null.
   *
   * <p>The name and the total, not just the name: the panel's header shows the figure the reader
   * clicked while its own query is still in flight, and shows it as the number to reconcile
   * against afterwards. Its resource rows can legitimately add up to less — the report returns
   * only the costliest few hundred resources in a month — so the invoice figure has to come from
   * here, where it is known exactly, rather than be re-derived there.
   */
  const [openService, setOpenService] = useState<ServiceSelection | null>(null);
  /**
   * Whether a service row is a drill-down or just an expander.
   *
   * <p>Explicit `null` turns it off for a billing source that cannot attribute cost to resources;
   * `undefined` means "not specified", which takes the AWS default like every other fetcher here.
   */
  const resourcesEnabled = resourcesFetcher !== null;
  /** Whether the card offers a cost basis at all. AWS only — see the prop. */
  const basisEnabled = capabilitiesFetcher !== null;
  /** Whether the invoice total can be reconciled by charge type. All three providers can. */
  const ledgerEnabled = chargeTypesFetcher !== null;
  /**
   * Which cost the figures below are computed from.
   *
   * <p>Owned here beside the month, because both re-base the same table and both belong in the
   * header's control row.
   */
  const [basis, setBasis] = useState<CostBasis>('unblended');
  /**
   * True only for the instant the print dialog is capturing the card. It forces every row
   * to render expanded on paper without touching `open`, so the on-screen tree keeps
   * whatever the reader had opened — the PDF is fully expanded, the UI is not.
   */
  const [printing, setPrinting] = useState(false);

  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState<string>(months[0].value);
  const monthLabel = months.find((m) => m.value === period)?.label ?? '';
  const isCurrentMonth = period === months[0].value;

  /**
   * Prints the whole bill, fully expanded, without changing what is on screen.
   *
   * A collapsed section prints as a list of service totals, which is not what anyone asks
   * a bill for — so `printing` forces every row open for the capture. The reader's own
   * expand/collapse state (`open`) is never touched: the PDF is fully expanded, the page
   * they return to looks exactly as they left it.
   *
   * The card's ancestors are tagged with `data-charges-print-ancestor` so the print CSS
   * can un-clip them (the reveal animation puts a `transform` on one of them, which would
   * otherwise crop the output to a single screen). The tag and the flag are both cleared
   * once the dialog closes, whether the reader saved or cancelled.
   */
  const handleDownload = () => {
    if (!document.getElementById(PRINT_STYLE_ID)) {
      const style = document.createElement('style');
      style.id = PRINT_STYLE_ID;
      style.textContent = PRINT_CSS;
      document.head.appendChild(style);
    }

    const card = document.getElementById('charges-by-service');
    const tagged: HTMLElement[] = [];
    for (let el = card?.parentElement ?? null; el; el = el.parentElement) {
      el.setAttribute('data-charges-print-ancestor', '');
      tagged.push(el);
    }
    if (card) card.setAttribute('data-charges-print-ancestor', '');

    const cleanup = () => {
      setPrinting(false);
      for (const el of tagged) el.removeAttribute('data-charges-print-ancestor');
      card?.removeAttribute('data-charges-print-ancestor');
      window.removeEventListener('afterprint', cleanup);
    };
    window.addEventListener('afterprint', cleanup);

    setPrinting(true);
    // Two frames so React has painted the fully expanded tree before the dialog captures it.
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        window.print();
        // Fallback for browsers that do not fire `afterprint` reliably.
        setTimeout(cleanup, 0);
      }),
    );
  };

  /*
   * What this report can be asked. Scans no data — it reads the export's column list.
   *
   * <p>A thrown query is a *failed* probe, not a pending one. Collapsing the two would leave the
   * toggle saying "Checking…" for the life of the tab after a single dropped connection, and the
   * reader with no way to tell a transient failure from a report that genuinely cannot answer.
   */
  const {
    data: caps,
    isPending: capsPending,
    isError: capsError,
  } = useCurInsight(
    // Derived from insightName rather than hardcoded — the convention on every other key on this
    // card, and what stops two providers sharing one entry for the same account id.
    `${insightName}:capabilities`,
    basisEnabled ? accountId : null,
    period,
    // The query is disabled when there is no probe, so this is never called then — but a fetcher
    // that cannot be null is one fewer thing for a change to the gate above to break.
    capabilitiesFetcher ?? (() => Promise.resolve(null)),
  );
  const capabilities: ReportCapabilities | null | undefined = !basisEnabled
    ? undefined
    : capsPending
      ? undefined
      : capsError
        ? null
        : (caps ?? null);

  /*
   * The basis rides in the cache key, and the default basis deliberately carries no suffix.
   *
   * `CostDetailTabs` issues this same unblended query as its "is this report readable" probe, so a
   * bare `:unblended` suffix would stop the two sharing one request and double the scan on every
   * page visit. Without the suffix on the non-default basis, react-query's 30-minute staleTime
   * would serve unblended rows under the amortized heading.
   */
  const chargesKey = basis === 'unblended' ? insightName : `${insightName}:${basis}`;
  // Cached across mounts, so returning to this page does not re-scan the report. A
  // failure leaves `charges` null and the section hides itself, rather than showing an
  // error the reader can do nothing about.
  const { data, isPending, isError } = useCurInsight(chargesKey, accountId, period, (a, p) =>
    fetcher(a, p, basis),
  );
  const charges: ServiceCharge[] | null = isError ? null : (data ?? null);
  // Only the first load blocks. A background revalidation keeps the table on screen
  // instead of replacing it with a skeleton showing the same numbers.
  const loading = Boolean(accountId) && isPending;

  const tree: ServiceNode[] = useMemo(() => {
    if (!charges) return [];
    const services = new Map<string, Map<string, Map<string, ServiceCharge[]>>>();
    for (const c of charges) {
      const regions = services.get(c.service) ?? new Map();
      const usageTypes = regions.get(c.region) ?? new Map();
      const lines = usageTypes.get(c.usageType) ?? [];
      lines.push(c);
      usageTypes.set(c.usageType, lines);
      regions.set(c.region, usageTypes);
      services.set(c.service, regions);
    }

    const sum = (lines: ServiceCharge[]) => lines.reduce((s, l) => s + l.cost, 0);

    return [...services.entries()]
      .map(([service, regionMap]) => {
        const regions = [...regionMap.entries()]
          .map(([region, usageMap]) => {
            const usageTypes = [...usageMap.entries()]
              .map(([usageType, lines]) => ({ usageType, cost: sum(lines), lines }))
              .sort((a, b) => b.cost - a.cost);
            return {
              region,
              cost: usageTypes.reduce((s, u) => s + u.cost, 0),
              usageTypes,
            };
          })
          .sort((a, b) => b.cost - a.cost);
        return {
          service,
          cost: regions.reduce((s, r) => s + r.cost, 0),
          regions,
        };
      })
      .sort((a, b) => b.cost - a.cost);
  }, [charges]);

  if (!accountId) return null;

  const total = tree.reduce((s, n) => s + n.cost, 0);

  /*
   * Null is the one reason to render nothing: the billing source cannot answer for this account,
   * so there is no month worth offering and no table to put behind the selector.
   *
   * An empty month is not that, and used to be conflated with it for the current month. The
   * report was read and this month is genuinely empty — the normal state on the 1st, before the
   * new billing period has been delivered. Vanishing then took the month selector with it, which
   * is the one control that would have got the reader to a month with data in it.
   */
  /*
   * A failed basis must not take the card with it.
   *
   * The early return is right for the default basis — the billing source cannot answer for this
   * account, so there is nothing behind the card. It is wrong for a non-default one: a 422 from the
   * amortized guard, or an Athena timeout while the card is on that basis, also yields a null
   * payload, and returning null then removes the sub-tab control, the month selector *and* the
   * toggle — leaving no control on screen to get back to unblended, inside a card that
   * `CostDetailTabs` keeps rendering because its own probe is on the unblended key.
   */
  const basisFailed = basis !== 'unblended' && !loading && charges === null;
  if (!loading && charges === null && !basisFailed) return null;
  // Excludes basisFailed, or the card asserts "No charges found for September 2026" — a
  // measurement the report did not make.
  const isEmpty = !loading && !basisFailed && (charges?.length ?? 0) === 0;
  const toggle = (key: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  return (
    <div
      id="charges-by-service"
      className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6"
    >
      <div className="flex items-start justify-between gap-4 mb-4 flex-wrap">
        <div>
          {heading ?? <h3 className="text-[15px] font-bold text-black">Charges by service</h3>}
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-1">
            {monthLabel}
            {isCurrentMonth ? ' so far' : ''}
            {/* The basis belongs in the prose as well as in the control, because the control
                carries .no-print and this sentence is the only record of it on paper. The word is
                the wire value — the same word the active button shows — so there is no second
                string for the two to disagree about. */}
            {basisEnabled && basis !== 'unblended' ? `, ${basis}` : ''}, {text.subheading}
            {!isEmpty &&
              !basisFailed &&
              (resourcesEnabled
                ? ' Click a service for the resources behind it, or the arrow for its billed lines.'
                : ' Click to expand.')}
          </p>
          {/* Printed copies lose the account context otherwise. */}
          <p className="hidden print:block text-[11px] text-[rgba(0,0,0,0.5)] mt-1">
            Account {accountId} · {monthLabel}
          </p>
        </div>

        <div className="flex items-center gap-3 flex-wrap">
          {basisEnabled && (
            <CostBasisToggle value={basis} onChange={setBasis} capabilities={capabilities} />
          )}

          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
            aria-label="Billing month"
            className="no-print px-3 py-1.5 text-[12.5px] border border-[rgba(0,0,0,0.15)] rounded-lg bg-white focus:outline-none focus:ring-2 focus:ring-black/5"
          >
            {months.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>

          <button
            type="button"
            onClick={handleDownload}
            disabled={isEmpty || basisFailed}
            title="Expands every row, then opens your browser's print dialog — choose 'Save as PDF'"
            className="no-print inline-flex items-center gap-1.5 px-3 py-1.5 text-[12.5px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors disabled:opacity-40"
          >
            <Icon icon="mdi:file-pdf-box" width={15} />
            Download PDF
          </button>

          <div className="text-right flex-shrink-0">
            <p className="text-[11px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">Total</p>
            {/* `total` is a sum over zero rows in the basis-failed state, and a zero whose truth is
                "not available" is the one thing the house style forbids outright. */}
            <p
              className="text-[18px] font-bold text-black"
              aria-label={basisFailed ? 'Total not available on this basis' : undefined}
            >
              {basisFailed ? '—' : money(total)}
            </p>
          </div>
        </div>
      </div>

      {loading && (
        <p className="text-[12.5px] text-[rgba(0,0,0,0.4)] py-6 text-center">{text.loading}</p>
      )}

      {isEmpty && !loading && (
        <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            No charges found for {monthLabel}.{' '}
            {isCurrentMonth ? text.currentMonthHint : text.emptyHint}
          </div>
        </div>
      )}

      {/* The basis could not be read. The heading, month selector and toggle stay mounted above
          this, because the control that recovers from it is one of them. */}
      {basisFailed && (
        <div className="flex items-start gap-2.5 px-3.5 py-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            {text.basisFailedNote}
          </div>
        </div>
      )}

      {/* What the amortized basis changes about the table below, in one note rather than two. Not
          .no-print: on paper the quantity column is still beside the amount and the caveat holds. */}
      {!loading && !basisFailed && !isEmpty && basisEnabled && basis === 'amortized' && (
        <div className="flex items-start gap-2.5 px-3.5 py-3 mb-3 bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.07)] rounded-lg">
          <Icon
            icon="mdi:information-outline"
            width={15}
            className="text-[rgba(0,0,0,0.35)] flex-shrink-0 mt-0.5"
          />
          <div className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed">
            {text.amortizedNote}
          </div>
        </div>
      )}

      {!isEmpty && !loading && !basisFailed && (
      <div className="overflow-x-auto -mx-2">
        <table className="w-full min-w-[640px] text-[12.5px]">
          <tbody>
            {tree.map((svc) => {
              // `printing` forces every node open for the PDF; the live `open` set is untouched.
              const svcOpen = printing || open.has(svc.service);
              return (
                <React.Fragment key={svc.service}>
                  {/*
                    Level 1 — service.

                    Two actions on one row, which is deliberate and is why the chevron is its own
                    button. Clicking the row asks "which resources is this?" and opens the panel,
                    because that is the question a service total raises and it used to cost a tab
                    switch and a second drill-down to answer. Clicking the chevron asks "what was
                    billed?" and expands the invoice lines in place, which is the breakdown this
                    table exists to show and would have been lost if the row's only action became
                    the panel.
                  */}
                  <tr
                    className="border-t-2 border-[rgba(0,0,0,0.1)] cursor-pointer hover:bg-[rgba(0,0,0,0.02)]"
                    onClick={() =>
                      resourcesEnabled
                        ? setOpenService({ service: svc.service, cost: svc.cost })
                        : toggle(svc.service)
                    }
                    title={
                      resourcesEnabled
                        ? `See the resources billed to ${svc.service}`
                        : undefined
                    }
                  >
                    <td className="px-2 py-2.5 font-bold text-black">
                      <span className="inline-flex items-center gap-1.5">
                        <button
                          type="button"
                          onClick={(e) => {
                            // Otherwise the row's own handler runs too and the panel opens on top
                            // of the rows the reader was expanding.
                            e.stopPropagation();
                            toggle(svc.service);
                          }}
                          aria-expanded={svcOpen}
                          aria-label={`${svcOpen ? 'Collapse' : 'Expand'} billed lines for ${
                            svc.service
                          }`}
                          title={
                            svcOpen
                              ? 'Hide the billed lines for this service'
                              : 'Show the billed lines for this service'
                          }
                          className="-ml-1 w-5 h-5 inline-flex items-center justify-center rounded hover:bg-[rgba(0,0,0,0.08)] transition-colors"
                        >
                          <Icon
                            icon={svcOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                            width={14}
                            className="text-[rgba(0,0,0,0.35)]"
                          />
                        </button>

                        {/* The name is the button for the panel, not just text inside a clickable
                            row. The row's own handler is a convenience for the mouse — a `tr` is
                            not focusable and announces nothing, so without this the drill-down
                            would exist for pointer users only. The chevron beside it is the one
                            other control, which is why both are real buttons and the row is
                            neither. */}
                        {resourcesEnabled ? (
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              setOpenService({ service: svc.service, cost: svc.cost });
                            }}
                            // The name alone is the accessible name, which is right — but it
                            // reads as a link to somewhere, and this opens a panel over the
                            // page. Said rather than implied, so the chevron beside it (which
                            // expands in place) is distinguishable without clicking either.
                            aria-haspopup="dialog"
                            title={`See the resources billed to ${svc.service}`}
                            className="text-left font-bold text-black hover:underline decoration-[rgba(0,0,0,0.3)] underline-offset-2"
                          >
                            {svc.service}
                          </button>
                        ) : (
                          svc.service
                        )}
                      </span>
                    </td>
                    {/* The quantity column, which a service row has nothing to put in — so it
                        carries the hint for the panel instead of adding a fourth column that
                        would be empty on all three levels below. Decorative: the name beside it
                        is the actual control, and announcing this as well would give screen
                        readers two things to do where there is one. */}
                    <td className="px-2 py-2.5 text-right">
                      {resourcesEnabled && (
                        <span
                          aria-hidden="true"
                          className="no-print inline-flex items-center gap-1 text-[11px] font-medium text-[#4285F4] whitespace-nowrap"
                        >
                          Resources
                          <Icon icon="mdi:chevron-right" width={12} />
                        </span>
                      )}
                    </td>
                    <td className="px-2 py-2.5 text-right font-bold text-black whitespace-nowrap">
                      {money(svc.cost)}
                    </td>
                  </tr>

                  {svcOpen &&
                    svc.regions.map((rgn) => {
                      const rgnKey = `${svc.service}|${rgn.region}`;
                      const rgnOpen = printing || open.has(rgnKey);
                      return (
                        <React.Fragment key={rgnKey}>
                          {/* Level 2 — region */}
                          <tr
                            className="border-t border-[rgba(0,0,0,0.05)] cursor-pointer hover:bg-[rgba(0,0,0,0.02)]"
                            onClick={() => toggle(rgnKey)}
                          >
                            <td className="px-2 py-2 pl-7 font-semibold text-[rgba(0,0,0,0.8)]">
                              <span className="inline-flex items-center gap-1.5">
                                <Icon
                                  icon={rgnOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                                  width={12}
                                  className="text-[rgba(0,0,0,0.3)]"
                                />
                                {regionLabel(rgn.region)}
                              </span>
                            </td>
                            <td className="px-2 py-2 text-right" />
                            <td className="px-2 py-2 text-right font-semibold text-[rgba(0,0,0,0.8)] whitespace-nowrap">
                              {money(rgn.cost)}
                            </td>
                          </tr>

                          {rgnOpen &&
                            rgn.usageTypes.map((ut) => (
                              <React.Fragment key={`${rgnKey}|${ut.usageType}`}>
                                {/* Level 3 — usage type */}
                                <tr className="border-t border-[rgba(0,0,0,0.04)]">
                                  <td className="px-2 py-1.5 pl-14 font-medium text-[rgba(0,0,0,0.7)]">
                                    {ut.usageType || '—'}
                                  </td>
                                  <td className="px-2 py-1.5 text-right" />
                                  <td className="px-2 py-1.5 text-right font-medium text-[rgba(0,0,0,0.7)] whitespace-nowrap">
                                    {money(ut.cost)}
                                  </td>
                                </tr>

                                {/* Level 4 — the rate lines */}
                                {ut.lines.map((line, i) => (
                                  <tr
                                    key={`${rgnKey}|${ut.usageType}|${i}`}
                                    className="bg-[rgba(0,0,0,0.012)]"
                                  >
                                    <td className="px-2 py-1 pl-20 text-[11.5px] text-[rgba(0,0,0,0.55)]">
                                      {line.description}
                                    </td>
                                    <td className="px-2 py-1 text-right text-[11.5px] text-[rgba(0,0,0,0.55)] tabular-nums whitespace-nowrap">
                                      {quantity(line.quantity)}
                                      {line.unit && (
                                        <span className="text-[rgba(0,0,0,0.35)] ml-1">
                                          {line.unit}
                                        </span>
                                      )}
                                    </td>
                                    <td className="px-2 py-1 text-right text-[11.5px] text-[rgba(0,0,0,0.6)] tabular-nums whitespace-nowrap">
                                      {money(line.cost)}
                                    </td>
                                  </tr>
                                ))}
                              </React.Fragment>
                            ))}
                        </React.Fragment>
                      );
                    })}
                </React.Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      )}

      {!isEmpty && !loading && !basisFailed && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
          {/* The "this reconciles to your invoice" claim that used to open this sentence is gone:
              it is now demonstrable one click away, in the ledger below, so asserting it in prose
              would be redundant. */}
          Negative amounts are credits, refunds, or usage covered by a Savings Plan or Reserved
          Instance.
        </p>
      )}

      {/* Said once, under the table it applies to, rather than on every row — and not at all when
          there is no table above it to explain. Nothing renders while the probe is in flight. */}
      {!loading && !basisFailed && !isEmpty && capabilities?.unitAvailable === false && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2 leading-relaxed">
          {capabilities.unitDetail}
        </p>
      )}

      {/*
        The ledger that proves the total above adds up, in place of the footnote that used to
        assert it. Collapsed, and its query is not issued until opened.

        The `!loading` gate is the parent's rather than the component's, and that is not a style
        preference: the component passes a null accountId while collapsed, so react-query reports
        isPending for its entire collapsed lifetime and gating its trigger on that flag would make
        the disclosure permanently unopenable.
      */}
      {!loading && !basisFailed && ledgerEnabled && (
        <ChargeTypeReconciliation
          accountId={accountId}
          period={period}
          monthLabel={monthLabel}
          basis={basis}
          // The figure computed from the tree it sits under, so the ledger reconciles against what
          // is on screen rather than re-deriving it.
          invoiceTotal={total}
          fetcher={chargeTypesFetcher}
          insightName={`${insightName}:charge-types`}
          currency={currency}
          copy={chargeTypesCopy}
        />
      )}

      {/*
        The resources behind one service, over the top of this table.

        Rendered into a portal, so its position in this tree does not matter — but it lives here
        rather than on the page so this section owns which service is open, and closing it returns
        the reader to the row they clicked with the tree's expansion state intact. It takes the
        month from this card's own selector, which is why there is one month control on the card
        rather than the two the separate tabs used to have.
      */}
      {resourcesEnabled && (
        <ServiceResourcesDrawer
          selection={openService}
          accountId={accountId}
          period={period}
          monthLabel={monthLabel}
          isCurrentMonth={isCurrentMonth}
          onClose={() => setOpenService(null)}
          fetcher={resourcesFetcher ?? undefined}
          scopedFetcher={scopedResourcesFetcher}
          basis={basis}
          detailFetcher={detailFetcher}
          connectivity={connectivity}
          insightName={resourcesInsightName}
          currency={currency}
          labels={labels}
          copy={resourcesCopy}
        />
      )}
    </div>
  );
};

export default ChargesByServiceSection;
