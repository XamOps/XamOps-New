/**
 * Insights Table Component
 * Displays performance insights in a table format
 */

import React from 'react';
import { Icon } from '@iconify/react';
import { PerformanceInsight } from '../../types/gcpPerformance';

interface InsightsTableProps {
  insights: PerformanceInsight[];
  selectedInsights: string[];
  onToggleInsight: (insightId: string) => void;
  onToggleAll: () => void;
  isAllSelected: boolean;
  loading: boolean;
}

const InsightsTable: React.FC<InsightsTableProps> = ({
  insights,
  selectedInsights,
  onToggleInsight,
  onToggleAll,
  isAllSelected,
  loading,
}) => {
  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3, 4, 5].map(i => (
          <div key={i} className="flex items-center gap-4 p-4 border-b border-gray-100 animate-pulse">
            <div className="w-5 h-5 bg-gray-200 rounded" />
            <div className="flex-1 space-y-2">
              <div className="h-3 bg-gray-200 rounded w-3/4" />
              <div className="h-2.5 bg-gray-100 rounded w-1/2" />
            </div>
            <div className="h-5 w-16 bg-gray-200 rounded-full" />
          </div>
        ))}
      </div>
    );
  }

  if (insights.length === 0) {
    return (
      <div className="flex flex-col items-center py-16">
        <div className="w-12 h-12 bg-gray-100 rounded-xl flex items-center justify-center mb-3">
          <Icon icon="mdi:magnify" width={24} className="text-gray-400" />
        </div>
        <h3 className="text-base font-bold text-gray-700 mb-1">No insights found</h3>
        <p className="text-sm text-gray-500">No performance insights available for the selected severity.</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-[rgba(0,0,0,0.12)]">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-100">
          <tr>
            <th className="px-4 py-2 w-12">
              <input
                type="checkbox"
                onChange={onToggleAll}
                checked={isAllSelected}
                className="rounded cursor-pointer"
              />
            </th>
            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Insight</th>
            <th className="px-4 py-2 text-center text-xs font-bold uppercase tracking-wider text-gray-500 w-20">Qty</th>
            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
          </tr>
        </thead>
        <tbody>
          {insights.map((insight) => {
            const insightId = insight.id || `${insight.resourceId}-${insight.insight}`;
            const isSelected = selectedInsights.includes(insightId);

            return (
              <tr
                key={insightId}
                className={`border-t border-gray-100 transition-colors ${
                  isSelected ? 'bg-blue-50 border-l-2 border-l-indigo-400' : 'hover:bg-gray-50'
                }`}
              >
                <td className="px-4 py-3">
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => onToggleInsight(insightId)}
                    className="rounded cursor-pointer"
                  />
                </td>
                <td className="px-4 py-3">
                  <div className="flex items-start gap-3">
                    <Icon
                      icon={insight.severity === 'CRITICAL' ? 'mdi:alert' : insight.severity === 'WARNING' ? 'mdi:alert-circle-outline' : 'mdi:information-outline'}
                      width={15}
                      className={`mt-0.5 flex-shrink-0 ${insight.severity === 'CRITICAL' ? 'text-red-500' : insight.severity === 'WARNING' ? 'text-orange-500' : 'text-yellow-500'}`}
                    />
                    <div className="flex-1">
                      <p className="font-medium text-gray-900 text-sm leading-relaxed">{insight.insight}</p>
                      {insight.description && (
                        <p className="text-xs text-gray-500 mt-0.5">{insight.description}</p>
                      )}
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3 text-center">
                  <span className="inline-flex items-center justify-center w-6 h-6 text-xs font-bold text-white bg-indigo-500 rounded-full">
                    {insight.quantity || 1}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <span className="font-medium text-gray-900 text-sm">{insight.resourceType}</span>
                  {insight.resourceId && (
                    <p className="text-xs text-gray-500 font-mono mt-0.5">{insight.resourceId}</p>
                  )}
                  {insight.region && <p className="text-xs text-gray-400 mt-0.5">{insight.region}</p>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default InsightsTable;
