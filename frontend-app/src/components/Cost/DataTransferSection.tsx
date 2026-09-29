import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchDataTransferCosts } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import { buildGroups, type Group, type Resource, type Route } from './dataTransferModel';

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
 * the resource, the route and the price, which is the default view here. The rolled-up
 * views answer the two coarser questions ("which route costs most", "which resource costs
 * most") off the same data.
 *
 * <p>The limit is worth stating plainly, because the obvious next question runs into it: a
 * billing line names one resource, the one being charged, so the far end of a flow is a
 * region or the internet and never a peer resource id. Naming both ends needs VPC Flow
 * Logs. The section says so rather than leaving a blank that reads as a bug.
 */

interface DataTransferSectionProps {
  /** Single AWS account with a report, or null to render nothing. */
  accountId: string | null;
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

const money = (v: number): string =>
  '$' + v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const amount = (v: number): string =>
  v.toLocaleString('en-US', {
    minimumFractionDigits: v < 10 ? 2 : 0,
    maximumFractionDigits: v < 10 ? 2 : 0,
  });

const quantity = (v: number, unit: string): string => (unit ? `${amount(v)} ${unit}` : amount(v));

/** GB and hours side by side, never summed. Em dash when a row has neither. */
const usageLabel = (gigabytes: number, hours: number): string => {
  const parts: string[] = [];
  if (gigabytes > 0) parts.push(`${amount(gigabytes)} GB`);
  if (hours > 0) parts.push(`${amount(hours)} hrs`);
  return parts.length > 0 ? parts.join(' + ') : '—';
};

/**
 * "Asia Pacific (Mumbai)" → "Mumbai".
 *
 * A route is read as a sentence, and two full AWS location names do not fit on one line.
 * The city in parentheses is the part that identifies it; anything without parentheses — a
 * region code, "Internet", a CloudFront edge — is already short and passes through. The
 * untruncated value stays in the row's `title`.
 */
const shortLocation = (value: string): string => {
  const parenthesised = value.match(/\(([^)]+)\)\s*$/);
  return parenthesised ? parenthesised[1] : value;
};

/**
 * The readable end of a resource id.
 *
 * ARNs are long and their distinguishing part is at the end, so an untruncated list of them
 * is a column of identical prefixes. The full id stays in the row's `title`.
 */
const shortResource = (resourceId: string): string => {
  if (!resourceId) return 'Not attributed to a resource';
  if (!resourceId.startsWith('arn:')) return resourceId;
  const tail = resourceId.split(/[:/]/).filter(Boolean).pop();
  return tail || resourceId;
};

/** True when a route's two ends are the same place, so no arrow should be drawn. */
const isWithinOneLocation = (from: string, to: string): boolean =>
  Boolean(from) && from === to;

/**
 * What each category means and what can be done about it.
 *
 * The explanations matter more here than anywhere else on the page: "cross-AZ" is jargon,
 * and someone who does not already know what it means cannot act on a number labelled with
 * it. Each one names the fix, because that is the only reason to show the row.
 */
const CATEGORIES: Record<string, { icon: string; color: string; blurb: string }> = {
  'NAT Gateway': {
    icon: 'mdi:lan-connect',
    color: '#DC2626',
    blurb:
      'Private subnets reaching the internet. Includes both the hourly fee and per-GB processing — ' +
      'a VPC endpoint removes the processing charge for traffic to S3, DynamoDB and other AWS services.',
  },
  'VPC endpoint': {
    icon: 'mdi:lan-pending',
    color: '#0F766E',
    blurb:
      'Interface and gateway endpoints keeping traffic to AWS services off the NAT gateway. ' +
      'Compare this against the NAT Gateway row: moving traffic here is usually the cheaper side.',
  },
  'Transit Gateway': {
    icon: 'mdi:hubspot',
    color: '#9333EA',
    blurb:
      'Attachment hours plus per-GB processing for traffic crossing VPCs, accounts or regions. ' +
      'Peering two VPCs directly avoids the per-GB charge where the topology allows it.',
  },
  'Cross-AZ': {
    icon: 'mdi:swap-horizontal',
    color: '#F4A429',
    blurb:
      'Traffic between availability zones in the same region, charged in both directions. ' +
      'Often an app talking to a database in another AZ.',
  },
  'Inter-region': {
    icon: 'mdi:earth',
    color: '#7C3AED',
    blurb: 'Traffic between AWS regions — replication, cross-region reads, or a multi-region setup.',
  },
  'Internet egress': {
    icon: 'mdi:upload-network-outline',
    color: '#4285F4',
    blurb:
      'Data leaving AWS for the internet. Serving it through CloudFront is usually cheaper per GB.',
  },
  'Internet ingress': {
    icon: 'mdi:download-network-outline',
    color: '#158045',
    blurb: 'Data entering AWS. Normally free, so anything charged here is worth a look.',
  },
  'Other network': {
    icon: 'mdi:transit-connection-variant',
    color: '#6B7280',
    blurb: 'Transfer charges that do not fall into the categories above.',
  },
};

const fallbackMeta = { icon: 'mdi:transit-connection-variant', color: '#6B7280', blurb: '' };

/** Which drill-down a category is showing. */
type ViewKey = 'flows' | 'routes' | 'resources' | 'usage';

const DataTransferSection: React.FC<DataTransferSectionProps> = ({ accountId }) => {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [views, setViews] = useState<Record<string, ViewKey>>({});

  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);

  // Cached across mounts, so returning to this page does not re-scan the report.
  const { data, isPending, isError } = useCurInsight(
    'data-transfer',
    accountId,
    period,
    fetchDataTransferCosts,
  );
  const breakdown = isError ? null : (data ?? null);
  // Only the first load blocks; a background revalidation keeps the rows on screen.
  const loading = Boolean(accountId) && isPending;

  const groups = useMemo<Group[]>(
    () => (breakdown ? buildGroups(breakdown.charges, breakdown.flows) : []),
    [breakdown],
  );

  const total = groups.reduce((sum, g) => sum + g.cost, 0);

  /**
   * The view that answers something for this category.
   *
   * Resource-and-route first where it exists, because that is the one row shape that names a
   * decision on its own. Falling back through routes to the raw usage types means a report
   * missing the optional columns still opens onto a populated table rather than a notice.
   */
  const defaultView = (g: Group): ViewKey => {
    if (g.flows.length > 0) return 'flows';
    if (g.routes.length > 0) return 'routes';
    return 'usage';
  };

  const toggle = (category: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(category)) next.delete(category);
      else next.add(category);
      return next;
    });
  };

  // No report for this account — the section does not belong on the page at all.
  if (!accountId || (!loading && breakdown === null)) return null;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
        <div>
          <h3 className="text-[15px] font-bold text-black">Data transfer &amp; network costs</h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
            Network charges pulled out of every service they are billed under. Expand a row for
            the resource, the route and the cost on one line.
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
      ) : groups.length === 0 ? (
        <div className="text-center py-8">
          <Icon
            icon="mdi:transit-connection-variant"
            width={26}
            className="text-[rgba(0,0,0,0.18)] mx-auto mb-2"
          />
          <p className="text-[13px] text-[rgba(0,0,0,0.55)]">
            No network charges in {months.find((m) => m.value === period)?.label ?? 'this period'}.
          </p>
          <p className="text-[11.5px] text-[rgba(0,0,0,0.35)] mt-1">
            Either nothing was billed, or the report does not yet cover this month.
          </p>
        </div>
      ) : (
        <div className="space-y-1.5">
          {groups.map((g) => {
            const meta = CATEGORIES[g.category] ?? fallbackMeta;
            const share = total > 0 ? (g.cost / total) * 100 : 0;
            const isOpen = expanded.has(g.category);
            const view = views[g.category] ?? defaultView(g);
            const topRoute = g.routes[0];

            return (
              <div
                key={g.category}
                className="border border-[rgba(0,0,0,0.08)] rounded-lg overflow-hidden"
              >
                <button
                  type="button"
                  onClick={() => toggle(g.category)}
                  aria-expanded={isOpen}
                  className="w-full flex items-center gap-3 px-3 py-2.5 hover:bg-[rgba(0,0,0,0.02)] transition-colors text-left"
                >
                  <Icon
                    icon={isOpen ? 'mdi:chevron-down' : 'mdi:chevron-right'}
                    width={16}
                    className="text-[rgba(0,0,0,0.35)] flex-shrink-0"
                  />
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
                          that nobody should have to click to learn where the money went. */}
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
                </button>

                {isOpen && (
                  <div className="border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
                    {meta.blurb && (
                      <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] leading-relaxed px-4 pt-2.5">
                        {meta.blurb}
                      </p>
                    )}

                    <div className="flex items-center gap-1 px-4 pt-2.5 pb-1 flex-wrap">
                      <span className="text-[10.5px] uppercase tracking-wide text-[rgba(0,0,0,0.35)] mr-1">
                        Break down by
                      </span>
                      {(
                        [
                          ['flows', 'Resource + route', g.flows.length],
                          ['routes', 'Route', g.routes.length],
                          ['resources', 'Resource', g.resources.length],
                          ['usage', 'Billed usage', g.charges.length],
                        ] as [ViewKey, string, number][]
                      ).map(([key, label, count]) => (
                        <button
                          key={key}
                          type="button"
                          aria-pressed={view === key}
                          onClick={() => setViews((prev) => ({ ...prev, [g.category]: key }))}
                          className={`text-[11.5px] px-2.5 py-1 rounded-md border transition-colors ${
                            view === key
                              ? 'bg-white border-[rgba(0,0,0,0.15)] text-black font-semibold shadow-sm'
                              : 'bg-transparent border-transparent text-[rgba(0,0,0,0.5)] hover:text-black'
                          }`}
                        >
                          {label}
                          {count > 0 && (
                            <span className="ml-1 text-[rgba(0,0,0,0.35)]">{count}</span>
                          )}
                        </button>
                      ))}
                    </div>

                    {view === 'flows' && (
                      <FlowTable
                        group={g}
                        resourceIds={breakdown?.resourceDataAvailable ?? false}
                        color={meta.color}
                      />
                    )}
                    {view === 'routes' && (
                      <RouteTable
                        group={g}
                        color={meta.color}
                        routeColumns={breakdown?.routeDataAvailable ?? false}
                      />
                    )}
                    {view === 'resources' && (
                      <ResourceTable
                        group={g}
                        resourceIds={breakdown?.resourceDataAvailable ?? false}
                      />
                    )}
                    {view === 'usage' && <UsageTable group={g} />}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {groups.length > 0 && (
        <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
          NAT Gateway, VPC endpoint and Transit Gateway include their hourly fees as well as
          per-GB processing — the two are billed separately, and the hourly one is usually the
          smaller of the pair.
        </p>
      )}
    </div>
  );
};

/** One route, drawn as a sentence: "Mumbai → N. Virginia", or "within Mumbai". */
const RouteLabel: React.FC<{ from: string; to: string; strong?: boolean }> = ({
  from,
  to,
  strong,
}) => {
  const weight = strong ? 'font-medium' : '';
  // Neither end known. Says so, rather than drawing an arrow between two dashes — which
  // looks like a rendering failure when it is actually a fact about the bill.
  if (!from && !to) {
    return (
      <span
        title="The bill records no source or destination for this charge"
        className="text-[rgba(0,0,0,0.35)] whitespace-nowrap"
      >
        not recorded
      </span>
    );
  }
  if (isWithinOneLocation(from, to)) {
    return (
      <span title={`Within ${from}`} className="whitespace-nowrap">
        <span className="text-[rgba(0,0,0,0.45)]">within </span>
        <span className={weight}>{shortLocation(from)}</span>
      </span>
    );
  }
  return (
    <span
      title={`${from || 'unknown'} → ${to || 'unknown'}`}
      className="whitespace-nowrap inline-flex items-center gap-1"
    >
      <span className={weight}>{shortLocation(from) || '—'}</span>
      <Icon icon="mdi:arrow-right" width={12} className="text-[rgba(0,0,0,0.3)]" />
      <span className={weight}>{shortLocation(to) || '—'}</span>
    </span>
  );
};

/** Marks a route XamOps worked out from the usage type rather than read from the report. */
const DerivedMark: React.FC = () => (
  <span
    title="Route inferred from the AWS usage type — this report does not carry the from/to location columns"
    className="ml-1 text-[9.5px] uppercase tracking-wide text-[rgba(0,0,0,0.3)] border border-[rgba(0,0,0,0.12)] rounded px-1 py-[1px] align-middle"
  >
    inferred
  </span>
);

/** Shared empty state for a drill-down the report cannot answer. */
const Unavailable: React.FC<{ icon: string; children: React.ReactNode }> = ({ icon, children }) => (
  <div className="flex items-start gap-2 px-4 py-3 border-t border-[rgba(0,0,0,0.05)]">
    <Icon icon={icon} width={15} className="text-[rgba(0,0,0,0.3)] flex-shrink-0 mt-0.5" />
    <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] leading-relaxed">{children}</p>
  </div>
);

/** Why `flows` is empty: the report cannot attribute a charge to a resource. */
const NoResourceIds: React.FC = () => (
  <Unavailable icon="mdi:tag-off-outline">
    This report was created without resource ids, so network charges cannot be attributed to a
    gateway, bucket or instance. Enabling resource ids on the export adds them from the next
    billing period — the route and usage views work either way.
  </Unavailable>
);

/** A horizontal share bar, sized against the largest row in its table. */
const ShareBar: React.FC<{ value: number; max: number; color: string }> = ({
  value,
  max,
  color,
}) => (
  <div className="h-1 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
    <div
      className="h-full rounded-full"
      style={{ width: `${max > 0 ? (value / max) * 100 : 0}%`, backgroundColor: color }}
    />
  </div>
);

/**
 * How much of a category a capped list covers, stated rather than implied.
 *
 * <p>Both views that use this are built from the same capped set of flows, so the count is
 * always a flow count even where the rows are resources. Saying "4 resources of 512" would
 * compare two different units and invite the reader to subtract them; naming the flows the
 * roll-up came from keeps the sentence true.
 */
const Coverage: React.FC<{
  /** Cost of the rows actually drawn. */
  listed: number;
  /** Flows the drawn rows were built from. */
  flows: number;
  /** Flows in the whole category, above the same threshold the rows were filtered by. */
  totalFlows: number;
  /** The category total, which is the header figure the reader is comparing against. */
  total: number;
  /** Null for the flow view, where the rows *are* the flows. */
  rolledUpInto: string | null;
}> = ({ listed, flows, totalFlows, total, rolledUpInto }) => {
  if (totalFlows <= flows) return null;
  const share = total > 0 ? ((listed / total) * 100).toFixed(0) : '0';
  return (
    <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] px-4 py-2 border-t border-[rgba(0,0,0,0.05)]">
      {rolledUpInto ? `Rolled up from the top ${flows} flows` : `Top ${flows} flows`} of{' '}
      {totalFlows.toLocaleString('en-US')} — {money(listed)} of {money(total)} ({share}% of this
      category).
    </p>
  );
};

/**
 * The default view: one row per resource per route, which is the whole point of the section.
 *
 * <p>Ends with the note about the far end, because "which resource is on the other side" is
 * the very next question and the answer is that no billing report contains it.
 */
const FlowTable: React.FC<{ group: Group; resourceIds: boolean; color: string }> = ({
  group,
  resourceIds,
  color,
}) => {
  if (!resourceIds) return <NoResourceIds />;
  if (group.flows.length === 0) {
    return (
      <Unavailable icon="mdi:tag-off-outline">
        AWS attributes none of these charges to a specific resource.
      </Unavailable>
    );
  }

  const top = group.flows[0].cost;
  const listed = group.flows.reduce((sum, f) => sum + f.cost, 0);

  return (
    <>
      <table className="w-full text-[12px]">
        <caption className="sr-only">
          Resources and routes charged in {group.category}
        </caption>
        <thead>
          <tr className="text-[10px] uppercase tracking-wide text-[rgba(0,0,0,0.35)] border-t border-[rgba(0,0,0,0.05)]">
            <th scope="col" className="px-4 py-1.5 text-left font-medium">
              Resource
            </th>
            <th scope="col" className="px-3 py-1.5 text-left font-medium">
              Route
            </th>
            <th scope="col" className="px-3 py-1.5 text-right font-medium">
              Usage
            </th>
            <th scope="col" className="px-4 py-1.5 text-right font-medium">
              Cost
            </th>
          </tr>
        </thead>
        <tbody>
          {group.flows.map((f, i) => (
            <tr
              key={`${f.resourceId}-${f.service}-${f.usageType}-${f.fromLocation}-${f.toLocation}-${i}`}
              className="border-t border-[rgba(0,0,0,0.05)] align-top"
            >
              <td className="px-4 py-2">
                <span
                  className={`text-[12px] text-black ${
                    f.name ? 'font-medium' : 'font-mono text-[11.5px]'
                  }`}
                  title={f.resourceId || 'No resource id on these charges'}
                >
                  {f.name || shortResource(f.resourceId)}
                </span>
                <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] mt-0.5">
                  {f.service}
                  <span className="mx-1">·</span>
                  {f.availabilityZone || f.region}
                </p>
              </td>
              <td className="px-3 py-2 text-[11.5px] text-black">
                <RouteLabel from={f.fromLocation} to={f.toLocation} />
                {f.routeDerived && <DerivedMark />}
                <p className="font-mono text-[10px] text-[rgba(0,0,0,0.35)] mt-0.5">
                  {f.usageType}
                </p>
              </td>
              <td className="px-3 py-2 text-right text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                {usageLabel(f.gigabytes, f.hours)}
              </td>
              <td className="px-4 py-2 text-right w-[104px]">
                <p className="font-medium text-black whitespace-nowrap">{money(f.cost)}</p>
                <div className="mt-1">
                  <ShareBar value={f.cost} max={top} color={color} />
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Coverage
        listed={listed}
        flows={group.flows.length}
        totalFlows={group.categoryFlows}
        total={group.cost}
        rolledUpInto={null}
      />
      <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] px-4 py-2 border-t border-[rgba(0,0,0,0.05)] leading-relaxed">
        The route names a region or the internet, not a second resource: a billing line records
        only the resource being charged. Pairing both ends needs VPC Flow Logs.
      </p>
    </>
  );
};

/**
 * Where the traffic went, summed over every resource on the route.
 *
 * @param routeColumns whether the report carries the from/to location columns. When it does
 *   not, every route here was read out of a usage type — each row says so on its own, but only
 *   this flag can say what to change about the export, which is the part a reader can act on.
 */
const RouteTable: React.FC<{ group: Group; color: string; routeColumns: boolean }> = ({
  group,
  color,
  routeColumns,
}) => {
  if (group.routes.length === 0) {
    return (
      <Unavailable icon="mdi:map-marker-off-outline">
        These charges name no source and destination, which is expected for a gateway or
        endpoint: the bytes passed <em>through</em> it on the way somewhere the bill does not
        record, and the hourly part moved nothing at all. Use Resource to see which gateway.
      </Unavailable>
    );
  }

  const top = group.routes[0].cost;

  return (
    <table className="w-full text-[12px]">
      <caption className="sr-only">Traffic routes in {group.category}</caption>
      <tbody>
        {group.routes.map((r: Route) => (
          <tr key={`${r.from}->${r.to}`} className="border-t border-[rgba(0,0,0,0.05)]">
            <td className="px-4 py-2">
              <div className="text-[12px] text-black">
                <RouteLabel from={r.from} to={r.to} strong />
                {r.derived && <DerivedMark />}
              </div>
              <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] mt-0.5 truncate">
                {r.services.slice(0, 3).join(', ')}
                {r.services.length > 3 && ` +${r.services.length - 3}`}
              </p>
            </td>
            <td className="px-3 py-2 w-[120px]">
              <ShareBar value={r.cost} max={top} color={color} />
            </td>
            <td className="px-3 py-2 text-right text-[rgba(0,0,0,0.6)] whitespace-nowrap">
              {usageLabel(r.gigabytes, r.hours)}
            </td>
            <td className="px-4 py-2 text-right font-medium text-black whitespace-nowrap w-[90px]">
              {money(r.cost)}
            </td>
          </tr>
        ))}
        {/* Kept as a row rather than dropped, so the routes add up to the category total.
            Gateway processing and hourly fees land here: real charges the bill gives no
            destination for. */}
        {group.unroutedCost > 0.005 && (
          <tr className="border-t border-[rgba(0,0,0,0.05)]">
            <td className="px-4 py-2 text-[rgba(0,0,0,0.5)] italic" colSpan={3}>
              No destination recorded (gateway processing, hourly fees)
            </td>
            <td className="px-4 py-2 text-right font-medium text-[rgba(0,0,0,0.6)] whitespace-nowrap">
              {money(group.unroutedCost)}
            </td>
          </tr>
        )}
      </tbody>
      {/* The one thing the per-row badges cannot say: how to stop the routes being guesses. */}
      {!routeColumns && (
        <tfoot>
          <tr>
            <td colSpan={4} className="px-4 py-2 border-t border-[rgba(0,0,0,0.05)]">
              <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] leading-relaxed">
                Every route above was read out of the AWS usage type: this report does not include
                the from/to location columns. Selecting all columns when creating the data export
                replaces these with what AWS itself recorded.
              </p>
            </td>
          </tr>
        </tfoot>
      )}
    </table>
  );
};

