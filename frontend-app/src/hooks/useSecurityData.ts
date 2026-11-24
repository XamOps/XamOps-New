/**
 * useSecurityData Hook
 *
 * Custom hook to fetch and manage security findings data
 * Converted from: security.html (securityCenter() Alpine.js component)
 *
 * API Endpoints:
 * - GET /api/xamops/security/findings?accountId={accountId}&forceRefresh={boolean}
 * - GET /api/xamops/security/prowler/status?accountId={accountId}
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { SecurityFinding, ProwlerStatus } from '../types/security';

interface UseSecurityDataOptions {
  selectedAccountId: string | null;
}

interface UseSecurityDataReturn {
  findings: SecurityFinding[];
  isLoading: boolean;
  isRefreshing: boolean;
  /**
   * Why the last load failed ("HTTP 500", a network error's message), or null. The Findings tab shows
   * it in a banner with Retry. Silent reloads never set it.
   */
  error: string | null;
  prowlerStatus: ProwlerStatus;
  loadFindings: (forceRefresh?: boolean, silent?: boolean) => Promise<void>;
  resolveFinding: (finding: SecurityFinding) => Promise<void>;
  restoreFinding: (finding: SecurityFinding) => Promise<void>;
  resolvingKeys: string[];
  lastResolved: SecurityFinding | null;
  dismissLastResolved: () => void;
}

/**
 * The identity of a finding, matching SecurityFindingKey on the server.
 *
 * A finding has no id: the same problem reaches this page from Prowler, from the describe-based
 * checks and from the event stream, and only the last of those has a row anywhere. Resource plus
 * control is what all three agree on, and it is already the pair the server dedupes them by.
 */
export const findingKey = (finding: SecurityFinding): string =>
  `${finding.resourceId ?? ''}|${finding.controlId ?? ''}`;

