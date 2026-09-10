import React, { useMemo } from 'react';
import { Icon } from '@iconify/react';
import type { ThreatStoryDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import StoryNarrative from './StoryNarrative';
import ResourceLink from './ResourceLink';
import DetailPanel, { PanelBlock, type PanelSection } from './DetailPanel';

interface Props {
  story: ThreatStoryDetail | null;
  isLoading: boolean;
  onClose: () => void;
  onOpenResource?: (resourceId: string) => void;
}

const fmt = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString(undefined,
    { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

/**
 * Threat story panel: the correlated attack narrative plus the detections, MITRE coverage and
 * resources behind it. Uses the shared DetailPanel so its nav matches every other panel and
 * Upwind's own vertical layout.
 */
const StoryDrawer: React.FC<Props> = ({ story, isLoading, onClose, onOpenResource }) => {
  const counts = useMemo(() => ({
    detections: story?.detections?.length ?? 0,
    mitre: story?.mitreTactics?.length ?? 0,
    resources: story?.resources?.length ?? 0,
  }), [story]);

  const sections: PanelSection[] = story ? [
    {
      id: 'summary', label: 'Story details', icon: 'mdi:format-list-bulleted', group: 'Story',
      render: () => (
        <>
          <PanelBlock>
            <div className="grid grid-cols-3 gap-3">
              <MiniStat label="Detections" value={story.detectionCount} />
              <MiniStat label="Resources" value={counts.resources} />
              <MiniStat label="Techniques" value={counts.mitre} />
            </div>
          </PanelBlock>
          {story.resolvedCount < story.detectionCount && (
            <PanelBlock>
              <p className="flex items-start gap-1.5 rounded-lg border border-gray-200 bg-gray-50 p-2.5
                            text-[10px] text-gray-500">
                <Icon icon="mdi:information-outline" width={12} className="mt-0.5 flex-shrink-0" />
                Showing {story.resolvedCount} of {story.detectionCount} correlated detections.
                The rest belong to accounts outside the current selection.
              </p>
            </PanelBlock>
          )}
          <PanelBlock><StoryNarrative markdown={story.description} /></PanelBlock>
        </>
      ),
    },
    {
      id: 'detections', label: 'Detections', icon: 'mdi:radar', group: 'Story',
      count: counts.detections,
      render: () => (
        <div className="space-y-2">
          {story.detections.map((d) => (
            <div key={d.id} className="rounded-lg border border-gray-200 p-3">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-semibold leading-snug text-gray-800">{d.title}</p>
                <SeverityChip severity={d.severity} />
              </div>
              <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-gray-500">
                {d.category && <span>{d.category}</span>}
                {d.resourceName && (
                  <ResourceLink resourceId={d.resourceId} label={d.resourceName} onOpen={onOpenResource} />
                )}
                {d.occurrenceCount != null && <span>{d.occurrenceCount}x</span>}
                <span>{fmt(d.lastSeenTime)}</span>
              </div>
              {d.mitreTactics.length > 0 && (
                <div className="mt-1.5 flex flex-wrap gap-1">
                  {d.mitreTactics.map((t) => (
                    <span key={t} className="rounded bg-gray-100 px-1.5 py-0.5 text-[9px] text-gray-600">{t}</span>
                  ))}
                </div>
              )}
            </div>
          ))}
          {story.detections.length === 0 && (
            <p className="text-xs text-gray-400">No detections resolved for this story.</p>
          )}
        </div>
      ),
    },
    {
      id: 'mitre', label: 'MITRE ATT&CK', icon: 'mdi:sitemap-outline', group: 'Story',
      count: counts.mitre,
      render: () => (
        <>
          <PanelBlock title="Tactics observed">
            {story.mitreTactics.map((t) => {
              const max = Math.max(1, ...story.mitreTactics.map((x) => x.count));
              return (
                <div key={t.tactic} className="flex items-center gap-3 py-1">
                  <span className="w-44 truncate text-[11px] text-gray-700" title={t.tactic}>{t.tactic}</span>
                  <div className="h-2 flex-1 overflow-hidden rounded-full bg-gray-100">
                    <div className="h-full rounded-full bg-gray-900"
                         style={{ width: `${(t.count / max) * 100}%` }} />
                  </div>
                  <span className="w-8 text-right text-xs font-semibold text-gray-700">{t.count}</span>
                </div>
              );
            })}
            {story.mitreTactics.length === 0 && (
              <p className="text-xs text-gray-400">No MITRE tactics recorded.</p>
            )}
          </PanelBlock>
          {story.categories.length > 0 && (
            <PanelBlock title="Detection categories">
              <div className="flex flex-wrap gap-1.5">
                {story.categories.map((c) => (
                  <span key={c.category}
                        className="rounded-full border border-gray-200 px-2 py-0.5 text-[10px] text-gray-600">
                    {c.category} <span className="font-bold">{c.count}</span>
                  </span>
                ))}
              </div>
            </PanelBlock>
          )}
        </>
      ),
    },
    {
      id: 'resources', label: 'Overview', icon: 'mdi:server', group: 'Resources',
      count: counts.resources,
      render: () => (
        <div className="space-y-2">
          {story.resources.map((r) => (
            <div key={r.resourceId ?? r.resourceName}
                 className="flex items-center justify-between gap-3 rounded-lg border border-gray-200 p-3">
              <div className="min-w-0">
                <ResourceLink resourceId={r.resourceId} label={r.resourceName}
                              sub={r.resourceType} onOpen={onOpenResource} />
                <p className="mt-0.5 text-[10px] text-gray-400">
                  {r.cloudProvider ?? '—'} · {r.cloudAccountId ?? '—'}
                </p>
              </div>
              <span className="flex-shrink-0 text-[10px] text-gray-500">
                <span className="font-bold text-gray-800">{r.detectionCount}</span> detections
              </span>
            </div>
          ))}
          {story.resources.length === 0 && (
            <p className="text-xs text-gray-400">No resources recorded for this story.</p>
          )}
        </div>
      ),
    },
    // NOTE: an "Investigation" section is intentionally absent — it needs a raw event stream we
    // do not hold. Removed rather than shown disabled, so the panel does not advertise the gap.
  ] : [];

  return (
    <DetailPanel
      title={isLoading && !story ? 'Loading story…' : (story?.title ?? 'Story unavailable')}
      subtitle={story ? (
        <>
          <SeverityChip severity={story.severity ?? story.worstSeverity} />
          {story.status && (
            <span className="rounded-full bg-gray-900 px-2 py-0.5 text-[10px] font-bold text-white">
              {story.status}
            </span>
          )}
          <span className="text-[10px] text-gray-400">
            {fmt(story.firstSeenTime ?? story.createTime)} → {fmt(story.lastSeenTime ?? story.updateTime)}
          </span>
        </>
      ) : undefined}
      sections={sections}
      isLoading={isLoading && !story}
      onClose={onClose}
      emptyState={!isLoading && !story ? (
        <div className="max-w-xs text-center">
          <Icon icon="mdi:book-remove-outline" width={36} className="mx-auto text-gray-300" />
          <p className="mt-3 text-sm font-semibold text-gray-900">Story not available</p>
          <p className="mt-1 text-xs text-gray-500">
            This story references no detection in the selected account, so there is nothing to show.
          </p>
        </div>
      ) : undefined}
    />
  );
};

const MiniStat: React.FC<{ label: string; value: number }> = ({ label, value }) => (
  <div className="rounded-lg border border-gray-200 p-3">
    <p className="text-lg font-bold text-gray-900">{value.toLocaleString()}</p>
    <p className="mt-0.5 text-[10px] text-gray-500">{label}</p>
  </div>
);

export default StoryDrawer;
