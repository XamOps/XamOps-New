/**
 * Tagging and cost allocation.
 *
 * Two questions with one answer behind them: what does each team cost, and how much of the
 * bill belongs to nobody. The second exists because it is what makes the first wrong — a
 * showback report that quietly omits unowned spend adds up to less than the invoice, and
 * the gap is precisely the number finance is looking for.
 *
 * <p>Measured in dollars, not in resources. The resource count this panel used to lead with
 * answered a question nobody asks: an account with 1,944 untagged AMIs and one untagged NAT
 * gateway scores near 0% either way, but the AMIs cost cents and the gateway costs $63, so
 * a count sends the reader to fix the wrong thing first, every time.
 *
 * <p>The inventory scan is kept underneath rather than dropped. A CUR only sees what was
 * billed, so it is blind to resources that cost nothing yet — idle AMIs, orphaned snapshots
 * — which are a real governance signal, just not the headline. Where there is no report at
 * all, the old count-based view is still the whole panel.
 */

import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { TaggingCompliance, PaginationState } from '../../types/finops';
import {
  fetchTagCoverage,
  fetchCostByTag,
  untaggedCsvUrl,
  type TagCoverage,
  type UntaggedService,
} from '../../api/curInsightsApi';
import { useCurInsight } from '../../hooks/useCurInsight';
import Pagination from './Pagination';

interface FinOpsTaggingComplianceProps {
  taggingCompliance: TaggingCompliance;
  pagination: PaginationState;
  onPaginationChange: (updates: Partial<PaginationState>) => void;
  /** Single AWS account with a report, or null to fall back to the inventory view. */
  accountId?: string | null;
}

const money = (v: number): string =>
  (v < 0 ? '-$' : '$') +
  Math.abs(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Compact form for the KPI headline, where cents are noise. */
const moneyShort = (v: number): string =>
  '$' + Math.round(Math.abs(v)).toLocaleString('en-US');

/**
 * A share, rounded without ever rounding a real number away.
 *
 * $9 of $2,361 is 0.38%, and printing "0%" beside a bar with a visible sliver in it reads
 * as a broken widget. Small non-zero shares keep a decimal instead.
 */
const percent = (part: number, whole: number): string => {
  if (whole <= 0) return '0%';
  const pct = (part / whole) * 100;
  if (pct > 0 && pct < 1) return `${pct.toFixed(1)}%`;
  return `${Math.round(pct)}%`;
};

/** Green above 90, amber above 70, red below — the same thresholds finance uses. */
const rateColor = (pct: number) =>
  pct >= 90
    ? { text: '#158045', bar: '#16a34a', bg: '#f0fdf4' }
    : pct >= 70
    ? { text: '#92400e', bar: '#f59e0b', bg: '#fffbeb' }
    : { text: '#991b1b', bar: '#dc2626', bg: '#fef2f2' };

const FinOpsTaggingCompliance: React.FC<FinOpsTaggingComplianceProps> = ({
  taggingCompliance,
  pagination,
  onPaginationChange,
  accountId = null,
}) => {
  const { data, isPending } = useCurInsight('tag-coverage', accountId, undefined, fetchTagCoverage);
  const coverage: TagCoverage | null = data ?? null;
  const loadingCoverage = Boolean(accountId) && isPending;

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 widget-panel-hover">
      <div className="flex items-center gap-2 pb-4 border-b border-[rgba(0,0,0,0.06)] mb-5">
        <div className="w-7 h-7 flex items-center justify-center rounded-md border border-[rgba(0,0,0,0.06)]">
          <Icon icon="mdi:tag-multiple-outline" width={16} color="#0EA5E9" />
        </div>
        <div>
          <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
            {coverage ? 'Tagging & Cost Allocation' : 'Tagging Compliance'}
          </p>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] flex items-center gap-1.5 mt-0.5">
            <span className="w-1.5 h-1.5 rounded-full bg-sky-400 animate-pulse inline-block" />
            {coverage
              ? 'Which spend has an owner, and what each owner costs — this month so far'
              : 'Monitor resource tagging across your infrastructure'}
          </p>
        </div>
      </div>

      {loadingCoverage ? (
        <div className="space-y-3">
          <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[92px] rounded-xl" />
          <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[120px] rounded-xl" />
        </div>
      ) : coverage ? (
        <CostWeightedView coverage={coverage} accountId={accountId} scan={taggingCompliance} />
      ) : (
        <InventoryView
          taggingCompliance={taggingCompliance}
          pagination={pagination}
          onPaginationChange={onPaginationChange}
        />
      )}
    </div>
  );
};

