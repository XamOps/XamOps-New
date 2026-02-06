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
  onAiRequest: (recommendation: XamOpsRecommendation) => void;
}

const XamOpsRecommendationsTable: React.FC<XamOpsRecommendationsTableProps> = ({
  recommendations,
  isLoading,
  onAiRequest,
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

  return (
    <div className="glass-card p-6">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center space-x-4">
          <div className="w-10 h-10 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-xl flex items-center justify-center shadow-lg">
            <i className="fas fa-bolt text-white"></i>
          </div>
          <div>
            <h2 className="text-xl font-bold text-gray-800">XamAi Recommendations</h2>
            <p className="text-sm text-gray-600">Proprietary analysis for enhanced optimization</p>
          </div>
        </div>
      </div>

      {/* Loading State */}
      {isLoading && <div className="skeleton h-96 rounded-2xl"></div>}

      {/* Empty State */}
      {!isLoading && recommendations.length === 0 && (
        <div className="empty-state text-center py-20 px-8 rounded-2xl">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-lightbulb text-gray-400 text-2xl"></i>
          </div>
          <h3 className="text-2xl font-bold text-gray-800 mb-2">No XamOps Recommendations</h3>
          <p className="text-gray-600">Could not load custom recommendations at this time.</p>
        </div>
      )}

      {/* Table with Data */}
      {!isLoading && recommendations.length > 0 && (
        <div className="max-h-[600px] overflow-y-auto">
          <div className="table-enhanced">
            <table>
              <thead className="sticky top-0 bg-gradient-to-r from-gray-50 to-gray-100">
                <tr>
                  <th style={{ width: '140px' }}>Instance ID</th>
                  <th style={{ width: '110px' }}>Current<br />Instance</th>
                  <th style={{ width: '90px' }}>Load Range</th>
                  <th style={{ width: '130px' }}>Intel Rec</th>
                  <th style={{ width: '130px' }}>AMD Rec</th>
                  <th style={{ width: '90px' }}>Projected<br />Max Util</th>
                  <th style={{ width: '100px' }}>Approx.<br />Savings</th>
                  <th style={{ width: '180px' }}>Reason</th>
                </tr>
              </thead>
              <tbody>
                {recommendations.map((rec, index) => (
                  <tr key={`${rec.instanceId || rec.currentInstance}-${index}`}>
                    <td className="first-cell-indicator">
                      <div className="flex items-center space-x-1">
                        <i className="fas fa-server text-indigo-600" style={{ fontSize: '0.6rem' }}></i>
                        <div className="flex flex-col">
                          <span className="font-mono text-gray-800 font-medium" style={{ fontSize: '0.7rem' }}>
                            {rec.instanceId || 'N/A'}
                          </span>
                          {rec.instanceName && (
                            <span className="text-xs text-gray-500" style={{ fontSize: '0.6rem' }}>
                              {rec.instanceName}
                            </span>
                          )}
                        </div>
                      </div>
                    </td>
                    <td>
                      <div className="flex flex-col">
                        <span className="font-mono font-semibold text-gray-800">{rec.currentInstance}</span>
                        {rec.currentUtilization && (
                          <span className="text-indigo-600 font-medium" style={{ fontSize: '0.65rem' }}>
                            {rec.currentUtilization}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="text-gray-700">{rec.loadRange}</td>
                    <td>
                      <div className="flex flex-col">
                        <span className="font-medium text-gray-800" style={{ fontSize: '0.7rem' }}>
                          {extractInstanceType(rec.intelRecommendation)}
                        </span>
                        {extractUtilization(rec.intelRecommendation) && (
                          <span className="text-gray-500" style={{ fontSize: '0.6rem' }}>
                            {extractUtilization(rec.intelRecommendation)}
                          </span>
                        )}
                      </div>
                    </td>
                    <td>
                      <div className="flex flex-col">
                        <span className="font-medium text-gray-800" style={{ fontSize: '0.7rem' }}>
                          {extractInstanceType(rec.amdRecommendation)}
                        </span>
                        {extractUtilization(rec.amdRecommendation) && (
                          <span className="text-gray-500" style={{ fontSize: '0.6rem' }}>
                            {extractUtilization(rec.amdRecommendation)}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="text-gray-700">{rec.projectedMaxUtil}</td>
                    <td>
                      <span className="savings-badge">{rec.approxCostSavings}</span>
                    </td>
                    <td className="text-gray-700 truncate-text" title={rec.reason}>
                      {rec.reason}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};

export default XamOpsRecommendationsTable;
