import React from 'react';
import type { SbomComponent } from '../../types/xamsecure';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';

interface Props {
  component: SbomComponent;
  onClose: () => void;
}

/**
 * One software component. The table can only show counts; this answers the question the
 * counts raise — how exposed is this package, and is it actually running.
 *
 * Every value comes from the already-loaded row, so opening it costs no request.
 */
const ComponentDrawer: React.FC<Props> = ({ component: c, onClose }) => {
  const sev = [
    { label: 'Critical', n: c.critical, cls: 'bg-red-500 text-white' },
    { label: 'High', n: c.high, cls: 'bg-orange-500 text-white' },
    { label: 'Medium', n: c.medium, cls: 'bg-yellow-400 text-gray-900' },
    { label: 'Low', n: c.low, cls: 'bg-emerald-500 text-white' },
  ];
  const worst = sev.find((s) => s.n > 0);

  const sections: PanelSection[] = [
    {
      id: 'package', label: 'Package', icon: 'mdi:package-variant-closed', group: 'Component',
      render: () => (
        <>
          <PanelBlock>
            <div className="grid grid-cols-3 gap-3">
              <Tile label="Vulnerabilities" value={c.totalVulns}
                    accent={c.totalVulns > 0 ? 'text-red-600' : 'text-gray-900'} />
              <Tile label="Resources" value={c.resourceCount} accent="text-gray-900" />
              <Tile label="Images" value={c.affectedImages} accent="text-gray-900" />
            </div>
          </PanelBlock>
          <PanelBlock>
            <PanelRow label="Name" value={c.name} />
            <PanelRow label="Version" value={c.version} mono />
            <PanelRow label="Manager" value={c.packageManager} />
            <PanelRow label="Framework" value={c.framework} />
            <PanelRow label="Licenses" value={c.licenses.length ? c.licenses.join(', ') : null} />
            <PanelRow label="Cloud" value={c.cloudProvider} />
            <PanelRow label="Account" value={c.cloudAccountId} />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'vulnerabilities', label: 'Vulnerabilities', icon: 'mdi:bug-outline', group: 'Component',
      count: c.totalVulns,
      render: () => (
        <PanelBlock>
          {c.totalVulns > 0 ? (
            <div className="space-y-2">
              {sev.filter((s) => s.n > 0).map((s) => (
                <div key={s.label} className="flex items-center gap-3">
                  <span className="w-16 text-[11px] font-semibold text-gray-600">{s.label}</span>
                  <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-gray-100">
                    <div className={`h-full rounded-full ${s.cls}`}
                         style={{ width: `${(s.n / Math.max(1, c.totalVulns)) * 100}%` }} />
                  </div>
                  <span className="w-8 text-right text-xs font-bold text-gray-800">{s.n}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-gray-400">No known vulnerabilities in this package.</p>
          )}
        </PanelBlock>
      ),
    },
    {
      id: 'runtime', label: 'Runtime', icon: 'mdi:play-speed', group: 'Component',
      render: () => (
        <PanelBlock>
          <div className="mb-3 rounded-lg border border-gray-200 p-3">
            <span className={`inline-block rounded px-2 py-0.5 text-[10px] font-bold ${
              c.inUse > 0 ? 'bg-red-100 text-red-700' : 'bg-gray-100 text-gray-600'}`}>
              {c.inUse > 0 ? 'Loaded at runtime' : 'Not in use'}
            </span>
            <p className="mt-2 text-[11px] leading-relaxed text-gray-700">
              {c.inUse > 0
                ? `Loaded at runtime on ${c.inUse} workload${c.inUse === 1 ? '' : 's'}. `
                  + 'Vulnerabilities here are reachable, so they outrank packages that are '
                  + 'merely installed.'
                : 'Not observed loading at runtime. Vulnerabilities are present in the image '
                  + 'but not currently reachable.'}
            </p>
          </div>
          <PanelRow label="Workloads loading it" value={c.inUse} />
          <PanelRow label="Resources carrying it" value={c.resourceCount} />
          <PanelRow label="Affected images" value={c.affectedImages} />
        </PanelBlock>
      ),
    },
  ];

  return (
    <DetailPanel
      title={c.name ?? 'Component'}
      subtitle={
        <>
          {c.version && <span className="font-mono text-[11px] text-gray-500">{c.version}</span>}
          {c.packageManager && (
            <span className="rounded-full bg-gray-900 px-2 py-0.5 text-[10px] font-bold uppercase
                             tracking-wide text-white">
              {c.packageManager}
            </span>
          )}
          <span className={`rounded-md px-2 py-0.5 text-[10px] font-bold ${
            c.inUse > 0 ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-500'}`}>
            {c.inUse > 0 ? 'Running' : 'Not in use'}
          </span>
          {worst && (
            <span className={`rounded-md px-2 py-0.5 text-[10px] font-bold ${worst.cls}`}>
              {worst.label}
            </span>
          )}
        </>
      }
      sections={sections}
      onClose={onClose}
    />
  );
};

const Tile: React.FC<{ label: string; value: number; accent: string }> = ({ label, value, accent }) => (
  <div className="rounded-xl border border-gray-200 px-3 py-2.5">
    <p className={`text-lg font-bold ${accent}`}>{value.toLocaleString()}</p>
    <p className="mt-0.5 text-[10px] text-gray-500">{label}</p>
  </div>
);

export default ComponentDrawer;
