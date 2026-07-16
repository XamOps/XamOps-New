import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { ApiEndpoint } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';

interface Props {
  data: ApiEndpoint[] | null;
  isLoading: boolean;
}

const METHOD_COLOR: Record<string, string> = {
  GET: '#16a34a', POST: '#2563eb', PUT: '#d97706', PATCH: '#9333ea',
  DELETE: '#dc2626', HEAD: '#0891b2', OPTIONS: '#64748b',
};
const methodColor = (m: string) => METHOD_COLOR[m?.toUpperCase()] ?? '#64748b';

/**
 * APIs — mirrors Upwind's API Security dashboard using only real endpoint data:
 * an endpoints funnel (total / internet-exposed / authenticated / unauthenticated),
 * a breakdown by HTTP method, top domains, and the endpoint inventory table.
 * Upwind's sensitive-data classification and API-vulnerability panels are omitted —
 * that data is not exposed by the API.
 */
const ApiSecurityTab: React.FC<Props> = ({ data, isLoading }) => {
  const all = useMemo(() => data ?? [], [data]);

  const stats = useMemo(() => {
    let exposed = 0, authed = 0, unauth = 0;
    const byMethod: Record<string, number> = {};
    const byDomain: Record<string, number> = {};
    for (const e of all) {
      if (e.internetExposed) exposed += 1;
      if (e.authenticated) authed += 1;
      // Only an explicit UNAUTHENTICATED counts (absent state = not analysed, like Upwind).
      if ((e.authState ?? '').toUpperCase() === 'UNAUTHENTICATED') unauth += 1;
      const m = (e.method ?? 'OTHER').toUpperCase();
      byMethod[m] = (byMethod[m] ?? 0) + 1;
      (e.domains ?? []).forEach((d) => { if (d) byDomain[d] = (byDomain[d] ?? 0) + 1; });
    }
    const methods = Object.entries(byMethod).sort((a, b) => b[1] - a[1]);
    const domains = Object.entries(byDomain).sort((a, b) => b[1] - a[1]).slice(0, 8);
    return { total: all.length, exposed, authed, unauth, methods, domains };
  }, [all]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(all, 50);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading API endpoints...</div>;
  }

  const maxMethod = Math.max(1, ...stats.methods.map(([, n]) => n));

  return (
    <div className="space-y-4">
      {/* Funnel stat cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Stat icon="mdi:api" label="Endpoints" value={stats.total} accent="text-gray-900" />
        <Stat icon="mdi:earth" label="Internet exposed" value={stats.exposed} accent="text-red-600" />
        <Stat icon="mdi:lock-check" label="Authenticated" value={stats.authed} accent="text-emerald-600" />
        <Stat icon="mdi:lock-open-alert" label="Unauthenticated" value={stats.unauth} accent="text-orange-600" />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Endpoints by method */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">Endpoints by method</p>
          <div className="space-y-2.5">
            {stats.methods.map(([m, n]) => (
              <div key={m} className="flex items-center gap-3">
                <span className="w-16 text-[11px] font-mono font-bold" style={{ color: methodColor(m) }}>{m}</span>
                <div className="flex-1 h-2.5 bg-gray-100 rounded-full overflow-hidden">
                  <div className="h-full rounded-full" style={{ width: `${(n / maxMethod) * 100}%`, backgroundColor: methodColor(m) }} />
                </div>
                <span className="w-12 text-right text-xs font-semibold text-gray-600">{n}</span>
              </div>
            ))}
            {stats.methods.length === 0 && <p className="text-xs text-gray-400">No endpoints.</p>}
          </div>
        </div>

        {/* Top domains */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">Top domains</p>
          <div className="space-y-2">
            {stats.domains.map(([d, n]) => (
              <div key={d} className="flex items-center justify-between gap-3 text-xs">
                <span className="flex items-center gap-1.5 text-gray-700 truncate">
                  <Icon icon="mdi:web" width={14} className="text-gray-400 flex-shrink-0" />
                  <span className="truncate" title={d}>{d}</span>
                </span>
                <span className="font-semibold text-gray-600 flex-shrink-0">{n}</span>
              </div>
            ))}
            {stats.domains.length === 0 && (
              <p className="text-xs text-gray-400">No domain data for these endpoints.</p>
            )}
          </div>
        </div>
      </div>

      {/* Endpoint inventory */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Endpoints ({stats.total})
          </p>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Method</th>
                <th className="px-5 py-2.5">Endpoint</th>
                <th className="px-5 py-2.5">Auth</th>
                <th className="px-5 py-2.5">Internet Exposed</th>
                <th className="px-5 py-2.5">First Seen</th>
                <th className="px-5 py-2.5">Last Seen</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id} className="border-b border-gray-50 hover:bg-gray-50">
                  <td className="px-5 py-3 font-mono font-bold" style={{ color: methodColor(e.method ?? '') }}>{e.method}</td>
                  <td className="px-5 py-3 font-mono text-gray-800 max-w-md truncate">
                    {e.uri}
                    {e.domains && e.domains.length > 0 && (
                      <span className="block text-[10px] text-gray-400">{e.domains.join(', ')}</span>
                    )}
                  </td>
                  <td className="px-5 py-3">
                    {(e.authState ?? '').toUpperCase() === 'AUTHENTICATED' ? (
                      <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-700">Authenticated</span>
                    ) : (e.authState ?? '').toUpperCase() === 'UNAUTHENTICATED' ? (
                      <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-orange-100 text-orange-700">Unauthenticated</span>
                    ) : (
                      <span className="text-[10px] text-gray-300">—</span>
                    )}
                  </td>
                  <td className="px-5 py-3">
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${
                      e.internetExposed ? 'bg-red-500 text-white' : 'bg-emerald-100 text-emerald-700'}`}>
                      {e.internetExposed ? 'Exposed' : 'Internal'}
                    </span>
                  </td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                    {e.firstSeenTime ? new Date(e.firstSeenTime).toLocaleDateString() : '—'}
                  </td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                    {e.lastSeenTime ? new Date(e.lastSeenTime).toLocaleDateString() : '—'}
                  </td>
                </tr>
              ))}
              {rows.length === 0 && (
                <tr><td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  No API endpoints discovered for the selected account.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} unit="endpoints" onPage={setPage} />
      </div>
    </div>
  );
};

const Stat: React.FC<{ icon: string; label: string; value: number; accent: string }> = ({ icon, label, value, accent }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <div className="flex items-center gap-2 mb-1">
      <Icon icon={icon} width={16} className="text-gray-400" />
      <p className="text-xs text-gray-500">{label}</p>
    </div>
    <p className={`text-2xl font-bold ${accent}`}>{value.toLocaleString()}</p>
  </div>
);

export default ApiSecurityTab;
