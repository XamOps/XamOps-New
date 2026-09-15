import React, { useEffect, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import type { ApiEndpoint } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';
import ApiEndpointDrawer from './ApiEndpointDrawer';
import { ribbonPath, type Point } from './funnelPath';

interface Props {
  data: ApiEndpoint[] | null;
  isLoading: boolean;
}

const METHOD_COLOR: Record<string, string> = {
  GET: '#16a34a', POST: '#2563eb', PUT: '#d97706', PATCH: '#9333ea',
  DELETE: '#dc2626', HEAD: '#0891b2', OPTIONS: '#64748b',
};
const methodColor = (m: string) => METHOD_COLOR[m?.toUpperCase()] ?? '#64748b';

type AuthBucket = 'AUTHENTICATED' | 'UNAUTHENTICATED' | 'UNCLASSIFIED';

interface EndpointFilters {
  q: string;
  /** Uppercase HTTP method, or '' for all. */
  method: string;
  auth: '' | AuthBucket;
  /** Only endpoints carrying observed sensitive data. */
  sensitive: boolean;
  /** '' all · 'yes' traffic observed · 'no' none observed. */
  ingress: '' | 'yes' | 'no';
}

const EMPTY_FILTERS: EndpointFilters = { q: '', method: '', auth: '', sensitive: false, ingress: '' };

const isFiltering = (f: EndpointFilters) =>
  f.q.trim() !== '' || f.method !== '' || f.auth !== '' || f.sensitive || f.ingress !== '';

/**
 * Collapses the auth state into the three buckets the summary cards count.
 *
 * Anything that is not an explicit AUTHENTICATED or UNAUTHENTICATED is unclassified rather than
 * unauthenticated: it means Upwind has not seen enough traffic to decide, and on this estate that
 * is over half the endpoints. Treating absent as unauthenticated would invent a finding.
 */
const authBucket = (e: ApiEndpoint): AuthBucket => {
  const s = (e.authState ?? '').toUpperCase();
  return s === 'AUTHENTICATED' || s === 'UNAUTHENTICATED' ? s : 'UNCLASSIFIED';
};

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

  const [filters, setFilters] = useState<EndpointFilters>(EMPTY_FILTERS);

  /**
   * Filtering is client-side because the whole endpoint list is already in memory — the tab
   * loads it in one request and every summary above is computed from it. Going back to the
   * server per keystroke would be slower and would make the cards disagree with the table.
   */
  const filtered = useMemo(() => {
    if (!isFiltering(filters)) return all;
    const q = filters.q.trim().toLowerCase();
    return all.filter((e) => {
      if (filters.method && (e.method ?? '').toUpperCase() !== filters.method) return false;
      if (filters.auth && authBucket(e) !== filters.auth) return false;
      if (filters.sensitive && (e.sensitiveData ?? []).length === 0) return false;
      if (filters.ingress === 'yes' && !e.internetExposed) return false;
      if (filters.ingress === 'no' && e.internetExposed) return false;
      if (q) {
        // Path, host, the API-resource id and observed status codes — the four things visible
        // in the row, so a search matches what the user can actually see.
        const haystack = [e.uri, e.resourceId, ...(e.domains ?? []), ...(e.statusCodes ?? [])]
          .filter(Boolean).join(' ').toLowerCase();
        if (!haystack.includes(q)) return false;
      }
      return true;
    });
  }, [all, filters]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(filtered, 50);

  // usePaged clamps a page that no longer exists, but landing mid-list after narrowing the set
  // reads as missing results. A new filter starts at the top.
  useEffect(() => { setPage(0); }, [filters, setPage]);

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
              hint="Endpoints with observed inbound traffic. Based on last-seen activity, so this is not a count of internet-facing endpoints." />
        <Stat icon="mdi:lock-check" label="Authenticated" value={stats.authed} accent="text-emerald-600" />
        <Stat icon="mdi:lock-open-alert" label="Unauthenticated" value={stats.unauth} accent="text-orange-600" />
        <Stat icon="mdi:shield-account-outline" label="Sensitive data" value={stats.sensitive}
              accent="text-purple-600"
              hint="Endpoints where PII, PHI, PCI or secrets were observed in traffic" />
        <Stat icon="mdi:help-circle-outline" label="Unclassified" value={stats.unclassified} accent="text-gray-500"
              hint="Not enough observed traffic to classify the authentication state" />
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
              : 'No PII, PHI, PCI or secrets observed on these endpoints.'}
          </p>
        )}
      </div>

      {/* Endpoint inventory */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between gap-3">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Endpoints{' '}
            {isFiltering(filters)
              ? `(${filtered.length.toLocaleString()} of ${stats.total.toLocaleString()})`
              : `(${stats.total.toLocaleString()})`}
          </p>
          {isFiltering(filters) && (
            <button type="button" onClick={() => setFilters(EMPTY_FILTERS)}
                    className="text-[11px] font-semibold text-gray-500 hover:text-gray-800">
              Clear filters
            </button>
          )}
        </div>

        {/* The summary cards and the funnel above stay on the full set on purpose: they describe
            the account, and having them move with the table would leave no fixed reference to
            compare a filtered subset against. */}
        <EndpointFilterBar filters={filters} onChange={setFilters}
                           methods={stats.methods.map(([m]) => m)} />

        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                <th className="px-5 py-2.5">Method</th>
                <th className="px-5 py-2.5">Endpoint</th>
                <th className="px-5 py-2.5">Served by</th>
                <th className="px-5 py-2.5">Auth</th>
                {/* Was "Internet Exposed" with a red badge. The field is a last-seen traffic
                    timestamp, so it read as a critical finding on 3,741 of 3,808 rows — see the
                    tab docstring. Renamed to match the summary card and the filter. */}
                <th className="px-5 py-2.5">Ingress</th>
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
                      e.internetExposed ? 'bg-sky-100 text-sky-700' : 'bg-gray-100 text-gray-500'}`}
                          title={e.internetExposed
                            ? 'Inbound traffic seen on this endpoint'
                            : 'No inbound traffic seen'}>
                      {e.internetExposed ? 'Observed' : 'None'}
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
                  {isFiltering(filters)
                    ? 'No endpoints match these filters.'
                    : 'No API endpoints discovered for the selected account.'}</td></tr>
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
 * Filter row for the endpoint table.
 *
 * The method list is passed in from the data rather than hardcoded from METHOD_COLOR, so the
 * dropdown only ever offers methods this account actually serves instead of seven options where
 * four return nothing.
 */
const EndpointFilterBar: React.FC<{
  filters: EndpointFilters;
  onChange: (f: EndpointFilters) => void;
  methods: string[];
}> = ({ filters, onChange, methods }) => {
  const set = <K extends keyof EndpointFilters>(key: K, value: EndpointFilters[K]) =>
    onChange({ ...filters, [key]: value });

  const selectClass = 'rounded-lg border border-gray-200 bg-white px-2.5 py-1.5 text-xs '
    + 'text-gray-700 focus:border-gray-400 focus:outline-none';

  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-gray-100 bg-gray-50/60 px-5 py-3">
      <div className="relative min-w-[220px] flex-1">
        <Icon icon="mdi:magnify" width={15}
              className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-gray-400" />
        <input
          type="search"
          value={filters.q}
          onChange={(e) => set('q', e.target.value)}
          placeholder="Search path, host, resource id or status code"
          aria-label="Search endpoints"
          className="w-full rounded-lg border border-gray-200 bg-white py-1.5 pl-8 pr-2.5 text-xs
                     text-gray-700 placeholder:text-gray-400 focus:border-gray-400 focus:outline-none"
        />
      </div>

      <select value={filters.method} onChange={(e) => set('method', e.target.value)}
              aria-label="Filter by HTTP method" className={selectClass}>
        <option value="">All methods</option>
        {methods.map((m) => <option key={m} value={m}>{m}</option>)}
      </select>

      <select value={filters.auth}
              onChange={(e) => set('auth', e.target.value as EndpointFilters['auth'])}
              aria-label="Filter by authentication state" className={selectClass}>
        <option value="">Any auth state</option>
        <option value="AUTHENTICATED">Authenticated</option>
        <option value="UNAUTHENTICATED">Unauthenticated</option>
        <option value="UNCLASSIFIED">Unclassified</option>
      </select>

      {/* Named for what the field means. See the tab docstring: this is a last-seen traffic
          timestamp, not an internet-facing determination. */}
      <select value={filters.ingress}
              onChange={(e) => set('ingress', e.target.value as EndpointFilters['ingress'])}
              aria-label="Filter by observed ingress" className={selectClass}>
        <option value="">Any ingress</option>
        <option value="yes">Traffic observed</option>
        <option value="no">No traffic observed</option>
      </select>

      <button type="button" onClick={() => set('sensitive', !filters.sensitive)}
              aria-pressed={filters.sensitive}
              className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs
                          font-semibold transition-colors ${
                filters.sensitive
                  ? 'border-purple-300 bg-purple-100 text-purple-700'
                  : 'border-gray-200 bg-white text-gray-600 hover:bg-gray-50'}`}>
        <Icon icon="mdi:shield-account-outline" width={13} />
        Sensitive data
      </button>
    </div>
  );
};

/**
 * Endpoint narrowing, matching Upwind's APIs funnel: how the full surface reduces to the
 * endpoints that actually warrant attention.
 *
 * Drawn with smooth beziers via {@link ribbonPath}. It previously joined the stage points with
 * straight segments, which put a corner at each boundary and turned the drop into the last stage
 * into a hard wedge.
 *
 * Caveat on the encoding, which this change does not alter: only the first three stages are a
 * narrowing chain. "Unauthenticated" is not a subset of "Authenticated" — authenticated,
 * unauthenticated and unclassified partition the surface, and on live data they sum to the
 * endpoint total — so reading the last step as a further narrowing is wrong. Left as-is because
 * it mirrors the vendor console's stage order; worth revisiting as a product decision.
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
  // A zero stage tapers to an actual point; the floor only keeps a small non-zero stage from
  // rendering as a hairline.
  const h = (v: number) => (v <= 0 ? 0 : Math.max(3, (Math.min(v, max) / max) * maxH));
  const edge = (sign: -1 | 1): Point[] =>
    stages.map((s, i) => [x(i), midY + sign * (h(s.value) / 2)] as Point);
  const d = ribbonPath(edge(-1), edge(1));

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
