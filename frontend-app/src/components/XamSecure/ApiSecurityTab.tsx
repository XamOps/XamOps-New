import React, { useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { ApiEndpoint } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';
import ApiEndpointDrawer from './ApiEndpointDrawer';

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
 * an endpoints funnel, a breakdown by HTTP method, top domains, the sensitive-data
 * classification observed on each endpoint, and the endpoint inventory table.
 *
 * "Ingress observed" is deliberately not called internet exposure. The endpoint payload's
 * risk_overview.internet_exposure.ingress carries only a last_seen_time and no `exposed`
 * boolean (verified live against api-security/endpoints/search, 100/100 sampled rows had the
 * timestamp). Presence of that timestamp means traffic was seen, not that the endpoint faces
 * the internet, so labelling it "internet exposed" turned a near-constant into a red risk
 * number covering essentially every endpoint.
 *
 * Sensitive data comes from the v2 endpoint payload's risk_overview.sensitive_data — the v1
 * endpoint list we previously read carried none, which is why this panel used to be absent.
 * Upwind's per-endpoint OWASP/CWE mapping is still not exposed by any endpoint, so no
 * vulnerability-class panel is shown.
 */
const ApiSecurityTab: React.FC<Props> = ({ data, isLoading }) => {
  const all = useMemo(() => data ?? [], [data]);
  // Rows were inert before; the endpoint payload now carries enough to be worth opening.
  const [selected, setSelected] = useState<ApiEndpoint | null>(null);

  const stats = useMemo(() => {
    let exposed = 0, authed = 0, unauth = 0, sensitive = 0;
    const byMethod: Record<string, number> = {};
    const byDomain: Record<string, number> = {};
    const bySensitive: Record<string, number> = {};
    for (const e of all) {
      if (e.internetExposed) exposed += 1;
      if (e.authenticated) authed += 1;
      // Only an explicit UNAUTHENTICATED counts (absent state = not analysed, like Upwind).
      if ((e.authState ?? '').toUpperCase() === 'UNAUTHENTICATED') unauth += 1;
      const m = (e.method ?? 'OTHER').toUpperCase();
      byMethod[m] = (byMethod[m] ?? 0) + 1;
      (e.domains ?? []).forEach((d) => { if (d) byDomain[d] = (byDomain[d] ?? 0) + 1; });
      const kinds = e.sensitiveData ?? [];
      if (kinds.length > 0) {
        sensitive += 1;
        kinds.forEach((k) => { bySensitive[k] = (bySensitive[k] ?? 0) + 1; });
      }
    }
    const methods = Object.entries(byMethod).sort((a, b) => b[1] - a[1]);
    const domains = Object.entries(byDomain).sort((a, b) => b[1] - a[1]).slice(0, 8);
    const sensitiveKinds = Object.entries(bySensitive).sort((a, b) => b[1] - a[1]);
    // Endpoints Upwind hasn't classified an auth state for (not observed enough traffic).
    const unclassified = Math.max(0, all.length - authed - unauth);
    // Both sensitive_data and status_codes arrive only on the v2 endpoint payload. If every
    // row is missing both, these rows were written by the old v1 read and the tab is showing
    // stale data rather than a genuinely clean result — worth saying so instead of asserting
    // Upwind observed nothing.
    const preV2Rows = all.length > 0
      && all.every((e) => (e.statusCodes ?? []).length === 0 && (e.sensitiveData ?? []).length === 0);
    return { total: all.length, exposed, authed, unauth, unclassified, methods, domains,
             sensitive, sensitiveKinds, preV2Rows };
  }, [all]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(all, 50);

  if (isLoading && all.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading API endpoints...</div>;
  }

  const maxMethod = Math.max(1, ...stats.methods.map(([, n]) => n));

  return (
    <div className="space-y-4">
      {/* Funnel stat cards */}
      <div className="grid grid-cols-2 lg:grid-cols-6 gap-4">
        <Stat icon="mdi:api" label="Endpoints" value={stats.total} accent="text-gray-900" />
        <Stat icon="mdi:swap-horizontal" label="Ingress observed" value={stats.exposed}
              accent="text-gray-900"
              hint="Endpoints Upwind has seen inbound traffic on. The payload carries a last-seen timestamp only, not an exposure verdict, so this is not a count of internet-facing endpoints." />
        <Stat icon="mdi:lock-check" label="Authenticated" value={stats.authed} accent="text-emerald-600" />
        <Stat icon="mdi:lock-open-alert" label="Unauthenticated" value={stats.unauth} accent="text-orange-600" />
        <Stat icon="mdi:shield-account-outline" label="Sensitive data" value={stats.sensitive}
              accent="text-purple-600"
              hint="Endpoints where Upwind observed PII, PHI, PCI or secrets in traffic" />
        <Stat icon="mdi:help-circle-outline" label="Unclassified" value={stats.unclassified} accent="text-gray-500"
              hint="Upwind hasn't observed enough traffic to classify auth state" />
      </div>

      {/* Sensitive data in traffic is the thing worth acting on; called out when non-zero. */}
      {stats.sensitive > 0 && (
        <div className="flex items-start gap-2.5 bg-red-50 border border-red-200 rounded-xl p-4">
          <Icon icon="mdi:alert-octagon" width={18} className="text-red-600 flex-shrink-0 mt-0.5" />
          <div>
            <p className="text-sm font-semibold text-red-800">
              {stats.sensitive.toLocaleString()}{' '}
              {stats.sensitive === 1 ? 'endpoint carries' : 'endpoints carry'} sensitive data
            </p>
            <p className="text-xs text-red-700 mt-0.5">
              Observed transmitting PII, PHI, PCI or secrets in live traffic.
            </p>
          </div>
        </div>
      )}

      <EndpointsFunnel total={stats.total} exposed={stats.exposed}
                       authed={stats.authed} unauth={stats.unauth} />

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

      {/* Sensitive data observed in endpoint traffic, by category and type. */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">
          Sensitive data in API traffic
        </p>
        {stats.sensitiveKinds.length > 0 ? (
          <div className="flex flex-wrap gap-2">
            {stats.sensitiveKinds.map(([kind, n]) => {
              const [category, type] = kind.split(':');
              return (
                <span key={kind}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px]
                                 border border-purple-200 bg-purple-50 text-purple-800">
                  <Icon icon="mdi:shield-account-outline" width={12} className="flex-shrink-0" />
                  <span className="font-semibold">{type ?? category}</span>
                  {type && <span className="text-purple-500">{category}</span>}
                  <span className="font-bold">{n}</span>
                </span>
              );
            })}
          </div>
        ) : (
          <p className="text-xs text-gray-400">
            {stats.preV2Rows
              ? 'No classification synced yet. These endpoint rows carry neither sensitive-data '
                + 'nor status-code fields, both of which arrive only on the v2 payload, so they '
                + 'predate the v2 switch and will populate on the next full sync.'
              : 'Upwind has not observed PII, PHI, PCI or secrets on these endpoints.'}
          </p>
        )}
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
                <th className="px-5 py-2.5">Served by</th>
                <th className="px-5 py-2.5">Auth</th>
                <th className="px-5 py-2.5">Internet Exposed</th>
                <th className="px-5 py-2.5">Sensitive data</th>
                <th className="px-5 py-2.5">Status codes</th>
                <th className="px-5 py-2.5">First Seen</th>
                <th className="px-5 py-2.5">Last Seen</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}
                    onClick={() => setSelected(e)}
                    className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer">
                  <td className="px-5 py-3 font-mono font-bold" style={{ color: methodColor(e.method ?? '') }}>{e.method}</td>
                  <td className="px-5 py-3 font-mono text-gray-800 max-w-md truncate">
                    {e.uri}
                    {e.domains && e.domains.length > 0 && (
                      <span className="block text-[10px] text-gray-400">{e.domains.join(', ')}</span>
                    )}
                  </td>
                  {/* Upwind's endpoint resource_id is an internal API-resource id (ep-…), not a
                      cloud resource id, so it cannot join the resource view. Shown as reference
                      only rather than as a link that would open an empty drawer. */}
                  <td className="px-5 py-3 font-mono text-[10px] text-gray-500 max-w-[140px] truncate"
                      title={e.resourceId ?? undefined}>
                    {e.resourceId ?? '—'}
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
                  <td className="px-5 py-3">
                    {(e.sensitiveData ?? []).length > 0 ? (
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full
                                       text-[10px] font-bold bg-purple-100 text-purple-700"
                            title={e.sensitiveData.join(' · ')}>
                        <Icon icon="mdi:shield-account-outline" width={11} />
                        {/* Show the first type and a count, so a wide list doesn't blow out the row. */}
                        {e.sensitiveData[0].split(':').slice(-1)[0]}
                        {e.sensitiveData.length > 1 ? ` +${e.sensitiveData.length - 1}` : ''}
                      </span>
                    ) : (
                      <span className="text-[10px] text-gray-300">—</span>
                    )}
                  </td>
                  <td className="px-5 py-3 font-mono text-[10px] text-gray-500 whitespace-nowrap">
                    {(e.statusCodes ?? []).length > 0 ? e.statusCodes.join(' ') : '—'}
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
                <tr><td colSpan={9} className="px-5 py-12 text-center text-gray-400">
                  No API endpoints discovered for the selected account.</td></tr>
              )}
            </tbody>
          </table>
        </div>
        <Pager page={page} totalPages={totalPages} total={total} unit="endpoints" onPage={setPage} />
      </div>

      {selected && (
        <ApiEndpointDrawer endpoint={selected} onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

/**
 * Endpoint narrowing, matching Upwind's APIs funnel: how the full surface reduces to the
 * endpoints that actually warrant attention. Stages are strictly nested subsets, so a
 * narrowing ribbon is an honest encoding rather than decoration.
 */
const EndpointsFunnel: React.FC<{
  total: number; exposed: number; authed: number; unauth: number;
}> = ({ total, exposed, authed, unauth }) => {
  const stages = [
    { label: 'Endpoints', value: total, icon: 'mdi:api' },
    { label: 'Ingress observed', value: exposed, icon: 'mdi:swap-horizontal' },
    { label: 'Authenticated', value: authed, icon: 'mdi:lock-check' },
    { label: 'Unauthenticated', value: unauth, icon: 'mdi:lock-open-alert' },
  ];
  if (total === 0) return null;

  const W = 1000, H = 110, midY = H / 2, maxH = H * 0.9;
  const max = Math.max(1, total);
  const n = stages.length;
  const x = (i: number) => (i / (n - 1)) * W;
  const h = (v: number) => Math.max(2, (Math.min(v, max) / max) * maxH);
  let d = `M ${x(0)} ${midY - h(stages[0].value) / 2}`;
  for (let i = 1; i < n; i++) d += ` L ${x(i)} ${midY - h(stages[i].value) / 2}`;
  d += ` L ${x(n - 1)} ${midY + h(stages[n - 1].value) / 2}`;
  for (let i = n - 2; i >= 0; i--) d += ` L ${x(i)} ${midY + h(stages[i].value) / 2}`;
  d += ' Z';

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">
        API endpoints funnel
      </p>
      <div className="flex justify-between gap-1 mb-1">
        {stages.map((s) => (
          <div key={s.label} className="flex-1 min-w-0 text-left">
            <div className="flex items-center gap-1 text-[9px] text-gray-500">
              <Icon icon={s.icon} width={11} className="flex-shrink-0" />
              <span className="truncate">{s.label}</span>
            </div>
            <p className="text-sm font-bold text-gray-900">{s.value.toLocaleString()}</p>
          </div>
        ))}
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" style={{ width: '100%', height: 100 }}
           role="img" aria-label="API endpoints narrowing from total to unauthenticated">
        <defs>
          <linearGradient id="apiFunnelGrad" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#60a5fa" />
            <stop offset="55%" stopColor="#3b82f6" />
            <stop offset="100%" stopColor="#1d4ed8" />
          </linearGradient>
        </defs>
        <path d={d} fill="url(#apiFunnelGrad)" />
      </svg>
    </div>
  );
};

const Stat: React.FC<{ icon: string; label: string; value: number; accent: string; hint?: string }> =
  ({ icon, label, value, accent, hint }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5" title={hint}>
    <div className="flex items-center gap-2 mb-1">
      <Icon icon={icon} width={16} className="text-gray-400" />
      <p className="text-xs text-gray-500">{label}</p>
    </div>
    <p className={`text-2xl font-bold ${accent}`}>{value.toLocaleString()}</p>
  </div>
);

export default ApiSecurityTab;
