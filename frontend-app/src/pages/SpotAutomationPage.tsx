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
import { useAccounts } from '../contexts/AccountContext';
import { useSpotAutomationData } from '../hooks/useSpotAutomationData';
import SpotAutomationHeader from '../components/SpotAutomation/SpotAutomationHeader';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import SpotAutomationASGTable from '../components/SpotAutomation/SpotAutomationASGTable';
import DeploymentModal from '../components/SpotAutomation/DeploymentModal';
import SyncAccountModal from '../components/SpotAutomation/SyncAccountModal';
// Coming Soon mode - these components are not used yet
// import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
// import LaunchAnalytics from '../components/LaunchAnalytics/LaunchAnalytics';
// import RispCoverage from '../components/RispCoverage/RispCoverage';

const SpotAutomationPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    asgs,
    costs,
    savings,
    historyData,
    historySummary,
    toggleASG,
    generateDeploymentUrl,
    syncAccount,
  } = useSpotAutomationData({ selectedAccountId });

  // Modal states
  const [isDeploymentModalOpen, setIsDeploymentModalOpen] = useState(false);
  const [isSyncAccountModalOpen, setIsSyncAccountModalOpen] = useState(false);
  const [historyTimeRange, setHistoryTimeRange] = useState<'last24h' | 'last7d' | 'last30d'>('last7d');

  // Tab state
  const [activeTab, setActiveTab] = useState<'dashboard' | 'analytics' | 'risp-coverage' | 'history'>('dashboard');

  // Toast notification state
  const [toast, setToast] = useState<{ show: boolean; message: string; type: 'success' | 'error' }>({
    show: false,
    message: '',
    type: 'success',
  });

  const showToast = (message: string, type: 'success' | 'error' = 'success') => {
    setToast({ show: true, message, type });
    setTimeout(() => setToast({ show: false, message: '', type: 'success' }), 4000);
  };

  const handleToggleASG = async (asg: any) => {
    try {
      await toggleASG(asg);
      showToast(
        `AutoSpotting ${!asg.isEnabled ? 'enabled' : 'disabled'} for ${asg.name}`,
        'success'
      );
    } catch (err) {
      showToast(
        `Failed to toggle ${asg.name}: ${err instanceof Error ? err.message : 'Unknown error'}`,
        'error'
      );
    }
  };

  const handleSyncAccount = async (accountId: string, accountName: string, regions: string) => {
    try {
      await syncAccount(accountId, accountName, regions);
      showToast(`Account ${accountId} synced successfully!`, 'success');
    } catch (err) {
      throw err; // Let the modal handle the error
    }
  };

  const handleTimeRangeChange = (range: 'last24h' | 'last7d' | 'last30d') => {
    setHistoryTimeRange(range);
    // Demo mode - no API call needed
  };

  // Demo data for Coming Soon preview - matches type interfaces
  const demoAsgs: typeof asgs = [
    { name: 'web-app-asg', region: 'us-east-1', isEnabled: true, instanceCount: 5, spotCount: 4, onDemandCount: 1, instanceTypes: 't3.medium, t3.large', currentCost: 0.42, onDemandCost: 0.85, savings: 0.43, potentialSavings: 0.50 },
    { name: 'api-server-asg', region: 'us-west-2', isEnabled: true, instanceCount: 3, spotCount: 3, onDemandCount: 0, instanceTypes: 'm5.large', currentCost: 0.28, onDemandCost: 0.72, savings: 0.44, potentialSavings: 0.44 },
    { name: 'worker-asg', region: 'eu-west-1', isEnabled: false, instanceCount: 8, spotCount: 0, onDemandCount: 8, instanceTypes: 'c5.xlarge', currentCost: 1.36, onDemandCost: 1.36, savings: 0, potentialSavings: 0.95 },
  ];
  const demoCosts: typeof costs = { currentHourly: 2.06, currentMonthlyCost: 1503.80, onDemandHourly: 2.93, hourlyActualSavings: 0.87 };
  const demoSavings: typeof savings = { actualMonthlySavings: 635.10, potentialMonthlySavings: 1378.85 };
  const demoHistoryData: typeof historyData = [
    { timestamp: '2024-01-01T00:00:00Z', total_actual_savings: 120, total_potential_savings: 180 },
    { timestamp: '2024-01-02T00:00:00Z', total_actual_savings: 145, total_potential_savings: 195 },
    { timestamp: '2024-01-03T00:00:00Z', total_actual_savings: 130, total_potential_savings: 185 },
    { timestamp: '2024-01-04T00:00:00Z', total_actual_savings: 165, total_potential_savings: 210 },
    { timestamp: '2024-01-05T00:00:00Z', total_actual_savings: 180, total_potential_savings: 225 },
    { timestamp: '2024-01-06T00:00:00Z', total_actual_savings: 155, total_potential_savings: 200 },
    { timestamp: '2024-01-07T00:00:00Z', total_actual_savings: 190, total_potential_savings: 240 },
  ];
  const demoHistorySummary: typeof historySummary = { total_savings: 1085, average_hourly_savings: 155, peak_savings: 190 };

  // Use demo data for Coming Soon mode
  const displayAsgs = demoAsgs;
  const displayCosts = demoCosts;
  const displaySavings = demoSavings;
  const displayHistoryData = demoHistoryData;
  const displayHistorySummary = demoHistorySummary;
  const displayEnabledRegionsCount = 3;
  const displayTotalInstances = 16;

  const enabledASGsCount = displayAsgs.filter((a: any) => a.isEnabled).length;

  return (
    <div className="flex-1 flex flex-col min-h-screen bg-gray-50 relative">
      {/* Coming Soon Banner */}
      <div className="bg-gradient-to-r from-indigo-600 via-purple-600 to-indigo-600 text-white py-3 px-8 flex items-center justify-center gap-3 shadow-lg">
        <div className="flex items-center gap-2 bg-white/20 px-3 py-1 rounded-full">
          <i className="fas fa-rocket text-yellow-300"></i>
          <span className="font-bold text-sm uppercase tracking-wide">Coming Soon</span>
        </div>
        <span className="text-sm">
          Spot Automation is under development. Preview the features below!
        </span>
      </div>

      {/* Header */}
      <SpotAutomationHeader
        onSyncAccount={() => {}}
        onDeployToAWS={() => {}}
        disabled={true}
      />

      {/* Tab Navigation */}
      <div className="bg-white border-b border-gray-200 px-8">
        <nav className="flex space-x-8" aria-label="Tabs">
          <button
            onClick={() => setActiveTab('dashboard')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
              activeTab === 'dashboard'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            <i className="fas fa-tachometer-alt mr-2"></i>
            Dashboard
          </button>
          <button
            onClick={() => setActiveTab('analytics')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
              activeTab === 'analytics'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            <i className="fas fa-chart-line mr-2"></i>
            Launch Analytics
          </button>
          <button
            onClick={() => setActiveTab('risp-coverage')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
              activeTab === 'risp-coverage'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            <i className="fas fa-chart-pie mr-2"></i>
            RI/SP Coverage
          </button>
          <button
            onClick={() => setActiveTab('history')}
            className={`py-4 px-1 border-b-2 font-medium text-sm transition-colors ${
              activeTab === 'history'
                ? 'border-blue-500 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
            }`}
          >
            <i className="fas fa-history mr-2"></i>
            Actions History
          </button>
        </nav>
      </div>

      {/* Main Content with Blur Overlay */}
      <div className="flex-1 p-8 relative">
        {/* Blurred Content */}
        <div className="filter blur-[2px] pointer-events-none select-none">
          {/* Dashboard Tab Content - Always show with demo data in Coming Soon mode */}
          {activeTab === 'dashboard' && (
            <div className="space-y-8">
              {/* KPIs */}
              <SpotAutomationKPIs
                costs={displayCosts}
                savings={displaySavings}
                enabledASGsCount={enabledASGsCount}
                totalASGsCount={displayAsgs.length}
                enabledRegionsCount={displayEnabledRegionsCount}
              />

              {/* Savings Chart */}
              <SpotAutomationChart
                historyData={displayHistoryData}
                historySummary={displayHistorySummary}
                timeRange={historyTimeRange}
                isLoading={false}
                onTimeRangeChange={handleTimeRangeChange}
                onRefresh={() => {}}
              />

              {/* ASG Table */}
              <SpotAutomationASGTable
                asgs={displayAsgs}
                costs={displayCosts}
                totalInstances={displayTotalInstances}
                isLoading={false}
                onToggleASG={handleToggleASG}
                onRefresh={() => {}}
              />
            </div>
          )}

          {/* Launch Analytics Tab Content */}
          {activeTab === 'analytics' && (
            <div className="bg-white rounded-2xl shadow-lg border border-gray-200 p-16 text-center">
              <div className="w-20 h-20 bg-gradient-to-br from-blue-100 to-indigo-200 rounded-full flex items-center justify-center mx-auto mb-6">
                <i className="fas fa-chart-line text-indigo-500 text-3xl"></i>
              </div>
              <h3 className="text-2xl font-bold text-gray-800 mb-3">Launch Analytics</h3>
              <p className="text-gray-500 max-w-md mx-auto">
                Track instance launches, spot interruptions, and capacity trends across your Auto Scaling Groups.
              </p>
            </div>
          )}

          {/* RI/SP Coverage Tab Content */}
          {activeTab === 'risp-coverage' && (
            <div className="bg-white rounded-2xl shadow-lg border border-gray-200 p-16 text-center">
              <div className="w-20 h-20 bg-gradient-to-br from-green-100 to-emerald-200 rounded-full flex items-center justify-center mx-auto mb-6">
                <i className="fas fa-chart-pie text-emerald-500 text-3xl"></i>
              </div>
              <h3 className="text-2xl font-bold text-gray-800 mb-3">RI/SP Coverage</h3>
              <p className="text-gray-500 max-w-md mx-auto">
                Monitor Reserved Instance and Savings Plan coverage to optimize your commitment-based discounts.
              </p>
            </div>
          )}

          {/* Actions History Tab Content */}
          {activeTab === 'history' && (
            <div className="bg-white rounded-2xl shadow-lg border border-gray-200 p-16 text-center">
              <div className="w-20 h-20 bg-gradient-to-br from-purple-100 to-violet-200 rounded-full flex items-center justify-center mx-auto mb-6">
                <i className="fas fa-history text-violet-500 text-3xl"></i>
              </div>
              <h3 className="text-2xl font-bold text-gray-800 mb-3">Actions History</h3>
              <p className="text-gray-500 max-w-md mx-auto">
                View detailed logs of all spot automation actions, including enables, disables, and instance replacements.
              </p>
            </div>
          )}
        </div>

        {/* Coming Soon Overlay - Fixed position for true centering */}
        <div className="fixed inset-0 flex items-center justify-center z-50 pointer-events-none" style={{ top: '180px' }}>
          <div className="bg-white/95 backdrop-blur-sm rounded-3xl shadow-2xl p-12 text-center max-w-lg mx-4 border border-gray-200 pointer-events-auto">
            <div className="w-24 h-24 bg-gradient-to-br from-indigo-500 to-purple-600 rounded-full flex items-center justify-center mx-auto mb-6 shadow-lg">
              <i className="fas fa-rocket text-white text-4xl"></i>
            </div>
            <h2 className="text-3xl font-bold text-gray-800 mb-3">Coming Soon</h2>
            <p className="text-gray-600 text-lg mb-6">
              Spot Automation is currently under development. We're working hard to bring you powerful cost-saving features.
            </p>
            <div className="flex items-center justify-center gap-4 text-sm text-gray-500">
              <div className="flex items-center gap-2">
                <i className="fas fa-check-circle text-green-500"></i>
                <span>Auto Scaling</span>
              </div>
              <div className="flex items-center gap-2">
                <i className="fas fa-check-circle text-green-500"></i>
                <span>Cost Savings</span>
              </div>
              <div className="flex items-center gap-2">
                <i className="fas fa-check-circle text-green-500"></i>
                <span>Analytics</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Modals */}
      <DeploymentModal
        isOpen={isDeploymentModalOpen}
        onClose={() => setIsDeploymentModalOpen(false)}
        onGenerateUrl={generateDeploymentUrl}
      />

      <SyncAccountModal
        isOpen={isSyncAccountModalOpen}
        onClose={() => setIsSyncAccountModalOpen(false)}
        onSync={handleSyncAccount}
      />

      {/* Toast Notification */}
      {toast.show && (
        <div className="fixed bottom-8 right-8 px-6 py-4 rounded-xl shadow-2xl text-white text-sm font-semibold z-50 flex items-center max-w-md animate-slide-up"
          style={{ backgroundColor: toast.type === 'success' ? '#10b981' : '#ef4444' }}
        >
          <i className={`fas ${toast.type === 'success' ? 'fa-check-circle' : 'fa-exclamation-circle'} mr-3 text-xl`}></i>
          <span>{toast.message}</span>
        </div>
      )}
    </div>
  );
};

export default SpotAutomationPage;
