import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchTopResources, type ResourceCost } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import Pagination from '../FinOps/Pagination';

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
 * the invoice, and a total that does not reconcile is worse than no total.
 */

interface TopResourcesSectionProps {
  /** Single AWS account with a report, or null to render nothing. */
  accountId: string | null;
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

const money = (v: number): string =>
  (v < 0 ? '-$' : '$') +
  Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

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

const TopResourcesSection: React.FC<TopResourcesSectionProps> = ({ accountId }) => {
  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);
  const [service, setService] = useState('all');
  const [region, setRegion] = useState('all');
  const [search, setSearch] = useState('');
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
  const { data, isPending, isError } = useCurInsight(
    'top-resources',
    accountId,
    period,
    fetchTopResources,
  );
  const result = isError ? null : (data ?? null);
  const loading = Boolean(accountId) && isPending;

  const all = useMemo(() => result?.resources ?? [], [result]);

  // Resources and account-level charges are different kinds of thing and are counted and
  // displayed separately; only the former is rankable.
  const resources = useMemo(() => all.filter((r) => r.resourceId !== ''), [all]);
  const accountLevel = useMemo(() => all.filter((r) => r.resourceId === ''), [all]);

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

  // No report for this account — the section does not belong on the page at all.
  if (!accountId || (!loading && result === null)) return null;

  const selectedLabel = months.find((m) => m.value === period)?.label ?? '';

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6">
      <div className="flex flex-wrap items-start justify-between gap-4 mb-4">
        <div>
          <h3 className="text-[15px] font-bold text-black">Top resources</h3>
          <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">
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
            Either nothing was billed, or the report does not yet cover this month.
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
              Names come from each resource's <span className="font-mono">Name</span> tag, and
              this report carries none. A CUR only includes tags activated for cost allocation
              — activate <span className="font-mono">Name</span> in the payer account's billing
              console and it will appear here from the next report.
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
    </div>
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
}> = ({ group, max, open, page, onToggle, onPageChange }) => {
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
            <ResourceRow key={`${r.resourceId}-${r.region}`} r={r} max={maxInGroup} />
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
const ResourceRow: React.FC<{ r: ResourceCost; max: number }> = ({ r, max }) => {
  const share = max > 0 ? (Math.abs(r.cost) / max) * 100 : 0;
  const label = labelFor(r);

  return (
    <div className="flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors">
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
