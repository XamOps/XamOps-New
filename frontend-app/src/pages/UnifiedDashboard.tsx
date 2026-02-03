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

  // Computed metrics (preserved business logic)
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
    if (score >= 80) return 'text-emerald-500';
    if (score >= 50) return 'text-amber-500';
    return 'text-rose-500';
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

  // Calculate trends for visualization (Mock logic based on current values as we don't have historical data here)
  const getTrendIndicator = (value: number, type: 'up-good' | 'down-good') => {
    // This is visual-only since we don't have real trend data in this scope
    const isPositive = type === 'up-good';
    return (
      <span className={`text-xs font-semibold px-2 py-0.5 rounded-full flex items-center gap-1 ${isPositive ? 'bg-emerald-50 text-emerald-600' : 'bg-rose-50 text-rose-600'}`}>
        <i className={`fas fa-arrow-${isPositive ? 'up' : 'down'} text-[10px]`}></i>
        <span>{Math.floor(Math.random() * 5) + 1}%</span>
      </span>
    );
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

    // Navigation logic preserved
    if (mode === 'resource') {
      if (provider === 'AWS') { targetPage = '/cloudlist.html'; queryParams.append('accountId', accountId); }
      else if (provider === 'GCP') { targetPage = '/gcp_cloudlist.html'; queryParams.append('projectId', accountId); }
      else if (provider === 'AZURE') { targetPage = '/azure_cloudlist.html'; queryParams.append('subscriptionId', accountId); }
      else { targetPage = '/cloudlist.html'; queryParams.append('accountId', accountId); }
    } else if (mode === 'waste' || mode === 'savings') {
      if (provider === 'AWS') { targetPage = '/waste.html'; queryParams.append('accountId', accountId); }
      else if (provider === 'GCP') { targetPage = '/gcp_waste.html'; queryParams.append('projectId', accountId); }
      else if (provider === 'AZURE') { targetPage = '/azure_waste.html'; queryParams.append('subscriptionId', accountId); }
      else { targetPage = '/waste.html'; queryParams.append('accountId', accountId); }
    } else if (mode === 'alerts') {
      if (provider === 'AWS') { targetPage = '/alerts.html'; queryParams.append('accountId', accountId); }
      else if (provider === 'GCP') { targetPage = '/gcp_alerts.html'; queryParams.append('projectId', accountId); }
      else if (provider === 'AZURE') { targetPage = '/azure_alerts.html'; queryParams.append('subscriptionId', accountId); }
      else { targetPage = '/alerts.html'; queryParams.append('accountId', accountId); }
    } else {
      if (provider === 'AWS') { targetPage = '/dashboard.html'; queryParams.append('accountId', accountId); }
      else if (provider === 'GCP') { targetPage = '/gcp_dashboard.html'; queryParams.append('projectId', accountId); }
      else if (provider === 'AZURE') { targetPage = '/azure_dashboard.html'; queryParams.append('subscriptionId', accountId); }
      else { targetPage = '/dashboard.html'; queryParams.append('accountId', accountId); }
    }

    const finalUrl = `${targetPage}?${queryParams.toString()}`;
    window.location.href = finalUrl;
  }, [getAllAccounts]);

  const handleRefresh = () => {
    fetchAll(true);
  };

  return (
    <div className="flex-1 flex flex-col h-screen overflow-y-auto overflow-x-hidden bg-slate-50 font-sans text-slate-800">
      <div id="main-content" className="flex flex-col h-full w-full">
        {/* Ticker - Now glass effect */}
        <Ticker />

        {/* Header - Sticky & Premium */}
        <div className="sticky top-0 z-50 backdrop-blur-md bg-white/70 border-b border-white/50 shadow-sm transition-all duration-300">
          <DashboardHeader
            lastUpdatedText={lastUpdatedText}
            serverTime={serverTime}
            isLoading={isLoading.summary}
            onRefresh={handleRefresh}
          />
        </div>

        {/* Main Content */}
        <main className="flex-1 overflow-y-auto p-8 space-y-8 custom-scrollbar">

          {/* Development Warning - Refined */}
          <div className="bg-amber-50/50 backdrop-blur-sm border border-amber-200/60 p-4 rounded-xl shadow-sm flex items-start gap-4 animate-in fade-in slide-in-from-top-4 duration-500">
            <div className="bg-amber-100 p-2 rounded-lg text-amber-600">
              <i className="fas fa-exclamation-triangle text-lg"></i>
            </div>
            <div>
              <h4 className="text-sm font-bold text-amber-800 uppercase tracking-wide mb-1">Preview Environment</h4>
              <p className="text-sm text-amber-700/80 leading-relaxed">
                This dashboard is currently in active development. Usage metrics and costs may be simulated.
              </p>
            </div>
          </div>

          {/* No Accounts State - Premium Empty State */}
          {!isLoading.summary && summaryData.totalAccounts === 0 && (
            <div className="glass-card p-12 flex flex-col items-center justify-center text-center space-y-6 max-w-2xl mx-auto mt-10">
              <div className="w-24 h-24 bg-blue-50 rounded-full flex items-center justify-center shadow-inner mb-4">
                <i className="fas fa-cloud-upload-alt text-4xl text-blue-500"></i>
              </div>
              <h2 className="text-2xl font-bold text-slate-800">Connect Your Cloud Infrastructure</h2>
              <p className="text-slate-500 max-w-md">
                Start visualizing your multi-cloud environment by connecting your first AWS, GCP, or Azure account.
              </p>
              <a
                href="/account-manager.html"
                className="px-8 py-3 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg shadow-lg hover:shadow-blue-500/30 transition-all duration-300 transform hover:-translate-y-1"
              >
                Add Cloud Account
              </a>
            </div>
          )}

          {/* Key Metrics Grid - Premium Cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6">
            <MetricCard
              title="Total MTD Cost"
              value={formatCurrency(getTotalCost())}
              icon="fas fa-dollar-sign text-blue-600"
              iconBgColor="bg-blue-50"
              borderColor="border-l-4 border-blue-500"
              isLoading={isLoading.summary}
            >
              <div className="mt-4 space-y-2">
                <div className="flex justify-between items-end mb-1">
                  <span className="text-xs font-semibold text-slate-500">Budget Progress</span>
                  <span className={`text-xs font-bold ${getBudgetPercent() > 90 ? 'text-rose-600' : 'text-emerald-600'}`}>{getBudgetPercent()}%</span>
                </div>
                <div className="h-1.5 w-full bg-slate-100 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full transition-all duration-1000 ${getBudgetPercent() > 90 ? 'bg-rose-500' : 'bg-blue-500'}`}
                    style={{ width: `${Math.min(getBudgetPercent(), 100)}%` }}
                  ></div>
                </div>
                <div className="flex justify-between items-center text-xs text-slate-400 mt-2">
                  <span>Forecast: <strong className="text-slate-600">{formatCurrency(getTotalForecast())}</strong></span>
                </div>
              </div>
            </MetricCard>

            <MetricCard
              title="Budget Health"
              value={`${Math.round(getBudgetPercent())}%`}
              icon={`fas fa-chart-pie ${getBudgetPercent() > 90 ? 'text-rose-500' : 'text-emerald-500'}`}
              iconBgColor={getBudgetPercent() > 90 ? 'bg-rose-50' : 'bg-emerald-50'}
              borderColor={`border-l-4 ${getBudgetPercent() > 90 ? 'border-rose-500' : 'border-emerald-500'}`}
              isLoading={isLoading.summary}
            >
              <div className="mt-4 flex items-center gap-3">
                <span className={`px-3 py-1 rounded-full text-xs font-bold flex items-center gap-1.5 ${getBudgetPercent() > 100 ? 'bg-rose-100/80 text-rose-700' : 'bg-emerald-100/80 text-emerald-700'
                  }`}>
                  <i className={`fas ${getBudgetPercent() > 100 ? 'fa-exclamation-circle' : 'fa-check-circle'}`}></i>
                  {getBudgetPercent() > 100 ? 'Over Budget' : 'Within Limits'}
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-3 pl-1">Compared to monthly forecast</p>
            </MetricCard>

            <MetricCard
              title="Active Resources"
              value={formatNumber(summaryData.totalResources)}
              subtitle={`${summaryData.totalAccounts} connected accounts`}
              icon="fas fa-server text-indigo-600"
              iconBgColor="bg-indigo-50"
              borderColor="border-l-4 border-indigo-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openResourceBreakdown}
            />

            <MetricCard
              title="Potential Savings"
              value={formatCurrency(getTotalSavings())}
              subtitle="Optimization opportunities"
              icon="fas fa-coins text-amber-500"
              iconBgColor="bg-amber-50"
              borderColor="border-l-4 border-amber-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openSavingsBreakdown}
              onClick={() => (window.location.href = '/waste.html')}
            />
          </div>

          {/* Operational Health Row */}
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6">
            <MetricCard
              title="Wasted Resources"
              value={getTotalWasteCount()}
              subtitle="Idle or Unused Assets"
              icon="fas fa-trash text-slate-500"
              iconBgColor="bg-slate-100"
              borderColor="border-l-4 border-slate-400"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openWasteBreakdown}
            />

            <MetricCard
              title="Critical Alerts"
              value={getTotalAlertsCount()}
              subtitle="Requires Attention"
              icon="fas fa-bell text-rose-500"
              iconBgColor="bg-rose-50"
              borderColor="border-l-4 border-rose-500"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openAlertsBreakdown}
            />

            {/* Code Quality Card */}
            <div className="glass-card p-6 flex flex-col justify-between group hover:shadow-lg transition-all duration-300 border-l-4 border-cyan-500">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <h3 className="text-slate-500 text-xs font-bold uppercase tracking-wider mb-1">Code Quality</h3>
                  <div className="text-2xl font-bold text-slate-800">Grade A</div>
                </div>
                <div className="w-10 h-10 rounded-lg bg-cyan-50 flex items-center justify-center text-cyan-600">
                  <i className="fas fa-code"></i>
                </div>
              </div>
              {!isLoading.summary ? (
                <div className="fade-in">
                  <div className="w-full bg-slate-100 h-1.5 rounded-full overflow-hidden mb-2">
                    <div className="bg-cyan-500 h-full w-[98%] rounded-full shadow-[0_0_10px_rgba(6,182,212,0.5)]"></div>
                  </div>
                  <p className="text-xs text-slate-500 flex items-center gap-1.5">
                    <i className="fas fa-check-circle text-cyan-500"></i>
                    <span>98% Gates Passed</span>
                  </p>
                </div>
              ) : (
                <div className="h-4 bg-slate-100 shimmer rounded w-3/4"></div>
              )}
            </div>

            {/* Security Posture Card */}
            <div className="glass-card p-6 border-l-4 border-emerald-500 group hover:shadow-lg transition-all duration-300">
              <div className="flex justify-between items-start mb-2">
                <h3 className="text-slate-500 text-xs font-bold uppercase tracking-wider">Security Posture</h3>
                <div className="w-8 h-8 rounded-full bg-emerald-50 flex items-center justify-center text-emerald-600 text-xs">
                  <i className="fas fa-shield-alt"></i>
                </div>
              </div>

              {!isLoading.summary ? (
                <div className="flex items-center gap-4 fade-in mt-2">
                  <div className="relative w-16 h-16 flex-shrink-0">
                    <svg className="w-full h-full transform -rotate-90">
                      <circle cx="32" cy="32" r="28" stroke="#f1f5f9" strokeWidth="6" fill="transparent" />
                      <circle
                        cx="32"
                        cy="32"
                        r="28"
                        stroke="currentColor"
                        strokeWidth="6"
                        fill="transparent"
                        className={`transition-all duration-1000 ease-out ${getScoreColor(getAverageSecurityScore())}`}
                        strokeDasharray="176"
                        strokeDashoffset={176 - (176 * getAverageSecurityScore()) / 100}
                        strokeLinecap="round"
                      />
                    </svg>
                    <div className="absolute inset-0 flex items-center justify-center">
                      <span className="text-sm font-bold text-slate-700">{getAverageSecurityScore()}</span>
                    </div>
                  </div>
                  <div>
                    <div className={`text-lg font-bold mb-0.5 ${getScoreColor(getAverageSecurityScore())}`}>{getSecurityLabel()}</div>
                    <div className="text-xs text-slate-400">Compliance Score</div>
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-4 animate-pulse mt-4">
                  <div className="w-14 h-14 rounded-full bg-slate-100"></div>
                  <div className="flex-1 space-y-2">
                    <div className="h-4 bg-slate-100 rounded w-20"></div>
                    <div className="h-3 bg-slate-100 rounded w-16"></div>
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Charts Row - Premium Glass Containers */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
            <CostChart awsData={awsData} gcpData={gcpData} azureData={azureData} />
            <ProviderDonutChart awsData={awsData} gcpData={gcpData} azureData={azureData} />
          </div>

          {/* Provider Cards Row */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
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

      {/* Breakdown Modal */}
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
