import React from 'react';
import { Icon } from '@iconify/react';
import { useMoney } from './costCurrency';
import type { Group, Resource, Route } from './dataTransferModel';

/**
 * The presentational layer of the network section, shared between the collapsed overview list
 * (DataTransferSection) and the slide-over panel (DataTransferCategoryPanel).
 *
 * <p>These pieces were co-located inside DataTransferSection when the drill-down was an inline
 * accordion. The panel now needs the same tables and route helpers, so they live here as one copy
 * rather than being duplicated — the arithmetic stays in dataTransferModel.ts and the wording stays
 * in the copy objects; this file is only how a group is drawn.
 */

export const amount = (v: number): string =>
  v.toLocaleString('en-US', {
    minimumFractionDigits: v < 10 ? 2 : 0,
    maximumFractionDigits: v < 10 ? 2 : 0,
  });

export const quantity = (v: number, unit: string): string =>
  unit ? `${amount(v)} ${unit}` : amount(v);

/** GB and hours side by side, never summed. Em dash when a row has neither. */
export const usageLabel = (gigabytes: number, hours: number): string => {
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
export const shortLocation = (value: string): string => {
  const parenthesised = value.match(/\(([^)]+)\)\s*$/);
  return parenthesised ? parenthesised[1] : value;
};

/**
 * The readable end of a resource id.
 *
 * ARNs are long and their distinguishing part is at the end, so an untruncated list of them
 * is a column of identical prefixes. The full id stays in the row's `title`.
 */
export const shortResource = (resourceId: string): string => {
  if (!resourceId) return 'Not attributed to a resource';
  if (!resourceId.startsWith('arn:')) return resourceId;
  const tail = resourceId.split(/[:/]/).filter(Boolean).pop();
  return tail || resourceId;
};

/** True when a route's two ends are the same place, so no arrow should be drawn. */
export const isWithinOneLocation = (from: string, to: string): boolean =>
  Boolean(from) && from === to;

/**
 * What each category means and what can be done about it.
 *
 * The explanations matter more here than anywhere else on the page: "cross-AZ" is jargon,
 * and someone who does not already know what it means cannot act on a number labelled with
 * it. Each one names the fix, because that is the only reason to show the row.
 */
export const CATEGORIES: Record<string, { icon: string; color: string; blurb: string }> = {
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
  // ── Categories only GCP produces ──────────────────────────────────────────
  // Harmless on AWS, which never emits them. Kept in the shared map rather than injected per
  // provider because an icon and a colour are presentation, not provider behaviour.
  'Load balancing': {
    icon: 'mdi:scale-balance',
    color: '#0891B2',
    blurb:
      'Forwarding rules and the data they process. On GCP this is where the cost of inbound ' +
      'traffic lands, because ingress itself is free and the charge sits on whatever handles it.',
  },
  'Cloud VPN': {
    icon: 'mdi:shield-lock-outline',
    color: '#B45309',
    blurb:
      'Tunnel uptime plus the traffic crossing it. A tunnel is billed per hour whether anything ' +
      'flows through it, so an abandoned one costs the same as a busy one.',
  },
  Interconnect: {
    icon: 'mdi:transit-connection-horizontal',
    color: '#4338CA',
    blurb:
      'Dedicated or partner links to your own network. Egress over these is cheaper per GB than ' +
      'the internet, so traffic still going the public route is worth moving here.',
  },
  'Within zone': {
    icon: 'mdi:arrow-collapse-all',
    color: '#15803D',
    blurb: 'Traffic that never left its zone. Normally free, so anything charged here is worth a look.',
  },
  'Other network': {
    icon: 'mdi:transit-connection-variant',
    color: '#6B7280',
    blurb: 'Transfer charges that do not fall into the categories above.',
  },
};

export const fallbackMeta = { icon: 'mdi:transit-connection-variant', color: '#6B7280', blurb: '' };

/** Which drill-down a category is showing. */
export type ViewKey = 'flows' | 'routes' | 'resources' | 'usage';

/** One route, drawn as a sentence: "Mumbai → N. Virginia", or "within Mumbai". */
export const RouteLabel: React.FC<{ from: string; to: string; strong?: boolean }> = ({
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
export const DerivedMark: React.FC = () => (
  <span
    title="Route inferred from the AWS usage type — this report does not carry the from/to location columns"
    className="ml-1 text-[9.5px] uppercase tracking-wide text-[rgba(0,0,0,0.3)] border border-[rgba(0,0,0,0.12)] rounded px-1 py-[1px] align-middle"
  >
    inferred
  </span>
);

/** Shared empty state for a drill-down the report cannot answer. */
export const Unavailable: React.FC<{ icon: string; children: React.ReactNode }> = ({
  icon,
  children,
}) => (
  <div className="flex items-start gap-2 px-4 py-3 border-t border-[rgba(0,0,0,0.05)]">
    <Icon icon={icon} width={15} className="text-[rgba(0,0,0,0.3)] flex-shrink-0 mt-0.5" />
    <p className="text-[11.5px] text-[rgba(0,0,0,0.5)] leading-relaxed">{children}</p>
  </div>
);

/** Why `flows` is empty: the billing source cannot attribute a charge to a named resource. */
export const NoResourceIds: React.FC<{ detail?: React.ReactNode }> = ({ detail }) => (
  <Unavailable icon="mdi:tag-off-outline">
    {detail ?? (
      <>
        This report was created without resource ids, so network charges cannot be attributed to a
        gateway, bucket or instance. Enabling resource ids on the export adds them from the next
        billing period — the route and usage views work either way.
      </>
    )}
  </Unavailable>
);

/** A horizontal share bar, sized against the largest row in its table. */
export const ShareBar: React.FC<{ value: number; max: number; color: string }> = ({
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
export const Coverage: React.FC<{
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
  const money = useMoney();
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
export const FlowTable: React.FC<{
  group: Group;
  resourceIds: boolean;
  color: string;
  noResourceDetail?: React.ReactNode;
}> = ({ group, resourceIds, color, noResourceDetail }) => {
  const money = useMoney();
  if (!resourceIds) return <NoResourceIds detail={noResourceDetail} />;
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
        <caption className="sr-only">Resources and routes charged in {group.category}</caption>
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
export const RouteTable: React.FC<{ group: Group; color: string; routeColumns: boolean }> = ({
  group,
  color,
  routeColumns,
}) => {
  const money = useMoney();
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
export const ResourceTable: React.FC<{
  group: Group;
  resourceIds: boolean;
  noResourceDetail?: React.ReactNode;
}> = ({ group, resourceIds, noResourceDetail }) => {
  const money = useMoney();
  if (!resourceIds) return <NoResourceIds detail={noResourceDetail} />;
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
export const UsageTable: React.FC<{ group: Group }> = ({ group }) => {
  const money = useMoney();
  return (
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
};
