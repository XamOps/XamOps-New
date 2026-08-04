/**
 * useWarData Hook
 *
 * Loads the WAR (Workload Attack-surface Review) report for the selected account
 * and owns all client-side filtering.
 *
 * API Endpoints:
 * - GET  /api/xamops/war/report?accountId={accountId}&forceRefresh={boolean}
 * - POST /api/xamops/war/scan?accountId={accountId}
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type {
  WarFinding,
  WarReport,
  WarStatus,
} from '../types/war';
import { isOpen } from '../types/war';

interface UseWarDataOptions {
  selectedAccountId: string | null;
}

export interface WarFilters {
  /** Attack path enum name, or 'All'. */
  attackPath: string;
  category: string;
  severity: string;
  status: WarStatus | 'All' | 'OPEN';
  search: string;
}

interface UseWarDataReturn {
  report: WarReport | null;
  findings: WarFinding[];
  filteredFindings: WarFinding[];
  isLoading: boolean;
  isRefreshing: boolean;
  error: string | null;
  filters: WarFilters;
  setFilter: <K extends keyof WarFilters>(key: K, value: WarFilters[K]) => void;
  resetFilters: () => void;
  loadReport: (forceRefresh?: boolean) => Promise<void>;
  exportCsv: () => void;
}

const DEFAULT_FILTERS: WarFilters = {
  attackPath: 'All',
  category: 'All',
  severity: 'All',
  // Defaulting to open findings is deliberate: the page exists to show what is
  // exposed, not to list 86 controls the user has to scan through.
  status: 'OPEN',
  search: '',
};

const SEVERITY_RANK: Record<string, number> = {
  critical: 0,
  high: 1,
  medium: 2,
  low: 3,
};

const asArray = <T,>(value: T[] | null | undefined): T[] => (Array.isArray(value) ? value : []);

/**
 * Fills in anything the payload is missing before it reaches a component.
 *
 * A cached report can predate a field the UI now renders — that is exactly how a null
 * `blockedActions` crashed the page on `null.length`. The backend versions its cache
 * keys to prevent it, but a component should not be one absent array away from taking
 * the whole page down, so the boundary normalises rather than trusting the shape.
 */
const normalizeReport = (data: Partial<WarReport> | null): WarReport | null => {
  if (!data) return null;

  const findings = asArray(data.findings).map((f) => ({
    ...f,
    evidence: asArray(f?.evidence),
    attackPaths: asArray(f?.attackPaths),
    attackPathLabels: asArray(f?.attackPathLabels),
  })) as WarReport['findings'];

  const statusCounts = data.statusCounts ?? {};

  // Older payloads carry neither field; derive them so the score gate still behaves.
  const verifiedControls = data.verifiedControls
    ?? (statusCounts.PASS ?? 0) + (statusCounts.FAIL ?? 0) + (statusCounts.WARN ?? 0);
  const scoreable = verifiedControls + (statusCounts.UNKNOWN ?? 0);

  return {
    ...data,
    scannedRegions: asArray(data.scannedRegions),
    statusCounts,
    severityCounts: data.severityCounts ?? {},
    attackPaths: asArray(data.attackPaths).map((p) => ({
      ...p,
      unverified: p?.unverified ?? 0,
      topControlIds: asArray(p?.topControlIds),
    })) as WarReport['attackPaths'],
    categories: asArray(data.categories),
    findings,
    topRisks: asArray(data.topRisks).map((f) => ({
      ...f,
      evidence: asArray(f?.evidence),
      attackPaths: asArray(f?.attackPaths),
      attackPathLabels: asArray(f?.attackPathLabels),
    })) as WarReport['topRisks'],
    deniedActions: asArray(data.deniedActions),
    blockedActions: asArray(data.blockedActions),
    scanWarnings: asArray(data.scanWarnings),
    verifiedControls,
    assessable: data.assessable ?? (verifiedControls > 0 && verifiedControls * 2 >= scoreable),
    blockedReason: data.blockedReason ?? null,
  } as WarReport;
};

/** Exported for tests only — see useWarData.normalize.test.ts. */
export const __normalizeReportForTest = normalizeReport;

