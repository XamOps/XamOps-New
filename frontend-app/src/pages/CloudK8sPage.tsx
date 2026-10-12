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
import { motion, useIsPresent } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import apiClient from '../utils/apiClient';
import { inPageState } from '../utils/inPageNavigation';
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

/** A message another page sent with the navigation, e.g. the cluster page's "That cluster link is invalid." */
function noticeFrom(state: unknown): string | null {
  const notice = (state as { notice?: unknown } | null)?.notice;
  return typeof notice === 'string' && notice ? notice : null;
}

const CloudK8sPage: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;

  // Shown once: the history entry's state is cleared, so Back or a reload doesn't repeat it. The
  // layout's outgoing page already renders this route while it animates out, so only the copy that
  // stays (isPresent) clears it, with an in-page navigation that doesn't remount the page.
  const isPresent = useIsPresent();
  const [notice, setNotice] = useState<string | null>(() => noticeFrom(location.state));
  useEffect(() => {
    if (isPresent && noticeFrom(location.state)) {
      navigate(
        { pathname: location.pathname, search: location.search, hash: location.hash },
        { replace: true, state: inPageState(location) },
      );
    }
  }, [isPresent, location, navigate]);

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
    const params = new URLSearchParams({ name: cluster.name, region: cluster.region, accountIds: String(selectedAccountId) });
    // The version EKS reports for this cluster (describeCluster), so the cluster page never guesses one.
    if (cluster.version) params.set('version', cluster.version);
    navigate(`/eks-details?${params.toString()}`);
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
      {notice && (
        <div
          role="status"
          className="flex items-center gap-3 rounded-lg border border-[rgba(0,0,0,0.12)] bg-[rgba(0,0,0,0.03)] px-3.5 py-2.5 text-[13px] text-[rgba(0,0,0,0.88)]"
        >
          <Icon icon="mdi:information-outline" width={16} aria-hidden="true" className="shrink-0 text-[rgba(0,0,0,0.6)]" />
          <span className="min-w-0 flex-1">{notice}</span>
          <button
            type="button"
            onClick={() => setNotice(null)}
            aria-label="Dismiss"
            className="flex h-6 w-6 items-center justify-center rounded-md text-[rgba(0,0,0,0.6)] outline-none hover:bg-[rgba(0,0,0,0.05)] focus-visible:ring-2 focus-visible:ring-[rgba(0,0,0,0.88)]"
          >
            <Icon icon="mdi:close" width={14} aria-hidden="true" />
          </button>
        </div>
      )}
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
