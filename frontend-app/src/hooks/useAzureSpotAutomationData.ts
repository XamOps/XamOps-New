/**
 * Custom hook for Azure Spot Automation (VMSS) data fetching and management
 */
import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import {
    fetchAzureLaunchAnalytics,
    fetchAzureSettings,
    updateAzureRispRespectSettings,
    syncAzureSpotEvents,
    fetchAzureVmssSpotConfig,
    saveAzureVmssSpotConfig,
    fetchAzureVmssSkuConfig,
    saveAzureVmssSkuConfig,
} from '../api/azureDashboardApi';

export interface AzureVMSS {
    name: string;
    resourceGroup: string;
    region: string;
    isEnabled: boolean;
    aksManaged: boolean;
    instanceCount: number;
    spotCount: number;
    onDemandCount: number;
    vmSku: string;
    currentCost: number;
    actualSavings: number;
    potentialSavings: number;
    updating?: boolean;
}

export interface AzureSpotCosts {
    currentHourly: number;
    currentMonthlyCost: number;
    onDemandHourly: number;
    hourlyActualSavings: number;
}

export interface AzureSpotSavings {
    actualMonthlySavings: number;
    potentialMonthlySavings: number;
}

export interface AzureHistoryDataPoint {
    timestamp: string;
    total_actual_savings: number;
    total_potential_savings: number;
}

export interface AzureHistorySummary {
    total_savings: number;
    average_hourly_savings: number;
    peak_savings: number;
}

interface UseAzureSpotAutomationDataProps {
    selectedAccountId: string | null;
}

