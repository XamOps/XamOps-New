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
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import apiClient from '../utils/apiClient';
import { Icon } from '@iconify/react';
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

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-amber-400" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">GKE Dashboard</span>
        <span className="text-[rgba(0,0,0,0.2)]">|</span>
        <button
          onClick={() => fetchClusters(true)}
          disabled={isRefreshing}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors disabled:opacity-50"
        >
          <Icon icon={isRefreshing ? 'mdi:loading' : 'mdi:refresh'} width={13} className={isRefreshing ? 'animate-spin' : ''} />
          {isRefreshing ? 'Refreshing…' : 'Refresh'}
        </button>
        <span className="text-[rgba(0,0,0,0.2)]">|</span>
        <button
          onClick={() => setIsHelpModalOpen(true)}
          className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] hover:bg-[rgba(0,0,0,0.04)] rounded-lg transition-colors"
        >
          <Icon icon="mdi:book-open-outline" width={13} />
          Setup Guide
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, fetchClusters]);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-5">
        <GcpCloudK8sClusterTable
          clusters={clusters}
          isLoading={isLoading}
          onViewDetails={navigateToDetails}
        />
        <GcpCloudK8sIntegrations />
      </motion.div>
      <GcpCloudK8sHelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
      />
    </main>
  );
};

export default GcpCloudK8sPage;