/** Which resource moved the bytes, summed over every route it used. */
const ResourceTable: React.FC<{ group: Group; resourceIds: boolean }> = ({
  group,
  resourceIds,
}) => {
  if (!resourceIds) return <NoResourceIds />;
  if (group.resources.length === 0) {
    return (
      <Unavailable icon="mdi:tag-off-outline">
        AWS attributes none of these charges to a specific resource.
      </Unavailable>
    );
  }

  const listed = group.resources.reduce((sum, r) => sum + r.cost, 0);

  return (
    <>
      <table className="w-full text-[12px]">
        <caption className="sr-only">Resources charged in {group.category}</caption>
        <tbody>
          {group.resources.map((r: Resource) => (
            <tr
              key={`${r.resourceId}-${r.service}-${r.region}`}
              className="border-t border-[rgba(0,0,0,0.05)] align-top"
            >
              <td className="px-4 py-2">
                <span
                  className={`text-[12px] text-black ${
                    r.name ? 'font-medium' : 'font-mono text-[11.5px]'
                  }`}
                  title={r.resourceId || 'No resource id on these charges'}
                >
                  {r.name || shortResource(r.resourceId)}
                </span>
                {r.name && r.resourceId && (
                  <span
                    className="font-mono text-[10.5px] text-[rgba(0,0,0,0.35)] ml-2"
                    title={r.resourceId}
                  >
                    {shortResource(r.resourceId)}
                  </span>
                )}
                <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] mt-0.5">
                  {r.service}
                  <span className="mx-1">·</span>
                  {r.availabilityZone || r.region}
                </p>
              </td>
              <td className="px-3 py-2 text-[11px] text-[rgba(0,0,0,0.55)]">
                {r.routes.slice(0, 2).map((route) => (
                  <p key={`${route.from}->${route.to}`} className="leading-relaxed">
                    <RouteLabel from={route.from} to={route.to} />
                    <span className="text-[rgba(0,0,0,0.35)] ml-1.5">{money(route.cost)}</span>
                  </p>
                ))}
                {r.routes.length > 2 && (
                  <p className="text-[rgba(0,0,0,0.35)]">+{r.routes.length - 2} more routes</p>
                )}
                {r.routes.length === 0 && <span className="text-[rgba(0,0,0,0.3)]">no route</span>}
              </td>
              <td className="px-3 py-2 text-right text-[rgba(0,0,0,0.6)] whitespace-nowrap">
                {usageLabel(r.gigabytes, r.hours)}
              </td>
              <td className="px-4 py-2 text-right font-medium text-black whitespace-nowrap w-[90px]">
                {money(r.cost)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {/* Rolled up from the capped flow list, so the shortfall is stated rather than implied. */}
      <Coverage
        listed={listed}
        flows={group.flows.length}
        totalFlows={group.categoryFlows}
        total={group.cost}
        rolledUpInto="resources"
      />
    </>
  );
};

/** The billing rows themselves — the usage types the charges were raised under. */
const UsageTable: React.FC<{ group: Group }> = ({ group }) => (
  <table className="w-full text-[12px]">
    <caption className="sr-only">Billed usage types in {group.category}</caption>
    <tbody>
      {group.charges.map((c, i) => (
        <tr
          key={`${c.usageType}-${c.service}-${c.region}-${c.fromLocation}-${c.toLocation}-${i}`}
          className="border-t border-[rgba(0,0,0,0.05)]"
        >
          <td className="px-4 py-2">
            <span className="font-mono text-[11.5px] text-black">{c.usageType}</span>
            <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] mt-0.5">
              {c.service}
              <span className="mx-1">·</span>
              {c.region}
              {(c.fromLocation || c.toLocation) && (
                <>
                  <span className="mx-1">·</span>
                  <RouteLabel from={c.fromLocation} to={c.toLocation} />
                </>
              )}
            </p>
          </td>
          <td className="px-3 py-2 text-right text-[rgba(0,0,0,0.6)] whitespace-nowrap">
            {quantity(c.quantity, c.unit)}
          </td>
          <td className="px-4 py-2 text-right font-medium text-black whitespace-nowrap w-[90px]">
            {money(c.cost)}
          </td>
        </tr>
      ))}
    </tbody>
  </table>
);

export default DataTransferSection;
