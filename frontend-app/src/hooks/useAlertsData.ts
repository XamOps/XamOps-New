/**
 * useAlertsData Hook
 *
 * Custom hook to fetch and manage alerts data
 * Converted from: alerts.html (alerts() Alpine.js component)
 *
 * API Endpoints:
 * - GET /api/xamops/cloudguard/alerts?accountId={accountId}&forceRefresh={boolean}
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import type {
  Alert,
  AlertCounts,
  AlertLink,
  AlertSeverity,
  AlertTab,
  AlertError,
  AlertsMetadata,
  AlertsResponse,
} from '../types/alerts';
import { matchesSeverity, type SeverityFilter } from '../components/Alerts/alertGroups';

interface UseAlertsDataOptions {
  selectedAccountId: string | null;
}

interface UseAlertsDataReturn {
  // State
  alerts: Alert[];
  /** `alerts` narrowed to the severity filter. */
  filteredAlerts: Alert[];
  isLoading: boolean;
  isRefreshing: boolean;
  severityFilter: SeverityFilter;
  activeTab: AlertTab;
  alertCounts: AlertCounts;

  // Enhanced error handling
  errors: AlertError[];
  metadata: AlertsMetadata | null;
  hasErrors: boolean;
  isPartialSuccess: boolean;

  // Actions
  loadAlerts: (forceRefresh?: boolean) => Promise<void>;
  retryFailedServices: () => Promise<void>;
  setSeverityFilter: (filter: SeverityFilter) => void;
  setActiveTab: (tab: AlertTab) => void;

  // Utility functions
  getAlertLink: (alert: Alert) => AlertLink;
  quotaConsoleLink: (region: string) => string;
}

const SEVERITIES: AlertSeverity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFORMATIONAL'];

const SEVERITY_RANK: Record<AlertSeverity, number> = {
  CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, INFORMATIONAL: 4,
};

/**
 * The alert's severity, preferring the backend's normalised value.
 *
 * Responses cached before `severity` was serialised, and the demo data, do not carry it, so it is
 * derived the way the backend derives it: quota by utilisation, anything else from `status`.
 */
const severityOf = (raw: any): AlertSeverity => {
  const explicit = String(raw.severity || '').toUpperCase();
  if ((SEVERITIES as string[]).includes(explicit)) return explicit as AlertSeverity;

  if (raw.type === 'QUOTA') {
    const pct = raw.limit > 0 ? (raw.usage / raw.limit) * 100 : 0;
    if (pct >= 90) return 'CRITICAL';
    if (pct >= 75) return 'MEDIUM';
    return 'INFORMATIONAL';
  }
  const status = String(raw.status || '').toUpperCase();
  if (status === 'CRITICAL') return 'CRITICAL';
  if (status === 'HIGH') return 'HIGH';
  if (status === 'LOW') return 'LOW';
  return 'MEDIUM';
};

/**
 * Normalise the backend's rows and sort them: most severe first, then fullest quota.
 *
 * Live security findings arrive typed SECURITY_FINDING. They used to be dropped here because
 * only QUOTA, ANOMALY and SECURITY were recognised, so the backend counted and emailed findings
 * the page never showed.
 */
export const normaliseAlerts = (data: any[]): Alert[] => {
  const processed = data
    .map((alert: any): Alert | null => {
      const base = {
        id: alert.id,
        service: alert.service || 'Unknown',
        name: alert.name,
        description: alert.description || '',
        region: alert.region || '',
        severity: severityOf(alert),
      };
      if (alert.type === 'QUOTA') {
        return { ...base, type: 'QUOTA', limit: alert.limit || 0, usage: alert.usage || 0 };
      }
      if (alert.type === 'ANOMALY') {
        return { ...base, type: 'ANOMALY', usage: alert.usage || 0, currencySymbol: alert.currencySymbol || '$' };
      }
      if (alert.type === 'SECURITY' || alert.type === 'SECURITY_FINDING') {
        return { ...base, type: 'SECURITY', source: alert.type === 'SECURITY_FINDING' ? 'LIVE' : 'SECURITY_HUB' };
      }
      return null;
    })
    .filter((a): a is Alert => a !== null);

  const quotaFill = (a: Alert) => (a.type === 'QUOTA' && a.limit > 0 ? a.usage / a.limit : 0);
  return processed.sort((a, b) => {
    const bySeverity = SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity];
    if (bySeverity !== 0) return bySeverity;
    return quotaFill(b) - quotaFill(a);
  });
};

