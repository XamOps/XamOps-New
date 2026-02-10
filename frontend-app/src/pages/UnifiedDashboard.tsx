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

import React, { useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Ticker } from '../components/Dashboard/Ticker';
import { DashboardHeader } from '../components/Dashboard/DashboardHeader';
import { MetricCard } from '../components/Dashboard/MetricCard';
import { ProviderCard } from '../components/Dashboard/ProviderCard';
import { CostChart } from '../components/Dashboard/CostChart';
import { ProviderDonutChart } from '../components/Dashboard/ProviderDonutChart';
import { AccountBreakdownModal } from '../components/Dashboard/AccountBreakdownModal';
import { useUnifiedDashboard } from '../hooks/useUnifiedDashboard';
import { useWebSocket } from '../hooks/useWebSocket';
import type { SubAccount, ViewMode } from '../types/dashboard';

export const UnifiedDashboard: React.FC = () => {
  const {
    isLoading,
    error,
    summaryData,
    awsData,
    gcpData,
    azureData,
    ticketCount,
    lastUpdatedText,
    serverTime,
    fetchAll,
    fetchProvider,
    updateResourceInventory,
    showModal,
    setShowModal,
    viewMode,
    setViewMode,
    activeProvider,
    setActiveProvider,
    activeAccountList,
    setActiveAccountList,
    getAllAccounts,
    notification,
    setNotification,
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

  // Navigation handlers
  const navigate = useNavigate();

  const handleRefresh = () => {
    fetchAll(true);
  };

  // Modal handlers - Select account and navigate to provider dashboard
  const handleSelectAccount = useCallback((accountId: string, mode: ViewMode) => {
    console.log(`Selected account ${accountId} in ${mode} mode, provider: ${activeProvider}`);
    setShowModal(false);

    // Update session storage with selected account
    sessionStorage.setItem('selectedAccountIds', JSON.stringify([accountId]));

    // Dispatch account-changed event
    globalThis.dispatchEvent(new CustomEvent('account-changed', {
      detail: { accountId, accountIds: [accountId] }
    }));

    // Determine target dashboard based on activeProvider from the modal
    let targetPath = '/dashboard';
    let provider = activeProvider;

    // If provider is "All" (e.g. from Eye Buttons/Active Resources), we need to find the specific provider for this account
    if (provider === 'All') {
      const account = getAllAccounts().find(a => a.id === accountId);
      if (account) {
        // Infer provider from account type/structure or parent context if available.
        // Since we flat-mapped, we might check properties. 
        // A robust way in XamOps is usually checking which list it came from, 
        // but here we have the object. 
        // Let's rely on the fact that AWS accounts usually have 'aws' specific fields or just check the ID format if distinct,
        // OR better: check against the loaded provider data.
        if (awsData?.selectedAccount?.subAccounts?.some(a => a.id === accountId)) provider = 'AWS';
        else if (gcpData?.selectedAccount?.subAccounts?.some(a => a.id === accountId)) provider = 'GCP';
        else if (azureData?.selectedAccount?.subAccounts?.some(a => a.id === accountId)) provider = 'Azure';
      }
    }

    // Routing Logic based on Provider and Mode
    if (provider === 'GCP') {
      switch (mode) {
        case 'resource': targetPath = '/gcp-cloudlist'; break; // Corrected: CloudList for Inventory
        case 'waste': targetPath = '/gcp-waste'; break;
        case 'alerts': targetPath = '/gcp-alerts'; break;
        case 'savings': targetPath = '/gcp-rightsizing'; break;
        default: targetPath = '/gcp-dashboard';
      }
    } else if (provider === 'Azure') {
      switch (mode) {
        case 'resource': targetPath = '/azure-cloudlist'; break;
        case 'waste': targetPath = '/azure-finops'; break; // Azure FinOps covers waste/savings
        case 'savings': targetPath = '/azure-finops'; break;
        case 'alerts': targetPath = '/azure-dashboard'; break; // Alerts on main dashboard
        default: targetPath = '/azure-dashboard';
      }
    } else {
      // AWS (Default)
      switch (mode) {
        case 'resource': targetPath = '/cloudlist'; break; // Corrected: CloudList for Inventory
        case 'waste': targetPath = '/waste'; break;
        case 'alerts': targetPath = '/alerts'; break;
        case 'savings': targetPath = '/rightsizing'; break;
        default: targetPath = '/dashboard';
      }
    }

    // Build URL with appropriate query parameters
    const urlParams = new URLSearchParams();
    // Clear existing to be safe

    if (provider === 'GCP') {
      urlParams.set('projectId', accountId);
    } else if (provider === 'Azure') {
      urlParams.set('subscriptionId', accountId);
    } else {
      urlParams.set('accountId', accountId);
    }

    navigate(`${targetPath}?${urlParams.toString()}`);
  }, [setShowModal, activeProvider, navigate, getAllAccounts, awsData, gcpData, azureData]);

  // Listen for account selection changes (for soft navigation)
  useEffect(() => {
    const handleAccountChanged = () => {
      console.log('🔄 Account changed, reloading unified dashboard...');
      fetchAll(true);
    };

    const handleMultiAccountChanged = () => {
      console.log('🔄 Multi-account selection changed, reloading unified dashboard...');
      fetchAll(true);
    };

    globalThis.addEventListener('account-changed', handleAccountChanged);
    globalThis.addEventListener('multi-account-selection-changed', handleMultiAccountChanged);

    return () => {
      globalThis.removeEventListener('account-changed', handleAccountChanged);
      globalThis.removeEventListener('multi-account-selection-changed', handleMultiAccountChanged);
    };
  }, [fetchAll]);

  return (
    <div className="flex-1 flex flex-col min-h-screen bg-slate-50 font-sans text-slate-800">
      <div id="unified-dashboard-container" className="flex flex-col w-full">
        {/* Notification Toast */}
        {notification && (
          <div className={`fixed top-4 right-4 z-[9999] px-6 py-4 rounded-xl shadow-2xl border flex items-center gap-4 animate-in slide-in-from-right-8 duration-300 ${notification.type === 'error' ? 'bg-rose-50 border-rose-200 text-rose-800' : 'bg-emerald-50 border-emerald-200 text-emerald-800'
            }`}>
            <div className={`p-2 rounded-lg ${notification.type === 'error' ? 'bg-rose-100 text-rose-600' : 'bg-emerald-100 text-emerald-600'}`}>
              <i className={`fas ${notification.type === 'error' ? 'fa-exclamation-circle' : 'fa-check-circle'} text-lg`}></i>
            </div>
            <div>
              <h4 className="font-bold text-sm">{notification.title}</h4>
              <p className="text-xs opacity-80">{notification.message}</p>
            </div>
            <button onClick={() => setNotification(null)} className="ml-2 hover:opacity-60 transition-opacity">
              <i className="fas fa-times text-sm"></i>
            </button>
          </div>
        )}

        {/* Ticker - Now glass effect */}
        <Ticker />

        {/* Header - Actions registered via TopbarContext */}
        <DashboardHeader
          lastUpdatedText={lastUpdatedText}
          serverTime={serverTime}
          isLoading={isLoading.summary}
          onRefresh={handleRefresh}
        />

        {/* Main Content */}
        <main className="flex-1 p-8 space-y-8">

          {/* Development Warning - Refined */}
          <div className="bg-amber-50 border border-amber-200/60 p-4 rounded-xl shadow-sm flex items-start gap-4 animate-in fade-in slide-in-from-top-4 duration-500">
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

            {/* Active Support Tickets Card */}
            <div
              className="glass-card p-6 flex flex-col justify-between group hover:shadow-lg transition-all duration-300 border-l-4 border-indigo-500 cursor-pointer"
              onClick={() => navigate('/tickets')}
            >
              <div className="flex justify-between items-start mb-4">
                <div>
                  <h3 className="text-slate-500 text-xs font-bold uppercase tracking-wider mb-1">Active Tickets</h3>
                  <div className="text-3xl font-black text-slate-800">
                    {isLoading.tickets ? '...' : ticketCount}
                  </div>
                </div>
                <div className="w-10 h-10 rounded-lg bg-indigo-50 flex items-center justify-center text-indigo-600 transition-transform group-hover:scale-110">
                  <i className="fas fa-ticket-alt"></i>
                </div>
              </div>
              {!isLoading.tickets ? (
                <div className="fade-in">
                  <p className="text-xs text-slate-500 flex items-center gap-1.5 font-medium">
                    <i className="fas fa-headset text-indigo-500"></i>
                    <span>Technical Support cases</span>
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

      {/* WebSocket Subscribers for Real-time Inventory Updates */}
      <div className="hidden">
        {getAllAccounts().map(account => (
          <InventorySubscriber
            key={account.id}
            accountId={account.id}
            onUpdate={(inventory) => updateResourceInventory(account.id, inventory)}
          />
        ))}
      </div>

      {/* Account Breakdown Modal */}
      <AccountBreakdownModal
        show={showModal}
        onClose={() => setShowModal(false)}
        provider={activeProvider}
        viewMode={viewMode}
        accountList={activeAccountList}
        onSelectAccount={handleSelectAccount}
      />
    </div>
  );
};

/**
 * Helper component to manage individual WebSocket subscriptions
 */
const InventorySubscriber: React.FC<{
  accountId: string;
  onUpdate: (inventory: any) => void;
}> = ({ accountId, onUpdate }) => {
  useWebSocket({
    accountId,
    onInventoryUpdate: onUpdate,
    enabled: true
  });
  return null;
};
