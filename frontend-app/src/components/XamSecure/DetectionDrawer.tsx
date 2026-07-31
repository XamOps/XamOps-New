import React, { useMemo } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { ThreatDetection } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';

interface Props {
  detection: ThreatDetection;
  onClose: () => void;
  onArchive?: (upwindId: string) => void;
  onOpenResource?: (resourceId: string) => void;
}

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * Curated detection panel. Replaces the raw-payload JSON tree, which read as a debug
 * view: the same information is here as labelled fields, with the MITRE chain, the
 * affected resource, and the evidence Upwind supplies.
 *
 * Everything is parsed from the stored payload, so no extra request is needed.
 */
const DetectionDrawer: React.FC<Props> = ({ detection, onClose, onArchive, onOpenResource }) => {
  const raw = useMemo<any>(() => {
    try { return detection.rawPayload ? JSON.parse(detection.rawPayload) : {}; }
    catch { return {}; }
  }, [detection.rawPayload]);

  const tactics = (detection.mitreTactics ?? '').split(',').map((t) => t.trim()).filter(Boolean);
  const attacks: any[] = Array.isArray(raw.mitre_attacks) ? raw.mitre_attacks : [];
  const description: string | null = raw.description ?? raw.summary ?? null;
  // Evidence keys vary by detection type; show whichever the payload carries.
  const evidence = ['process', 'file', 'network', 'user', 'principal', 'request']
    .map((k) => [k, raw[k]] as const)
    .filter(([, v]) => v && typeof v === 'object');

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <div className="relative w-full max-w-2xl h-full bg-white shadow-2xl flex flex-col"
           onClick={(e) => e.stopPropagation()} role="dialog"
           aria-label={detection.title ?? 'Detection'}>
        <div className="flex items-start justify-between px-6 py-4 border-b border-gray-200">
          <div className="pr-4 min-w-0">
            <div className="flex items-center gap-2 mb-1.5">
              <SeverityChip severity={detection.severity} />
              {detection.status && (
                <span className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide">
                  {detection.status}
                </span>
              )}
              {(detection.occurrenceCount ?? 1) > 1 && (
                <span className="text-[10px] font-semibold text-gray-400">
                  {detection.occurrenceCount} occurrences
                </span>
              )}
            </div>
            <h3 className="text-sm font-bold text-gray-900 leading-snug">
              {detection.title ?? 'Detection'}
            </h3>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
            {onArchive && (
              <button
                onClick={() => { onArchive(detection.upwindId); onClose(); }}
                className="px-3 py-1.5 bg-gray-900 hover:bg-black text-white text-xs font-bold rounded-xl"
              >
                Archive
              </button>
            )}
            <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-gray-100" aria-label="Close">
              <Icon icon="mdi:close" width={18} />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto overflow-x-hidden px-6 py-5 space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <Meta label="First seen" value={fmt(detection.firstSeenTime)} />
            <Meta label="Last seen" value={fmt(detection.lastSeenTime)} />
            <Meta label="Category" value={detection.category ?? '—'} />
            <Meta label="Provider" value={detection.cloudProvider ?? '—'} />
          </div>

          {description && (
            <Section title="What happened">
              <p className="text-xs text-gray-700 leading-relaxed">{description}</p>
            </Section>
          )}

          <Section title="Affected resource">
            <div className="border border-gray-200 rounded-lg px-3 py-2.5">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-xs font-semibold text-gray-800 truncate">
                    {detection.resourceName ?? detection.resourceId ?? '—'}
                  </p>
                  <p className="text-[10px] text-gray-400 truncate">
                    {detection.resourceType ?? '—'}
                    {detection.cloudAccountId ? ` · ${detection.cloudAccountId}` : ''}
                  </p>
                </div>
                {onOpenResource && detection.resourceId && (
                  <button
                    onClick={() => onOpenResource(detection.resourceId!)}
                    className="flex-shrink-0 inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg border border-gray-200 hover:border-gray-400 text-gray-700"
                  >
                    Open resource
                    <Icon icon="mdi:arrow-top-right" width={11} />
                  </button>
                )}
              </div>
            </div>
          </Section>

          {(attacks.length > 0 || tactics.length > 0) && (
            <Section title="MITRE ATT&CK">
              {attacks.length > 0 ? (
                <div className="space-y-1.5">
                  {attacks.map((a, i) => (
                    <div key={i} className="flex items-center gap-2 text-xs border border-gray-200 rounded-lg px-3 py-2">
                      <span className="font-semibold text-gray-800">{a.tactic_name ?? '—'}</span>
                      <Icon icon="mdi:chevron-right" width={13} className="text-gray-300" />
                      <span className="text-gray-600">{a.technique_name ?? '—'}</span>
                      {a.technique_id && (
                        <span className="ml-auto text-[10px] font-mono text-gray-400">{a.technique_id}</span>
                      )}
                    </div>
                  ))}
                </div>
              ) : (
                <div className="flex flex-wrap gap-1.5">
                  {tactics.map((t) => (
                    <span key={t} className="px-2 py-0.5 rounded-md text-[10px] font-semibold bg-gray-100 text-gray-600 border border-gray-200">
                      {t}
                    </span>
                  ))}
                </div>
              )}
            </Section>
          )}

          {evidence.length > 0 && (
            <Section title="Evidence">
              <div className="space-y-3">
                {evidence.map(([key, value]) => (
                  <div key={key}>
                    <p className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-1">{key}</p>
                    <div className="border border-gray-200 rounded-lg divide-y divide-gray-100">
                      {Object.entries(value as Record<string, unknown>)
                        .filter(([, v]) => v !== null && typeof v !== 'object')
                        .map(([k, v]) => (
                          <div key={k} className="flex gap-3 px-3 py-1.5">
                            <span className="text-[10px] text-gray-400 w-32 flex-shrink-0">{k}</span>
                            <span className="text-[11px] text-gray-700 font-mono break-all">{String(v)}</span>
                          </div>
                        ))}
                    </div>
                  </div>
                ))}
              </div>
            </Section>
          )}
        </div>
      </div>
    </div>
  ), document.body);
};

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div>
    <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-2">{title}</h4>
    {children}
  </div>
);

const Meta: React.FC<{ label: string; value: string }> = ({ label, value }) => (
  <div className="min-w-0">
    <p className="text-[10px] font-bold uppercase tracking-wide text-gray-400 mb-1">{label}</p>
    <p className="text-xs font-medium text-gray-700 truncate">{value}</p>
  </div>
);

export default DetectionDrawer;
