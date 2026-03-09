/**
 * Custom hook for GCP Spot Automation (MIGs) data fetching and management
 */
import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';

export interface GcpMIG {
    name: string;
    region: string;
    isEnabled: boolean;
    instanceCount: number;
    spotCount: number;
    onDemandCount: number;
    currentCost: number;
    savings: number;
    potentialSavings: number;
    gkeManagedPool: boolean;
    updating?: boolean;
}

export interface GcpSpotCosts {
    currentHourly: number;
    currentMonthlyCost: number;
    onDemandHourly: number;
    hourlyActualSavings: number;
}

export interface GcpSpotSavings {
    actualMonthlySavings: number;
    potentialMonthlySavings: number;
}

export interface GcpHistoryDataPoint {
    timestamp: string;
    total_actual_savings: number;
    total_potential_savings: number;
}

export interface GcpHistorySummary {
    total_savings: number;
    average_hourly_savings: number;
    peak_savings: number;
}

interface UseGcpSpotAutomationDataProps {
    selectedAccountId: string | null;
}

export const useGcpSpotAutomationData = ({ selectedAccountId }: UseGcpSpotAutomationDataProps) => {
    const [isLoading, setIsLoading] = useState(true);
    const [isLoadingHistory, setIsLoadingHistory] = useState(false);
    const [error, setError] = useState<Error | null>(null);

    const [migs, setMigs] = useState<GcpMIG[]>([]);
    const [costs, setCosts] = useState<GcpSpotCosts>({
        currentHourly: 0,
        currentMonthlyCost: 0,
        onDemandHourly: 0,
        hourlyActualSavings: 0,
    });
    const [savings, setSavings] = useState<GcpSpotSavings>({
        actualMonthlySavings: 0,
        potentialMonthlySavings: 0,
    });
    const [enabledLocationsCount, setEnabledLocationsCount] = useState(0);
    const [totalInstances, setTotalInstances] = useState(0);
    const [historyData, setHistoryData] = useState<GcpHistoryDataPoint[]>([]);
    const [historySummary, setHistorySummary] = useState<GcpHistorySummary>({
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
            console.log('[GcpSpotAutomation] Fetching MIGs for account:', selectedAccountId);
            const data = await apiClient.get(`/api/gcp/autospotting/migs/all-regions/${selectedAccountId}`);

            const migData: GcpMIG[] = data.map((mig: any) => ({
                name: mig.name,
                region: mig.region,
                isEnabled: mig.isEnabled,
                instanceCount: mig.instanceCount,
                spotCount: mig.spotInstanceCount,
                onDemandCount: mig.ondemandInstanceCount,
                currentCost: mig.currentHourlyCost,
                savings: mig.actualHourlySavings,
                potentialSavings: mig.potentialHourlySavings,
                gkeManagedPool: mig.gkeManagedPool ?? (typeof mig.name === 'string' && mig.name.startsWith('gke-')),
            }));

            setMigs(migData);

            const totalCurrent = migData.reduce((sum, item) => sum + item.currentCost, 0);
            const totalSavings = migData.reduce((sum, item) => sum + item.savings, 0);
            // GKE-managed pools cannot be converted to Spot — exclude from potential savings
            const totalPotential = migData
                .filter(item => !item.gkeManagedPool)
                .reduce((sum, item) => sum + item.potentialSavings, 0);

            setCosts({
                currentHourly: totalCurrent,
                currentMonthlyCost: totalCurrent * 730,
                onDemandHourly: totalCurrent + totalSavings,
                hourlyActualSavings: totalSavings,
            });

            setSavings({
                actualMonthlySavings: totalSavings * 730,
                potentialMonthlySavings: totalPotential * 730,
            });

            setEnabledLocationsCount(new Set(migData.map(m => m.region)).size);
            setTotalInstances(migData.reduce((sum, m) => sum + m.instanceCount, 0));
        } catch (err) {
            console.error('[GcpSpotAutomation] Fetch error:', err);
            setError(err instanceof Error ? err : new Error('Failed to fetch data'));
        } finally {
            setIsLoading(false);
        }
    }, [selectedAccountId]);

    const fetchHistoryData = useCallback(async (range: string = 'last7d') => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') return;
        setIsLoadingHistory(true);
        try {
            const data = await apiClient.get(`/api/gcp/autospotting/history/${selectedAccountId}?range=${range}`);
            setHistoryData(data.data_points || []);
            setHistorySummary(data.summary || { total_savings: 0, average_hourly_savings: 0, peak_savings: 0 });
        } catch (err) {
            console.error('[GcpSpotAutomation] History fetch error:', err);
        } finally {
            setIsLoadingHistory(false);
        }
    }, [selectedAccountId]);

    const toggleMIG = useCallback(async (mig: GcpMIG) => {
        if (!selectedAccountId || selectedAccountId === 'No account selected') return;
        const newState = !mig.isEnabled;
        const endpoint = newState ? 'enable' : 'disable';

        // Mark this MIG as updating (shows spinner, disables toggle)
        setMigs(prev => prev.map(m =>
            m.name === mig.name && m.region === mig.region ? { ...m, updating: true } : m
        ));

        try {
            const params = new URLSearchParams({
                accountId: selectedAccountId,
                region: mig.region,
                migName: mig.name
            });
            await apiClient.post(`/api/gcp/autospotting/${endpoint}?${params.toString()}`, null);

            setMigs(prev => prev.map(m =>
                m.name === mig.name && m.region === mig.region ? { ...m, isEnabled: newState, updating: false } : m
            ));

            return true;
        } catch (err) {
            console.error('[GcpSpotAutomation] Toggle error:', err);
            // Clear updating state on error so the toggle is re-enabled
            setMigs(prev => prev.map(m =>
                m.name === mig.name && m.region === mig.region ? { ...m, updating: false } : m
            ));
            throw err;
        }
    }, [selectedAccountId]);

    useEffect(() => {
        fetchData();
        fetchHistoryData();
    }, [fetchData, fetchHistoryData]);

    return {
        migs,
        costs,
        savings,
        enabledLocationsCount,
        totalInstances,
        historyData,
        historySummary,
        isLoading,
        isLoadingHistory,
        error,
        refresh: fetchData,
        fetchHistoryData,
        toggleMIG,
    };
};
