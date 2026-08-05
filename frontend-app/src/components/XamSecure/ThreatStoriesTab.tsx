import React from 'react';
import { Icon } from '@iconify/react';
import type { ThreatStoriesSummary } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';

interface Props {
  stories: ThreatStoriesSummary | null;
  isLoading: boolean;
  onOpenStory?: (storyId: string) => void;
}

const fmt = (iso: string | null): string => (iso ? new Date(iso).toLocaleString() : '—');

const statusTone = (status: string | null): string => {
  switch ((status ?? '').toUpperCase()) {
    case 'OPEN': return 'bg-amber-100 text-amber-700';
    case 'RESOLVED': return 'bg-emerald-100 text-emerald-700';
    case 'ARCHIVED': return 'bg-gray-100 text-gray-500';
    default: return 'bg-gray-100 text-gray-600';
  }
};

/**
 * Threat stories as a first-class list, matching Upwind's Threats > Stories table.
 *
 * "Related events" is what the story correlates in Upwind; "ours" is how many of those
 * detections we hold for the selected accounts. Showing both matters because a story can span
 * accounts — and because a story that correlates none of our detections is withheld entirely
 * (stories carry no account id, so correlated detections are the only ownership signal we have).
 */
const ThreatStoriesTab: React.FC<Props> = ({ stories, isLoading, onOpenStory }) => {
  const rows = stories?.stories ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Threat stories</p>
        {rows.length > 0 && (
          <span className="text-[10px] font-semibold text-gray-500">
            {stories?.activeCount ?? 0} active of {rows.length}
          </span>
        )}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Story</th>
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Related events</th>
              <th className="px-5 py-2.5">In this account</th>
              <th className="px-5 py-2.5">First seen</th>
              <th className="px-5 py-2.5">Last seen</th>
              <th className="px-5 py-2.5">Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((s) => {
              const clickable = !!onOpenStory && !!s.id;
              return (
                <tr key={s.id}
                    className={`border-b border-gray-50 ${clickable ? 'hover:bg-gray-50 cursor-pointer' : ''}`}
                    onClick={clickable ? () => onOpenStory!(s.id!) : undefined}>
                  <td className="px-5 py-3 font-medium text-gray-800 max-w-md">
                    <span className="flex items-center gap-1.5">
                      <Icon icon="mdi:book-open-variant" width={13} className="flex-shrink-0 text-gray-400" />
                      <span className="truncate" title={s.title ?? ''}>{s.title ?? 'Untitled story'}</span>
                    </span>
                  </td>
                  <td className="px-5 py-3"><SeverityChip severity={s.severity} /></td>
                  <td className="px-5 py-3 font-semibold text-gray-700">{s.relatedEvents}</td>
                  <td className="px-5 py-3 text-gray-600">{s.resolvedCount}</td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">{fmt(s.createTime)}</td>
                  <td className="px-5 py-3 whitespace-nowrap text-gray-500">{fmt(s.updateTime)}</td>
                  <td className="px-5 py-3">
                    <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${statusTone(s.status)}`}>
                      {s.status ?? 'Unknown'}
                    </span>
                  </td>
                </tr>
              );
            })}
            {rows.length === 0 && (
              <tr>
                <td colSpan={7} className="px-5 py-12 text-center text-gray-400">
                  {isLoading
                    ? 'Loading threat stories...'
                    : 'No threat stories correlate a detection in the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default ThreatStoriesTab;
