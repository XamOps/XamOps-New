import React from 'react';
import { Icon } from '@iconify/react';
import type { ThreatOverview, ThreatStoriesSummary, ThreatStory } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';

interface Props {
  data: ThreatOverview | null;
  stories: ThreatStoriesSummary | null;
  isLoading: boolean;
  onOpenStory?: (storyId: string) => void;
  onSeeAllStories?: () => void;
}

const DAY_LABEL = (iso: string): string => {
  const d = new Date(`${iso}T00:00:00Z`);
  return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
};

/**
 * Threats dashboard, mirroring what Upwind puts on its Threats > Dashboard: the
 * noise-to-signal funnel, a seven-day detection trend, and breakdowns by MITRE tactic and
 * detection source.
 *
 * The funnel's first stage is the one place we can't match. Upwind starts from raw sensor
 * events (986 in their console against 30 detections); we sync detections only. That stage is
 * therefore drawn as unavailable with the reason attached, instead of quietly starting the
 * funnel at detections and implying no filtering happened.
 */
const ThreatsDashboard: React.FC<Props> = ({ data, stories, isLoading, onOpenStory, onSeeAllStories }) => {
  if (isLoading && !data) {
    return <div className="bg-white border border-gray-200 rounded-xl p-12 text-center text-xs text-gray-400">
      Loading threat overview...
    </div>;
  }
  if (!data) {
    return <div className="bg-white border border-gray-200 rounded-xl p-12 text-center text-xs text-gray-400">
      No threat data for the selected account.
    </div>;
  }

  const trendMax = Math.max(1, ...data.trend.map((p) => p.critical + p.high + p.other));
  const tacticMax = Math.max(1, ...data.byTactic.map((t) => t.count));
  const sourceMax = Math.max(1, ...data.bySource.map((s) => s.count));
  const activeStories = (stories?.stories ?? []).filter(
    (s) => (s.status ?? '').toUpperCase() !== 'RESOLVED' && (s.status ?? '').toUpperCase() !== 'ARCHIVED');

  return (
    <div className="space-y-4">
      {/* Funnel */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <div className="flex items-center gap-2 mb-4">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
            Noise to signal threat funnel
          </p>
        </div>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          <FunnelStage
            label="Events" icon="mdi:pulse"
            value={data.eventCount}
            available={data.eventCountAvailable}
            reason={data.eventCountReason}
          />
          <FunnelStage label="Detections" icon="mdi:radar" value={data.detectionCount} available />
          <FunnelStage label="Affected resources" icon="mdi:cube-outline" value={data.resourceCount} available />
          <FunnelStage label="Threat stories" icon="mdi:book-open-variant" value={data.storyCount} available />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Trend */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <div className="flex items-center justify-between mb-4">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Threat detections over time
            </p>
            <span className="text-[10px] text-gray-400">Last 7 days</span>
          </div>
          <div className="flex items-center gap-3 mb-3">
            <Legend colour="bg-red-500" label="Critical" />
            <Legend colour="bg-orange-400" label="High" />
            <Legend colour="bg-gray-300" label="Other" />
          </div>
          <div className="flex items-end justify-between gap-1.5 h-32">
            {data.trend.map((p) => {
              const total = p.critical + p.high + p.other;
              const h = (n: number) => (total === 0 ? 0 : (n / trendMax) * 100);
              return (
                <div key={p.date} className="flex-1 flex flex-col items-center gap-1 group">
                  <div className="w-full flex flex-col justify-end h-28"
                       title={`${DAY_LABEL(p.date)} — ${total} detection${total === 1 ? '' : 's'}`}>
                    {p.critical > 0 && <div className="w-full bg-red-500" style={{ height: `${h(p.critical)}%` }} />}
                    {p.high > 0 && <div className="w-full bg-orange-400" style={{ height: `${h(p.high)}%` }} />}
                    {p.other > 0 && <div className="w-full bg-gray-300" style={{ height: `${h(p.other)}%` }} />}
                    {total === 0 && <div className="w-full h-0.5 bg-gray-100 rounded" />}
                  </div>
                  <span className="text-[9px] text-gray-400 whitespace-nowrap">
                    {DAY_LABEL(p.date).split(' ')[0]}
                  </span>
                </div>
              );
            })}
          </div>
          {/* Bucketed on last-seen, which is what makes a long-running detection show up on the
              day it was last active rather than the day it started. */}
          <p className="mt-3 text-[10px] text-gray-400">Bucketed by last seen, UTC.</p>
        </div>

        {/* Active stories */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <div className="flex items-center justify-between mb-4">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Active threat stories
            </p>
            {onSeeAllStories && activeStories.length > 0 && (
              <button type="button" onClick={onSeeAllStories}
                      className="text-[10px] font-semibold text-gray-500 hover:text-gray-900">
                All stories
              </button>
            )}
          </div>
          {activeStories.length === 0 ? (
            <div className="py-8 text-center">
              <Icon icon="mdi:shield-check-outline" width={26} className="mx-auto text-gray-300" />
              <p className="mt-2 text-xs text-gray-400">
                No active threat stories in this account.
              </p>
            </div>
          ) : (
            <div className="space-y-1.5">
              {activeStories.slice(0, 6).map((s) => (
                <StoryRow key={s.id} story={s} onOpen={onOpenStory} />
              ))}
            </div>
          )}
        </div>

        {/* MITRE tactics */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">
            Threat detections by MITRE tactic
          </p>
          {data.byTactic.length === 0 ? (
            <p className="py-6 text-center text-xs text-gray-400">No tactics recorded.</p>
          ) : (
            <div className="space-y-2">
              {data.byTactic.map((t) => (
                <BarRow key={t.tactic} label={t.tactic} count={t.count} max={tacticMax} colour="bg-indigo-500" />
              ))}
            </div>
          )}
        </div>

        {/* Sources */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
          <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-4">
            Active threats by source
          </p>
          {data.bySource.length === 0 ? (
            <p className="py-6 text-center text-xs text-gray-400">No sources recorded.</p>
          ) : (
            <div className="space-y-2">
              {data.bySource.map((s) => (
                <BarRow key={s.source} label={s.source} count={s.count} max={sourceMax} colour="bg-cyan-500" />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

const Legend: React.FC<{ colour: string; label: string }> = ({ colour, label }) => (
  <span className="flex items-center gap-1 text-[10px] text-gray-500">
    <span className={`w-2 h-2 rounded-full ${colour}`} />{label}
  </span>
);

const FunnelStage: React.FC<{
  label: string; icon: string; value: number | null; available: boolean; reason?: string | null;
}> = ({ label, icon, value, available, reason }) => (
  <div className={`rounded-lg border p-3 ${available ? 'border-gray-200' : 'border-dashed border-gray-200 bg-gray-50'}`}
       title={available ? undefined : reason ?? undefined}>
    <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-gray-400">
      <Icon icon={icon} width={12} />
      {label}
      {!available && <Icon icon="mdi:information-outline" width={11} className="text-gray-300" />}
    </div>
    {available ? (
      <p className="mt-1 text-2xl font-extrabold text-gray-900 leading-none">
        {(value ?? 0).toLocaleString()}
      </p>
    ) : (
      <p className="mt-1.5 text-[11px] text-gray-400 leading-snug">Not available</p>
    )}
  </div>
);

const BarRow: React.FC<{ label: string; count: number; max: number; colour: string }> = ({
  label, count, max, colour,
}) => (
  <div className="flex items-center gap-3">
    <span className="w-40 flex-shrink-0 text-[11px] text-gray-600 truncate" title={label}>{label}</span>
    <div className="flex-1 h-2 bg-gray-100 rounded-full overflow-hidden">
      <div className={`h-full ${colour} rounded-full`} style={{ width: `${(count / max) * 100}%` }} />
    </div>
    <span className="w-10 text-right text-[11px] font-semibold text-gray-700">{count.toLocaleString()}</span>
  </div>
);

const StoryRow: React.FC<{ story: ThreatStory; onOpen?: (id: string) => void }> = ({ story, onOpen }) => {
  const clickable = !!onOpen && !!story.id;
  return (
    <div
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onClick={clickable ? () => onOpen!(story.id!) : undefined}
      onKeyDown={clickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') onOpen!(story.id!); } : undefined}
      className={`flex items-center gap-2 border border-gray-200 rounded-lg px-3 py-2 ${
        clickable ? 'cursor-pointer hover:bg-gray-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900' : ''}`}
    >
      <SeverityChip severity={story.severity} />
      <span className="min-w-0 flex-1 text-xs font-medium text-gray-800 truncate" title={story.title ?? ''}>
        {story.title ?? 'Untitled story'}
      </span>
      <span className="flex-shrink-0 text-[10px] text-gray-400">
        {story.relatedEvents} event{story.relatedEvents === 1 ? '' : 's'}
      </span>
    </div>
  );
};

export default ThreatsDashboard;
