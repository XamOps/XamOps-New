import React from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { SbomComponent } from '../../types/xamsecure';

interface Props {
  component: SbomComponent;
  onClose: () => void;
}

/**
 * Detail panel for one software component. The table can only show counts; this answers
 * the question the counts raise — how exposed is this package, and is it actually running.
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

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div className="relative w-full max-w-xl h-full bg-white shadow-2xl flex flex-col"
           onClick={(e) => e.stopPropagation()} role="dialog"
           aria-label={c.name ?? 'Component'}>
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-200">
          <div className="pr-4 min-w-0">
            <div className="flex flex-wrap items-center gap-1.5 mb-1.5">
              {c.packageManager && (
                <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-900 text-white uppercase tracking-wide">
                  {c.packageManager}
                </span>
              )}
              {c.inUse > 0 ? (
                <span className="px-2 py-0.5 rounded-md text-[10px] font-bold bg-emerald-100 text-emerald-700">
                  Running
                </span>
              ) : (
                <span className="px-2 py-0.5 rounded-md text-[10px] font-semibold bg-gray-100 text-gray-500">
                  Not in use
                </span>
              )}
              {worst && (
                <span className={`px-2 py-0.5 rounded-md text-[10px] font-bold ${worst.cls}`}>
                  {worst.label}
                </span>
              )}
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug break-words">
              {c.name ?? 'Component'}
            </h3>
            {c.version && <p className="text-[11px] font-mono text-gray-400 mt-0.5">{c.version}</p>}
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100 flex-shrink-0"
                  aria-label="Close">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden px-6 py-5 space-y-6">
          {/* In-use is the field that decides whether a CVE here is urgent or theoretical. */}
          <div className="grid grid-cols-3 gap-3">
            <Tile label="Vulnerabilities" value={c.totalVulns}
                  accent={c.totalVulns > 0 ? 'text-red-600' : 'text-gray-900'} />
            <Tile label="Resources" value={c.resourceCount} accent="text-gray-900" />
            <Tile label="Images" value={c.affectedImages} accent="text-gray-900" />
          </div>

          <Section title="Vulnerabilities by severity">
            {c.totalVulns > 0 ? (
              <div className="space-y-2">
                {sev.filter((s) => s.n > 0).map((s) => (
                  <div key={s.label} className="flex items-center gap-3">
                    <span className="w-16 text-[11px] font-semibold text-gray-600">{s.label}</span>
                    <div className="flex-1 h-2.5 bg-gray-100 rounded-full overflow-hidden">
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
          </Section>

          <Section title="Runtime">
            <p className="text-xs text-gray-700 leading-relaxed">
              {c.inUse > 0
                ? `Loaded at runtime on ${c.inUse} workload${c.inUse === 1 ? '' : 's'}. Vulnerabilities here are reachable, so they should be prioritised over packages that are merely installed.`
                : 'Not observed loading at runtime. Vulnerabilities are present in the image but not currently reachable.'}
            </p>
          </Section>

          <Section title="Package">
            <div className="border border-gray-200 rounded-lg divide-y divide-gray-50">
              <Row label="Name" value={c.name} />
              <Row label="Version" value={c.version} mono />
              <Row label="Manager" value={c.packageManager} />
              <Row label="Framework" value={c.framework} />
              <Row label="Licenses" value={c.licenses.length ? c.licenses.join(', ') : null} />
              <Row label="Provider" value={c.cloudProvider} />
              <Row label="Account" value={c.cloudAccountId} />
            </div>
          </Section>
        </div>
      </div>
    </div>
  ), document.body);
};

const Tile: React.FC<{ label: string; value: number; accent: string }> = ({ label, value, accent }) => (
  <div className="border border-gray-200 rounded-xl px-3 py-2.5">
    <p className="text-[10px] font-bold uppercase tracking-wide text-gray-400">{label}</p>
    <p className={`text-lg font-bold leading-tight mt-0.5 ${accent}`}>{value.toLocaleString()}</p>
  </div>
);

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div>
    <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-2">{title}</h4>
    {children}
  </div>
);

const Row: React.FC<{ label: string; value: string | null; mono?: boolean }> = ({ label, value, mono }) => (
  <div className="flex items-start gap-3 px-3 py-2">
    <span className="text-[10px] font-bold uppercase tracking-wide text-gray-400 w-24 flex-shrink-0 pt-0.5">
      {label}
    </span>
    <span className={`text-xs text-gray-700 break-all min-w-0 ${mono ? 'font-mono text-[11px]' : ''}`}>
      {value ?? '—'}
    </span>
  </div>
);

export default ComponentDrawer;
