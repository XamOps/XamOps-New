/**
 * Unified Dashboard Page
 *
 * Main dashboard showing multi-cloud overview with AWS, GCP, and Azure metrics
 * Converted from unified-dashboard.html to modern React with TypeScript
 *
 * Changes:
 * - Replaced Alpine.js with React hooks (useState, useEffect, useCallback)
 * - Extracted data fetching logic to custom hook (useUnifiedDashboard)
 * - Modularized components (Ticker, Header, MetricCard, ProviderCard, Charts, Modal)
 * - Preserved all backend API endpoints exactly as they were
 * - Maintained same business logic for calculations and UI states
 * - Kept same light theme, colors, and visual design
 * - Added proper TypeScript types for type safety
 */

import React, { useCallback } from 'react';
import { Ticker } from '../components/Dashboard/Ticker';
import { DashboardHeader } from '../components/Dashboard/DashboardHeader';
import { MetricCard } from '../components/Dashboard/MetricCard';
import { ProviderCard } from '../components/Dashboard/ProviderCard';
import { CostChart } from '../components/Dashboard/CostChart';
import { ProviderDonutChart } from '../components/Dashboard/ProviderDonutChart';
import { AccountBreakdownModal } from '../components/Dashboard/AccountBreakdownModal';
import { useUnifiedDashboard } from '../hooks/useUnifiedDashboard';
import type { ViewMode, SubAccount } from '../types/dashboard';

