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

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import type { Alert, GrafanaAlert, AlertCounts, AlertTab, AlertSeverityClass, AlertError, AlertsMetadata, AlertsResponse } from '../types/alerts';

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

  // Enhanced error handling
  errors: AlertError[];
  metadata: AlertsMetadata | null;
  hasErrors: boolean;
  isPartialSuccess: boolean;

  // Actions
  loadAlerts: (forceRefresh?: boolean) => Promise<void>;
  fetchGrafanaAlerts: () => Promise<void>;
  retryFailedServices: () => Promise<void>;
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

  // Enhanced error handling state
  const [errors, setErrors] = useState<AlertError[]>([]);
  const [metadata, setMetadata] = useState<AlertsMetadata | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

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
        } else if (alert.type === 'SECURITY') {
          return {
            id: alert.id,
            service: alert.service,
            type: 'SECURITY' as const,
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
   * Load CloudGuard alerts with enhanced error handling
   */
  const loadAlerts = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        console.warn('[Alerts] No account selected. Cannot fetch alert data.');
        return;
      }

      // Cancel any in-flight request before starting a new one
      abortControllerRef.current?.abort();
      abortControllerRef.current = new AbortController();
      const { signal } = abortControllerRef.current;

      // No browser-side cache.
      //
      // This read a sessionStorage blob that had no TTL — it wrote an `alerts_last_loaded`
      // timestamp and then never read it anywhere — so a page refresh re-rendered the same JSON
      // without contacting the server. On an alerts surface that meant resolved alerts could not be
      // cleared by refreshing at all, only by the in-app Refresh button. The server caches this
      // list already, with an expiry and an eviction path; a copy in the tab had neither.
      setIsLoading(true);

      if (forceRefresh) {
        setIsRefreshing(true);
        setErrors([]); // Clear previous errors on refresh
      }

      console.log(`[Alerts] Fetching alerts for account: ${selectedAccountId}, forceRefresh: ${forceRefresh}`);

      try {
        const response = await fetch(
          `/api/xamops/cloudguard/alerts?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          {
            credentials: 'include',
            signal,
            headers: {
              'Accept': 'application/json',
            }
          }
        );

        // Handle service unavailable (all services failed)
        if (response.status === 503) {
          const data: AlertsResponse = await response.json();
          setErrors(data.errors || []);
          setAlerts([]);
          setMetadata(data.metadata || null);
          console.error('[Alerts] All alert services are currently unavailable');
          return;
        }

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: Failed to fetch alerts`);
        }

        const data: AlertsResponse = await response.json();
        const processedAlerts = processAlerts(data.alerts || []);

        setAlerts(processedAlerts);
        setErrors(data.errors || []);
        setMetadata(data.metadata || null);

        // Log warnings for partial failures
        if (data.errors && data.errors.length > 0) {
          const failedServices = data.errors.map(e => e.service).join(', ');
          console.warn(`[Alerts] Some services failed: ${failedServices}. Showing ${processedAlerts.length} alerts.`);
        } else {
          console.log(`[Alerts] Successfully loaded ${processedAlerts.length} alerts`);
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        console.error('[Alerts] Failed to load alert data:', err);

        // Set error state
        setErrors([{
          service: 'SYSTEM',
          errorType: 'UNKNOWN',
          message: err instanceof Error ? err.message : 'Unknown error occurred',
          partial: false,
          timestamp: new Date().toISOString(),
        }]);

        setAlerts([]);
        setMetadata(null);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, processAlerts]
  );

  /**
   * Retry failed services by forcing a refresh
   */
  const retryFailedServices = useCallback(async () => {
    console.log('[Alerts] Retrying failed services...');
    await loadAlerts(true);
  }, [loadAlerts]);

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

  /**
   * Computed properties for error handling
   */
  const hasErrors = useMemo(() => errors.length > 0, [errors]);
  const isPartialSuccess = useMemo(() => hasErrors && alerts.length > 0, [hasErrors, alerts.length]);

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
    errors,
    metadata,
    hasErrors,
    isPartialSuccess,
    loadAlerts,
    fetchGrafanaAlerts,
    retryFailedServices,
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
