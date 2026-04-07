/**
 * XamOps Recommendations Table Component
 *
 * Displays XamAi rightsizing recommendations in a table format
 */

import React from 'react';
import type { XamOpsRecommendation } from '../../hooks/useRightsizingData';

interface XamOpsRecommendationsTableProps {
  recommendations: XamOpsRecommendation[];
  isLoading: boolean;
  canApply?: boolean;
  onApply?: (instanceId: string, targetType: string, region: string) => void;
}

const XamOpsRecommendationsTable: React.FC<XamOpsRecommendationsTableProps> = ({
  recommendations,
  isLoading,
  canApply = false,
  onApply,
}) => {
  // Extract instance type from recommendation string
  const extractInstanceType = (recommendation: string): string => {
    return recommendation.split('(')[0].trim();
  };

  // Extract utilization info from recommendation string
  const extractUtilization = (recommendation: string): string => {
    const match = recommendation.match(/\((.*?)\)/);
    return match ? match[0] : '';
  };

  // Loading State
  if (isLoading) {
    return (
      <div className="glass-card-table p-6">
        <div className="skeleton h-96 rounded-2xl"></div>
      </div>
    );
  }

  // Table with Data
  return (
    <div className="glass-card-table p-6">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center space-x-4">
          <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-lg">
            <i className="fas fa-bolt text-white"></i>
          </div>
          <div>
            <h2 className="text-xl font-bold text-gray-800 dark:text-gray-200">XamAi Recommendations</h2>
            <p className="text-sm text-gray-600 dark:text-gray-400">Proprietary analysis for enhanced optimization</p>
          </div>
        </div>
      </div>

      <div
        className="table-scroll-container"
        onScroll={(e) => {
          const target = e.currentTarget;
          const isAtEnd = target.scrollLeft + target.clientWidth >= target.scrollWidth - 10;
          if (isAtEnd) {
            target.classList.add('scrolled-to-end');
          } else {
            target.classList.remove('scrolled-to-end');
          }
        }}
      >
        <div className="table-enhanced">
          <table style={{ tableLayout: 'fixed', width: '100%', minWidth: '1200px' }}>
            <colgroup>
              <col style={{ width: '12%' }} />
              <col style={{ width: '9%' }} />
              <col style={{ width: '7%' }} />
              <col style={{ width: '11%' }} />
              <col style={{ width: '11%' }} />
              <col style={{ width: '7%' }} />
              <col style={{ width: '8%' }} />
              <col style={{ width: '19%' }} />
              <col style={{ width: '16%' }} />
            </colgroup>
            <thead className="sticky top-0 !bg-gradient-to-r !from-gray-50 !to-gray-100 dark:!from-gray-800 dark:!to-gray-900">
              <tr>
                <th className="!text-gray-700 dark:!text-gray-300">Instance</th>
                <th className="!text-gray-700 dark:!text-gray-300">Current<br />Instance</th>
                <th className="!text-gray-700 dark:!text-gray-300">Load Range</th>
                <th className="!text-gray-700 dark:!text-gray-300">Intel Rec</th>
                <th className="!text-gray-700 dark:!text-gray-300">AMD Rec</th>
                <th className="!text-gray-700 dark:!text-gray-300">Projected<br />Max Util</th>
                <th className="!text-gray-700 dark:!text-gray-300">Approx.<br />Savings</th>
                <th className="!text-gray-700 dark:!text-gray-300">Reason</th>
                <th className="!text-gray-700 dark:!text-gray-300">Action</th>
              </tr>
            </thead>
            <tbody>
              {recommendations.map((rec, index) => (
                <tr key={`${rec.instanceId || rec.currentInstance}-${index}`}>
                  <td className="first-cell-indicator">
                    <div className="flex items-center space-x-1">
                      <i className="fas fa-server text-indigo-600 dark:text-indigo-400" style={{ fontSize: '0.6rem' }}></i>
                      <div className="flex flex-col min-w-0">
                        <span className="font-semibold text-gray-800 dark:text-gray-200 truncate" style={{ fontSize: '0.75rem' }}>
                          {rec.instanceName || rec.instanceId || 'N/A'}
                        </span>
                        {rec.instanceName && (
                          <span className="font-mono text-gray-400 dark:text-gray-500 truncate" style={{ fontSize: '0.65rem' }}>
                            {rec.instanceId}
                          </span>
                        )}
                      </div>
                    </div>
                  </td>
                  <td>
                    <div className="flex flex-col">
                      <span className="font-mono font-semibold text-gray-800 dark:text-gray-200">{rec.currentInstance}</span>
                      {rec.currentUtilization && (
                        <span className="text-indigo-600 dark:text-indigo-400 font-medium" style={{ fontSize: '0.65rem' }}>
                          {rec.currentUtilization}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="text-gray-900 dark:text-gray-100">{rec.loadRange}</td>
                  <td>
                    <div className="flex flex-col">
                      <span className="font-medium text-gray-800 dark:text-gray-200" style={{ fontSize: '0.7rem' }}>
                        {extractInstanceType(rec.intelRecommendation)}
                      </span>
                      {extractUtilization(rec.intelRecommendation) && (
                        <span className="text-gray-500 dark:text-gray-400" style={{ fontSize: '0.6rem' }}>
                          {extractUtilization(rec.intelRecommendation)}
                        </span>
                      )}
                    </div>
                  </td>
                  <td>
                    <div className="flex flex-col">
                      <span className="font-medium text-gray-800 dark:text-gray-200" style={{ fontSize: '0.7rem' }}>
                        {extractInstanceType(rec.amdRecommendation)}
                      </span>
                      {extractUtilization(rec.amdRecommendation) && (
                        <span className="text-gray-500 dark:text-gray-400" style={{ fontSize: '0.6rem' }}>
                          {extractUtilization(rec.amdRecommendation)}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="text-gray-900 dark:text-gray-100">{rec.projectedMaxUtil}</td>
                  <td>
                    <span className="savings-badge">{rec.approxCostSavings}</span>
                  </td>
                  <td className="reason-text text-gray-900 dark:text-gray-100">
                    {rec.reason}
                  </td>
                  <td>
                    <div className="flex flex-col gap-1">
                      <button
                        onClick={() => onApply && rec.region && onApply(rec.instanceId, extractInstanceType(rec.intelRecommendation), rec.region)}
                        disabled={!canApply || !rec.region || !onApply}
                        title={!canApply ? 'Read-write access required' : !rec.region ? 'Region not available' : `Apply Intel: ${extractInstanceType(rec.intelRecommendation)}`}
                        className={`inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs font-semibold transition-all ${
                          canApply && rec.region
                            ? 'bg-blue-600 hover:bg-blue-700 text-white cursor-pointer'
                            : 'bg-gray-100 dark:bg-gray-700 text-gray-400 dark:text-gray-500 cursor-not-allowed'
                        }`}
                        style={{ fontSize: '0.65rem' }}
                      >
                        <i className="fas fa-microchip"></i> Intel
                      </button>
                      <button
                        onClick={() => onApply && rec.region && onApply(rec.instanceId, extractInstanceType(rec.amdRecommendation), rec.region)}
                        disabled={!canApply || !rec.region || !onApply}
                        title={!canApply ? 'Read-write access required' : !rec.region ? 'Region not available' : `Apply AMD: ${extractInstanceType(rec.amdRecommendation)}`}
                        className={`inline-flex items-center gap-1 px-2 py-1 rounded-md text-xs font-semibold transition-all ${
                          canApply && rec.region
                            ? 'bg-purple-600 hover:bg-purple-700 text-white cursor-pointer'
                            : 'bg-gray-100 dark:bg-gray-700 text-gray-400 dark:text-gray-500 cursor-not-allowed'
                        }`}
                        style={{ fontSize: '0.65rem' }}
                      >
                        <i className="fas fa-microchip"></i> AMD
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default XamOpsRecommendationsTable;
