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
import { useNavigate, Link } from 'react-router-dom';
import { DashboardHeader } from '../components/Dashboard/DashboardHeader';
import { MetricCard } from '../components/Dashboard/MetricCard';
import { ProviderCard } from '../components/Dashboard/ProviderCard';
import { CostChart } from '../components/Dashboard/CostChart';
import { ProviderDonutChart } from '../components/Dashboard/ProviderDonutChart';
import { TicketStatusDonutChart } from '../components/Dashboard/TicketStatusDonutChart';
import { AccountBreakdownModal } from '../components/Dashboard/AccountBreakdownModal';
import { useUnifiedDashboard } from '../hooks/useUnifiedDashboard';
import { useWebSocket } from '../hooks/useWebSocket';
import { useAccounts } from '../contexts/AccountContext';
import type { SubAccount, ViewMode } from '../types/dashboard';

export const UnifiedDashboard: React.FC = () => {
  const { accounts: userAccounts } = useAccounts();
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
    mergeProviderAccounts,
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
    const awsOpt = awsData?.selectedAccount?.optimizationSummary?.totalPotentialSavings || 0;
    const gcpOpt = gcpData?.selectedAccount?.optimizationSummary?.totalPotentialSavings || 0;
    const azureOpt = azureData?.selectedAccount?.optimizationSummary?.totalPotentialSavings || 0;
    return awsOpt + gcpOpt + azureOpt;
  };

  const getTotalWasteCount = () => {
    const all = getAllAccounts();
    return all.reduce((sum, a) => sum + (a.wastedResourceCount ?? 0), 0);
  };

  const getTotalAlertsCount = () => {
    const all = getAllAccounts();
    return all.reduce((sum, a) => sum + (a.activeAlertCount ?? 0), 0);
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

  const getSecurityColor = () => {
    const score = getAverageSecurityScore();
    if (score >= 80) return '#059669';
    if (score >= 50) return '#d97706';
    return '#dc2626';
  };

  // Modal handlers
  const openBreakdown = (provider: string, providerData: typeof awsData) => {
    setViewMode('cost');
    setActiveProvider(provider);
    const merged = mergeProviderAccounts(providerData, provider as 'AWS' | 'GCP' | 'Azure');
    const sortedList = JSON.parse(JSON.stringify(merged)).sort(
      (a: SubAccount, b: SubAccount) => b.monthToDateSpend - a.monthToDateSpend
    );
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
    const accounts = getAllAccounts().sort((a, b) =>
      (b.wastedResourceCount ?? 0) - (a.wastedResourceCount ?? 0)
    );
    setActiveAccountList(accounts);
    setShowModal(true);
  };

  const openAlertsBreakdown = () => {
    setViewMode('alerts');
    setActiveProvider('All');
    const accounts = getAllAccounts().sort((a, b) =>
      (b.activeAlertCount ?? 0) - (a.activeAlertCount ?? 0)
    );
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

    // Only select accounts the current user actually has access to.
    // Cross-tenant accounts appear in the unified snapshot but are not in the
    // user's account list — selecting them sets an inaccessible ID in
    // sessionStorage, leaving the sidebar and destination page in a broken state.
    const accessible = userAccounts.find(a => a.id === accountId);
    if (!accessible) {
      setNotification({
        type: 'error',
        title: 'Account Not Accessible',
        message: 'This account is not available in your current session.',
      });
      setTimeout(() => setNotification(null), 4000);
      return;
    }

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
  }, [setShowModal, activeProvider, navigate, getAllAccounts, awsData, gcpData, azureData, userAccounts, setNotification]);

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
    <div className="flex-1 flex flex-col min-h-screen bg-white font-sans text-slate-800">
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

        {/* Header - Actions registered via TopbarContext */}
        <DashboardHeader
          lastUpdatedText={lastUpdatedText}
          serverTime={serverTime}
          isLoading={Object.values(isLoading).some(Boolean)}
          onRefresh={handleRefresh}
        />

        {/* Main Content */}
        <main className="flex-1 p-8 space-y-8">

          {/* Development Warning - Refined */}
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
              <Link
                to="/account-manager"
                className="px-8 py-3 bg-blue-600 hover:bg-blue-700 text-white font-semibold rounded-lg shadow-lg hover:shadow-blue-500/30 transition-all duration-300 transform hover:-translate-y-1"
              >
                Add Cloud Account
              </Link>
            </div>
          )}

          {/* Key Metrics Grid - Premium Cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6 items-start">
            <MetricCard
              title="Total MTD Cost"
              value={formatCurrency(getTotalCost())}
              subtitle={`Forecast: ${formatCurrency(getTotalForecast())}`}
              icon="fas fa-dollar-sign text-[rgba(0,0,0,0.45)]"
              isLoading={isLoading.summary}
            />

            <MetricCard
              title="Budget Health"
              value={`${Math.round(getBudgetPercent())}%`}
              subtitle={getBudgetPercent() > 100 ? 'Over budget' : 'Within monthly limits'}
              icon="fas fa-chart-pie"
              isLoading={isLoading.summary}
              progress={getBudgetPercent() / 100}
              valueColor={
                getBudgetPercent() > 100 ? '#dc2626'
                  : getBudgetPercent() > 90 ? '#d97706'
                  : '#059669'
              }
            />

            <MetricCard
              title="Active Resources"
              value={formatNumber(summaryData.totalResources)}
              subtitle={`${summaryData.totalAccounts} connected accounts`}
              icon="fas fa-server text-[rgba(0,0,0,0.45)]"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openResourceBreakdown}
            />

            <MetricCard
              title="Potential Savings"
              value={formatCurrency(getTotalSavings())}
              subtitle="Optimization opportunities"
              icon="fas fa-coins"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openSavingsBreakdown}
              valueColor={getTotalSavings() > 0 ? '#059669' : 'rgb(10, 10, 10)'}
              onClick={() => navigate('/waste')}
            />
          </div>

          {/* Operational Health Row */}
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-6 items-start">
            <MetricCard
              title="Wasted Resources"
              value={getTotalWasteCount()}
              subtitle="Idle or Unused Assets"
              icon="fas fa-trash"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openWasteBreakdown}
              valueColor={getTotalWasteCount() > 0 ? '#d97706' : 'rgb(10, 10, 10)'}
            />

            <MetricCard
              title="Critical Alerts"
              value={getTotalAlertsCount()}
              subtitle="Requires Attention"
              icon="fas fa-bell"
              isLoading={isLoading.summary}
              showViewButton={true}
              onViewClick={openAlertsBreakdown}
              valueColor={getTotalAlertsCount() > 0 ? '#dc2626' : 'rgb(10, 10, 10)'}
            />

            {/* Active Support Tickets Card */}
            <div
              className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden group card-hover-lift cursor-pointer"
              onClick={() => navigate('/tickets')}
            >
              <div className="absolute top-4 right-4">
                <div className="w-6 h-6 bg-[rgba(0,0,0,0.04)] rounded-md flex items-center justify-center text-[rgba(0,0,0,0.45)]">
                  <i className="fas fa-ticket-alt text-[10px]"></i>
                </div>
              </div>
              <div className="pr-8">
                <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Active Tickets</p>
                <p className="leading-8 mb-1">
                  <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px', color: 'rgb(10, 10, 10)', fontVariantNumeric: 'tabular-nums' }}>
                    {isLoading.tickets ? '–' : ticketCount}
                  </span>
                </p>
                <p className="text-[11px] text-[rgba(0,0,0,0.35)] font-medium">Technical Support cases</p>
              </div>
            </div>

            {/* Security Posture Card */}
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden group card-hover-lift">
              <div className="absolute top-4 right-4">
                <div
                  className="w-6 h-6 rounded-md flex items-center justify-center"
                  style={{ backgroundColor: `${getSecurityColor()}1f`, color: getSecurityColor() }}
                >
                  <i className="fas fa-shield-alt text-[10px]"></i>
                </div>
              </div>
              {!isLoading.summary ? (
                <div className="fade-in pr-8">
                  <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">Security Posture</p>
                  <p className="leading-8 mb-1 flex items-baseline gap-0.5">
                    <span style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px', color: getSecurityColor(), fontVariantNumeric: 'tabular-nums' }}>
                      {getAverageSecurityScore()}
                    </span>
                    <span style={{ fontFamily: "'Inter', sans-serif", fontSize: '13px', fontWeight: 400, color: 'rgba(0,0,0,0.3)' }}>/100</span>
                  </p>
                  <p className="text-[11px] font-medium" style={{ color: getSecurityColor() }}>
                    {getSecurityLabel()} <span className="text-[rgba(0,0,0,0.35)]">· Compliance Score</span>
                  </p>
                </div>
              ) : (
                <div className="animate-pulse pr-8 space-y-3 pt-0.5">
                  <div className="h-2.5 w-24 bg-[rgba(0,0,0,0.06)] shimmer rounded"></div>
                  <div className="h-8 w-20 bg-[rgba(0,0,0,0.06)] shimmer rounded"></div>
                  <div className="h-2 w-16 bg-[rgba(0,0,0,0.04)] shimmer rounded"></div>
                </div>
              )}
            </div>
          </div>

          {/* Charts Row - Premium Glass Containers */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
            <CostChart awsData={awsData} gcpData={gcpData} azureData={azureData} />
            <ProviderDonutChart awsData={awsData} gcpData={gcpData} azureData={azureData} />
          </div>

          {/* Support Tickets Row */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
            <TicketStatusDonutChart />
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
              onViewBreakdown={() => openBreakdown('AWS', awsData)}
            />
            <ProviderCard
              provider="GCP"
              data={gcpData}
              isLoading={isLoading.gcp}
              error={error.gcp}
              accountCount={summaryData.gcpCount}
              onRetry={() => fetchProvider('gcp', true)}
              onViewBreakdown={() => openBreakdown('GCP', gcpData)}
            />
            <ProviderCard
              provider="Azure"
              data={azureData}
              isLoading={isLoading.azure}
              error={error.azure}
              accountCount={summaryData.azureCount}
              onRetry={() => fetchProvider('azure', true)}
              onViewBreakdown={() => openBreakdown('Azure', azureData)}
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
