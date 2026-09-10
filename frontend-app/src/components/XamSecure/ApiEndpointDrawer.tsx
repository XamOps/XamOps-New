import React from 'react';
import { Icon } from '@iconify/react';
import type { ApiEndpoint } from '../../types/xamsecure';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';

interface Props {
  endpoint: ApiEndpoint;
  onClose: () => void;
}

const fmt = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—');

const METHOD_COLOR: Record<string, string> = {
  GET: '#16a34a', POST: '#2563eb', PUT: '#d97706', PATCH: '#9333ea',
  DELETE: '#dc2626', HEAD: '#0891b2', OPTIONS: '#64748b',
};

/**
 * API endpoint panel. The APIs tab had no detail view at all — rows were inert.
 *
 * Sensitive data and status codes come from the v2 endpoint search payload, which the sync
 * only started reading in e584326a4; the v1 endpoint list we used before carried neither, so
 * there was little worth opening a panel for.
 *
 * OWASP and CWE are the notable gap. Upwind's own APIs section leads with "Top 10 OWASP
 * vulnerabilities" and "Top 10 CWE categories" per endpoint, but neither the v1 nor the v2
 * endpoint payload exposes them, so those sections are marked unavailable rather than faked.
 */
const ApiEndpointDrawer: React.FC<Props> = ({ endpoint, onClose }) => {
  const method = (endpoint.method ?? '').toUpperCase();
  const sensitive = endpoint.sensitiveData ?? [];
  const codes = endpoint.statusCodes ?? [];
  const auth = (endpoint.authState ?? '').toUpperCase();

  // Group "PII:Email" style entries by category so the panel reads by data class.
  const byCategory = sensitive.reduce<Record<string, string[]>>((acc, entry) => {
    const [category, type] = entry.includes(':') ? entry.split(':') : ['Other', entry];
    (acc[category] ??= []).push(type ?? category);
    return acc;
  }, {});

  const sections: PanelSection[] = [
    {
      id: 'details', label: 'Endpoint details', icon: 'mdi:format-list-bulleted', group: 'Endpoint',
      render: () => (
        <>
          <PanelBlock>
            <PanelRow label="Method" value={
              <span className="font-mono font-bold" style={{ color: METHOD_COLOR[method] ?? '#64748b' }}>
                {method || '—'}
              </span>} />
            <PanelRow label="URI" value={<span className="font-mono break-all">{endpoint.uri}</span>} />
            <PanelRow label="Domains" value={
              (endpoint.domains ?? []).length > 0 ? endpoint.domains.join(', ') : null} />
            <PanelRow label="Account" value={endpoint.cloudAccountId} />
            <PanelRow label="First seen" value={fmt(endpoint.firstSeenTime)} />
            <PanelRow label="Last seen" value={fmt(endpoint.lastSeenTime)} />
          </PanelBlock>
          <PanelBlock title="Served by">
            {/* Shown as a reference, not a link: this id belongs to the API catalog and does
                not resolve against the cloud resource inventory, so a link would open an
                empty resource view. Kept internal — not surfaced to the user as a caveat. */}
            <PanelRow label="API resource id" value={endpoint.resourceId} mono />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'exposure', label: 'Exposure & auth', icon: 'mdi:earth', group: 'Endpoint',
      render: () => (
        <>
          <PanelBlock>
            <div className="mb-3 rounded-lg border border-gray-200 p-3">
              <span className={`inline-block rounded px-2 py-0.5 text-[10px] font-bold ${
                endpoint.internetExposed ? 'bg-red-500 text-white' : 'bg-emerald-100 text-emerald-700'}`}>
                {endpoint.internetExposed ? 'Internet exposed' : 'Internal'}
              </span>
              {endpoint.internetExposed && sensitive.length > 0 && (
                <p className="mt-2 text-[11px] font-semibold text-red-700">
                  Reachable from the internet and observed carrying sensitive data.
                </p>
              )}
            </div>
            <PanelRow label="Internet exposed" value={
              endpoint.internetExposed
                ? <span className="font-semibold text-red-600">Yes</span>
                : <span className="text-gray-600">No</span>} />
            <PanelRow label="Authentication" value={
              auth === 'AUTHENTICATED'
                ? <span className="font-semibold text-emerald-600">Authenticated</span>
                : auth === 'UNAUTHENTICATED'
                  ? <span className="font-semibold text-orange-600">Unauthenticated</span>
                  : <span className="text-gray-500">Not classified</span>} />
          </PanelBlock>
          {auth !== 'AUTHENTICATED' && auth !== 'UNAUTHENTICATED' && (
            <p className="flex items-start gap-1.5 text-[10px] text-gray-400">
              <Icon icon="mdi:information-outline" width={12} className="mt-0.5 flex-shrink-0" />
              Not enough observed traffic to classify the authentication state. Unclassified is
              not the same as unauthenticated.
            </p>
          )}
        </>
      ),
    },
    {
      id: 'sensitive', label: 'Sensitive data', icon: 'mdi:shield-account-outline', group: 'Endpoint',
      count: sensitive.length,
      render: () => (
        <>
          {sensitive.length === 0 ? (
            <p className="text-xs text-gray-500">
              No PII, PHI, PCI or secrets observed in this endpoint's traffic.
            </p>
          ) : (
            <PanelBlock>
              {Object.entries(byCategory).map(([category, types]) => (
                <div key={category} className="mb-3 last:mb-0">
                  <p className="mb-1.5 text-[10px] font-bold uppercase tracking-wide text-gray-400">
                    {category}
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {types.map((t) => (
                      <span key={t} className="rounded-full border border-purple-200 bg-purple-50
                                               px-2.5 py-1 text-[11px] font-semibold text-purple-800">
                        {t}
                      </span>
                    ))}
                  </div>
                </div>
              ))}
            </PanelBlock>
          )}
        </>
      ),
    },
    {
      id: 'traffic', label: 'Observed traffic', icon: 'mdi:chart-timeline-variant', group: 'Endpoint',
      count: codes.length,
      render: () => (
        <>
          <PanelBlock title="HTTP status codes observed">
            {codes.length === 0 ? (
              <p className="text-xs text-gray-500">No status codes recorded for this endpoint.</p>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {codes.map((c) => {
                  const n = parseInt(c, 10);
                  const tone = n >= 500 ? 'border-red-200 bg-red-50 text-red-700'
                    : n >= 400 ? 'border-orange-200 bg-orange-50 text-orange-700'
                      : n >= 200 && n < 300 ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
                        : 'border-gray-200 bg-gray-50 text-gray-600';
                  return (
                    <span key={c} className={`rounded border px-2 py-1 font-mono text-[11px] font-semibold ${tone}`}>
                      {c}
                    </span>
                  );
                })}
              </div>
            )}
          </PanelBlock>
          <p className="flex items-start gap-1.5 text-[10px] text-gray-400">
            <Icon icon="mdi:information-outline" width={12} className="mt-0.5 flex-shrink-0" />
            Status codes observed in live traffic. A 401 alongside a 200 means the endpoint both
            accepts and rejects requests, which is normal; a 000 means connections failed.
          </p>
        </>
      ),
    },
    // NOTE: "API vulnerabilities" (OWASP API Top-10 / CWE per endpoint) and "Tests" sections
    // were removed rather than rendered as permanently disabled nav items. A greyed-out entry
    // whose tooltip explains an upstream data gap advertises the gap to the customer and can
    // never become available without a provider change. Re-add them here if the data source
    // arrives; tracked in docs/upwind-api-requests-2026-09-07.md.
  ];

  return (
    <DetailPanel
      title={
        <span className="flex flex-wrap items-baseline gap-2">
          <span className="font-mono font-bold" style={{ color: METHOD_COLOR[method] ?? '#64748b' }}>
            {method}
          </span>
          <span className="font-mono break-all">{endpoint.uri}</span>
        </span>
      }
      subtitle={
        <>
          <span className={`rounded px-2 py-0.5 text-[10px] font-bold ${
            endpoint.internetExposed ? 'bg-red-500 text-white' : 'bg-emerald-100 text-emerald-700'}`}>
            {endpoint.internetExposed ? 'Exposed' : 'Internal'}
          </span>
          {auth === 'AUTHENTICATED' && (
            <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-700">
              Authenticated
            </span>
          )}
          {auth === 'UNAUTHENTICATED' && (
            <span className="rounded-full bg-orange-100 px-2 py-0.5 text-[10px] font-bold text-orange-700">
              Unauthenticated
            </span>
          )}
          {sensitive.length > 0 && (
            <span className="rounded-full bg-purple-100 px-2 py-0.5 text-[10px] font-bold text-purple-700">
              {sensitive.length} sensitive {sensitive.length === 1 ? 'field' : 'fields'}
            </span>
          )}
        </>
      }
      sections={sections}
      onClose={onClose}
    />
  );
};

export default ApiEndpointDrawer;
