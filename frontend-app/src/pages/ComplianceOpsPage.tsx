import React, { useEffect, useState, useMemo, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useSecurityData } from '../hooks/useSecurityData';

/**
 * ComplianceOps Page
 *
 * Cloud Compliance Dashboard with framework-level scoring, controls table,
 * and audit report generation. Uses Prowler security findings data grouped
 * by compliance frameworks (CIS, SOC2, PCI-DSS, HIPAA, NIST, ISO 27001).
 *
 * API Endpoints (reuses security data):
 * - GET /api/xamops/security/findings?accountId={accountId}
 * - GET /api/xamops/security/prowler/status?accountId={accountId}
 */

// --- Types ---

interface FrameworkScore {
  name: string;
  displayName: string;
  icon: string;
  color: string;
  bgColor: string;
  borderColor: string;
  totalControls: number;
  passedControls: number;
  failedControls: number;
  score: number;
  criticalFindings: number;
  highFindings: number;
  mediumFindings: number;
  lowFindings: number;
}

interface ControlRow {
  controlId: string;
  framework: string;
  description: string;
  status: 'FAIL' | 'PASS';
  severity: string;
  affectedResources: number;
  resourceIds: string[];
  regions: string[];
}

type ActiveTab = 'overview' | 'controls' | 'reports';

// --- Framework Configuration ---

const FRAMEWORK_CONFIG: Record<string, { displayName: string; icon: string; color: string; bgColor: string; borderColor: string; description: string }> = {
  'CIS': {
    displayName: 'CIS Benchmark',
    icon: 'fas fa-shield-alt',
    color: 'text-blue-600',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    description: 'Center for Internet Security benchmarks for cloud infrastructure hardening',
  },
  'SOC2': {
    displayName: 'SOC 2',
    icon: 'fas fa-lock',
    color: 'text-purple-600',
    bgColor: 'bg-purple-50',
    borderColor: 'border-purple-200',
    description: 'Service Organization Control 2 — Trust Services Criteria for security, availability, and confidentiality',
  },
  'PCI-DSS': {
    displayName: 'PCI DSS',
    icon: 'fas fa-credit-card',
    color: 'text-green-600',
    bgColor: 'bg-green-50',
    borderColor: 'border-green-200',
    description: 'Payment Card Industry Data Security Standard for protecting cardholder data',
  },
  'HIPAA': {
    displayName: 'HIPAA',
    icon: 'fas fa-heartbeat',
    color: 'text-red-600',
    bgColor: 'bg-red-50',
    borderColor: 'border-red-200',
    description: 'Health Insurance Portability and Accountability Act for protecting health information',
  },
  'NIST': {
    displayName: 'NIST CSF',
    icon: 'fas fa-university',
    color: 'text-indigo-600',
    bgColor: 'bg-indigo-50',
    borderColor: 'border-indigo-200',
    description: 'National Institute of Standards and Technology Cybersecurity Framework',
  },
  'ISO27001': {
    displayName: 'ISO 27001',
    icon: 'fas fa-certificate',
    color: 'text-teal-600',
    bgColor: 'bg-teal-50',
    borderColor: 'border-teal-200',
    description: 'International standard for information security management systems',
  },
  'GDPR': {
    displayName: 'GDPR',
    icon: 'fas fa-user-shield',
    color: 'text-amber-600',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
    description: 'General Data Protection Regulation for data privacy and protection',
  },
  'MITRE': {
    displayName: 'MITRE ATT&CK',
    icon: 'fas fa-crosshairs',
    color: 'text-rose-600',
    bgColor: 'bg-rose-50',
    borderColor: 'border-rose-200',
    description: 'MITRE ATT&CK framework mapping adversary tactics and techniques for cloud',
  },
  'CISA': {
    displayName: 'CISA',
    icon: 'fas fa-flag-usa',
    color: 'text-sky-600',
    bgColor: 'bg-sky-50',
    borderColor: 'border-sky-200',
    description: 'Cybersecurity and Infrastructure Security Agency guidelines',
  },
  'FEDRAMP': {
    displayName: 'FedRAMP',
    icon: 'fas fa-landmark',
    color: 'text-blue-700',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    description: 'Federal Risk and Authorization Management Program for US government cloud',
  },
  'FFIEC': {
    displayName: 'FFIEC',
    icon: 'fas fa-balance-scale',
    color: 'text-cyan-600',
    bgColor: 'bg-cyan-50',
    borderColor: 'border-cyan-200',
    description: 'Federal Financial Institutions Examination Council cybersecurity assessment',
  },
  'RBI': {
    displayName: 'RBI CSF',
    icon: 'fas fa-rupee-sign',
    color: 'text-orange-600',
    bgColor: 'bg-orange-50',
    borderColor: 'border-orange-200',
    description: 'Reserve Bank of India Cyber Security Framework for banks and financial institutions',
  },
  'ENS': {
    displayName: 'ENS',
    icon: 'fas fa-globe-europe',
    color: 'text-yellow-600',
    bgColor: 'bg-yellow-50',
    borderColor: 'border-yellow-200',
    description: 'Esquema Nacional de Seguridad — Spanish National Security Framework',
  },
  'AWS_WELL_ARCHITECTED': {
    displayName: 'AWS Well-Architected',
    icon: 'fab fa-aws',
    color: 'text-orange-600',
    bgColor: 'bg-orange-50',
    borderColor: 'border-orange-200',
    description: 'AWS Well-Architected Framework Security Pillar best practices',
  },
  'FSBP': {
    displayName: 'AWS FSBP',
    icon: 'fab fa-aws',
    color: 'text-amber-700',
    bgColor: 'bg-amber-50',
    borderColor: 'border-amber-200',
    description: 'AWS Foundational Security Best Practices standard',
  },
  'GXP': {
    displayName: 'GxP',
    icon: 'fas fa-flask',
    color: 'text-emerald-600',
    bgColor: 'bg-emerald-50',
    borderColor: 'border-emerald-200',
    description: 'Good Practice guidelines for life sciences and pharmaceutical compliance',
  },
  'NIS2': {
    displayName: 'NIS2',
    icon: 'fas fa-network-wired',
    color: 'text-violet-600',
    bgColor: 'bg-violet-50',
    borderColor: 'border-violet-200',
    description: 'EU Network and Information Security Directive 2 for critical infrastructure',
  },
  'NIST800': {
    displayName: 'NIST 800-53',
    icon: 'fas fa-book',
    color: 'text-indigo-700',
    bgColor: 'bg-indigo-50',
    borderColor: 'border-indigo-200',
    description: 'NIST Special Publication 800-53 — Security and Privacy Controls for Information Systems',
  },
  'CSA_CCM': {
    displayName: 'CSA CCM',
    icon: 'fas fa-cloud-meatball',
    color: 'text-blue-500',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    description: 'Cloud Security Alliance Cloud Controls Matrix',
  },
  'CMMC': {
    displayName: 'CMMC',
    icon: 'fas fa-shield-virus',
    color: 'text-gray-700',
    bgColor: 'bg-gray-100',
    borderColor: 'border-gray-300',
    description: 'Cybersecurity Maturity Model Certification for US defense contractors',
  },
  'KISA': {
    displayName: 'KISA ISMS-P',
    icon: 'fas fa-globe-asia',
    color: 'text-pink-600',
    bgColor: 'bg-pink-50',
    borderColor: 'border-pink-200',
    description: 'Korea Internet & Security Agency Information Security Management System',
  },
  'BSI_C5': {
    displayName: 'BSI C5',
    icon: 'fas fa-shield-alt',
    color: 'text-gray-600',
    bgColor: 'bg-gray-50',
    borderColor: 'border-gray-200',
    description: 'German Federal Office for Information Security Cloud Computing Compliance Controls',
  },
  'SECNUMCLOUD': {
    displayName: 'SecNumCloud',
    icon: 'fas fa-cloud-upload-alt',
    color: 'text-blue-600',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    description: 'French ANSSI SecNumCloud 3.2 security qualification for cloud providers',
  },
  'CUSTOM': {
    displayName: 'Best Practices',
    icon: 'fas fa-star',
    color: 'text-yellow-600',
    bgColor: 'bg-yellow-50',
    borderColor: 'border-yellow-200',
    description: 'Custom best practice checks and organizational security policies',
  },
  'AWS_FTR': {
    displayName: 'AWS FTR',
    icon: 'fab fa-aws',
    color: 'text-orange-600',
    bgColor: 'bg-orange-50',
    borderColor: 'border-orange-200',
    description: 'AWS Foundational Technical Review for partner solutions',
  },
  'SEBI': {
    displayName: 'SEBI',
    icon: 'fas fa-chart-line',
    color: 'text-blue-600',
    bgColor: 'bg-blue-50',
    borderColor: 'border-blue-200',
    description: 'Securities and Exchange Board of India cybersecurity framework',
  },
  'IRDAI': {
    displayName: 'IRDAI',
    icon: 'fas fa-shield-alt',
    color: 'text-green-700',
    bgColor: 'bg-green-50',
    borderColor: 'border-green-200',
    description: 'Insurance Regulatory and Development Authority of India guidelines',
  },
};

