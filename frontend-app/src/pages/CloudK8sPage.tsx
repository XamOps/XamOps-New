/**
 * CloudK8s Dashboard Page
 *
 * Converted from: cloudk8s.html
 *
 * Monitor and manage EKS clusters with real-time status updates.
 *
 * Features:
 * - Fetches EKS clusters from backend API
 * - Enable monitoring functionality
 * - Help modal with complete observability setup guide
 * - Cache-aware data fetching with refresh capability
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import apiClient from '../utils/apiClient';
import CloudK8sHeader from '../components/CloudK8s/CloudK8sHeader';
import CloudK8sClusterTable from '../components/CloudK8s/CloudK8sClusterTable';
import CloudK8sIntegrations from '../components/CloudK8s/CloudK8sIntegrations';
import CloudK8sHelpModal from '../components/CloudK8s/CloudK8sHelpModal';

export interface EKSCluster {
  name: string;
  region: string;
  version: string;
  connected: boolean;
  isEnabling?: boolean;
}

const CloudK8sPage: React.FC = () => {
  const navigate = useNavigate();
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;

  const [clusters, setClusters] = useState<EKSCluster[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);

  /**
   * Fetch clusters from backend API
   */
  const fetchClusters = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setClusters([]);
      return;
    }

    const cacheKey = `k8sClusters-${selectedAccountId}`;

    if (forceRefresh) {
      sessionStorage.removeItem(cacheKey);
    }

    const cachedData = sessionStorage.getItem(cacheKey);

    if (cachedData && !forceRefresh) {
      const parsedClusters = JSON.parse(cachedData).map((c: EKSCluster) => ({
        ...c,
        isEnabling: false
      }));
      setClusters(parsedClusters);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    if (forceRefresh) setIsRefreshing(true);

    try {
      const data = await apiClient.get<EKSCluster[]>(
        `/api/xamops/k8s/clusters?accountIds=${selectedAccountId}&forceRefresh=${forceRefresh}`
      );

      const processedClusters = data.map((c: EKSCluster) => ({
        ...c,
        isEnabling: false
      }));

      setClusters(processedClusters);
      sessionStorage.setItem(cacheKey, JSON.stringify(data));
    } catch (err) {
      console.error('Failed to fetch clusters', err);
      setClusters([]);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, [selectedAccountId]);

  /**
   * Enable monitoring for a cluster
   */
  const enableMonitoring = useCallback(async (cluster: EKSCluster) => {
    if (!selectedAccountId) return;

    setClusters(prev =>
      prev.map(c =>
        c.name === cluster.name && c.region === cluster.region
          ? { ...c, isEnabling: true }
          : c
      )
    );

    const { name, region } = cluster;

    try {
      await apiClient.post(
        `/api/xamops/eks/${name}/enable-monitoring?accountId=${selectedAccountId}&region=${region}`
      );

      alert(`Successfully initiated monitoring for ${name}. It may take a few minutes to connect.`);

      // Refresh clusters after a delay
      setTimeout(() => fetchClusters(true), 5000);
    } catch (err: any) {
      alert(`Error: ${err.message}`);
      console.error('Failed to enable monitoring', err);
    } finally {
      setClusters(prev =>
        prev.map(c =>
          c.name === cluster.name && c.region === cluster.region
            ? { ...c, isEnabling: false }
            : c
        )
      );
    }
  }, [selectedAccountId, fetchClusters]);

  /**
   * Navigate to cluster details
   */
  const navigateToDetails = useCallback((cluster: EKSCluster) => {
    navigate(`/eks-details?name=${cluster.name}&region=${cluster.region}&accountIds=${selectedAccountId}`);
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

  return (
    <main className="cloudk8s-page flex-1 flex flex-col min-h-screen bg-gray-50">
      {/* Header */}
      <CloudK8sHeader
        isRefreshing={isRefreshing}
        onRefresh={() => fetchClusters(true)}
      />

      {/* Main Content */}
      <div className="flex-1 p-4 md:p-8 space-y-8">
        <div className="space-y-8">
          {/* Clusters Table */}
          <CloudK8sClusterTable
            clusters={clusters}
            isLoading={isLoading}
            onEnableMonitoring={enableMonitoring}
            onViewDetails={navigateToDetails}
          />

          {/* Available Integrations */}
          <CloudK8sIntegrations />
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
      <CloudK8sHelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
      />
    </main>
  );
};

export default CloudK8sPage;
