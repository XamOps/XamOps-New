/**
 * GCP CloudK8s Dashboard Page
 *
 * Monitor and manage GKE (Google Kubernetes Engine) clusters with real-time status updates.
 *
 * Features:
 * - Fetches GKE clusters from backend API
 * - Enable monitoring functionality
 * - Help modal with complete observability setup guide
 * - Cache-aware data fetching with refresh capability
 * - Provider-aware routing (accessed when GCP account is selected)
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import apiClient from '../utils/apiClient';
import clsx from 'clsx';
import GcpCloudK8sClusterTable from '../components/GcpCloudK8s/GcpCloudK8sClusterTable';
import GcpCloudK8sIntegrations from '../components/GcpCloudK8s/GcpCloudK8sIntegrations';
import GcpCloudK8sHelpModal from '../components/GcpCloudK8s/GcpCloudK8sHelpModal';
import { GKECluster } from '../types/gkeTypes';

const GcpCloudK8sPage: React.FC = () => {
  const navigate = useNavigate();
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const { setActions, clearActions } = useTopbar();

  const [clusters, setClusters] = useState<GKECluster[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);

  /**
   * Fetch GKE clusters from backend API
   */
  const fetchClusters = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setClusters([]);
      return;
    }

    const cacheKey = `gkeClusters-${selectedAccountId}`;

    if (forceRefresh) {
      sessionStorage.removeItem(cacheKey);
    }

    const cachedData = sessionStorage.getItem(cacheKey);

    if (cachedData && !forceRefresh) {
      setClusters(JSON.parse(cachedData));
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    if (forceRefresh) setIsRefreshing(true);

    try {
      // API endpoint for GKE clusters (similar pattern to EKS)
      const data = await apiClient.get<GKECluster[]>(
        `/api/xamops/gke/clusters?accountIds=${selectedAccountId}&forceRefresh=${forceRefresh}`
      );

      setClusters(data);
      sessionStorage.setItem(cacheKey, JSON.stringify(data));
    } catch (err: any) {
      console.error('Failed to fetch GKE clusters', err);

      // For development: Show empty state with helpful message
      if (err.message?.includes('404') || err.message?.includes('not found')) {
        console.info('GKE API endpoint not yet implemented. Showing empty state.');
      }

      setClusters([]);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, [selectedAccountId]);

  /**
   * Navigate to cluster details
   */
  const navigateToDetails = useCallback((cluster: GKECluster) => {
    navigate(`/gke-details?name=${cluster.name}&location=${cluster.location}&accountIds=${selectedAccountId}`);
  }, [navigate, selectedAccountId]);

  // Initialize on mount and when account changes
  useEffect(() => {
    fetchClusters(false);
  }, [fetchClusters]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = () => {
      fetchClusters(false);
    };

    window.addEventListener('account-changed', handleAccountChanged);
    return () => window.removeEventListener('account-changed', handleAccountChanged);
  }, [fetchClusters]);

  // Redirect to AWS CloudK8s if AWS account is selected
  useEffect(() => {
    if (selectedAccountDisplay.provider && selectedAccountDisplay.provider !== 'GCP') {
      console.log(`Redirecting to AWS CloudK8s - Provider: ${selectedAccountDisplay.provider}`);
      navigate('/cloudk8s');
    }
  }, [selectedAccountDisplay.provider, navigate]);

  // Topbar Integration
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={() => fetchClusters(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh Now'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, fetchClusters]);

  return (
    <main className="gcp-cloudk8s-page flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header Removed - Integrated into Topbar */}

      {/* Main Content */}
      <div className="flex-1 p-4 md:p-8 space-y-8">
        <div className="space-y-8">
          {/* Clusters Table */}
          <GcpCloudK8sClusterTable
            clusters={clusters}
            isLoading={isLoading}
            onViewDetails={navigateToDetails}
          />

          {/* Available Integrations */}
          <GcpCloudK8sIntegrations />
        </div>
      </div>

      {/* Floating Help Button */}
      <button
        onClick={() => setIsHelpModalOpen(true)}
        className="fixed bottom-8 right-8 w-14 h-14 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white text-2xl shadow-lg cursor-pointer hover:scale-110 hover:shadow-xl transition-all z-30 group"
        aria-label="Open setup guide"
      >
        <i className="fas fa-question group-hover:animate-pulse"></i>
      </button>

      {/* Help Modal */}
      <GcpCloudK8sHelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
      />
    </main>
  );
};

export default GcpCloudK8sPage;
