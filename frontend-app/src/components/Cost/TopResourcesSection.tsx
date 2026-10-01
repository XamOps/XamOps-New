import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import {
  fetchTopResources,
  type ResourceChange,
  type ResourceCost,
  type TopResources,
} from '../../api/curInsightsApi';
import { CostCurrencyContext, useMoney, useMoneyIn } from './costCurrency';
import ResourceCostDrawer from './ResourceCostDrawer';
import { useCurInsight } from '../../hooks/useCurInsight';
import {
  invalidateResourceLabels,
  publishResourceLabels,
  setResourceLabels,
  useLabels,
  useResourceLabelMap,
} from '../../hooks/useResourceLabels';
import { removeLabel } from '../../services/resourceLabelsApi';
import type { ResourceLabel, ResourceLabelMap, ResourceRef } from '../../types/resourceLabels';
import Pagination from '../FinOps/Pagination';
import ResourceLabelCell from './ResourceLabelCell';

/**
 * What each individual resource cost — this bucket, this instance, this volume.
 *
 * <p>The Cost Explorer API cannot answer this. Its resource-level view covers EC2 alone and
 * retains fourteen days, so until now the product could say "S3 cost $2,527" and not which
 * bucket. Everything here comes from {@code line_item_resource_id}, which exists only
 * because the report was created with resource ids enabled.
 *
 * <p>Resources are grouped under their service and the groups start collapsed, matching the
 * drill-down in {@code ChargesByServiceSection}. A flat ranking put the top spender's
 * resources first and buried every other service below the fold — on the account this was
 * built against, ten Kiro rows pushed EC2 off the screen. The reader picks the service they
 * came to look at, then opens it.
 *
 * <p>Rows are labelled by the resource's {@code Name} tag where the report carries one — a
 * CUR only includes tags activated for cost allocation, so it often does not. Failing that
 * the label is the id's own tail, which is at least the part of an ARN that identifies the
 * thing. The full id stays on the row either way, because that is what gets pasted into a
 * console or a ticket.
 *
 * <p>Charges belonging to no resource — tax, support, subscriptions — are shown in their
 * own group rather than dropped. Hiding them would make this section's total disagree with
 * the invoice, and a total that does not reconcile is worse than no total. They are the one
 * thing here that cannot be labelled: with no resource id there is nothing to label.
 *
 * <h2>Labels</h2>
 * Each resource row carries the same XamOps labels the Cloud List assigns, through the same
 * endpoints and into the same table — so a label put on a bucket here is on that bucket
 * everywhere, and the reverse. This is the view where that matters most: the Cloud List can
 * say which resources belong to a team, and only this one can say what they cost.
 *
 * <p>The two pages do not agree by accident. A resource's id in this report is a bare id for
 * some services and a full ARN for others, while the Cloud List knows it by its short id
 * alone, so the server reconciles the two before storing anything — see
 * {@code ResourceIdResolver}. Labelling an RDS instance from here without that would write a
 * row under its ARN that the Cloud List, looking under its name, could never find.
 */

/**
 * The wording that names the billing source, and explains why it might not answer.
 *
 * <p>This section's unavailable state said "No Cost and Usage Report to read" and blamed Cost
 * Explorer for stopping at the service. Both sentences are AWS facts. On Azure there is no Cost and
 * Usage Report and no Cost Explorer — per-resource cost comes from a Cost Management query grouped by
 * {@code ResourceId}, and whether a subscription will answer that depends on the agreement behind it.
 * Telling an Azure customer to check a report they have never had sends them looking for something
 * that does not exist.
 *
 * <p>Overridable rather than branched on a provider enum, matching {@code DataTransferCopy}.
 */
export interface TopResourcesCopy {
  /** Headline when the source cannot answer. Rendered as "{title} for September 2026". */
  unavailableTitle?: string;
  /** What that means and what to do about it. */
  unavailableDetail?: React.ReactNode;
  /** Why a past month with a readable source can still hold no resource rows. */
  emptyHint?: React.ReactNode;
  /**
   * Why the month in progress can hold none — delivery lag rather than missing coverage.
   *
   * <p>Worth separating from {@code emptyHint}: "the report does not yet cover this month" is
   * true of a month before the report existed and false of the one currently open, where the
   * data is coming and simply has not arrived.
   */
  currentMonthHint?: React.ReactNode;
  /** Why rows fall back to ids instead of names, which is a different setting on each cloud. */
  noNamesHint?: React.ReactNode;
}

