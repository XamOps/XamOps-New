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
                  <th style={{ width: '50px' }} className="text-center">AI</th>
                </tr>
              </thead>
              <tbody>
                {recommendations.map((rec, index) => (
                  <tr key={`${rec.instanceId || rec.currentInstance}-${index}`}>
                    <td className="first-cell-indicator">
                      <div className="flex items-center space-x-1">
                        <i className="fas fa-server text-indigo-600" style={{ fontSize: '0.6rem' }}></i>
                        <span className="font-mono text-gray-800 font-medium" style={{ fontSize: '0.7rem' }}>
                          {rec.instanceId || 'N/A'}
                        </span>
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
                    <td className="text-center">
                      <button onClick={() => onAiRequest(rec)} className="sparkle-button-compact">
                        <svg className="sparkle-compact" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                          <path
                            d="M14.187 8.096L15 5.25L15.813 8.096C16.0231 8.83114 16.4171 9.50062 16.9577 10.0413C17.4984 10.5819 18.1679 10.9759 18.903 11.186L21.75 12L18.904 12.813C18.1689 13.0231 17.4994 13.4171 16.9587 13.9577C16.4181 14.4984 16.0241 15.1679 15.814 15.903L15 18.75L14.187 15.904C13.9769 15.1689 13.5829 14.4994 13.0423 13.9587C12.5016 13.4181 11.8321 13.0241 11.097 12.814L8.25 12L11.096 11.187C11.8311 10.9769 12.5006 10.5829 13.0413 10.0423C13.5819 9.50162 13.9759 8.83214 14.186 8.097L14.187 8.096Z"
                            fill="currentColor"
                            stroke="currentColor"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                        </svg>
                      </button>
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
