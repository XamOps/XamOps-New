/**
 * AKS Details Page
 *
 * Comprehensive AKS cluster dashboard with 8 tabs.
 * Reuses all GKE tab components — Prometheus metrics are cloud-agnostic.
 */

import React, { useEffect, useState } from 'react';
import { useSearchParams, Link, Navigate } from 'react-router-dom';
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
import GkeNodePoolsTab from '../components/GkeDetails/tabs/GkeNodePoolsTab';
import AzureCloudK8sHelpModal from '../components/AzureCloudK8s/AzureCloudK8sHelpModal';

const AksDetailsPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const { setTitle, setActions, clearActions } = useTopbar();

  const clusterName = searchParams.get('name');
  const resourceGroup = searchParams.get('resourceGroup') || '';
  const accountId = searchParams.get('accountIds');

  const [showGuide, setShowGuide] = useState(false);

  const aksData = useAksDashboard({ clusterName, resourceGroup, accountId });
  const { dashboard, loading, error, notFound, activeTab, setActiveTab, isRefreshing, refresh, reload, getTimeSince } = aksData;

  // Zone-less server date-times, read in browser-local time as elsewhere in the app.
  const timeSince = (value?: string | null): string | null => {
    if (!value) return null;
    const ms = new Date(value).getTime();
    return Number.isNaN(ms) ? null : getTimeSince(ms);
  };
  const syncedAgo = timeSince(dashboard?.lastSyncedAt);
  const agentSeenAgo = timeSince(dashboard?.agentLastSeenAt);

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
          {/* The copy can be up to a day old (nightly sync), so say how old it is. */}
          {syncedAgo && (
            <div className="text-[10px] text-gray-400 font-medium whitespace-nowrap" title={dashboard?.lastSyncedAt}>
              Synced {syncedAgo}
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
  }, [setTitle, setActions, clearActions, clusterName, accountId, resourceGroup, dashboard, isRefreshing, refresh, showGuide, setShowGuide, syncedAgo]);

  // A link without a cluster or subscription has nothing to show; the cluster list does.
  if (!clusterName || !accountId) {
    return <Navigate to="/azure-cloudk8s" replace />;
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
              <div className="flex-1 min-w-0">
                <h3 className="text-lg font-bold text-red-700">
                  {notFound ? 'Cluster Not Found' : 'Error Loading Dashboard'}
                </h3>
                <p className="text-sm text-red-600 mt-1 break-words">{error}</p>
              </div>
              {notFound ? (
                <Link
                  to={`/azure-cloudk8s?accountIds=${accountId}`}
                  className="ml-4 flex-shrink-0 px-4 py-2 bg-white text-red-700 text-xs font-bold rounded-xl border border-red-200 hover:bg-red-50 transition-all"
                >
                  Back to AKS clusters
                </Link>
              ) : (
                <button
                  onClick={reload}
                  className="ml-4 flex-shrink-0 flex items-center gap-2 px-4 py-2 bg-white text-red-700 text-xs font-bold rounded-xl border border-red-200 hover:bg-red-50 transition-all"
                >
                  <i className="fas fa-redo"></i>
                  <span>Retry</span>
                </button>
              )}
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

            {/* A refresh Azure or the agent store could only partly answer: the copy below is the last complete one. */}
            {dashboard?.refreshError && (
              <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-center gap-3">
                <i className="fas fa-exclamation-triangle text-amber-500 text-lg"></i>
                <div>
                  <p className="font-semibold text-amber-800 text-sm">Refresh incomplete</p>
                  <p className="text-amber-700 text-xs mt-0.5 break-words">{dashboard.refreshError}</p>
                </div>
              </div>
            )}

            {/* Agent data exists but the agent has stopped checking in. */}
            {dashboard?.connected && dashboard?.agentStale && (
              <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 flex items-center gap-3">
                <i className="fas fa-plug text-amber-500 text-lg"></i>
                <div>
                  <p className="font-semibold text-amber-800 text-sm">Agent Not Reporting</p>
                  <p className="text-amber-700 text-xs mt-0.5">
                    The XamOps agent last checked in {agentSeenAgo ?? 'over 15 minutes ago'}. The figures below are from its last push.
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
              {activeTab === 'cost'         && <GkeCostTab gkeData={aksData} cloud="azure" />}
              {activeTab === 'performance'  && <GkePerformanceTab gkeData={aksData} />}
              {activeTab === 'security'     && <GkeSecurityTab gkeData={aksData} cloud="azure" />}
              {activeTab === 'workloads'    && <GkeWorkloadsTab gkeData={aksData} cloud="azure" />}
              {activeTab === 'pods'         && <GkePodsTab gkeData={aksData} />}
              {activeTab === 'containers'   && <GkeContainersTab gkeData={aksData} />}
              {activeTab === 'nodes'        && <GkeNodesTab gkeData={aksData} cloud="azure" />}
              {activeTab === 'nodepools'    && <GkeNodePoolsTab gkeData={aksData} />}
            </div>
          </>
        )}
      </div>

      {/* All three guide variables are already in the URL, so the user types nothing. */}
      <AzureCloudK8sHelpModal
        isOpen={showGuide}
        onClose={() => setShowGuide(false)}
        clusterName={clusterName ?? undefined}
        resourceGroup={resourceGroup || undefined}
        subscriptionId={accountId ?? undefined}
      />
    </main>
  );
};

export default AksDetailsPage;
