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
import { useSearchParams } from 'react-router-dom';
import { useEksDashboard } from '../hooks/useEksDashboard';
import EksHeader from '../components/EksDetails/EksHeader';
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
    setActiveTab
  } = eksData;

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
        {/* Header */}
        <EksHeader
          clusterName={clusterName}
          region={region}
          version={version}
          accountId={accountId}
          eksData={eksData}
          onOpenIntegrationGuide={() => setShowIntegrationGuide(true)}
        />

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
