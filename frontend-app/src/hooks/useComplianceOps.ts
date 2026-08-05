/**
 * useComplianceOps
 *
 * Loads the server-aggregated ComplianceOps report, owns client-side filtering, and
 * drives the scan lifecycle.
 *
 * Endpoints:
 * - GET  /api/xamops/compliance/report?accountId=&trendDays=
 * - POST /api/xamops/{provider-path}/prowler/scan?accountId=      (existing trigger)
 * - GET  /api/xamops/{provider-path}/prowler/status?accountId=     (existing poll)
 *
 * Scan triggering still goes through the provider security controllers so there stays
 * one way to start a scan; only the *reading* moved to the compliance API.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  ComplianceControl,
  ComplianceReport,
  ComplianceStatus,
} from '../types/compliance';
import { SEVERITY_RANK, frameworkKey } from '../components/Compliance/complianceTokens';

export type CloudProvider = 'AWS' | 'GCP' | 'Azure';

/** 'OPEN' collapses FAIL + MANUAL: everything that still needs a human. */
export type StatusFilter = ComplianceStatus | 'All' | 'OPEN';

export interface ComplianceFilters {
  status: StatusFilter;
  severity: string;
  /** Normalised framework key, or 'All'. */
  framework: string;
  service: string;
  search: string;
  /** Only controls with no assigned owner. */
  unownedOnly: boolean;
}

interface ScanStatus {
  status: string;
  findingsCount?: number;
  error?: string;
  message?: string;
}

const DEFAULT_FILTERS: ComplianceFilters = {
  // Failing work first. The page exists to show what needs doing, not to list 400
  // controls the user has to wade through.
  status: 'OPEN',
  severity: 'All',
  framework: 'All',
  service: 'All',
  search: '',
  unownedOnly: false,
};

const asArray = <T,>(value: T[] | null | undefined): T[] => (Array.isArray(value) ? value : []);

const EMPTY_COUNTS: ComplianceReport['counts'] = {
  totalControls: 0, passedControls: 0, failedControls: 0, manualControls: 0,
  waivedControls: 0, passedResources: 0, failedResources: 0,
  critical: 0, high: 0, medium: 0, low: 0, newlyFailing: 0, newlyPassing: 0,
};

const EMPTY_PROGRAM: ComplianceReport['program'] = {
  activeExceptions: 0, exceptionsExpiringSoon: 0, lapsedExceptions: 0, pendingExceptions: 0,
  failingControlsWithOwner: 0, failingControlsWithoutOwner: 0,
  remediationOpen: 0, remediationInProgress: 0, remediationAwaitingVerification: 0,
  remediationOverdue: 0, completedScans: 0, previousScanAt: null,
};

/**
 * Fills in anything the payload is missing before it reaches a component.
 *
 * This is the same guard the WAR page needed after a cached report with a null array
 * crashed a component on `.length`. A component should not be one absent field away
 * from taking the page down, so the boundary normalises rather than trusting the shape.
 */
export const normalizeReport = (
  data: Partial<ComplianceReport> | null,
): ComplianceReport | null => {
  if (!data) return null;

  const normalizeControl = (c: Partial<ComplianceControl>): ComplianceControl => ({
    ...c,
    checkId: c.checkId ?? '',
    remediationRefs: asArray(c.remediationRefs),
    frameworks: asArray(c.frameworks).map((f) => ({
      framework: f?.framework ?? '',
      controls: asArray(f?.controls),
    })),
    resources: asArray(c.resources),
    status: (c.status ?? 'MANUAL') as ComplianceStatus,
    severity: c.severity ?? 'LOW',
    passedResources: c.passedResources ?? 0,
    failedResources: c.failedResources ?? 0,
    owner: c.owner ?? null,
    exception: c.exception ?? null,
    task: c.task ?? null,
    newlyFailing: c.newlyFailing ?? false,
    failingForScans: c.failingForScans ?? 0,
  }) as ComplianceControl;

  return {
    ...data,
    accountId: data.accountId ?? '',
    scanStatus: data.scanStatus ?? 'NONE',
    scanError: data.scanError ?? null,
    regions: asArray(data.regions),
    frameworksRequested: asArray(data.frameworksRequested),
    score: data.score ?? null,
    assessable: data.assessable ?? false,
    scoreLabel: data.scoreLabel ?? 'Unable to assess',
    counts: { ...EMPTY_COUNTS, ...(data.counts ?? {}) },
    frameworks: asArray(data.frameworks),
    controls: asArray(data.controls).map(normalizeControl),
    topRisks: asArray(data.topRisks).map(normalizeControl),
    trend: asArray(data.trend),
    program: { ...EMPTY_PROGRAM, ...(data.program ?? {}) },
    notes: asArray(data.notes),
  } as ComplianceReport;
};

