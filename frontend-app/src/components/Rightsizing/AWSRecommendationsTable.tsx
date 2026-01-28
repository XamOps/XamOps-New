/**
 * AWS Recommendations Table Component
 *
 * Displays AWS rightsizing recommendations in a table format
 */

import React from 'react';
import type { AWSRecommendation } from '../../hooks/useRightsizingData';

interface AWSRecommendationsTableProps {
  recommendations: AWSRecommendation[];
  isLoading: boolean;
  onAiRequest: (recommendation: AWSRecommendation) => void;
}

const AWSRecommendationsTable: React.FC<AWSRecommendationsTableProps> = ({
  recommendations,
  isLoading,
  onAiRequest,
}) => {
  // Get service icon based on service type
  const getServiceIcon = (serviceType: string): string => {
    const iconMap: Record<string, string> = {
      'EC2': 'ec2-instances.png',
      'EBS': 'aws-ebs.png',
      'Lambda': 'aws-lambda.png',
    };
    return iconMap[serviceType] || 'default-icon.png';
  };

  return (
    <div className="glass-card p-8">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center space-x-2 text-sm text-gray-600">
          <i className="fab fa-aws text-orange-500"></i>
          <span className="text-xs italic">These recommendations are coming directly from AWS</span>
        </div>
      </div>

      {/* Loading State */}
      {isLoading && <div className="skeleton h-96 rounded-2xl"></div>}

      {/* Empty State */}
      {!isLoading && recommendations.length === 0 && (
        <div className="empty-state text-center py-20 px-8 rounded-2xl">
          <div className="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-inbox text-gray-400 text-2xl"></i>
          </div>
          <h3 className="text-2xl font-bold text-gray-800 mb-2">No Recommendations</h3>
          <p className="text-gray-600">No rightsizing recommendations available at this time.</p>
        </div>
      )}

      {/* Table with Data */}
      {!isLoading && recommendations.length > 0 && (
        <div className="max-h-[600px] overflow-y-auto">
          <div className="table-enhanced">
            <table className="w-full text-sm">
              <thead className="text-xs text-gray-700 uppercase bg-gradient-to-r from-gray-50 to-gray-100 sticky top-0 z-10">
                <tr>
                  <th className="px-6 py-4 text-left font-bold">Resource ID</th>
                  <th className="px-6 py-4 text-left font-bold">Cost Comparison</th>
                  <th className="px-6 py-4 text-left font-bold">Savings</th>
                  <th className="px-6 py-4 text-left font-bold">Reason</th>
                  <th className="px-6 py-4 text-center font-bold">AI</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-200">
                {recommendations.map((rec, index) => (
                  <tr key={`${rec.resourceId}-${index}`} className="hover:bg-gray-50 transition-all">
                    <td className="px-6 py-4 first-cell-indicator">
                      <div className="flex items-center space-x-3">
                        <div className="w-8 h-8 rounded-lg flex-shrink-0">
                          <img
                            src={`/icons/${getServiceIcon(rec.service)}`}
                            alt={rec.service}
                            className="w-full h-full rounded-lg object-cover"
                          />
                        </div>
                        <span className="font-mono text-gray-800 font-medium">{rec.resourceId}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-between space-x-4">
                        <div className="text-center">
                          <p className="font-bold text-red-600">${rec.currentMonthlyCost.toFixed(2)}</p>
                          <p className="text-xs text-gray-500">{rec.currentType}</p>
                        </div>
                        <i className="fas fa-arrow-right text-gray-400"></i>
                        <div className="text-center">
                          <p className="font-bold text-green-600">${rec.recommendedMonthlyCost.toFixed(2)}</p>
                          <p className="text-xs text-gray-500">{rec.recommendedType}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex flex-col space-y-1">
                        <span className="savings-badge px-3 py-1 text-xs font-semibold">
                          ${rec.estimatedMonthlySavings.toFixed(2)}
                        </span>
                        {rec.currentMonthlyCost > 0 && (
                          <span className="text-xs text-gray-500">
                            {((rec.estimatedMonthlySavings / rec.currentMonthlyCost) * 100).toFixed(1)}% savings
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <p className="text-sm text-gray-700 break-words">{rec.recommendationReason}</p>
                    </td>
                    <td className="px-6 py-4 text-center">
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

export default AWSRecommendationsTable;
