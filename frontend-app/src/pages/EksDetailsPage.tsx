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
  const { setActions, clearActions } = useTopbar();

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
    isRefreshing,
    refresh,
    lastUpdateTimestamp,
    getTimeSince,
    totalPodsCount
  } = eksData;

  // Topbar Integration
  useEffect(() => {
    if (!clusterName) return;

    setActions(
      <div className="flex items-center gap-4">
        <div className="text-right mr-2 hidden md:block">
          <div className="text-xs text-gray-400 font-medium">Last synced</div>
          <div className="text-xs text-gray-600 font-semibold">
            {getTimeSince(lastUpdateTimestamp)}
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
          onClick={refresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Syncing...' : 'Sync Data'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, refresh, getTimeSince, lastUpdateTimestamp, clusterName]);

  // Handle invalid params
  if (!clusterName || !accountId) {
    return (
      <main className="eks-details-page flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="text-center p-8">
          <div className="w-20 h-20 bg-red-100 rounded-2xl flex items-center justify-center mx-auto mb-4">
            <i className="fas fa-exclamation-triangle text-red-500 text-3xl"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-800 mb-2">Missing Parameters</h3>
          <p className="text-gray-600">
            Required parameters missing: {!clusterName && 'clusterName'} {!accountId && 'accountId'}
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="eks-details-page flex-1 flex flex-col min-h-screen bg-gray-50">
      <div className="p-4 md:p-8 max-w-[1800px] mx-auto w-full">
        {/* Cluster Info Header (Simplified) */}
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
          <div className="flex items-center gap-4">
            <Link
              to={`/cloudk8s?accountIds=${accountId}`}
              className="w-10 h-10 bg-white border border-gray-200 rounded-xl flex items-center justify-center text-gray-500 hover:text-indigo-600 hover:border-indigo-300 transition-all shadow-sm"
            >
              <i className="fas fa-arrow-left"></i>
            </Link>

            <div className="relative">
              <div className="w-12 h-12 bg-gradient-to-br from-indigo-600 to-violet-700 rounded-xl flex items-center justify-center text-white text-xl shadow-md">
                <i className="fas fa-dharmachakra"></i>
              </div>
              <div className="absolute -bottom-1 -right-1 w-3 h-3 bg-green-500 border-2 border-white rounded-full"></div>
            </div>

            <div>
              <div className="flex items-center gap-3">
                <h1 className="text-2xl font-bold text-gray-900">{clusterName}</h1>
                <span className="px-2 py-0.5 rounded-lg bg-indigo-50 text-indigo-700 text-xs font-bold uppercase tracking-wider">
                  EKS
                </span>
              </div>
              <div className="flex items-center gap-3 text-sm text-gray-500 mt-1">
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-code-branch text-xs"></i>
                  <span>v{version}</span>
                </span>
                <span className="text-gray-300">|</span>
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-map-marker-alt text-xs"></i>
                  <span>{region}</span>
                </span>
                <span className="text-gray-300">|</span>
                <span className="flex items-center gap-1.5">
                  <i className="fas fa-cubes text-xs"></i>
                  <span>{totalPodsCount || 0} pods</span>
                </span>
              </div>
            </div>
          </div>
        </div>

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
          <div className="bg-red-50 border-l-4 border-red-400 p-6 rounded-lg shadow-sm">
            <div className="flex items-center">
              <i className="fas fa-exclamation-circle text-red-500 text-2xl mr-4"></i>
              <div>
                <h3 className="text-lg font-bold text-red-800">Error Loading Dashboard</h3>
                <p className="text-sm text-red-700 mt-1">{error}</p>
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

            {/* Development Alert */}
            <div className="bg-amber-50 border-l-4 border-amber-400 p-4 mb-8 rounded shadow-sm">
              <div className="flex">
                <i className="fas fa-exclamation-triangle text-amber-500 mr-3"></i>
                <p className="text-sm text-amber-700">
                  <span className="font-bold">Under Development:</span> This feature is currently being developed. Data may be inaccurate or incomplete.
                </p>
              </div>
            </div>

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
