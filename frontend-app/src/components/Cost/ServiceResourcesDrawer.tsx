import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import {
  fetchTopResources,
  type CostBasis,
  type ResourceChange,
  type ResourceCost,
  type TopResources,
} from '../../api/curInsightsApi';
import { CostCurrencyContext, useMoney, useMoneyIn } from './costCurrency';
import ResourceCostDrawer from './ResourceCostDrawer';
import type { ConnectivityFetchers } from './ResourceConnectivityTab';
import { useCurInsight } from '../../hooks/useCurInsight';
import { shortenId } from '../../utils/shortenId';
import {
  invalidateResourceLabels,
  publishResourceLabels,
  setResourceLabels,
  useLabels,
  useResourceLabelMap,
} from '../../hooks/useResourceLabels';
import { removeLabel } from '../../services/resourceLabelsApi';
import type { ResourceLabel, ResourceLabelMap, ResourceRef } from '../../types/resourceLabels';
import { lockPageScroll } from '../../utils/scrollLock';
import Pagination from '../FinOps/Pagination';
import ResourceLabelCell from './ResourceLabelCell';

/**
 * What one service's resources cost — this bucket, this instance, this volume — over the invoice.
 *
 * <h2>Why this is a panel and no longer a tab</h2>
 * Per-resource cost used to be a sibling tab of the invoice breakdown: "Charges by service" and
 * "Charges by resource", side by side, each listing every service in the account. That put the
 * reader's actual question — *which* S3 bucket is the $2,527 — one tab switch and one re-drill-down
 * away from the figure that raised it. They would expand S3 in the invoice view, read the total,
 * switch tabs, find S3 again in a second list of services, and expand it again. Two lists of the
 * same services, two expansion states to keep in their head, and the number they came from no
 * longer on screen.
 *
 * <p>So the second list is gone and this panel took its place. The service row in the invoice *is*
 * the drill-down now: click it and its resources arrive over the top, in front of the figure they
 * add up to. Closing returns the reader to the row they clicked with the invoice tree exactly as
 * they left it, which a tab switch never did.
 *
 * <p>This also removes a question the two tabs could not answer together. Both read the same report
 * at the same grain and group on the same column — {@code product_product_name} on AWS,
 * {@code service.description} on GCP — so a service's name here is the same string as in the
 * invoice tree, and its resources genuinely belong to the total above them. Side by side, nothing
 * said so.
 *
 * <h2>What the report can and cannot say</h2>
 * Everything here comes from {@code line_item_resource_id}, which exists only because the report
 * was created with resource ids enabled. Cost Explorer cannot answer this at all: its
 * resource-level view covers EC2 alone and retains fourteen days.
 *
 * <p>Rows are labelled by the resource's {@code Name} tag where the report carries one — a CUR only
 * includes tags activated for cost allocation, so it often does not. Failing that the label is the
 * id's own tail, which is at least the part of an ARN that identifies the thing. The full id stays
 * on the row either way, because that is what gets pasted into a console or a ticket.
 *
 * <p>Charges belonging to no resource — tax, support, subscriptions — are listed in their own block
 * rather than dropped, so this panel's total can still be reconciled against the service total that
 * opened it. They are the one thing here that cannot be labelled: with no resource id there is
 * nothing to label.
 *
 * <h2>Labels</h2>
 * Each resource row carries the same XamOps labels the Cloud List assigns, through the same
 * endpoints and into the same table — so a label put on a bucket here is on that bucket everywhere,
 * and the reverse. This is the view where that matters most: the Cloud List can say which resources
 * belong to a team, and only this one can say what they cost.
 *
 * <p>The two pages do not agree by accident. A resource's id in this report is a bare id for some
 * services and a full ARN for others, while the Cloud List knows it by its short id alone, so the
 * server reconciles the two before storing anything — see {@code ResourceIdResolver}. Labelling an
 * RDS instance from here without that would write a row under its ARN that the Cloud List, looking
 * under its name, could never find.
 */