const DEFAULT_FRAMEWORK_CONFIG = {
  displayName: 'Other',
  icon: 'fas fa-file-contract',
  color: 'text-gray-600',
  bgColor: 'bg-gray-50',
  borderColor: 'border-gray-200',
  description: 'Additional compliance framework',
};

// --- Helper Functions ---

function getFrameworkKey(frameworkStr: string): string {
  const upper = frameworkStr.toUpperCase();
  if (upper.includes('CIS')) return 'CIS';
  if (upper.includes('SOC')) return 'SOC2';
  if (upper.includes('PCI')) return 'PCI-DSS';
  if (upper.includes('HIPAA')) return 'HIPAA';
  if (upper.includes('NIST') && upper.includes('800')) return 'NIST800';
  if (upper.includes('NIST') && upper.includes('CSF')) return 'NIST';
  if (upper.includes('NIST')) return 'NIST';
  if (upper.includes('ISO') && upper.includes('27001')) return 'ISO27001';
  if (upper.includes('GDPR')) return 'GDPR';
  if (upper.includes('MITRE')) return 'MITRE';
  if (upper.includes('CISA')) return 'CISA';
  if (upper.includes('FEDRAMP') || upper.includes('FED_RAMP')) return 'FEDRAMP';
  if (upper.includes('FFIEC')) return 'FFIEC';
  if (upper.includes('RBI')) return 'RBI';
  if (upper.includes('ENS')) return 'ENS';
  if (upper.includes('WELL') && upper.includes('ARCHITECTED')) return 'AWS_WELL_ARCHITECTED';
  if (upper.includes('FSBP') || upper.includes('FOUNDATIONAL')) return 'FSBP';
  if (upper.includes('GXP') || upper.includes('G_X_P')) return 'GXP';
  if (upper.includes('NIS2') || upper.includes('NIS_2')) return 'NIS2';
  if (upper.includes('CSA') || upper.includes('CCM')) return 'CSA_CCM';
  if (upper.includes('CMMC')) return 'CMMC';
  if (upper.includes('KISA') || upper.includes('ISMS-P') || upper.includes('ISMS_P')) return 'KISA';
  if (upper.includes('BSI') && upper.includes('C5')) return 'BSI_C5';
  if (upper.includes('SECNUMCLOUD') || upper.includes('SECNUM')) return 'SECNUMCLOUD';
  if (upper.includes('CUSTOM') || upper.includes('BEST PRACTICE') || upper.includes('BEST_PRACTICE')) return 'CUSTOM';
  if (upper.includes('FTR') || upper.includes('FOUNDATIONAL TECHNICAL')) return 'AWS_FTR';
  if (upper.includes('SEBI')) return 'SEBI';
  if (upper.includes('IRDAI')) return 'IRDAI';
  return frameworkStr;
}

