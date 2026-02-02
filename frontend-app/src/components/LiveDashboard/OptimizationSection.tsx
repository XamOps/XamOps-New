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
    return '$' + (num == null ? '0.00' : num.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }));
  };

  // Helper to render empty recommendation card
  const renderEmptyCard = (title: string, icon: string) => (
    <div className="rounded-xl p-4 border border-gray-100 bg-gray-50/50 backdrop-blur-sm shadow-inner">
      <div className="flex items-center justify-between mb-4">
        <h4 className="font-black text-[10px] text-gray-400 uppercase tracking-widest flex items-center">
          <div className="w-6 h-6 bg-gray-200 rounded-lg flex items-center justify-center mr-2 shadow-sm">
            {icon === 'fa-server' && (
              <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" />
              </svg>
            )}
            {icon === 'fa-hdd' && (
              <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3zM9 12h6" />
              </svg>
            )}
            {icon === 'fa-bolt' && (
              <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            )}
            {icon === 'fa-exclamation-triangle' && (
              <svg className="w-3.5 h-3.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
            )}
          </div>
          <span className="truncate">{title}</span>
        </h4>
        <span className="bg-gray-100 text-gray-400 text-[10px] font-black px-1.5 py-0.5 rounded-md flex-shrink-0 border border-gray-200">0</span>
      </div>
      <div className="text-center py-6">
        <div className="w-12 h-12 bg-white rounded-full flex items-center justify-center mx-auto mb-3 shadow-sm border border-gray-100">
          <svg className="w-6 h-6 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </div>
        <p className="text-[11px] text-gray-500 font-bold uppercase tracking-wider">Optimized</p>
        <p className="text-[10px] text-gray-400 mt-1">No pending actions</p>
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
      <div className={`optimization-card rounded-xl p-4 border border-gray-100 bg-white shadow-sm hover:shadow-md transition-all border-l-4 border-l-${color}-500 group`}>
        <div className="flex items-center justify-between mb-4">
          <h4 className="font-black text-[10px] text-gray-500 uppercase tracking-widest flex items-center">
            <div className={`w-6 h-6 bg-gradient-to-br ${gradientClass} rounded-lg flex items-center justify-center mr-2 shadow-lg shadow-${color}-500/20 group-hover:scale-110 transition-transform`}>
              {icon === 'fa-server' && (
                <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01" />
                </svg>
              )}
              {icon === 'fa-hdd' && (
                <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7v10c0 2 1 3 3 3h10c2 0 3-1 3-3V7c0-2-1-3-3-3H7c-2 0-3 1-3 3zM9 12h6" />
                </svg>
              )}
              {icon === 'fa-bolt' && (
                <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              )}
            </div>
            <span className="truncate">{title}</span>
          </h4>
          <span className={`bg-${color}-50 text-${color}-600 text-[10px] font-black px-1.5 py-0.5 rounded-md flex-shrink-0 border border-${color}-100`}>
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
              {recs.slice(0, 2).map((rec) => {
                const resourceId = rec.resourceId || rec.instanceId || rec.volumeId || rec.functionName || '-';
                const currentType = rec.currentType || rec.instanceType || rec.volumeType || '-';
                const recommendedType = rec.recommendedType || (rec.recommendedMemory ? `${rec.recommendedMemory}MB` : '-');
                const savings = rec.estimatedMonthlySavings || rec.estimatedSavings || 0;
                const uniqueKey = `${resourceId}-${savings}`;

                return (
                  <tr key={uniqueKey} className="hover:bg-gray-50 transition-colors">
                    <td className="px-2 py-1.5 font-mono text-xs text-gray-600 max-w-[120px] truncate" title={resourceId}>
                      {resourceId}
                    </td>
                    <td className="px-2 py-1.5">
                      <div className="flex items-center space-x-1">
                        <span className="text-gray-600 text-xs truncate max-w-[80px]" title={currentType}>{currentType}</span>
                        <svg className="w-2.5 h-2.5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M14 5l7 7m0 0l-7 7m7-7H3" />
                        </svg>
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
          <div className="mt-3 text-center border-t border-gray-50 pt-3">
            <a
              href={`${linkPath.replace('.html', '')}?accountIds=${selectedAccountIds.join(',')}`}
              className={`inline-flex items-center text-[10px] uppercase font-black tracking-widest text-${color}-600 hover:text-${color}-700 transition-colors`}
            >
              +{recs.length - 2} more insights
              <svg className="w-3 h-3 ml-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M17 8l4 4m0 0l-4 4m4-4H3" />
              </svg>
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
              <div className="w-5 h-5 bg-gray-300 rounded flex items-center justify-center mr-1.5 shadow-sm">
                <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
              </div>
              <span className="truncate">Cost Anomalies</span>
            </h4>
            <span className="bg-gray-200 text-gray-500 text-xs font-semibold px-1.5 py-0.5 rounded-full flex-shrink-0">0</span>
          </div>
          <div className="text-center py-8">
            <svg className="w-8 h-8 text-gray-300 mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
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
            <div className="w-5 h-5 bg-gradient-to-br from-red-500 to-red-600 rounded flex items-center justify-center mr-1.5 shadow-lg shadow-red-500/20">
              <svg className="w-3.5 h-3.5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
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
              {anomalies.slice(0, 2).map((a) => (
                <tr key={`${a.service || a.month}-${a.startDate}`} className="hover:bg-gray-50 transition-colors">
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
          <div className="mt-3 text-center border-t border-gray-50 pt-3">
            <a
              href={`/alerts?accountIds=${selectedAccountIds.join(',')}`}
              className="inline-flex items-center text-[10px] uppercase font-black tracking-widest text-red-600 hover:text-red-700 transition-colors"
            >
              +{anomalies.length - 2} more anomalies
              <svg className="w-3 h-3 ml-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M17 8l4 4m0 0l-4 4m4-4H3" />
              </svg>
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
    <div className="glass-card p-6 rounded-2xl border border-gray-200 shadow-xl shadow-indigo-500/5 relative overflow-hidden h-full">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-amber-50 rounded-xl flex items-center justify-center border border-amber-100 shadow-sm">
            <svg className="w-6 h-6 text-amber-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" />
            </svg>
          </div>
          <div>
            <h3 className="text-xl font-black text-gray-900 tracking-tight uppercase px-1">Optimization Hub</h3>
            <p className="text-[10px] text-gray-400 font-bold uppercase tracking-widest flex items-center gap-1.5 px-1 mt-0.5">
              <span className="w-1.5 h-1.5 bg-amber-400 rounded-full animate-pulse"></span>
              Strategic Insights
            </p>
          </div>
        </div>
        {isLoading && <div className="loading-spinner w-5 h-5 border-2 border-indigo-500/20 border-t-indigo-500"></div>}
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
              <svg className="w-8 h-8 text-blue-400 mx-auto mb-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13.875 18.825A10.05 10.05 0 0112 19c-4.478 0-8.268-2.943-9.543-7a9.97 9.97 0 011.563-3.029m5.858.908a3 3 0 114.243 4.243M9.878 9.878l4.242 4.242M9.88 9.88l-3.29-3.29m7.532 7.532l3.29 3.29M3 3l18 18" />
              </svg>
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
                    <div className="w-7 h-7 bg-gradient-to-br from-green-500 to-green-600 rounded-lg flex items-center justify-center flex-shrink-0 shadow-lg shadow-green-500/20">
                      <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                      </svg>
                    </div>
                    <div>
                      <p className="text-xs text-green-700 font-medium">Potential Savings</p>
                      <p className="text-base font-bold text-green-800">
                        {formatCurrency(totalSavings)}&nbsp;<span className="text-xs font-normal text-green-600">/mo</span>
                      </p>
                    </div>
                  </div>
                </div>

                {/* Critical Alerts Card */}
                <div className="bg-gradient-to-br from-red-50 to-red-100 border border-red-200 p-2.5 rounded-lg">
                  <div className="flex items-center space-x-2">
                    <div className="w-7 h-7 bg-gradient-to-br from-red-500 to-red-600 rounded-lg flex items-center justify-center flex-shrink-0 shadow-lg shadow-red-500/20">
                      <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                      </svg>
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