/**
 * The wording that names the billing source, and explains why it might not answer.
 *
 * <p>This panel's unavailable state said "No Cost and Usage Report to read" and blamed Cost Explorer
 * for stopping at the service. Both sentences are AWS facts. On Azure there is no Cost and Usage
 * Report and no Cost Explorer — per-resource cost comes from a Cost Management query grouped by
 * {@code ResourceId}, and whether a subscription will answer that depends on the agreement behind
 * it. Telling an Azure customer to check a report they have never had sends them looking for
 * something that does not exist.
 *
 * <p>Overridable rather than branched on a provider enum, matching {@code DataTransferCopy}.
 */
export interface ServiceResourcesCopy {
  /** Headline when the source cannot answer. Rendered as "{title} for September 2026". */
  unavailableTitle?: string;
  /** What that means and what to do about it. */
  unavailableDetail?: React.ReactNode;
  /** Why a past month with a readable source can still hold no resource rows. */
  emptyHint?: React.ReactNode;
  /**
   * Why the month in progress can hold none — delivery lag rather than missing coverage.
   *
   * <p>Worth separating from {@code emptyHint}: "the report does not yet cover this month" is true
   * of a month before the report existed and false of the one currently open, where the data is
   * coming and simply has not arrived.
   */
  currentMonthHint?: React.ReactNode;
  /** Why rows fall back to ids instead of names, which is a different setting on each cloud. */
  noNamesHint?: React.ReactNode;
}