// ── The CUR view: dollars ────────────────────────────────────────────────────

const CostWeightedView: React.FC<{
  coverage: TagCoverage;
  accountId: string | null;
  scan: TaggingCompliance;
}> = ({ coverage, accountId, scan }) => {
  const {
    totalCost,
    allocatedCost,
    checkedTags,
    unavailableTags,
    byTag,
    untaggedServices,
    untaggedCount,
  } = coverage;

  const unallocated = Math.max(0, totalCost - allocatedCost);
  const rate = totalCost > 0 ? (allocatedCost / totalCost) * 100 : 100;
  const colors = rateColor(rate);

  // The tag costing the most to not have. Worth naming: "fix cost-center first" is an
  // instruction, where three equal-looking progress bars are a puzzle.
  const weakest = useMemo(
    () => [...byTag].sort((a, b) => b.untaggedCost - a.untaggedCost)[0] ?? null,
    [byTag],
  );

  // Nothing checkable — the required tags exist as policy but not in the report.
  if (checkedTags.length === 0) {
    return <UnavailableNotice unavailableTags={unavailableTags} scan={scan} />;
  }

  return (
    <>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        <Kpi
          label="Attributed spend"
          value={`${rate.toFixed(1)}%`}
          valueColor={colors.text}
          caption={`${moneyShort(allocatedCost)} of ${moneyShort(totalCost)}`}
          icon="mdi:check-decagram-outline"
          iconBg={colors.bg}
          iconColor={colors.text}
          bar={{ pct: rate, color: colors.bar }}
        />
        <Kpi
          label="Belongs to nobody"
          value={moneyShort(unallocated)}
          valueColor={unallocated > 0 ? '#dc2626' : '#158045'}
          caption={
            totalCost > 0
              ? `${percent(unallocated, totalCost)} of spend · ${untaggedCount.toLocaleString()} resources`
              : 'No resource spend this month'
          }
          icon="mdi:help-circle-outline"
          iconBg={unallocated > 0 ? '#fef2f2' : '#f0fdf4'}
          iconColor={unallocated > 0 ? '#dc2626' : '#158045'}
        />
        <Kpi
          label="Costliest gap"
          value={weakest && weakest.untaggedCost > 0 ? weakest.tagKey : '—'}
          valueColor="rgba(0,0,0,0.88)"
          valueSize="18px"
          caption={
            weakest && weakest.untaggedCost > 0
              ? `${money(weakest.untaggedCost)} carries no ${weakest.tagKey}`
              : 'Every required tag is fully applied'
          }
          icon="mdi:tag-off-outline"
          iconBg="#4285F414"
          iconColor="#4285F4"
        />
      </div>

      {/* Per-tag coverage: which policy is actually being followed. */}
      <div className="mb-6">
        <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)] mb-3">
          Coverage by required tag
        </h4>
        <div className="space-y-2.5">
          {byTag.map((t) => {
            const pct = totalCost > 0 ? (t.taggedCost / totalCost) * 100 : 0;
            const c = rateColor(pct);
            return (
              <div key={t.tagKey} className="flex items-center gap-3">
                <span className="font-mono text-[11.5px] text-[rgba(0,0,0,0.7)] w-[120px] flex-shrink-0 truncate">
                  {t.tagKey}
                </span>
                <div className="flex-1 h-2 bg-[rgba(0,0,0,0.05)] rounded-full overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{ width: `${pct}%`, backgroundColor: c.bar }}
                  />
                </div>
                <span className="text-[12px] font-semibold w-[52px] text-right" style={{ color: c.text }}>
                  {percent(t.taggedCost, totalCost)}
                </span>
                <span className="text-[11.5px] text-[rgba(0,0,0,0.45)] w-[110px] text-right flex-shrink-0">
                  {money(t.untaggedCost)} untagged
                </span>
              </div>
            );
          })}
        </div>
        {unavailableTags.length > 0 && (
          <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-3 leading-relaxed">
            Not checked: <span className="font-mono">{unavailableTags.join(', ')}</span>. A CUR
            only carries tags activated for cost allocation, so this report cannot say who
            has them — which is different from nobody having them.
          </p>
        )}
      </div>

      <ChargebackTable coverage={coverage} accountId={accountId} />

      {untaggedServices.length > 0 && (
        <div className="mt-6">
          <div className="flex items-center gap-2 mb-3">
            <span className="w-2 h-2 rounded-full bg-red-500 inline-block" />
            <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
              Where the unattributed spend is
            </h4>
            <span className="px-2 py-0.5 text-[11px] font-medium bg-red-50 text-red-600 border border-red-100 rounded-full">
              {money(Math.max(0, totalCost - allocatedCost))}
            </span>
          </div>
          <Concentration
            head={coverage.concentrationHead}
            headCost={coverage.concentrationCost}
            unallocated={unallocated}
            count={untaggedCount}
          />

          {/* Folded by service. The tail is thousands of resources worth pennies each; a
              flat list of them is a wall, and paging through it finds nothing the first
              page did not already say. Each service still shows its complete total. */}
          <div className="divide-y divide-[rgba(0,0,0,0.05)] mt-3">
            {untaggedServices.map((s) => (
              <ServiceGroup key={s.service} group={s} max={untaggedServices[0]?.cost ?? 0} />
            ))}
          </div>

          {accountId && untaggedCount > 0 && (
            <a
              href={untaggedCsvUrl(accountId)}
              className="inline-flex items-center gap-1.5 mt-4 px-3 py-1.5 text-[12px] font-medium border border-[rgba(0,0,0,0.15)] rounded-lg hover:border-[rgba(0,0,0,0.35)] transition-colors"
            >
              <Icon icon="mdi:download-outline" width={14} />
              Download all {untaggedCount.toLocaleString()} as CSV
            </a>
          )}
        </div>
      )}

      <InventoryFootnote scan={scan} untaggedBilled={untaggedCount} />
    </>
  );
};