const AWS_COPY: Required<TopResourcesCopy> = {
  unavailableTitle: 'No Cost and Usage Report to read',
  unavailableDetail: (
    <>
      Per-resource cost comes from the report itself — Cost Explorer stops at the service. Either no
      report covers this month, or the one connected has not been delivered recently enough to trust.
    </>
  ),
  emptyHint: <>Either nothing was billed, or the report does not yet cover this month.</>,
  currentMonthHint: (
    <>
      A new billing month reads as empty until AWS delivers its first report, a few hours after the
      month opens. Pick last month for a complete one.
    </>
  ),
  noNamesHint: (
    <>
      Names come from each resource&apos;s <span className="font-mono">Name</span> tag, and this
      report carries none. A CUR only includes tags activated for cost allocation — activate{' '}
      <span className="font-mono">Name</span> in the payer account&apos;s billing console and it will
      appear here from the next report.
    </>
  ),
};

interface TopResourcesSectionProps {
  /** Single account or project with a usable billing source, or null to render nothing. */
  accountId: string | null;
  /** Defaults to the AWS Cost and Usage Report; GCP and Azure pass their own fetchers. */
  fetcher?: (accountId: string, period?: string) => Promise<TopResources | null>;
  /** What this section calls the billing source. Defaults to AWS wording. */
  copy?: TopResourcesCopy;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  /**
   * Whether rows offer the label control.
   *
   * <p>Off for GCP. Labels are stored against a resource id and reconciled through
   * {@code ResourceIdResolver}, which arbitrates using the AWS inventory table — so a GCP resource
   * id would resolve against nothing and the control would appear to work while writing rows no
   * page could read back. Better absent than silently wrong.
   */
  labels?: boolean;
  /**
   * Where the detail panel's payload comes from. Defaults to the AWS Cost and Usage Report; GCP and
   * Azure pass their own, which return the identical shape.
   */
  detailFetcher?: (
    accountId: string,
    resourceId: string,
    period?: string,
  ) => Promise<ResourceChange | null>;
  /**
   * Replaces this section's own `h3`.
   *
   * <p>Used to hoist the sub-tab control into the card header, so switching between this and
   * the invoice breakdown happens where the title is rather than in a strip above the card.
   * The control's active label reads as the heading, which is why this substitutes for the
   * title rather than sitting beside it.
   */
  heading?: React.ReactNode;
}

/** The last 12 months, newest first. Matches the other CUR sections. */
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

// Money is formatted through useMoney, which reads the section's currency from context so the
// nested service and resource rows need no extra prop. See ./costCurrency.

/**
 * Rows per page — for the service list and for the resources inside one open service.
 *
 * The same number for both, so paging never changes how much of the page moves.
 */
const PAGE_SIZE = 10;

/** Resources of one service, costliest first. */
interface ServiceGroup {
  service: string;
  cost: number;
  resources: ResourceCost[];
}

/**
 * An icon for the resource, chosen from its service.
 *
 * Service name rather than the id prefix, because ids are only self-describing for a
 * handful of types — `i-` and `vol-` are recognisable, an S3 key or a Lambda ARN is not.
 */
const iconFor = (service: string): string => {
  const s = service.toLowerCase();
  if (s.includes('elastic compute') || s.includes('ec2')) return 'mdi:server';
  if (s.includes('simple storage') || s.includes('s3')) return 'mdi:bucket-outline';
  if (s.includes('relational database') || s.includes('rds')) return 'mdi:database';
  if (s.includes('lambda')) return 'mdi:function-variant';
  if (s.includes('kubernetes') || s.includes('eks')) return 'mdi:kubernetes';
  if (s.includes('load balancing')) return 'mdi:scale-balance';
  if (s.includes('cloudwatch')) return 'mdi:chart-line';
  if (s.includes('dynamodb')) return 'mdi:table-large';
  if (s.includes('cloudfront')) return 'mdi:web';
  if (s.includes('virtual private cloud') || s.includes('vpc')) return 'mdi:lan';
  if (s.includes('registry') || s.includes('ecr')) return 'mdi:package-variant-closed';
  return 'mdi:cube-outline';
};

/**
 * Shortens a long id from the middle.
 *
 * The ends of an ARN carry the identity — the service at the front, the resource name at
 * the back. Truncating with a trailing ellipsis throws away the half that names the thing.
 */
const shorten = (id: string, max = 52): string => {
  if (id.length <= max) return id;
  const head = Math.ceil((max - 1) / 2);
  return `${id.slice(0, head)}…${id.slice(id.length - (max - head - 1))}`;
};

/**
 * The last segment of an ARN — what the console calls the resource.
 *
 * `arn:aws:aoss:ap-south-1:605…:account/SearchOCU` is unreadable at a glance and
 * "SearchOCU" is the part the reader came for. Falls back to the id unchanged when there
 * is no tail to take, which is the case for a bare id like `i-089ccf1b5a839880a`.
 */
