/**
 * useGcpAlertsData Hook
 *
 * Fetches and manages GCP Alerts data: loading, filtering, searching and pagination.
 */

import { useState, useCallback, useMemo, useEffect } from 'react';
import apiClient from '../utils/apiClient';
import {
  alertCount,
  type GcpAlert,
  type GcpAlertCounts,
  type GcpAlertsResponse,
  type GcpAlertStatus,
  type GcpAlertType,
} from '../types/gcpAlerts';

interface UseGcpAlertsDataOptions {
  selectedAccountId: string | null;
}

export const PAGE_SIZE_OPTIONS = [25, 50, 100] as const;

const ALL_TYPES: GcpAlertType[] = ['SECURITY', 'ANOMALY', 'QUOTA'];
const ALL_SEVERITIES: GcpAlertStatus[] = ['CRITICAL', 'HIGH', 'WARNING', 'OK'];

const SEVERITY_ORDER: Record<string, number> = {
  CRITICAL: 0, HIGH: 1, WARNING: 2, OK: 3, UNKNOWN: 4,
};
const TYPE_ORDER: Record<string, number> = { ANOMALY: 0, SECURITY: 1, QUOTA: 2 };

export const useGcpAlertsData = ({ selectedAccountId }: UseGcpAlertsDataOptions) => {
  const [alerts, setAlerts] = useState<GcpAlert[]>([]);
  const [generatedAt, setGeneratedAt] = useState<string | null>(null);
  const [degraded, setDegraded] = useState(false);
  const [degradedReasons, setDegradedReasons] = useState<string[]>([]);
  const [fromCache, setFromCache] = useState(false);
  const [totalFindings, setTotalFindings] = useState(0);

  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Filters
  const [selectedService, setSelectedService] = useState('All');
  const [searchTerm, setSearchTerm] = useState('');
  const [activeTypes, setActiveTypes] = useState<GcpAlertType[]>(ALL_TYPES);
  const [activeSeverities, setActiveSeverities] = useState<GcpAlertStatus[]>(ALL_SEVERITIES);

  // Pagination
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(50);

  /**
   * Load alerts from the API.
   *
   * No browser-side cache.
   *
   * This used to read a sessionStorage blob written with no timestamp and no TTL. Because
   * sessionStorage survives a reload, refreshing the page re-rendered the identical JSON and never
   * contacted the server — so a customer who had resolved their findings could not clear them by
   * refreshing, only by pressing the in-app Refresh button. The server already caches this list in
   * Redis, where it has an expiry and can be evicted; a second copy in the tab was invisible
   * staleness nobody could reach.
   */
  const loadAlerts = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        return;
      }

      if (forceRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }

      try {
        const data = await apiClient.get<GcpAlertsResponse>(
          `/api/xamops/gcp/cloudguard/alerts?accountId=${encodeURIComponent(selectedAccountId)}&forceRefresh=${forceRefresh}`
        );

        // Tolerate the pre-envelope array shape. A browser holding an older bundle, or a stale
        // cached response, would otherwise render an empty page with no explanation.
        const envelope: GcpAlertsResponse = Array.isArray(data)
          ? {
              alerts: data as GcpAlert[],
              generatedAt: null,
              degraded: false,
              degradedReasons: [],
              fromCache: false,
              totalFindings: (data as GcpAlert[]).length,
            }
          : data;

        const processedAlerts: GcpAlert[] = (envelope?.alerts || []).map((alert: any) => ({
          id: alert.id || `gcp-alert-${Math.random()}`,
          service: alert.service || 'Unknown',
          type: alert.type || 'UNKNOWN',
          name: alert.name || 'Unnamed Alert',
          limit: alert.limit || 0,
          usage: alert.usage || 0,
          description: alert.description || 'No description available.',
          region: alert.region || 'global',
          status: alert.status || 'UNKNOWN',
          affectedCount: alert.affectedCount ?? 0,
          resources: Array.isArray(alert.resources) ? alert.resources : [],
        }));

        setAlerts(sortAlerts(processedAlerts));
        setGeneratedAt(envelope?.generatedAt ?? null);
        setDegraded(Boolean(envelope?.degraded));
        setDegradedReasons(envelope?.degradedReasons || []);
        setFromCache(Boolean(envelope?.fromCache));
        setTotalFindings(
          envelope?.totalFindings ?? processedAlerts.reduce((sum, a) => sum + alertCount(a), 0)
        );
      } catch (err) {
        console.error('Failed to load GCP alert data', err);
        // A transport failure is reported as degraded, never as an empty list. An empty list renders
        // as "All Clear!", which is the wrong thing to tell someone when the truth is that we do not
        // know.
        setAlerts([]);
        setTotalFindings(0);
        setGeneratedAt(null);
        setDegraded(true);
        setDegradedReasons(['Could not reach XamOps to load alerts. Please retry in a few moments.']);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId]
  );

  /** Sort by severity, then type, then how widely the issue spreads. */
  const sortAlerts = (data: GcpAlert[]): GcpAlert[] =>
    [...(data || [])].sort((a, b) => {
      const severityA = SEVERITY_ORDER[a.status] ?? 99;
      const severityB = SEVERITY_ORDER[b.status] ?? 99;
      if (severityA !== severityB) return severityA - severityB;

      const typeA = TYPE_ORDER[a.type] ?? 99;
      const typeB = TYPE_ORDER[b.type] ?? 99;
      if (typeA !== typeB) return typeA - typeB;

      // Most-affected first, so the biggest problem leads. Quota rows have no group, so they fall
      // back to utilisation, which is their equivalent of "how bad is this".
      const countDelta = alertCount(b) - alertCount(a);
      if (countDelta !== 0) return countDelta;

      const pctA = a.type === 'QUOTA' && a.limit ? a.usage / a.limit : 0;
      const pctB = b.type === 'QUOTA' && b.limit ? b.usage / b.limit : 0;
      return pctB - pctA;
    });

  useEffect(() => {
    loadAlerts(false);
  }, [selectedAccountId]);

  // Any filter change returns to page 1. Staying on page 7 of a result set that now has two pages
  // shows an empty table and reads as "no results" rather than "you are past the end".
  useEffect(() => {
    setPage(1);
  }, [selectedService, searchTerm, activeTypes, activeSeverities, pageSize]);

  const services = useMemo(() => {
    const serviceNames = alerts.map(a => a.service);
    return [...new Set(serviceNames)].sort();
  }, [alerts]);

  const filteredAlerts = useMemo(() => {
    const needle = searchTerm.trim().toLowerCase();

    return alerts.filter(alert => {
      if (selectedService !== 'All' && alert.service !== selectedService) return false;
      if (!activeTypes.includes(alert.type)) return false;

      // An unrecognised status stays visible. Hiding a row because we could not classify it is the
      // one failure mode a security list cannot have.
      const known = ALL_SEVERITIES.includes(alert.status);
      if (known && !activeSeverities.includes(alert.status)) return false;

      if (!needle) return true;
      const haystack = [
        alert.name,
        alert.service,
        alert.description,
        alert.region,
        ...(alert.resources || []),
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return haystack.includes(needle);
    });
  }, [alerts, selectedService, activeTypes, activeSeverities, searchTerm]);

  const pageCount = Math.max(1, Math.ceil(filteredAlerts.length / pageSize));
  const safePage = Math.min(page, pageCount);

  const pagedAlerts = useMemo(
    () => filteredAlerts.slice((safePage - 1) * pageSize, safePage * pageSize),
    [filteredAlerts, safePage, pageSize]
  );

  /** Counts over the whole set, not the current page or filter — a KPI that moved when you typed
   * in the search box would be describing the filter rather than the account. */
  const alertCounts = useMemo<GcpAlertCounts>(() => {
    const sumFor = (predicate: (a: GcpAlert) => boolean) =>
      alerts.filter(predicate).reduce((sum, a) => sum + alertCount(a), 0);

    return {
      rows: alerts.length,
      findings: totalFindings,
      critical: sumFor(a => (a.status || '').toUpperCase() === 'CRITICAL'),
      high: sumFor(a => (a.status || '').toUpperCase() === 'HIGH'),
      costAnomalies: sumFor(a => a.type === 'ANOMALY'),
      quotaWarnings: sumFor(a => a.type === 'QUOTA'),
    };
  }, [alerts, totalFindings]);

  const toggleType = useCallback((type: GcpAlertType) => {
    setActiveTypes(prev =>
      prev.includes(type)
        ? // Never let the last filter be switched off — an empty table with every chip greyed out
          // looks like a data problem rather than a filter the user set.
          prev.length === 1 ? prev : prev.filter(t => t !== type)
        : [...prev, type]
    );
  }, []);

  const toggleSeverity = useCallback((severity: GcpAlertStatus) => {
    setActiveSeverities(prev =>
      prev.includes(severity)
        ? prev.length === 1 ? prev : prev.filter(s => s !== severity)
        : [...prev, severity]
    );
  }, []);

  const resetFilters = useCallback(() => {
    setSelectedService('All');
    setSearchTerm('');
    setActiveTypes(ALL_TYPES);
    setActiveSeverities(ALL_SEVERITIES);
  }, []);

  const filtersActive =
    selectedService !== 'All' ||
    searchTerm.trim() !== '' ||
    activeTypes.length !== ALL_TYPES.length ||
    activeSeverities.length !== ALL_SEVERITIES.length;

  // ── Presentation helpers ───────────────────────────────────────────────────

  const getUsagePercentage = useCallback((alert: GcpAlert): number => {
    if (alert.type !== 'QUOTA' || !alert.limit || alert.limit === 0) return 0;
    return (alert.usage / alert.limit) * 100;
  }, []);

  const getUsageColor = useCallback((alert: GcpAlert): string => {
    if (alert.status === 'CRITICAL') return 'bg-gradient-to-r from-red-500 to-red-600';
    if (alert.status === 'WARNING') return 'bg-gradient-to-r from-yellow-500 to-yellow-600';
    return 'bg-gradient-to-r from-green-500 to-green-600';
  }, []);

  const getUsageBadgeClass = useCallback((alert: GcpAlert): string => {
    if (alert.status === 'CRITICAL') return 'usage-badge-critical';
    if (alert.status === 'WARNING') return 'usage-badge-warning';
    if (alert.status === 'OK') return 'usage-badge-healthy';
    return 'bg-gray-100 text-gray-800 border-gray-200';
  }, []);

  const getSecurityBadgeClass = useCallback((status: string): string => {
    const upperStatus = (status || '').toUpperCase();
    if (upperStatus === 'CRITICAL') return 'severity-badge-critical';
    if (upperStatus === 'HIGH') return 'severity-badge-high';
    if (upperStatus === 'MEDIUM') return 'bg-yellow-100 text-yellow-800 border-yellow-200';
    if (upperStatus === 'LOW') return 'bg-blue-100 text-blue-800 border-blue-200';
    return 'bg-gray-100 text-gray-800 border-gray-200';
  }, []);

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

  const getGcpActionText = useCallback((alert: GcpAlert): string => {
    switch (alert.type) {
      case 'QUOTA': return 'View Quotas';
      case 'ANOMALY': return 'View Billing';
      case 'SECURITY': return 'View in SCC';
      default: return 'Investigate';
    }
  }, []);

  return {
    // Data
    alerts,
    filteredAlerts,
    pagedAlerts,
    alertCounts,
    services,
    generatedAt,
    degraded,
    degradedReasons,
    fromCache,
    totalFindings,

    // Status
    isLoading,
    isRefreshing,

    // Filters
    selectedService,
    setSelectedService,
    searchTerm,
    setSearchTerm,
    activeTypes,
    toggleType,
    activeSeverities,
    toggleSeverity,
    resetFilters,
    filtersActive,

    // Pagination
    page: safePage,
    setPage,
    pageSize,
    setPageSize,
    pageCount,

    // Actions
    loadAlerts,

    // Presentation helpers
    getUsagePercentage,
    getUsageColor,
    getUsageBadgeClass,
    getSecurityBadgeClass,
    getServiceIconClass,
    getGcpConsoleLink,
    getGcpActionText,
  };
};
