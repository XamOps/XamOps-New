/**
 * AKS Details Page
 *
 * Comprehensive AKS cluster dashboard with 8 tabs.
 * Reuses all GKE tab components — Prometheus metrics are cloud-agnostic.
 */

import React, { useEffect, useState } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { useAksDashboard } from '../hooks/useAksDashboard';
import { useTopbar } from '../contexts/TopbarContext';
import clsx from 'clsx';
import AksSummaryCards from '../components/AksDetails/AksSummaryCards';
import AksTabs from '../components/AksDetails/AksTabs';
import GkeOverviewTab from '../components/GkeDetails/tabs/GkeOverviewTab';
import GkeWorkloadsTab from '../components/GkeDetails/tabs/GkeWorkloadsTab';
import GkePodsTab from '../components/GkeDetails/tabs/GkePodsTab';
import GkeCostTab from '../components/GkeDetails/tabs/GkeCostTab';
import GkePerformanceTab from '../components/GkeDetails/tabs/GkePerformanceTab';
import GkeSecurityTab from '../components/GkeDetails/tabs/GkeSecurityTab';
import GkeContainersTab from '../components/GkeDetails/tabs/GkeContainersTab';
import GkeNodesTab from '../components/GkeDetails/tabs/GkeNodesTab';
import AzureCloudK8sHelpModal from '../components/AzureCloudK8s/AzureCloudK8sHelpModal';

const AksDetailsPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const { setTitle, setActions, clearActions } = useTopbar();

  const clusterName = searchParams.get('name');
  const resourceGroup = searchParams.get('resourceGroup') || '';
  const accountId = searchParams.get('accountIds');

  const [showGuide, setShowGuide] = useState(false);

  const aksData = useAksDashboard({ clusterName, resourceGroup, accountId });
  const { dashboard, loading, error, activeTab, setActiveTab, isRefreshing, refresh } = aksData;

  useEffect(() => {
    if (!clusterName) return;

    setTitle(clusterName);

    setActions(
      <div className="flex items-center gap-4">
        <Link
          to={`/azure-cloudk8s?accountIds=${accountId}`}
          className="w-10 h-10 bg-white border border-gray-200 rounded-xl flex items-center justify-center text-gray-500 hover:text-blue-600 hover:border-blue-300 transition-all shadow-sm"
          title="Back to AKS Clusters"
        >
          <i className="fas fa-arrow-left"></i>
        </Link>

        <div className="hidden md:flex flex-col items-end mr-2">
          <div className="flex items-center gap-1.5 text-[10px] text-gray-400 font-medium whitespace-nowrap">
            <i className="fas fa-layer-group"></i> {resourceGroup}
          </div>
          {dashboard?.version && (
            <div className="text-[10px] text-gray-600 font-semibold">
              v{dashboard.version}
            </div>
          )}
        </div>

        <button
          onClick={() => setShowGuide(true)}
          className="flex items-center gap-2 px-4 py-2 bg-white text-gray-700 text-xs font-bold rounded-xl border border-gray-200 shadow-sm hover:shadow-md transition-all active:scale-95"
        >
          <i className="fas fa-book"></i>
          <span>Guide</span>
        </button>

        <button
          onClick={refresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-blue-200 hover:bg-blue-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );

    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, clusterName, accountId, resourceGroup, dashboard, isRefreshing, refresh, showGuide, setShowGuide]);

  if (!clusterName || !accountId) {
    return (
      <main className="flex-1 flex items-center justify-center min-h-screen">
        <div className="text-center p-8">
          <div className="w-20 h-20 bg-red-50 rounded-2xl flex items-center justify-center mx-auto mb-4 border border-red-200">
            <i className="fas fa-exclamation-triangle text-red-400 text-3xl"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-800 mb-2">Missing Parameters</h3>
          <p className="text-gray-500">
            Required URL parameters missing: {!clusterName && 'name'} {!accountId && 'accountIds'}
          </p>
        </div>
      </main>
    );
  }

  return (
    <main className="aks-details-page flex-1 flex flex-col min-h-screen bg-gray-50">
      <div className="p-4 md:p-8 space-y-8 w-full">

        {/* Loading State */}
        {loading && !dashboard && (
          <div className="space-y-6">
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
              {[1, 2, 3, 4].map(i => (
                <div key={i} className="skeleton h-32 rounded-xl bg-gray-200 animate-pulse"></div>
              ))}
            </div>
            <div className="h-96 rounded-xl bg-gray-200 animate-pulse"></div>
          </div>
        )}

        {/* Error State */}
        {error && !loading && (
          <div className="bg-red-50 border-l-4 border-red-400 p-6 rounded-lg shadow-sm">
            <div className="flex items-center">
              <i className="fas fa-exclamation-circle text-red-400 text-2xl mr-4"></i>
              <div>
                <h3 className="text-lg font-bold text-red-700">Error Loading Dashboard</h3>
                <p className="text-sm text-red-600 mt-1">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Dashboard Content */}
        {(!loading || dashboard) && !error && (
          <>
            {/* Disconnected Banner */}
            {dashboard && !dashboard.connected && (
              <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-center gap-3">
                <i className="fas fa-info-circle text-amber-500 text-lg"></i>
                <div>
                  <p className="font-semibold text-amber-800 text-sm">Monitoring Not Connected</p>
                  <p className="text-amber-700 text-xs mt-0.5">
                    Enable monitoring on this AKS cluster to see real-time workload, pod, and resource metrics.
                    Agent pool information is available from the Azure API.
                  </p>
                </div>
              </div>
            )}

            {/* Summary Cards */}
            <AksSummaryCards aksData={aksData} />

            {/* Tabs */}
            <AksTabs activeTab={activeTab} onTabChange={setActiveTab} />

            {/* Tab Content — reuse all GKE tab components, pass aksData as gkeData */}
            <div>
              {activeTab === 'overview'     && <GkeOverviewTab gkeData={aksData} />}
              {activeTab === 'cost'         && <GkeCostTab gkeData={aksData} />}
              {activeTab === 'performance'  && <GkePerformanceTab gkeData={aksData} />}
              {activeTab === 'security'     && <GkeSecurityTab gkeData={aksData} />}
              {activeTab === 'workloads'    && <GkeWorkloadsTab gkeData={aksData} />}
              {activeTab === 'pods'         && <GkePodsTab gkeData={aksData} />}
              {activeTab === 'containers'   && <GkeContainersTab gkeData={aksData} />}
              {activeTab === 'nodes'        && <GkeNodesTab gkeData={aksData} />}
            </div>
          </>
        )}
      </div>

      <AzureCloudK8sHelpModal isOpen={showGuide} onClose={() => setShowGuide(false)} />
    </main>
  );
};

export default AksDetailsPage;