const arnTail = (id: string): string => {
  const slash = id.lastIndexOf('/');
  if (slash >= 0 && slash < id.length - 1) return id.slice(slash + 1);
  // An ARN with no path still ends in its name, e.g. arn:aws:s3:::my-bucket.
  if (id.startsWith('arn:')) {
    const colon = id.lastIndexOf(':');
    if (colon >= 0 && colon < id.length - 1) return id.slice(colon + 1);
  }
  return id;
};

/**
 * The best label a resource has, and whether it is a real name.
 *
 * The `Name` tag when the report carries it — that is the thing the reader recognises.
 * Otherwise the id's own tail, which is an identifier rather than a name, and is rendered
 * as one.
 */
const labelFor = (r: ResourceCost): { text: string; isTag: boolean } =>
  r.name ? { text: r.name, isTag: true } : { text: arnTail(r.resourceId), isTag: false };

/**
 * A CUR row as the label API takes it.
 *
 * <p>The type is sent as the CUR's service name, which is a billing product rather than a
 * resource type, and the server replaces it with the inventory's own type for every resource
 * the inventory knows — so the two pages never store two vocabularies for one resource. It is
 * sent at all because the column is not nullable and a resource billed but not indexed has
 * nothing better.
 *
 * <p>{@code "Global"} is this section's stand-in for a row the report gave no region, not a
 * region code, so it is dropped rather than stored as one.
 */
const refFor = (r: ResourceCost): ResourceRef => ({
  resourceId: r.resourceId,
  resourceType: r.service,
  resourceName: r.name || undefined,
  region: r.region === 'Global' ? undefined : r.region,
});

/**
 * Everything a resource row needs to show and change its labels.
 *
 * <p>Bundled rather than passed as seven props, because they travel through the service group
 * unchanged — the group has no interest in labels beyond handing them down.
 */
interface Labelling {
  accountId: string;
  allLabels: ResourceLabel[];
  labelMap: ResourceLabelMap;
  onChanged: (resourceId: string, updated: ResourceLabel[]) => void;
  onLabelCreated: (label: ResourceLabel) => void;
  onRemove: (resource: ResourceCost, label: ResourceLabel) => void;
}

const NO_LABELS: ResourceLabel[] = [];
const NO_LABEL_MAP: ResourceLabelMap = {};

