/**
 * useGcpAlertsData Hook
 *
 * Custom hook for fetching and managing GCP Alerts data
 * Converted from: gcp_alerts.html Alpine.js implementation
 */

import { useState, useCallback, useMemo, useEffect } from 'react';
import apiClient from '../utils/apiClient';
import type { GcpAlert } from '../types/gcpAlerts';

interface UseGcpAlertsDataOptions {
    selectedAccountId: string | null;
}

export const useGcpAlertsData = ({ selectedAccountId }: UseGcpAlertsDataOptions) => {
    const [alerts, setAlerts] = useState<GcpAlert[]>([]);
    const [isLoading, setIsLoading] = useState(true);
    const [isRefreshing, setIsRefreshing] = useState(false);
    const [selectedService, setSelectedService] = useState('All');

    /**
     * Load alerts data from API with sessionStorage caching
     */
    const loadAlerts = useCallback(
        async (forceRefresh = false) => {
            if (!selectedAccountId) {
                setIsLoading(false);
                return;
            }

            const cacheKey = `gcpAlertsData-${selectedAccountId}`;

            // Handle refresh state
            if (forceRefresh) {
                setIsRefreshing(true);
                sessionStorage.removeItem(cacheKey);
            } else {
                setIsLoading(true);

                // Check sessionStorage cache first
                const cached = sessionStorage.getItem(cacheKey);
                if (cached) {
                    try {
                        const cachedData = JSON.parse(cached) as GcpAlert[];
                        setAlerts(processAlerts(cachedData));
                        setIsLoading(false);
                        return;
                    } catch (err) {
                        console.error('Failed to parse cached alerts data', err);
                    }
                }
            }

            try {
                const data = await apiClient.get<GcpAlert[]>(
                    `/api/xamops/gcp/cloudguard/alerts?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`
                );

                // Process response data with defaults
                const processedAlerts: GcpAlert[] = (data || []).map((alert: any) => ({
                    id: alert.id || `gcp-alert-${Math.random()}`,
                    service: alert.service || 'Unknown',
                    type: alert.type || 'UNKNOWN',
                    name: alert.name || 'Unnamed Alert',
                    limit: alert.limit || 0,
                    usage: alert.usage || 0,
                    description: alert.description || 'No description available.',
                    region: alert.region || 'global',
                    status: alert.status || 'UNKNOWN'
                }));

                // Cache the data
                sessionStorage.setItem(cacheKey, JSON.stringify(processedAlerts));

                // Sort and set alerts
                setAlerts(processAlerts(processedAlerts));
            } catch (err) {
                console.error('Failed to load GCP alert data', err);
                setAlerts([]);
            } finally {
                setIsLoading(false);
                setIsRefreshing(false);
            }
        },
        [selectedAccountId]
    );

    /**
     * Process and sort alerts by severity, type, and usage percentage
     */
    const processAlerts = useCallback((data: GcpAlert[]): GcpAlert[] => {
        return (data || []).sort((a, b) => {
            const severityOrder: Record<string, number> = {
                'CRITICAL': 0, 'HIGH': 1, 'WARNING': 2, 'OK': 3, 'UNKNOWN': 4
            };
            const typeOrder: Record<string, number> = {
                'ANOMALY': 0, 'SECURITY': 1, 'QUOTA': 2
            };

            // Primary sort: by severity
            const severityA = severityOrder[a.status] ?? 99;
            const severityB = severityOrder[b.status] ?? 99;
            if (severityA !== severityB) return severityA - severityB;

            // Secondary sort: by type
            const typeA = typeOrder[a.type] ?? 99;
            const typeB = typeOrder[b.type] ?? 99;
            if (typeA !== typeB) return typeA - typeB;

            // Tertiary sort: by usage percentage (for QUOTA types)
            const percentageA = a.type === 'QUOTA' ? (a.usage / a.limit) || 0 : 0;
            const percentageB = b.type === 'QUOTA' ? (b.usage / b.limit) || 0 : 0;
            return percentageB - percentageA;
        });
    }, []);

    /**
     * Load alerts when account changes
     */
    useEffect(() => {
        loadAlerts(false);
    }, [selectedAccountId]);

    /**
     * Get unique service names sorted alphabetically
     */
    const services = useMemo(() => {
        const serviceNames = alerts.map((a) => a.service);
        return [...new Set(serviceNames)].sort();
    }, [alerts]);

    /**
     * Filter alerts by selected service
     */
    const filteredAlerts = useMemo(() => {
        if (selectedService === 'All') {
            return alerts;
        }
        return alerts.filter(q => q.service === selectedService);
    }, [alerts, selectedService]);

    /**
     * Calculate alert counts for KPI cards
     */
    const alertCounts = useMemo(() => {
        return {
            total: alerts.length,
            critical: alerts.filter(q =>
                q.type === 'ANOMALY' ||
                (q.status && q.status.toUpperCase() === 'CRITICAL')
            ).length,
            warning: alerts.filter(q =>
                q.status &&
                (q.status.toUpperCase() === 'WARNING' || q.status.toUpperCase() === 'HIGH')
            ).length,
            healthy: alerts.filter(q => {
                const status = (q.status || '').toUpperCase();
                return status === 'OK' ||
                    (q.type === 'SECURITY' && status !== 'CRITICAL' && status !== 'HIGH');
            }).length
        };
    }, [alerts]);

    /**
     * Calculate usage percentage for QUOTA alerts
     */
    const getUsagePercentage = useCallback((alert: GcpAlert): number => {
        if (alert.type !== 'QUOTA' || !alert.limit || alert.limit === 0) return 0;
        return (alert.usage / alert.limit) * 100;
    }, []);

    /**
     * Get usage color gradient based on status
     */
    const getUsageColor = useCallback((alert: GcpAlert): string => {
        const status = alert.status;
        if (status === 'CRITICAL') return 'bg-gradient-to-r from-red-500 to-red-600';
        if (status === 'WARNING') return 'bg-gradient-to-r from-yellow-500 to-yellow-600';
        return 'bg-gradient-to-r from-green-500 to-green-600';
    }, []);

    /**
     * Get badge class for usage percentage
     */
    const getUsageBadgeClass = useCallback((alert: GcpAlert): string => {
        const status = alert.status;
        if (status === 'CRITICAL') return 'usage-badge-critical';
        if (status === 'WARNING') return 'usage-badge-warning';
        if (status === 'OK') return 'usage-badge-healthy';
        return 'bg-gray-100 text-gray-800 border-gray-200';
    }, []);

    /**
     * Get badge class for security status
     */
    const getSecurityBadgeClass = useCallback((status: string): string => {
        const upperStatus = (status || '').toUpperCase();
        if (upperStatus === 'CRITICAL') return 'severity-badge-critical';
        if (upperStatus === 'HIGH') return 'severity-badge-high';
        if (upperStatus === 'MEDIUM') return 'bg-yellow-100 text-yellow-800 border-yellow-200';
        if (upperStatus === 'LOW') return 'bg-blue-100 text-blue-800 border-blue-200';
        return 'bg-gray-100 text-gray-800 border-gray-200';
    }, []);

    /**
     * Get alert class for row styling
     */
    const getAlertClass = useCallback((alert: GcpAlert): string => {
        const status = (alert.status || '').toUpperCase();
        if (status === 'CRITICAL' || alert.type === 'ANOMALY') return 'critical';
        if (status === 'WARNING' || status === 'HIGH') return 'warning';
        return 'normal';
    }, []);

    /**
     * Get Font Awesome icon class for GCP service
     */
    const getGcpServiceIcon = useCallback((serviceName: string): string => {
        const icons: Record<string, string> = {
            'VPC Network': 'fa-network-wired',
            'Billing': 'fa-dollar-sign',
            'Compute Engine': 'fa-server',
            'Cloud Storage': 'fa-database',
            'Cloud SQL': 'fa-database',
            'Kubernetes Engine': 'fa-dharmachakra',
            'Cloud Functions': 'fa-bolt',
            'IAM': 'fa-users-cog',
            'Security Command Center': 'fa-shield-alt',
        };
        return icons[serviceName] || 'fa-cloud';
    }, []);

    /**
     * Get service icon color class
     */
    const getServiceIconClass = useCallback((serviceName: string): string => {
        const serviceColors: Record<string, string> = {
            'VPC Network': 'service-icon-vpc',
            'Billing': 'service-icon-billing',
            'Compute Engine': 'service-icon-compute',
            'Security Command Center': 'service-icon-security',
            'IAM': 'service-icon-security',
        };
        return serviceColors[serviceName] || 'service-icon-default';
    }, []);

    /**
     * Get GCP Console link based on alert type
     */
    const getGcpConsoleLink = useCallback((alert: GcpAlert): string => {
        const projectId = selectedAccountId;
        if (!projectId) return '#';

        switch (alert.type) {
            case 'QUOTA':
                return `https://console.cloud.google.com/iam-admin/quotas?project=${projectId}`;
            case 'ANOMALY':
                return `https://console.cloud.google.com/billing/projects/${projectId}/overview`;
            case 'SECURITY':
                return `https://console.cloud.google.com/security/command-center/findings?project=${projectId}`;
            default:
                return `https://console.cloud.google.com/home/dashboard?project=${projectId}`;
        }
    }, [selectedAccountId]);

    /**
     * Get action button text based on alert type
     */
    const getGcpActionText = useCallback((alert: GcpAlert): string => {
        switch (alert.type) {
            case 'QUOTA': return 'View Quotas';
            case 'ANOMALY': return 'View Billing';
            case 'SECURITY': return 'View Finding';
            default: return 'Investigate';
        }
    }, []);

    return {
        alerts,
        filteredAlerts,
        isLoading,
        isRefreshing,
        selectedService,
        services,
        alertCounts,
        loadAlerts,
        setSelectedService,
        getUsagePercentage,
        getUsageColor,
        getUsageBadgeClass,
        getSecurityBadgeClass,
        getAlertClass,
        getGcpServiceIcon,
        getServiceIconClass,
        getGcpConsoleLink,
        getGcpActionText,
    };
};
