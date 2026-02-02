/**
 * Best Practices Table Component
 * Displays best practice audit insights
 */

import React from 'react';
import { PerformanceInsight } from '../../types/gcpPerformance';

interface BestPracticesTableProps {
  insights: PerformanceInsight[];
  loading: boolean;
}

const BestPracticesTable: React.FC<BestPracticesTableProps> = ({ insights, loading }) => {
  if (loading) {
    return (
      <div className="space-y-4">
        {[1, 2, 3].map((i) => (
          <div key={i} className="flex items-center space-x-4 p-4 border border-gray-200 rounded-lg">
            <div className="flex-1 space-y-2">
              <div className="skeleton h-4 w-3/4 rounded"></div>
              <div className="skeleton h-3 w-1/2 rounded"></div>
            </div>
            <div className="skeleton h-6 w-20 rounded"></div>
          </div>
        ))}
      </div>
    );
  }

  if (insights.length === 0) {
    return (
      <div className="text-center py-16">
        <div className="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-check-circle text-green-500 text-xl"></i>
        </div>
        <h3 className="text-lg font-semibold text-gray-800 mb-2">
          No Best Practice violations found!
        </h3>
        <p className="text-gray-600">Your GCP resources are following best practices.</p>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead className="text-xs text-gray-700 uppercase bg-gray-50">
          <tr>
            <th className="px-6 py-4 text-left font-bold">Recommendation</th>
            <th className="px-6 py-4 text-left font-bold">Resource</th>
            <th className="px-6 py-4 text-center font-bold">Savings</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-200">
          {insights.map((insight, index) => (
            <tr key={index} className="insight-row">
              <td className="px-6 py-4">
                <p className="font-medium text-gray-900">{insight.insight}</p>
                {insight.description && (
                  <p className="text-xs text-gray-500 mt-1">{insight.description}</p>
                )}
              </td>
              <td className="px-6 py-4">
                {insight.resourceId && (
                  <p className="font-mono text-xs text-gray-700">{insight.resourceId}</p>
                )}
                {insight.resourceType && (
                  <p className="text-xs text-gray-500 mt-1">{insight.resourceType}</p>
                )}
              </td>
              <td className="px-6 py-4 text-center">
                <span className="text-green-600 font-semibold">
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
