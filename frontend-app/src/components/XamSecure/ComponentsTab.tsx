import React, { useMemo, useState } from 'react';
import type { SbomComponent } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';

interface Props {
  data: SbomComponent[] | null;
  isLoading: boolean;
}

/** Software components (SBOM) — mirrors Upwind's Components view. */
const ComponentsTab: React.FC<Props> = ({ data, isLoading }) => {
  const all = data ?? [];
  const [query, setQuery] = useState('');
  const [inUseOnly, setInUseOnly] = useState(false);
  const [vulnOnly, setVulnOnly] = useState(false);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return all.filter((c) => {
      if (inUseOnly && c.inUse <= 0) return false;
      if (vulnOnly && c.totalVulns <= 0) return false;
      if (q && !(`${c.name ?? ''} ${c.version ?? ''} ${c.packageManager ?? ''}`.toLowerCase().includes(q))) return false;
      return true;
    });
  }, [all, query, inUseOnly, vulnOnly]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(filtered, 50);

  const totals = useMemo(() => ({
    packages: all.length,
    vulnerable: all.filter((c) => c.totalVulns > 0).length,
    inUse: all.filter((c) => c.inUse > 0).length,
  }), [all]);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading software components...</div>;
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <Stat value={totals.packages} label="Total packages" />
        <Stat value={totals.vulnerable} label="Vulnerable packages" accent="text-red-600" />
        <Stat value={totals.inUse} label="In use at runtime" accent="text-emerald-600" />
      </div>

      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="flex flex-wrap items-center gap-3 px-5 py-3 border-b border-gray-100">
          <input
            value={query}
            onChange={(e) => { setQuery(e.target.value); setPage(0); }}
            placeholder="Search package or version..."
            className="flex-1 min-w-[180px] text-xs border border-gray-200 rounded-lg px-3 py-2 focus:outline-none focus:ring-1 focus:ring-gray-300"
          />
          <label className="flex items-center gap-1.5 text-xs text-gray-600">
            <input type="checkbox" checked={inUseOnly} onChange={(e) => { setInUseOnly(e.target.checked); setPage(0); }} />
            In use only
          </label>
          <label className="flex items-center gap-1.5 text-xs text-gray-600">
            <input type="checkbox" checked={vulnOnly} onChange={(e) => { setVulnOnly(e.target.checked); setPage(0); }} />
            Vulnerable only
          </label>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Package</th>
                <th className="px-5 py-2.5">Version</th>
                <th className="px-5 py-2.5">Manager</th>
                <th className="px-5 py-2.5">Framework</th>
                <th className="px-5 py-2.5">Licenses</th>
                <th className="px-5 py-2.5">Vulnerabilities</th>
                <th className="px-5 py-2.5">In use</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c, i) => (
                <tr key={`${c.name}-${c.version}-${i}`} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-5 py-3 font-medium text-gray-800 max-w-xs truncate">{c.name ?? '—'}</td>
                  <td className="px-5 py-3 font-mono text-gray-600">{c.version ?? '—'}</td>
                  <td className="px-5 py-3 text-gray-500">{c.packageManager ?? '—'}</td>
                  <td className="px-5 py-3 text-gray-500">{c.framework ?? '—'}</td>
                  <td className="px-5 py-3 text-gray-500 max-w-[140px] truncate">{c.licenses.join(', ') || '—'}</td>
                  <td className="px-5 py-3"><VulnBadges c={c} /></td>
                  <td className="px-5 py-3">
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                      c.inUse > 0 ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-500'}`}>
                      {c.inUse > 0 ? `${c.inUse} in use` : 'Not in use'}
                    </span>
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr><td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                  No software components for the selected account.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} unit="packages" onPage={setPage} />
      </div>
    </div>
  );
};

const VulnBadges: React.FC<{ c: SbomComponent }> = ({ c }) => {
  if (c.totalVulns <= 0) return <span className="text-gray-400">None</span>;
  return (
    <div className="flex items-center gap-1">
      {c.critical > 0 && <Badge n={c.critical} className="bg-red-500 text-white" />}
      {c.high > 0 && <Badge n={c.high} className="bg-orange-500 text-white" />}
      {c.medium > 0 && <Badge n={c.medium} className="bg-yellow-400 text-gray-900" />}
      {c.low > 0 && <Badge n={c.low} className="bg-emerald-500 text-white" />}
    </div>
  );
};

const Badge: React.FC<{ n: number; className: string }> = ({ n, className }) => (
  <span className={`px-1.5 py-0.5 rounded text-[10px] font-bold ${className}`}>{n}</span>
);

const Stat: React.FC<{ value: number; label: string; accent?: string }> = ({ value, label, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <p className={`text-2xl font-bold ${accent ?? 'text-gray-900'}`}>{value.toLocaleString()}</p>
    <p className="text-xs text-gray-500 mt-1">{label}</p>
  </div>
);

export default ComponentsTab;