export const useAzureSpotAutomationData = ({ selectedAccountId }: UseAzureSpotAutomationDataProps) => {
    const [isLoading, setIsLoading] = useState(true);
    const [isLoadingHistory, setIsLoadingHistory] = useState(false);
    const [error, setError] = useState<Error | null>(null);

    const [vmssList, setVmssList] = useState<AzureVMSS[]>([]);
    const [costs, setCosts] = useState<AzureSpotCosts>({
        currentHourly: 0,
        currentMonthlyCost: 0,
        onDemandHourly: 0,
        hourlyActualSavings: 0,
    });
    const [savings, setSavings] = useState<AzureSpotSavings>({
        actualMonthlySavings: 0,
        potentialMonthlySavings: 0,
    });
    const [enabledRegionsCount, setEnabledRegionsCount] = useState(0);
    const [totalInstances, setTotalInstances] = useState(0);
    const [historyData, setHistoryData] = useState<AzureHistoryDataPoint[]>([]);
    const [historySummary, setHistorySummary] = useState<AzureHistorySummary>({
        total_savings: 0,
        average_hourly_savings: 0,
        peak_savings: 0,
    });

    const fetchData = useCallback(async () => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') {
            setIsLoading(false);
            return;
        }
        setIsLoading(true);
        setError(null);
        try {
            console.log('[AzureSpotAutomation] Fetching VMSS for account:', selectedAccountId);
            const data = await apiClient.get(`/api/azure/autospotting/vmss/all-regions/${selectedAccountId}`);

            const vmssData: AzureVMSS[] = data.map((vmss: any) => ({
                name: vmss.name,
                resourceGroup: vmss.resourceGroup,
                region: vmss.region,
                isEnabled: vmss.isEnabled,
                aksManaged: vmss.aksManaged ?? (
                    (typeof vmss.name === 'string' && vmss.name.toLowerCase().startsWith('aks-')) ||
                    (typeof vmss.resourceGroup === 'string' && vmss.resourceGroup.toUpperCase().startsWith('MC_'))
                ),
                instanceCount: vmss.instanceCount,
                spotCount: vmss.spotInstanceCount,
                onDemandCount: vmss.ondemandInstanceCount,
                vmSku: vmss.vmSku,
                currentCost: vmss.currentHourlyCost,
                actualSavings: vmss.actualHourlySavings,
                potentialSavings: vmss.potentialHourlySavings,
            }));

            setVmssList(vmssData);

            const totalCurrent = vmssData.reduce((sum, item) => sum + item.currentCost, 0);
            const totalActual = vmssData.reduce((sum, item) => sum + item.actualSavings, 0);
            const totalPotential = vmssData.reduce((sum, item) => sum + item.potentialSavings, 0);

            setCosts({
                currentHourly: totalCurrent,
                currentMonthlyCost: totalCurrent * 730,
                onDemandHourly: totalCurrent + totalActual,
                hourlyActualSavings: totalActual,
            });

            setSavings({
                actualMonthlySavings: totalActual * 730,
                potentialMonthlySavings: totalPotential * 730,
            });

            setEnabledRegionsCount(new Set(vmssData.filter(v => v.isEnabled).map(v => v.region)).size);
            setTotalInstances(vmssData.reduce((sum, v) => sum + v.instanceCount, 0));
        } catch (err) {
            console.error('[AzureSpotAutomation] Fetch error:', err);
            setError(err instanceof Error ? err : new Error('Failed to fetch data'));
        } finally {
            setIsLoading(false);
        }
    }, [selectedAccountId]);

    const fetchHistoryData = useCallback(async (range: string = 'last7d') => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') return;
        setIsLoadingHistory(true);
        try {
            const data = await apiClient.get(
                `/api/azure/autospotting/history/${selectedAccountId}?range=${range}`
            );
            setHistoryData(data.data_points || []);
            setHistorySummary(
                data.summary || { total_savings: 0, average_hourly_savings: 0, peak_savings: 0 }
            );
        } catch (err) {
            console.error('[AzureSpotAutomation] History fetch error:', err);
        } finally {
            setIsLoadingHistory(false);
        }
    }, [selectedAccountId]);

    const toggleVMSS = useCallback(
        async (vmss: AzureVMSS) => {
            if (!selectedAccountId || selectedAccountId === 'No account selected') return;
            const newState = !vmss.isEnabled;
            const endpoint = newState ? 'enable' : 'disable';

            // Optimistic update
            setVmssList(prev =>
                prev.map(v =>
                    v.name === vmss.name && v.resourceGroup === vmss.resourceGroup
                        ? { ...v, updating: true }
                        : v
                )
            );

            try {
                const params = new URLSearchParams({
                    subscriptionId: selectedAccountId,
                    resourceGroup: vmss.resourceGroup,
                    vmssName: vmss.name,
                });
                await apiClient.post(`/api/azure/autospotting/${endpoint}?${params.toString()}`, null);

                setVmssList(prev =>
                    prev.map(v =>
                        v.name === vmss.name && v.resourceGroup === vmss.resourceGroup
                            ? { ...v, isEnabled: newState, updating: false }
                            : v
                    )
                );
                return true;
            } catch (err) {
                // Revert optimistic update on error
                setVmssList(prev =>
                    prev.map(v =>
                        v.name === vmss.name && v.resourceGroup === vmss.resourceGroup
                            ? { ...v, updating: false }
                            : v
                    )
                );
                console.error('[AzureSpotAutomation] Toggle error:', err);
                throw err;
            }
        },
        [selectedAccountId]
    );

    // =================== LAUNCH ANALYTICS ===================

    const [launchAnalytics, setLaunchAnalytics] = useState<any>(null);
    const [isLoadingAnalytics, setIsLoadingAnalytics] = useState(false);

    const fetchLaunchAnalytics = useCallback(async (range: string = 'last7d') => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') return;
        setIsLoadingAnalytics(true);
        try {
            const data = await fetchAzureLaunchAnalytics(selectedAccountId, range);
            setLaunchAnalytics(data);
        } catch (err) {
            console.error('[AzureSpotAutomation] Launch analytics fetch error:', err);
        } finally {
            setIsLoadingAnalytics(false);
        }
    }, [selectedAccountId]);

    // =================== SETTINGS ===================

    const [settings, setSettings] = useState<any>(null);

    const fetchSettings = useCallback(async () => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') return;
        try {
            const data = await fetchAzureSettings(selectedAccountId);
            setSettings(data);
        } catch (err) {
            console.error('[AzureSpotAutomation] Settings fetch error:', err);
        }
    }, [selectedAccountId]);

    const saveRispSettings = useCallback(async (enabled: boolean, threshold: number) => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') return;
        const updated = await updateAzureRispRespectSettings(selectedAccountId, enabled, threshold);
        setSettings(updated);
    }, [selectedAccountId]);

    // =================== VMSS SPOT CONFIG ===================

    const getVmssSpotConfig = useCallback(async (resourceGroup: string, vmssName: string) => {
        if (!selectedAccountId) return null;
        return fetchAzureVmssSpotConfig(selectedAccountId, resourceGroup, vmssName);
    }, [selectedAccountId]);

    const saveVmssSpotConfig = useCallback(async (
        resourceGroup: string, vmssName: string,
        spotPercentage: number | null, onDemandCount: number | null
    ) => {
        if (!selectedAccountId) return null;
        return saveAzureVmssSpotConfig(selectedAccountId, resourceGroup, vmssName, spotPercentage, onDemandCount);
    }, [selectedAccountId]);

    const getVmssSkuConfig = useCallback(async (resourceGroup: string, vmssName: string) => {
        if (!selectedAccountId) return null;
        return fetchAzureVmssSkuConfig(selectedAccountId, resourceGroup, vmssName);
    }, [selectedAccountId]);

    const saveVmssSkuConfig = useCallback(async (
        resourceGroup: string, vmssName: string,
        allowedVmSkus: string, strategy: string
    ) => {
        if (!selectedAccountId) return null;
        return saveAzureVmssSkuConfig(selectedAccountId, resourceGroup, vmssName, allowedVmSkus, strategy);
    }, [selectedAccountId]);

    // =================== BACKFILL ===================

    const syncEvents = useCallback(async () => {
        if (!selectedAccountId) return null;
        return syncAzureSpotEvents(selectedAccountId);
    }, [selectedAccountId]);

    useEffect(() => {
        fetchData();
        fetchHistoryData();
    }, [fetchData, fetchHistoryData]);

    return {
        vmssList,
        costs,
        savings,
        enabledRegionsCount,
        totalInstances,
        historyData,
        historySummary,
        isLoading,
        isLoadingHistory,
        error,
        refresh: fetchData,
        fetchHistoryData,
        toggleVMSS,
        // Launch analytics
        launchAnalytics,
        isLoadingAnalytics,
        fetchLaunchAnalytics,
        // Settings
        settings,
        fetchSettings,
        saveRispSettings,
        // VMSS config
        getVmssSpotConfig,
        saveVmssSpotConfig,
        getVmssSkuConfig,
        saveVmssSkuConfig,
        // Backfill
        syncEvents,
    };
};
