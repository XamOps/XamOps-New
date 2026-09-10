import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { CodeScan, CodeScansResponse, CodeScanFindings } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';
import CodeScanDrawer from './CodeScanDrawer';
import FilterChips from './FilterChips';

interface Props {
  data: CodeScansResponse | null;
  isLoading: boolean;
  loadFindings: (fingerprint: string) => Promise<CodeScanFindings | null>;
}

const fmt = (iso: string | null): string => (iso ? new Date(iso).toLocaleString() : '—');

/**
 * One palette per scan kind, carried through the tab, the volume bars and the table cell so the
 * two kinds are distinguishable at a glance. `color` was declared here from the start and never
 * referenced, which is why every row rendered in the same grey.
 */
const TYPE_META: Record<string, {
  label: string; icon: string; text: string; bar: string; tint: string;
}> = {
  IMAGE_BUILD: {
    label: 'Image build', icon: 'logos:docker-icon',
    text: 'text-blue-700', bar: 'bg-blue-500',
    tint: 'border-blue-200 bg-blue-50 text-blue-800',
  },
  IAC_SCAN_BUILD: {
    label: 'IaC scan', icon: 'simple-icons:terraform',
    text: 'text-purple-700', bar: 'bg-purple-500',
    tint: 'border-purple-200 bg-purple-50 text-purple-800',
  },
};

/**
 * Avatar tint for an initiator, deterministic on the name.
 *
 * Purely to break up a column of identical grey text — the colour carries no meaning and must not
 * be read as one, which is why none of these are the red/amber/emerald used for severity.
 */
const AVATAR_TINTS = [
  'bg-sky-100 text-sky-700', 'bg-violet-100 text-violet-700', 'bg-teal-100 text-teal-700',
  'bg-indigo-100 text-indigo-700', 'bg-fuchsia-100 text-fuchsia-700', 'bg-cyan-100 text-cyan-700',
];
const avatarTint = (name: string): string => {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) % 997;
  return AVATAR_TINTS[h % AVATAR_TINTS.length];
};

const SOURCE_ICON: Record<string, string> = {
  GITHUB: 'mdi:github',
  GITLAB: 'mdi:gitlab',
  BITBUCKET: 'mdi:bitbucket',
};

/**
 * Code — Upwind's shift-left CI scan history.
 *
 * This answers a question the runtime vulnerability data cannot: not "what is deployed", but
 * "what is this commit about to add". That distinction is why the drawer leads with introduced
 * CVEs rather than a total.
 *
 * Scans without a fingerprint are shown but not clickable — the findings route is keyed on it, so
 * there is nothing to open. Roughly one scan in nine is in that state.
 */
