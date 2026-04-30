import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import apiClient from '../utils/apiClient';
import clsx from 'clsx';
import AzureCloudK8sClusterTable from '../components/AzureCloudK8s/AzureCloudK8sClusterTable';
import AzureCloudK8sIntegrations from '../components/AzureCloudK8s/AzureCloudK8sIntegrations';
import AzureCloudK8sHelpModal from '../components/AzureCloudK8s/AzureCloudK8sHelpModal';
import { AKSCluster } from '../types/aksTypes';

const AzureCloudK8sPage: React.FC = () => {
  const navigate = useNavigate();
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds[0] || null;
  const { setActions, clearActions } = useTopbar();

  const [clusters, setClusters] = useState<AKSCluster[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isHelpModalOpen, setIsHelpModalOpen] = useState(false);

  const fetchClusters = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setClusters([]);
      return;
    }

    const cacheKey = `aksClusters-${selectedAccountId}`;

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
      const data = await apiClient.get<AKSCluster[]>(
        `/api/xamops/aks/clusters?accountIds=${selectedAccountId}`
      );

      setClusters(data);
      sessionStorage.setItem(cacheKey, JSON.stringify(data));
    } catch (err: any) {
      console.error('Failed to fetch AKS clusters', err);
      setClusters([]);
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

  // Topbar: Refresh button
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={() => fetchClusters(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-blue-200 hover:bg-blue-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh Now'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, fetchClusters]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-gray-50">
      <div className="flex-1 p-4 md:p-8 space-y-8">
        <AzureCloudK8sClusterTable
          clusters={clusters}
          isLoading={isLoading}
          onViewDetails={navigateToDetails}
        />
        <AzureCloudK8sIntegrations />
      </div>

      {/* Floating Help Button */}
      <button
        onClick={() => setIsHelpModalOpen(true)}
        className="fixed bottom-8 right-8 w-14 h-14 bg-gradient-to-br from-blue-500 to-cyan-600 rounded-full flex items-center justify-center text-white text-2xl shadow-lg cursor-pointer hover:scale-110 hover:shadow-xl transition-all z-30 group"
        aria-label="Open setup guide"
      >
        <i className="fas fa-question group-hover:animate-pulse"></i>
      </button>

      <AzureCloudK8sHelpModal
        isOpen={isHelpModalOpen}
        onClose={() => setIsHelpModalOpen(false)}
      />
    </main>
  );
};

export default AzureCloudK8sPage;
