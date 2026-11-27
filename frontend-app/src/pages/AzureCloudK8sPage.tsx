import React, { useState, useEffect, useCallback } from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import apiClient from '../utils/apiClient';
import { Icon } from '@iconify/react';
import AzureCloudK8sClusterTable from '../components/AzureCloudK8s/AzureCloudK8sClusterTable';
import AzureCloudK8sIntegrations from '../components/AzureCloudK8s/AzureCloudK8sIntegrations';
import AzureCloudK8sHelpModal from '../components/AzureCloudK8s/AzureCloudK8sHelpModal';
import { AKSCluster } from '../types/aksTypes';
import { classifyApiError } from '../utils/k8sApiError';

/**
 * The reason to show when the list cannot be read. The server answers a failed Azure call with
 * `{"error": "..."}`, which apiClient wraps as `HTTP 502: {...}`.
 */
const listErrorMessage = (err: unknown): string => {
  const message = err instanceof Error ? err.message : '';
  try {
    const parsed = JSON.parse(message.replace(/^HTTP \d{3}:\s*/, ''));
    if (parsed && typeof parsed.error === 'string' && parsed.error.trim()) return parsed.error;
  } catch {
    // Not a JSON body; fall through to a generic reason.
  }
  return classifyApiError(err).kind === 'network'
    ? 'The XamOps server could not be reached.'
    : 'The AKS cluster list could not be loaded.';
};

// The list is cached per tab so navigating back is instant. Without an expiry a
// long-lived tab never sees a cluster added or removed in Azure, so entries older
// than this are dropped and refetched.
const CLUSTER_CACHE_TTL_MS = 5 * 60 * 1000;

type CachedClusters = { ts: number; data: AKSCluster[] };

const readCachedClusters = (cacheKey: string): AKSCluster[] | null => {
  const raw = sessionStorage.getItem(cacheKey);
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as CachedClusters;
    // Entries written before this envelope existed are bare arrays: discard them.
    if (!parsed || typeof parsed.ts !== 'number' || !Array.isArray(parsed.data)) {
      sessionStorage.removeItem(cacheKey);
      return null;
    }
    if (Date.now() - parsed.ts > CLUSTER_CACHE_TTL_MS) {
      sessionStorage.removeItem(cacheKey);
      return null;
    }
    return parsed.data;
  } catch {
    sessionStorage.removeItem(cacheKey);
    return null;
  }
};

const AzureCloudK8sPage: React.FC = () => {
  const navigate = useNavigate();
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const { setActions, clearActions } = useTopbar();

  const [clusters, setClusters] = useState<AKSCluster[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const fetchClusters = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setClusters([]);
      setLoadError(null);
      return;
    }

    const cacheKey = `aksClusters-${selectedAccountId}`;

    if (forceRefresh) {
      sessionStorage.removeItem(cacheKey);
    } else {
      const cachedData = readCachedClusters(cacheKey);
      if (cachedData) {
        setClusters(cachedData);
        setLoadError(null);
        setIsLoading(false);
        return;
      }
    }

    setIsLoading(true);
    if (forceRefresh) setIsRefreshing(true);

    try {
      // forceRefresh has to reach the server. The cluster list is Redis-cached for
      // 60 minutes, so dropping only the sessionStorage copy re-reads the same stale
      // list and clusters created or deleted in Azure stay invisible until it expires.
      const data = await apiClient.get<AKSCluster[]>(
        `/api/xamops/aks/clusters?accountIds=${encodeURIComponent(selectedAccountId)}&forceRefresh=${forceRefresh}`
      );

      setClusters(data);
      setLoadError(null);
      // Never cache an empty list (as the EKS page does): an empty read must not stand in for
      // "no clusters" for the next five minutes.
      if (Array.isArray(data) && data.length > 0) {
        sessionStorage.setItem(cacheKey, JSON.stringify({ ts: Date.now(), data }));
      }
    } catch (err: any) {
      // A failed read used to land here as an empty list, shown as "No AKS clusters found".
      console.error('Failed to fetch AKS clusters', err);
      setClusters([]);
      setLoadError(listErrorMessage(err));
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, [selectedAccountId]);

  const navigateToDetails = useCallback((cluster: AKSCluster) => {
    navigate(`/aks-details?name=${encodeURIComponent(cluster.name)}&resourceGroup=${encodeURIComponent(cluster.resourceGroup)}&accountIds=${selectedAccountId}`);
  }, [navigate, selectedAccountId]);

  // Load on mount and account change
  useEffect(() => {
    fetchClusters(false);
  }, [fetchClusters]);

  useEffect(() => {
    const handleAccountChanged = () => fetchClusters(false);
    window.addEventListener('account-changed', handleAccountChanged);
    return () => window.removeEventListener('account-changed', handleAccountChanged);
  }, [fetchClusters]);

  // Redirect if non-Azure account selected
  useEffect(() => {
    if (selectedAccountDisplay.provider && selectedAccountDisplay.provider !== 'Azure') {
      navigate('/cloudk8s');
    }
  }, [selectedAccountDisplay.provider, navigate]);

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        <span className="w-2 h-2 rounded-full bg-amber-400" />
        <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.65)]">AKS Dashboard</span>
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
        {loadError && !isLoading ? (
          <div className="bg-white border border-red-200 rounded-xl shadow-sm p-5 flex items-start gap-3" role="alert">
            <Icon icon="mdi:alert-circle-outline" width={20} className="text-red-500 flex-shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className="text-[13px] font-semibold text-red-700">Could not load AKS clusters</p>
              <p className="text-[12px] text-[rgba(0,0,0,0.55)] mt-1 break-words">{loadError}</p>
            </div>
            <button
              onClick={() => fetchClusters(false)}
              className="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-semibold text-red-700 border border-red-200 hover:bg-red-50 rounded-lg transition-colors flex-shrink-0"
            >
              <Icon icon="mdi:refresh" width={13} />
              Retry
            </button>
          </div>
        ) : (
          <AzureCloudK8sClusterTable
            clusters={clusters}
            isLoading={isLoading}
            onViewDetails={navigateToDetails}
          />
        )}
        <AzureCloudK8sIntegrations />
      </motion.div>
      {/* No cluster is selected here, so offer a picker. Choosing one fills the resource
          group too, which avoids pairing a cluster with the wrong group. */}
      <AzureCloudK8sHelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
        subscriptionId={selectedAccountId ?? undefined}
        clusters={clusters.map(c => ({ name: c.name, resourceGroup: c.resourceGroup }))}
      />
    </main>
  );
};

export default AzureCloudK8sPage;