const AWS_COPY: Required<ServiceResourcesCopy> = {
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

/** The service the panel is open on, and the invoice figure its rows should add up to. */
export interface ServiceSelection {
  /** As the billing source names it, so it matches the resource rows' own `service`. */
  service: string;
  /** The service's total from the invoice breakdown — every line, resource-bearing or not. */
  cost: number;
}

interface ServiceResourcesDrawerProps {
  /** The service to describe, or null when the panel is closed. */
  selection: ServiceSelection | null;
  /** Account, subscription or project the service was billed to. */
  accountId: string | null;
  /** Billing month, `YYYY-MM`, owned by the card's own month selector. */
  period: string;
  /** That month written out, for the panel's subheading. */
  monthLabel: string;
  /** Whether that month is still in progress, whose emptiness means something different. */
  isCurrentMonth: boolean;
  onClose: () => void;
  /** Defaults to the AWS Cost and Usage Report; GCP and Azure pass their own fetchers. */
  fetcher?: (accountId: string, period?: string) => Promise<TopResources | null>;
  /**
   * One service's resources, scoped server-side. When supplied this replaces the account-wide
   * fetcher above.
   *
   * <p>That is the whole point: the account-wide query returns the costliest 500 groups in the
   * account, so a service outside that had no rows at all and this panel apologised for resources
   * sitting in the report, queryable.
   *
   * <p>Null or absent keeps the account-wide path, which is what GCP and Azure use — neither
   * BigQuery's export nor Azure Cost Management has a scoped per-service equivalent.
   */
  scopedFetcher?:
    | ((
        accountId: string,
        service: string,
        period?: string,
        basis?: CostBasis,
      ) => Promise<TopResources | null>)
    | null;
  /** The basis the invoice figure was computed on, threaded into the scoped query. */
  basis?: CostBasis;
  /**
   * Where the per-resource detail panel's payload comes from. Defaults to the AWS Cost and Usage
   * Report; GCP and Azure pass their own, which return the identical shape.
   */
  detailFetcher?: (
    accountId: string,
    resourceId: string,
    period?: string,
    scope?: { service?: string; region?: string },
  ) => Promise<ResourceChange | null>;
  /** Cache-key discriminator, so two providers never share an entry for the same id. */
  insightName?: string;
  /** ISO currency code the billing source reports in. */
  currency?: string;
  /**
   * Where the per-resource panel's Connectivity tab reads from, or `null` to omit that tab.
   *
   * <p>A pass-through, like `detailFetcher`: configured by whichever page wires the card up, owned
   * by the panel the resource row opens.
   */
  connectivity?: ConnectivityFetchers | null;
  /**
   * Whether rows offer the label control.
   *
   * <p>Off for GCP. Labels are stored against a resource id and reconciled through
   * {@code ResourceIdResolver}, which arbitrates using the AWS inventory table — so a GCP resource
   * id would resolve against nothing and the control would appear to work while writing rows no
   * page could read back. Better absent than silently wrong.
   */
  labels?: boolean;
  /** What this panel calls the billing source. Defaults to AWS wording. */
  copy?: ServiceResourcesCopy;
}

/** Rows per page, for the resource list and for the resource-less charges below it. */
const PAGE_SIZE = 10;

/**
 * An icon for the service, chosen from its name.
 *
 * Service name rather than the id prefix, because ids are only self-describing for a handful of
 * types — `i-` and `vol-` are recognisable, an S3 key or a Lambda ARN is not.
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
 * Shortens a long id from the middle, at this panel's own budget.
 *
 * <p>72, not the canvas card's 34: this ran at 52 and 46 characters when the list sat in a 768px
 * panel, and widening the panel without raising them would have spent the new space on empty
 * gutter, because this cut lands well before the CSS `truncate` does and is therefore the binding
 * constraint. Still bounded rather than unlimited — the row has five other things on it, and an
 * ARN allowed to run full length pushes the amount off the end.
 */
const shorten = (id: string, max = 72): string => shortenId(id, max);

/**
 * The last segment of an ARN — what the console calls the resource.
 *
 * `arn:aws:aoss:ap-south-1:605…:account/SearchOCU` is unreadable at a glance and "SearchOCU" is the
 * part the reader came for. Falls back to the id unchanged when there is no tail to take, which is
 * the case for a bare id like `i-089ccf1b5a839880a`.
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
 * The `Name` tag when the report carries it — that is the thing the reader recognises. Otherwise the
 * id's own tail, which is an identifier rather than a name, and is rendered as one.
 */
const labelFor = (r: ResourceCost): { text: string; isTag: boolean } =>
  r.name ? { text: r.name, isTag: true } : { text: arnTail(r.resourceId), isTag: false };

/**
 * A CUR row as the label API takes it.
 *
 * <p>The type is sent as the CUR's service name, which is a billing product rather than a resource
 * type, and the server replaces it with the inventory's own type for every resource the inventory
 * knows — so the two pages never store two vocabularies for one resource. It is sent at all because
 * the column is not nullable and a resource billed but not indexed has nothing better.
 *
 * <p>{@code "Global"} is this panel's stand-in for a row the report gave no region, not a region
 * code, so it is dropped rather than stored as one.
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
 * <p>Bundled rather than passed as six props, because they travel to every row unchanged.
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

const ServiceResourcesDrawer: React.FC<ServiceResourcesDrawerProps> = ({
  selection,
  accountId,
  period,
  monthLabel,
  isCurrentMonth,
  onClose,
  fetcher = fetchTopResources,
  scopedFetcher,
  basis,
  detailFetcher,
  connectivity,
  insightName = 'top-resources',
  currency = 'USD',
  labels = true,
  copy,
}) => {
  const text = { ...AWS_COPY, ...copy };
  /*
   * `useMoneyIn(currency)`, not `useMoney()`. This component provides CostCurrencyContext inside its
   * own return value and therefore cannot consume it there — the totals it renders itself would be
   * formatted in dollars while the rows beneath them, which are separate components inside the
   * provider, were correct. Same note as in DataTransferSection.
   */
  const money = useMoneyIn(currency);

  const service = selection?.service ?? null;
  const open = service !== null;

  const [region, setRegion] = useState('all');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  /**
   * Which page of resource-less charges is showing, 1-based.
   *
   * Its own state because that list answers to the month alone. The region and search controls
   * filter resources, and a charge with no resource id is not something they can narrow.
   */
  const [chargePage, setChargePage] = useState(1);
  /**
   * The resource whose detail panel is open, or null.
   *
   * <p>The row, not just the id: the detail panel needs a label for its header while the payload is
   * still in flight, and the row already has one. A header that starts blank and fills in reads as a
   * loading fault rather than as loading.
   */
  const [detail, setDetail] = useState<ResourceCost | null>(null);
  const openDetail = useCallback((r: ResourceCost) => setDetail(r), []);
  const closeDetail = useCallback(() => setDetail(null), []);

  /*
   * A different service is a different list, so its filters and paging start fresh — a region left
   * selected from the last service would silently hide rows in this one, and page 3 of a longer list
   * renders as empty rather than as the resources the reader just asked for.
   *
   * <p>Only on open. Resetting as the panel closes would blank the content mid-exit-animation.
   */
  useEffect(() => {
    if (!service) {
      setDetail(null);
      return;
    }
    setRegion('all');
    setSearch('');
    setPage(1);
    setChargePage(1);
  }, [service]);

  /*
   * Escape closes the panel, unless the per-resource detail is open over it — then it belongs to
   * that one, which closes itself. Without the guard a single Escape dismissed both, so the reader
   * lost the list they were working through as well as the resource they had opened from it.
   */
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !detail) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, detail, onClose]);

  // Through the counted lock, because the detail panel opens over this one and holds it too.
  useEffect(() => (open ? lockPageScroll() : undefined), [open]);

  /*
   * One query, scoped to this service where the billing source can do that and account-wide where
   * it cannot.
   *
   * <p>On the scoped path the service IS the query, so it has to be in the key. The account-wide
   * path deliberately leaves it out — the report is read once and cut client-side, so opening six
   * services in turn costs one Athena scan rather than six — and a scoped query under that key
   * would serve the first service's rows for every service after it.
   *
   * <p>`accountId` is passed as null while closed, which is what keeps either query from firing
   * for a reader who never opens a service. react-query keeps the cached rows either way, so
   * reopening is instant. Branched inside one hook call rather than calling a hook conditionally.
   */
  const scoped = Boolean(scopedFetcher);
  const resourcesKey = scoped
    ? `${insightName}:svc:${service ?? ''}${basis === 'amortized' ? ':amortized' : ''}`
    : insightName;
  const { data, isPending, isError } = useCurInsight(
    resourcesKey,
    open ? accountId : null,
    period,
    scoped ? (a, p) => scopedFetcher!(a, service as string, p, basis) : fetcher,
  );
  const result = isError ? null : (data ?? null);
  const loading = open && Boolean(accountId) && isPending;

  /**
   * Every row the report attributed to this service, resource-bearing or not.
   *
   * <p>The filter stays unconditionally. On the scoped path every row already matches, so it is a
   * no-op; on the fallback path it is load-bearing. Keeping it removes a branch and cannot be wrong
   * in either mode — including for `service === 'Other'`, where the backend maps blanks to the same
   * word the client sent.
   */
  const all = useMemo(
    () => (service ? (result?.resources ?? []).filter((r) => r.service === service) : []),
    [result, service],
  );

  // Resources and resource-less charges are different kinds of thing, counted and displayed
  // separately; only the former is rankable or labellable.
  const resources = useMemo(() => all.filter((r) => r.resourceId !== ''), [all]);
  const charges = useMemo(() => all.filter((r) => r.resourceId === ''), [all]);

  /*
   * ── Labels ────────────────────────────────────────────────────────────────────────────
   *
   * The same labels the Cloud List assigns, read and written through the same endpoints. Only
   * `resources` are offered the control: a resource-less charge has no id, so every one of them
   * would collide on the same empty key.
   *
   * Requested for the whole service rather than the visible page, because the list is filtered and
   * paged client-side — fetching per page would refetch on every keystroke and still show nothing
   * until it landed.
   */
  const resourceIds = useMemo(() => resources.map((r) => r.resourceId), [resources]);
  const { data: allLabelsData } = useLabels();
  const { data: labelMapData } = useResourceLabelMap(labels ? accountId : null, resourceIds);
  // Shared empties rather than fresh literals, so a pending query does not hand the rows a new
  // object on every render and invalidate every memo below it.
  const allLabels = allLabelsData ?? NO_LABELS;
  const labelMap = labelMapData ?? NO_LABEL_MAP;

  // The popover awaits its own assign or remove before reporting, so this is a completed write and
  // safe to confirm against the database straight away.
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
   * <p>The chip goes before the request, and comes back if the request fails — otherwise a failed
   * remove leaves the row looking changed while the database disagrees, and the next refetch
   * silently restores the chip with no explanation.
   *
   * <p>Confirmation waits for the call to return. Invalidating alongside the optimistic write would
   * race it: the refetch would read the row the delete had not yet removed and overwrite the
   * optimistic value with it.
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
        await removeLabel({ accountId, labelId: label.id, resources: [refFor(resource)] });
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
    [
      accountId,
      labels,
      allLabels,
      labelMap,
      handleLabelChanged,
      handleLabelCreated,
      handleLabelRemoved,
    ],
  );

  // From every resource in the service rather than from what the search leaves standing, so a
  // selection can never go stale and leave the control showing a region it no longer offers. A
  // combination that matches nothing is handled by the empty state.
  const regions = useMemo(
    () => Array.from(new Set(resources.map((r) => r.region))).sort((a, b) => a.localeCompare(b)),
    [resources],
  );

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return resources.filter(
      (r) =>
        (region === 'all' || r.region === region) &&
        (term === '' ||
          r.resourceId.toLowerCase().includes(term) ||
          (r.name ?? '').toLowerCase().includes(term) ||
          r.instanceType.toLowerCase().includes(term)),
    );
  }, [resources, region, search]);

  // Rescaled to the current view, so the bars still differentiate when a filter narrows it —
  // against the whole account's maximum every bar in one service would be a sliver. Measured across
  // the filtered list rather than the visible page, so a row keeps its length as the reader pages.
  const maxCost = filtered.length > 0 ? Math.max(...filtered.map((r) => Math.abs(r.cost))) : 0;

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  // Clamped rather than trusted: the list is rebuilt from the filters, and a page number left over
  // from a longer one would otherwise render an empty panel.
  const currentPage = Math.min(page, totalPages);
  const visible = filtered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE);

  const chargeTotalPages = Math.max(1, Math.ceil(charges.length / PAGE_SIZE));
  const chargeCurrentPage = Math.min(chargePage, chargeTotalPages);
  const visibleCharges = charges.slice(
    (chargeCurrentPage - 1) * PAGE_SIZE,
    chargeCurrentPage * PAGE_SIZE,
  );

  const isFiltered = region !== 'all' || search.trim() !== '';
  const filteredTotal = filtered.reduce((sum, r) => sum + r.cost, 0);
  const chargesTotal = charges.reduce((sum, r) => sum + r.cost, 0);
  /** What the rows in this panel account for, before any filter narrows them. */
  const listedTotal = all.reduce((sum, r) => sum + r.cost, 0);
  const invoiceTotal = selection?.cost ?? listedTotal;

  // A CUR only carries the tags that were activated for cost allocation, so "no names anywhere" is a
  // setting rather than a bug — and worth saying, since the reader is otherwise left wondering why
  // every row is an id.
  const anyNamed = resources.some((r) => Boolean(r.name));

  /**
   * How much of the service total the rows below do not explain.
   *
   * <p>Two things cause a gap, and both are worth admitting rather than leaving the reader to
   * subtract. The report returns only the costliest 500 resource groups in the month, so a service
   * low down the bill can be partly or entirely absent from it. And the invoice total includes line
   * types that group by service but carry no resource id at all.
   *
   * <p>One cent of tolerance, because these are two separately rounded sums of the same underlying
   * lines and a floating-point tail is not a discrepancy.
   */
  const unexplained = invoiceTotal - listedTotal;
  const truncated = Boolean(result && result.groupCount > (result.resources?.length ?? 0));
  /*
   * Silent on the scoped path when the rows are capped, because the note above already accounts for
   * the same money — exactly, from the payload's own window totals. Two explanations for one figure
   * is worse than one, and the second would be false: a truncated tail is the resources past the
   * cap, not the sub-cent invoice groups this sentence names.
   */
  const hasGap = Math.abs(unexplained) >= 0.01 && !(scoped && truncated);

  return createPortal(
    <AnimatePresence>
      {open && selection && (
        <React.Fragment key="service-resources-drawer">
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            className="fixed inset-0 bg-black/30 z-50"
            onClick={onClose}
          />
          <motion.aside
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ duration: 0.2, ease: 'easeInOut' }}
            role="dialog"
            aria-label={`Resources billed to ${selection.service}`}
            /*
             * Sized to the viewport rather than to a fixed breakpoint width.
             *
             * <p>A flat `max-w-3xl` was 768px on every screen, which on a wide monitor left the
             * rows crammed into a third of the glass while two thirds of it sat empty behind the
             * backdrop — and these rows are wide by nature: a name, an ARN, an instance type, a
             * label chip, a bar and an amount, six columns competing for the same line.
             *
             * <p>Still capped, because `vw` alone keeps growing: past roughly 1280px the row
             * content stops gaining anything and the panel starts reading as a page rather than
             * as something over one. Full width below `sm`, where there is no room to show
             * context behind it anyway.
             */
            className="fixed right-0 top-0 h-full w-full sm:w-[78vw] sm:max-w-[1280px] bg-white shadow-2xl z-50 flex flex-col"
          >
            {/* Header */}
            <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.08)] flex items-start justify-between gap-4">
              <div className="min-w-0 flex items-start gap-3">
                <span className="w-9 h-9 flex-shrink-0 flex items-center justify-center rounded-lg bg-[rgba(66,133,244,0.08)]">
                  <Icon
                    icon={iconFor(selection.service)}
                    width={18}
                    className="text-[#4285F4]"
                  />
                </span>
                <div className="min-w-0">
                  <p
                    className="text-[15px] font-bold text-black truncate"
                    title={selection.service}
                  >
                    {selection.service}
                  </p>
                  <p className="text-[11.5px] text-[rgba(0,0,0,0.45)] mt-0.5">
                    {monthLabel}
                    {isCurrentMonth ? ' so far' : ''} ·{' '}
                    {loading
                      ? 'reading the billing source…'
                      : `${resources.length.toLocaleString('en-US')} ${
                          resources.length === 1 ? 'resource' : 'resources'
                        }`}
                  </p>
                </div>
              </div>

              <div className="flex items-start gap-4 flex-shrink-0">
                <div className="text-right">
                  <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] uppercase tracking-wide">
                    Total
                  </p>
                  <p className="text-[17px] font-bold text-black leading-tight">
                    {money(invoiceTotal)}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={onClose}
                  aria-label="Close"
                  className="w-8 h-8 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.04)] transition-colors"
                >
                  <Icon icon="mdi:close" width={15} />
                </button>
              </div>
            </div>

            <CostCurrencyContext.Provider value={currency}>
              <div className="flex-1 overflow-y-auto px-5 py-4">
                {loading && (
                  <div className="space-y-2">
                    {[0, 1, 2, 3, 4, 5].map((i) => (
                      <div
                        key={i}
                        className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[44px] rounded-lg"
                      />
                    ))}
                  </div>
                )}

                {/* Cannot answer at all — distinct from "this service has no resource rows". The
                    reader clicked a service in a table that worked, so an unexplained blank panel
                    would read as a fault. */}
                {!loading && result === null && (
                  <div className="text-center py-12">
                    <Icon
                      icon="mdi:file-remove-outline"
                      width={26}
                      className="text-[rgba(0,0,0,0.2)] mx-auto mb-2"
                    />
                    <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.6)]">
                      {text.unavailableTitle} for {monthLabel}
                    </p>
                    <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-[460px] mx-auto leading-relaxed">
                      {text.unavailableDetail}
                    </p>
                  </div>
                )}

                {/* Read, but nothing in it for this service. Usually the 500-row cap rather than an
                    empty month, and those are different enough to say which. */}
                {!loading && result !== null && all.length === 0 && (
                  <div className="text-center py-12">
                    <Icon
                      icon="mdi:cube-outline"
                      width={26}
                      className="text-[rgba(0,0,0,0.18)] mx-auto mb-2"
                    />
                    <p className="text-[13px] text-[rgba(0,0,0,0.55)]">
                      No resource-level cost for {selection.service} in {monthLabel}.
                    </p>
                    <p className="text-[11.5px] text-[rgba(0,0,0,0.4)] mt-1 max-w-[460px] mx-auto leading-relaxed">
                      {/* The "not among the costliest N" apology is false on the scoped path: the
                          query asked about this service specifically, so an empty result means the
                          service genuinely has no resource-level rows. It is kept on the fallback
                          path, where it is true and is the usual reason. */}
                      {truncated && !scoped ? (
                        <>
                          The billing source returns the{' '}
                          {(result.resources?.length ?? 0).toLocaleString('en-US')} costliest
                          resources of {result.groupCount.toLocaleString('en-US')} in this month, and
                          none of this service&apos;s fall inside that. Its{' '}
                          {money(invoiceTotal)} is still on the invoice breakdown behind this panel.
                        </>
                      ) : isCurrentMonth ? (
                        text.currentMonthHint
                      ) : (
                        text.emptyHint
                      )}
                    </p>
                  </div>
                )}

                {!loading && result !== null && all.length > 0 && (
                  <>
                    {/* Filters. Offered only once the list is long enough for them to beat reading
                        it, and the region control only when there is more than one region to pick
                        — a select with a single option is a control that cannot do anything. */}
                    {resources.length > PAGE_SIZE && (
                      <div className="flex flex-wrap items-center gap-2 mb-3">
                        {regions.length > 1 && (
                          /* Region codes rather than display names, because the codes are what the
                             rows show and what anyone scanning this list is matching against. */
                          <select
                            value={region}
                            onChange={(e) => {
                              setRegion(e.target.value);
                              setPage(1);
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
                        )}

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
                              setPage(1);
                            }}
                            placeholder="Find a resource by name, id or instance type…"
                            className="w-full text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg pl-7 pr-2.5 py-1.5 bg-white text-black placeholder:text-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
                          />
                        </div>

                        {isFiltered && (
                          <span className="text-[11.5px] text-[rgba(0,0,0,0.5)] whitespace-nowrap">
                            {filtered.length} {filtered.length === 1 ? 'resource' : 'resources'} ·{' '}
                            <span className="font-semibold text-black">
                              {money(filteredTotal)}
                            </span>
                          </span>
                        )}
                      </div>
                    )}

                    {resources.length > 0 && filtered.length === 0 ? (
                      <p className="text-[12.5px] text-[rgba(0,0,0,0.45)] text-center py-6">
                        Nothing matches that filter.
                      </p>
                    ) : (
                      filtered.length > 0 && (
                        <>
                          <div className="divide-y divide-[rgba(0,0,0,0.05)]">
                            {visible.map((r) => (
                              <ResourceRow
                                key={`${r.resourceId}-${r.region}`}
                                r={r}
                                max={maxCost}
                                labelling={labelling}
                                onOpen={openDetail}
                              />
                            ))}
                          </div>
                          <Pagination
                            currentPage={currentPage}
                            totalPages={totalPages}
                            totalItems={filtered.length}
                            itemsPerPage={PAGE_SIZE}
                            onPageChange={setPage}
                          />
                        </>
                      )
                    )}

                    {/* Listed rather than dropped, so this panel can be reconciled against the
                        service total in the header. Not labellable and not openable: with no
                        resource id there is nothing to label and no per-resource story to tell. */}
                    {charges.length > 0 && (
                      <div className="mt-5 pt-4 border-t border-[rgba(0,0,0,0.08)]">
                        <div className="flex items-baseline justify-between mb-1">
                          <h4 className="text-[12.5px] font-semibold text-black">
                            Charges with no resource
                          </h4>
                          <span className="text-[13px] font-bold text-black">
                            {money(chargesTotal)}
                          </span>
                        </div>
                        <p className="text-[11px] text-[rgba(0,0,0,0.4)] mb-2">
                          Billed to the account rather than to any one resource — tax, support,
                          subscriptions — so they carry no id.
                        </p>
                        <div className="space-y-0.5">
                          {visibleCharges.map((r) => (
                            <div
                              key={`${r.service}-${r.region}`}
                              className="flex items-center justify-between px-2 py-1.5 text-[12.5px]"
                            >
                              <span className="text-[rgba(0,0,0,0.7)]">
                                {r.region === 'Global' ? 'Global' : r.region}
                              </span>
                              <span className="font-medium text-black">{money(r.cost)}</span>
                            </div>
                          ))}
                        </div>
                        <Pagination
                          currentPage={chargeCurrentPage}
                          totalPages={chargeTotalPages}
                          totalItems={charges.length}
                          itemsPerPage={PAGE_SIZE}
                          onPageChange={setChargePage}
                        />
                      </div>
                    )}

                    {!anyNamed && resources.length > 0 && (
                      <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
                        {text.noNamesHint}
                      </p>
                    )}

                    {/* The one truncation case that is real on the scoped path: a single service
                        with more resources than the per-service cap. The count comes from the
                        payload's own rows and the tail from its unpartitioned window totals, so
                        both figures are exact rather than inferred. */}
                    {scoped && truncated && (
                      <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
                        Showing the {(result.resources?.length ?? 0).toLocaleString('en-US')}{' '}
                        costliest of {result.groupCount.toLocaleString('en-US')} resources billed to
                        this service. The rest account for {money(result.totalCost - listedTotal)}.
                      </p>
                    )}

                    {/* Stated rather than left as arithmetic the reader has to do. The header shows
                        the invoice figure, which is the one that reconciles; these rows can cover
                        less of it, and silently disagreeing with a total on the same screen is
                        worse than explaining the gap.

                        Not rendered at all on a truncated scoped payload: `hasGap` excludes that
                        case, because the note directly above has already explained the same
                        difference and explained it correctly. */}
                    {hasGap && (
                      <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
                        The rows above account for {money(listedTotal)} of this service&apos;s{' '}
                        {money(invoiceTotal)}.{' '}
                        {scoped
                          ? /* Scoped and not truncated, so every resource group billed to this
                               service is on screen and the mechanism is known precisely. "Spend
                               attributed to no resource" would be wrong here: those charges are
                               listed in their own block and are counted in listedTotal. */
                            `The remaining ${money(
                              unexplained,
                            )} is billed lines the invoice view drops because they net to less than a tenth of a cent; this list drops nothing.`
                          : truncated
                            ? `The remaining ${money(
                                unexplained,
                              )} is spend the billing source attributes to no resource, or resources outside the ${(
                                result.resources?.length ?? 0
                              ).toLocaleString('en-US')} costliest it returns for a month.`
                            : `The remaining ${money(
                                unexplained,
                              )} is spend the billing source attributes to no resource.`}
                      </p>
                    )}
                  </>
                )}
              </div>
            </CostCurrencyContext.Provider>
          </motion.aside>

          {/*
            One resource, in depth, over this panel. Rendered into its own portal and stacked above
            at z-[60], so the list stays behind it and closing the detail returns the reader to the
            row they clicked with this panel's filters and paging intact.
          */}
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
            // The row's own slice, so the panel's total matches the figure that was clicked. The
            // report groups by (resource, service, region), so the id alone is not the same
            // question.
            scope={detail ? { service: detail.service, region: detail.region } : undefined}
            // That panel stays on unblended cost whatever this one is on — cost ÷ quantity is only
            // a real unit rate there. These two props let it say so rather than leaving the reader
            // to find the discrepancy by subtracting.
            basis={basis}
            rowCost={detail?.cost}
            connectivity={connectivity}
          />
        </React.Fragment>
      )}
    </AnimatePresence>,
    document.body,
  );
};

