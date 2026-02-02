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
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
import LaunchAnalytics from '../components/LaunchAnalytics/LaunchAnalytics';
import RispCoverage from '../components/RispCoverage/RispCoverage';

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
    isRefreshing,
    isLoadingHistory,
    error,
    fetchCostData,
    fetchHistoryData,
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
    fetchHistoryData(range);
  };

  // Error state
  if (error) {
    const isBackendNotImplemented = error.message.includes('not implemented') ||
                                     error.message.includes('not found') ||
                                     error.message.includes('HTML instead of JSON');

    return (
      <div className="flex-1 flex flex-col min-h-screen bg-gray-50 p-8">
        <div className="bg-white p-10 rounded-xl shadow-2xl max-w-2xl mx-auto">
          <div className="flex items-center justify-center w-20 h-20 mx-auto mb-6 bg-yellow-100 rounded-full">
            <i className={`fas ${isBackendNotImplemented ? 'fa-tools' : 'fa-exclamation-triangle'} text-yellow-600 text-3xl`}></i>
          </div>
          <h2 className="text-3xl font-bold text-center mb-4 text-gray-800">
            {isBackendNotImplemented ? 'Backend Not Configured' : 'Error Loading Data'}
          </h2>
          <p className="text-center text-gray-600 mb-6 text-lg">{error.message}</p>

          {isBackendNotImplemented && (
            <div className="bg-blue-50 border-2 border-blue-200 rounded-xl p-6 mb-6">
              <h3 className="font-bold text-blue-900 mb-3 flex items-center">
                <i className="fas fa-info-circle mr-2"></i>
                Backend Setup Required
              </h3>
              <p className="text-sm text-gray-700 mb-3">
                The Spot Automation feature requires the following backend API endpoints:
              </p>
              <ul className="text-sm text-gray-700 space-y-1 list-disc list-inside">
                <li><code className="bg-gray-200 px-2 py-1 rounded text-xs">/api/autospotting/costs/{'{accountId}'}</code></li>
                <li><code className="bg-gray-200 px-2 py-1 rounded text-xs">/api/autospotting/costs/history/{'{accountId}'}</code></li>
                <li><code className="bg-gray-200 px-2 py-1 rounded text-xs">/api/autospotting/asgs/{'{accountId}'}/enable</code></li>
                <li><code className="bg-gray-200 px-2 py-1 rounded text-xs">/api/autospotting/asgs/{'{accountId}'}/disable</code></li>
                <li><code className="bg-gray-200 px-2 py-1 rounded text-xs">/api/cloudformation/deploy-url</code></li>
                <li><code className="bg-gray-200 px-2 py-1 rounded text-xs">/api/cloudformation/sync-account</code></li>
              </ul>
              <p className="text-sm text-gray-700 mt-3">
                Please ensure the backend AutoSpotting service is running and properly configured.
              </p>
            </div>
          )}

          <div className="flex gap-3">
            <button
              onClick={() => window.history.back()}
              className="flex-1 bg-gray-200 text-gray-700 py-3 px-6 rounded-lg hover:bg-gray-300 transition-colors font-semibold"
            >
              Go Back
            </button>
            <button
              onClick={() => window.location.reload()}
              className="flex-1 bg-blue-600 text-white py-3 px-6 rounded-lg hover:bg-blue-700 transition-colors font-semibold"
            >
              Try Again
            </button>
          </div>
        </div>
      </div>
    );
  }

  const enabledASGsCount = asgs.filter((a) => a.isEnabled).length;

  return (
    <div className="flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header */}
      <SpotAutomationHeader
        onSyncAccount={() => setIsSyncAccountModalOpen(true)}
        onDeployToAWS={() => setIsDeploymentModalOpen(true)}
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

      {/* Main Content */}
      <div className="flex-1 p-8">
        {/* No Account Selected */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-20 rounded-2xl border-2 border-dashed border-gray-300 bg-white shadow-lg">
            <div className="w-24 h-24 bg-gradient-to-br from-blue-100 to-blue-200 rounded-full flex items-center justify-center mx-auto mb-6">
              <i className="fas fa-cloud text-blue-500 text-4xl"></i>
            </div>
            <h4 className="text-2xl font-bold text-gray-800 mb-3">No Account Selected</h4>
            <p className="text-gray-600 text-lg">
              Please select an account from the sidebar to view Spot Automation data.
            </p>
          </div>
        )}

        {/* Loading State - Initial */}
        {selectedAccountId && isLoading && (
          <div className="bg-white rounded-2xl shadow-lg border border-gray-200 p-20 text-center">
            <i className="fas fa-spinner fa-spin text-6xl text-blue-600 mb-6"></i>
            <p className="text-gray-700 font-bold text-xl">Loading AutoSpotting data...</p>
            <p className="text-sm text-gray-500 mt-3">Fetching cost data and ASG information</p>
          </div>
        )}

        {/* Main Content - Data Loaded */}
        {selectedAccountId && !isLoading && (
          <>
            {/* Dashboard Tab Content */}
            {activeTab === 'dashboard' && (
              <div className="space-y-8">
                {/* KPIs */}
                <SpotAutomationKPIs
                  costs={costs}
                  savings={savings}
                  enabledASGsCount={enabledASGsCount}
                  totalASGsCount={asgs.length}
                  enabledRegionsCount={enabledRegionsCount}
                />

                {/* Savings Chart */}
                <SpotAutomationChart
                  historyData={historyData}
                  historySummary={historySummary}
                  timeRange={historyTimeRange}
                  isLoading={isLoadingHistory}
                  onTimeRangeChange={handleTimeRangeChange}
                  onRefresh={() => fetchHistoryData(historyTimeRange)}
                />

                {/* ASG Table */}
                <SpotAutomationASGTable
                  asgs={asgs}
                  costs={costs}
                  totalInstances={totalInstances}
                  isLoading={isRefreshing}
                  onToggleASG={handleToggleASG}
                  onRefresh={() => fetchCostData(true)}
                />
              </div>
            )}

            {/* Launch Analytics Tab Content */}
            {activeTab === 'analytics' && (
              <LaunchAnalytics accountId={selectedAccountId} />
            )}

            {/* RI/SP Coverage Tab Content */}
            {activeTab === 'risp-coverage' && (
              <RispCoverage accountId={selectedAccountId} />
            )}

            {/* Actions History Tab Content */}
            {activeTab === 'history' && (
              <ActionsHistory accountId={selectedAccountId} />
            )}
          </>
        )}
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
