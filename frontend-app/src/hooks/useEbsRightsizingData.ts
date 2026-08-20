/**
 * useEbsRightsizingData Hook
 * Manages EBS/Disk rightsizing data: volumes, recommendations, policies, action history, ROI.
 */

import { useState, useEffect, useCallback, useRef } from 'react';

export interface EbsVolume {
  volumeId: string;
  region: string;
  volumeType: string;
  sizeGb: number;
  state: string;
  attached: boolean;
  instanceId?: string;
  device?: string;
  isRootVolume?: boolean;
  volumeName?: string;
  encrypted: boolean;
  monthlyCost: number;
  usedPercent?: number;
  usedGb?: number;
  freeGb?: number;
  agentDeployed?: boolean;
  recommendation: 'DELETE_UNATTACHED' | 'UPGRADE_TYPE' | 'EXPAND' | 'SHRINK' | 'NONE';
  recommendationReason?: string;
  recommendedType: string;
  recommendedSizeGb: number;
  estimatedSavings: number;
  asgName?: string;
}

export interface DiskPolicy {
  id?: number;
  policyName: string;
  desiredUtilization: number;
  minDiskSizeGb: number;
  maxDiskCount: number;
  policyType: 'STANDARD' | 'IOPS_OPTIMIZED' | 'CUSTOM';
  targetDiskType?: string;
  cloudProvider: string;
  isDefault: boolean;
  createdAt?: string;
}

export interface EbsVolumeAction {
  id: number;
  volumeId: string;
  region: string;
  actionType: string;
  oldType?: string;
  newType?: string;
  oldSizeGb?: number;
  newSizeGb?: number;
  estimatedSavings?: number;
  status: 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED';
  errorMessage?: string;
  initiatedAt: string;
  completedAt?: string;
}

export interface PendingDeletionVolume {
  volumeId: string;
  region: string;
  volumeType: string;
  sizeGb: number;
  state: string;
  monthlyCost: number;
  deleteAfter?: string;
  sourceVolumeId?: string;
}

export interface RoiResult {
  provisionedGb: number;
  utilizationPct: number;
  targetGb: number;
  currentType: string;
  recommendedType: string;
  currentCostPerMonth: number;
  newCostPerMonth: number;
  monthlySavings: number;
  savingsPct: number;
  firstYearSavings: number;
  fiveYearSavings: number;
}

interface UseEbsRightsizingDataOptions {
  selectedAccountId: string | null;
}