export const useAlertsData = ({
  selectedAccountId,
}: UseAlertsDataOptions): UseAlertsDataReturn => {
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [severityFilter, setSeverityFilter] = useState<SeverityFilter>('ALL');
  const [activeTab, setActiveTab] = useState<AlertTab>('cloudguard');

  // Enhanced error handling state
  const [errors, setErrors] = useState<AlertError[]>([]);
  const [metadata, setMetadata] = useState<AlertsMetadata | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

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

        // Service unavailable: the scan failed or timed out. The body names why.
        if (response.status === 503) {
          const data: AlertsResponse = await response.json();
          setErrors(
            data.errors && data.errors.length > 0
              ? data.errors
              : [{
                  service: 'Alerts scan',
                  errorType: 'UNKNOWN',
                  message: 'The alerts could not be loaded. Please retry in a few moments.',
                  partial: false,
                  timestamp: new Date().toISOString(),
                }]
          );
          setAlerts([]);
          setMetadata(data.metadata || null);
          console.error('[Alerts] All alert services are currently unavailable');
          return;
        }

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: Failed to fetch alerts`);
        }

        const data: AlertsResponse = await response.json();
        const processedAlerts = normaliseAlerts(data.alerts || []);

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
        if (!signal.aborted) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
      }
    },
    [selectedAccountId]
  );

  /**
   * Retry after a failure.
   *
   * After a timeout the scan is still running on the server and saves its result when it
   * finishes, so the retry is a normal load: it picks up that result, or joins the scan if it is
   * still going. A forced refresh would start the whole scan over and likely time out again.
   * Any other failure forces a fresh scan.
   */
  const retryFailedServices = useCallback(async () => {
    const onlyTimedOut = errors.length > 0 && errors.every((e) => e.errorType === 'TIMEOUT');
    console.log(`[Alerts] Retrying (${onlyTimedOut ? 'collecting the running scan' : 'fresh scan'})...`);
    await loadAlerts(!onlyTimedOut);
  }, [loadAlerts, errors]);

  /**
   * Filter alerts by severity
   */
  const filteredAlerts = useMemo(
    () => alerts.filter((a) => matchesSeverity(a.severity, severityFilter)),
    [alerts, severityFilter]
  );

  /** The Service Quotas console for a region, where a limit increase is requested. */
  const quotaConsoleLink = useCallback(
    (region: string): string =>
      `https://console.aws.amazon.com/servicequotas/home?region=${region}#/service-quotas/dashboard`,
    []
  );

  /**
   * Where the alert's action button goes. Security alerts used to fall through to the Service
   * Quotas console, which has nothing to do with them.
   */
  const getAlertLink = useCallback((alert: Alert): AlertLink => {
    switch (alert.type) {
      case 'QUOTA':
        return { href: quotaConsoleLink(alert.region), label: 'Request increase', external: true };
      case 'ANOMALY':
        return {
          href: 'https://console.aws.amazon.com/cost-management/home#/cost-anomalies',
          label: 'View in Cost Explorer',
          external: true,
        };
      case 'SECURITY':
        return alert.source === 'LIVE'
          ? { href: '/security', label: 'Open Security Center', external: false }
          : {
              href: `https://console.aws.amazon.com/securityhub/home?region=${alert.region}#/findings`,
              label: 'View in Security Hub',
              external: true,
            };
    }
  }, [quotaConsoleLink]);

  /**
   * Calculate alert counts from severity, so security findings and cost spikes count too — they
   * used to be left out of every card except the total.
   */
  const alertCounts = useMemo((): AlertCounts => ({
    total: alerts.length,
    critical: alerts.filter((a) => a.severity === 'CRITICAL').length,
    warning: alerts.filter((a) => a.severity === 'HIGH' || a.severity === 'MEDIUM').length,
  }), [alerts]);

  /**
   * Computed properties for error handling
   */
  const hasErrors = useMemo(() => errors.length > 0, [errors]);
  const isPartialSuccess = useMemo(() => hasErrors && alerts.length > 0, [hasErrors, alerts.length]);

  // Load on mount and whenever the selected account changes. Switching accounts used to force a
  // full re-scan of every region through an 'account-changed' listener as well; the normal load
  // is enough, and serves the server's cache when it is still fresh.
  useEffect(() => {
    if (selectedAccountId) {
      setSeverityFilter('ALL');
      loadAlerts(false);
    }
    return () => abortControllerRef.current?.abort();
  }, [selectedAccountId]); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    alerts,
    filteredAlerts,
    isLoading,
    isRefreshing,
    severityFilter,
    activeTab,
    alertCounts,
    errors,
    metadata,
    hasErrors,
    isPartialSuccess,
    loadAlerts,
    retryFailedServices,
    setSeverityFilter,
    setActiveTab,
    getAlertLink,
    quotaConsoleLink,
  };
};

export default useAlertsData;
