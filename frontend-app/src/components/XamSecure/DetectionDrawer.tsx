import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { ThreatDetection } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import DetailPanel, { PanelBlock, PanelRow, type PanelSection } from './DetailPanel';

interface Props {
  detection: ThreatDetection;
  onClose: () => void;
  onArchive?: (upwindId: string) => void;
  onOpenResource?: (resourceId: string) => void;
}

const fmt = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString() : '—';

/**
 * Detection panel: labelled fields, the MITRE chain, the affected resource and whatever
 * evidence Upwind supplied — rather than the raw-payload JSON tree this replaced, which read
 * as a debug view.
 *
 * Everything is parsed from the stored payload, so opening it costs no request.
 */
const DetectionDrawer: React.FC<Props> = ({
  detection, onClose, onArchive, onOpenResource,
}) => {
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

  const sections: PanelSection[] = [
    {
      id: 'details', label: 'Detection details', icon: 'mdi:format-list-bulleted', group: 'Detection',
      render: () => (
        <>
          {description && (
            <PanelBlock title="What happened">
              <p className="text-xs leading-relaxed text-gray-700">{description}</p>
            </PanelBlock>
          )}
          <PanelBlock>
            <PanelRow label="Severity" value={<SeverityChip severity={detection.severity} />} />
            <PanelRow label="Status" value={detection.status} />
            <PanelRow label="Category" value={detection.category} />
            <PanelRow label="Occurrences" value={detection.occurrenceCount} />
            <PanelRow label="First seen" value={fmt(detection.firstSeenTime)} />
            <PanelRow label="Last seen" value={fmt(detection.lastSeenTime)} />
            <PanelRow label="Detection id" value={detection.upwindId} mono />
          </PanelBlock>
        </>
      ),
    },
    {
      id: 'mitre', label: 'MITRE ATT&CK', icon: 'mdi:sitemap-outline', group: 'Detection',
      count: attacks.length || tactics.length || undefined,
      available: attacks.length > 0 || tactics.length > 0,
      unavailableReason: 'No MITRE mapping on this detection.',
      render: () => (
        <PanelBlock>
          {attacks.length > 0 ? (
            <div className="space-y-1.5">
              {attacks.map((a, i) => (
                <div key={i} className="flex items-center gap-2 rounded-lg border border-gray-200
                                        px-3 py-2 text-xs">
                  <span className="font-semibold text-gray-800">{a.tactic_name ?? '—'}</span>
                  <Icon icon="mdi:chevron-right" width={13} className="text-gray-300" />
                  <span className="text-gray-600">{a.technique_name ?? '—'}</span>
                  {a.technique_id && (
                    <span className="ml-auto font-mono text-[10px] text-gray-400">{a.technique_id}</span>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {tactics.map((t) => (
                <span key={t} className="rounded-md border border-gray-200 bg-gray-100 px-2 py-0.5
                                         text-[10px] font-semibold text-gray-600">{t}</span>
              ))}
            </div>
          )}
        </PanelBlock>
      ),
    },
    {
      id: 'evidence', label: 'Evidence', icon: 'mdi:file-search-outline', group: 'Detection',
      count: evidence.length || undefined,
      available: evidence.length > 0,
      unavailableReason: 'Upwind supplied no process, file, network or identity evidence.',
      render: () => (
        <div className="space-y-4">
          {evidence.map(([key, value]) => (
            <PanelBlock key={key} title={key}>
              <div className="divide-y divide-gray-100 rounded-lg border border-gray-200">
                {Object.entries(value as Record<string, unknown>)
                  .filter(([, v]) => v !== null && typeof v !== 'object')
                  .map(([k, v]) => (
                    <div key={k} className="flex gap-3 px-3 py-1.5">
                      <span className="w-32 flex-shrink-0 text-[10px] text-gray-400">{k}</span>
                      <span className="break-all font-mono text-[11px] text-gray-700">{String(v)}</span>
                    </div>
                  ))}
              </div>
            </PanelBlock>
          ))}
        </div>
      ),
    },
    {
      id: 'resource', label: 'Overview', icon: 'mdi:server', group: 'Resource',
      render: () => (
        <PanelBlock action={onOpenResource && detection.resourceId ? (
          <button type="button" onClick={() => onOpenResource(detection.resourceId!)}
                  className="inline-flex items-center gap-1 rounded-lg border border-gray-200 px-2.5 py-1
                             text-[10px] font-bold text-gray-700 hover:border-gray-400
                             focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
            Open resource <Icon icon="mdi:arrow-top-right" width={11} />
          </button>
        ) : undefined}>
          <PanelRow label="Name" value={detection.resourceName ?? detection.resourceId} />
          <PanelRow label="Type" value={detection.resourceType} />
          <PanelRow label="Cloud" value={detection.cloudProvider} />
          <PanelRow label="Account" value={detection.cloudAccountId} />
          {detection.resourceId && <PanelRow label="Resource id" value={detection.resourceId} mono />}
        </PanelBlock>
      ),
    },
  ];

  return (
    <DetailPanel
      title={detection.title ?? 'Detection'}
      subtitle={
        <>
          <SeverityChip severity={detection.severity} />
          {detection.status && (
            <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">
              {detection.status}
            </span>
          )}
          {(detection.occurrenceCount ?? 1) > 1 && (
            <span className="text-[10px] font-semibold text-gray-400">
              {detection.occurrenceCount} occurrences
            </span>
          )}
        </>
      }
      actions={onArchive ? (
        <button type="button"
                onClick={() => { onArchive(detection.upwindId); onClose(); }}
                className="rounded-xl bg-gray-900 px-3 py-1.5 text-xs font-bold text-white
                           hover:bg-black focus:outline-none focus-visible:ring-2
                           focus-visible:ring-gray-900">
          Archive
        </button>
      ) : undefined}
      sections={sections}
      onClose={onClose}
    />
  );
};

export default DetectionDrawer;
