/**
 * Spot Automation Page
 *
 * Converted from: spot-automation.html
 *
 * This page provides automated Spot instance management and cost optimization.
 *
 * Features:
 * - Real-time cost tracking and savings calculations
 * - Auto Scaling Group (ASG) management with enable/disable toggles
 * - Savings history chart with multiple time ranges
 * - CloudFormation deployment to AWS
 * - Account syncing for multi-account support
 * - Comprehensive cost breakdown by ASG
 *
 * API Endpoints:
 * - GET /api/autospotting/costs/${accountId} - Fetch cost data
 * - GET /api/autospotting/costs/history/${accountId} - Fetch history data
 * - POST /api/autospotting/asgs/${accountId}/enable - Enable ASG
 * - POST /api/autospotting/asgs/${accountId}/disable - Disable ASG
 * - GET /api/cloudformation/deploy-url - Generate deployment URL
 * - POST /api/cloudformation/sync-account - Sync account
 */

import React, { useState } from 'react';
import { gooeyToast } from 'goey-toast';
import { useAccounts } from '../contexts/AccountContext';
import { useSpotAutomationData } from '../hooks/useSpotAutomationData';
import SpotAutomationHeader from '../components/SpotAutomation/SpotAutomationHeader';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import SpotAutomationASGTable from '../components/SpotAutomation/SpotAutomationASGTable';
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
import LaunchAnalytics from '../components/LaunchAnalytics/LaunchAnalytics';
import RispCoverage from '../components/RispCoverage/RispCoverage';
import { SpotAutomationSkeleton } from '../components/Shared/Skeleton';

const SpotAutomationPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    asgs,
    costs,
    savings,
    enabledRegionsCount,
    totalInstances,
    historyData,
    historySummary,
    isLoading,
    isLoadingHistory,
    toggleASG,
    syncASG,
    fetchCostData,
    fetchHistoryData,
  } = useSpotAutomationData({ selectedAccountId });

  // Modal states
  const [historyTimeRange, setHistoryTimeRange] = useState<'last24h' | 'last7d' | 'last30d'>('last7d');

  // Tab state
  const [activeTab, setActiveTab] = useState<'dashboard' | 'analytics' | 'history' | 'risp-coverage'>('dashboard');

  const handleToggleASG = async (asg: any) => {
    try {
      await toggleASG(asg);
      gooeyToast.success(`AutoSpotting ${!asg.isEnabled ? 'enabled' : 'disabled'} for ${asg.name}`);
    } catch (err) {
      gooeyToast.error(`Failed to toggle ${asg.name}`, {
        description: err instanceof Error ? err.message : 'Unknown error',
      });
    }
  };

  const handleTimeRangeChange = (range: 'last24h' | 'last7d' | 'last30d') => {
    setHistoryTimeRange(range);
    fetchHistoryData(range);
  };

  const enabledASGsCount = asgs.filter((a: any) => a.isEnabled).length;

  return (
    <div className="flex-1 flex flex-col min-h-screen bg-gray-50 relative">
      {/* Header */}
      <SpotAutomationHeader />

      {/* Tab Navigation */}
      <div className="bg-white border-b border-gray-200 px-8">
        <nav className="flex space-x-8" aria-label="Tabs">
          <button
            onClick={() => setActiveTab('dashboard')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'dashboard'
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
          >
            <i className="fas fa-tachometer-alt mr-2"></i>
            Dashboard
          </button>
          <button
            onClick={() => setActiveTab('analytics')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'analytics'
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
          >
            <i className="fas fa-chart-line mr-2"></i>
            Launch Analytics
          </button>
          <button
            onClick={() => setActiveTab('history')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'history'
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
          >
            <i className="fas fa-history mr-2"></i>
            Actions History
          </button>
          <button
            onClick={() => setActiveTab('risp-coverage')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${activeTab === 'risp-coverage'
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
          >
            <i className="fas fa-chart-pie mr-2"></i>
            RI/SP Coverage
          </button>
        </nav>
      </div>

      {/* Main Content */}
      <div className="flex-1 p-8">
        {/* Dashboard Tab */}
        {activeTab === 'dashboard' && (
          isLoading && asgs.length === 0 ? (
            <SpotAutomationSkeleton />
          ) : (
            <div className="space-y-8">
              <SpotAutomationKPIs
                costs={costs}
                savings={savings}
                enabledASGsCount={enabledASGsCount}
                totalASGsCount={asgs.length}
                enabledRegionsCount={enabledRegionsCount}
              />

              <SpotAutomationChart
                historyData={historyData}
                historySummary={historySummary}
                timeRange={historyTimeRange}
                isLoading={isLoadingHistory}
                onTimeRangeChange={handleTimeRangeChange}
                onRefresh={() => fetchHistoryData(historyTimeRange)}
              />

              <SpotAutomationASGTable
                asgs={asgs}
                costs={costs}
                totalInstances={totalInstances}
                isLoading={isLoading}
                selectedAccountId={selectedAccountId}
                onToggleASG={handleToggleASG}
                onSyncASG={syncASG}
                onRefresh={() => fetchCostData(true)}
              />
            </div>
          )
        )}

        {/* Launch Analytics Tab */}
        {activeTab === 'analytics' && (
          <LaunchAnalytics accountId={selectedAccountId || ''} />
        )}

        {/* Actions History Tab */}
        {activeTab === 'history' && (
          <ActionsHistory accountId={selectedAccountId} />
        )}
        {/* RI/SP Coverage Tab */}
        {activeTab === 'risp-coverage' && (
          <RispCoverage accountId={selectedAccountId} />
        )}

      </div>

    </div>
  );
};

export default SpotAutomationPage;