/**
 * useGcpSecurityData Hook
 *
 * Custom hook for fetching and managing GCP Security Center data
 */

import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';
import { escapeHtml } from '../utils/sanitizeHtml';
import type {
  GcpSecurityFinding,
  GcpIamDrift,
  GcpContainerVulnerability,
  GcpSeverityLevel,
  GcpSeverityCounts,
} from '../types/gcpSecurity';

interface UseGcpSecurityDataOptions {
  selectedAccountId: string | null;
}

/** Sections that can be included in an exported report. */
export type GcpExportSection = 'findings' | 'iamDrift' | 'vulnerabilities';

interface UseGcpSecurityDataReturn {
  findings: GcpSecurityFinding[];
  filteredFindings: GcpSecurityFinding[];
  iamDrift: GcpIamDrift[];
  vulnerabilities: GcpContainerVulnerability[];
  isLoading: boolean;
  isRefreshing: boolean;
  isExporting: boolean;
  selectedCategory: string;
  categories: string[];
  severityCounts: GcpSeverityCounts;
  openSeverities: GcpSeverityLevel[];
  openSections: string[];
  isModalOpen: boolean;
  selectedFinding: GcpSecurityFinding | null;
  remediationSteps: string;
  loadAllData: (forceRefresh?: boolean) => void;
  exportData: (sections?: GcpExportSection[]) => void;
  setSelectedCategory: (category: string) => void;
  toggleSeverity: (severity: GcpSeverityLevel) => void;
  toggleSection: (section: string) => void;
  openRemediationModal: (finding: GcpSecurityFinding) => void;
  closeModal: () => void;
  getFindingsBySeverity: (severity: GcpSeverityLevel) => GcpSecurityFinding[];
}