export const useEbsRightsizingData = ({ selectedAccountId }: UseEbsRightsizingDataOptions) => {
  const [volumes, setVolumes] = useState<EbsVolume[]>([]);
  const [recommendations, setRecommendations] = useState<EbsVolume[]>([]);
  const [policies, setPolicies] = useState<DiskPolicy[]>([]);
  const [actionHistory, setActionHistory] = useState<EbsVolumeAction[]>([]);
  const [pendingDeletion, setPendingDeletion] = useState<PendingDeletionVolume[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isDiskUsageLoading, setIsDiskUsageLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [ssmUnavailableInstances, setSsmUnavailableInstances] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [inFlightActions, setInFlightActions] = useState<Map<string, EbsVolumeAction>>(new Map());
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchRecommendations = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) return;
    const cacheKey = `ebs-recommendations-${selectedAccountId}`;
    if (!forceRefresh) {
      const cached = sessionStorage.getItem(cacheKey);
      if (cached) {
        try {
          setRecommendations(JSON.parse(cached));
          return;
        } catch {}
      }
    }
    setIsLoading(true);
    try {
      const res = await fetch(
        `/api/ebs/recommendations/${selectedAccountId}?forceRefresh=${forceRefresh}`,
        { credentials: 'include' }
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: EbsVolume[] = await res.json();
      setRecommendations(data);
      sessionStorage.setItem(cacheKey, JSON.stringify(data));
    } catch (e) {
      console.error('[EBS] Failed to fetch recommendations:', e);
      setError('Failed to load recommendations');
    } finally {
      setIsLoading(false);
    }
  }, [selectedAccountId]);

  const fetchVolumes = useCallback(async (forceRefresh = false): Promise<EbsVolume[]> => {
    if (!selectedAccountId) return [];
    const cacheKey = `ebs-volumes-${selectedAccountId}`;
    if (!forceRefresh) {
      const cached = sessionStorage.getItem(cacheKey);
      if (cached) {
        try {
          const data = JSON.parse(cached) as EbsVolume[];
          setVolumes(data);
          return data;
        } catch {}
      }
    }
    setIsLoading(true);
    try {
      const res = await fetch(
        `/api/ebs/volumes/${selectedAccountId}?forceRefresh=${forceRefresh}`,
        { credentials: 'include' }
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data: EbsVolume[] = await res.json();
      setVolumes(data);
      sessionStorage.setItem(cacheKey, JSON.stringify(data));
      return data;
    } catch (e) {
      console.error('[EBS] Failed to fetch volumes:', e);
      return [];
    } finally {
      setIsLoading(false);
    }
  }, [selectedAccountId]);

  const fetchPolicies = useCallback(async () => {
    if (!selectedAccountId) return;
    try {
      const res = await fetch(`/api/ebs/policies/${selectedAccountId}`, { credentials: 'include' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setPolicies(await res.json());
    } catch (e) {
      console.error('[EBS] Failed to fetch policies:', e);
    }
  }, [selectedAccountId]);

  const fetchActionHistory = useCallback(async () => {
    if (!selectedAccountId) return;
    try {
      const res = await fetch(`/api/ebs/action-history/${selectedAccountId}`, { credentials: 'include' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setActionHistory(await res.json());
    } catch (e) {
      console.error('[EBS] Failed to fetch action history:', e);
    }
  }, [selectedAccountId]);

  const startPollingForVolume = useCallback((_volumeId: string) => {
    if (pollingRef.current) clearInterval(pollingRef.current);
    pollingRef.current = setInterval(async () => {
      if (!selectedAccountId) return;
      try {
        const res = await fetch(`/api/ebs/action-history/${selectedAccountId}`, { credentials: 'include' });
        if (!res.ok) return;
        const history: EbsVolumeAction[] = await res.json();
        const active = history.filter(
          (a) => a.status === 'PENDING' || a.status === 'IN_PROGRESS'
        );
        const newMap = new Map(active.map((a) => [a.volumeId, a]));
        setInFlightActions(newMap);
        setActionHistory(history);
        if (active.length === 0) {
          clearInterval(pollingRef.current!);
          pollingRef.current = null;
          fetchVolumes(true);
        }
      } catch {
        // ignore polling errors
      }
    }, 5_000);
  }, [selectedAccountId, fetchVolumes]);

  useEffect(() => {
    return () => {
      if (pollingRef.current) clearInterval(pollingRef.current);
    };
  }, [selectedAccountId]);

  const applyAction = useCallback(async (
    volumeId: string, region: string, actionType: string,
    targetType?: string, targetSizeGb?: number,
    instanceId?: string, asgName?: string
  ) => {
    if (!selectedAccountId) return { success: false, error: 'No account selected' };
    try {
      const res = await fetch(`/api/ebs/action/${selectedAccountId}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ volumeId, region, actionType, targetType, targetSizeGb, instanceId, asgName }),
      });
      const data = await res.json();
      if (data.success) {
        startPollingForVolume(volumeId);
        // Refresh recommendations and history after successful action
        sessionStorage.removeItem(`ebs-recommendations-${selectedAccountId}`);
        sessionStorage.removeItem(`ebs-volumes-${selectedAccountId}`);
        await Promise.all([fetchRecommendations(true), fetchActionHistory()]);
      }
      return data;
    } catch (e) {
      console.error('[EBS] Failed to apply action:', e);
      return { success: false, error: String(e) };
    }
  }, [selectedAccountId, fetchRecommendations, fetchActionHistory, startPollingForVolume]);

  const createPolicy = useCallback(async (policy: DiskPolicy) => {
    if (!selectedAccountId) return;
    const res = await fetch(`/api/ebs/policies/${selectedAccountId}`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(policy),
    });
    const data = await res.json();
    await fetchPolicies();
    return data;
  }, [selectedAccountId, fetchPolicies]);

  const updatePolicy = useCallback(async (policyId: number, policy: DiskPolicy) => {
    const res = await fetch(`/api/ebs/policies/${policyId}`, {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(policy),
    });
    const data = await res.json();
    await fetchPolicies();
    return data;
  }, [fetchPolicies]);

  const deletePolicy = useCallback(async (policyId: number) => {
    await fetch(`/api/ebs/policies/${policyId}`, { method: 'DELETE', credentials: 'include' });
    await fetchPolicies();
  }, [fetchPolicies]);

  const initiateDownsize = useCallback(async (
    volumeId: string, region: string, instanceId: string,
    mountPoint: string, targetSizeGb: number
  ) => {
    if (!selectedAccountId) return { success: false, error: 'No account selected' };
    try {
      const res = await fetch(`/api/ebs/shrink/${selectedAccountId}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ volumeId, region, instanceId, mountPoint, targetSizeGb }),
      });
      const data = await res.json();
      if (data.success) {
        startPollingForVolume(volumeId);
        await fetchActionHistory();
      }
      return data;
    } catch (e) {
      return { success: false, error: String(e) };
    }
  }, [selectedAccountId, fetchActionHistory, startPollingForVolume]);

  const initiateExpand = useCallback(
    async (
      volumeId: string,
      region: string,
      instanceId: string,
      device: string,
      targetSizeGb: number,
      asgName?: string
    ) => {
      if (!selectedAccountId) return { success: false, error: 'No account selected' };
      try {
        const res = await fetch(`/api/ebs/expand/${selectedAccountId}`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ volumeId, region, instanceId, device, targetSizeGb, asgName }),
        });
        const data = await res.json();
        if (data.success) {
          startPollingForVolume(volumeId);
          await fetchActionHistory();
        }
        return data;
      } catch (e: any) {
        return { success: false, error: String(e) };
      }
    },
    [selectedAccountId, startPollingForVolume, fetchActionHistory]
  );

  const calculateRoi = useCallback(async (
    provisionedGb: number, utilizationPct: number,
    growthRatePct: number, diskType: string, region?: string
  ): Promise<RoiResult | null> => {
    try {
      const res = await fetch('/api/ebs/roi/calculate', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provisionedGb, utilizationPct, growthRatePct, diskType, region }),
      });
      return await res.json();
    } catch (e) {
      console.error('[EBS] ROI calculation failed:', e);
      return null;
    }
  }, []);

  const fetchDiskUsage = useCallback(async (vols: EbsVolume[]) => {
    if (!selectedAccountId || !vols.length) return;

    // Group attached volumes by region → unique instanceIds
    const byRegion: Record<string, string[]> = {};
    vols.forEach(vol => {
      if (vol.instanceId && vol.region) {
        if (!byRegion[vol.region]) byRegion[vol.region] = [];
        if (!byRegion[vol.region].includes(vol.instanceId)) {
          byRegion[vol.region].push(vol.instanceId);
        }
      }
    });
    if (Object.keys(byRegion).length === 0) return;

    setIsDiskUsageLoading(true);
    try {
      // Fire one request per region in parallel; each blocks ~5–30s on SSM
      const regionResults = await Promise.all(
        Object.entries(byRegion).map(async ([region, instanceIds]) => {
          try {
            const res = await fetch(`/api/ebs/disk-usage/${selectedAccountId}`, {
              method: 'POST',
              credentials: 'include',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ region, instanceIds }),
            });
            if (!res.ok) return null;
            const raw = await res.json();
            // Extract metadata keys, leave only instance-keyed data
            const unavailable: string[] = raw['__ssmUnavailable'] ?? [];
            const available: string[]   = raw['__ssmAvailable']   ?? [];
            // eslint-disable-next-line @typescript-eslint/no-unused-vars
            const { __ssmUnavailable, __ssmAvailable, ...instanceData } = raw;
            return { region, data: instanceData, unavailable, available };
          } catch {
            return null;
          }
        })
      );

      // Accumulate SSM availability across all regions
      const allUnavailable: string[] = [];
      regionResults.forEach(r => { if (r) allUnavailable.push(...r.unavailable); });
      setSsmUnavailableInstances(allUnavailable);

      const updates: Record<string, Record<string, Record<string, { usedGb: number; freeGb: number; usedPercent: number }>>> = {};
      regionResults.forEach(r => { if (r) updates[r.region] = r.data; });

      if (Object.keys(updates).length === 0) return;

      // Compute new volumes with disk usage + utilization-based recommendations
      const newVolumes = vols.map(vol => {
        if (!vol.instanceId || !vol.device) return vol;
        const regionData = updates[vol.region];
        if (!regionData) return vol;
        const instanceData = regionData[vol.instanceId];
        if (!instanceData) return vol;

        // 1. Exact vol-id match (NVMe instances where SSM script resolved the EBS volume ID)
        let matchKey: string | undefined = Object.keys(instanceData).find(k =>
          k === vol.volumeId || k.replace('vol-', '') === vol.volumeId.replace('vol-', '')
        );

        // 2. Device-name prefix match (/dev/xvda → /dev/xvda1)
        if (!matchKey) {
          const baseDevice = vol.device.replace('/dev/', '');
          matchKey = Object.keys(instanceData).find(dev => {
            const base = dev.replace('/dev/', '');
            return base.startsWith(baseDevice) || baseDevice.startsWith(base);
          });
        }

        // 3. Size-based fallback (NVMe: /dev/nvme0n1p1 with no vol-id symlink)
        if (!matchKey) {
          let bestDiff = 6; // 5 GB tolerance
          for (const [dev, devData] of Object.entries(instanceData) as [string, any][]) {
            const reportedTotal = (devData.totalGb ?? (devData.usedGb + devData.freeGb)) as number;
            const diff = Math.abs(reportedTotal - vol.sizeGb);
            if (diff < bestDiff) { bestDiff = diff; matchKey = dev; }
          }
        }

        if (!matchKey) return vol;

        const { usedGb, freeGb, usedPercent } = instanceData[matchKey] as { usedGb: number; freeGb: number; totalGb: number; usedPercent: number };
        let updated: EbsVolume = { ...vol, usedGb, freeGb, usedPercent };

        // Derive utilization-based recommendations (only when current is NONE and volume is in-use)
        if (updated.recommendation === 'NONE' && updated.state === 'in-use') {
          if (usedPercent > 80) {
            const targetSizeGb = Math.ceil(updated.sizeGb * 1.5);
            updated = {
              ...updated,
              recommendation: 'EXPAND',
              recommendationReason: `Volume is ${usedPercent.toFixed(0)}% full — expand from ${updated.sizeGb}GB to ${targetSizeGb}GB to prevent disruption`,
              recommendedSizeGb: targetSizeGb,
              estimatedSavings: 0,
            };
          } else if (usedPercent < 25 && updated.sizeGb >= 20) {
            const usedGbApprox = updated.sizeGb * usedPercent / 100;
            const targetSizeGb = Math.max(10, Math.ceil(usedGbApprox / 0.4));
            const savings = (updated.sizeGb - targetSizeGb) * 0.08;
            if (targetSizeGb < updated.sizeGb * 0.7 && savings > 0.5) {
              const reason = updated.isRootVolume
                ? `Root volume is only ${usedPercent.toFixed(0)}% used — migrate to new ${targetSizeGb}GB volume to save $${savings.toFixed(2)}/mo (requires instance stop)`
                : `Volume is only ${usedPercent.toFixed(0)}% used — shrink from ${updated.sizeGb}GB to ${targetSizeGb}GB to save $${savings.toFixed(2)}/mo`;
              updated = {
                ...updated,
                recommendation: 'SHRINK',
                recommendationReason: reason,
                recommendedSizeGb: targetSizeGb,
                estimatedSavings: parseFloat(savings.toFixed(2)),
              };
            }
          }
        }
        return updated;
      });

      setVolumes(newVolumes);

      // Merge utilization-derived recommendations into recommendations state
      const volIdsWithUsage = new Set(newVolumes.filter(v => v.usedPercent !== undefined).map(v => v.volumeId));
      setRecommendations(prev => {
        const kept = prev.filter(r => !volIdsWithUsage.has(r.volumeId));
        const newRecs = newVolumes.filter(v => volIdsWithUsage.has(v.volumeId) && v.recommendation !== 'NONE');
        return [...kept, ...newRecs];
      });
    } finally {
      setIsDiskUsageLoading(false);
    }
  }, [selectedAccountId]);

  const fetchPendingDeletion = useCallback(async () => {
    if (!selectedAccountId) return;
    try {
      const res = await fetch(`/api/ebs/pending-deletion/${selectedAccountId}`, { credentials: 'include' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setPendingDeletion(await res.json());
    } catch (e) {
      console.error('[EBS] Failed to fetch pending-deletion volumes:', e);
    }
  }, [selectedAccountId]);

  const deletePendingVolume = useCallback(async (volumeId: string, region: string) => {
    if (!selectedAccountId) return { success: false, error: 'No account selected' };
    try {
      const res = await fetch(`/api/ebs/pending-deletion/${selectedAccountId}`, {
        method: 'DELETE',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ volumeId, region }),
      });
      const data = await res.json();
      if (data.success) await fetchPendingDeletion();
      return data;
    } catch (e) {
      return { success: false, error: String(e) };
    }
  }, [selectedAccountId, fetchPendingDeletion]);

  const keepVolume = useCallback(async (volumeId: string, region: string) => {
    if (!selectedAccountId) return { success: false, error: 'No account selected' };
    try {
      const res = await fetch(`/api/ebs/pending-deletion/${selectedAccountId}/keep`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ volumeId, region }),
      });
      const data = await res.json();
      if (data.success) await fetchPendingDeletion();
      return data;
    } catch (e) {
      return { success: false, error: String(e) };
    }
  }, [selectedAccountId, fetchPendingDeletion]);

  const fixSsmPermissions = useCallback(async (region: string, instanceIds: string[]) => {
    if (!selectedAccountId) return { success: false, error: 'No account selected' };
    try {
      const res = await fetch(`/api/ebs/ssm/fix/${selectedAccountId}`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ region, instanceIds }),
      });
      return await res.json();
    } catch (e) {
      return { success: false, error: String(e) };
    }
  }, [selectedAccountId]);

  const refreshAll = useCallback(async () => {
    setIsRefreshing(true);
    const [vols] = await Promise.all([
      fetchVolumes(true),
      fetchRecommendations(true),
      fetchPolicies(),
      fetchActionHistory(),
      fetchPendingDeletion(),
    ]);
    if (vols && vols.length > 0) fetchDiskUsage(vols);
    setIsRefreshing(false);
  }, [fetchVolumes, fetchRecommendations, fetchPolicies, fetchActionHistory, fetchPendingDeletion, fetchDiskUsage]);

  useEffect(() => {
    if (selectedAccountId) {
      const init = async () => {
        const vols = await fetchVolumes();
        if (vols && vols.length > 0) fetchDiskUsage(vols);
      };
      init();
      fetchRecommendations();
      fetchPolicies();
      fetchActionHistory();
      fetchPendingDeletion();
    }
  }, [selectedAccountId]);

  // Derived stats
  const totalWastedMonthly = recommendations.reduce((sum, r) => sum + (r.estimatedSavings || 0), 0);
  const unattachedCount  = recommendations.filter(r => r.recommendation === 'DELETE_UNATTACHED').length;
  const typeUpgradeCount = recommendations.filter(r => r.recommendation === 'UPGRADE_TYPE').length;
  const shrinkCount      = recommendations.filter(r => r.recommendation === 'SHRINK').length;
  const expandCount      = recommendations.filter(r => r.recommendation === 'EXPAND').length;

  return {
    volumes,
    recommendations,
    policies,
    actionHistory,
    pendingDeletion,
    isLoading,
    isDiskUsageLoading,
    isRefreshing,
    ssmUnavailableInstances,
    error,
    totalWastedMonthly,
    unattachedCount,
    typeUpgradeCount,
    shrinkCount,
    expandCount,
    inFlightActions,
    applyAction,
    initiateDownsize,
    initiateExpand,
    startPollingForVolume,
    createPolicy,
    updatePolicy,
    deletePolicy,
    calculateRoi,
    refreshAll,
    fixSsmPermissions,
    fetchActionHistory,
    fetchPendingDeletion,
    deletePendingVolume,
    keepVolume,
  };
};