const TopResourcesSection: React.FC<TopResourcesSectionProps> = ({
  accountId,
  heading,
  fetcher = fetchTopResources,
  insightName = 'top-resources',
  currency = 'USD',
  labels = true,
  copy,
  detailFetcher,
}) => {
  const text = { ...AWS_COPY, ...copy };
  /*
   * `useMoneyIn(currency)`, not `useMoney()`. See the same note in DataTransferSection: this
   * component provides CostCurrencyContext inside its own return value and therefore cannot consume
   * it there, so the four totals it renders itself — the header total, the filtered total, the
   * account-level total and the account-level rows — were formatted in dollars while the grouped
   * resource rows beneath them, which are separate components inside the provider, were correct.
   */
  const money = useMoneyIn(currency);
  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);
  /** The month still in progress, whose emptiness means something different. See the copy type. */
  const isCurrentMonth = period === months[0].value;
  const [service, setService] = useState('all');
  const [region, setRegion] = useState('all');
  const [search, setSearch] = useState('');
  /**
   * The resource whose detail panel is open, or null.
   *
   * <p>The row, not just the id: the panel needs a label to show in its header while the payload is
   * still in flight, and the row already has one. A header that starts blank and fills in reads as a
   * loading fault rather than as loading.
   */
  const [detail, setDetail] = useState<ResourceCost | null>(null);
  const openDetail = useCallback((r: ResourceCost) => setDetail(r), []);
  const closeDetail = useCallback(() => setDetail(null), []);
  /** Services the reader has drilled into. */
  const [open, setOpen] = useState<Set<string>>(new Set());
  /** Which page of the service list is showing, 1-based. */
  const [page, setPage] = useState(1);
  /** Which page of resources each open service is showing, 1-based, keyed by service. */
  const [resourcePages, setResourcePages] = useState<Map<string, number>>(new Map());
  /**
   * Which page of account-level charges is showing, 1-based.
   *
   * Its own state because that list answers to the month alone. The service, region and
   * search controls filter resources, and a charge with no resource id is not something
   * they can narrow — so paging it is independent of them.
   */
  const [accountPage, setAccountPage] = useState(1);

  // Cached across mounts, so returning to this page does not re-scan the report.
  const { data, isPending, isError } = useCurInsight(insightName, accountId, period, fetcher);
  const result = isError ? null : (data ?? null);
  const loading = Boolean(accountId) && isPending;

  const all = useMemo(() => result?.resources ?? [], [result]);

  // Resources and account-level charges are different kinds of thing and are counted and
  // displayed separately; only the former is rankable.
  const resources = useMemo(() => all.filter((r) => r.resourceId !== ''), [all]);
  const accountLevel = useMemo(() => all.filter((r) => r.resourceId === ''), [all]);

  /*
   * ── Labels ────────────────────────────────────────────────────────────────────────────
   *
   * The same labels the Cloud List assigns, read and written through the same endpoints.
   * Only `resources` are offered the control: an account-level charge has no resource id,
   * so every one of them would collide on the same empty key.
   *
   * Requested for the whole month rather than the visible page, because the list is filtered
   * and paged client-side — fetching per page would refetch on every expand, sort and keystroke
   * and still show nothing until it landed. One request of at most a few hundred ids covers
   * every view of the month.
   */
  const resourceIds = useMemo(() => resources.map((r) => r.resourceId), [resources]);
  const { data: allLabelsData } = useLabels();
  const { data: labelMapData } = useResourceLabelMap(accountId, resourceIds);
  // Shared empties rather than fresh literals, so a pending query does not hand the rows a new
  // object on every render and invalidate every memo below it.
  const allLabels = allLabelsData ?? NO_LABELS;
  const labelMap = labelMapData ?? NO_LABEL_MAP;

  // The popover awaits its own assign or remove before reporting, so this is a completed
  // write and safe to confirm against the database straight away.
  const handleLabelChanged = useCallback(
    (resourceId: string, updated: ResourceLabel[]) =>
      publishResourceLabels(accountId, resourceId, updated),
    [accountId],
  );

  // A new label has to reach the picker's list, which is the labels query rather than this
  // component's state — so the create is published the same way an assignment is.
  const handleLabelCreated = useCallback(() => invalidateResourceLabels(), []);

  /**
   * Take a label off via the × on its chip.
   *
   * <p>The chip goes before the request, and comes back if the request fails — otherwise a
   * failed remove leaves the row looking changed while the database disagrees, and the next
   * refetch silently restores the chip with no explanation.
   *
   * <p>Confirmation waits for the call to return. Invalidating alongside the optimistic write
   * would race it: the refetch would read the row the delete had not yet removed and overwrite
   * the optimistic value with it.
   */
  const handleLabelRemoved = useCallback(
    async (resource: ResourceCost, label: ResourceLabel) => {
      if (!accountId) return;
      const before = labelMap[resource.resourceId] ?? [];
      setResourceLabels(
        accountId,
        resource.resourceId,
        before.filter((l) => l.id !== label.id),
      );
      try {
        await removeLabel({
          accountId,
          labelId: label.id,
          resources: [refFor(resource)],
        });
        invalidateResourceLabels();
      } catch (err) {
        console.error('[Labels] Remove failed, rolling back:', err);
        setResourceLabels(accountId, resource.resourceId, before);
      }
    },
    [accountId, labelMap],
  );

  const labelling: Labelling | null = useMemo(
    () =>
      accountId && labels
        ? {
            accountId,
            allLabels,
            labelMap,
            onChanged: handleLabelChanged,
            onLabelCreated: handleLabelCreated,
            onRemove: handleLabelRemoved,
          }
        : null,
    [accountId, labels, allLabels, labelMap, handleLabelChanged, handleLabelCreated,
      handleLabelRemoved],
  );

  const services = useMemo(
    () => Array.from(new Set(resources.map((r) => r.service))).sort(),
    [resources],
  );

  // Both option lists come from every resource rather than from what the other filter
  // leaves standing, so a selection can never go stale and leave a select showing a value
  // it no longer offers. A combination that matches nothing is handled by the empty state.
  const regions = useMemo(
    () => Array.from(new Set(resources.map((r) => r.region))).sort((a, b) => a.localeCompare(b)),
    [resources],
  );

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return resources.filter(
      (r) =>
        (service === 'all' || r.service === service) &&
        (region === 'all' || r.region === region) &&
        (term === '' ||
          r.resourceId.toLowerCase().includes(term) ||
          (r.name ?? '').toLowerCase().includes(term) ||
          r.instanceType.toLowerCase().includes(term)),
    );
  }, [resources, service, region, search]);

  // Grouped by service, costliest service first. The API returns resources already sorted
  // by cost, and grouping preserves that order, so each group is sorted too.
  const groups: ServiceGroup[] = useMemo(() => {
    const byService = new Map<string, ResourceCost[]>();
    for (const r of filtered) {
      const list = byService.get(r.service);
      if (list) list.push(r);
      else byService.set(r.service, [r]);
    }
    return [...byService.entries()]
      .map(([svc, list]) => ({
        service: svc,
        cost: list.reduce((sum, r) => sum + r.cost, 0),
        resources: list,
      }))
      .sort((a, b) => b.cost - a.cost);
  }, [filtered]);

  // The largest group in the current view, so the bars rescale when a filter narrows it —
  // against the global maximum every bar in a filtered list would be a sliver. Taken from
  // every group rather than the visible page, so a bar keeps its length as you page.
  const maxGroup = groups.length > 0 ? Math.max(...groups.map((g) => Math.abs(g.cost))) : 0;

  const totalPages = Math.max(1, Math.ceil(groups.length / PAGE_SIZE));
  // Clamped rather than trusted: the group list is rebuilt from the filters, and a page
  // number left over from a longer list would otherwise render an empty section.
  const currentPage = Math.min(page, totalPages);
  const visibleGroups = groups.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  /**
   * Which groups the current filters imply should be open:
   * a search opens every service that still has a match, because a match hidden inside a
   * collapsed group looks like no match at all; picking one service from the dropdown is
   * itself the drill-down; neither leaves the collapsed overview.
   *
   * <p>Depends on {@code filtered} rather than on the open set, so opening and closing a
   * group by hand afterwards sticks — this only re-runs when the filters or the month's
   * data actually change.
   */
  useEffect(() => {
    if (search.trim() !== '') setOpen(new Set(filtered.map((r) => r.service)));
    else if (service !== 'all') setOpen(new Set([service]));
    else setOpen(new Set());
  }, [search, service, filtered]);

  const toggle = (svc: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(svc)) next.delete(svc);
      else next.add(svc);
      return next;
    });

  const setResourcePage = (svc: string, next: number) =>
    setResourcePages((prev) => new Map(prev).set(svc, next));

  /**
   * Back to the first page of everything.
   *
   * A page number outlives the list it was counting: narrowing the filters shortens the
   * list, and page 3 of a list that now has one page would render as empty rather than as
   * the match the reader was looking for.
   */
  const resetPaging = () => {
    setPage(1);
    setResourcePages(new Map());
  };

  // A CUR only carries the tags that were activated for cost allocation, so "no names
  // anywhere" is a setting rather than a bug — and worth saying, since the reader is
  // otherwise left wondering why every row is an id.
  const anyNamed = resources.some((r) => Boolean(r.name));

  // Summed over every charge, not the visible page, so the heading total still reconciles
  // to the invoice while the list is paged.
  const accountLevelTotal = accountLevel.reduce((sum, r) => sum + r.cost, 0);
  const accountTotalPages = Math.max(1, Math.ceil(accountLevel.length / PAGE_SIZE));
  const accountCurrentPage = Math.min(accountPage, accountTotalPages);
  const visibleAccountLevel = accountLevel.slice(
    (accountCurrentPage - 1) * PAGE_SIZE,
    accountCurrentPage * PAGE_SIZE,
  );

  const isFiltered = service !== 'all' || region !== 'all' || search.trim() !== '';
  const filteredTotal = filtered.reduce((sum, r) => sum + r.cost, 0);

  // Nothing to scope a report to. Whether this account *has* a readable one is decided by
  // CostDetailTabs, which hides the whole card — including the sub-tab control that lives in
  // the heading below. This panel must not hide itself: doing so would delete that control and
  // strand the reader on a blank card with no way back to the invoice breakdown.
  if (!accountId) return null;

  const selectedLabel = months.find((m) => m.value === period)?.label ?? '';

  /*
   * The report did not answer for this month.
   *
   * Stated rather than hidden, for the reason above. It is a real case even on an account with
   * a working report: a CUR only covers months from its creation onward, so picking an earlier
   * one legitimately has nothing behind it.
   */
  if (!loading && result === null) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
        <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
          {heading ?? <h3 className="text-[15px] font-bold text-black">Charges by resource</h3>}
          <select
            value={period}
            onChange={(e) => {
              setPeriod(e.target.value);
              resetPaging();
              setAccountPage(1);
            }}
            aria-label="Billing month"
            className="text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg px-2.5 py-1.5 bg-white text-black focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
          >
            {months.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </div>
        <div className="text-center py-10 rounded-xl border border-dashed border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.015)]">
          <Icon
            icon="mdi:file-remove-outline"
            width={26}
            className="text-[rgba(0,0,0,0.2)] mx-auto mb-2"
          />
          <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
            {text.unavailableTitle} for {selectedLabel}
          </p>
          <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-[460px] mx-auto leading-relaxed">
            {text.unavailableDetail}
          </p>
        </div>
      </div>
    );
  }

  return (
    <CostCurrencyContext.Provider value={currency}>
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
        <div>
          {heading ?? <h3 className="text-[15px] font-bold text-black">Charges by resource</h3>}
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-1">
            What each individual resource cost — not just which service it belongs to.
            Click a service to see its resources.
          </p>
        </div>

        <div className="flex items-center gap-4">
          <select
            value={period}
            onChange={(e) => {
              setPeriod(e.target.value);
              resetPaging();
              setAccountPage(1);
            }}
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
            <p className="text-[18px] font-bold text-black leading-tight">
              {money(result?.totalCost ?? 0)}
            </p>
          </div>
        </div>
      </div>

      {loading ? (
        <div className="space-y-2">
          {[0, 1, 2, 3, 4].map((i) => (
            <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[42px] rounded-lg" />
          ))}
        </div>
      ) : all.length === 0 ? (
        <div className="text-center py-8">
          <Icon icon="mdi:cube-outline" width={26} className="text-[rgba(0,0,0,0.18)] mx-auto mb-2" />
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">No resource costs in {selectedLabel}.</p>
          <p className="text-[11.5px] text-[rgba(0,0,0,0.35)] mt-1">
            {isCurrentMonth ? text.currentMonthHint : text.emptyHint}
          </p>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2 mb-3">
            <select
              value={service}
              onChange={(e) => {
                setService(e.target.value);
                resetPaging();
              }}
              aria-label="Service"
              className="text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg px-2.5 py-1.5 bg-white text-black focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
            >
              <option value="all">All services</option>
              {services.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>

            {/* Region codes rather than display names, because the codes are what the rows
                show and what anyone scanning this list is matching against. */}
            <select
              value={region}
              onChange={(e) => {
                setRegion(e.target.value);
                resetPaging();
              }}
              aria-label="Region"
              className="text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg px-2.5 py-1.5 bg-white text-black focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
            >
              <option value="all">All regions</option>
              {regions.map((rg) => (
                <option key={rg} value={rg}>
                  {rg}
                </option>
              ))}
            </select>

            <div className="relative flex-1 min-w-[180px]">
              <Icon
                icon="mdi:magnify"
                width={14}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
              />
              <input
                type="text"
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  resetPaging();
                }}
                placeholder="Find a resource by name, id or instance type…"
                className="w-full text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg pl-7 pr-2.5 py-1.5 bg-white text-black placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
              />
            </div>

            {isFiltered && (
              <span className="text-[11.5px] text-[rgba(0,0,0,0.5)] whitespace-nowrap">
                {filtered.length} {filtered.length === 1 ? 'resource' : 'resources'} ·{' '}
                <span className="font-semibold text-black">{money(filteredTotal)}</span>
              </span>
            )}
          </div>

          {filtered.length === 0 ? (
            <p className="text-[12.5px] text-[rgba(0,0,0,0.45)] text-center py-6">
              Nothing matches that filter.
            </p>
          ) : (
            <>
              <div className="divide-y divide-[rgba(0,0,0,0.05)]">
                {visibleGroups.map((g) => (
                  <ServiceGroupRow
                    key={g.service}
                    group={g}
                    max={maxGroup}
                    open={open.has(g.service)}
                    page={resourcePages.get(g.service) ?? 1}
                    onToggle={() => toggle(g.service)}
                    onPageChange={(p) => setResourcePage(g.service, p)}
                    labelling={labelling}
                    onOpen={openDetail}
                  />
                ))}
              </div>
              {/* Counts services, not resources: this list's rows are services, and the
                  resource count for the filter is already stated above. */}
              <Pagination
                currentPage={currentPage}
                totalPages={totalPages}
                totalItems={groups.length}
                itemsPerPage={PAGE_SIZE}
                onPageChange={setPage}
              />
            </>
          )}

          {accountLevel.length > 0 && (
            <div className="mt-5 pt-4 border-t border-[rgba(0,0,0,0.08)]">
              <div className="flex items-baseline justify-between mb-2">
                <h4 className="text-[12.5px] font-semibold text-black">Account-level charges</h4>
                <span className="text-[13px] font-bold text-black">{money(accountLevelTotal)}</span>
              </div>
              <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-2">
                Billed to the account rather than to any one resource, so they have no id.
              </p>
              <div className="space-y-0.5">
                {visibleAccountLevel.map((r) => (
                  <div
                    key={`${r.service}-${r.region}`}
                    className="flex items-center justify-between px-2 py-1.5 text-[12.5px]"
                  >
                    <span className="text-[rgba(0,0,0,0.7)]">
                      {r.service}
                      {r.region !== 'Global' && (
                        <span className="text-[rgba(0,0,0,0.35)] ml-2">{r.region}</span>
                      )}
                    </span>
                    <span className="font-medium text-black">{money(r.cost)}</span>
                  </div>
                ))}
              </div>
              <Pagination
                currentPage={accountCurrentPage}
                totalPages={accountTotalPages}
                totalItems={accountLevel.length}
                itemsPerPage={PAGE_SIZE}
                onPageChange={setAccountPage}
              />
            </div>
          )}

          {!anyNamed && resources.length > 0 && (
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
              {text.noNamesHint}
            </p>
          )}

          {result && result.groupCount > all.length && (
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
              Showing the {all.length} costliest of {result.groupCount.toLocaleString('en-US')}{' '}
              resources. The total above covers all of them; each service total covers only
              the resources listed under it.
            </p>
          )}
        </>
      )}

      {/* Rendered into a portal, so its position in this tree does not matter — but it lives here
          rather than on the page so the section owns which resource is open, and closing it returns
          the reader to the row they clicked with the table's scroll, paging and filters intact. */}
      <ResourceCostDrawer
        resourceId={detail?.resourceId ?? null}
        resourceName={detail ? labelFor(detail).text : undefined}
        accountId={accountId}
        period={period}
        onClose={closeDetail}
        fetcher={detailFetcher}
        insightName={`${insightName}-detail`}
        currency={currency}
        unavailableDetail={text.unavailableDetail}
        // The row's own slice, so the panel's total matches the figure that was clicked. This table
        // groups by (resource, service, region), so the id alone is not the same question.
        scope={detail ? { service: detail.service, region: detail.region } : undefined}
      />
    </div>
    </CostCurrencyContext.Provider>
  );
};