/**
 * How much of the problem the top of the list is.
 *
 * <p>The single most useful sentence on this panel. "1,826 resources are untagged" reads
 * as a quarter's work and gets deferred forever; "twenty of them are 89% of the money"
 * reads as an afternoon, which is what it actually is. Untagged spend follows the same
 * long tail as spend itself — the tail here is thousands of resources worth cents each.
 */
const Concentration: React.FC<{
  head: number;
  headCost: number;
  unallocated: number;
  count: number;
}> = ({ head, headCost, unallocated, count }) => {
  if (count <= head || unallocated <= 0) return null;

  const share = (headCost / unallocated) * 100;
  const tailCount = count - head;
  const tailCost = Math.max(0, unallocated - headCost);

  return (
    <div className="flex items-start gap-2.5 px-3.5 py-3 mb-1 bg-[rgba(66,133,244,0.05)] border border-[rgba(66,133,244,0.2)] rounded-lg">
      <Icon icon="mdi:lightbulb-on-outline" width={15} className="text-[#4285F4] flex-shrink-0 mt-0.5" />
      <p className="text-[12px] text-[rgba(0,0,0,0.7)] leading-relaxed">
        <strong>Tag {head} resources and {share.toFixed(0)}% of this closes.</strong> The
        costliest {head} carry {money(headCost)} of the {money(unallocated)}. The other{' '}
        {tailCount.toLocaleString()} are {money(tailCost)} between them — about{' '}
        {money(tailCost / Math.max(1, tailCount))} each, which is rarely worth chasing
        one at a time.
      </p>
    </div>
  );
};

