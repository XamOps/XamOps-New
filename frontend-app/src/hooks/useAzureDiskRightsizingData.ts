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
  /** ISO code of monthlyCost and estimatedSavings: the subscription's billing currency (e.g. "INR"). */
  currency?: string;
}

/** Body returned by POST /api/azure/disks/action/{accountId}. Failures arrive as HTTP 200 with success=false. */
export interface AzureDiskActionResult {
  success: boolean;
  error?: string;
  /** e.g. "Disk x expanded to 64 GB". */
  message?: string;
  /** True after an EXPAND: the guest filesystem still has to be grown. */
  followUpRequired?: boolean;
  /** What to do next, shown to the user (the in-guest grow commands). */
  followUpAction?: string;
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
  /** Set when the action history could not be loaded, so the tab does not claim there is none. */
  historyError: string | null;
  fetchDisks: (forceRefresh?: boolean) => Promise<void>;
  applyAction: (diskName: string, resourceGroup: string, actionType: string, targetSizeGb?: number) => Promise<AzureDiskActionResult | undefined>;
  refreshAll: () => Promise<void>;
}

/**
 * The API sends null for usage it does not have (no disk agent on the VM), while AzureDisk and the
 * page treat a missing value as undefined. A null passed the page's `!== undefined` checks and
 * crashed the row render on usedPercent.toFixed, and hid the "install the disk agent" hints,
 * which test `=== undefined`.
 */
function normalizeDisk(disk: AzureDisk): AzureDisk {
  return {
    ...disk,
    instanceName: disk.instanceName ?? undefined,
    usedPercent: disk.usedPercent ?? undefined,
    usedGb: disk.usedGb ?? undefined,
    freeGb: disk.freeGb ?? undefined,
  };
}

export function useAzureDiskRightsizingData(selectedAccountId: string | null): UseAzureDiskRightsizingDataReturn {
  const [disks, setDisks] = useState<AzureDisk[]>([]);
  const [recommendations, setRecommendations] = useState<AzureDisk[]>([]);
  const [actionHistory, setActionHistory] = useState<AzureDiskAction[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const fetchDisks = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) return;
    // Cleared on every load. It was cleared only when the account changed, so after one failure a
    // successful Refresh still left the error panel in place of the table.
    setError(null);
    const cacheKey = getDiskCacheKey(selectedAccountId);
    if (!forceRefresh) {
      const cached = sessionStorage.getItem(cacheKey);
      if (cached) {
        try {
          // Normalised here too: a list cached before this fix still holds the raw nulls.
          const diskList: AzureDisk[] = (JSON.parse(cached) as AzureDisk[]).map(normalizeDisk);
          setDisks(diskList);
          setRecommendations(diskList.filter(d => d.recommendation !== 'NONE'));
          return;
        } catch {}
      }
    }
    try {
      const url = `/api/azure/disks/volumes/${selectedAccountId}?forceRefresh=${forceRefresh}`;
      const data = await apiClient.get(url);
      const diskList: AzureDisk[] = (Array.isArray(data) ? data : []).map(normalizeDisk);
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
      setHistoryError(null);
    } catch (err: any) {
      // Non-fatal for the page, but the History tab says so instead of "No Actions Yet".
      setHistoryError(err?.message ?? 'Action history could not be loaded');
    }
  }, [selectedAccountId]);

  const applyAction = useCallback(async (
    diskName: string, resourceGroup: string, actionType: string, targetSizeGb?: number
  ) => {
    if (!selectedAccountId) return undefined;
    const result = await apiClient.post<AzureDiskActionResult>(`/api/azure/disks/action/${selectedAccountId}`, {
      diskName, resourceGroup, actionType, targetSizeGb
    });
    sessionStorage.removeItem(getDiskCacheKey(selectedAccountId));
    await fetchDisks(true);
    await fetchHistory();
    // The backend reports a failed action as HTTP 200 with success=false. Ignoring the body made
    // the page announce "applied" for actions Azure had refused.
    if (result && result.success === false) {
      throw new Error(result.error || 'The action failed');
    }
    return result;
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

  return { disks, recommendations, actionHistory, isLoading, isRefreshing, error, historyError, fetchDisks, applyAction, refreshAll };
}
