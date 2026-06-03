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
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import apiClient from '../utils/apiClient';
import CloudK8sHeader from '../components/CloudK8s/CloudK8sHeader';
import CloudK8sClusterTable from '../components/CloudK8s/CloudK8sClusterTable';
import CloudK8sIntegrations from '../components/CloudK8s/CloudK8sIntegrations';

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
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <CloudK8sHeader
        isRefreshing={isRefreshing}
        onRefresh={() => fetchClusters(true)}
      />
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-5">
        <CloudK8sClusterTable
          clusters={clusters}
          isLoading={isLoading}
          onViewDetails={navigateToDetails}
        />
        <CloudK8sIntegrations />
      </motion.div>
    </main>
  );
};

export default CloudK8sPage;
