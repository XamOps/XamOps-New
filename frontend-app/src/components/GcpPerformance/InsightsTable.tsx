/**
 * Insights Table Component
 * Displays performance insights in a table format
 */

import React from 'react';
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
      <div className="space-y-4">
        {[1, 2, 3, 4, 5].map((i) => (
          <div key={i} className="flex items-center space-x-4 p-4 border border-gray-200 rounded-lg">
            <div className="skeleton w-6 h-6 rounded"></div>
            <div className="flex-1 space-y-2">
              <div className="skeleton h-4 w-3/4 rounded"></div>
              <div className="skeleton h-3 w-1/2 rounded"></div>
            </div>
            <div className="skeleton h-6 w-16 rounded-full"></div>
          </div>
        ))}
      </div>
    );
  }

  if (insights.length === 0) {
    return (
      <div className="text-center py-16">
        <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-search text-gray-400 text-xl"></i>
        </div>
        <h3 className="text-lg font-semibold text-gray-800 mb-2">No insights found</h3>
        <p className="text-gray-600">No performance insights available for the selected severity.</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-xs text-gray-700 uppercase bg-gray-50">
          <tr>
            <th className="p-4 w-8">
              <input
                type="checkbox"
                onChange={onToggleAll}
                checked={isAllSelected}
                className="rounded cursor-pointer"
              />
            </th>
            <th className="px-6 py-4 text-left font-bold">Insight</th>
            <th className="px-6 py-4 text-center font-bold">Qty</th>
            <th className="px-6 py-4 text-left font-bold">Resource</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-200">
          {insights.map((insight) => {
            const insightId = insight.id || `${insight.resourceId}-${insight.insight}`;
            const isSelected = selectedInsights.includes(insightId);

            return (
              <tr
                key={insightId}
                className={`insight-row ${isSelected ? 'bg-blue-50 border-l-blue-500' : ''}`}
              >
                <td className="p-4">
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => onToggleInsight(insightId)}
                    className="rounded cursor-pointer"
                  />
                </td>
                <td className="px-6 py-4">
                  <div className="flex items-start space-x-3">
                    <div className="flex-shrink-0 mt-1">
                      <i
                        className={`fas ${
                          insight.severity === 'CRITICAL'
                            ? 'fa-exclamation-triangle text-red-500'
                            : insight.severity === 'WARNING'
                            ? 'fa-exclamation-circle text-orange-500'
                            : 'fa-info-circle text-yellow-500'
                        }`}
                      ></i>
                    </div>
                    <div className="flex-1">
                      <p className="font-medium text-gray-900 leading-relaxed">{insight.insight}</p>
                      {insight.description && (
                        <p className="text-xs text-gray-500 mt-1">{insight.description}</p>
                      )}
                    </div>
                  </div>
                </td>
                <td className="px-6 py-4 text-center">
                  <span className="inline-flex items-center justify-center w-6 h-6 text-xs font-bold text-white bg-blue-500 rounded-full">
                    {insight.quantity || 1}
                  </span>
                </td>
                <td className="px-6 py-4">
                  <span className="font-medium text-gray-900">{insight.resourceType}</span>
                  {insight.resourceId && (
                    <p className="text-xs text-gray-500 font-mono">{insight.resourceId}</p>
                  )}
                  {insight.region && <p className="text-xs text-gray-500">{insight.region}</p>}
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
