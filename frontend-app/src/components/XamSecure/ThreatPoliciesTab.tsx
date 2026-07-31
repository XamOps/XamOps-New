import React, { useMemo, useState } from 'react';
import type { ThreatPolicy } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';
import FilterChips from './FilterChips';

interface Props {
  data: ThreatPolicy[] | null;
  isLoading: boolean;
}

const prettyCategory = (c: string | null) =>
  (c ?? '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (m) => m.toUpperCase()) || '—';

/** Threat detection policies — mirrors Upwind's Threats → Policies. */
const ThreatPoliciesTab: React.FC<Props> = ({ data, isLoading }) => {
  const all = data ?? [];
  const [enabledOnly, setEnabledOnly] = useState(false);
  const [category, setCategory] = useState<string | null>(null);

  const filteredByEnabled = useMemo(
    () => (enabledOnly ? all.filter((p) => p.enabled) : all),
    [all, enabledOnly]);

  // Categories reflect the enabled filter so the chip counts always match what's listed.
  const categories = useMemo(() => {
    const counts = new Map<string, number>();
    for (const p of filteredByEnabled) {
      const key = p.category ?? 'UNCATEGORISED';
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return [...counts.entries()].sort((a, b) => b[1] - a[1]);
  }, [filteredByEnabled]);

  const filtered = useMemo(
    () => (category === null
      ? filteredByEnabled
      : filteredByEnabled.filter((p) => (p.category ?? 'UNCATEGORISED') === category)),
    [filteredByEnabled, category]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(filtered, 50);

  const totals = useMemo(() => ({
    policies: all.length,
    enabled: all.filter((p) => p.enabled).length,
    openIssues: all.reduce((a, p) => a + (p.openIssues || 0), 0),
  }), [all]);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading threat policies...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Stat value={totals.policies} label="Total policies" />
        <Stat value={totals.enabled} label="Enabled" accent="text-emerald-600" />
        <Stat value={totals.openIssues} label="Open issues" accent="text-red-600" />
      </div>

      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="flex flex-wrap items-center gap-3 px-5 py-3 border-b border-gray-100">
          <label className="flex items-center gap-1.5 text-xs text-gray-600">
            <input type="checkbox" checked={enabledOnly} onChange={(e) => { setEnabledOnly(e.target.checked); setPage(0); }} />
            Enabled only
          </label>
          {/* Upwind groups policies by category; 49 flat rows are hard to scan without it. */}
          <FilterChips
            options={categories.map(([c, n]) => ({ value: c, label: prettyCategory(c), count: n }))}
            active={category}
            allCount={filteredByEnabled.length}
            onChange={(v) => { setCategory(v); setPage(0); }}
          />
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Policy</th>
                <th className="px-5 py-2.5">Category</th>
                <th className="px-5 py-2.5">Scope</th>
                <th className="px-5 py-2.5">Open issues</th>
                <th className="px-5 py-2.5">Managed by</th>
                <th className="px-5 py-2.5">Status</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <tr key={p.id} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-5 py-3 font-medium text-gray-800">{p.displayName ?? '—'}</td>
                  <td className="px-5 py-3 text-gray-600">{prettyCategory(p.category)}</td>
                  <td className="px-5 py-3 text-gray-500">{prettyCategory(p.scope)}</td>
                  <td className="px-5 py-3">
                    <span className={p.openIssues > 0 ? 'font-bold text-red-600' : 'text-gray-400'}>{p.openIssues}</span>
                  </td>
                  <td className="px-5 py-3 text-gray-500">{p.managedBy ?? '—'}</td>
                  <td className="px-5 py-3">
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                      p.enabled ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-500'}`}>
                      {p.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr><td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  No threat policies for the selected account.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} unit="policies" onPage={setPage} />
      </div>
    </div>
  );
};

const Stat: React.FC<{ value: number; label: string; accent?: string }> = ({ value, label, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <p className={`text-2xl font-bold ${accent ?? 'text-gray-900'}`}>{value.toLocaleString()}</p>
    <p className="text-xs text-gray-500 mt-1">{label}</p>
  </div>
);

export default ThreatPoliciesTab;