/** One resource, with a bar sized against the largest in the current view. */
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
  const openable = Boolean(onOpen) && r.resourceId !== '';

  return (
    <div
      className={`flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors ${
        openable ? 'cursor-pointer' : ''
      }`}
      /*
       * The label control and its popover live inside this row, so a click there must not also
       * open the detail. Anything inside an interactive element is treated as its own target.
       *
       * <p>The containment check is what makes that correct here rather than merely tidy.
       * `closest` walks all the way to the document, and this row now sits inside a panel that
       * is itself `role="dialog"` — so an unbounded lookup matched that ancestor for *every*
       * click and swallowed all of them, leaving the rows dead. The row is the boundary.
       */
      onClick={
        openable
          ? (e) => {
              const target = e.target as HTMLElement;
              const control = target.closest('button, a, input, select, [role="dialog"]');
              if (control && e.currentTarget.contains(control)) return;
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
          {/* The full id on hover: the label is for scanning, not for copying into a console. A
              tagged name is prose and set as such; an id fragment stays monospaced, so the two are
              never mistaken for each other. */}
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
        {/* The service is in the panel header, so only the region is added here — and the id itself
            where the label above replaced it, since that is what gets pasted into a console or a
            ticket. */}
        <div className="flex items-center gap-2 mt-0.5 min-w-0">
          <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] flex-shrink-0">
            {r.region === 'Global' ? 'Global' : r.region}
          </span>
          {label.text !== r.resourceId && (
            <span
              title={r.resourceId}
              className="font-mono text-[10.5px] text-[rgba(0,0,0,0.35)] truncate"
            >
              {shorten(r.resourceId, 64)}
            </span>
          )}
        </div>
      </div>

      {/* Before the bar rather than after the amount: a label is something about the resource, and
          the bar and figure are one unit that reads as the row's answer. */}
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

export default ServiceResourcesDrawer;
