/**
 * OptimizationSection Component
 *
 * Converted from: dashboard.html (optimization hub section)
 *
 * Displays cost optimization recommendations and anomalies.
 * Layout matches original dashboard.html exactly.
 */

import React from 'react';
import type { OptimizationData } from '../../types/liveDashboard';

interface OptimizationSectionProps {
  optimization: OptimizationData;
  isLoading: boolean;
  isMultiAccount: boolean;
  selectedAccountIds?: string[];
}

// Extended interface to handle API response format
interface RecommendationItem {
  resourceId?: string;
  instanceId?: string;
  volumeId?: string;
  functionName?: string;
  currentType?: string;
  instanceType?: string;
  volumeType?: string;
  recommendedType?: string;
  recommendedMemory?: number;
  estimatedMonthlySavings?: number;
  estimatedSavings?: number;
}

interface AnomalyItem {
  service?: string;
  month?: string;
  startDate?: string;
  unexpectedSpend?: number;
  currentCost?: number;
}

const OptimizationSection: React.FC<OptimizationSectionProps> = ({
  optimization,
  isLoading,
  isMultiAccount,
  selectedAccountIds = [],
}) => {
  const formatCurrency = (num: number): string => {
    return '$' + (num != null ? num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }) : '0.00');
  };

  // Helper to render empty recommendation card
  const renderEmptyCard = (title: string, icon: string) => (
    <div className="rounded-lg p-3 border border-gray-200 bg-gray-50">
      <div className="flex items-center justify-between mb-2">
        <h4 className="font-semibold text-xs text-gray-500 flex items-center">
          <div className="w-5 h-5 bg-gray-300 rounded flex items-center justify-center mr-1.5">
            <i className={`fas ${icon} text-white text-xs`}></i>
          </div>
          <span className="truncate">{title}</span>
        </h4>
        <span className="bg-gray-200 text-gray-500 text-xs font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0">0</span>
      </div>
      <div className="text-center py-8">
        <i className="fas fa-check-circle text-gray-300 text-2xl mb-2"></i>
        <p className="text-xs text-gray-400 font-medium">No recommendations</p>
        <p className="text-xs text-gray-400">Everything looks good!</p>
      </div>
    </div>
  );

  // Helper to render recommendation card with data (matches original dashboard.html)
  const renderRecsCard = (
    title: string,
    recs: RecommendationItem[] | undefined,
    icon: string,
    color: string,
    gradientClass: string,
    linkPath: string
  ) => {
    if (!recs || recs.length === 0) {
      return renderEmptyCard(title, icon);
    }

    return (
      <div className={`optimization-card rounded-lg p-3 border-l-4 border-${color}-500`}>
        <div className="flex items-center justify-between mb-2">
          <h4 className="font-semibold text-xs text-gray-800 flex items-center">
            <div className={`w-5 h-5 bg-gradient-to-br ${gradientClass} rounded flex items-center justify-center mr-1.5`}>
              <i className={`fas ${icon} text-white text-xs`}></i>
            </div>
            <span className="truncate">{title}</span>
          </h4>
          <span className={`bg-${color}-100 text-${color}-800 text-xs font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0`}>
            {recs.length}
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-2 py-1 text-left font-medium text-gray-700 text-xs">Resource</th>
                <th className="px-2 py-1 text-left font-medium text-gray-700 text-xs">Rec.</th>
                <th className="px-2 py-1 text-right font-medium text-gray-700 text-xs">Savings</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {recs.slice(0, 2).map((rec, idx) => {
                const resourceId = rec.resourceId || rec.instanceId || rec.volumeId || rec.functionName || '-';
                const currentType = rec.currentType || rec.instanceType || rec.volumeType || '-';
                const recommendedType = rec.recommendedType || (rec.recommendedMemory ? `${rec.recommendedMemory}MB` : '-');
                const savings = rec.estimatedMonthlySavings || rec.estimatedSavings || 0;

                return (
                  <tr key={idx} className="hover:bg-gray-50 transition-colors">
                    <td className="px-2 py-1.5 font-mono text-xs text-gray-600 max-w-[120px] truncate" title={resourceId}>
                      {resourceId}
                    </td>
                    <td className="px-2 py-1.5">
                      <div className="flex items-center space-x-1">
                        <span className="text-gray-600 text-xs truncate max-w-[80px]" title={currentType}>{currentType}</span>
                        <i className="fas fa-arrow-right text-gray-400" style={{ fontSize: '8px' }}></i>
                        <span className={`font-medium text-${color}-600 text-xs truncate max-w-[80px]`} title={recommendedType}>
                          {recommendedType}
                        </span>
                      </div>
                    </td>
                    <td className={`px-2 py-1.5 text-right font-semibold text-${color}-600 text-xs whitespace-nowrap`}>
                      {formatCurrency(savings)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {recs.length > 2 && (
          <div className="mt-1.5 text-center">
            <a
              href={`${linkPath}?accountIds=${selectedAccountIds.join(',')}`}
              className={`inline-flex items-center text-xs text-${color}-600 hover:text-${color}-700 font-medium`}
            >
              +{recs.length - 2} more
              <i className="fas fa-arrow-right ml-1" style={{ fontSize: '10px' }}></i>
            </a>
          </div>
        )}
      </div>
    );
  };

  // Helper to render anomalies card (matches original dashboard.html)
  const renderAnomaliesCard = (anomalies: AnomalyItem[] | undefined) => {
    if (!anomalies || anomalies.length === 0) {
      return (
        <div className="rounded-lg p-3 border border-gray-200 bg-gray-50">
          <div className="flex items-center justify-between mb-2">
            <h4 className="font-semibold text-xs text-gray-500 flex items-center">
              <div className="w-5 h-5 bg-gray-300 rounded flex items-center justify-center mr-1.5">
                <i className="fas fa-exclamation-triangle text-white text-xs"></i>
              </div>
              <span className="truncate">Cost Anomalies</span>
            </h4>
            <span className="bg-gray-200 text-gray-500 text-xs font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0">0</span>
          </div>
          <div className="text-center py-8">
            <i className="fas fa-check-circle text-gray-300 text-2xl mb-2"></i>
            <p className="text-xs text-gray-400 font-medium">No anomalies detected</p>
            <p className="text-xs text-gray-400">Spending is normal</p>
          </div>
        </div>
      );
    }

    return (
      <div className="anomaly-card rounded-lg p-3 border-l-4 border-red-500">
        <div className="flex items-center justify-between mb-2">
          <h4 className="font-semibold text-xs text-gray-800 flex items-center">
            <div className="w-5 h-5 bg-gradient-to-br from-red-500 to-red-600 rounded flex items-center justify-center mr-1.5">
              <i className="fas fa-exclamation-triangle text-white text-xs"></i>
            </div>
            <span className="truncate">Cost Anomalies</span>
          </h4>
          <span className="bg-red-100 text-red-800 text-xs font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0">
            {anomalies.length}
          </span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-2 py-1 text-left font-medium text-gray-700 text-xs">Service</th>
                <th className="px-2 py-1 text-left font-medium text-gray-700 text-xs">Date</th>
                <th className="px-2 py-1 text-right font-medium text-gray-700 text-xs">Spend</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {anomalies.slice(0, 2).map((a, idx) => (
                <tr key={idx} className="hover:bg-gray-50 transition-colors">
                  <td className="px-2 py-1.5 font-medium text-gray-800 text-xs truncate max-w-[120px]" title={a.service || a.month}>
                    {a.service || a.month || '-'}
                  </td>
                  <td className="px-2 py-1.5 text-gray-600 text-xs whitespace-nowrap">{a.startDate || '-'}</td>
                  <td className="px-2 py-1.5 text-right font-semibold text-red-600 text-xs whitespace-nowrap">
                    {formatCurrency(a.unexpectedSpend || a.currentCost || 0)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {anomalies.length > 2 && (
          <div className="mt-1.5 text-center">
            <a
              href={`/alerts.html?accountIds=${selectedAccountIds.join(',')}`}
              className="inline-flex items-center text-xs text-red-600 hover:text-red-700 font-medium"
            >
              +{anomalies.length - 2} more
              <i className="fas fa-arrow-right ml-1" style={{ fontSize: '10px' }}></i>
            </a>
          </div>
        )}
      </div>
    );
  };

  // Get summary data with fallbacks
  const summary = optimization?.summary || {
    totalPotentialSavings: 0,
    criticalAlerts: 0,
    totalRecommendations: 0,
  };
  const totalSavings = summary.totalPotentialSavings || 0;
  const criticalAlerts = summary.criticalAlerts || 0;

  return (
    <div className="chart-container p-6 rounded-xl relative">
      <div className="flex items-center justify-between mb-6">
        <h3 className="text-lg font-bold text-gray-800 flex items-center">
          <i className="fas fa-arrows-alt widget-handle mr-3 text-gray-400"></i>
          <i className="fas fa-lightbulb mr-2 text-yellow-500"></i>
          Optimization Hub
        </h3>
        {isLoading && <div className="loading-spinner w-5 h-5"></div>}
      </div>

      {/* Loading Skeleton */}
      {isLoading && (
        <div className="absolute inset-x-0 bottom-0 top-16 z-10 p-6 bg-white/80 backdrop-blur-sm rounded-b-xl space-y-6">
          <div className="grid grid-cols-2 gap-6">
            <div className="h-32 skeleton rounded-xl"></div>
            <div className="h-32 skeleton rounded-xl"></div>
          </div>
          <div className="h-24 skeleton rounded-xl"></div>
        </div>
      )}

      {/* Optimization Content - matches original dashboard.html layout */}
      {!isLoading && (
        <div className="fade-in">
          {/* Multi-Account Mode: Hide Details */}
          {isMultiAccount ? (
            <div className="bg-blue-50 border border-blue-200 rounded-lg p-6 text-center">
              <i className="fas fa-eye-slash text-blue-400 text-2xl mb-2"></i>
              <h3 className="text-base font-semibold text-gray-700 mb-1">Account-Specific Insights Hidden</h3>
              <p className="text-gray-600 text-xs max-w-md mx-auto">
                Select a single account to view detailed recommendations.
              </p>
            </div>
          ) : (
            <>
              {/* Summary Cards - 2 column grid at top */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-2 mb-2">
                {/* Potential Savings Card */}
                <div className="bg-gradient-to-br from-green-50 to-green-100 border border-green-200 p-2.5 rounded-lg">
                  <div className="flex items-center space-x-2">
                    <div className="w-7 h-7 bg-gradient-to-br from-green-500 to-green-600 rounded-lg flex items-center justify-center flex-shrink-0">
                      <i className="fas fa-piggy-bank text-white text-xs"></i>
                    </div>
                    <div>
                      <p className="text-xs text-green-700 font-medium">Potential Savings</p>
                      <p className="text-base font-bold text-green-800">
                        {formatCurrency(totalSavings)}
                        <span className="text-xs font-normal text-green-600">/mo</span>
                      </p>
                    </div>
                  </div>
                </div>

                {/* Critical Alerts Card */}
                <div className="bg-gradient-to-br from-red-50 to-red-100 border border-red-200 p-2.5 rounded-lg">
                  <div className="flex items-center space-x-2">
                    <div className="w-7 h-7 bg-gradient-to-br from-red-500 to-red-600 rounded-lg flex items-center justify-center flex-shrink-0">
                      <i className="fas fa-exclamation-triangle text-white text-xs"></i>
                    </div>
                    <div>
                      <p className="text-xs text-red-700 font-medium">Critical Alerts</p>
                      <p className="text-base font-bold text-red-800">
                        {criticalAlerts}
                        <span className="text-xs font-normal text-red-600"> items</span>
                      </p>
                    </div>
                  </div>
                </div>
              </div>

              {/* Recommendation Cards - 4 column grid */}
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-2">
                {renderRecsCard(
                  'EC2 Right-Sizing',
                  optimization?.ec2Recommendations,
                  'fa-server',
                  'orange',
                  'from-orange-500 to-orange-600',
                  '/rightsizing.html'
                )}
                {renderRecsCard(
                  'EBS Optimization',
                  optimization?.ebsRecommendations,
                  'fa-hdd',
                  'purple',
                  'from-purple-500 to-purple-600',
                  '/rightsizing.html'
                )}
                {renderRecsCard(
                  'Lambda Optimization',
                  optimization?.lambdaRecommendations,
                  'fa-bolt',
                  'yellow',
                  'from-yellow-500 to-yellow-600',
                  '/rightsizing.html'
                )}
                {renderAnomaliesCard(optimization?.anomalies)}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default OptimizationSection;