const CodeTab: React.FC<Props> = ({ data, isLoading, loadFindings }) => {
  const [selected, setSelected] = useState<CodeScan | null>(null);
  const [branch, setBranch] = useState<string | null>(null);
  const scans = data?.scans ?? [];

  // Upwind splits this page by scan kind (IaC / SCA / Admission controller) before anything else,
  // and the two kinds answer different questions — a Terraform misconfiguration and a dependency
  // CVE are not comparable rows. Labels come from our own `type` values rather than borrowing
  // theirs, since IMAGE_BUILD -> SCA is an inference we have not verified.
  const [kind, setKind] = useState<string>('IMAGE_BUILD');
  const kindCounts = useMemo(() => {
    const c: Record<string, number> = {};
    scans.forEach((s) => { const k = s.type ?? 'OTHER'; c[k] = (c[k] ?? 0) + 1; });
    return c;
  }, [scans]);

  // Newest first. Upwind returns the scan stream in its own order — in practice oldest first —
  // and neither the backend nor this component reordered it. The table therefore opened on scans
  // from weeks earlier while the chart showed the last 14 days, so the two panels appeared to
  // describe unrelated periods. A CI history should lead with the most recent scan regardless.
  const all = useMemo(() => {
    const ts = (s: CodeScan) => (s.createTime ? new Date(s.createTime).getTime() : 0);
    return scans
      .filter((s) => (s.type ?? 'OTHER') === kind)
      .slice()
      .sort((a, b) => ts(b) - ts(a));
  }, [scans, kind]);
  const activeMeta = TYPE_META[kind];

  const totals = useMemo(() => ({
    branches: new Set(scans.map((s) => s.branch).filter(Boolean)).size,
    initiators: new Set(scans.map((s) => s.initiator).filter(Boolean)).size,
  }), [scans]);

  const branches = useMemo(() => {
    const counts: Record<string, number> = {};
    all.forEach((s) => { if (s.branch) counts[s.branch] = (counts[s.branch] ?? 0) + 1; });
    // Main branches first, then busiest — a long tail of feature branches is not worth chips.
    return Object.entries(counts)
      .sort((a, b) => {
        const aMain = a[0] === 'main' || a[0] === 'master';
        const bMain = b[0] === 'main' || b[0] === 'master';
        if (aMain !== bMain) return aMain ? -1 : 1;
        return b[1] - a[1];
      })
      .slice(0, 6);
  }, [all]);

  const filtered = useMemo(
    () => (branch === null ? all : all.filter((s) => s.branch === branch)),
    [all, branch]);
  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(filtered, 50);

  // Scan volume per day. Deliberately not coloured by outcome: Upwind's console tints these bars
  // by its deploy recommendation, and that verdict is computed there — it is absent from both the
  // scan event and the findings payload, so tinting them here would be invention.
  const timeline = useMemo(() => {
    const DAYS = 14;
    // Bucket boundaries walk the calendar rather than adding 86_400_000ms. Fixed-millisecond
    // steps drift by an hour across a DST change, which silently shifts a day's scans into the
    // neighbouring bucket for anyone outside a no-DST timezone.
    const buckets: { label: string; axisLabel: string; count: number }[] = [];
    for (let i = DAYS - 1; i >= 0; i--) {
      const dayStart = new Date();
      dayStart.setHours(0, 0, 0, 0);
      dayStart.setDate(dayStart.getDate() - i);
      const dayEnd = new Date(dayStart);
      dayEnd.setDate(dayEnd.getDate() + 1);

      const start = dayStart.getTime();
      const end = dayEnd.getTime();

      // Month is shown on the first column and whenever the month turns over, so the axis reads
      // as a date range. Bare day numbers ("31 01 02 …") gave no way to tell what period the
      // chart covered, which is why it looked unrelated to the dates in the table below.
      const isMonthBoundary = i === DAYS - 1 || dayStart.getDate() === 1;

      // Day and month are composed explicitly rather than taken from a localised string.
      // `toLocaleDateString({ day, month })` orders them by locale — "31 Jul" under en-GB but
      // "Jul 31" under en-US — and the axis previously took the first token of that string, so
      // US users saw the month repeated down the axis instead of the day of the month.
      const dayNum = String(dayStart.getDate()).padStart(2, '0');
      const monthName = dayStart.toLocaleDateString(undefined, { month: 'short' });

      buckets.push({
        label: `${dayNum} ${monthName}`,
        axisLabel: isMonthBoundary ? `${dayNum} ${monthName}` : dayNum,
        count: all.filter((s) => {
          if (!s.createTime) return false;
          const t = new Date(s.createTime).getTime();
          return t >= start && t < end;
        }).length,
      });
    }
    return buckets;
  }, [all]);
  const timelineMax = Math.max(1, ...timeline.map((b) => b.count));
  // The chart is a 14-day window while the table lists every scan. Stating the subset stops the
  // two panels reading as a contradiction when most scans predate the window.
  const timelineTotal = timeline.reduce((sum, b) => sum + b.count, 0);

  if (isLoading && scans.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading code scans...</div>;
  }

  // Withheld rather than empty: say so plainly instead of showing a bare "no data" table.
  if (data && !data.available) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-10 text-center">
        <Icon icon="mdi:source-branch" width={28} className="mx-auto text-gray-300" />
        <p className="mt-3 text-sm font-semibold text-gray-700">Code scans not shown</p>
        <p className="mt-1 mx-auto max-w-md text-xs text-gray-500 leading-relaxed">
          {data.unavailableReason
            ?? 'Code scan history is reported across the whole organization.'}
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Organization-wide, and said so explicitly. Every other XamSecure tab is scoped to the
          account picker; this one cannot be, because CI scan events belong to a source repository
          rather than a cloud account. Without this notice, seeing repositories while a single
          account is selected reads as a data leak. Keep it: it is a tenancy clarification, not a
          statement about any upstream API. */}
      <div className="flex items-start gap-2 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
        <Icon icon="mdi:office-building-outline" width={14}
              className="text-gray-400 mt-0.5 flex-shrink-0" />
        <p className="text-[11px] text-gray-600">
          <span className="font-semibold">Organization-wide.</span> CI scans belong to a source
          repository rather than a cloud account, so this tab is not filtered by the account
          selector.
        </p>
      </div>

      {/* Scan kind first, as Upwind orders it. Admission controller is theirs and reported 0;
          we have no scan type for it, so it is not shown rather than shown empty. */}
      <div className="flex items-center gap-2">
        {Object.entries(TYPE_META).map(([type, meta]) => (
          <KindTab key={type} label={meta.label} icon={meta.icon} tint={meta.tint}
                   count={kindCounts[type] ?? 0}
                   active={kind === type}
                   onClick={() => { setKind(type); setBranch(null); setPage(0); }} />
        ))}
      </div>

      {/* Counts describe the whole stream, not the selected kind, so they hold still while
          switching tabs. Every one is derived from the list already in hand — no extra request. */}
      <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
        <Stat icon="mdi:source-branch-sync" label="CI scans" value={scans.length}
              accent="text-gray-900" />
        <Stat icon="logos:docker-icon" label="Image builds"
              value={kindCounts.IMAGE_BUILD ?? 0} accent="text-blue-700" />
        <Stat icon="simple-icons:terraform" label="IaC scans"
              value={kindCounts.IAC_SCAN_BUILD ?? 0} accent="text-purple-700" />
        <Stat icon="mdi:source-branch" label="Branches" value={totals.branches}
              accent="text-emerald-700" />
        <Stat icon="mdi:account-multiple-outline" label="Contributors"
              value={totals.initiators} accent="text-amber-700" />
      </div>

      {/* Scan volume, so a gap in CI shows up as a gap. */}
      {all.length > 0 && (
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm px-5 py-4">
          <div className="flex items-baseline justify-between">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Scans per day
            </p>
            <p className="text-[10px] text-gray-400">
              {timelineTotal.toLocaleString()} of {all.length.toLocaleString()} in last 14 days
            </p>
          </div>
          {/* The track carries a fixed height (h-24), not `height: 100%`. The row is
              `items-end`, so columns are not stretched and a percentage height had nothing to
              resolve against — the track collapsed to zero and only the labels rendered. This
              mirrors the trend chart in ThreatsDashboard, which is the structure known to work. */}
          <div className="mt-3 flex items-end justify-between gap-1.5 h-32">
            {timeline.map((b) => (
              <div key={b.label} className="flex-1 flex flex-col items-center gap-1 min-w-0">
                {/* Reserves its line even at zero, so bars do not shift as counts change. */}
                <span className={`text-[9px] font-semibold leading-none ${
                  b.count > 0 ? 'text-gray-500' : 'text-transparent'}`}>
                  {b.count > 0 ? b.count : '0'}
                </span>
                <div className="w-full flex flex-col justify-end h-24"
                     title={`${b.label} — ${b.count} scan${b.count === 1 ? '' : 's'}`}>
                  {/* Tinted by scan kind, which is real data. Upwind tints by deploy
                      recommendation; that verdict is not in the API, so this is the honest
                      dimension to colour by. */}
                  {b.count > 0
                    ? <div data-testid="scan-bar"
                           className={`w-full rounded-sm ${activeMeta?.bar ?? 'bg-gray-800'}`}
                           style={{ height: `${(b.count / timelineMax) * 100}%` }} />
                    // A day with no CI activity is a fact worth seeing, not a blank column.
                    : <div className="w-full h-0.5 bg-gray-100 rounded" />}
                </div>
                <span className="text-[9px] text-gray-400 whitespace-nowrap">
                  {b.axisLabel}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {branches.length > 1 && (
        <FilterChips
          options={branches.map(([b, n]) => ({ value: b, label: b, count: n }))}
          active={branch}
          allCount={all.length}
          onChange={(v) => { setBranch(v); setPage(0); }}
        />
      )}

      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            CI scans
          </p>
          {/* Upwind's search has no sort and an unusable date filter, so the backend keeps the
              newest N scans. Saying so stops a clipped history reading as the complete record. */}
          <span className="text-[10px] text-gray-400">
            {total.toLocaleString()} scans
            {data?.historyTruncated && ' · newest only, older history not loaded'}
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Repository</th>
                <th className="px-3 py-2.5">Branch</th>
                <th className="px-3 py-2.5">Type</th>
                <th className="px-3 py-2.5">Commit</th>
                <th className="px-3 py-2.5">Initiator</th>
                <th className="px-3 py-2.5">PR</th>
                <th className="px-3 py-2.5">Scanned</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s, i) => {
                const meta = TYPE_META[s.type ?? ''] ?? null;
                const openable = !!s.fingerprint;
                return (
                  <tr key={`${s.fingerprint ?? 'nofp'}-${s.commitSha ?? i}`}
                      className={`border-b border-gray-50 ${
                        openable ? 'hover:bg-gray-50 cursor-pointer' : 'opacity-70'}`}
                      title={openable ? undefined : 'This scan has no fingerprint, so no findings are available for it'}
                      onClick={openable ? () => setSelected(s) : undefined}>
                    <td className="px-5 py-3">
                      <span className="flex items-center gap-1.5 min-w-0">
                        <Icon icon={SOURCE_ICON[s.source ?? ''] ?? 'mdi:source-repository'}
                              width={13} className="flex-shrink-0 text-gray-500" />
                        <span className="font-medium text-gray-800 truncate">
                          {s.repository ?? '—'}
                        </span>
                      </span>
                    </td>
                    <td className="px-3 py-3">
                      <span className="flex items-center gap-1 text-gray-600">
                        <Icon icon="mdi:source-branch" width={12} className="text-gray-400" />
                        <span className="truncate max-w-[180px]" title={s.branch ?? ''}>
                          {s.branch ?? '—'}
                        </span>
                      </span>
                    </td>
                    <td className="px-3 py-3">
                      {meta ? (
                        <span className={`inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5
                                          text-[10px] font-semibold ${meta.tint}`}>
                          <Icon icon={meta.icon} width={12} className="flex-shrink-0" />
                          {meta.label}
                        </span>
                      ) : <span className="text-gray-400">{s.type ?? '—'}</span>}
                    </td>
                    <td className="px-3 py-3 font-mono text-[11px] text-gray-500">
                      {s.commitSha ? s.commitSha.slice(0, 7) : '—'}
                    </td>
                    <td className="px-3 py-3">
                      {s.initiator ? (
                        <span className="flex items-center gap-1.5 min-w-0">
                          <span className={`flex h-5 w-5 flex-shrink-0 items-center justify-center
                                            rounded-full text-[9px] font-bold uppercase
                                            ${avatarTint(s.initiator)}`}>
                            {s.initiator.slice(0, 2)}
                          </span>
                          <span className="truncate max-w-[110px] text-gray-600"
                                title={s.initiator}>{s.initiator}</span>
                        </span>
                      ) : <span className="text-gray-300">—</span>}
                    </td>
                    <td className="px-3 py-3">
                      {s.pullRequestUrl ? (
                        <a href={s.pullRequestUrl} target="_blank" rel="noreferrer noopener"
                           onClick={(e) => e.stopPropagation()}
                           className="inline-flex items-center gap-1 font-medium text-blue-700 hover:text-blue-900 underline decoration-dotted">
                          {s.pullRequestId ? `#${s.pullRequestId}` : 'Open'}
                          <Icon icon="mdi:arrow-top-right" width={11} />
                        </a>
                      ) : <span className="text-gray-300">—</span>}
                    </td>
                    <td className="px-3 py-3 whitespace-nowrap text-gray-500">
                      {fmt(s.createTime)}
                    </td>
                  </tr>
                );
              })}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                    No CI scans reported.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} onPage={setPage} unit="scans" />
      </div>

      <p className="px-1 text-[10px] leading-relaxed text-gray-400">
        Open a scan to see its introduced, pre-existing and resolved CVEs.
      </p>

      {selected?.fingerprint && (
        <CodeScanDrawer scan={selected} load={loadFindings} onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

/** Compact rollup tile, matching the classification strip on Inventory. */
const Stat: React.FC<{ icon: string; label: string; value: number; accent: string }> =
  ({ icon, label, value, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm px-4 py-3">
    <div className="flex items-center gap-1.5 mb-0.5">
      <Icon icon={icon} width={13} className="flex-shrink-0 text-gray-400" />
      <p className="text-[10px] text-gray-500 truncate" title={label}>{label}</p>
    </div>
    <p className={`text-xl font-bold ${accent}`}>{value.toLocaleString()}</p>
  </div>
);

const KindTab: React.FC<{
  label: string; icon: string; tint: string; count: number; active: boolean; onClick: () => void;
}> = ({ label, icon, tint, count, active, onClick }) => (
  <button
    type="button"
    onClick={onClick}
    aria-pressed={active}
    // The selected kind carries its own colour rather than a generic dark pill, so the tab, the
    // volume bars and the table cell all read as one thing.
    className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold
                transition-colors focus:outline-none focus-visible:ring-2
                focus-visible:ring-gray-900 ${
      active ? tint : 'border-gray-200 bg-white text-gray-600 hover:bg-gray-50'}`}
  >
    <Icon icon={icon} width={13} className="flex-shrink-0" />
    {label}
    <span className={active ? 'opacity-70' : 'text-gray-400'}>{count}</span>
  </button>
);

export default CodeTab;
