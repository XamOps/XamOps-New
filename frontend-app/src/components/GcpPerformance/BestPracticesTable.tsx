/**
 * Best Practices Table Component
 * Displays best practice audit insights
 */

import React from 'react';
import { Icon } from '@iconify/react';
import { PerformanceInsight } from '../../types/gcpPerformance';

interface BestPracticesTableProps {
  insights: PerformanceInsight[];
  loading: boolean;
}

const BestPracticesTable: React.FC<BestPracticesTableProps> = ({ insights, loading }) => {
  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map(i => (
          <div key={i} className="flex items-center gap-4 p-4 border-b border-gray-100 animate-pulse">
            <div className="flex-1 space-y-2">
              <div className="h-3 bg-gray-200 rounded w-3/4" />
              <div className="h-2.5 bg-gray-100 rounded w-1/2" />
            </div>
            <div className="h-5 w-20 bg-gray-200 rounded" />
          </div>
        ))}
      </div>
    );
  }

  if (insights.length === 0) {
    return (
      <div className="flex flex-col items-center py-16">
        <div className="w-12 h-12 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mb-3">
          <Icon icon="mdi:check-circle-outline" width={24} className="text-emerald-600" />
        </div>
        <h3 className="text-base font-bold text-gray-700 mb-1">No Best Practice violations found!</h3>
        <p className="text-sm text-gray-500">Your GCP resources are following best practices.</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-[rgba(0,0,0,0.12)]">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-100">
          <tr>
            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Recommendation</th>
            <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
            <th className="px-4 py-2 text-center text-xs font-bold uppercase tracking-wider text-gray-500 w-32">Savings</th>
          </tr>
        </thead>
        <tbody>
          {insights.map((insight, index) => (
            <tr key={index} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
              <td className="px-4 py-3">
                <p className="font-medium text-gray-900 text-sm">{insight.insight}</p>
                {insight.description && (
                  <p className="text-xs text-gray-500 mt-0.5">{insight.description}</p>
                )}
              </td>
              <td className="px-4 py-3">
                {insight.resourceId && (
                  <code className="text-xs font-mono text-gray-700 bg-gray-100 px-1.5 py-0.5 rounded">{insight.resourceId}</code>
                )}
                {insight.resourceType && (
                  <p className="text-xs text-gray-500 mt-1">{insight.resourceType}</p>
                )}
              </td>
              <td className="px-4 py-3 text-center">
                <span className="text-sm font-bold text-emerald-600">
                  ${(insight.potentialSavings || 0).toFixed(2)}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default BestPracticesTable;