export const useSecurityData = ({
  selectedAccountId,
}: UseSecurityDataOptions): UseSecurityDataReturn => {
  const [findings, setFindings] = useState<SecurityFinding[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [prowlerStatus, setProwlerStatus] = useState<ProwlerStatus>({ status: 'UNKNOWN' });
  const [resolvingKeys, setResolvingKeys] = useState<string[]>([]);
  const [lastResolved, setLastResolved] = useState<SecurityFinding | null>(null);

  const pollIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  /**
   * Process and sort findings by severity
   */
  const processFindings = useCallback((data: any): SecurityFinding[] => {
    if (!data) return [];

    const findingsArray = Array.isArray(data) ? data : (data.findings || data);

    if (!Array.isArray(findingsArray)) return [];

    const validFindings = findingsArray.filter(
      (f: any) => f && typeof f === 'object' && f.severity
    );

    return validFindings.sort((a, b) => {
      const severityOrder: Record<string, number> = {
        CRITICAL: 0,
        HIGH: 1,
        MEDIUM: 2,
        LOW: 3,
      };
      const severityA = (a.severity || '').trim().toUpperCase();
      const severityB = (b.severity || '').trim().toUpperCase();
      return (severityOrder[severityA] || 4) - (severityOrder[severityB] || 4);
    });
  }, []);

  /**
   * Load security findings
   */
  const loadFindings = useCallback(
    async (forceRefresh: boolean = false, silent: boolean = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        return;
      }

      // Cancel any in-flight request before starting a new one
      abortControllerRef.current?.abort();
      abortControllerRef.current = new AbortController();
      const { signal } = abortControllerRef.current;

      if (!silent) {
        setIsLoading(true);
        setError(null);
      }

      if (forceRefresh) {
        setIsRefreshing(true);
      }

      const url = `/api/xamops/security/findings?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;

      try {
        const response = await fetch(url, {
          credentials: 'include',
          signal,
        });

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        const processed = processFindings(data);
        setFindings(processed);
        setError(null);
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        console.error('Fetch error:', err);
        // A failed silent reload keeps the list it had. A failed visible one empties it and says
        // why in the tab's banner, which offers Retry: a blocking alert() did neither.
        if (!silent) {
          setFindings([]);
          setError(err instanceof Error && err.message ? err.message : 'Unknown error');
        }
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, processFindings]
  );

  /**
   * Mark a finding fixed.
   *
   * Optimistic: the row leaves the list before the request returns, and is put back if the
   * request fails. Reloading instead would be a multi-region scan on the fresh path, so the
   * honest choice is to show the outcome immediately and undo it on error rather than make the
   * user wait to find out whether their click landed.
   */
  const resolveFinding = useCallback(
    async (finding: SecurityFinding) => {
      if (!selectedAccountId) return;
      const key = findingKey(finding);
      if (resolvingKeys.includes(key)) return;

      setResolvingKeys((prev) => [...prev, key]);
      setFindings((prev) => prev.filter((f) => findingKey(f) !== key));

      const params = new URLSearchParams({
        accountId: selectedAccountId,
        resourceId: finding.resourceId ?? '',
        controlId: finding.controlId ?? '',
        severity: finding.severity ?? '',
        title: finding.description ?? '',
      });

      try {
        const response = await fetch(`/api/xamops/security/findings/resolve?${params}`, {
          method: 'POST',
          credentials: 'include',
        });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        setLastResolved(finding);
      } catch (err) {
        // Put it back. A finding that silently vanished because a request failed is the one
        // outcome worse than not having the button at all.
        setFindings((prev) => [...prev, finding]);
        console.error('Could not mark the finding fixed:', err);
        alert('Could not mark that finding fixed. It is still on the list.');
      } finally {
        setResolvingKeys((prev) => prev.filter((k) => k !== key));
      }
    },
    [selectedAccountId, resolvingKeys]
  );

  /**
   * Put a finding back on the list.
   *
   * Marking the wrong row fixed is one misclick on a dense table, and for a finding that really is
   * fixed no scan will ever contradict it — so without this the mistake would be permanent.
   */
  const restoreFinding = useCallback(
    async (finding: SecurityFinding) => {
      if (!selectedAccountId) return;
      const params = new URLSearchParams({
        accountId: selectedAccountId,
        resourceId: finding.resourceId ?? '',
        controlId: finding.controlId ?? '',
      });

      try {
        const response = await fetch(`/api/xamops/security/findings/restore?${params}`, {
          method: 'POST',
          credentials: 'include',
        });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        setFindings((prev) =>
          prev.some((f) => findingKey(f) === findingKey(finding)) ? prev : [...prev, finding]
        );
        setLastResolved(null);
      } catch (err) {
        console.error('Could not restore the finding:', err);
        alert('Could not put that finding back. Refresh to see the current list.');
      }
    },
    [selectedAccountId]
  );

  const dismissLastResolved = useCallback(() => setLastResolved(null), []);

  /**
   * Check Prowler scan status
   */
  const checkProwlerStatus = useCallback(async () => {
    if (!selectedAccountId) return;

    try {
      const response = await fetch(
        `/api/xamops/security/prowler/status?accountId=${selectedAccountId}`,
        { credentials: 'include' }
      );
      const data = await response.json();

      // Check if scan just finished (transitioned from RUNNING to COMPLETED)
      if (data.status === 'COMPLETED' && prowlerStatus.status === 'RUNNING') {
        // Force refresh to merge new Prowler findings with manual checks
        loadFindings(true, true);
      }

      setProwlerStatus(data);
    } catch (e) {
      console.error('Polling error', e);
    }
  }, [selectedAccountId, prowlerStatus.status, loadFindings]);

  /**
   * Start polling for Prowler status
   */
  const startPolling = useCallback(() => {
    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
    }

    // Run immediately
    checkProwlerStatus();

    // Then poll every 5 seconds
    pollIntervalRef.current = setInterval(checkProwlerStatus, 5000);
  }, [checkProwlerStatus]);

  // Search, filters, grouping and the CSV export live in the Findings tab
  // (components/Security/Findings/findingsModel.ts), over `findings` as loaded.

  // Initialize data on mount
  useEffect(() => {
    if (selectedAccountId) {
      console.log('[Security] Loading findings...');
      loadFindings(false);
      startPolling();
    }

    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, [selectedAccountId]); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    findings,
    isLoading,
    isRefreshing,
    error,
    prowlerStatus,
    loadFindings,
    resolveFinding,
    restoreFinding,
    resolvingKeys,
    lastResolved,
    dismissLastResolved,
  };
};

export default useSecurityData;