/**
 * One service, collapsed to a total until it is opened.
 *
 * The group total is the sum of the resources listed under it. Where the report was
 * truncated (see the note at the foot of the section) that is the costliest slice of the
 * service rather than everything it spent — "Charges by service" is the number that
 * reconciles to the invoice.
 *
 * <p>An open service pages its resources {@link PAGE_SIZE} at a time. A service with fifty
 * of them would otherwise push every other service off the screen, which is the same
 * problem the grouping was there to solve.
 */
const ServiceGroupRow: React.FC<{
  group: ServiceGroup;
  max: number;
  open: boolean;
  page: number;
  onToggle: () => void;
  onPageChange: (page: number) => void;
  /** Null until an account is selected, which is also when nothing is labellable. */
  labelling: Labelling | null;
  /** Passed through to each row, which decides whether it is openable. */
  onOpen?: (r: ResourceCost) => void;
}> = ({ group, max, open, page, onToggle, onPageChange, labelling, onOpen }) => {
  const money = useMoney();
  const share = max > 0 ? (Math.abs(group.cost) / max) * 100 : 0;
  const totalPages = Math.max(1, Math.ceil(group.resources.length / PAGE_SIZE));
  const currentPage = Math.min(page, totalPages);
  const rows = group.resources.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);
  // Within an open service the bars rescale to that service, so a $26 OpenSearch resource
  // is not a sliver just because an unrelated service spent $200. Measured across the whole
  // service rather than the page, so a row keeps its length as the reader pages.
  const maxInGroup =
    group.resources.length > 0 ? Math.max(...group.resources.map((r) => Math.abs(r.cost))) : 0;

  return (
    <div>
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="w-full flex items-center gap-3 px-2 py-2 text-left rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors"
      >
        <Icon
          icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'}
          width={14}
          className="text-[rgba(0,0,0,0.35)] flex-shrink-0"
        />
        <Icon
          icon={iconFor(group.service)}
          width={16}
          className="text-[rgba(0,0,0,0.45)] flex-shrink-0"
        />

        <div className="flex-1 min-w-0">
          <p className="text-[12.5px] font-semibold text-black truncate">{group.service}</p>
          <p className="text-[10.5px] text-[rgba(0,0,0,0.4)]">
            {group.resources.length} {group.resources.length === 1 ? 'resource' : 'resources'}
          </p>
        </div>

        <div className="w-[70px] flex-shrink-0 hidden sm:block">
          <div className="h-1 bg-[rgba(0,0,0,0.05)] rounded-full overflow-hidden">
            <div className="h-full rounded-full bg-[#4285F4]" style={{ width: `${share}%` }} />
          </div>
        </div>

        <span className="text-[12.5px] font-bold text-black text-right w-[88px] flex-shrink-0">
          {money(group.cost)}
        </span>
      </button>

      {open && (
        <div className="ml-[15px] pl-3 border-l border-[rgba(0,0,0,0.07)] pb-1.5">
          {rows.map((r) => (
            <ResourceRow
              key={`${r.resourceId}-${r.region}`}
              r={r}
              max={maxInGroup}
              labelling={labelling}
              onOpen={onOpen}
            />
          ))}

          <Pagination
            currentPage={currentPage}
            totalPages={totalPages}
            totalItems={group.resources.length}
            itemsPerPage={PAGE_SIZE}
            onPageChange={onPageChange}
          />
        </div>
      )}
    </div>
  );
};

