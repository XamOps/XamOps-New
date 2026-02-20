/**
 * AutoSpotting Cache Context
 * 
 * Provides caching for AutoSpotting dashboard data to prevent re-fetching
 * when navigating between tabs or pages.
 */

import React, { createContext, useContext, useState, useCallback, ReactNode, useEffect } from 'react';
import { CustomerDashboardData, AdminDashboardData, SpotAutomationCacheData } from '../types/autospotting';
import { useAuth } from './AuthContext';

interface CacheEntry<T> {
    data: T;
    timestamp: number;
}

interface AutoSpottingCacheContextType {
    customerCache: Map<string, CacheEntry<CustomerDashboardData>>;
    adminCache: Map<string, CacheEntry<AdminDashboardData>>;
    spotAutomationCache: Map<string, CacheEntry<SpotAutomationCacheData>>;
    setCustomerData: (accountId: string, data: CustomerDashboardData) => void;
    getCustomerData: (accountId: string) => CustomerDashboardData | null;
    setAdminData: (range: string, data: AdminDashboardData) => void;
    getAdminData: (range: string) => AdminDashboardData | null;
    setSpotAutomationData: (accountId: string, data: SpotAutomationCacheData) => void;
    getSpotAutomationData: (accountId: string) => SpotAutomationCacheData | null;
    clearCache: () => void;
}

const AutoSpottingCacheContext = createContext<AutoSpottingCacheContextType | null>(null);

const CACHE_TTL = 5 * 60 * 1000; // 5 minutes

export const AutoSpottingCacheProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
    const { userProfile } = useAuth();
    const [customerCache, setCustomerCache] = useState<Map<string, CacheEntry<CustomerDashboardData>>>(new Map());
    const [adminCache, setAdminCache] = useState<Map<string, CacheEntry<AdminDashboardData>>>(new Map());
    const [spotAutomationCache, setSpotAutomationCache] = useState<Map<string, CacheEntry<SpotAutomationCacheData>>>(new Map());

    // Clear cache on logout
    useEffect(() => {
        if (!userProfile) {
            setCustomerCache(new Map());
            setAdminCache(new Map());
            setSpotAutomationCache(new Map());
        }
    }, [userProfile]);

    const setCustomerData = useCallback((accountId: string, data: CustomerDashboardData) => {
        setCustomerCache(prev => {
            const next = new Map(prev);
            next.set(accountId, { data, timestamp: Date.now() });
            return next;
        });
    }, []);

    const getCustomerData = useCallback((accountId: string) => {
        const entry = customerCache.get(accountId);
        if (!entry) return null;
        if (Date.now() - entry.timestamp > CACHE_TTL) return null;
        return entry.data;
    }, [customerCache]);

    const setAdminData = useCallback((range: string, data: AdminDashboardData) => {
        setAdminCache(prev => {
            const next = new Map(prev);
            next.set(range, { data, timestamp: Date.now() });
            return next;
        });
    }, []);

    const getAdminData = useCallback((range: string) => {
        const entry = adminCache.get(range);
        if (!entry) return null;
        if (Date.now() - entry.timestamp > CACHE_TTL) return null;
        return entry.data;
    }, [adminCache]);

    const setSpotAutomationData = useCallback((accountId: string, data: SpotAutomationCacheData) => {
        setSpotAutomationCache(prev => {
            const next = new Map(prev);
            next.set(accountId, { data, timestamp: Date.now() });
            return next;
        });
    }, []);

    const getSpotAutomationData = useCallback((accountId: string) => {
        const entry = spotAutomationCache.get(accountId);
        if (!entry) return null;
        if (Date.now() - entry.timestamp > CACHE_TTL) return null;
        return entry.data;
    }, [spotAutomationCache]);

    const clearCache = useCallback(() => {
        setCustomerCache(new Map());
        setAdminCache(new Map());
        setSpotAutomationCache(new Map());
    }, []);

    return (
        <AutoSpottingCacheContext.Provider value={{
            customerCache,
            adminCache,
            spotAutomationCache,
            setCustomerData,
            getCustomerData,
            setAdminData,
            getAdminData,
            setSpotAutomationData,
            getSpotAutomationData,
            clearCache
        }}>
            {children}
        </AutoSpottingCacheContext.Provider>
    );
};

export const useAutoSpottingCache = () => {
    const context = useContext(AutoSpottingCacheContext);
    if (!context) {
        throw new Error('useAutoSpottingCache must be used within an AutoSpottingCacheProvider');
    }
    return context;
};

export default AutoSpottingCacheContext;
