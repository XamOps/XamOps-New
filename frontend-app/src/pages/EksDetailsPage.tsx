/**
 * EKS Details Page
 *
 * Converted from: eks-details.html
 *
 * Comprehensive EKS cluster dashboard with multiple tabs for monitoring
 * cluster health, cost, performance, security, workloads, and more.
 *
 * Features:
 * - Real-time cluster metrics and health scoring
 * - Cost analysis and optimization recommendations
 * - Security monitoring (Falco, Kubescape, Trivy)
 * - Resource utilization tracking
 * - Workload, pod, container, and node management
 * - Karpenter autoscaling monitoring
 * - Integration guide modal
 */

import React, { useState, useEffect } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useEksDashboard } from '../hooks/useEksDashboard';
import { useTopbar } from '../contexts/TopbarContext';
import clsx from 'clsx';
import EksSummaryCards from '../components/EksDetails/EksSummaryCards';
import EksTabs from '../components/EksDetails/EksTabs';
import EksOverviewTab from '../components/EksDetails/tabs/EksOverviewTab';
import EksCostTab from '../components/EksDetails/tabs/EksCostTab';
import EksPerformanceTab from '../components/EksDetails/tabs/EksPerformanceTab';
import EksSecurityTab from '../components/EksDetails/tabs/EksSecurityTab';
import EksWorkloadsTab from '../components/EksDetails/tabs/EksWorkloadsTab';
import EksPodsTab from '../components/EksDetails/tabs/EksPodsTab';
import EksContainersTab from '../components/EksDetails/tabs/EksContainersTab';
import EksNodesTab from '../components/EksDetails/tabs/EksNodesTab';
import EksKarpenterTab from '../components/EksDetails/tabs/EksKarpenterTab';
import EksIntegrationModal from '../components/EksDetails/EksIntegrationModal';

const EksDetailsPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [showIntegrationGuide, setShowIntegrationGuide] = useState(false);
  const { setTitle, setActions, clearActions } = useTopbar();

  // Get params from URL
  const clusterName = searchParams.get('name');
  const region = searchParams.get('region') || 'us-east-1';
  const version = searchParams.get('version') || '1.33';
  const accountId = searchParams.get('accountIds');

  // Initialize dashboard hook
  const eksData = useEksDashboard({
    clusterName,
    region,
    version,
    accountId
  });

  const {
    dashboard,
    loading,
    error,
    activeTab,
    setActiveTab,
    isSyncing,
    syncInBackground,
    lastUpdateTimestamp,
    getTimeSince,
    totalPodsCount
  } = eksData;

  // Topbar Integration
  useEffect(() => {
    if (!clusterName) return;

    setTitle(clusterName);

    setActions(
      <div className="flex items-center gap-4">
        <Link
          to={`/cloudk8s?accountIds=${accountId}`}
          className="w-10 h-10 bg-white border border-gray-200 rounded-xl flex items-center justify-center text-gray-500 hover:text-indigo-600 hover:border-indigo-300 transition-all shadow-sm"
          title="Back to Clusters"
        >
          <i className="fas fa-arrow-left"></i>
        </Link>

        {/* Sync Status - Simplified for Topbar */}
        <div className="hidden md:flex flex-col items-end mr-2">
          <div className="flex items-center gap-1.5 text-[10px] text-gray-400 font-medium whitespace-nowrap">
            <i className="fas fa-code-branch"></i> v{version} | <i className="fas fa-map-marker-alt"></i> {region}
          </div>
          <div className="text-[10px] text-gray-600 font-semibold">
            Last synced: {getTimeSince(lastUpdateTimestamp)}
          </div>
        </div>

        <button
          onClick={() => setShowIntegrationGuide(true)}
          className="flex items-center gap-2 px-4 py-2 bg-white text-gray-700 text-xs font-bold rounded-xl border border-gray-200 shadow-sm hover:shadow-md transition-all active:scale-95"
        >
          <i className="fas fa-book"></i>
          <span>Guide</span>
        </button>

        <button
          onClick={syncInBackground}
          disabled={isSyncing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isSyncing && 'animate-spin')}></i>
          <span>{isSyncing ? 'Syncing...' : 'Sync Data'}</span>
        </button>
      </div>
    );
    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, isSyncing, syncInBackground, getTimeSince, lastUpdateTimestamp, clusterName, accountId, version, region]);

  // Handle invalid params
  if (!clusterName || !accountId) {
    return (
      <main className="eks-details-page flex-1 flex items-center justify-center min-h-screen">
        <div className="text-center p-8">
          <div className="w-20 h-20 bg-red-900/20 rounded-2xl flex items-center justify-center mx-auto mb-4 border border-red-500/30">
            <i className="fas fa-exclamation-triangle text-red-400 text-3xl"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-100 mb-2">Missing Parameters</h3>
          <p className="text-gray-400">
            Required parameters missing: {!clusterName && 'clusterName'} {!accountId && 'accountId'}
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="eks-details-page flex-1 flex flex-col min-h-screen">
      <div className="p-8 space-y-8 w-full">
        {/* Old Header Removed */}

        {/* Loading State */}
        {loading && !dashboard && (
          <div className="space-y-6">
            <div className="grid grid-cols-4 gap-6">
              {[1, 2, 3, 4].map(i => (
                <div key={i} className="skeleton h-40 rounded-xl"></div>
              ))}
            </div>
            <div className="skeleton h-96 rounded-xl"></div>
          </div>
        )}

        {/* Error State */}
        {error && !loading && (
          <div className="bg-red-900/20 border-l-4 border-red-500 p-6 rounded-lg shadow-sm backdrop-blur-sm">
            <div className="flex items-center">
              <i className="fas fa-exclamation-circle text-red-400 text-2xl mr-4"></i>
              <div>
                <h3 className="text-lg font-bold text-red-300">Error Loading Dashboard</h3>
                <p className="text-sm text-red-400 mt-1">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Dashboard Content */}
        {(!loading || dashboard) && !error && (
          <>
            {/* Summary Cards */}
            <EksSummaryCards eksData={eksData} />

            {/* Tabs */}
            <EksTabs activeTab={activeTab} onTabChange={setActiveTab} />

            {/* Tab Content */}
            <div className="tab-content">
              {activeTab === 'overview' && <EksOverviewTab eksData={eksData} />}
              {activeTab === 'cost' && <EksCostTab eksData={eksData} />}
              {activeTab === 'performance' && <EksPerformanceTab eksData={eksData} />}
              {activeTab === 'security' && <EksSecurityTab eksData={eksData} />}
              {activeTab === 'workloads' && <EksWorkloadsTab eksData={eksData} />}
              {activeTab === 'pods' && <EksPodsTab eksData={eksData} />}
              {activeTab === 'containers' && <EksContainersTab eksData={eksData} />}
              {activeTab === 'nodes' && <EksNodesTab eksData={eksData} />}
              {activeTab === 'karpenter' && <EksKarpenterTab eksData={eksData} />}
            </div>
          </>
        )}
      </div>

      {/* Integration Guide Modal */}
      <EksIntegrationModal
        isOpen={showIntegrationGuide}
        onClose={() => setShowIntegrationGuide(false)}
      />
    </main>
  );
};

export default EksDetailsPage;
