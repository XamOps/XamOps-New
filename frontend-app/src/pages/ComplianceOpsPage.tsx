import React, { useEffect, useState, useMemo, useCallback, useRef } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useSecurityData } from '../hooks/useSecurityData';
import { useTabState } from '../hooks/useTabState';

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
  'CIS':                { displayName: 'CIS Benchmark',       icon: 'mdi:shield-half-full',          color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200',    description: 'Center for Internet Security benchmarks for cloud infrastructure hardening' },
  'SOC2':               { displayName: 'SOC 2',               icon: 'mdi:lock-outline',              color: 'text-purple-600',  bgColor: 'bg-purple-50',  borderColor: 'border-purple-200',  description: 'Service Organization Control 2 — Trust Services Criteria for security, availability, and confidentiality' },
  'PCI-DSS':            { displayName: 'PCI DSS',             icon: 'mdi:credit-card-outline',       color: 'text-green-600',   bgColor: 'bg-green-50',   borderColor: 'border-green-200',   description: 'Payment Card Industry Data Security Standard for protecting cardholder data' },
  'HIPAA':              { displayName: 'HIPAA',               icon: 'mdi:heart-pulse',               color: 'text-red-600',     bgColor: 'bg-red-50',     borderColor: 'border-red-200',     description: 'Health Insurance Portability and Accountability Act for protecting health information' },
  'NIST':               { displayName: 'NIST CSF',            icon: 'mdi:bank-outline',              color: 'text-indigo-600',  bgColor: 'bg-indigo-50',  borderColor: 'border-indigo-200',  description: 'National Institute of Standards and Technology Cybersecurity Framework' },
  'ISO27001':           { displayName: 'ISO 27001',           icon: 'mdi:certificate-outline',       color: 'text-teal-600',    bgColor: 'bg-teal-50',    borderColor: 'border-teal-200',    description: 'International standard for information security management systems' },
  'GDPR':               { displayName: 'GDPR',                icon: 'mdi:account-shield-outline',    color: 'text-amber-600',   bgColor: 'bg-amber-50',   borderColor: 'border-amber-200',   description: 'General Data Protection Regulation for data privacy and protection' },
  'MITRE':              { displayName: 'MITRE ATT&CK',        icon: 'mdi:crosshairs',                color: 'text-rose-600',    bgColor: 'bg-rose-50',    borderColor: 'border-rose-200',    description: 'MITRE ATT&CK framework mapping adversary tactics and techniques for cloud' },
  'CISA':               { displayName: 'CISA',                icon: 'mdi:flag-variant',              color: 'text-sky-600',     bgColor: 'bg-sky-50',     borderColor: 'border-sky-200',     description: 'Cybersecurity and Infrastructure Security Agency guidelines' },
  'FEDRAMP':            { displayName: 'FedRAMP',             icon: 'mdi:bank-outline',              color: 'text-blue-700',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200',    description: 'Federal Risk and Authorization Management Program for US government cloud' },
  'FFIEC':              { displayName: 'FFIEC',               icon: 'mdi:scale-balance',             color: 'text-cyan-600',    bgColor: 'bg-cyan-50',    borderColor: 'border-cyan-200',    description: 'Federal Financial Institutions Examination Council cybersecurity assessment' },
  'RBI':                { displayName: 'RBI CSF',             icon: 'mdi:currency-inr',              color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200',  description: 'Reserve Bank of India Cyber Security Framework for banks and financial institutions' },
  'ENS':                { displayName: 'ENS',                 icon: 'mdi:earth',                     color: 'text-yellow-600',  bgColor: 'bg-yellow-50',  borderColor: 'border-yellow-200',  description: 'Esquema Nacional de Seguridad — Spanish National Security Framework' },
  'AWS_WELL_ARCHITECTED': { displayName: 'AWS Well-Architected', icon: 'mdi:cloud-outline',          color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200',  description: 'AWS Well-Architected Framework Security Pillar best practices' },
  'FSBP':               { displayName: 'AWS FSBP',            icon: 'mdi:cloud-outline',             color: 'text-amber-700',   bgColor: 'bg-amber-50',   borderColor: 'border-amber-200',   description: 'AWS Foundational Security Best Practices standard' },
  'GXP':                { displayName: 'GxP',                 icon: 'mdi:flask-outline',             color: 'text-emerald-600', bgColor: 'bg-emerald-50', borderColor: 'border-emerald-200', description: 'Good Practice guidelines for life sciences and pharmaceutical compliance' },
  'NIS2':               { displayName: 'NIS2',                icon: 'mdi:network-outline',           color: 'text-violet-600',  bgColor: 'bg-violet-50',  borderColor: 'border-violet-200',  description: 'EU Network and Information Security Directive 2 for critical infrastructure' },
  'NIST800':            { displayName: 'NIST 800-53',         icon: 'mdi:book-outline',              color: 'text-indigo-700',  bgColor: 'bg-indigo-50',  borderColor: 'border-indigo-200',  description: 'NIST Special Publication 800-53 — Security and Privacy Controls for Information Systems' },
  'CSA_CCM':            { displayName: 'CSA CCM',             icon: 'mdi:cloud-outline',             color: 'text-blue-500',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200',    description: 'Cloud Security Alliance Cloud Controls Matrix' },
  'CMMC':               { displayName: 'CMMC',                icon: 'mdi:shield-bug-outline',        color: 'text-gray-700',    bgColor: 'bg-gray-100',   borderColor: 'border-gray-300',    description: 'Cybersecurity Maturity Model Certification for US defense contractors' },
  'KISA':               { displayName: 'KISA ISMS-P',         icon: 'mdi:earth',                     color: 'text-pink-600',    bgColor: 'bg-pink-50',    borderColor: 'border-pink-200',    description: 'Korea Internet & Security Agency Information Security Management System' },
  'BSI_C5':             { displayName: 'BSI C5',              icon: 'mdi:shield-half-full',          color: 'text-gray-600',    bgColor: 'bg-gray-50',    borderColor: 'border-gray-200',    description: 'German Federal Office for Information Security Cloud Computing Compliance Controls' },
  'SECNUMCLOUD':        { displayName: 'SecNumCloud',         icon: 'mdi:cloud-upload-outline',      color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200',    description: 'French ANSSI SecNumCloud 3.2 security qualification for cloud providers' },
  'CUSTOM':             { displayName: 'Best Practices',      icon: 'mdi:star-outline',              color: 'text-yellow-600',  bgColor: 'bg-yellow-50',  borderColor: 'border-yellow-200',  description: 'Custom best practice checks and organizational security policies' },
  'AWS_FTR':            { displayName: 'AWS FTR',             icon: 'mdi:cloud-outline',             color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200',  description: 'AWS Foundational Technical Review for partner solutions' },
  'SEBI':               { displayName: 'SEBI',                icon: 'mdi:chart-line',                color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200',    description: 'Securities and Exchange Board of India cybersecurity framework' },
  'IRDAI':              { displayName: 'IRDAI',               icon: 'mdi:shield-half-full',          color: 'text-green-700',   bgColor: 'bg-green-50',   borderColor: 'border-green-200',   description: 'Insurance Regulatory and Development Authority of India guidelines' },
};

const DEFAULT_FRAMEWORK_CONFIG = {
  displayName: 'Other',
  icon: 'mdi:file-document-outline',
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

// --- Hook: polls Prowler findings + status for GCP or Azure ---

type CloudProvider = 'GCP' | 'Azure';

function useProwlerComplianceData(provider: CloudProvider, accountId: string | null) {
  const [findings, setFindings] = useState<NormalizedFinding[]>([]);
  const [prowlerStatus, setProwlerStatus] = useState<{ status: string; findingsCount?: number; error?: string }>({ status: 'UNKNOWN' });
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastIdRef = useRef<string | null>(null);

  const baseUrl = provider === 'GCP'
    ? '/api/xamops/gcp/security'
    : '/api/xamops/azure/security';

  const fetchFindings = useCallback(async (id: string) => {
    try {
      const res = await fetch(`${baseUrl}/prowler/findings?accountId=${id}`, { credentials: 'include' });
      if (!res.ok) return;
      const raw: Array<{ status_code?: string; severity?: string; message?: string; finding_info?: { title?: string; desc?: string }; resources?: Array<{ uid?: string; region?: string }>; unmapped?: { compliance?: Record<string, string[]> } }> = await res.json();
      const normalized: NormalizedFinding[] = (raw || [])
        .filter(f => f.status_code === 'FAIL')
        .flatMap(f => {
          const compliance = f.unmapped?.compliance ?? {};
          const frameworks = Object.keys(compliance);
          if (frameworks.length === 0) return [{
            complianceFramework: 'CUSTOM',
            controlId: f.finding_info?.title ?? 'N/A',
            description: f.finding_info?.desc ?? f.message ?? '',
            severity: (f.severity ?? 'LOW').toUpperCase(),
            resourceId: f.resources?.[0]?.uid ?? '',
            region: f.resources?.[0]?.region,
          }];
          return frameworks.flatMap(fw =>
            (compliance[fw] ?? []).map(controlId => ({
              complianceFramework: fw,
              controlId,
              description: f.finding_info?.desc ?? f.message ?? '',
              severity: (f.severity ?? 'LOW').toUpperCase(),
              resourceId: f.resources?.[0]?.uid ?? '',
              region: f.resources?.[0]?.region,
            }))
          );
        });
      setFindings(normalized);
    } catch (e) {
      console.error(`[Prowler ${provider}] findings fetch error:`, e);
    }
  }, [baseUrl, provider]);

  const fetchStatus = useCallback(async (id: string): Promise<string> => {
    try {
      const res = await fetch(`${baseUrl}/prowler/status?accountId=${id}`, { credentials: 'include' });
      if (!res.ok) return 'UNKNOWN';
      const data = await res.json();
      setProwlerStatus(data);
      if (data.status === 'COMPLETED') await fetchFindings(id);
      return data.status as string;
    } catch {
      return 'UNKNOWN';
    }
  }, [baseUrl, fetchFindings]);

  const startPolling = useCallback((id: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      const s = await fetchStatus(id);
      if (s !== 'RUNNING') {
        clearInterval(pollRef.current!);
        pollRef.current = null;
        setIsRefreshing(false);
      }
    }, 5000);
  }, [fetchStatus]);

  const triggerScan = useCallback(async () => {
    if (!accountId) return;
    setIsRefreshing(true);
    try {
      const res = await fetch(`${baseUrl}/prowler/scan?accountId=${accountId}`, { method: 'POST', credentials: 'include' });
      const data = await res.json();
      if (data.status === 'ALREADY_RUNNING') {
        alert('A scan is already in progress.');
        setIsRefreshing(false);
      } else if (data.status === 'STARTED') {
        setProwlerStatus({ status: 'RUNNING' });
        startPolling(accountId);
      } else {
        alert(`Failed to trigger scan: ${data.message}`);
        setIsRefreshing(false);
      }
    } catch (e) {
      console.error(`[Prowler ${provider}] trigger error:`, e);
      setIsRefreshing(false);
    }
  }, [accountId, baseUrl, provider, startPolling]);

  // Initial load: check status + cached findings
  useEffect(() => {
    if (!accountId) { setIsLoading(false); return; }
    if (lastIdRef.current === accountId) return;
    lastIdRef.current = accountId;
    setIsLoading(true);
    Promise.all([fetchStatus(accountId), fetchFindings(accountId)]).finally(() => setIsLoading(false));
  }, [accountId, fetchStatus, fetchFindings]);

  // Poll if already running
  useEffect(() => {
    if (prowlerStatus.status === 'RUNNING' && accountId && !pollRef.current) {
      startPolling(accountId);
    }
  }, [prowlerStatus.status, accountId, startPolling]);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  return { findings, prowlerStatus, isLoading, isRefreshing, triggerScan };
}

// Shared finding shape used by the scoring/table logic
interface NormalizedFinding {
  complianceFramework?: string;
  controlId?: string;
  description?: string;
  severity: string;
  resourceId: string;
  region?: string;
}

const ComplianceOpsPage: React.FC = () => {
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Provider from the currently selected account ('AWS' | 'GCP' | 'Azure')
  const provider = selectedAccountDisplay?.provider ?? 'AWS';

  // AWS hook (always called — React rules)
  const {
    findings: awsFindings,
    isLoading: awsLoading,
    isRefreshing: awsRefreshing,
    prowlerStatus: awsProwlerStatus,
    loadFindings,
  } = useSecurityData({ selectedAccountId: provider === 'AWS' ? selectedAccountId : null });

  // GCP + Azure use Prowler endpoints (always called — React rules)
  const gcpProwler = useProwlerComplianceData('GCP', provider === 'GCP' ? selectedAccountId : null);
  const azureProwler = useProwlerComplianceData('Azure', provider === 'Azure' ? selectedAccountId : null);

  // Unified values based on active provider
  const findings: NormalizedFinding[] = provider === 'GCP' ? gcpProwler.findings
    : provider === 'Azure' ? azureProwler.findings
    : awsFindings as unknown as NormalizedFinding[];

  const isLoading = provider === 'GCP' ? gcpProwler.isLoading
    : provider === 'Azure' ? azureProwler.isLoading
    : awsLoading;

  const isRefreshing = provider === 'GCP' ? gcpProwler.isRefreshing
    : provider === 'Azure' ? azureProwler.isRefreshing
    : awsRefreshing;

  const prowlerStatus = provider === 'GCP' ? gcpProwler.prowlerStatus
    : provider === 'Azure' ? azureProwler.prowlerStatus
    : awsProwlerStatus;

  const { setActions, clearActions } = useTopbar();

  const [activeTab, setActiveTab] = useTabState<ActiveTab>('overview');
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

  // Unified scan trigger used by topbar and empty-state
  const triggerScan = provider === 'GCP' ? gcpProwler.triggerScan
    : provider === 'Azure' ? azureProwler.triggerScan
    : triggerComplianceScan;

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
        {/* AWS-only: Prowler scan status badges */}
        {provider === 'AWS' && prowlerStatus.status === 'RUNNING' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-xs font-medium">
            <div className="w-2 h-2 bg-blue-500 rounded-full animate-pulse"></div>
            <span>Prowler Scan Running...</span>
          </div>
        )}
        {provider === 'AWS' && prowlerStatus.status === 'FAILED' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-red-50 text-red-700 rounded-lg text-xs font-medium" title={prowlerStatus.error || 'Unknown error'}>
            <Icon icon="mdi:alert-triangle" width={14} />
            <span>Prowler Scan Failed</span>
          </div>
        )}
        {provider === 'AWS' && prowlerStatus.status === 'COMPLETED' && prowlerStatus.findingsCount != null && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 text-green-700 rounded-lg text-xs font-medium">
            <Icon icon="mdi:check-circle-outline" width={14} />
            <span>{prowlerStatus.findingsCount} Prowler findings</span>
          </div>
        )}
        <button
          onClick={exportComplianceReport}
          disabled={isExportingReport || controlRows.length === 0}
          className="flex items-center gap-2 px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.12)] rounded-md text-xs font-medium text-emerald-600 hover:shadow-sm disabled:opacity-50 disabled:cursor-not-allowed transition-all"
        >
          <Icon icon="mdi:file-delimited-outline" width={14} />
          Export Report
        </button>
        <button
          onClick={triggerScan}
          disabled={isTriggeringScan || isRefreshing || prowlerStatus.status === 'RUNNING'}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <Icon icon="mdi:refresh" width={14} className={(isTriggeringScan || isRefreshing || prowlerStatus.status === 'RUNNING') ? 'animate-spin' : ''} />
          {prowlerStatus.status === 'RUNNING' ? 'Scan Running...' : isTriggeringScan ? 'Triggering...' : 'Run Compliance Scan'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, provider, prowlerStatus, isRefreshing, isTriggeringScan, isExportingReport, controlRows.length, exportComplianceReport, triggerScan]);

  // Reset page when filters change
  useEffect(() => {
    setControlPage(1);
  }, [selectedFramework, controlStatusFilter, controlSeverityFilter, controlSearch]);

  // --- Render: Loading ---

  if (isLoading) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6 bg-white">
        <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ duration: 0.2 }}>
          <div className="animate-pulse space-y-6">
            <div className="h-8 bg-[rgba(0,0,0,0.06)] rounded w-64" />
            <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
              {[1, 2, 3, 4].map((i) => (
                <div key={i} className="h-28 bg-[rgba(0,0,0,0.06)] rounded-xl" />
              ))}
            </div>
            <div className="h-64 bg-[rgba(0,0,0,0.06)] rounded-xl" />
          </div>
        </motion.div>
      </main>
    );
  }

  // --- Render: No Account ---

  if (!selectedAccountId) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-white">
        <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
          <div className="text-center max-w-md">
            <div className="w-14 h-14 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:cloud-outline" width={32} color="#4f46e5" />
            </div>
            <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">No Account Selected</h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
              Select a cloud account from the sidebar to view compliance status across frameworks.
            </p>
          </div>
        </motion.div>
      </main>
    );
  }

  // --- Render: No Data ---

  if (findings.length === 0 && !isLoading) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-white">
        <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
          <div className="text-center max-w-lg">
            <div className="w-14 h-14 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:shield-check-outline" width={32} color="#10b981" />
            </div>
            <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">No Compliance Data Available</h3>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6">
              Run a security scan to generate compliance findings across frameworks like CIS, SOC 2, PCI-DSS, and more.
            </p>
            <button
              onClick={triggerScan}
              disabled={isRefreshing || prowlerStatus.status === 'RUNNING'}
              className="inline-flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black disabled:opacity-50 transition-colors active:scale-95"
            >
              <Icon icon="mdi:play-outline" width={14} className={(isRefreshing || prowlerStatus.status === 'RUNNING') ? 'animate-spin' : ''} />
              {prowlerStatus.status === 'RUNNING' ? 'Scan Running...' : isRefreshing ? 'Loading...' : 'Run Compliance Scan'}
            </button>
          </div>
        </motion.div>
      </main>
    );
  }

  // --- Main Render ---

  return (
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-5">

        {/* Prowler Running Banner (AWS only) */}
        {provider === 'AWS' && prowlerStatus.status === 'RUNNING' && (
          <div className="bg-blue-50 border border-blue-200 rounded-xl p-4 flex items-center gap-3">
            <Icon icon="mdi:radar" width={20} className="text-blue-600 animate-spin shrink-0" />
            <div>
              <p className="text-sm font-semibold text-blue-900">Compliance scan in progress</p>
              <p className="text-xs text-blue-700">Results will update automatically when the scan completes.</p>
            </div>
          </div>
        )}

        {/* Overall Compliance Score + KPIs */}
        <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
          {/* Overall Score */}
          <div className="md:col-span-1 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col items-center justify-center">
            <div className={`w-16 h-16 rounded-full flex items-center justify-center mb-3 ${getScoreColor(overallStats.avgScore).bg}`}>
              <span className={`${getScoreColor(overallStats.avgScore).text}`} style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '22px', fontWeight: 400, letterSpacing: '0px', lineHeight: 1 }}>
                {overallStats.avgScore}%
              </span>
            </div>
            <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] text-center">Overall Score</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-0.5 text-center">Avg. across frameworks</p>
          </div>

          {/* KPI Cards */}
          {[
            { label: 'Frameworks',      value: overallStats.totalFrameworks, sub: 'Compliance standards',  valueClass: 'text-[rgba(0,0,0,0.88)]' },
            { label: 'Failed Controls', value: overallStats.totalFailed,     sub: 'Require attention',      valueClass: 'text-red-600' },
            { label: 'Critical Issues', value: overallStats.totalCritical,   sub: 'Fix immediately',        valueClass: 'text-red-600' },
            { label: 'Total Findings',  value: overallStats.totalFindings,   sub: 'Across all frameworks',  valueClass: 'text-[rgba(0,0,0,0.88)]' },
          ].map((c, i) => (
            <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 card-hover-lift" style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) ${(i + 1) * 0.06}s both` }}>
              <p className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.88)] mb-2">{c.label}</p>
              <p className={`tabular-nums ${c.valueClass}`} style={{ fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif", fontSize: '32px', fontWeight: 400, letterSpacing: '0px', lineHeight: '32px' }}>{c.value}</p>
              <p className="text-[11px] text-[rgba(0,0,0,0.35)] mt-2">{c.sub}</p>
            </div>
          ))}
        </div>

        {/* Tab Navigation */}
        <div className="bg-white rounded-xl shadow-sm border border-[rgba(0,0,0,0.12)]">
          <div className="border-b border-[rgba(0,0,0,0.06)] px-6">
            <nav className="flex gap-6">
              {[
                { key: 'overview' as ActiveTab, label: 'Framework Overview', icon: 'mdi:view-grid-outline' },
                { key: 'controls' as ActiveTab, label: 'Controls & Findings', icon: 'mdi:format-list-checks' },
                { key: 'reports' as ActiveTab, label: 'Reports', icon: 'mdi:file-document-outline' },
              ].map((tab) => (
                <button
                  key={tab.key}
                  onClick={() => setActiveTab(tab.key)}
                  className={`flex items-center gap-2 py-4 px-1 text-[13px] font-medium border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-indigo-600 text-indigo-600'
                      : 'border-transparent text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.2)]'
                  }`}
                >
                  <Icon icon={tab.icon} width={14} />
                  {tab.label}
                </button>
              ))}
            </nav>
          </div>

          <AnimatePresence mode="wait">
          <motion.div key={activeTab} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
          {/* ============================================ */}
          {/* TAB: Framework Overview                      */}
          {/* ============================================ */}
          {activeTab === 'overview' && (
            <div className="p-6 space-y-5">
              {/* Framework Score Cards */}
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {frameworkScores.map((fw, idx) => {
                  const scoreColor = getScoreColor(fw.score);
                  return (
                    <div
                      key={fw.name}
                      className={`rounded-xl p-5 cursor-pointer transition-all ${
                        selectedFramework === fw.name
                          ? `border-2 ${fw.borderColor} shadow-md`
                          : 'border border-[rgba(0,0,0,0.12)] shadow-sm hover:shadow-md'
                      }`}
                      style={{ animation: `fadeInUp 0.35s ease ${Math.min(idx * 0.05, 0.4)}s both` }}
                      onClick={() => {
                        setSelectedFramework(selectedFramework === fw.name ? 'all' : fw.name);
                        setActiveTab('controls');
                      }}
                    >
                      {/* Header */}
                      <div className="flex items-center justify-between mb-4">
                        <div className="flex items-center gap-3">
                          <div className={`w-9 h-9 rounded-lg ${fw.bgColor} flex items-center justify-center`}>
                            <Icon icon={fw.icon} width={16} className={fw.color} />
                          </div>
                          <div>
                            <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">{fw.displayName}</h4>
                            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">{fw.totalControls} controls</p>
                          </div>
                        </div>
                        <div className={`px-3 py-1.5 rounded-lg font-bold text-lg ${scoreColor.bg} ${scoreColor.text}`}>
                          {fw.score}%
                        </div>
                      </div>

                      {/* Progress Bar */}
                      <div className="w-full bg-[rgba(0,0,0,0.08)] rounded-full h-2 mb-4">
                        <div
                          className={`h-2 rounded-full transition-all duration-700 ${scoreColor.bar}`}
                          style={{ width: `${fw.score}%` }}
                        ></div>
                      </div>

                      {/* Pass / Fail */}
                      <div className="flex items-center justify-between text-sm mb-3">
                        <span className="flex items-center gap-1 text-green-600">
                          <Icon icon="mdi:check-circle-outline" width={13} />
                          <span className="font-semibold">{fw.passedControls} passed</span>
                        </span>
                        <span className="flex items-center gap-1 text-red-600">
                          <Icon icon="mdi:close-circle-outline" width={13} />
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
                    <div className="w-8 h-8 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5">
                      <Icon icon="mdi:information-outline" width={16} className="text-indigo-600" />
                    </div>
                    <div>
                      <h4 className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">How Compliance Scoring Works</h4>
                      <p className="text-[12px] text-[rgba(0,0,0,0.6)] leading-relaxed">
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
                  <Icon icon="mdi:magnify" width={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)]" />
                  <input
                    type="text"
                    placeholder="Search controls, descriptions..."
                    value={controlSearch}
                    onChange={(e) => setControlSearch(e.target.value)}
                    className="w-full pl-9 pr-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500"
                  />
                </div>

                {/* Framework Filter */}
                <select
                  value={selectedFramework}
                  onChange={(e) => setSelectedFramework(e.target.value)}
                  className="px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] font-medium text-[rgba(0,0,0,0.7)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
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
                  className="px-3 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[13px] font-medium text-[rgba(0,0,0,0.7)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="all">All Severities</option>
                  <option value="CRITICAL">Critical</option>
                  <option value="HIGH">High</option>
                  <option value="MEDIUM">Medium</option>
                  <option value="LOW">Low</option>
                </select>

                {/* Results Count */}
                <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.4)] bg-[rgba(0,0,0,0.04)] px-1.5 py-0.5 rounded-md">
                  {filteredControls.length} controls
                </span>
              </div>

              {/* Controls Table */}
              <div className="overflow-x-auto rounded-xl border border-[rgba(0,0,0,0.12)] shadow-sm">
                <table className="w-full">
                  <thead>
                    <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                      <th className="text-left px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Framework</th>
                      <th className="text-left px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Control ID</th>
                      <th className="text-left px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)] w-[40%]">Description</th>
                      <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Status</th>
                      <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Severity</th>
                      <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resources</th>
                      <th className="text-left px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Regions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {paginatedControls.length === 0 ? (
                      <tr>
                        <td colSpan={7} className="px-4 py-12 text-center">
                          <div className="text-[rgba(0,0,0,0.4)] flex flex-col items-center gap-2">
                            <Icon icon="mdi:magnify" width={32} className="text-[rgba(0,0,0,0.15)]" />
                            <p className="text-[13px]">No controls match your filters</p>
                          </div>
                        </td>
                      </tr>
                    ) : (
                      paginatedControls.map((control, idx) => {
                        const sevBadge = getSeverityBadge(control.severity);
                        const fwConfig = getFrameworkConfig(control.framework);
                        return (
                          <tr key={idx} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors" style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}>
                            <td className="px-4 py-3">
                              <span className={`inline-flex items-center gap-1.5 px-2 py-1 rounded text-xs font-semibold ${fwConfig.bgColor} ${fwConfig.color}`}>
                                <Icon icon={fwConfig.icon} width={11} />
                                {fwConfig.displayName}
                              </span>
                            </td>
                            <td className="px-4 py-3 text-[13px] font-mono text-[rgba(0,0,0,0.88)] font-medium">{control.controlId}</td>
                            <td className="px-4 py-3 text-[13px] text-[rgba(0,0,0,0.7)] leading-snug">{control.description}</td>
                            <td className="px-4 py-3 text-center">
                              <span className="inline-flex items-center gap-1 px-2 py-1 bg-red-100 text-red-700 rounded text-xs font-bold">
                                <Icon icon="mdi:close-circle-outline" width={11} />
                                FAIL
                              </span>
                            </td>
                            <td className="px-4 py-3 text-center">
                              <span className={`px-2 py-1 rounded text-xs font-bold ${sevBadge.bg} ${sevBadge.text}`}>
                                {sevBadge.label}
                              </span>
                            </td>
                            <td className="px-4 py-3 text-center">
                              <span className="text-[13px] font-bold text-[rgba(0,0,0,0.88)]">{control.affectedResources}</span>
                            </td>
                            <td className="px-4 py-3">
                              <div className="flex flex-wrap gap-1">
                                {control.regions.slice(0, 3).map((r, i) => (
                                  <span key={i} className="px-1.5 py-0.5 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] rounded text-[11px]">
                                    {r}
                                  </span>
                                ))}
                                {control.regions.length > 3 && (
                                  <span className="px-1.5 py-0.5 bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.4)] rounded text-[11px]">
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
                  <div className="text-[13px] text-[rgba(0,0,0,0.45)]">
                    Showing {((controlPage - 1) * controlsPerPage) + 1}-{Math.min(controlPage * controlsPerPage, filteredControls.length)} of {filteredControls.length}
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => setControlPage((p) => Math.max(1, p - 1))}
                      disabled={controlPage === 1}
                      className="px-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-[rgba(0,0,0,0.025)]"
                    >
                      <Icon icon="mdi:chevron-left" width={16} />
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
                              : 'border border-[rgba(0,0,0,0.12)] text-[rgba(0,0,0,0.7)] hover:bg-[rgba(0,0,0,0.025)]'
                          }`}
                        >
                          {page}
                        </button>
                      );
                    })}
                    <button
                      onClick={() => setControlPage((p) => Math.min(totalControlPages, p + 1))}
                      disabled={controlPage === totalControlPages}
                      className="px-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-[rgba(0,0,0,0.025)]"
                    >
                      <Icon icon="mdi:chevron-right" width={16} />
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
            <div className="p-6 space-y-5">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                {/* Compliance Report */}
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 hover:shadow-md transition-all">
                  <div className="flex items-center gap-3 mb-4">
                    <div className="w-9 h-9 bg-indigo-50 border border-indigo-100 rounded-lg flex items-center justify-center flex-shrink-0">
                      <Icon icon="mdi:file-delimited-outline" width={18} className="text-indigo-600" />
                    </div>
                    <div>
                      <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Detailed Controls Report</h4>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)]">All controls with affected resources</p>
                    </div>
                  </div>
                  <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
                    Export a CSV with every compliance control, its status, severity, affected resource IDs, and regions.
                    Suitable for audit submissions and compliance reviews.
                  </p>
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{controlRows.length} controls</span>
                    <button
                      onClick={exportComplianceReport}
                      disabled={controlRows.length === 0}
                      className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                    >
                      <Icon icon="mdi:download" width={14} />
                      Download CSV
                    </button>
                  </div>
                </div>

                {/* Framework Summary */}
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-6 hover:shadow-md transition-all">
                  <div className="flex items-center gap-3 mb-4">
                    <div className="w-9 h-9 bg-purple-50 border border-purple-100 rounded-lg flex items-center justify-center flex-shrink-0">
                      <Icon icon="mdi:chart-pie" width={18} className="text-purple-600" />
                    </div>
                    <div>
                      <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Framework Summary Report</h4>
                      <p className="text-[11px] text-[rgba(0,0,0,0.4)]">High-level compliance scores</p>
                    </div>
                  </div>
                  <p className="text-[13px] text-[rgba(0,0,0,0.55)] mb-4">
                    Export a summary with each framework's compliance score, pass/fail control counts, and severity
                    breakdown. Ideal for executive dashboards and compliance deck preparation.
                  </p>
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] text-[rgba(0,0,0,0.35)]">{frameworkScores.length} frameworks</span>
                    <button
                      onClick={exportFrameworkSummary}
                      disabled={frameworkScores.length === 0}
                      className="flex items-center gap-2 px-4 py-2 bg-purple-600 text-white rounded-lg text-sm font-medium hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                    >
                      <Icon icon="mdi:download" width={14} />
                      Download CSV
                    </button>
                  </div>
                </div>
              </div>

              {/* Report Preview Table */}
              {frameworkScores.length > 0 && (
                <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
                  <div className="bg-[rgba(0,0,0,0.02)] px-6 py-4 border-b border-[rgba(0,0,0,0.06)]">
                    <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] flex items-center gap-2">
                      <Icon icon="mdi:table" width={16} className="text-[rgba(0,0,0,0.5)]" />
                      Framework Summary Preview
                    </h4>
                  </div>
                  <div className="overflow-x-auto">
                    <table className="w-full">
                      <thead>
                        <tr className="border-b border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.02)]">
                          <th className="text-left px-6 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Framework</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Score</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Passed</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Failed</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Critical</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">High</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Medium</th>
                          <th className="text-center px-4 py-3 text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Low</th>
                        </tr>
                      </thead>
                      <tbody>
                        {frameworkScores.map((fw, idx) => {
                          const scoreColor = getScoreColor(fw.score);
                          return (
                            <tr key={fw.name} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors" style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}>
                              <td className="px-6 py-3">
                                <div className="flex items-center gap-2">
                                  <div className={`w-8 h-8 rounded-lg ${fw.bgColor} flex items-center justify-center`}>
                                    <Icon icon={fw.icon} width={16} className={fw.color} />
                                  </div>
                                  <span className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">{fw.displayName}</span>
                                </div>
                              </td>
                              <td className="px-4 py-3 text-center">
                                <span className={`px-2.5 py-1 rounded-lg font-bold text-sm ${scoreColor.bg} ${scoreColor.text}`}>
                                  {fw.score}%
                                </span>
                              </td>
                              <td className="px-4 py-3 text-center text-[13px] font-semibold text-green-600">{fw.passedControls}</td>
                              <td className="px-4 py-3 text-center text-[13px] font-semibold text-red-600">{fw.failedControls}</td>
                              <td className="px-4 py-3 text-center">
                                {fw.criticalFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-red-100 text-red-700 rounded text-xs font-bold">{fw.criticalFindings}</span>
                                ) : <span className="text-[rgba(0,0,0,0.15)]">-</span>}
                              </td>
                              <td className="px-4 py-3 text-center">
                                {fw.highFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-orange-100 text-orange-700 rounded text-xs font-bold">{fw.highFindings}</span>
                                ) : <span className="text-[rgba(0,0,0,0.15)]">-</span>}
                              </td>
                              <td className="px-4 py-3 text-center">
                                {fw.mediumFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-amber-100 text-amber-700 rounded text-xs font-bold">{fw.mediumFindings}</span>
                                ) : <span className="text-[rgba(0,0,0,0.15)]">-</span>}
                              </td>
                              <td className="px-4 py-3 text-center">
                                {fw.lowFindings > 0 ? (
                                  <span className="px-2 py-0.5 bg-blue-100 text-blue-700 rounded text-xs font-bold">{fw.lowFindings}</span>
                                ) : <span className="text-[rgba(0,0,0,0.15)]">-</span>}
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
          </motion.div>
          </AnimatePresence>
        </div>
      </motion.div>
    </main>
  );
};

export default ComplianceOpsPage;