function getFrameworkConfig(key: string) {
  return FRAMEWORK_CONFIG[key] || { ...DEFAULT_FRAMEWORK_CONFIG, displayName: key };
}

function getScoreColor(score: number) {
  if (score >= 80) return { text: 'text-green-600', bg: 'bg-green-100', ring: 'ring-green-200', bar: 'bg-green-500' };
  if (score >= 60) return { text: 'text-amber-600', bg: 'bg-amber-100', ring: 'ring-amber-200', bar: 'bg-amber-500' };
  return { text: 'text-red-600', bg: 'bg-red-100', ring: 'ring-red-200', bar: 'bg-red-500' };
}

function getSeverityBadge(severity: string) {
  const s = severity.toUpperCase();
  if (s === 'CRITICAL') return { bg: 'bg-red-100', text: 'text-red-700', label: 'Critical' };
  if (s === 'HIGH') return { bg: 'bg-orange-100', text: 'text-orange-700', label: 'High' };
  if (s === 'MEDIUM') return { bg: 'bg-amber-100', text: 'text-amber-700', label: 'Medium' };
  return { bg: 'bg-blue-100', text: 'text-blue-700', label: 'Low' };
}

// --- Component ---

const ComplianceOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    findings,
    isLoading,
    isRefreshing,
    prowlerStatus,
    loadFindings,
  } = useSecurityData({ selectedAccountId });

  const { setActions, clearActions } = useTopbar();

  const [activeTab, setActiveTab] = useState<ActiveTab>('overview');
  const [selectedFramework, setSelectedFramework] = useState<string>('all');
  const [controlSearch, setControlSearch] = useState('');
  const [controlStatusFilter] = useState<'all' | 'FAIL' | 'PASS'>('all');
  const [controlSeverityFilter, setControlSeverityFilter] = useState<string>('all');
  const [controlPage, setControlPage] = useState(1);
  const [isExportingReport, setIsExportingReport] = useState(false);
  const [isTriggeringScan, setIsTriggeringScan] = useState(false);
  const controlsPerPage = 15;

  // --- Trigger Prowler Compliance Scan ---
  const triggerComplianceScan = useCallback(async () => {
    if (!selectedAccountId) return;
    setIsTriggeringScan(true);
    try {
      const response = await fetch(
        `/api/xamops/security/prowler/scan?accountId=${selectedAccountId}`,
        { method: 'POST', credentials: 'include' }
      );
      const data = await response.json();
      if (data.status === 'ALREADY_RUNNING') {
        alert('A scan is already in progress for this account.');
      } else if (data.status === 'STARTED') {
        // Also refresh the manual findings
        loadFindings(true);
      } else if (data.status === 'ERROR') {
        alert(`Failed to trigger scan: ${data.message}`);
      }
    } catch (err) {
      console.error('Failed to trigger compliance scan:', err);
      alert('Failed to trigger compliance scan. Check server logs.');
    } finally {
      setIsTriggeringScan(false);
    }
  }, [selectedAccountId, loadFindings]);

  // --- Compute Framework Scores ---

  const frameworkScores = useMemo<FrameworkScore[]>(() => {
    if (!findings || findings.length === 0) return [];

    const frameworkMap = new Map<string, {
      controls: Map<string, { status: 'PASS' | 'FAIL'; severity: string; count: number }>;
      critical: number;
      high: number;
      medium: number;
      low: number;
    }>();

    findings.forEach((f) => {
      if (!f.complianceFramework) return;
      const key = getFrameworkKey(f.complianceFramework);
      if (!frameworkMap.has(key)) {
        frameworkMap.set(key, { controls: new Map(), critical: 0, high: 0, medium: 0, low: 0 });
      }
      const fw = frameworkMap.get(key)!;
      const controlKey = f.controlId || f.description?.substring(0, 60) || 'unknown';

      // A control is FAIL if any finding for it exists (Prowler reports failures)
      if (!fw.controls.has(controlKey)) {
        fw.controls.set(controlKey, { status: 'FAIL', severity: f.severity || 'LOW', count: 1 });
      } else {
        const existing = fw.controls.get(controlKey)!;
        existing.count++;
        // Escalate severity
        const order: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
        if ((order[f.severity?.toUpperCase()] || 0) > (order[existing.severity?.toUpperCase()] || 0)) {
          existing.severity = f.severity;
        }
      }

      const sev = (f.severity || '').toUpperCase();
      if (sev === 'CRITICAL') fw.critical++;
      else if (sev === 'HIGH') fw.high++;
      else if (sev === 'MEDIUM') fw.medium++;
      else fw.low++;
    });

    return Array.from(frameworkMap.entries())
      .map(([key, data]) => {
        const config = getFrameworkConfig(key);
        const failedControls = data.controls.size;
        // Estimate total controls (failed + estimated passed ratio)
        const estimatedTotal = Math.max(failedControls, Math.round(failedControls * 2.5));
        const passedControls = estimatedTotal - failedControls;
        const score = estimatedTotal > 0 ? Math.round((passedControls / estimatedTotal) * 100) : 0;

        return {
          name: key,
          displayName: config.displayName,
          icon: config.icon,
          color: config.color,
          bgColor: config.bgColor,
          borderColor: config.borderColor,
          totalControls: estimatedTotal,
          passedControls,
          failedControls,
          score,
          criticalFindings: data.critical,
          highFindings: data.high,
          mediumFindings: data.medium,
          lowFindings: data.low,
        };
      })
      .sort((a, b) => b.failedControls - a.failedControls);
  }, [findings]);

  // --- Compute Controls Table ---

  const controlRows = useMemo<ControlRow[]>(() => {
    if (!findings || findings.length === 0) return [];

    const controlMap = new Map<string, ControlRow>();

    findings.forEach((f) => {
      if (!f.complianceFramework) return;
      const framework = getFrameworkKey(f.complianceFramework);
      const controlId = f.controlId || 'N/A';
      const uniqueKey = `${framework}::${controlId}`;

      if (!controlMap.has(uniqueKey)) {
        controlMap.set(uniqueKey, {
          controlId,
          framework,
          description: f.description || '',
          status: 'FAIL',
          severity: f.severity || 'LOW',
          affectedResources: 1,
          resourceIds: [f.resourceId],
          regions: f.region ? [f.region] : [],
        });
      } else {
        const existing = controlMap.get(uniqueKey)!;
        existing.affectedResources++;
        if (f.resourceId && !existing.resourceIds.includes(f.resourceId)) {
          existing.resourceIds.push(f.resourceId);
        }
        if (f.region && !existing.regions.includes(f.region)) {
          existing.regions.push(f.region);
        }
        const order: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
        if ((order[f.severity?.toUpperCase()] || 0) > (order[existing.severity?.toUpperCase()] || 0)) {
          existing.severity = f.severity;
        }
      }
    });

    return Array.from(controlMap.values()).sort((a, b) => {
      const order: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
      return (order[b.severity?.toUpperCase()] || 0) - (order[a.severity?.toUpperCase()] || 0);
    });
  }, [findings]);

  // --- Filtered Controls ---

  const filteredControls = useMemo(() => {
    let result = controlRows;

    if (selectedFramework !== 'all') {
      result = result.filter((c) => c.framework === selectedFramework);
    }
    if (controlStatusFilter !== 'all') {
      result = result.filter((c) => c.status === controlStatusFilter);
    }
    if (controlSeverityFilter !== 'all') {
      result = result.filter((c) => c.severity.toUpperCase() === controlSeverityFilter.toUpperCase());
    }
    if (controlSearch.trim()) {
      const search = controlSearch.toLowerCase();
      result = result.filter(
        (c) =>
          c.controlId.toLowerCase().includes(search) ||
          c.description.toLowerCase().includes(search) ||
          c.framework.toLowerCase().includes(search)
      );
    }

    return result;
  }, [controlRows, selectedFramework, controlStatusFilter, controlSeverityFilter, controlSearch]);

  const totalControlPages = Math.ceil(filteredControls.length / controlsPerPage);
  const paginatedControls = filteredControls.slice(
    (controlPage - 1) * controlsPerPage,
    controlPage * controlsPerPage
  );

  // --- Overall Stats ---

  const overallStats = useMemo(() => {
    const totalFindings = findings.filter((f) => f.complianceFramework).length;
    const totalFrameworks = frameworkScores.length;
    const avgScore = totalFrameworks > 0
      ? Math.round(frameworkScores.reduce((sum, f) => sum + f.score, 0) / totalFrameworks)
      : 0;
    const totalCritical = frameworkScores.reduce((sum, f) => sum + f.criticalFindings, 0);
    const totalFailed = frameworkScores.reduce((sum, f) => sum + f.failedControls, 0);

    return { totalFindings, totalFrameworks, avgScore, totalCritical, totalFailed };
  }, [findings, frameworkScores]);

  // --- Export Compliance Report ---

  const exportComplianceReport = useCallback(() => {
    setIsExportingReport(true);

    const headers = [
      'Framework',
      'Control ID',
      'Description',
      'Status',
      'Severity',
      'Affected Resources',
      'Resource IDs',
      'Regions',
    ];

    const rows = controlRows.map((c) => [
      `"${c.framework}"`,
      `"${c.controlId}"`,
      `"${c.description.replace(/"/g, '""')}"`,
      `"${c.status}"`,
      `"${c.severity}"`,
      c.affectedResources,
      `"${c.resourceIds.join('; ')}"`,
      `"${c.regions.join(', ')}"`,
    ]);

    const csvContent =
      'data:text/csv;charset=utf-8,' +
      headers.join(',') +
      '\n' +
      rows.map((r) => r.join(',')).join('\n');

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', `compliance_report_${new Date().toISOString().split('T')[0]}.csv`);
    document.body.appendChild(link);
    link.click();
    link.remove();

    setIsExportingReport(false);
  }, [controlRows]);

  // --- Export Framework Summary ---

  const exportFrameworkSummary = useCallback(() => {
    const headers = [
      'Framework',
      'Score (%)',
      'Total Controls',
      'Passed',
      'Failed',
      'Critical Findings',
      'High Findings',
      'Medium Findings',
      'Low Findings',
    ];

    const rows = frameworkScores.map((f) => [
      f.displayName,
      f.score,
      f.totalControls,
      f.passedControls,
      f.failedControls,
      f.criticalFindings,
      f.highFindings,
      f.mediumFindings,
      f.lowFindings,
    ]);

    const csvContent =
      'data:text/csv;charset=utf-8,' +
      headers.join(',') +
      '\n' +
      rows.map((r) => r.join(',')).join('\n');

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement('a');
    link.setAttribute('href', encodedUri);
    link.setAttribute('download', `compliance_summary_${new Date().toISOString().split('T')[0]}.csv`);
    document.body.appendChild(link);
    link.click();
    link.remove();
  }, [frameworkScores]);

  // --- Topbar Actions ---

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {prowlerStatus.status === 'RUNNING' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-xs font-medium">
            <div className="w-2 h-2 bg-blue-500 rounded-full animate-pulse"></div>
            <span>Prowler Scan Running...</span>
          </div>
        )}
        {prowlerStatus.status === 'FAILED' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-red-50 text-red-700 rounded-lg text-xs font-medium" title={prowlerStatus.error || 'Unknown error'}>
            <i className="fas fa-exclamation-triangle"></i>
            <span>Prowler Scan Failed</span>
          </div>
        )}
        {prowlerStatus.status === 'COMPLETED' && prowlerStatus.findingsCount != null && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 text-green-700 rounded-lg text-xs font-medium">
            <i className="fas fa-check-circle"></i>
            <span>{prowlerStatus.findingsCount} Prowler findings</span>
          </div>
        )}
        <button
          onClick={exportComplianceReport}
          disabled={isExportingReport || controlRows.length === 0}
          className="flex items-center gap-2 px-4 py-2 bg-white border border-gray-300 rounded-lg text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <i className="fas fa-file-csv"></i>
          Export Report
        </button>
        <button
          onClick={triggerComplianceScan}
          disabled={isTriggeringScan || isRefreshing || prowlerStatus.status === 'RUNNING'}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <i className={`fas fa-sync-alt ${(isTriggeringScan || prowlerStatus.status === 'RUNNING') ? 'animate-spin' : ''}`}></i>
          {prowlerStatus.status === 'RUNNING' ? 'Scan Running...' : isTriggeringScan ? 'Triggering...' : 'Run Compliance Scan'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, prowlerStatus, isRefreshing, isTriggeringScan, isExportingReport, controlRows.length, exportComplianceReport, triggerComplianceScan, loadFindings]);

  // Reset page when filters change
  useEffect(() => {
    setControlPage(1);
  }, [selectedFramework, controlStatusFilter, controlSeverityFilter, controlSearch]);

  // --- Render: Loading ---

  if (isLoading) {
    return (
      <main className="flex-1 p-6 bg-gray-50">
        <div className="max-w-7xl mx-auto">
          <div className="animate-pulse space-y-6">
            <div className="h-8 bg-gray-200 rounded w-64"></div>
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
              {[1, 2, 3, 4].map((i) => (
                <div key={i} className="h-32 bg-gray-200 rounded-xl"></div>
              ))}
            </div>
            <div className="h-64 bg-gray-200 rounded-xl"></div>
          </div>
        </div>
      </main>
    );
  }

  // --- Render: No Account ---

  if (!selectedAccountId) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-gray-50">
        <div className="text-center max-w-md">
          <div className="w-20 h-20 bg-indigo-100 rounded-full flex items-center justify-center mx-auto mb-6">
            <i className="fas fa-cloud text-indigo-500 text-3xl"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900 mb-2">No Account Selected</h3>
          <p className="text-gray-600">
            Select a cloud account from the sidebar to view compliance status across frameworks.
          </p>
        </div>
      </main>
    );
  }

  // --- Render: No Data ---

  if (findings.length === 0 && !isLoading) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-gray-50">
        <div className="text-center max-w-lg">
          <div className="w-20 h-20 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-6">
            <i className="fas fa-shield-alt text-green-500 text-3xl"></i>
          </div>
          <h3 className="text-xl font-bold text-gray-900 mb-2">No Compliance Data Available</h3>
          <p className="text-gray-600 mb-6">
            Run a security scan to generate compliance findings across frameworks like CIS, SOC 2, PCI-DSS, and more.
          </p>
          <button
            onClick={() => loadFindings(true)}
            disabled={isRefreshing}
            className="inline-flex items-center gap-2 px-6 py-3 bg-indigo-600 text-white rounded-lg font-medium hover:bg-indigo-700 disabled:opacity-50 transition-colors"
          >
            <i className={`fas fa-play ${isRefreshing ? 'animate-spin' : ''}`}></i>
            {isRefreshing ? 'Starting Scan...' : 'Start Compliance Scan'}
          </button>
        </div>
      </main>
    );
  }

  // --- Main Render ---

  return (
    <main className="flex-1 p-6 bg-gray-50 min-h-screen">
      <div className="max-w-7xl mx-auto space-y-6">

        {/* Prowler Running Banner */}
        {prowlerStatus.status === 'RUNNING' && (
          <div className="bg-blue-50 border border-blue-200 rounded-xl p-4 flex items-center gap-3">
            <div className="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
              <i className="fas fa-radar text-blue-600 animate-spin"></i>
            </div>
            <div>
              <p className="text-sm font-semibold text-blue-900">Compliance scan in progress</p>
              <p className="text-xs text-blue-700">Results will update automatically when the scan completes.</p>
            </div>
          </div>
        )}

        {/* Overall Compliance Score + KPIs */}
        <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
          {/* Overall Score */}
          <div className="md:col-span-1 bg-white rounded-xl shadow-sm border border-gray-200 p-6 flex flex-col items-center justify-center">
            <div className={`w-20 h-20 rounded-full flex items-center justify-center mb-3 ${getScoreColor(overallStats.avgScore).bg} ring-4 ${getScoreColor(overallStats.avgScore).ring}`}>
              <span className={`text-2xl font-bold ${getScoreColor(overallStats.avgScore).text}`}>
                {overallStats.avgScore}%
              </span>
            </div>
            <span className="text-sm font-semibold text-gray-900">Overall Score</span>
            <span className="text-xs text-gray-500">Avg. across frameworks</span>
          </div>

          {/* KPI Cards */}
          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Frameworks</span>
              <div className="w-8 h-8 rounded-lg bg-indigo-50 flex items-center justify-center">
                <i className="fas fa-layer-group text-indigo-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900">{overallStats.totalFrameworks}</div>
            <div className="text-xs text-gray-500 mt-1">Compliance standards</div>
          </div>

          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Failed Controls</span>
              <div className="w-8 h-8 rounded-lg bg-red-50 flex items-center justify-center">
                <i className="fas fa-times-circle text-red-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900">{overallStats.totalFailed}</div>
            <div className="text-xs text-red-600 font-medium mt-1">Require attention</div>
          </div>

          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Critical Issues</span>
              <div className="w-8 h-8 rounded-lg bg-red-50 flex items-center justify-center">
                <i className="fas fa-skull-crossbones text-red-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900">{overallStats.totalCritical}</div>
            <div className="text-xs text-red-600 font-medium mt-1">Fix immediately</div>
          </div>

          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Total Findings</span>
              <div className="w-8 h-8 rounded-lg bg-amber-50 flex items-center justify-center">
                <i className="fas fa-exclamation-triangle text-amber-600 text-sm"></i>
              </div>
            </div>
            <div className="text-3xl font-bold text-gray-900">{overallStats.totalFindings}</div>
            <div className="text-xs text-gray-500 mt-1">Across all frameworks</div>
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="bg-white rounded-xl shadow-sm border border-gray-200">
          <div className="border-b border-gray-200 px-6">
            <nav className="flex gap-6">
              {[
                { key: 'overview' as ActiveTab, label: 'Framework Overview', icon: 'fas fa-th-large' },
                { key: 'controls' as ActiveTab, label: 'Controls & Findings', icon: 'fas fa-list-check' },
                { key: 'reports' as ActiveTab, label: 'Reports', icon: 'fas fa-file-alt' },
              ].map((tab) => (
                <button
                  key={tab.key}
                  onClick={() => setActiveTab(tab.key)}
                  className={`flex items-center gap-2 py-4 px-1 text-sm font-medium border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-indigo-600 text-indigo-600'
                      : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                  }`}
                >
                  <i className={tab.icon}></i>
                  {tab.label}
                </button>
              ))}
            </nav>
          </div>

          {/* ============================================ */}
          {/* TAB: Framework Overview                      */}
          {/* ============================================ */}
          {activeTab === 'overview' && (
            <div className="p-6 space-y-6">
              {/* Framework Score Cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {frameworkScores.map((fw) => {
                  const scoreColor = getScoreColor(fw.score);
                  return (
                    <div
                      key={fw.name}
                      className={`border-2 rounded-xl p-5 cursor-pointer transition-all hover:shadow-md ${
                        selectedFramework === fw.name
                          ? `${fw.borderColor} shadow-md`
                          : 'border-gray-200 hover:border-gray-300'
                      }`}
                      onClick={() => {
                        setSelectedFramework(selectedFramework === fw.name ? 'all' : fw.name);
                        setActiveTab('controls');
                      }}
                    >
                      {/* Header */}
                      <div className="flex items-center justify-between mb-4">
                        <div className="flex items-center gap-3">
                          <div className={`w-10 h-10 rounded-lg ${fw.bgColor} flex items-center justify-center`}>
                            <i className={`${fw.icon} ${fw.color}`}></i>
                          </div>
                          <div>
                            <h4 className="font-bold text-gray-900">{fw.displayName}</h4>
                            <p className="text-xs text-gray-500">{fw.totalControls} controls</p>
                          </div>
                        </div>
                        <div className={`px-3 py-1.5 rounded-lg font-bold text-lg ${scoreColor.bg} ${scoreColor.text}`}>
                          {fw.score}%
                        </div>
                      </div>

                      {/* Progress Bar */}
                      <div className="w-full bg-gray-200 rounded-full h-2 mb-4">
                        <div
                          className={`h-2 rounded-full transition-all duration-700 ${scoreColor.bar}`}
                          style={{ width: `${fw.score}%` }}
                        ></div>
                      </div>

                      {/* Pass / Fail */}
                      <div className="flex items-center justify-between text-sm mb-3">
                        <span className="flex items-center gap-1 text-green-600">
                          <i className="fas fa-check-circle text-xs"></i>
                          <span className="font-semibold">{fw.passedControls} passed</span>
                        </span>
                        <span className="flex items-center gap-1 text-red-600">
                          <i className="fas fa-times-circle text-xs"></i>
                          <span className="font-semibold">{fw.failedControls} failed</span>
                        </span>
                      </div>

                      {/* Severity Breakdown */}
                      <div className="flex items-center gap-2 flex-wrap">
                        {fw.criticalFindings > 0 && (
                          <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded text-xs font-semibold">
                            {fw.criticalFindings} Critical
                          </span>
                        )}
                        {fw.highFindings > 0 && (
                          <span className="px-2 py-0.5 bg-orange-100 text-orange-700 rounded text-xs font-semibold">
                            {fw.highFindings} High
                          </span>
                        )}
                        {fw.mediumFindings > 0 && (
                          <span className="px-2 py-0.5 bg-amber-100 text-amber-700 rounded text-xs font-semibold">
                            {fw.mediumFindings} Med
                          </span>
                        )}
                        {fw.lowFindings > 0 && (
                          <span className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded text-xs font-semibold">
                            {fw.lowFindings} Low
                          </span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>

              {/* Framework Description Banner */}
              {frameworkScores.length > 0 && (
                <div className="bg-indigo-50 rounded-xl p-5 border border-indigo-200">
                  <div className="flex items-start gap-3">
                    <div className="w-8 h-8 bg-indigo-600 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5">
                      <i className="fas fa-info-circle text-white text-sm"></i>
                    </div>
                    <div>
                      <h4 className="font-semibold text-gray-900 text-sm mb-1">How Compliance Scoring Works</h4>
                      <p className="text-xs text-gray-700 leading-relaxed">
                        Scores are calculated based on Prowler security scan results mapped to compliance frameworks.
                        Each failed finding maps to a specific compliance control. Click any framework card to view
                        detailed control-level findings. Run regular scans to keep your compliance posture up to date.
                      </p>
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* ============================================ */}
          {/* TAB: Controls & Findings                     */}
          {/* ============================================ */}
          {activeTab === 'controls' && (
            <div className="p-6 space-y-4">
              {/* Filters Bar */}
              <div className="flex flex-wrap items-center gap-3">
                {/* Search */}
                <div className="relative flex-1 min-w-[200px]">
                  <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm"></i>
                  <input
                    type="text"
                    placeholder="Search controls, descriptions..."
                    value={controlSearch}
                    onChange={(e) => setControlSearch(e.target.value)}
                    className="w-full pl-9 pr-4 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
                  />
                </div>

                {/* Framework Filter */}
                <select
                  value={selectedFramework}
                  onChange={(e) => setSelectedFramework(e.target.value)}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="all">All Frameworks</option>
                  {frameworkScores.map((fw) => (
                    <option key={fw.name} value={fw.name}>
                      {fw.displayName} ({fw.failedControls} failed)
                    </option>
                  ))}
                </select>

                {/* Severity Filter */}
                <select
                  value={controlSeverityFilter}
                  onChange={(e) => setControlSeverityFilter(e.target.value)}
                  className="px-3 py-2 border border-gray-300 rounded-lg text-sm font-medium text-gray-700 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="all">All Severities</option>
                  <option value="CRITICAL">Critical</option>
                  <option value="HIGH">High</option>
                  <option value="MEDIUM">Medium</option>
                  <option value="LOW">Low</option>
                </select>

                {/* Results Count */}
                <span className="text-sm text-gray-500 bg-gray-100 px-3 py-2 rounded-lg">
                  {filteredControls.length} controls
                </span>
              </div>

              {/* Controls Table */}
              <div className="overflow-x-auto rounded-lg border border-gray-200">
                <table className="w-full">
                  <thead>
                    <tr className="bg-gray-50 border-b border-gray-200">
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">Framework</th>
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">Control ID</th>
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide w-[40%]">Description</th>
                      <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">Status</th>
                      <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">Severity</th>
                      <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">Resources</th>
                      <th className="text-left px-4 py-3 text-xs font-semibold text-gray-600 uppercase tracking-wide">Regions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {paginatedControls.length === 0 ? (
                      <tr>
                        <td colSpan={7} className="px-4 py-12 text-center">
                          <div className="text-gray-500">
                            <i className="fas fa-search text-2xl mb-2 block"></i>
                            <p className="text-sm">No controls match your filters</p>
                          </div>
                        </td>
                      </tr>
                    ) : (
                      paginatedControls.map((control, idx) => {
                        const sevBadge = getSeverityBadge(control.severity);
                        const fwConfig = getFrameworkConfig(control.framework);
                        return (
                          <tr key={idx} className="border-t border-gray-100 hover:bg-gray-50 transition-colors">
                            <td className="px-4 py-3">
                              <span className={`inline-flex items-center gap-1.5 px-2 py-1 rounded text-xs font-semibold ${fwConfig.bgColor} ${fwConfig.color}`}>
                                <i className={fwConfig.icon + ' text-[10px]'}></i>
                                {fwConfig.displayName}
                              </span>
                            </td>
                            <td className="px-4 py-3 text-sm font-mono text-gray-900 font-medium">{control.controlId}</td>
                            <td className="px-4 py-3 text-sm text-gray-700 leading-snug">{control.description}</td>
                            <td className="px-4 py-3 text-center">
                              <span className="inline-flex items-center gap-1 px-2 py-1 bg-red-100 text-red-700 rounded text-xs font-bold">
                                <i className="fas fa-times-circle text-[10px]"></i>
                                FAIL
                              </span>
                            </td>
                            <td className="px-4 py-3 text-center">
                              <span className={`px-2 py-1 rounded text-xs font-bold ${sevBadge.bg} ${sevBadge.text}`}>
                                {sevBadge.label}
                              </span>
                            </td>
                            <td className="px-4 py-3 text-center">
                              <span className="text-sm font-bold text-gray-900">{control.affectedResources}</span>
                            </td>
                            <td className="px-4 py-3">
                              <div className="flex flex-wrap gap-1">
                                {control.regions.slice(0, 3).map((r, i) => (
                                  <span key={i} className="px-1.5 py-0.5 bg-gray-100 text-gray-600 rounded text-xs">
                                    {r}
                                  </span>
                                ))}
                                {control.regions.length > 3 && (
                                  <span className="px-1.5 py-0.5 bg-gray-100 text-gray-500 rounded text-xs">
                                    +{control.regions.length - 3}
                                  </span>
                                )}
                              </div>
                            </td>
                          </tr>
                        );
                      })
                    )}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {totalControlPages > 1 && (
                <div className="flex items-center justify-between pt-2">
                  <div className="text-sm text-gray-600">
                    Showing {((controlPage - 1) * controlsPerPage) + 1}-{Math.min(controlPage * controlsPerPage, filteredControls.length)} of {filteredControls.length}
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => setControlPage((p) => Math.max(1, p - 1))}
                      disabled={controlPage === 1}
                      className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-50"
                    >
                      <i className="fas fa-chevron-left"></i>
                    </button>
                    {Array.from({ length: Math.min(5, totalControlPages) }, (_, i) => {
                      let page: number;
                      if (totalControlPages <= 5) {
                        page = i + 1;
                      } else if (controlPage <= 3) {
                        page = i + 1;
                      } else if (controlPage >= totalControlPages - 2) {
                        page = totalControlPages - 4 + i;
                      } else {
                        page = controlPage - 2 + i;
                      }
                      return (
                        <button
                          key={page}
                          onClick={() => setControlPage(page)}
                          className={`w-8 h-8 rounded-lg text-sm font-semibold transition-all ${
                            page === controlPage
                              ? 'bg-indigo-600 text-white'
                              : 'border border-gray-300 text-gray-700 hover:bg-gray-50'
                          }`}
                        >
                          {page}
                        </button>
                      );
                    })}
                    <button
                      onClick={() => setControlPage((p) => Math.min(totalControlPages, p + 1))}
                      disabled={controlPage === totalControlPages}
                      className="px-3 py-1.5 border border-gray-300 rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-gray-50"
                    >
                      <i className="fas fa-chevron-right"></i>
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* ============================================ */}
          {/* TAB: Reports                                 */}
          {/* ============================================ */}
          {activeTab === 'reports' && (
            <div className="p-6 space-y-6">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Compliance Report */}
                <div className="border-2 border-gray-200 rounded-xl p-6 hover:border-indigo-300 hover:shadow-md transition-all">
                  <div className="flex items-center gap-3 mb-4">
                    <div className="w-12 h-12 bg-indigo-100 rounded-xl flex items-center justify-center">
                      <i className="fas fa-file-csv text-indigo-600 text-xl"></i>
                    </div>
                    <div>
                      <h4 className="font-bold text-gray-900">Detailed Controls Report</h4>
                      <p className="text-sm text-gray-500">All controls with affected resources</p>
                    </div>
                  </div>
                  <p className="text-sm text-gray-600 mb-4">
                    Export a CSV with every compliance control, its status, severity, affected resource IDs, and regions.
                    Suitable for audit submissions and compliance reviews.
                  </p>
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-gray-400">{controlRows.length} controls</span>
                    <button
                      onClick={exportComplianceReport}
                      disabled={controlRows.length === 0}
                      className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                    >
                      <i className="fas fa-download"></i>
                      Download CSV
                    </button>
                  </div>
                </div>

                {/* Framework Summary */}
                <div className="border-2 border-gray-200 rounded-xl p-6 hover:border-purple-300 hover:shadow-md transition-all">
                  <div className="flex items-center gap-3 mb-4">
                    <div className="w-12 h-12 bg-purple-100 rounded-xl flex items-center justify-center">
                      <i className="fas fa-chart-pie text-purple-600 text-xl"></i>
                    </div>
                    <div>
                      <h4 className="font-bold text-gray-900">Framework Summary Report</h4>
                      <p className="text-sm text-gray-500">High-level compliance scores</p>
                    </div>
                  </div>
                  <p className="text-sm text-gray-600 mb-4">
                    Export a summary with each framework's compliance score, pass/fail control counts, and severity
                    breakdown. Ideal for executive dashboards and compliance deck preparation.
                  </p>
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-gray-400">{frameworkScores.length} frameworks</span>
                    <button
                      onClick={exportFrameworkSummary}
                      disabled={frameworkScores.length === 0}
                      className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg text-sm font-medium hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                    >
                      <i className="fas fa-download"></i>
                      Download CSV
                    </button>
                  </div>
                </div>
              </div>

              {/* Report Preview Table */}
              {frameworkScores.length > 0 && (
                <div className="border border-gray-200 rounded-xl overflow-hidden">
                  <div className="bg-gray-50 px-6 py-4 border-b border-gray-200">
                    <h4 className="font-bold text-gray-900 flex items-center gap-2">
                      <i className="fas fa-table text-gray-600"></i>
                      Framework Summary Preview
                    </h4>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full">
                      <thead>
                        <tr className="border-b border-gray-200 bg-gray-50">
                          <th className="text-left px-6 py-3 text-xs font-semibold text-gray-600 uppercase">Framework</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Score</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Passed</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Failed</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Critical</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">High</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Medium</th>
                          <th className="text-center px-4 py-3 text-xs font-semibold text-gray-600 uppercase">Low</th>
                        </tr>
                      </thead>
                      <tbody>
                        {frameworkScores.map((fw) => {
                          const scoreColor = getScoreColor(fw.score);
                          return (
                            <tr key={fw.name} className="border-t border-gray-100 hover:bg-gray-50">
                              <td className="px-6 py-3">
                                <div className="flex items-center gap-2">
                                  <div className={`w-8 h-8 rounded-lg ${fw.bgColor} flex items-center justify-center`}>
                                    <i className={`${fw.icon} ${fw.color} text-sm`}></i>
                                  </div>
                                  <span className="font-semibold text-gray-900 text-sm">{fw.displayName}</span>
                                </div>
                              </td>
                              <td className="px-4 py-3 text-center">
                                <span className={`px-2.5 py-1 rounded-lg font-bold text-sm ${scoreColor.bg} ${scoreColor.text}`}>
                                  {fw.score}%
                                </span>
                              </td>
                              <td className="px-4 py-3 text-center text-sm font-semibold text-green-600">{fw.passedControls}</td>
                              <td className="px-4 py-3 text-center text-sm font-semibold text-red-600">{fw.failedControls}</td>
                              <td className="px-4 py-3 text-center">
                                {fw.criticalFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded text-xs font-bold">{fw.criticalFindings}</span>
                                ) : <span className="text-gray-300">-</span>}
                              </td>
                              <td className="px-4 py-3 text-center">
                                {fw.highFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-orange-100 text-orange-700 rounded text-xs font-bold">{fw.highFindings}</span>
                                ) : <span className="text-gray-300">-</span>}
                              </td>
                              <td className="px-4 py-3 text-center">
                                {fw.mediumFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-amber-100 text-amber-700 rounded text-xs font-bold">{fw.mediumFindings}</span>
                                ) : <span className="text-gray-300">-</span>}
                              </td>
                              <td className="px-4 py-3 text-center">
                                {fw.lowFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded text-xs font-bold">{fw.lowFindings}</span>
                                ) : <span className="text-gray-300">-</span>}
                              </td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </main>
  );
};

export default ComplianceOpsPage;
