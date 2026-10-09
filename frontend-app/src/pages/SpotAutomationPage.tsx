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
 */

import React, { useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { gooeyToast } from 'goey-toast';
import { useAccounts } from '../contexts/AccountContext';
import { useSpotAutomationData } from '../hooks/useSpotAutomationData';
import { useAutoSpottingFeatureStatus } from '../hooks/useAutoSpottingFeatureStatus';
import { isConversionsPausedError, parseAutoSpottingError, PAUSED_MESSAGE } from '../utils/autospottingErrors';
import { AriaTabGroup } from '../components/ui';
import SpotAutomationHeader from '../components/SpotAutomation/SpotAutomationHeader';
import SpotAutomationKPIs from '../components/SpotAutomation/SpotAutomationKPIs';
import SpotAutomationChart from '../components/SpotAutomation/SpotAutomationChart';
import SpotAutomationASGTable from '../components/SpotAutomation/SpotAutomationASGTable';
import ConversionsPausedNotice from '../components/SpotAutomation/ConversionsPausedNotice';
import ActionsHistory from '../components/ActionsHistory/ActionsHistory';
import LaunchAnalytics from '../components/LaunchAnalytics/LaunchAnalytics';
import RispCoverage from '../components/RispCoverage/RispCoverage';
import { SpotAutomationSkeleton } from '../components/Shared/Skeleton';
import { useTabState } from '../hooks/useTabState';

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
    error,
    toggleASG,
    syncASG,
    fetchCostData,
    fetchHistoryData,
  } = useSpotAutomationData({ selectedAccountId });

  // Fails closed: treated as paused until the server says otherwise.
  const featureStatus = useAutoSpottingFeatureStatus();
  const conversionsPaused = !featureStatus.awsConversionsEnabled;

  // Modal states
  const [historyTimeRange, setHistoryTimeRange] = useState<'last24h' | 'last7d' | 'last30d'>('last7d');

  // Tab state
  const [activeTab, setActiveTab] = useTabState<'dashboard' | 'analytics' | 'history' | 'risp-coverage'>('dashboard');

  const handleToggleASG = async (asg: any) => {
    try {
      await toggleASG(asg);
      gooeyToast.success(`AutoSpotting ${!asg.isEnabled ? 'enabled' : 'disabled'} for ${asg.name}`);
    } catch (err) {
      if (isConversionsPausedError(err)) {
        gooeyToast.info('Spot conversions are paused', { description: PAUSED_MESSAGE });
        featureStatus.refresh();
      } else {
        gooeyToast.error(`Failed to update ${asg.name}`, {
          description: parseAutoSpottingError(err).message,
        });
      }
    }
  };

  const handleTimeRangeChange = (range: 'last24h' | 'last7d' | 'last30d') => {
    setHistoryTimeRange(range);
    fetchHistoryData(range);
  };

  const enabledASGsCount = asgs.filter((a: any) => a.isEnabled).length;

  return (
    <div className="flex-1 flex flex-col min-h-screen bg-white relative">
      {/* Header */}
      <SpotAutomationHeader />

      {/* Tab Navigation */}
      <div className="bg-white border-b border-[rgba(0,0,0,0.12)] px-8">
        <AriaTabGroup
          tabs={[
            { id: 'dashboard',     label: 'Dashboard',        icon: 'mdi:view-dashboard-outline' },
            { id: 'analytics',     label: 'Launch Analytics', icon: 'mdi:chart-line' },
            { id: 'history',       label: 'Actions History',  icon: 'mdi:history' },
            { id: 'risp-coverage', label: 'RI/SP Coverage',   icon: 'mdi:chart-pie-outline' },
          ]}
          selectedKey={activeTab}
          onSelectionChange={(key) => setActiveTab(key as typeof activeTab)}
        />
      </div>

      {/* Main Content */}
      <div className="flex-1 p-6">
        {/* Beta Warning */}
        <div className="mb-4 flex items-start gap-3 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-amber-800 text-xs">
          <Icon icon="mdi:flask-outline" width={16} className="mt-0.5 text-amber-500 flex-shrink-0" />
          <span><strong>Beta Feature:</strong> AWS Spot Automation is currently in beta. Some features may be incomplete or subject to change.</span>
        </div>

        {conversionsPaused && <ConversionsPausedNotice className="mb-4" />}

        <AnimatePresence mode="wait">
          {/* Dashboard Tab */}
          {activeTab === 'dashboard' && (
            <motion.div key="dashboard" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              {isLoading && asgs.length === 0 ? (
                <SpotAutomationSkeleton />
              ) : (
                <div className="space-y-5">
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
                    conversionsPaused={conversionsPaused}
                    error={error ? parseAutoSpottingError(error).message : null}
                    onRetry={() => fetchCostData(true)}
                  />
                </div>
              )}
            </motion.div>
          )}

          {/* Launch Analytics Tab */}
          {activeTab === 'analytics' && (
            <motion.div key="analytics" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <LaunchAnalytics accountId={selectedAccountId || ''} />
            </motion.div>
          )}

          {/* Actions History Tab */}
          {activeTab === 'history' && (
            <motion.div key="history" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <ActionsHistory accountId={selectedAccountId} />
            </motion.div>
          )}

          {/* RI/SP Coverage Tab */}
          {activeTab === 'risp-coverage' && (
            <motion.div key="risp-coverage" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
              <RispCoverage accountId={selectedAccountId} />
            </motion.div>
          )}
        </AnimatePresence>

      </div>

    </div>
  );
};

export default SpotAutomationPage;