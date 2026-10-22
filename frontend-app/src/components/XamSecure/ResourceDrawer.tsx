import React from 'react';
import { Icon } from '@iconify/react';
import type { ResourceDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';

interface Props {
  resourceId: string;
  detail: ResourceDetail | null;
  isLoading: boolean;
  onClose: () => void;
}

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * One resource, everything we know about it — the cross-domain join the flat tables never
 * had. A failing check, an exploitable CVE and a live detection on the same machine read as
 * one story instead of three unrelated rows.
 *
 * Grouped nav follows the shared DetailPanel, with the section counts doing the work the
 * old facet buttons did: they tell you where else this resource appears.
 *
 * APIs is deliberately marked unavailable rather than shown empty. Upwind stamps endpoints
 * with an internal API-resource id (ep-…), not the cloud resource id findings use, so that
 * join can never match — a permanent zero would read as "this resource serves no APIs",
 * which is a different and false claim.
 */
const ResourceDrawer: React.FC<Props> = ({ resourceId, detail, isLoading, onClose }) => {
  const counts = detail?.counts;
  const identity = detail?.identity;
  const name = identity?.name ?? resourceId;

  // `counts` is the gate, not `detail`: the endpoint answers `{}` for a resource it cannot
  // resolve, and `{}` is truthy, so keying the empty state off `detail` alone sent that case
  // down the "has sections" path with no sections to show — a blank nav and "Nothing to show."
  const resolved = !!counts;

  const sections: PanelSection[] = detail && counts ? [
    {
      id: 'overview', label: 'Overview', icon: 'mdi:information-outline', group: 'Resource',
      render: () => (
        <>
          <PanelBlock>
            <PanelRow label="Name" value={identity?.name} />
            <PanelRow label="Type" value={identity?.resourceType} />
            <PanelRow label="Category" value={identity?.assetCategory} />
            <PanelRow label="Cloud" value={identity?.cloudProvider} />
            <PanelRow label="Account" value={identity?.cloudAccountId} />
            <PanelRow label="Region" value={identity?.region} />
            <PanelRow label="Resource id" value={resourceId} mono />
          </PanelBlock>
          <PanelBlock title="Where else this resource appears">
            <div className="grid grid-cols-3 gap-3">
              <Tally label="Failing checks" value={counts.configurations} tone="text-orange-600" />
              <Tally label="Open CVEs" value={counts.vulnerabilities} tone="text-red-600" />
              <Tally label="Detections" value={counts.detections} tone="text-red-600" />
            </div>
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'configurations', label: 'Configurations', icon: 'mdi:clipboard-check-outline',
      group: 'Findings', count: counts.configurations,
      available: counts.configurations > 0,
      unavailableReason: 'No failing configuration checks on this resource.',
      render: () => (
        <Records head={['Check', 'Framework', 'Last seen', 'Severity']}
                 rows={detail.configurations.map((c, i) => (
                   <tr key={i} className="border-b border-gray-50 last:border-0">
                     <td className="px-3 py-2 font-medium text-gray-800">{c.check ?? '—'}</td>
                     <td className="px-3 py-2 text-gray-600">{c.framework ?? '—'}</td>
                     <td className="px-3 py-2 whitespace-nowrap text-gray-500">{fmt(c.lastSeenTime)}</td>
                     <td className="px-3 py-2"><SeverityChip severity={c.severity} /></td>
                   </tr>
                 ))}
                 empty="No failing checks on this resource." />
      ),
    },
    {
      id: 'vulnerabilities', label: 'Vulnerabilities', icon: 'mdi:bug-outline',
      group: 'Findings', count: counts.vulnerabilities,
      available: counts.vulnerabilities > 0,
      unavailableReason: 'No open vulnerabilities on this resource.',
      render: () => (
        <Records head={['CVE', 'CVSS', 'EPSS', 'Exploitable', 'Severity']}
                 rows={detail.vulnerabilities.map((v, i) => (
                   <tr key={i} className="border-b border-gray-50 last:border-0">
                     <td className="px-3 py-2 font-medium text-gray-800">
                       {v.cveId ?? '—'}
                       {v.name && (
                         <span className="block max-w-[240px] truncate text-[10px] text-gray-400">
                           {v.name}
                         </span>
                       )}
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
                 empty="No open vulnerabilities on this resource." />
      ),
    },
    {
      id: 'detections', label: 'Detections', icon: 'mdi:flash-outline',
      group: 'Findings', count: counts.detections,
      available: counts.detections > 0,
      unavailableReason: 'No threat detections on this resource.',
      render: () => (
        <Records head={['Detection', 'MITRE tactics', 'Last seen', 'Severity']}
                 rows={detail.detections.map((d) => (
                   <tr key={d.upwindId} className="border-b border-gray-50 last:border-0">
                     <td className="px-3 py-2 font-medium text-gray-800">{d.title ?? '—'}</td>
                     <td className="px-3 py-2 text-gray-600">{d.mitreTactics ?? '—'}</td>
                     <td className="px-3 py-2 whitespace-nowrap text-gray-500">{fmt(d.lastSeenTime)}</td>
                     <td className="px-3 py-2"><SeverityChip severity={d.severity} /></td>
                   </tr>
                 ))}
                 empty="No detections on this resource." />
      ),
    },
    // NOTE: an "APIs" section is intentionally absent. API endpoints are keyed by an API-catalog
    // resource id that does not resolve against the cloud resource id findings use, so the join
    // cannot match. Previously rendered as a disabled nav item with the reason in a tooltip,
    // which advertised the limitation to the customer for no benefit. Do not re-add without a
    // working join, and do not show it empty — a permanent zero reads as "serves no APIs".
  ] : [];

  return (
    <DetailPanel
      title={name}
      subtitle={
        <>
          <SeverityChip severity={detail?.topSeverity ?? null} />
          {identity?.resourceType && (
            <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">
              {identity.resourceType}
            </span>
          )}
          <span className="max-w-md truncate text-[10px] text-gray-400" title={resourceId}>
            {resourceId}
          </span>
        </>
      }
      sections={sections}
      isLoading={isLoading}
      onClose={onClose}
      emptyState={!isLoading && !resolved ? (
        <div className="max-w-xs text-center">
          <Icon icon="mdi:database-off-outline" width={36} className="mx-auto text-gray-300" />
          <p className="mt-3 text-sm font-semibold text-gray-900">Nothing references this resource</p>
          <p className="mt-1 text-xs text-gray-500">
            No XamSecure records mention it yet. It may not be covered by a sensor, or the
            next sync has not run.
          </p>
        </div>
      ) : undefined}
    />
  );
};

const Tally: React.FC<{ label: string; value: number; tone: string }> = ({ label, value, tone }) => (
  <div className="rounded-lg border border-gray-200 p-3">
    <p className={`text-lg font-bold ${value > 0 ? tone : 'text-gray-300'}`}>{value}</p>
    <p className="mt-0.5 text-[10px] text-gray-500">{label}</p>
  </div>
);

const Records: React.FC<{ head: string[]; rows: React.ReactNode[]; empty: string }> =
  ({ head, rows, empty }) => (
  <div className="overflow-hidden rounded-lg border border-gray-200">
    <table className="w-full text-xs">
      <thead>
        <tr className="border-b border-gray-100 bg-gray-50 text-left text-[10px] uppercase text-gray-400">
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
);

export default ResourceDrawer;
