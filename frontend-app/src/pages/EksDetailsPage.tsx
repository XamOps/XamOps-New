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
import { AnimatePresence, motion } from 'framer-motion';
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
import EksResourcesTab from '../components/EksDetails/tabs/EksResourcesTab';
// import EksAutoscalingTab from '../components/EksDetails/tabs/EksAutoscalingTab';
import EksIntegrationModal from '../components/EksDetails/EksIntegrationModal';
import EksDiagnosticsPanel from '../components/EksDetails/EksDiagnosticsPanel';

const EksDetailsPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [showIntegrationGuide, setShowIntegrationGuide] = useState(false);
  const { setTitle, setActions, clearActions } = useTopbar();

  // Listen for the custom event dispatched by child tabs (e.g. EksAutoscalingTab)
  useEffect(() => {
    const handler = () => setShowIntegrationGuide(true);
    document.addEventListener('open-integration-guide', handler);
    return () => document.removeEventListener('open-integration-guide', handler);
  }, []);

  // Get params from URL
  const clusterName = searchParams.get('name');
  const region = searchParams.get('region') || 'us-east-1';
  const version = searchParams.get('version') || '1.33';
  const accountId = searchParams.get('accountIds');
  const tabParam = searchParams.get('tab') as any;
  const debugMode = searchParams.get('debug') === 'true';

  // Initialize dashboard hook
  const eksData = useEksDashboard({
    clusterName,
    region,
    version,
    accountId,
    initialTab: tabParam || undefined,
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
    getTimeSince
  } = eksData;

  // Topbar Integration
  useEffect(() => {
    if (!clusterName) return;

    setTitle(clusterName);

    setActions(
      <div className="flex items-center gap-3">
        <Link
          to={`/cloudk8s?accountIds=${accountId}`}
          className="w-9 h-9 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl flex items-center justify-center text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.25)] transition-all"
          title="Back to Clusters"
        >
          <i className="fas fa-arrow-left text-xs"></i>
        </Link>

        <div className="hidden md:flex flex-col items-end">
          <div className="flex items-center gap-1.5 text-[10px] text-[rgba(0,0,0,0.35)] font-medium whitespace-nowrap">
            <i className="fas fa-code-branch"></i> v{version} · <i className="fas fa-map-marker-alt"></i> {region}
          </div>
          <div className="text-[10px] text-[rgba(0,0,0,0.5)] font-semibold">
            Synced: {getTimeSince(lastUpdateTimestamp)}
          </div>
        </div>

        <button
          onClick={() => setShowIntegrationGuide(true)}
          className="flex items-center gap-2 px-4 py-2 bg-white text-[rgba(0,0,0,0.6)] text-xs font-bold rounded-xl border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.03)] transition-all"
        >
          <i className="fas fa-book text-xs"></i>
          <span>Guide</span>
        </button>

        <button
          onClick={syncInBackground}
          disabled={isSyncing}
          className="flex items-center gap-2 px-4 py-2 bg-[rgba(0,0,0,0.88)] text-white text-xs font-bold rounded-xl hover:bg-black transition-all disabled:opacity-50"
        >
          <i className={clsx('fas fa-sync-alt', isSyncing && 'animate-spin')}></i>
          <span>{isSyncing ? 'Syncing…' : 'Sync Data'}</span>
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
      <main className="flex-1 flex items-center justify-center min-h-screen bg-white">
        <div className="text-center p-8">
          <div className="w-16 h-16 bg-[rgba(220,38,38,0.06)] rounded-xl flex items-center justify-center mx-auto mb-4 border border-[rgba(220,38,38,0.15)]">
            <i className="fas fa-exclamation-triangle text-[#DC2626] text-2xl"></i>
          </div>
          <h3 className="text-[18px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">Missing Parameters</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.4)]">
            Required parameters missing: {!clusterName && 'clusterName'} {!accountId && 'accountId'}
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="eks-details-page flex-1 flex flex-col min-h-screen bg-white" style={{ fontFamily: "'Inter', sans-serif" }}>
      <div className="p-6 space-y-4 w-full">
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
          <div className="bg-[#ffebe9] border border-[rgba(255,129,130,0.4)] p-5 rounded-lg">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-[#cf222e] text-lg"></i>
              <div>
                <h3 className="text-sm font-semibold text-[#cf222e]">Error Loading Dashboard</h3>
                <p className="text-[13px] text-[#57606a] mt-0.5">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Dashboard Content */}
        {(!loading || dashboard) && !error && (
          <>
            {/* Data Diagnostics — only when ?debug=true */}
            {debugMode && dashboard && <EksDiagnosticsPanel dashboard={dashboard} />}

            {/* Summary Cards */}
            <EksSummaryCards eksData={eksData} />

            {/* Tabs */}
            <EksTabs activeTab={activeTab} onTabChange={setActiveTab} />

            {/* Tab Content */}
            <div className="tab-content">
              <AnimatePresence mode="wait">
                <motion.div key={activeTab} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                  {activeTab === 'overview' && <EksOverviewTab eksData={eksData} />}
                  {activeTab === 'cost' && <EksCostTab eksData={eksData} />}
                  {activeTab === 'performance' && <EksPerformanceTab eksData={eksData} />}
                  {activeTab === 'security' && <EksSecurityTab eksData={eksData} />}
                  {activeTab === 'workloads' && <EksWorkloadsTab eksData={eksData} />}
                  {activeTab === 'pods' && <EksPodsTab eksData={eksData} />}
                  {activeTab === 'containers' && <EksContainersTab eksData={eksData} />}
                  {activeTab === 'nodes' && <EksNodesTab eksData={eksData} />}
                  {activeTab === 'resources' && <EksResourcesTab eksData={eksData} />}
                  {activeTab === 'karpenter' && <EksKarpenterTab eksData={eksData} />}
                  {/* {activeTab === 'autoscaling' && <EksAutoscalingTab eksData={eksData} />} */}
                </motion.div>
              </AnimatePresence>
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