/** One resource, with a bar sized against the largest in its service. */
const ResourceRow: React.FC<{
  r: ResourceCost;
  max: number;
  labelling: Labelling | null;
  /** Opens the detail panel. Absent when the billing source cannot describe one resource. */
  onOpen?: (r: ResourceCost) => void;
}> = ({ r, max, labelling, onOpen }) => {
  const money = useMoney();
  const share = max > 0 ? (Math.abs(r.cost) / max) * 100 : 0;
  const label = labelFor(r);
  /*
   * Only rows that name a resource open the panel.
   *
   * The resource-less charges — tax, support, Marketplace purchases — are grouped here deliberately so
   * the section's total reconciles with the invoice, but there is no per-resource story to tell about
   * them, and a panel that opened onto "no detail available" would be a dead end the row advertised.
   */
  const openable = Boolean(onOpen) && r.resourceId !== '';

  return (
    <div
      className={`flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors ${
        openable ? 'cursor-pointer' : ''
      }`}
      /* The label control and its popover live inside this row, so a click there must not also open
         the panel. Anything inside an interactive element is treated as its own target. */
      onClick={
        openable
          ? (e) => {
              const target = e.target as HTMLElement;
              if (target.closest('button, a, input, select, [role="dialog"]')) return;
              onOpen?.(r);
            }
          : undefined
      }
      role={openable ? 'button' : undefined}
      tabIndex={openable ? 0 : undefined}
      onKeyDown={
        openable
          ? (e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onOpen?.(r);
              }
            }
          : undefined
      }
      title={openable ? 'Open cost detail for this resource' : undefined}
    >
      <div className="flex-1 min-w-0">
        <div className="flex items-baseline gap-2">
          {/* The full id on hover: the label is for scanning, not for copying into a
              console. A tagged name is prose and set as such; an id fragment stays
              monospaced, so the two are never mistaken for each other. */}
          <span
            title={r.resourceId}
            className={
              label.isTag
                ? 'text-[12px] font-medium text-black truncate'
                : 'font-mono text-[11.5px] text-black truncate'
            }
          >
            {shorten(label.text)}
          </span>
          {r.instanceType && (
            <span className="text-[10.5px] px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.6)] flex-shrink-0">
              {r.instanceType}
            </span>
          )}
        </div>
        {/* The service is on the group header above, so only the region is added here —
            and the id itself where the label above replaced it, since that is what gets
            pasted into a console or a ticket. */}
        <div className="flex items-center gap-2 mt-0.5 min-w-0">
          <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] flex-shrink-0">
            {r.region === 'Global' ? 'Global' : r.region}
          </span>
          {label.text !== r.resourceId && (
            <span
              title={r.resourceId}
              className="font-mono text-[10.5px] text-[rgba(0,0,0,0.35)] truncate"
            >
              {shorten(r.resourceId, 46)}
            </span>
          )}
        </div>
      </div>

      {/* Before the bar rather than after the amount: a label is something about the
          resource, and the bar and figure are one unit that reads as the row's answer. */}
      {labelling && (
        <div className="max-w-[45%] min-w-0">
          <ResourceLabelCell
            accountId={labelling.accountId}
            resource={refFor(r)}
            labels={labelling.labelMap[r.resourceId] ?? []}
            allLabels={labelling.allLabels}
            onChanged={labelling.onChanged}
            onLabelCreated={labelling.onLabelCreated}
            onRemove={(label) => labelling.onRemove(r, label)}
          />
        </div>
      )}

      <div className="w-[70px] flex-shrink-0 hidden sm:block">
        <div className="h-1 bg-[rgba(0,0,0,0.05)] rounded-full overflow-hidden">
          <div className="h-full rounded-full bg-[#4285F4]" style={{ width: `${share}%` }} />
        </div>
      </div>

      <span className="text-[12.5px] font-semibold text-black text-right w-[88px] flex-shrink-0">
        {money(r.cost)}
      </span>
    </div>
  );
};

export default TopResourcesSection;
