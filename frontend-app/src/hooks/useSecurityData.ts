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
import type { SecurityFinding, ProwlerStatus, SeverityLevel, SeverityCounts } from '../types/security';

interface UseSecurityDataOptions {
  selectedAccountId: string | null;
}

interface UseSecurityDataReturn {
  findings: SecurityFinding[];
  filteredFindings: SecurityFinding[];
  isLoading: boolean;
  isRefreshing: boolean;
  isExporting: boolean;
  prowlerStatus: ProwlerStatus;
  selectedCompliance: string;
  complianceFrameworks: string[];
  severityCounts: SeverityCounts;
  openSeverities: SeverityLevel[];
  loadFindings: (forceRefresh?: boolean, silent?: boolean) => Promise<void>;
  exportData: () => void;
  setSelectedCompliance: (compliance: string) => void;
  toggleSeverity: (severity: SeverityLevel) => void;
  getFindingsBySeverity: (severity: SeverityLevel) => SecurityFinding[];
}

export const useSecurityData = ({
  selectedAccountId,
}: UseSecurityDataOptions): UseSecurityDataReturn => {
  const [findings, setFindings] = useState<SecurityFinding[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [isExporting, setIsExporting] = useState<boolean>(false);
  const [selectedCompliance, setSelectedCompliance] = useState<string>('All');
  const [openSeverities, setOpenSeverities] = useState<SeverityLevel[]>([]);
  const [prowlerStatus, setProwlerStatus] = useState<ProwlerStatus>({ status: 'UNKNOWN' });

  const pollIntervalRef = useRef<NodeJS.Timeout | null>(null);

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

      if (!silent) {
        setIsLoading(true);
      }

      if (forceRefresh) {
        setIsRefreshing(true);
      }

      const url = `/api/xamops/security/findings?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;

      try {
        const response = await fetch(url, {
          credentials: 'include',
        });

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const data = await response.json();
        const processed = processFindings(data);
        setFindings(processed);
      } catch (err) {
        console.error('Fetch error:', err);
        if (!silent) {
          setFindings([]);
          alert(`Failed to load security findings: ${err instanceof Error ? err.message : 'Unknown error'}`);
        }
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, processFindings]
  );

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
        // Reload findings silently in background
        loadFindings(false, true);
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

  /**
   * Export findings to CSV
   */
  const exportData = useCallback(() => {
    setIsExporting(true);

    const filtered = selectedCompliance === 'All' || !selectedCompliance
      ? findings
      : findings.filter((f) => {
          const framework = `${f.complianceFramework || ''} ${f.controlId || ''}`.trim();
          return framework === selectedCompliance;
        });

    const headers = [
      'Resource ID',
      'Region',
      'Category',
      'Severity',
      'Description',
      'Compliance Framework',
      'Control ID',
    ];

    const rows = filtered.map((finding) => [
      `"${finding.resourceId || ''}"`,
      `"${finding.region || ''}"`,
      `"${finding.category || ''}"`,
      `"${finding.severity || ''}"`,
      `"${(finding.description || '').replace(/"/g, '""')}"`,
      `"${finding.complianceFramework || ''}"`,
      `"${finding.controlId || ''}"`,
    ]);

    const csvContent =
      'data:text/csv;charset=utf-8,' +
      headers.join(',') +
      '\n' +
      rows.map((e) => e.join(',')).join('\n');

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', 'security_findings_export.csv');
    document.body.appendChild(link);
    link.click();
    link.remove();

    setIsExporting(false);
  }, [findings, selectedCompliance]);

  /**
   * Toggle severity section open/closed
   */
  const toggleSeverity = useCallback((severity: SeverityLevel) => {
    setOpenSeverities((prev) =>
      prev.includes(severity) ? prev.filter((s) => s !== severity) : [...prev, severity]
    );
  }, []);

  /**
   * Get filtered findings based on selected compliance
   */
  const filteredFindings = selectedCompliance === 'All' || !selectedCompliance
    ? findings
    : findings.filter((f) => {
        const framework = `${f.complianceFramework || ''} ${f.controlId || ''}`.trim();
        return framework === selectedCompliance;
      });

  /**
   * Get unique compliance frameworks
   */
  const complianceFrameworks = [...new Set(
    findings.map((f) => `${f.complianceFramework || ''} ${f.controlId || ''}`.trim())
  )].filter(Boolean).sort();

  /**
   * Get severity counts from filtered findings
   */
  const severityCounts: SeverityCounts = filteredFindings.reduce(
    (acc, finding) => {
      if (finding && finding.severity) {
        const severity = finding.severity.trim().toUpperCase();
        const properCase = (severity.charAt(0) + severity.slice(1).toLowerCase()) as keyof SeverityCounts;
        if (properCase in acc) {
          acc[properCase] = (acc[properCase] || 0) + 1;
        }
      }
      return acc;
    },
    { Critical: 0, High: 0, Medium: 0, Low: 0 } as SeverityCounts
  );

  /**
   * Get findings by severity level
   */
  const getFindingsBySeverity = useCallback(
    (severity: SeverityLevel): SecurityFinding[] => {
      return filteredFindings.filter(
        (f) => f.severity && f.severity.trim().toLowerCase() === severity.toLowerCase()
      );
    },
    [filteredFindings]
  );

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
    filteredFindings,
    isLoading,
    isRefreshing,
    isExporting,
    prowlerStatus,
    selectedCompliance,
    complianceFrameworks,
    severityCounts,
    openSeverities,
    loadFindings,
    exportData,
    setSelectedCompliance,
    toggleSeverity,
    getFindingsBySeverity,
  };
};

export default useSecurityData;