const providerPath = (provider: CloudProvider): string => {
  if (provider === 'GCP') return '/api/xamops/gcp/security';
  if (provider === 'Azure') return '/api/xamops/azure/security';
  return '/api/xamops/security';
};

interface UseComplianceOpsOptions {
  accountId: string | null;
  provider: CloudProvider;
  trendDays?: number;
}

export const useComplianceOps = ({
  accountId,
  provider,
  trendDays = 90,
}: UseComplianceOpsOptions) => {
  const [report, setReport] = useState<ComplianceReport | null>(null);
  const [scanStatus, setScanStatus] = useState<ScanStatus>({ status: 'UNKNOWN' });
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<ComplianceFilters>(DEFAULT_FILTERS);

  const abortRef = useRef<AbortController | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const base = providerPath(provider);

  const loadReport = useCallback(async () => {
    if (!accountId) {
      setIsLoading(false);
      setReport(null);
      return;
    }
    abortRef.current?.abort();
    abortRef.current = new AbortController();
    const { signal } = abortRef.current;

    setError(null);
    try {
      const res = await fetch(
        `/api/xamops/compliance/report?accountId=${encodeURIComponent(accountId)}`
        + `&trendDays=${trendDays}`,
        { credentials: 'include', signal },
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      setReport(normalizeReport(await res.json()));
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return;
      console.error('[ComplianceOps] report load failed:', err);
      setError(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setIsLoading(false);
    }
  }, [accountId, trendDays]);

  const fetchScanStatus = useCallback(async (id: string): Promise<string> => {
    try {
      const res = await fetch(`${base}/prowler/status?accountId=${encodeURIComponent(id)}`, {
        credentials: 'include',
      });
      if (!res.ok) return 'UNKNOWN';
      const data: ScanStatus = await res.json();
      setScanStatus(data);
      return data.status;
    } catch {
      return 'UNKNOWN';
    }
  }, [base]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const startPolling = useCallback((id: string) => {
    stopPolling();
    pollRef.current = setInterval(async () => {
      const s = await fetchScanStatus(id);
      if (s !== 'RUNNING') {
        stopPolling();
        setIsRefreshing(false);
        // The scan writes a new compliance_scan row on completion, so the report has to
        // be re-read rather than patched from the status payload.
        await loadReport();
      }
    }, 5000);
  }, [fetchScanStatus, loadReport, stopPolling]);

  const triggerScan = useCallback(async () => {
    if (!accountId) return;
    setIsRefreshing(true);
    setError(null);
    try {
      const res = await fetch(
        `${base}/prowler/scan?accountId=${encodeURIComponent(accountId)}`,
        { method: 'POST', credentials: 'include' },
      );
      const data = await res.json();
      if (data.status === 'STARTED') {
        setScanStatus({ status: 'RUNNING', message: data.message });
        startPolling(accountId);
      } else if (data.status === 'ALREADY_RUNNING') {
        setScanStatus({ status: 'RUNNING', message: data.message });
        startPolling(accountId);
      } else {
        setIsRefreshing(false);
        setError(data.message ?? 'Could not start the scan.');
      }
    } catch (err) {
      console.error('[ComplianceOps] scan trigger failed:', err);
      setIsRefreshing(false);
      setError(err instanceof Error ? err.message : 'Could not start the scan.');
    }
  }, [accountId, base, startPolling]);

  useEffect(() => {
    if (!accountId) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    void Promise.all([loadReport(), fetchScanStatus(accountId)]);
  }, [accountId, loadReport, fetchScanStatus]);

  // Pick a scan already in flight back up on mount or account switch.
  useEffect(() => {
    if (scanStatus.status === 'RUNNING' && accountId && !pollRef.current) {
      setIsRefreshing(true);
      startPolling(accountId);
    }
  }, [scanStatus.status, accountId, startPolling]);

  useEffect(() => () => {
    stopPolling();
    abortRef.current?.abort();
  }, [stopPolling]);

  const controls = useMemo(() => report?.controls ?? [], [report]);

  /** Distinct services present, for the filter strip. */
  const services = useMemo(() => {
    const set = new Set<string>();
    controls.forEach((c) => { if (c.service) set.add(c.service); });
    return Array.from(set).sort();
  }, [controls]);

  const frameworkOptions = useMemo(() => {
    const map = new Map<string, string>();
    (report?.frameworks ?? []).forEach((f) => map.set(frameworkKey(f.framework), f.framework));
    return Array.from(map.entries()).map(([key, raw]) => ({ key, raw }));
  }, [report]);

  const filteredControls = useMemo(() => {
    const term = filters.search.trim().toLowerCase();

    return controls
      .filter((c) => {
        if (filters.status === 'OPEN' && c.status !== 'FAIL' && c.status !== 'MANUAL') return false;
        if (filters.status !== 'All' && filters.status !== 'OPEN' && c.status !== filters.status) {
          return false;
        }
        if (filters.severity !== 'All'
          && (c.severity ?? '').toLowerCase() !== filters.severity.toLowerCase()) return false;
        if (filters.framework !== 'All'
          && !c.frameworks.some((f) => frameworkKey(f.framework) === filters.framework)) return false;
        if (filters.service !== 'All' && c.service !== filters.service) return false;
        if (filters.unownedOnly && c.owner) return false;
        if (term) {
          const haystack = [
            c.checkId,
            c.title ?? '',
            c.service ?? '',
            c.remediation ?? '',
            ...c.frameworks.flatMap((f) => [f.framework, ...f.controls]),
          ].join(' ').toLowerCase();
          if (!haystack.includes(term)) return false;
        }
        return true;
      })
      // Server order is already risk-first; re-sort so filtering never reshuffles.
      .sort((a, b) => {
        const rank = (s: ComplianceStatus) => (s === 'FAIL' ? 0 : s === 'MANUAL' ? 1 : s === 'WAIVED' ? 2 : 3);
        if (rank(a.status) !== rank(b.status)) return rank(a.status) - rank(b.status);
        const sa = SEVERITY_RANK[(a.severity ?? 'low').toLowerCase()] ?? 4;
        const sb = SEVERITY_RANK[(b.severity ?? 'low').toLowerCase()] ?? 4;
        if (sa !== sb) return sa - sb;
        if (b.failedResources !== a.failedResources) return b.failedResources - a.failedResources;
        return a.checkId.localeCompare(b.checkId);
      });
  }, [controls, filters]);

  const setFilter = useCallback(
    <K extends keyof ComplianceFilters>(key: K, value: ComplianceFilters[K]) => {
      setFilters((prev) => ({ ...prev, [key]: value }));
    },
    [],
  );

  const resetFilters = useCallback(() => setFilters(DEFAULT_FILTERS), []);

  return {
    report,
    controls,
    filteredControls,
    services,
    frameworkOptions,
    scanStatus,
    isLoading,
    isRefreshing,
    error,
    filters,
    setFilter,
    resetFilters,
    loadReport,
    triggerScan,
  };
};