export const UnifiedDashboard: React.FC = () => {
  const {
    isLoading,
    error,
    summaryData,
    awsData,
    gcpData,
    azureData,
    showModal,
    viewMode,
    activeProvider,
    activeAccountList,
    lastUpdatedText,
    serverTime,
    fetchAll,
    fetchProvider,
    setShowModal,
    setViewMode,
    setActiveProvider,
    setActiveAccountList,
    getAllAccounts,
  } = useUnifiedDashboard();

  // Formatting helpers
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

  // Computed metrics
  const getTotalCost = () => {
    return (
      (awsData?.selectedAccount?.monthToDateSpend || 0) +
      (gcpData?.selectedAccount?.monthToDateSpend || 0) +
      (azureData?.selectedAccount?.monthToDateSpend || 0)
    );
  };

  const getTotalForecast = () => {
    return (
      (awsData?.selectedAccount?.forecastedSpend || 0) +
      (gcpData?.selectedAccount?.forecastedSpend || 0) +
      (azureData?.selectedAccount?.forecastedSpend || 0)
    );
  };

  const getBudgetPercent = () => {
    const cost = getTotalCost();
    const forecast = getTotalForecast();
    return forecast > 0 ? Math.round((cost / forecast) * 100) : 0;
  };

  const getTotalSavings = () => {
    return (
      (awsData?.selectedAccount?.optimizationSummary?.totalPotentialSavings || 0) +
      (gcpData?.selectedAccount?.optimizationSummary?.totalPotentialSavings || 0) +
      (azureData?.selectedAccount?.optimizationSummary?.totalPotentialSavings || 0)
    );
  };

  const getTotalWasteCount = () => {
    const awsWaste = awsData?.selectedAccount?.optimizationSummary?.criticalAlerts || 0;
    const gcpWaste = gcpData?.selectedAccount?.optimizationSummary?.criticalAlerts || 0;
    const azureWaste = azureData?.selectedAccount?.optimizationSummary?.criticalAlerts || 0;
    return awsWaste + gcpWaste + azureWaste + 12;
  };

  const getTotalAlertsCount = () => {
    const awsAlerts = awsData?.selectedAccount?.optimizationSummary?.criticalAlerts || 0;
    const gcpAlerts = gcpData?.selectedAccount?.optimizationSummary?.criticalAlerts || 0;
    const azureAlerts = azureData?.selectedAccount?.optimizationSummary?.criticalAlerts || 0;
    return awsAlerts + gcpAlerts + azureAlerts;
  };

  const getAverageSecurityScore = () => {
    let total = 0,
      count = 0;
    if (awsData?.selectedAccount?.securityScore) {
      total += awsData.selectedAccount.securityScore;
      count++;
    }
    if (gcpData?.selectedAccount?.securityScore) {
      total += gcpData.selectedAccount.securityScore;
      count++;
    }
    if (azureData?.selectedAccount?.securityScore) {
      total += azureData.selectedAccount.securityScore;
      count++;
    }
    return count === 0 ? 100 : Math.round(total / count);
  };

  const getSecurityLabel = () => {
    const score = getAverageSecurityScore();
    if (score >= 80) return 'Strong';
    if (score >= 50) return 'Moderate';
    return 'Critical';
  };

  const getScoreColor = (score: number) => {
    if (score >= 80) return 'text-green-500';
    if (score >= 50) return 'text-yellow-500';
    return 'text-red-500';
  };

  // Modal handlers
  const openBreakdown = (provider: string, list: SubAccount[] | undefined) => {
    setViewMode('cost');
    setActiveProvider(provider);
    const sortedList = list
      ? JSON.parse(JSON.stringify(list)).sort((a: SubAccount, b: SubAccount) => b.monthToDateSpend - a.monthToDateSpend)
      : [];
    setActiveAccountList(sortedList);
    setShowModal(true);
  };

  const openResourceBreakdown = () => {
    setViewMode('resource');
    setActiveProvider('All');
    const accounts = getAllAccounts().sort((a, b) => {
      const countA = a.resourceInventory?.totalCount || a.resourceCount || 0;
      const countB = b.resourceInventory?.totalCount || b.resourceCount || 0;
      return countB - countA;
    });
    setActiveAccountList(accounts);
    setShowModal(true);
  };

  const openWasteBreakdown = () => {
    setViewMode('waste');
    setActiveProvider('All');
    const accounts = getAllAccounts().sort((a, b) => {
      const valA = a.optimizationSummary?.criticalAlerts || 0;
      const valB = b.optimizationSummary?.criticalAlerts || 0;
      return valB - valA;
    });
    setActiveAccountList(accounts);
    setShowModal(true);
  };

  const openAlertsBreakdown = () => {
    setViewMode('alerts');
    setActiveProvider('All');
    const accounts = getAllAccounts().sort((a, b) => {
      const valA = a.optimizationSummary?.criticalAlerts || 0;
      const valB = b.optimizationSummary?.criticalAlerts || 0;
      return valB - valA;
    });
    setActiveAccountList(accounts);
    setShowModal(true);
  };

  const openSavingsBreakdown = () => {
    setViewMode('savings');
    setActiveProvider('All');
    const accounts = getAllAccounts().sort((a, b) => {
      const valA = a.optimizationSummary?.totalPotentialSavings || 0;
      const valB = b.optimizationSummary?.totalPotentialSavings || 0;
      return valB - valA;
    });
    setActiveAccountList(accounts);
    setShowModal(true);
  };

  // Navigation handlers
  const navigateToAccount = useCallback((accountId: string, mode: ViewMode) => {
    const account = getAllAccounts().find((a) => a.id === accountId);
    if (!account) {
      console.error('Account not found:', accountId);
      return;
    }

    const provider = (account.provider || '').toUpperCase();
    let targetPage = '';
    const queryParams = new URLSearchParams();
    queryParams.append('accountIds', accountId);

    if (mode === 'resource') {
      if (provider === 'AWS') {
        targetPage = '/cloudlist.html';
        queryParams.append('accountId', accountId);
      } else if (provider === 'GCP') {
        targetPage = '/gcp_cloudlist.html';
        queryParams.append('projectId', accountId);
      } else if (provider === 'AZURE') {
        targetPage = '/azure_cloudlist.html';
        queryParams.append('subscriptionId', accountId);
      } else {
        targetPage = '/cloudlist.html';
        queryParams.append('accountId', accountId);
      }
    } else if (mode === 'waste' || mode === 'savings') {
      if (provider === 'AWS') {
        targetPage = '/waste.html';
        queryParams.append('accountId', accountId);
      } else if (provider === 'GCP') {
        targetPage = '/gcp_waste.html';
        queryParams.append('projectId', accountId);
      } else if (provider === 'AZURE') {
        targetPage = '/azure_waste.html';
        queryParams.append('subscriptionId', accountId);
      } else {
        targetPage = '/waste.html';
        queryParams.append('accountId', accountId);
      }
    } else if (mode === 'alerts') {
      if (provider === 'AWS') {
        targetPage = '/alerts.html';
        queryParams.append('accountId', accountId);
      } else if (provider === 'GCP') {
        targetPage = '/gcp_alerts.html';
        queryParams.append('projectId', accountId);
      } else if (provider === 'AZURE') {
        targetPage = '/azure_alerts.html';
        queryParams.append('subscriptionId', accountId);
      } else {
        targetPage = '/alerts.html';
        queryParams.append('accountId', accountId);
      }
    } else {
      if (provider === 'AWS') {
        targetPage = '/dashboard.html';
        queryParams.append('accountId', accountId);
      } else if (provider === 'GCP') {
        targetPage = '/gcp_dashboard.html';
        queryParams.append('projectId', accountId);
      } else if (provider === 'AZURE') {
        targetPage = '/azure_dashboard.html';
        queryParams.append('subscriptionId', accountId);
      } else {
        targetPage = '/dashboard.html';
        queryParams.append('accountId', accountId);
      }
    }

    const finalUrl = `${targetPage}?${queryParams.toString()}`;
    window.location.href = finalUrl;
  }, [getAllAccounts]);

  const handleRefresh = () => {
    fetchAll(true);
  };

  return (
    <div className="flex-1 flex flex-col h-screen overflow-y-auto overflow-x-hidden">
      <div id="main-content" className="flex flex-col h-full w-full">
        {/* Ticker */}
        <Ticker />

        {/* Header */}
        <DashboardHeader
          lastUpdatedText={lastUpdatedText}
          serverTime={serverTime}
          isLoading={isLoading.summary}
          onRefresh={handleRefresh}
        />

        {/* Main Content */}
        <main className="flex-1 overflow-y-auto p-8 space-y-6 custom-scrollbar">
          {/* Development Warning */}
          <div className="bg-amber-50 border-l-4 border-amber-500 p-3 rounded-r shadow-sm">
            <div className="flex items-center">
              <div className="flex-shrink-0">
                <i className="fas fa-exclamation-triangle text-amber-500"></i>
              </div>
              <div className="ml-3">
                <p className="text-xs text-amber-800">
                  <span className="font-semibold">Under Development:</span> This feature is currently being developed.
                  Data may be inaccurate or incomplete.
                </p>
              </div>
            </div>
          </div>

          {/* No Accounts Warning */}
          {!isLoading.summary && summaryData.totalAccounts === 0 && (
            <div className="bg-blue-50 border-l-4 border-blue-500 p-4 rounded-r shadow-sm mb-6">
              <div className="flex">
                <div className="flex-shrink-0">
                  <i className="fas fa-info-circle text-blue-500 text-xl"></i>
                </div>
                <div className="ml-3">
                  <h3 className="text-sm font-medium text-blue-800">No Cloud Accounts Connected</h3>
                  <div className="mt-2 text-sm text-blue-700">
                    <p>It looks like you don't have any cloud accounts connected yet. The dashboard will remain empty until you add one.</p>
                    <p className="mt-2">
                      <a href="/account-manager.html" className="font-bold underline hover:text-blue-900">
                        Go to Account Management &rarr;
                      </a>
                    </p>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* Top Metrics Row */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
            <MetricCard
              title="Total MTD Cost"
              value={formatCurrency(getTotalCost())}
              icon="fas fa-dollar-sign text-indigo-600"
              iconBgColor="from-indigo-100 to-purple-100"
              borderColor="border-l-indigo-500"
              isLoading={isLoading.summary}
            >
              <div className="mt-5 h-2 w-full bg-gray-100 rounded-full overflow-hidden shadow-inner">
                <div
                  className="h-full bg-gradient-to-r from-indigo-500 via-purple-500 to-indigo-600 transition-all duration-1000 shadow-lg"
                  style={{ width: `${getBudgetPercent()}%` }}
                ></div>
              </div>
              <div className="mt-3 text-xs font-medium text-gray-500 flex justify-between items-center">
                <span className="flex items-center gap-1">
                  <i className="fas fa-chart-line text-[10px] text-indigo-500"></i>
                  Projection
                </span>
                <span className="font-bold text-gray-700">{formatCurrency(getTotalForecast())}</span>
              </div>
            </MetricCard>

            <MetricCard
              title="Budget Utilization"
              value={`${Math.round(getBudgetPercent())}%`}
              icon={`fas fa-chart-pie ${getBudgetPercent() > 90 ? 'text-red-600' : 'text-blue-600'}`}
              iconBgColor={getBudgetPercent() > 90 ? 'from-red-100 to-orange-100' : 'from-blue-100 to-indigo-100'}
              borderColor="border-l-purple-500"
              isLoading={isLoading.summary}
            >
              <div className="mt-4 flex items-center gap-2">
                <span
                  className={`text-xs font-semibold px-3 py-1.5 rounded-full flex items-center gap-1.5 ${getBudgetPercent() > 100 ? 'bg-red-100 text-red-700' : 'bg-green-100 text-green-700'
                    }`}
                >
                  <i className={`fas ${getBudgetPercent() > 100 ? 'fa-arrow-up' : 'fa-check'}`}></i>
                  <span>{getBudgetPercent() > 100 ? 'Over Budget' : 'On Track'}</span>
                </span>
              </div>
              <p className="text-xs text-gray-500 mt-3 font-medium">vs Forecasted Spend</p>
            </MetricCard>

            <MetricCard
              title="Total Resources"
              value={formatNumber(summaryData.totalResources)}
              subtitle={`Active across ${summaryData.totalAccounts} accounts`}
              icon="fas fa-layer-group text-blue-600"
              iconBgColor="from-blue-100 to-indigo-100"
              borderColor="border-l-blue-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openResourceBreakdown}
            />

            <MetricCard
              title="Potential Savings"
              value={formatCurrency(getTotalSavings())}
              subtitle="Optimization available"
              icon="fas fa-piggy-bank text-green-600"
              iconBgColor="from-green-100 to-emerald-100"
              borderColor="border-l-green-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openSavingsBreakdown}
              onClick={() => (window.location.href = '/waste.html')}
            />
          </div>

          {/* Second Metrics Row */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
            <MetricCard
              title="Total Waste"
              value={getTotalWasteCount()}
              subtitle="Idle Resources Detected"
              icon="fas fa-trash-alt text-lg text-orange-600"
              iconBgColor="from-orange-100 to-amber-100"
              borderColor="border-l-orange-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openWasteBreakdown}
            />

            <MetricCard
              title="Active Alerts"
              value={getTotalAlertsCount()}
              subtitle="Critical Issues"
              icon="fas fa-bell text-lg text-red-600"
              iconBgColor="from-red-100 to-rose-100"
              borderColor="border-l-red-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openAlertsBreakdown}
            />

            <div className="glass-card rounded-xl p-6 metric-card border-l-4 border-l-cyan-500">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Code Quality</h3>
                <div className="w-12 h-12 bg-gradient-to-br from-cyan-100 to-blue-100 text-cyan-600 rounded-xl flex items-center justify-center shadow-sm">
                  <i className="fas fa-code text-lg"></i>
                </div>
              </div>
              {!isLoading.summary ? (
                <div className="fade-in">
                  <div className="flex items-baseline gap-2 mb-3">
                    <span className="text-5xl font-black metric-value">A</span>
                    <span className="text-sm font-semibold text-gray-500 px-2 py-1 bg-gray-100 rounded-lg">Rating</span>
                  </div>
                  <p className="text-xs font-medium text-gray-500 flex items-center gap-1">
                    <i className="fas fa-check-circle text-[10px] text-cyan-500"></i>
                    Passed Gates: <span className="font-bold text-gray-700">98%</span>
                  </p>
                </div>
              ) : (
                <div className="h-10 w-28 bg-gray-200 shimmer rounded-lg animate-pulse"></div>
              )}
            </div>

            <div className="glass-card rounded-xl p-6 metric-card border-l-4 border-l-emerald-500">
              <div className="flex items-center justify-between mb-3">
                <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Security Posture</h3>
                <div className="w-12 h-12 bg-gradient-to-br from-emerald-100 to-green-100 text-emerald-600 rounded-xl flex items-center justify-center shadow-sm">
                  <i className="fas fa-shield-alt text-lg"></i>
                </div>
              </div>
              {!isLoading.summary ? (
                <div className="flex items-center gap-4 fade-in">
                  <div className="relative w-16 h-16 flex items-center justify-center">
                    <svg className="w-full h-full transform -rotate-90">
                      <circle cx="32" cy="32" r="28" stroke="#f3f4f6" strokeWidth="5" fill="transparent" />
                      <circle
                        cx="32"
                        cy="32"
                        r="28"
                        stroke="currentColor"
                        strokeWidth="5"
                        fill="transparent"
                        className={`transition-all duration-1000 ease-out drop-shadow-sm ${getScoreColor(
                          getAverageSecurityScore()
                        )}`}
                        strokeDasharray="176"
                        strokeDashoffset={176 - (176 * getAverageSecurityScore()) / 100}
                      />
                    </svg>
                    <span className="absolute text-lg font-black metric-value">{getAverageSecurityScore()}</span>
                  </div>
                  <div>
                    <p className="text-xl font-black metric-value mb-1">{getSecurityLabel()}</p>
                    <p className="text-xs font-medium text-gray-500 flex items-center gap-1">
                      <i className="fas fa-globe text-[10px] text-emerald-500"></i>
                      Global Compliance
                    </p>
                  </div>
                </div>
              ) : (
                <div className="animate-pulse flex gap-4 items-center">
                  <div className="h-16 w-16 rounded-full bg-gray-200 shimmer"></div>
                  <div className="space-y-2">
                    <div className="h-6 w-24 bg-gray-200 shimmer rounded"></div>
                    <div className="h-3 w-32 bg-gray-200 shimmer rounded"></div>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Charts Row */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <CostChart awsData={awsData} gcpData={gcpData} azureData={azureData} />
            <ProviderDonutChart awsData={awsData} gcpData={gcpData} azureData={azureData} />
          </div>

          {/* Provider Cards Row */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <ProviderCard
              provider="AWS"
              data={awsData}
              isLoading={isLoading.aws}
              error={error.aws}
              accountCount={summaryData.awsCount}
              onRetry={() => fetchProvider('aws', true)}
              onViewBreakdown={() => openBreakdown('AWS', awsData?.selectedAccount?.subAccounts)}
            />
            <ProviderCard
              provider="GCP"
              data={gcpData}
              isLoading={isLoading.gcp}
              error={error.gcp}
              accountCount={summaryData.gcpCount}
              onRetry={() => fetchProvider('gcp', true)}
              onViewBreakdown={() => openBreakdown('GCP', gcpData?.selectedAccount?.subAccounts)}
            />
            <ProviderCard
              provider="Azure"
              data={azureData}
              isLoading={isLoading.azure}
              error={error.azure}
              accountCount={summaryData.azureCount}
              onRetry={() => fetchProvider('azure', true)}
              onViewBreakdown={() => openBreakdown('Azure', azureData?.selectedAccount?.subAccounts)}
            />
          </div>
        </main>
      </div>

      {/* Modal */}
      <AccountBreakdownModal
        show={showModal}
        onClose={() => setShowModal(false)}
        provider={activeProvider}
        viewMode={viewMode}
        accountList={activeAccountList}
        onSelectAccount={navigateToAccount}
      />
    </div>
  );
};
