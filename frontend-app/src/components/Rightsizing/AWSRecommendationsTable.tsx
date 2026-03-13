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
}

const AWSRecommendationsTable: React.FC<AWSRecommendationsTableProps> = ({
  recommendations,
  isLoading,
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

  // Loading State
  if (isLoading) {
    return (
      <div className="glass-card-table p-8">
        <div className="skeleton h-96 rounded-2xl"></div>
      </div>
    );
  }

  // Table with Data
  return (
    <div className="glass-card-table p-8">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center space-x-2 text-sm text-gray-600 dark:text-gray-400">
          <i className="fab fa-aws text-orange-500"></i>
          <span className="text-xs italic">These recommendations are coming directly from AWS</span>
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
          <table className="w-full text-sm" style={{ tableLayout: 'fixed' }}>
            <colgroup>
              <col style={{ width: '22%' }} />
              <col style={{ width: '25%' }} />
              <col style={{ width: '15%' }} />
              <col style={{ width: '38%' }} />
            </colgroup>
            <thead className="text-xs !text-gray-700 dark:!text-gray-300 uppercase !bg-gradient-to-r !from-gray-50 !to-gray-100 dark:!from-gray-800 dark:!to-gray-900 sticky top-0 z-10">
              <tr>
                <th className="px-6 py-4 text-left font-bold">Resource ID</th>
                <th className="px-6 py-4 text-left font-bold">Cost Comparison</th>
                <th className="px-6 py-4 text-left font-bold">Savings</th>
                <th className="px-6 py-4 text-left font-bold">Reason</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {recommendations.map((rec, index) => (
                <tr key={`${rec.resourceId}-${index}`} className="hover:bg-gray-50 dark:hover:bg-gray-800/30 transition-all">
                  <td className="px-6 py-4 first-cell-indicator">
                    <div className="flex items-center space-x-3">
                      <div className="w-8 h-8 rounded-lg flex-shrink-0">
                        <img
                          src={`/icons/${getServiceIcon(rec.service)}`}
                          alt={rec.service}
                          className="w-full h-full rounded-lg object-cover"
                        />
                      </div>
                      <div className="flex flex-col">
                        <span className="font-mono text-gray-800 dark:text-gray-200 font-medium">{rec.resourceId}</span>
                        {rec.instanceName && (
                          <span className="text-xs text-gray-500 dark:text-gray-400">{rec.instanceName}</span>
                        )}
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center justify-start space-x-2">
                      <div className="text-left min-w-0">
                        <p className="font-bold text-red-600 dark:text-red-400">${rec.currentMonthlyCost.toFixed(2)}</p>
                        <p className="text-xs text-gray-500 dark:text-gray-400">{rec.currentType}</p>
                      </div>
                      <i className="fas fa-arrow-right text-gray-400 dark:text-gray-500 text-xs"></i>
                      <div className="text-left min-w-0">
                        <p className="font-bold text-green-600 dark:text-green-400">${rec.recommendedMonthlyCost.toFixed(2)}</p>
                        <p className="text-xs text-gray-500 dark:text-gray-400">{rec.recommendedType}</p>
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
                    <p className="text-sm text-gray-900 dark:text-gray-100 reason-text">{rec.recommendationReason}</p>
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

export default AWSRecommendationsTable;