export const useWarData = ({ selectedAccountId }: UseWarDataOptions): UseWarDataReturn => {
  const [report, setReport] = useState<WarReport | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<WarFilters>(DEFAULT_FILTERS);

  const abortRef = useRef<AbortController | null>(null);

  const loadReport = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        setReport(null);
        return;
      }

      abortRef.current?.abort();
      abortRef.current = new AbortController();
      const { signal } = abortRef.current;

      setError(null);
      if (forceRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }

      const url = `/api/xamops/war/report?accountId=${encodeURIComponent(selectedAccountId)}`
        + `&forceRefresh=${forceRefresh}`;

      try {
        const response = await fetch(url, { credentials: 'include', signal });

        if (response.status === 503) {
          throw new Error(
            'The scan is taking longer than expected and is still running. '
            + 'Retry in a moment to pick up the completed report.',
          );
        }
        if (response.status === 404) {
          throw new Error('This account is not registered for scanning.');
        }
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const data = await response.json();
        setReport(normalizeReport(data));
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        const message = err instanceof Error ? err.message : 'Unknown error';
        console.error('[WAR] load failed:', err);
        setError(message);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId],
  );

  useEffect(() => {
    loadReport(false);
    return () => abortRef.current?.abort();
  }, [loadReport]);

  const findings = useMemo(() => report?.findings ?? [], [report]);

  const filteredFindings = useMemo(() => {
    const term = filters.search.trim().toLowerCase();

    return findings
      .filter((f) => {
        if (filters.status === 'OPEN' && !isOpen(f.status)) return false;
        if (filters.status !== 'All' && filters.status !== 'OPEN' && f.status !== filters.status) {
          return false;
        }
        if (filters.attackPath !== 'All' && !f.attackPaths.includes(filters.attackPath)) {
          return false;
        }
        if (filters.category !== 'All' && f.category !== filters.category) return false;
        if (
          filters.severity !== 'All'
          && f.severity?.toLowerCase() !== filters.severity.toLowerCase()
        ) {
          return false;
        }
        if (term) {
          const haystack = [
            f.controlId,
            f.title,
            f.description,
            f.subCategory,
            f.summary ?? '',
            f.tools,
            ...f.evidence,
          ]
            .join(' ')
            .toLowerCase();
          if (!haystack.includes(term)) return false;
        }
        return true;
      })
      // Highest risk first, then advisory order so the list is stable between loads.
      .sort((a, b) => {
        if (b.riskWeight !== a.riskWeight) return b.riskWeight - a.riskWeight;
        const rankA = SEVERITY_RANK[a.severity?.toLowerCase()] ?? 4;
        const rankB = SEVERITY_RANK[b.severity?.toLowerCase()] ?? 4;
        if (rankA !== rankB) return rankA - rankB;
        return a.ordinal - b.ordinal;
      });
  }, [findings, filters]);

  const setFilter = useCallback(
    <K extends keyof WarFilters>(key: K, value: WarFilters[K]) => {
      setFilters((prev) => ({ ...prev, [key]: value }));
    },
    [],
  );

  const resetFilters = useCallback(() => setFilters(DEFAULT_FILTERS), []);

  const exportCsv = useCallback(() => {
    if (!report) return;

    const escape = (value: string) => `"${String(value ?? '').replace(/"/g, '""')}"`;
    const header = [
      'Control ID', 'Category', 'Sub-Category', 'Control', 'Severity', 'Status',
      'Attack Paths', 'Affected', 'Region', 'Finding', 'Business Impact', 'Evidence', 'Reference',
    ];

    const rows = report.findings.map((f) => [
      f.controlId,
      f.category,
      f.subCategory,
      f.title,
      f.severity,
      f.status,
      f.attackPathLabels.join('; '),
      String(f.affectedCount),
      f.region ?? 'Global',
      f.summary ?? '',
      f.businessImpact,
      f.evidence.join(' | '),
      f.reference,
    ].map(escape).join(','));

    const csv = [header.map(escape).join(','), ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    const stamp = (report.generatedAt ?? '').slice(0, 10) || 'unknown-date';
    link.download = `war-report-${report.accountId}-${stamp}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  }, [report]);

  return {
    report,
    findings,
    filteredFindings,
    isLoading,
    isRefreshing,
    error,
    filters,
    setFilter,
    resetFilters,
    loadReport,
    exportCsv,
  };
};
