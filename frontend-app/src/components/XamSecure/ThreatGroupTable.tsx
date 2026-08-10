import React from 'react';
import { Icon } from '@iconify/react';
import type { ThreatGroups, ThreatGroupDimension } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import { resourceMark, telemetryMark, ResourceIcon } from '../../utils/resourceBranding';

interface Props {
  data: ThreatGroups | null | undefined;
  dimension: ThreatGroupDimension;
  isLoading: boolean;
}

const HEADINGS: Record<ThreatGroupDimension, { primary: string; secondary: string }> = {
  threat: { primary: 'Threat', secondary: 'Source' },
  resource: { primary: 'Resource', secondary: 'Type' },
  account: { primary: 'Cloud account', secondary: '' },
};

const ICONS: Record<ThreatGroupDimension, string> = {
  threat: 'mdi:alert-outline',
  resource: 'mdi:cube-outline',
  account: 'mdi:cloud-outline',
};

const fmt = (iso: string | null): string => (iso ? new Date(iso).toLocaleString() : '—');

/**
 * Detections rolled up by threat, resource or account.
 *
 * The flat feed repeats one rule across dozens of resources, so it answers "what happened last"
 * rather than "what is going on". Upwind's Group by chips exist for the same reason; grouping by
 * threat collapses the repetition, grouping by resource shows which workloads are noisy.
 */
const ThreatGroupTable: React.FC<Props> = ({ data, dimension, isLoading }) => {
  const rows = data?.groups ?? [];
  const head = HEADINGS[dimension];
  const showSecondary = head.secondary !== '';

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">{head.primary}</th>
              {showSecondary && <th className="px-3 py-2.5">{head.secondary}</th>}
              <th className="px-3 py-2.5">Severity</th>
              <th className="px-3 py-2.5">Detections</th>
              <th className="px-3 py-2.5">Occurrences</th>
              <th className="px-3 py-2.5">Resources</th>
              <th className="px-3 py-2.5">First seen</th>
              <th className="px-3 py-2.5">Last seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((g) => (
              <tr key={g.key} className="border-b border-gray-50 hover:bg-gray-50">
                <td className="px-5 py-3">
                  <span className="flex items-center gap-1.5 min-w-0">
                    {/* A resource row is branded by its own type; a threat row has no product
                        to name, so it keeps the abstract glyph. */}
                    {dimension === 'resource' && g.secondary ? (
                      <ResourceIcon mark={resourceMark(g.secondary)} size={13} className="flex-shrink-0" />
                    ) : dimension === 'account' ? (
                      <ResourceIcon mark={resourceMark('aws')} size={13} className="flex-shrink-0" />
                    ) : (
                      <Icon icon={ICONS[dimension]} width={13} className="flex-shrink-0 text-gray-400" />
                    )}
                    <span className="font-medium text-gray-800 truncate max-w-md" title={g.key}>
                      {g.key}
                    </span>
                  </span>
                </td>
                {showSecondary && (
                  <td className="px-3 py-3 text-[11px] text-gray-500">
                    {g.secondary ? (
                      <span className="flex items-center gap-1.5" title={g.secondary}>
                        <ResourceIcon
                          mark={dimension === 'threat' ? telemetryMark(g.secondary) : resourceMark(g.secondary)}
                          size={12} className="flex-shrink-0" />
                        <span className="truncate">
                          {dimension === 'threat'
                            ? telemetryMark(g.secondary).label
                            : resourceMark(g.secondary).label}
                        </span>
                      </span>
                    ) : <span className="text-gray-300">—</span>}
                  </td>
                )}
                <td className="px-3 py-3"><SeverityChip severity={g.severity} /></td>
                <td className="px-3 py-3 font-semibold text-gray-700">{g.detections.toLocaleString()}</td>
                {/* Occurrences exceed detections when Upwind collapses repeats into one row. */}
                <td className="px-3 py-3 text-gray-600">{g.occurrences.toLocaleString()}</td>
                <td className="px-3 py-3 text-gray-600">{g.resourceCount.toLocaleString()}</td>
                <td className="px-3 py-3 whitespace-nowrap text-gray-500">{fmt(g.firstSeenTime)}</td>
                <td className="px-3 py-3 whitespace-nowrap text-gray-500">{fmt(g.lastSeenTime)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={showSecondary ? 8 : 7}
                    className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading...' : 'No detections for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {rows.length > 0 && (
        <div className="px-5 py-3 border-t border-gray-200 text-[10px] text-gray-400">
          {rows.length.toLocaleString()} {head.primary.toLowerCase()}
          {rows.length === 1 ? '' : 's'} · grouped across every detection in scope, not just the
          current page of the flat feed.
        </div>
      )}
    </div>
  );
};

export default ThreatGroupTable;