/** One service's unattributed spend, folded until opened. */
const ServiceGroup: React.FC<{ group: UntaggedService; max: number }> = ({ group, max }) => {
  const [open, setOpen] = useState(false);
  const listed = group.resources.length;
  const hidden = group.resourceCount - listed;

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        className="w-full flex items-center gap-3 px-2 py-2.5 text-left rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors"
      >
        <Icon
          icon={open ? 'mdi:chevron-down' : 'mdi:chevron-right'}
          width={14}
          className="text-[rgba(0,0,0,0.35)] flex-shrink-0"
        />
        <div className="flex-1 min-w-0">
          <p className="text-[12.5px] font-semibold text-[rgba(0,0,0,0.88)] truncate">
            {group.service}
          </p>
          <p className="text-[10.5px] text-[rgba(0,0,0,0.4)]">
            {group.resourceCount.toLocaleString()}{' '}
            {group.resourceCount === 1 ? 'resource' : 'resources'}
          </p>
        </div>
        <div className="w-[90px] flex-shrink-0 hidden sm:block">
          <div className="h-1.5 bg-[rgba(0,0,0,0.05)] rounded-full overflow-hidden">
            <div
              className="h-full rounded-full bg-[#dc2626]"
              style={{ width: `${max > 0 ? (group.cost / max) * 100 : 0}%` }}
            />
          </div>
        </div>
        <span className="text-[12.5px] font-bold text-[rgba(0,0,0,0.88)] w-[90px] text-right flex-shrink-0">
          {money(group.cost)}
        </span>
      </button>

      {open && (
        <div className="ml-[15px] pl-3 border-l border-[rgba(0,0,0,0.07)] pb-2">
          {group.resources.map((r) => (
            <div
              key={`${r.resourceId}-${r.region}`}
              className="flex items-center gap-3 px-2 py-1.5 rounded-lg hover:bg-[rgba(0,0,0,0.02)]"
            >
              <div className="flex-1 min-w-0">
                <p
                  className={
                    r.name
                      ? 'text-[12px] font-medium text-[rgba(0,0,0,0.88)] truncate'
                      : 'font-mono text-[11.5px] text-[rgba(0,0,0,0.88)] truncate'
                  }
                  title={r.resourceId}
                >
                  {r.name || r.resourceId}
                </p>
                <p className="text-[10.5px] text-[rgba(0,0,0,0.4)] truncate">{r.region}</p>
              </div>
              <span className="flex flex-wrap gap-1 flex-shrink-0">
                {r.missingTags.map((tag) => (
                  <span
                    key={tag}
                    className="px-2 py-0.5 text-[10.5px] font-mono font-medium bg-red-50 text-red-600 border border-red-100 rounded-full"
                  >
                    {tag}
                  </span>
                ))}
              </span>
              <span className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] w-[80px] text-right flex-shrink-0">
                {money(r.cost)}
              </span>
            </div>
          ))}

          {hidden > 0 && (
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] px-2 pt-1.5">
              {hidden.toLocaleString()} more in this service, worth{' '}
              {money(Math.max(0, group.cost - group.resources.reduce((s, r) => s + r.cost, 0)))}{' '}
              between them. Enforce the tag on {group.service} rather than fixing them
              individually — or take the CSV below.
            </p>
          )}
        </div>
      )}
    </div>
  );
};

