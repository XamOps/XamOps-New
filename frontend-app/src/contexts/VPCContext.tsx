/**
 * VPC Context
 * 
 * Provides cached VPC data for AWS and GCP across the application
 * Reduces redundant API calls by caching VPC data in memory
 */

import React, { createContext, useContext, useState, useCallback, useRef, useMemo } from 'react';

// Types
export interface VPC {
    id: string;
    name: string;
    region: string;
}

export interface GcpVPC {
    id: string;
    name: string;
}

interface VPCCache<T> {
    data: T[];
    timestamp: number;
    accountId: string;
}

interface VPCContextType {
    // AWS VPCs
    awsVpcs: VPC[];
    awsVpcsLoading: boolean;
    awsVpcsError: string | null;
    fetchAwsVpcs: (accountId: string, forceRefresh?: boolean) => Promise<VPC[]>;

    // GCP VPCs  
    gcpVpcs: GcpVPC[];
    gcpVpcsLoading: boolean;
    gcpVpcsError: string | null;
    fetchGcpVpcs: (accountId: string, forceRefresh?: boolean) => Promise<GcpVPC[]>;

    // Clear cache
    clearCache: () => void;
}

const VPCContext = createContext<VPCContextType | undefined>(undefined);

// Cache TTL: 5 minutes
const CACHE_TTL = 5 * 60 * 1000;

export const VPCProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
    // AWS VPC State
    const [awsVpcs, setAwsVpcs] = useState<VPC[]>([]);
    const [awsVpcsLoading, setAwsVpcsLoading] = useState(false);
    const [awsVpcsError, setAwsVpcsError] = useState<string | null>(null);
    const awsCacheRef = useRef<VPCCache<VPC> | null>(null);

    // GCP VPC State
    const [gcpVpcs, setGcpVpcs] = useState<GcpVPC[]>([]);
    const [gcpVpcsLoading, setGcpVpcsLoading] = useState(false);
    const [gcpVpcsError, setGcpVpcsError] = useState<string | null>(null);
    const gcpCacheRef = useRef<VPCCache<GcpVPC> | null>(null);

    /**
     * Check if cache is valid
     */
    const isCacheValid = <T,>(cache: VPCCache<T> | null, accountId: string): boolean => {
        if (!cache) return false;
        if (cache.accountId !== accountId) return false;
        const age = Date.now() - cache.timestamp;
        return age < CACHE_TTL;
    };

    /**
     * Fetch AWS VPCs
     */
    const fetchAwsVpcs = useCallback(async (accountId: string, forceRefresh: boolean = false): Promise<VPC[]> => {
        // Check cache first (unless force refresh)
        if (!forceRefresh && isCacheValid(awsCacheRef.current, accountId)) {
            console.log('✅ [VPCContext] Using cached AWS VPCs for account:', accountId);
            return awsCacheRef.current!.data;
        }

        console.log('🔄 [VPCContext] Fetching AWS VPCs from API for account:', accountId);
        setAwsVpcsLoading(true);
        setAwsVpcsError(null);

        try {
            const response = await fetch(`/api/xamops/cloudmap/vpcs?accountId=${accountId}`, {
                credentials: 'include',
            });

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const data = await response.json();

            // Update cache
            awsCacheRef.current = {
                data,
                timestamp: Date.now(),
                accountId,
            };

            setAwsVpcs(data);
            console.log(`✅ [VPCContext] Cached ${data.length} AWS VPCs for account:`, accountId);

            return data;
        } catch (error) {
            const errorMessage = error instanceof Error ? error.message : 'Failed to load AWS VPCs';
            console.error('❌ [VPCContext] Error loading AWS VPCs:', error);
            setAwsVpcsError(errorMessage);
            setAwsVpcs([]);
            throw error;
        } finally {
            setAwsVpcsLoading(false);
        }
    }, []);

    /**
     * Fetch GCP VPCs
     */
    const fetchGcpVpcs = useCallback(async (accountId: string, forceRefresh: boolean = false): Promise<GcpVPC[]> => {
        // Check cache first (unless force refresh)
        if (!forceRefresh && isCacheValid(gcpCacheRef.current, accountId)) {
            console.log('✅ [VPCContext] Using cached GCP VPCs for account:', accountId);
            return gcpCacheRef.current!.data;
        }

        console.log('🔄 [VPCContext] Fetching GCP VPCs from API for account:', accountId);
        setGcpVpcsLoading(true);
        setGcpVpcsError(null);

        try {
            const response = await fetch(`/api/xamops/gcp/cloudmap/vpcs?accountId=${accountId}`, {
                credentials: 'include',
            });

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const data = await response.json();

            // Update cache
            gcpCacheRef.current = {
                data,
                timestamp: Date.now(),
                accountId,
            };

            setGcpVpcs(data);
            console.log(`✅ [VPCContext] Cached ${data.length} GCP VPCs for account:`, accountId);

            return data;
        } catch (error) {
            const errorMessage = error instanceof Error ? error.message : 'Failed to load GCP VPCs';
            console.error('❌ [VPCContext] Error loading GCP VPCs:', error);
            setGcpVpcsError(errorMessage);
            setGcpVpcs([]);
            throw error;
        } finally {
            setGcpVpcsLoading(false);
        }
    }, []);

    /**
     * Clear all caches
     */
    const clearCache = useCallback(() => {
        console.log('🗑️ [VPCContext] Clearing all VPC caches');
        awsCacheRef.current = null;
        gcpCacheRef.current = null;
        setAwsVpcs([]);
        setGcpVpcs([]);
        setAwsVpcsError(null);
        setGcpVpcsError(null);
    }, []);

    const value: VPCContextType = useMemo(() => ({
        awsVpcs,
        awsVpcsLoading,
        awsVpcsError,
        fetchAwsVpcs,
        gcpVpcs,
        gcpVpcsLoading,
        gcpVpcsError,
        fetchGcpVpcs,
        clearCache,
    }), [
        awsVpcs,
        awsVpcsLoading,
        awsVpcsError,
        fetchAwsVpcs,
        gcpVpcs,
        gcpVpcsLoading,
        gcpVpcsError,
        fetchGcpVpcs,
        clearCache,
    ]);

    return <VPCContext.Provider value={value}>{children}</VPCContext.Provider>;
};

/**
 * Hook to use VPC context
 */
export const useVPC = (): VPCContextType => {
    const context = useContext(VPCContext);
    if (!context) {
        throw new Error('useVPC must be used within a VPCProvider');
    }
    return context;
};
