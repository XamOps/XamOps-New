import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';

// Namespace the cache by tenant/impersonation identity so cached disk data
// cannot leak across a tenant switch or "login as" session change.
function getDiskCacheKey(accountId: string): string {
  const tenantId = sessionStorage.getItem('selectedTenantId') || '';
  const impersonateUserId = sessionStorage.getItem('impersonateUserId') || '';
  return `azure-disks-${tenantId}-${impersonateUserId}-${accountId}`;
}

export interface AzureDisk {
  diskName: string;
  resourceGroup: string;
  region: string;
  diskType: string;       // Premium_LRS, Standard_LRS, StandardSSD_LRS, UltraSSD_LRS
  sizeGb: number;
  status: string;
  attached: boolean;
  instanceName?: string;
  monthlyCost: number;
  usedPercent?: number;
  usedGb?: number;
  freeGb?: number;
  agentDeployed?: boolean;
  recommendation: 'DELETE_UNATTACHED' | 'SHRINK' | 'EXPAND' | 'NONE';
  recommendationReason?: string;
  recommendedSizeGb: number;
  estimatedSavings: number;
}

export interface AzureDiskAction {
  id: number;
  volumeId: string;
  region: string;
  actionType: string;
  oldSizeGb?: number;
  newSizeGb?: number;
  estimatedSavings?: number;
  status: 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  initiatedAt: string;
  completedAt?: string;
}

interface UseAzureDiskRightsizingDataReturn {
  disks: AzureDisk[];
  recommendations: AzureDisk[];
  actionHistory: AzureDiskAction[];
  isLoading: boolean;
  isRefreshing: boolean;
  error: string | null;
  fetchDisks: (forceRefresh?: boolean) => Promise<void>;
  applyAction: (diskName: string, resourceGroup: string, actionType: string, targetSizeGb?: number) => Promise<void>;
  refreshAll: () => Promise<void>;
}

export function useAzureDiskRightsizingData(selectedAccountId: string | null): UseAzureDiskRightsizingDataReturn {
  const [disks, setDisks] = useState<AzureDisk[]>([]);
  const [recommendations, setRecommendations] = useState<AzureDisk[]>([]);
  const [actionHistory, setActionHistory] = useState<AzureDiskAction[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDisks = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) return;
    const cacheKey = getDiskCacheKey(selectedAccountId);
    if (!forceRefresh) {
      const cached = sessionStorage.getItem(cacheKey);
      if (cached) {
        try {
          const diskList: AzureDisk[] = JSON.parse(cached);
          setDisks(diskList);
          setRecommendations(diskList.filter(d => d.recommendation !== 'NONE'));
          return;
        } catch {}
      }
    }
    try {
      const url = `/api/azure/disks/volumes/${selectedAccountId}?forceRefresh=${forceRefresh}`;
      const data = await apiClient.get(url);
      const diskList: AzureDisk[] = Array.isArray(data) ? data : [];
      setDisks(diskList);
      setRecommendations(diskList.filter(d => d.recommendation !== 'NONE'));
      sessionStorage.setItem(cacheKey, JSON.stringify(diskList));
    } catch (err: any) {
      setError(err?.message ?? 'Failed to load Azure disks');
    }
  }, [selectedAccountId]);

  const fetchHistory = useCallback(async () => {
    if (!selectedAccountId) return;
    try {
      const data = await apiClient.get(`/api/azure/disks/history/${selectedAccountId}`);
      setActionHistory(Array.isArray(data) ? data : []);
    } catch {
      // non-fatal
    }
  }, [selectedAccountId]);

  const applyAction = useCallback(async (
    diskName: string, resourceGroup: string, actionType: string, targetSizeGb?: number
  ) => {
    if (!selectedAccountId) return;
    await apiClient.post(`/api/azure/disks/action/${selectedAccountId}`, {
      diskName, resourceGroup, actionType, targetSizeGb
    });
    sessionStorage.removeItem(getDiskCacheKey(selectedAccountId));
    await fetchDisks(true);
    await fetchHistory();
  }, [selectedAccountId, fetchDisks, fetchHistory]);

  const refreshAll = useCallback(async () => {
    if (!selectedAccountId) return;
    setIsRefreshing(true);
    try {
      await fetchDisks(true);
      await fetchHistory();
    } finally {
      setIsRefreshing(false);
    }
  }, [selectedAccountId, fetchDisks, fetchHistory]);

  useEffect(() => {
    if (!selectedAccountId) return;
    setIsLoading(true);
    setError(null);
    Promise.all([fetchDisks(), fetchHistory()]).finally(() => setIsLoading(false));
  }, [selectedAccountId]);

  return { disks, recommendations, actionHistory, isLoading, isRefreshing, error, fetchDisks, applyAction, refreshAll };
}