/** Spend by the values of one tag — the chargeback report. */
const ChargebackTable: React.FC<{
  coverage: TagCoverage;
  accountId: string | null;
}> = ({ coverage, accountId }) => {
  // Defaults to the first required tag, which is the one the account is held to and so the
  // one most likely to be worth splitting by.
  const keys = coverage.availableTagKeys.length > 0 ? coverage.availableTagKeys : coverage.checkedTags;
  const [tagKey, setTagKey] = useState(coverage.checkedTags[0] ?? keys[0] ?? '');

  const { data, isPending } = useCurInsight(
    `cost-by-tag:${tagKey}`,
    tagKey ? accountId : null,
    undefined,
    (id, period) => fetchCostByTag(id, tagKey, period),
  );
  const values = data ?? [];
  const max = values.length > 0 ? Math.max(...values.map((v) => Math.abs(v.cost))) : 0;

  // Shares are against what this split actually adds up to, not the coverage total. The two
  // now cover the same charges, but the value list is capped server-side, and a share of a
  // number bigger than its own denominator is how you get a 103% next to a bar.
  const splitTotal = values.reduce((sum, v) => sum + v.cost, 0);

  if (!tagKey) return null;

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
        <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Spend by owner</h4>
        <div className="flex items-center gap-2">
          <label htmlFor="chargebackTag" className="text-[11.5px] text-[rgba(0,0,0,0.45)]">
            Split by
          </label>
          <select
            id="chargebackTag"
            value={tagKey}
            onChange={(e) => setTagKey(e.target.value)}
            className="px-2 py-1 text-[12px] font-mono border border-[rgba(0,0,0,0.12)] rounded-lg focus:ring-2 focus:ring-blue-200 focus:border-blue-500 outline-none"
          >
            {keys.map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </select>
        </div>
      </div>

      {isPending ? (
        <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[96px] rounded-lg" />
      ) : values.length === 0 ? (
        <p className="text-[12px] text-[rgba(0,0,0,0.45)] py-4 text-center">
          No spend carries this tag.
        </p>
      ) : (
        <div className="space-y-1.5">
          {values.map((v) => {
            const unassigned = v.value === '(unassigned)';
            return (
              <div key={v.value} className="flex items-center gap-3">
                <span
                  className={`text-[12px] w-[140px] flex-shrink-0 truncate ${
                    unassigned
                      ? 'text-red-600 font-medium italic'
                      : 'text-[rgba(0,0,0,0.8)] font-medium'
                  }`}
                  title={v.value}
                >
                  {v.value}
                </span>
                <div className="flex-1 h-2.5 bg-[rgba(0,0,0,0.04)] rounded-full overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all duration-500"
                    style={{
                      width: `${max > 0 ? (Math.abs(v.cost) / max) * 100 : 0}%`,
                      backgroundColor: unassigned ? '#dc2626' : '#4285F4',
                    }}
                  />
                </div>
                <span className="text-[12.5px] font-semibold text-[rgba(0,0,0,0.88)] w-[88px] text-right flex-shrink-0">
                  {money(v.cost)}
                </span>
                <span className="text-[11px] text-[rgba(0,0,0,0.4)] w-[48px] text-right flex-shrink-0">
                  {percent(v.cost, splitTotal)}
                </span>
                <span className="text-[11px] text-[rgba(0,0,0,0.4)] w-[80px] text-right flex-shrink-0 hidden lg:block">
                  {v.resourceCount} {v.resourceCount === 1 ? 'resource' : 'resources'}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

/** Every required tag is policy-only — nothing to score until one is activated. */
const UnavailableNotice: React.FC<{ unavailableTags: string[]; scan: TaggingCompliance }> = ({
  unavailableTags,
  scan,
}) => (
  <>
    <div className="flex items-start gap-2.5 px-3.5 py-3 bg-amber-50 border border-amber-200 rounded-lg">
      <Icon icon="mdi:information-outline" width={16} className="text-amber-600 flex-shrink-0 mt-0.5" />
      <div className="text-[12px] text-[rgba(0,0,0,0.7)] leading-relaxed">
        <p className="font-semibold mb-1">Cost allocation is not switched on yet.</p>
        <p>
          None of the required tags (
          <span className="font-mono">{unavailableTags.join(', ')}</span>) are in this
          account's Cost and Usage Report. A CUR only carries tags that were activated for
          cost allocation, so until they are, spend cannot be attributed to an owner.
          Activate them under <strong>Billing → Cost allocation tags</strong> in the payer
          account; they appear in the report about a day later.
        </p>
      </div>
    </div>
    <InventoryFootnote scan={scan} untaggedBilled={null} />
  </>
);

/** The resource scan, kept as context under the money. */
const InventoryFootnote: React.FC<{ scan: TaggingCompliance; untaggedBilled: number | null }> = ({
  scan,
  untaggedBilled,
}) => {
  const scanned = scan?.totalResourcesScanned ?? 0;
  if (scanned === 0) return null;
  const untagged = scan?.untaggedResourcesCount ?? 0;

  return (
    <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-5 pt-4 border-t border-[rgba(0,0,0,0.06)] leading-relaxed">
      Separately, an inventory scan found {untagged.toLocaleString()} untagged of{' '}
      {scanned.toLocaleString()} resources
      {untaggedBilled !== null && (
        <> — {untaggedBilled.toLocaleString()} of them cost money this month</>
      )}
      . The scan also sees resources that are billed nothing yet, such as idle AMIs and
      snapshots, which is why its count is larger than anything above.
    </p>
  );
};

// ── The fallback view: resource counts, for accounts with no report ──────────

const InventoryView: React.FC<{
  taggingCompliance: TaggingCompliance;
  pagination: PaginationState;
  onPaginationChange: (updates: Partial<PaginationState>) => void;
}> = ({ taggingCompliance, pagination, onPaginationChange }) => {
  const compliancePercentage = taggingCompliance?.compliancePercentage || 0;
  const totalResourcesScanned = taggingCompliance?.totalResourcesScanned || 0;
  const untaggedResourcesCount = taggingCompliance?.untaggedResourcesCount || 0;
  const untaggedResources = taggingCompliance?.untaggedResources || [];

  const totalPages = Math.ceil(untaggedResourcesCount / pagination.itemsPerPage);
  const start = (pagination.currentPage - 1) * pagination.itemsPerPage;
  const paginatedResources = untaggedResources.slice(start, start + pagination.itemsPerPage);

  const colors = rateColor(compliancePercentage);

  return (
    <>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        <Kpi
          label="Compliance rate"
          value={`${compliancePercentage.toFixed(1)}%`}
          valueColor={colors.text}
          icon="mdi:percent"
          iconBg={colors.bg}
          iconColor={colors.text}
          bar={{ pct: compliancePercentage, color: colors.bar }}
        />
        <Kpi
          label="Resources scanned"
          value={totalResourcesScanned.toLocaleString()}
          valueColor="rgba(0,0,0,0.88)"
          caption="Total resources analyzed"
          icon="mdi:server-outline"
          iconBg="#4285F414"
          iconColor="#4285F4"
        />
        <Kpi
          label="Untagged resources"
          value={untaggedResourcesCount.toLocaleString()}
          valueColor={untaggedResourcesCount === 0 ? '#158045' : '#dc2626'}
          caption="Missing required tags"
          icon={untaggedResourcesCount === 0 ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline'}
          iconBg={untaggedResourcesCount === 0 ? '#f0fdf4' : '#fef2f2'}
          iconColor={untaggedResourcesCount === 0 ? '#158045' : '#dc2626'}
        />
      </div>

      {untaggedResources.length > 0 && (
        <div>
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-red-500 inline-block" />
              <h4 className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">
                Untagged Resources Details
              </h4>
              <span className="px-2 py-0.5 text-[11px] font-medium bg-red-50 text-red-600 border border-red-100 rounded-full">
                {untaggedResourcesCount} total
              </span>
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="taggingItemsPerPage" className="text-[12px] text-[rgba(0,0,0,0.45)]">
                Show:
              </label>
              <select
                id="taggingItemsPerPage"
                value={pagination.itemsPerPage}
                onChange={(e) =>
                  onPaginationChange({ itemsPerPage: Number(e.target.value), currentPage: 1 })
                }
                className="px-2 py-1 text-[12px] border border-[rgba(0,0,0,0.12)] rounded-lg focus:ring-2 focus:ring-blue-200 focus:border-blue-500 outline-none"
              >
                <option value="10">10</option>
                <option value="25">25</option>
                <option value="50">50</option>
                <option value="100">100</option>
              </select>
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource ID</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Type</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Region</th>
                  <th className="text-left px-4 py-2.5 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Missing</th>
                </tr>
              </thead>
              <tbody>
                {paginatedResources.map((resource) => (
                  <tr key={resource.resourceId} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.02)]">
                    <td className="px-4 py-3 font-mono text-[12px] text-[rgba(0,0,0,0.88)]">{resource.resourceId}</td>
                    <td className="px-4 py-3">
                      <span className="px-2 py-0.5 text-[11px] font-medium bg-indigo-50 text-indigo-700 border border-indigo-100 rounded-full">
                        {resource.resourceType}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-[14px] text-[rgba(0,0,0,0.88)]">{resource.region}</td>
                    <td className="px-4 py-3">
                      {/* The scan already knows which tags are absent; saying "Missing"
                          and stopping made the reader go and look it up. */}
                      <span className="flex flex-wrap gap-1">
                        {(resource.missingTags ?? []).map((tag) => (
                          <span
                            key={tag}
                            className="px-2 py-0.5 text-[10.5px] font-mono font-medium bg-red-50 text-red-600 border border-red-100 rounded-full"
                          >
                            {tag}
                          </span>
                        ))}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            currentPage={pagination.currentPage}
            totalPages={totalPages}
            totalItems={untaggedResourcesCount}
            itemsPerPage={pagination.itemsPerPage}
            onPageChange={(page) => onPaginationChange({ currentPage: page })}
          />
        </div>
      )}

      {untaggedResources.length === 0 && totalResourcesScanned > 0 && (
        <div className="text-center py-10 rounded-xl border-2 border-dashed border-green-200">
          <div className="w-12 h-12 bg-green-50 rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:check-circle-outline" width={24} color="#158045" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Resources Tagged</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            All scanned resources have the required tags. Great job!
          </p>
        </div>
      )}

      {totalResourcesScanned === 0 && (
        <div className="text-center py-10 rounded-xl border-2 border-dashed border-[rgba(0,0,0,0.1)]">
          <div className="w-12 h-12 bg-[rgba(0,0,0,0.04)] rounded-full flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:tag-multiple-outline" width={22} color="rgba(0,0,0,0.3)" />
          </div>
          <p className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Tagging Data Available</p>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            Tagging compliance data will appear here once resources are scanned.
          </p>
        </div>
      )}
    </>
  );
};

/** One headline number, in the card shape the rest of FinOps uses. */
const Kpi: React.FC<{
  label: string;
  value: string;
  valueColor: string;
  valueSize?: string;
  caption?: string;
  icon: string;
  iconBg: string;
  iconColor: string;
  bar?: { pct: number; color: string };
}> = ({ label, value, valueColor, valueSize = '28px', caption, icon, iconBg, iconColor, bar }) => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4">
    <div className="flex items-start justify-between">
      <div className="min-w-0">
        <p className="text-[10px] text-[rgba(0,0,0,0.35)] uppercase tracking-wide font-semibold">
          {label}
        </p>
        <p
          className="font-extrabold mt-1 leading-tight truncate"
          style={{
            fontFamily: "'Barlow Condensed', 'Inter', sans-serif",
            fontSize: valueSize,
            color: valueColor,
          }}
          title={value}
        >
          {value}
        </p>
        {bar && (
          <div className="mt-2 bg-[rgba(0,0,0,0.06)] rounded-full h-1.5 overflow-hidden w-24">
            <div
              className="h-1.5 rounded-full transition-all duration-500"
              style={{ width: `${Math.min(bar.pct, 100)}%`, backgroundColor: bar.color }}
            />
          </div>
        )}
        {caption && <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1">{caption}</p>}
      </div>
      <div
        className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0"
        style={{ backgroundColor: iconBg }}
      >
        <Icon icon={icon} width={20} color={iconColor} />
      </div>
    </div>
  </div>
);

export default FinOpsTaggingCompliance;
