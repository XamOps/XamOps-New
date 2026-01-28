/**
 * useAlertsData Hook
 *
 * Custom hook to fetch and manage alerts data
 * Converted from: alerts.html (alerts() Alpine.js component)
 *
 * API Endpoints:
 * - GET /api/xamops/cloudguard/alerts?accountId={accountId}&forceRefresh={boolean}
 * - GET /api/xamops/cloudguard/grafana-alerts
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { Alert, GrafanaAlert, AlertCounts, AlertTab, AlertSeverityClass } from '../types/alerts';

interface UseAlertsDataOptions {
  selectedAccountId: string | null;
}

interface UseAlertsDataReturn {
  // State
  alerts: Alert[];
  grafanaAlerts: GrafanaAlert[];
  filteredAlerts: Alert[];
  isLoading: boolean;
  isRefreshing: boolean;
  loadingGrafana: boolean;
  selectedService: string;
  activeTab: AlertTab;
  services: string[];
  alertCounts: AlertCounts;

  // Actions
  loadAlerts: (forceRefresh?: boolean) => Promise<void>;
  fetchGrafanaAlerts: () => Promise<void>;
  setSelectedService: (service: string) => void;
  setActiveTab: (tab: AlertTab) => void;

  // Utility functions
  getUsagePercentage: (alert: Alert) => number;
  getUsageColor: (alert: Alert) => string;
  getUsageBadgeClass: (alert: Alert) => string;
  getAlertClass: (alert: Alert) => AlertSeverityClass;
  getServiceIconClass: (serviceName: string) => string;
  getAwsConsoleLink: (alert: Alert) => string;
}

export const useAlertsData = ({
  selectedAccountId,
}: UseAlertsDataOptions): UseAlertsDataReturn => {
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [grafanaAlerts, setGrafanaAlerts] = useState<GrafanaAlert[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [loadingGrafana, setLoadingGrafana] = useState<boolean>(false);
  const [selectedService, setSelectedService] = useState<string>('All');
  const [activeTab, setActiveTab] = useState<AlertTab>('cloudguard');

  /**
   * Process and sort alerts by usage percentage
   */
  const processAlerts = useCallback((data: any[]): Alert[] => {
    const processed = data
      .map((alert: any) => {
        if (alert.type === 'QUOTA') {
          return {
            id: alert.id,
            service: alert.service,
            type: 'QUOTA' as const,
            name: alert.name,
            limit: alert.limit,
            usage: alert.usage,
            description: alert.description,
            region: alert.region,
          };
        } else if (alert.type === 'ANOMALY') {
          return {
            id: alert.id,
            service: alert.service,
            type: 'ANOMALY' as const,
            name: alert.name,
            usage: alert.usage,
            description: alert.description,
            region: alert.region,
          };
        }
        return null;
      })
      .filter(Boolean) as Alert[];

    // Sort by usage percentage (highest first)
    return processed.sort((a, b) => {
      const percentageA = a.type === 'QUOTA' ? (a.usage / a.limit) || 0 : 1;
      const percentageB = b.type === 'QUOTA' ? (b.usage / b.limit) || 0 : 1;
      return percentageB - percentageA;
    });
  }, []);

  /**
   * Load CloudGuard alerts
   */
  const loadAlerts = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        console.warn('[Alerts] No account selected. Cannot fetch alert data.');
        return;
      }

      const cacheKey = `alertsData-${selectedAccountId}`;
      const cachedData = sessionStorage.getItem(cacheKey);

      // Use cached data if available and not forcing refresh
      if (cachedData && !forceRefresh) {
        console.log('[Alerts] Loading from cache...');
        setAlerts(JSON.parse(cachedData));
        setIsLoading(false);
        return;
      }

      // Show loading state only on first load
      if (!cachedData) {
        setIsLoading(true);
      }

      if (forceRefresh) {
        setIsRefreshing(true);
        sessionStorage.removeItem(cacheKey);
      }

      console.log(`[Alerts] Fetching alerts for account: ${selectedAccountId}, forceRefresh: ${forceRefresh}`);

      try {
        const response = await fetch(
          `/api/xamops/cloudguard/alerts?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          { credentials: 'include' }
        );

        if (!response.ok) {
          throw new Error('Failed to fetch alert data');
        }

        const data = await response.json();
        const processedAlerts = processAlerts(data);

        // Cache the data
        sessionStorage.setItem(cacheKey, JSON.stringify(processedAlerts));
        sessionStorage.setItem('alerts_last_loaded', Date.now().toString());

        setAlerts(processedAlerts);
        console.log(`[Alerts] Successfully loaded ${processedAlerts.length} alerts`);
      } catch (err) {
        console.error('[Alerts] Failed to load alert data:', err);
        setAlerts([]);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, processAlerts]
  );

  /**
   * Fetch Grafana alerts
   */
  const fetchGrafanaAlerts = useCallback(async () => {
    setLoadingGrafana(true);
    try {
      const res = await fetch('/api/xamops/cloudguard/grafana-alerts', {
        credentials: 'include',
      });
      if (!res.ok) throw new Error('Failed to fetch Grafana alerts');
      const data = await res.json();
      setGrafanaAlerts(data);
      console.log(`[Alerts] Loaded ${data.length} Grafana alerts`);
    } catch (e) {
      console.error('[Alerts] Error fetching Grafana alerts:', e);
      setGrafanaAlerts([]);
    } finally {
      setLoadingGrafana(false);
    }
  }, []);

  /**
   * Get unique services from alerts
   */
  const services = useMemo(() => {
    const serviceNames = alerts.map((q) => q.service);
    return [...new Set(serviceNames)].sort();
  }, [alerts]);

  /**
   * Filter alerts by selected service
   */
  const filteredAlerts = useMemo(() => {
    if (selectedService === 'All') {
      return alerts;
    }
    return alerts.filter((q) => q.service === selectedService);
  }, [alerts, selectedService]);

  /**
   * Get usage percentage for an alert
   */
  const getUsagePercentage = useCallback((alert: Alert): number => {
    if (alert.type !== 'QUOTA' || alert.limit === 0) return 0;
    return (alert.usage / alert.limit) * 100;
  }, []);

  /**
   * Get usage color class based on percentage
   */
  const getUsageColor = useCallback((alert: Alert): string => {
    const percentage = getUsagePercentage(alert);
    if (percentage > 90) return 'bg-gradient-to-r from-red-500 to-red-600';
    if (percentage > 75) return 'bg-gradient-to-r from-yellow-500 to-yellow-600';
    return 'bg-gradient-to-r from-green-500 to-green-600';
  }, [getUsagePercentage]);

  /**
   * Get usage badge class based on percentage
   */
  const getUsageBadgeClass = useCallback((alert: Alert): string => {
    const percentage = getUsagePercentage(alert);
    if (percentage > 90) return 'alerts-badge-critical';
    if (percentage > 75) return 'alerts-badge-warning';
    return 'alerts-badge-healthy';
  }, [getUsagePercentage]);

  /**
   * Get alert class based on type and percentage
   */
  const getAlertClass = useCallback((alert: Alert): AlertSeverityClass => {
    if (alert.type === 'ANOMALY') return 'critical';
    const percentage = getUsagePercentage(alert);
    if (percentage > 90) return 'critical';
    if (percentage > 75) return 'warning';
    return 'normal';
  }, [getUsagePercentage]);

  /**
   * Get service icon class
   */
  const getServiceIconClass = useCallback((serviceName: string): string => {
    const serviceColors: Record<string, string> = {
      EC2: 'alerts-service-ec2',
      S3: 'alerts-service-s3',
      RDS: 'alerts-service-rds',
      Lambda: 'alerts-service-lambda',
      VPC: 'alerts-service-vpc',
      IAM: 'alerts-service-iam',
      CloudFormation: 'alerts-service-cloudformation',
      Route53: 'alerts-service-route53',
    };
    return serviceColors[serviceName] || 'alerts-service-default';
  }, []);

  /**
   * Get AWS console link for an alert
   */
  const getAwsConsoleLink = useCallback((alert: Alert): string => {
    if (alert.type === 'ANOMALY') {
      return 'https://console.aws.amazon.com/cost-management/home#/cost-anomalies';
    }
    return `https://console.aws.amazon.com/servicequotas/home?region=${alert.region}#/service-quotas/dashboard`;
  }, []);

  /**
   * Calculate alert counts
   */
  const alertCounts = useMemo((): AlertCounts => {
    const critical = alerts.filter(
      (q) => q.type === 'ANOMALY' || getUsagePercentage(q) > 90
    ).length;

    const warning = alerts.filter((q) => {
      const p = getUsagePercentage(q);
      return q.type === 'QUOTA' && p > 75 && p <= 90;
    }).length;

    const healthy = alerts.filter(
      (q) => q.type === 'QUOTA' && getUsagePercentage(q) <= 75
    ).length;

    return {
      total: alerts.length,
      critical,
      warning,
      healthy,
    };
  }, [alerts, getUsagePercentage]);

  // Initialize data on mount and handle account changes
  useEffect(() => {
    if (selectedAccountId) {
      loadAlerts(false);
      fetchGrafanaAlerts();
    }

    // Listen for account-changed events
    const handleAccountChanged = () => {
      console.log('[Alerts] Account changed event detected');
      loadAlerts(true);
    };

    const handleMultiAccountChanged = () => {
      console.log('[Alerts] Multi-account selection changed');
      loadAlerts(true);
    };

    window.addEventListener('account-changed', handleAccountChanged);
    window.addEventListener('multi-account-selection-changed', handleMultiAccountChanged);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged);
      window.removeEventListener('multi-account-selection-changed', handleMultiAccountChanged);
    };
  }, [selectedAccountId]); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    alerts,
    grafanaAlerts,
    filteredAlerts,
    isLoading,
    isRefreshing,
    loadingGrafana,
    selectedService,
    activeTab,
    services,
    alertCounts,
    loadAlerts,
    fetchGrafanaAlerts,
    setSelectedService,
    setActiveTab,
    getUsagePercentage,
    getUsageColor,
    getUsageBadgeClass,
    getAlertClass,
    getServiceIconClass,
    getAwsConsoleLink,
  };
};

export default useAlertsData;
