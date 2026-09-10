import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { fetchTopResources, type ResourceCost } from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';

/**
 * What each individual resource cost — this bucket, this instance, this volume.
 *
 * <p>The Cost Explorer API cannot answer this. Its resource-level view covers EC2 alone and
 * retains fourteen days, so until now the product could say "S3 cost $2,527" and not which
 * bucket. Everything here comes from {@code line_item_resource_id}, which exists only
 * because the report was created with resource ids enabled.
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

/** How many rows before the list stops being scannable. */
const PAGE = 20;

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

const TopResourcesSection: React.FC<TopResourcesSectionProps> = ({ accountId }) => {
  const months = useMemo(monthOptions, []);
  const [period, setPeriod] = useState(months[0].value);
  const [service, setService] = useState('all');
  const [search, setSearch] = useState('');
  const [expanded, setExpanded] = useState(false);

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

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return resources.filter(
      (r) =>
        (service === 'all' || r.service === service) &&
        (term === '' ||
          r.resourceId.toLowerCase().includes(term) ||
          r.instanceType.toLowerCase().includes(term)),
    );
  }, [resources, service, search]);

  const shown = expanded ? filtered : filtered.slice(0, PAGE);
  // The largest in the current view, so the bars rescale when a filter narrows it —
  // against the global maximum every bar in a filtered list would be a sliver.
  const max = shown.length > 0 ? Math.max(...shown.map((r) => Math.abs(r.cost))) : 0;

  const accountLevelTotal = accountLevel.reduce((sum, r) => sum + r.cost, 0);
  const isFiltered = service !== 'all' || search.trim() !== '';
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
          </p>
        </div>

        <div className="flex items-center gap-4">
          <select
            value={period}
            onChange={(e) => setPeriod(e.target.value)}
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
                setExpanded(false);
              }}
              className="text-[12px] border border-[rgba(0,0,0,0.15)] rounded-lg px-2.5 py-1.5 bg-white text-black focus:outline-none focus:ring-1 focus:ring-[#4285F4]"
            >
              <option value="all">All services</option>
              {services.map((s) => (
                <option key={s} value={s}>
                  {s}
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
                  setExpanded(false);
                }}
                placeholder="Find a resource by id or instance type…"
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
            <div className="space-y-0.5">
              {shown.map((r) => (
                <ResourceRow key={`${r.resourceId}-${r.service}-${r.region}`} r={r} max={max} />
              ))}
            </div>
          )}

          {filtered.length > PAGE && (
            <button
              type="button"
              onClick={() => setExpanded((v) => !v)}
              className="mt-2 text-[12px] font-medium text-[#1A56C4] hover:underline"
            >
              {expanded
                ? 'Show less'
                : `Show all ${filtered.length} resources`}
            </button>
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
                {accountLevel.map((r) => (
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
            </div>
          )}

          {result && result.groupCount > all.length && (
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-3 leading-relaxed">
              Showing the {all.length} costliest of {result.groupCount.toLocaleString('en-US')}{' '}
              resources. The total above covers all of them.
            </p>
          )}
        </>
      )}
    </div>
  );
};

/** One resource, with a bar sized against the largest in the current view. */
const ResourceRow: React.FC<{ r: ResourceCost; max: number }> = ({ r, max }) => {
  const share = max > 0 ? (Math.abs(r.cost) / max) * 100 : 0;

  return (
    <div className="flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors">
      <Icon
        icon={iconFor(r.service)}
        width={16}
        className="text-[rgba(0,0,0,0.45)] flex-shrink-0"
      />

      <div className="flex-1 min-w-0">
        <div className="flex items-baseline gap-2">
          {/* The full id on hover: the shortened form is for scanning, not for copying
              into a console. */}
          <span
            title={r.resourceId}
            className="font-mono text-[11.5px] text-black truncate"
          >
            {shorten(r.resourceId)}
          </span>
          {r.instanceType && (
            <span className="text-[10.5px] px-1.5 py-0.5 rounded bg-[rgba(0,0,0,0.05)] text-[rgba(0,0,0,0.6)] flex-shrink-0">
              {r.instanceType}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2 mt-0.5">
          <span className="text-[10.5px] text-[rgba(0,0,0,0.4)] truncate">
            {r.service}
            {r.region !== 'Global' && ` · ${r.region}`}
          </span>
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
