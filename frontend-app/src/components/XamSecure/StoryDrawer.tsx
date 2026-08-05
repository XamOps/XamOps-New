import React, { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '@iconify/react';
import type { ThreatStoryDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import StoryNarrative from './StoryNarrative';
import ResourceLink from './ResourceLink';

interface Props {
  story: ThreatStoryDetail | null;
  isLoading: boolean;
  onClose: () => void;
  onOpenResource?: (resourceId: string) => void;
}

type SubTab = 'summary' | 'detections' | 'mitre' | 'resources';

const fmt = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString(undefined,
    { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';

/**
 * Threat story detail panel, mirroring Upwind's story view: the correlated attack narrative
 * plus the detections, MITRE coverage and resources behind it.
 *
 * Sub-tabs rather than one long scroll, because these are four different questions — what
 * happened, which signals fired, which techniques, and what was touched.
 */
const StoryDrawer: React.FC<Props> = ({ story, isLoading, onClose, onOpenResource }) => {
  const [tab, setTab] = useState<SubTab>('summary');

  const counts = useMemo(() => ({
    detections: story?.detections?.length ?? 0,
    mitre: story?.mitreTactics?.length ?? 0,
    resources: story?.resources?.length ?? 0,
  }), [story]);

  return createPortal((
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      <div className="absolute inset-0 bg-black/30" />
      <aside
        className="relative h-full w-full max-w-2xl bg-white shadow-xl flex flex-col"
        onClick={(e) => e.stopPropagation()}
        role="dialog" aria-modal="true"
        aria-label={story?.title ?? 'Threat story'}
      >
        {/* Header */}
        <div className="flex items-start justify-between gap-3 px-5 py-4 border-b border-gray-200">
          <div className="min-w-0">
            <p className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-gray-400">
              <Icon icon="mdi:book-open-variant" width={12} />
              Threat story
            </p>
            <h2 className="mt-1 text-sm font-bold text-gray-900 leading-snug">
              {isLoading && !story ? 'Loading story...' : (story?.title ?? 'Story unavailable')}
            </h2>
            {story && (
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <SeverityChip severity={story.severity ?? story.worstSeverity} />
                {story.status && (
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-gray-900 text-white">
                    {story.status}
                  </span>
                )}
                <span className="text-[10px] text-gray-400">
                  {fmt(story.firstSeenTime ?? story.createTime)} → {fmt(story.lastSeenTime ?? story.updateTime)}
                </span>
              </div>
            )}
          </div>
          <button type="button" onClick={onClose} aria-label="Close"
                  className="flex-shrink-0 rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-700
                             focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
            <Icon icon="mdi:close" width={18} />
          </button>
        </div>

        {!isLoading && !story ? (
          <div className="flex-1 flex items-center justify-center p-6">
            <div className="text-center max-w-xs">
              <Icon icon="mdi:book-remove-outline" width={36} className="mx-auto text-gray-300" />
              <p className="mt-3 text-sm font-semibold text-gray-900">Story not available</p>
              <p className="mt-1 text-xs text-gray-500">
                This story references no detection in the selected account, so there is nothing
                to show here.
              </p>
            </div>
          </div>
        ) : (
          <>
            {/* Sub-tabs */}
            <div className="flex items-center gap-1 px-5 pt-3 border-b border-gray-200">
              <SubTabButton label="Summary" active={tab === 'summary'} onClick={() => setTab('summary')} />
              <SubTabButton label="Detections" count={counts.detections}
                            active={tab === 'detections'} onClick={() => setTab('detections')} />
              <SubTabButton label="MITRE" count={counts.mitre}
                            active={tab === 'mitre'} onClick={() => setTab('mitre')} />
              <SubTabButton label="Resources" count={counts.resources}
                            active={tab === 'resources'} onClick={() => setTab('resources')} />
            </div>

            <div className="flex-1 overflow-y-auto px-5 py-4">
              {isLoading && !story ? (
                <p className="text-xs text-gray-400">Loading...</p>
              ) : tab === 'summary' ? (
                <div className="space-y-4">
                  <div className="grid grid-cols-3 gap-3">
                    <MiniStat label="Detections" value={story!.detectionCount} />
                    <MiniStat label="Resources" value={counts.resources} />
                    <MiniStat label="Techniques" value={counts.mitre} />
                  </div>
                  {/* Says so plainly when a story spans accounts we can't see, rather than
                      showing a smaller number with no explanation. */}
                  {story!.resolvedCount < story!.detectionCount && (
                    <p className="flex items-start gap-1.5 rounded-lg bg-gray-50 border border-gray-200 p-2.5
                                  text-[10px] text-gray-500">
                      <Icon icon="mdi:information-outline" width={12} className="flex-shrink-0 mt-0.5" />
                      Showing {story!.resolvedCount} of {story!.detectionCount} correlated detections.
                      The rest belong to accounts outside the current selection.
                    </p>
                  )}
                  <StoryNarrative markdown={story!.description} />
                </div>
              ) : tab === 'detections' ? (
                <div className="space-y-2">
                  {story!.detections.map((d) => (
                    <div key={d.id} className="rounded-lg border border-gray-200 p-3">
                      <div className="flex items-start justify-between gap-2">
                        <p className="text-xs font-semibold text-gray-800 leading-snug">{d.title}</p>
                        <SeverityChip severity={d.severity} />
                      </div>
                      <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-gray-500">
                        {d.category && <span>{d.category}</span>}
                        {d.resourceName && (
                          <ResourceLink resourceId={d.resourceId} label={d.resourceName}
                                        onOpen={onOpenResource} />
                        )}
                        {d.occurrenceCount != null && <span>{d.occurrenceCount}x</span>}
                        <span>{fmt(d.lastSeenTime)}</span>
                      </div>
                      {d.mitreTactics.length > 0 && (
                        <div className="mt-1.5 flex flex-wrap gap-1">
                          {d.mitreTactics.map((t) => (
                            <span key={t} className="px-1.5 py-0.5 rounded bg-gray-100 text-[9px] text-gray-600">
                              {t}
                            </span>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                  {story!.detections.length === 0 && (
                    <p className="text-xs text-gray-400">No detections resolved for this story.</p>
                  )}
                </div>
              ) : tab === 'mitre' ? (
                <div className="space-y-3">
                  <p className="text-[10px] text-gray-400">
                    Tactics observed across this story's detections, most frequent first.
                  </p>
                  {story!.mitreTactics.map((t) => {
                    const max = Math.max(1, ...story!.mitreTactics.map((x) => x.count));
                    return (
                      <div key={t.tactic} className="flex items-center gap-3">
                        <span className="w-44 text-[11px] text-gray-700 truncate" title={t.tactic}>
                          {t.tactic}
                        </span>
                        <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden">
                          <div className="h-full rounded-full bg-gray-900"
                               style={{ width: `${(t.count / max) * 100}%` }} />
                        </div>
                        <span className="w-8 text-right text-xs font-semibold text-gray-700">{t.count}</span>
                      </div>
                    );
                  })}
                  {story!.mitreTactics.length === 0 && (
                    <p className="text-xs text-gray-400">No MITRE tactics recorded.</p>
                  )}
                  {story!.categories.length > 0 && (
                    <div className="pt-3 border-t border-gray-100">
                      <p className="text-[10px] font-semibold text-gray-500 uppercase tracking-wide mb-2">
                        Detection categories
                      </p>
                      <div className="flex flex-wrap gap-1.5">
                        {story!.categories.map((c) => (
                          <span key={c.category}
                                className="px-2 py-0.5 rounded-full border border-gray-200 text-[10px] text-gray-600">
                            {c.category} <span className="font-bold">{c.count}</span>
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              ) : (
                <div className="space-y-2">
                  {story!.resources.map((r) => (
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
                  {story!.resources.length === 0 && (
                    <p className="text-xs text-gray-400">No resources recorded for this story.</p>
                  )}
                </div>
              )}
            </div>
          </>
        )}
      </aside>
    </div>
  ), document.body);
};

const SubTabButton: React.FC<{
  label: string; count?: number; active: boolean; onClick: () => void;
}> = ({ label, count, active, onClick }) => (
  <button type="button" onClick={onClick}
          aria-current={active ? 'page' : undefined}
          className={`px-3 py-2 text-xs font-semibold border-b-2 -mb-px transition-colors
                      focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900 ${
            active ? 'border-gray-900 text-gray-900' : 'border-transparent text-gray-400 hover:text-gray-700'}`}>
    {label}
    {count != null && <span className="ml-1 text-[10px] font-normal text-gray-400">{count}</span>}
  </button>
);

const MiniStat: React.FC<{ label: string; value: number }> = ({ label, value }) => (
  <div className="rounded-lg border border-gray-200 p-3">
    <p className="text-lg font-bold text-gray-900">{value.toLocaleString()}</p>
    <p className="text-[10px] text-gray-500 mt-0.5">{label}</p>
  </div>
);

export default StoryDrawer;
