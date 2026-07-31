import React, { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { ResourceDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';

interface Props {
  resourceId: string;
  detail: ResourceDetail | null;
  isLoading: boolean;
  onClose: () => void;
}

// APIs are absent by design: Upwind stamps endpoints with an internal API-resource id,
// not the cloud resource id findings use, so that facet could never populate.
type Facet = 'configurations' | 'vulnerabilities' | 'detections';

const FACETS: { key: Facet; label: string; icon: string }[] = [
  { key: 'configurations', label: 'Configurations', icon: 'mdi:clipboard-check-outline' },
  { key: 'vulnerabilities', label: 'Vulnerabilities', icon: 'mdi:bug-outline' },
  { key: 'detections', label: 'Detections', icon: 'mdi:flash-outline' },
];

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * One resource, everything we know about it. Joins the four synced domains on
 * resource_id so a failing check, an exploitable CVE, a live detection and an exposed
 * endpoint on the same machine read as one story instead of four unrelated rows.
 *
 * Facets act as the cross-tab link the flat tables never had: the counts tell you where
 * else this resource appears, and selecting one shows those records inline.
 */
const ResourceDrawer: React.FC<Props> = ({ resourceId, detail, isLoading, onClose }) => {
  const counts = detail?.counts;
  // Open on the facet that actually has the worst news, not a fixed default.
  const initialFacet = useMemo<Facet>(() => {
    if (!counts) return 'configurations';
    if (counts.detections > 0) return 'detections';
    if (counts.vulnerabilities > 0) return 'vulnerabilities';
    return 'configurations';
  }, [counts]);
  const [facet, setFacet] = useState<Facet | null>(null);
  const active = facet ?? initialFacet;

  const identity = detail?.identity;
  const name = identity?.name ?? resourceId;

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div
        className="relative w-full max-w-3xl h-full bg-white shadow-2xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-label={`Resource ${name}`}
      >
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-200">
          <div className="pr-4 min-w-0">
            <div className="flex items-center gap-2 mb-1.5">
              <SeverityChip severity={detail?.topSeverity ?? null} />
              {identity?.resourceType && (
                <span className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide">
                  {identity.resourceType}
                </span>
              )}
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug truncate">{name}</h3>
            <p className="text-[11px] text-gray-400 truncate mt-0.5">{resourceId}</p>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 flex-shrink-0"
                  aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden">
          {isLoading && (
            <div className="px-6 py-12 text-center text-xs text-gray-400">Loading resource…</div>
          )}

          {!isLoading && detail && counts && (
            <div className="px-6 py-5 space-y-6">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                <Meta label="Provider" value={identity?.cloudProvider ?? '—'} />
                <Meta label="Account" value={identity?.cloudAccountId ?? '—'} />
                <Meta label="Region" value={identity?.region ?? '—'} />
                <Meta label="Category" value={identity?.assetCategory ?? '—'} />
              </div>

              {/* Where else this resource appears. This is the cross-tab join. */}
              <div>
                <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-2">
                  Related findings
                </h4>
                <div className="grid grid-cols-3 gap-2">
                  {FACETS.map((f) => {
                    const n = counts[f.key];
                    const isActive = active === f.key;
                    return (
                      <button
                        key={f.key}
                        onClick={() => setFacet(f.key)}
                        disabled={n === 0}
                        aria-pressed={isActive}
                        className={`text-left px-3 py-2.5 rounded-xl border transition-colors
                          ${isActive
                            ? 'border-gray-900 bg-gray-900 text-white'
                            : n === 0
                              ? 'border-gray-100 bg-gray-50 text-gray-300 cursor-default'
                              : 'border-gray-200 bg-white text-gray-700 hover:border-gray-400'}`}
                      >
                        <span className="flex items-center gap-1.5">
                          <Icon icon={f.icon} width={13} />
                          <span className="text-[10px] font-semibold uppercase tracking-wide">
                            {f.label}
                          </span>
                        </span>
                        <span className="block text-lg font-bold leading-tight mt-0.5">{n}</span>
                      </button>
                    );
                  })}
                </div>
              </div>

              {active === 'configurations' && (
                <Records
                  title={`Failing configuration checks (${detail.configurations.length})`}
                  head={['Check', 'Framework', 'Last seen', 'Severity']}
                  rows={detail.configurations.map((c, i) => (
                    <tr key={i} className="border-b border-gray-50 last:border-0">
                      <td className="px-3 py-2 text-gray-800 font-medium">{c.check ?? '—'}</td>
                      <td className="px-3 py-2 text-gray-600">{c.framework ?? '—'}</td>
                      <td className="px-3 py-2 text-gray-500 whitespace-nowrap">{fmt(c.lastSeenTime)}</td>
                      <td className="px-3 py-2"><SeverityChip severity={c.severity} /></td>
                    </tr>
                  ))}
                  empty="No failing checks on this resource."
                />
              )}

              {active === 'vulnerabilities' && (
                <Records
                  title={`Open vulnerabilities (${detail.vulnerabilities.length})`}
                  head={['CVE', 'CVSS', 'EPSS', 'Exploitable', 'Severity']}
                  rows={detail.vulnerabilities.map((v, i) => (
                    <tr key={i} className="border-b border-gray-50 last:border-0">
                      <td className="px-3 py-2 text-gray-800 font-medium">
                        {v.cveId ?? '—'}
                        {v.name && <span className="block text-[10px] text-gray-400 truncate max-w-[240px]">{v.name}</span>}
                      </td>
                      <td className="px-3 py-2 text-gray-600">{v.cvssScore ?? '—'}</td>
                      <td className="px-3 py-2 text-gray-600">{v.epssScore ?? '—'}</td>
                      <td className="px-3 py-2">
                        {v.exploitable
                          ? <span className="text-[10px] font-bold text-red-600">Yes</span>
                          : <span className="text-[10px] text-gray-400">No</span>}
                      </td>
                      <td className="px-3 py-2"><SeverityChip severity={v.severity} /></td>
                    </tr>
                  ))}
                  empty="No open vulnerabilities on this resource."
                />
              )}

              {active === 'detections' && (
                <Records
                  title={`Threat detections (${detail.detections.length})`}
                  head={['Detection', 'MITRE tactics', 'Last seen', 'Severity']}
                  rows={detail.detections.map((d) => (
                    <tr key={d.upwindId} className="border-b border-gray-50 last:border-0">
                      <td className="px-3 py-2 text-gray-800 font-medium">{d.title ?? '—'}</td>
                      <td className="px-3 py-2 text-gray-600">{d.mitreTactics ?? '—'}</td>
                      <td className="px-3 py-2 text-gray-500 whitespace-nowrap">{fmt(d.lastSeenTime)}</td>
                      <td className="px-3 py-2"><SeverityChip severity={d.severity} /></td>
                    </tr>
                  ))}
                  empty="No detections on this resource."
                />
              )}

            </div>
          )}

          {!isLoading && !detail && (
            <div className="px-6 py-12 text-center text-xs text-gray-400">
              No XamSecure records reference this resource yet.
            </div>
          )}
        </div>
      </div>
    </div>
  ), document.body);
};

const Records: React.FC<{
  title: string; head: string[]; rows: React.ReactNode[]; empty: string;
}> = ({ title, head, rows, empty }) => (
  <div>
    <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-2">{title}</h4>
    <div className="border border-gray-200 rounded-lg overflow-hidden">
      <table className="w-full text-xs">
        <thead>
          <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100 bg-gray-50">
            {head.map((h) => <th key={h} className="px-3 py-2">{h}</th>)}
          </tr>
        </thead>
        <tbody>
          {rows.length > 0 ? rows : (
            <tr>
              <td colSpan={head.length} className="px-3 py-6 text-center text-gray-400">{empty}</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  </div>
);

const Meta: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div className="min-w-0">
    <p className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-1">{label}</p>
    <p className="text-xs font-medium text-gray-700 truncate">{value}</p>
  </div>
);

export default ResourceDrawer;