export const useGcpSecurityData = ({
  selectedAccountId,
}: UseGcpSecurityDataOptions): UseGcpSecurityDataReturn => {
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isExporting, setIsExporting] = useState(false);
  const [findings, setFindings] = useState<GcpSecurityFinding[]>([]);
  const [iamDrift, setIamDrift] = useState<GcpIamDrift[]>([]);
  const [vulnerabilities, setVulnerabilities] = useState<GcpContainerVulnerability[]>([]);
  const [openSeverities, setOpenSeverities] = useState<GcpSeverityLevel[]>(['CRITICAL', 'HIGH']);
  const [openSections, setOpenSections] = useState<string[]>(['iamDrift', 'vulnerabilities']);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [selectedFinding, setSelectedFinding] = useState<GcpSecurityFinding | null>(null);
  const [remediationSteps, setRemediationSteps] = useState('');
  const [selectedCategory, setSelectedCategory] = useState('All');
  const lastFetchedId = useRef<string | null>(null);

  /**
   * Load all security data
   */
  const loadAllData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        return;
      }

      if (!forceRefresh && lastFetchedId.current === selectedAccountId) {
        setIsLoading(false);
        return;
      }

      setIsLoading(!forceRefresh);
      setIsRefreshing(forceRefresh);

      try {
        const [findingsData, iamDriftData, vulnerabilitiesData] = await Promise.all([
          apiClient.get<GcpSecurityFinding[]>(`/api/xamops/gcp/security/findings?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`),
          apiClient.get<GcpIamDrift[]>(`/api/xamops/gcp/security/iam-drift?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`),
          apiClient.get<GcpContainerVulnerability[]>(
            `/api/xamops/gcp/security/container-vulnerabilities?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`
          ),
        ]);

        // Process and sort findings by severity
        const processedFindings = (findingsData || []).sort((a: GcpSecurityFinding, b: GcpSecurityFinding) => {
          const severityOrder: Record<GcpSeverityLevel, number> = {
            CRITICAL: 0,
            HIGH: 1,
            MEDIUM: 2,
            LOW: 3,
          };
          return (severityOrder[a.severity] || 4) - (severityOrder[b.severity] || 4);
        });

        setFindings(processedFindings);
        setIamDrift(iamDriftData || []);
        setVulnerabilities(vulnerabilitiesData || []);
        lastFetchedId.current = selectedAccountId;
      } catch (err) {
        console.error('Failed to load GCP security data:', err);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId]
  );

  /**
   * Get unique categories from findings
   */
  const categories = useMemo(() => {
    return [...new Set(findings.map((f) => f.category))].sort();
  }, [findings]);

  /**
   * Filter findings by selected category
   */
  const filteredFindings = useMemo(() => {
    if (selectedCategory === 'All') {
      return findings;
    }
    return findings.filter((f) => f.category === selectedCategory);
  }, [findings, selectedCategory]);

  /**
   * Export the selected sections to a single CSV file.
   *
   * Each requested section is written as its own titled block with its own
   * header row, separated by a blank line, so one download can carry findings,
   * IAM drift, and container vulnerabilities together. Findings respect the
   * active category filter; the other two sections are always exported in full.
   */
  const exportData = useCallback(
    (sections: GcpExportSection[] = ['findings', 'iamDrift', 'vulnerabilities']) => {
      // csv-escape: wrap in quotes and double any embedded quotes.
      const cell = (value: unknown) => `"${String(value ?? '').replace(/"/g, '""')}"`;
      const active = new Set(sections);
      const blocks: string[] = [];

      if (active.has('findings')) {
        const headers = ['Category', 'Severity', 'Description', 'Resource', 'Compliance'];
        const rows = filteredFindings.map((f) => [
          cell(f.category),
          cell(f.severity),
          cell(f.description),
          cell(f.resourceName),
          cell(f.complianceControl || 'GCP Best Practice'),
        ].join(','));
        blocks.push(['Security Findings', headers.join(','), ...rows].join('\n'));
      }

      if (active.has('iamDrift')) {
        const headers = ['Resource', 'Member', 'Role', 'Risk'];
        const rows = iamDrift.flatMap((drift) =>
          (drift.driftDetails || []).map((d) => [
            cell(drift.resourceName),
            cell(d.member),
            cell(d.role),
            cell(d.status),
          ].join(','))
        );
        blocks.push(['IAM Policy Drift', headers.join(','), ...rows].join('\n'));
      }

      if (active.has('vulnerabilities')) {
        const headers = ['Severity', 'CVE', 'Package', 'Version', 'Image', 'Description'];
        const rows = vulnerabilities.map((v) => [
          cell(v.severity),
          cell(v.cveId),
          cell(v.packageName),
          cell(v.version),
          cell(v.imageName),
          cell(v.description),
        ].join(','));
        blocks.push(['Container Vulnerabilities', headers.join(','), ...rows].join('\n'));
      }

      if (blocks.length === 0) return;

      setIsExporting(true);
      let url: string | null = null;
      try {
        // Use a Blob object URL rather than a `data:` URI. A data URI is parsed
        // as a URL, so any `#` in a finding description (e.g. doc anchor links)
        // would start a fragment and silently truncate the rest of the file.
        // \uFEFF BOM keeps Excel from mangling UTF-8 characters.
        const csvContent = '\uFEFF' + blocks.join('\n\n');
        const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
        url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.setAttribute('href', url);
        link.setAttribute('download', 'gcp_security_report.csv');
        document.body.appendChild(link);
        link.click();
        link.remove();
      } finally {
        if (url) URL.revokeObjectURL(url);
        setIsExporting(false);
      }
    },
    [filteredFindings, iamDrift, vulnerabilities]
  );

  /**
   * Calculate severity counts
   */
  const severityCounts = useMemo(() => {
    return filteredFindings.reduce(
      (acc, finding) => {
        acc[finding.severity] = (acc[finding.severity] || 0) + 1;
        return acc;
      },
      { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 } as GcpSeverityCounts
    );
  }, [filteredFindings]);

  /**
   * Get findings by severity level
   */
  const getFindingsBySeverity = useCallback(
    (severity: GcpSeverityLevel) => {
      return filteredFindings.filter((f) => f.severity === severity);
    },
    [filteredFindings]
  );

  /**
   * Toggle severity section
   */
  const toggleSeverity = useCallback((severity: GcpSeverityLevel) => {
    setOpenSeverities((prev) =>
      prev.includes(severity) ? prev.filter((s) => s !== severity) : [...prev, severity]
    );
  }, []);

  /**
   * Toggle custom section (IAM drift, vulnerabilities)
   */
  const toggleSection = useCallback((section: string) => {
    setOpenSections((prev) =>
      prev.includes(section) ? prev.filter((s) => s !== section) : [...prev, section]
    );
  }, []);

  /**
   * Open remediation modal
   */
  const openRemediationModal = useCallback((finding: GcpSecurityFinding) => {
    setSelectedFinding(finding);
    // Finding fields are Security Command Center payloads, not trusted markup:
    // escape them so the surrounding template is the only HTML that survives.
    setRemediationSteps(
      `<p><strong>1. Identify Resource:</strong> Locate the resource <strong>${escapeHtml(finding.resourceName)}</strong> in the GCP console.</p>` +
      `<p><strong>2. Analyze Finding:</strong> Understand the issue: <em>"${escapeHtml(finding.description)}"</em>.</p>` +
      `<p><strong>3. Follow Best Practices:</strong> Consult the official Google Cloud documentation for the <strong>${escapeHtml(finding.category)}</strong> service to apply the recommended fix.</p>` +
      `<p><strong>4. Verify Fix:</strong> Re-run the security scan to confirm the issue is resolved.</p>`
    );
    setIsModalOpen(true);
  }, []);

  /**
   * Close modal
   */
  const closeModal = useCallback(() => {
    setIsModalOpen(false);
    setSelectedFinding(null);
    setRemediationSteps('');
  }, []);

  // Load data on mount and when account changes
  useEffect(() => {
    loadAllData(false);
  }, [loadAllData]);

  return {
    findings,
    filteredFindings,
    iamDrift,
    vulnerabilities,
    isLoading,
    isRefreshing,
    isExporting,
    selectedCategory,
    categories,
    severityCounts,
    openSeverities,
    openSections,
    isModalOpen,
    selectedFinding,
    remediationSteps,
    loadAllData,
    exportData,
    setSelectedCategory,
    toggleSeverity,
    toggleSection,
    openRemediationModal,
    closeModal,
    getFindingsBySeverity,
  };
};
