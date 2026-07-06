/**
 * ProviderCard Component
 *
 * Displays provider-specific dashboard cards (AWS, GCP, Azure)
 * with metrics like spend, resources, alerts, and security scores
 */

import React from 'react';
import type { ProviderData } from '../../types/dashboard';

interface ProviderCardProps {
  provider: 'AWS' | 'GCP' | 'Azure';
  data: ProviderData | null;
  isLoading: boolean;
  error: string | null;
  accountCount: number;
  onRetry: () => void;
  onViewBreakdown: () => void;
}

const providerConfig = {
  AWS: {
    name: 'Amazon AWS',
    icon: 'fab fa-aws',
    color: '#FF9900',
    accountLabel: 'Accounts',
    borderClass: 'aws-card',
  },
  GCP: {
    name: 'Google Cloud',
    icon: 'fab fa-google',
    color: '#34A853',
    accountLabel: 'Projects',
    borderClass: 'gcp-card',
  },
  Azure: {
    name: 'Microsoft Azure',
    icon: 'fab fa-microsoft',
    color: '#0078D4',
    accountLabel: 'Subscriptions',
    borderClass: 'azure-card',
  },
};

export const ProviderCard: React.FC<ProviderCardProps> = ({
  provider,
  data,
  isLoading,
  error,
  accountCount,
  onRetry,
  onViewBreakdown,
}) => {
  const config = providerConfig[provider];

  const formatCurrency = (value: number | undefined) => {
    if (value === null || value === undefined || isNaN(value)) return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      maximumFractionDigits: 0,
    }).format(value);
  };

  const formatNumber = (value: number | undefined) => {
    if (value === null || value === undefined || isNaN(value)) return '0';
    return new Intl.NumberFormat('en-US', {
      notation: 'compact',
      compactDisplay: 'short',
    }).format(value);
  };

  const getScoreColor = (score: number | undefined) => {
    if (!score) return 'text-gray-500';
    if (score >= 80) return 'text-green-500';
    if (score >= 50) return 'text-yellow-500';
    return 'text-red-500';
  };

  return (
    <div className={`glass-card rounded-xl p-6 flex flex-col h-full provider-card ${config.borderClass} group hover:shadow-2xl transform hover:-translate-y-1 transition-all duration-300`}>
      {/* Error Overlay */}
      {error && (
        <div className="absolute inset-0 z-10 bg-white/95 backdrop-blur-sm flex flex-col items-center justify-center text-center p-4 rounded-xl">
          <div className="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mb-3">
            <i className="fas fa-exclamation-triangle text-red-600 text-2xl"></i>
          </div>
          <p className="text-sm font-bold text-gray-800 mb-1">{provider} Unavailable</p>
          <p className="text-xs text-gray-500 mb-3">Connection failed</p>
          <button
            onClick={onRetry}
            className="px-4 py-2 text-sm font-semibold bg-white border-2 border-gray-300 hover:border-gray-400 rounded-lg shadow-sm transition-all"
          >
            <i className="fas fa-sync-alt mr-2"></i>Retry
          </button>
        </div>
      )}

      {/* Header */}
      <div className="flex justify-between items-start mb-5">
        <div className="flex items-center gap-3">
          <div
            className="w-14 h-14 bg-gradient-to-br rounded-xl flex items-center justify-center shadow-lg"
            style={{
              backgroundColor: `${config.color}20`,
              color: config.color,
            }}
          >
            <i className={`${config.icon} text-3xl`}></i>
          </div>
          <div>
            <h4 className="text-lg font-black text-gray-900">{config.name}</h4>
            {!isLoading && (
              <p className="text-xs font-semibold text-gray-500 flex items-center gap-1">
                <i className="fas fa-layer-group text-[10px]"></i>
                <span>{accountCount} {config.accountLabel}</span>
              </p>
            )}
          </div>
        </div>
      </div>

      {/* Content */}
      {!isLoading && !error && data ? (
        <div className="space-y-4 flex-1 fade-in">
          <div className="bg-gradient-to-br from-gray-50 to-white border border-gray-200 rounded-xl p-4">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Month-to-Date Spend
            </span>
            <div className="mt-2 text-2xl font-black metric-value">
              {formatCurrency(data.selectedAccount?.monthToDateSpend)}
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] p-3 rounded-lg">
              <span className="text-xs font-semibold text-gray-600 mb-1.5 flex items-center gap-1">
                <i className="fas fa-server text-[10px] text-[rgba(0,0,0,0.4)]"></i>
                Resources
              </span>
              <span className="text-lg font-black text-gray-900">
                {formatNumber(data.selectedAccount?.resourceInventory?.totalCount)}
              </span>
            </div>
            {provider === 'Azure' ? (
              <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] p-3 rounded-lg">
                <span className="text-xs font-semibold text-gray-600 mb-1.5 flex items-center gap-1">
                  <i className="fas fa-shield-alt text-[10px] text-[rgba(0,0,0,0.4)]"></i>
                  Score
                </span>
                <span className={`text-lg font-black ${getScoreColor(data.selectedAccount?.securityScore)}`}>
                  {data.selectedAccount?.securityScore || 'N/A'}
                </span>
              </div>
            ) : (
              <div className="bg-[rgba(0,0,0,0.02)] border border-[rgba(0,0,0,0.08)] p-3 rounded-lg">
                <span className="text-xs font-semibold text-gray-600 mb-1.5 flex items-center gap-1">
                  <i className={`fas fa-bell text-[10px] ${(data.selectedAccount?.optimizationSummary?.criticalAlerts || 0) > 0 ? 'text-red-500' : 'text-[rgba(0,0,0,0.4)]'}`}></i>
                  Alerts
                </span>
                <span className={`text-lg font-black ${(data.selectedAccount?.optimizationSummary?.criticalAlerts || 0) > 0 ? 'text-red-600' : 'text-gray-900'}`}>
                  {data.selectedAccount?.optimizationSummary?.criticalAlerts || 0}
                </span>
              </div>
            )}
          </div>
          <button
            onClick={onViewBreakdown}
            className="w-full mt-auto py-3 text-sm font-bold text-white bg-[rgba(0,0,0,0.88)] hover:bg-[rgba(0,0,0,0.75)] rounded-lg transition-all"
          >
            <i className="fas fa-chart-bar mr-2"></i>View Breakdown
          </button>
        </div>
      ) : isLoading ? (
        <div className="space-y-4 animate-pulse flex-1">
          <div className="h-20 bg-gray-200 shimmer rounded-xl"></div>
          <div className="grid grid-cols-2 gap-3">
            <div className="h-16 bg-gray-200 shimmer rounded-lg"></div>
            <div className="h-16 bg-gray-200 shimmer rounded-lg"></div>
          </div>
        </div>
      ) : null}
    </div>
  );
};
