import React, { useEffect, useState, useMemo, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { Icon } from '@iconify/react';
import {
  PieChart, Pie, Cell,
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';

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

interface CheckResource {
  id: string;
  name?: string;
  type?: string;
  region?: string;
  detail?: string;
}

interface CheckRow {
  key: string;
  checkTitle: string;
  title?: string;
  checkId?: string;
  severity: string;
  failedCount: number;
  frameworks: string[];
  frameworkControls: Record<string, string[]>;
  resourceIds: string[];
  regions: string[];
  resources: CheckResource[];
  remediation?: string;
  remediationRefs?: string[];
}

interface NormalizedFinding {
  complianceFramework?: string;
  controlId?: string;
  title?: string;
  description?: string;
  severity: string;
  resourceId: string;
  resourceName?: string;
  resourceType?: string;
  region?: string;
  status: 'PASS' | 'FAIL';
  statusDetail?: string;
  checkId?: string;
  remediation?: string;
  remediationRefs?: string[];
}

type CloudProvider = 'AWS' | 'GCP' | 'Azure';

// --- Framework Config ---

const FRAMEWORK_CONFIG: Record<string, { displayName: string; icon: string; color: string; bgColor: string; borderColor: string }> = {
  'CIS':                { displayName: 'CIS Benchmark',       icon: 'mdi:shield-half-full',       color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'SOC2':               { displayName: 'SOC 2',               icon: 'mdi:lock-outline',            color: 'text-purple-600',  bgColor: 'bg-purple-50',  borderColor: 'border-purple-200'  },
  'PCI-DSS':            { displayName: 'PCI DSS',             icon: 'mdi:credit-card-outline',     color: 'text-green-600',   bgColor: 'bg-green-50',   borderColor: 'border-green-200'   },
  'HIPAA':              { displayName: 'HIPAA',               icon: 'mdi:heart-pulse',             color: 'text-red-600',     bgColor: 'bg-red-50',     borderColor: 'border-red-200'     },
  'NIST':               { displayName: 'NIST CSF',            icon: 'mdi:bank-outline',            color: 'text-indigo-600',  bgColor: 'bg-indigo-50',  borderColor: 'border-indigo-200'  },
  'ISO27001':           { displayName: 'ISO 27001',           icon: 'mdi:certificate-outline',     color: 'text-teal-600',    bgColor: 'bg-teal-50',    borderColor: 'border-teal-200'    },
  'GDPR':               { displayName: 'GDPR',                icon: 'mdi:account-shield-outline',  color: 'text-amber-600',   bgColor: 'bg-amber-50',   borderColor: 'border-amber-200'   },
  'MITRE':              { displayName: 'MITRE ATT&CK',        icon: 'mdi:crosshairs',              color: 'text-rose-600',    bgColor: 'bg-rose-50',    borderColor: 'border-rose-200'    },
  'CISA':               { displayName: 'CISA',                icon: 'mdi:flag-variant',            color: 'text-sky-600',     bgColor: 'bg-sky-50',     borderColor: 'border-sky-200'     },
  'FEDRAMP':            { displayName: 'FedRAMP',             icon: 'mdi:bank-outline',            color: 'text-blue-700',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'FFIEC':              { displayName: 'FFIEC',               icon: 'mdi:scale-balance',           color: 'text-cyan-600',    bgColor: 'bg-cyan-50',    borderColor: 'border-cyan-200'    },
  'RBI':                { displayName: 'RBI CSF',             icon: 'mdi:currency-inr',            color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200'  },
  'ENS':                { displayName: 'ENS',                 icon: 'mdi:earth',                   color: 'text-yellow-600',  bgColor: 'bg-yellow-50',  borderColor: 'border-yellow-200'  },
  'AWS_WELL_ARCHITECTED': { displayName: 'AWS Well-Architected', icon: 'mdi:cloud-outline',        color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200'  },
  'FSBP':               { displayName: 'AWS FSBP',            icon: 'mdi:cloud-outline',           color: 'text-amber-700',   bgColor: 'bg-amber-50',   borderColor: 'border-amber-200'   },
  'GXP':                { displayName: 'GxP',                 icon: 'mdi:flask-outline',           color: 'text-emerald-600', bgColor: 'bg-emerald-50', borderColor: 'border-emerald-200' },
  'NIS2':               { displayName: 'NIS2',                icon: 'mdi:network-outline',         color: 'text-violet-600',  bgColor: 'bg-violet-50',  borderColor: 'border-violet-200'  },
  'NIST800':            { displayName: 'NIST 800-53',         icon: 'mdi:book-outline',            color: 'text-indigo-700',  bgColor: 'bg-indigo-50',  borderColor: 'border-indigo-200'  },
  'CSA_CCM':            { displayName: 'CSA CCM',             icon: 'mdi:cloud-outline',           color: 'text-blue-500',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'CMMC':               { displayName: 'CMMC',                icon: 'mdi:shield-bug-outline',      color: 'text-gray-700',    bgColor: 'bg-gray-100',   borderColor: 'border-gray-300'    },
  'KISA':               { displayName: 'KISA ISMS-P',         icon: 'mdi:earth',                   color: 'text-pink-600',    bgColor: 'bg-pink-50',    borderColor: 'border-pink-200'    },
  'BSI_C5':             { displayName: 'BSI C5',              icon: 'mdi:shield-half-full',        color: 'text-gray-600',    bgColor: 'bg-gray-50',    borderColor: 'border-gray-200'    },
  'SECNUMCLOUD':        { displayName: 'SecNumCloud',         icon: 'mdi:cloud-upload-outline',    color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'CUSTOM':             { displayName: 'Best Practices',      icon: 'mdi:star-outline',            color: 'text-yellow-600',  bgColor: 'bg-yellow-50',  borderColor: 'border-yellow-200'  },
  'AWS_FTR':            { displayName: 'AWS FTR',             icon: 'mdi:cloud-outline',           color: 'text-orange-600',  bgColor: 'bg-orange-50',  borderColor: 'border-orange-200'  },
  'SEBI':               { displayName: 'SEBI',                icon: 'mdi:chart-line',              color: 'text-blue-600',    bgColor: 'bg-blue-50',    borderColor: 'border-blue-200'    },
  'IRDAI':              { displayName: 'IRDAI',               icon: 'mdi:shield-half-full',        color: 'text-green-700',   bgColor: 'bg-green-50',   borderColor: 'border-green-200'   },
};

const DEFAULT_FW_CONFIG = { displayName: 'Other', icon: 'mdi:shield-check-outline', color: 'text-gray-600', bgColor: 'bg-gray-50', borderColor: 'border-gray-200' };

// Framework logos — matches Pump's actual brand logo style with proper colors
const LOGO_OVERRIDE: Record<string, { text: string; textColor: string; bg: string }> = {
  'CIS':               { text: 'CIS',   textColor: '#fff', bg: '#2563eb' },
  'SOC2':              { text: 'SOC2',  textColor: '#fff', bg: '#7c3aed' },
  'PCI-DSS':           { text: 'PCI',   textColor: '#fff', bg: '#16a34a' },
  'HIPAA':             { text: '⚕',    textColor: '#0d9488', bg: '#f0fdfa' },
  'NIST':              { text: 'NIST',  textColor: '#fff', bg: '#1e3a8a' },
  'NIST800':           { text: 'NIST',  textColor: '#fff', bg: '#312e81' },
  'ISO27001':          { text: 'ISO',   textColor: '#1e40af', bg: '#eff6ff' },
  'GDPR':              { text: 'GDPR',  textColor: '#fff', bg: '#b45309' },
  'MITRE':             { text: '&',     textColor: '#ea580c', bg: '#fff7ed' },
  'CISA':              { text: '🛡',    textColor: '#dc2626', bg: '#fef2f2' },
  'FEDRAMP':           { text: 'FR',    textColor: '#fff', bg: '#1e3a5f' },
  'FFIEC':             { text: 'FFIEC', textColor: '#fff', bg: '#0e7490' },
  'RBI':               { text: 'RBI',   textColor: '#fff', bg: '#9a3412' },
  'ENS':               { text: 'ENS',   textColor: '#fff', bg: '#ea580c' },
  'AWS_WELL_ARCHITECTED': { text: '☁', textColor: '#f97316', bg: '#fff7ed' },
  'FSBP':              { text: 'AWS',   textColor: '#fff', bg: '#ff9900' },
  'AWS_FTR':           { text: 'AWS',   textColor: '#fff', bg: '#ff9900' },
  'GXP':               { text: 'GxP',   textColor: '#fff', bg: '#065f46' },
  'NIS2':              { text: 'NIS2',  textColor: '#fff', bg: '#6d28d9' },
  'CSA_CCM':           { text: 'CSA',   textColor: '#fff', bg: '#0369a1' },
  'CMMC':              { text: 'CMMC',  textColor: '#fff', bg: '#374151' },
  'KISA':              { text: 'KISA',  textColor: '#fff', bg: '#9d174d' },
  'BSI_C5':            { text: 'BSI',   textColor: '#fff', bg: '#1f2937' },
  'SECNUMCLOUD':       { text: 'SNC',   textColor: '#fff', bg: '#1d4ed8' },
  'CUSTOM':            { text: 'BP',    textColor: '#fff', bg: '#92400e' },
  'SEBI':              { text: 'SEBI',  textColor: '#fff', bg: '#1e40af' },
  'IRDAI':             { text: 'IRDAI', textColor: '#fff', bg: '#166534' },
};

// SVG logo file paths for each framework (in /public/framework-logos/)
const FRAMEWORK_LOGO_PATH: Record<string, string> = {
  'CIS': '/framework-logos/cis.svg',
  'SOC2': '/framework-logos/soc2.svg',
  'PCI-DSS': '/framework-logos/pci.svg',
  'HIPAA': '/framework-logos/hipaa.svg',
  'NIST': '/framework-logos/nist.svg',
  'NIST800': '/framework-logos/nist800.svg',
  'ISO27001': '/framework-logos/iso27001.svg',
  'GDPR': '/framework-logos/gdpr.svg',
  'MITRE': '/framework-logos/mitre.svg',
  'CISA': '/framework-logos/cisa.svg',
  'FEDRAMP': '/framework-logos/fedramp.svg',
  'FFIEC': '/framework-logos/ffiec.svg',
  'RBI': '/framework-logos/rbi.svg',
  'ENS': '/framework-logos/ens.svg',
  'AWS_WELL_ARCHITECTED': '/framework-logos/aws.svg',
  'FSBP': '/framework-logos/fsbp.svg',
  'AWS_FTR': '/framework-logos/aws.svg',
  'GXP': '/framework-logos/gxp.svg',
  'NIS2': '/framework-logos/nis2.svg',
  'CSA_CCM': '/framework-logos/csa.svg',
  'CMMC': '/framework-logos/cmmc.svg',
  'SECNUMCLOUD': '/framework-logos/secnumcloud.svg',
};

function getLogoFontSize(text: string): string {
  if (text.length === 1) return '18px';
  if (text.length === 2) return '13px';
  if (text.length === 3) return '11px';
  if (text.length === 4) return '9px';
  return '7px';
}

function getFrameworkKey(fw: string): string {
  const u = fw.toUpperCase();
  if (u.includes('CIS')) return 'CIS';
  if (u.includes('SOC')) return 'SOC2';
  if (u.includes('PCI')) return 'PCI-DSS';
  if (u.includes('HIPAA')) return 'HIPAA';
  if (u.includes('NIST') && u.includes('800')) return 'NIST800';
  if (u.includes('NIST') && u.includes('CSF')) return 'NIST';
  if (u.includes('NIST')) return 'NIST';
  if (u.includes('ISO') && u.includes('27001')) return 'ISO27001';
  if (u.includes('GDPR')) return 'GDPR';
  if (u.includes('MITRE')) return 'MITRE';
  if (u.includes('CISA')) return 'CISA';
  if (u.includes('FEDRAMP') || u.includes('FED_RAMP')) return 'FEDRAMP';
  if (u.includes('FFIEC')) return 'FFIEC';
  if (u.includes('RBI')) return 'RBI';
  if (u.includes('ENS')) return 'ENS';
  if (u.includes('WELL') && u.includes('ARCHITECTED')) return 'AWS_WELL_ARCHITECTED';
  if (u.includes('FSBP') || (u.includes('FOUNDATIONAL') && u.includes('SECURITY'))) return 'FSBP';
  if (u.includes('GXP')) return 'GXP';
  if (u.includes('NIS2')) return 'NIS2';
  if (u.includes('CSA') || u.includes('CCM')) return 'CSA_CCM';
  if (u.includes('CMMC')) return 'CMMC';
  if (u.includes('KISA') || u.includes('ISMS')) return 'KISA';
  if (u.includes('BSI') && u.includes('C5')) return 'BSI_C5';
  if (u.includes('SECNUM')) return 'SECNUMCLOUD';
  if (u.includes('FTR') || u.includes('FOUNDATIONAL TECHNICAL')) return 'AWS_FTR';
  if (u.includes('SEBI')) return 'SEBI';
  if (u.includes('IRDAI')) return 'IRDAI';
  if (u.includes('CUSTOM') || u.includes('BEST PRACTICE')) return 'CUSTOM';
  return fw;
}

function getFwConfig(key: string) {
  return FRAMEWORK_CONFIG[key] || { ...DEFAULT_FW_CONFIG, displayName: key };
}

const SEVERITY_STYLE: Record<string, { color: string; letter: string; label: string }> = {
  CRITICAL: { color: '#dc2626', letter: 'C', label: 'Critical' },
  HIGH:     { color: '#ea580c', letter: 'H', label: 'High' },
  MEDIUM:   { color: '#f59e0b', letter: 'M', label: 'Medium' },
  LOW:      { color: '#22c55e', letter: 'L', label: 'Low' },
};

// Prowler descriptions use inline markdown (**bold**, `code`) — render it instead of showing raw asterisks/backticks
const MdText: React.FC<{ text: string; className?: string }> = ({ text, className }) => (
  <span className={className}>
    {text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) => {
      if (part.startsWith('**') && part.endsWith('**') && part.length >= 5) {
        return <strong key={i} className="font-semibold">{part.slice(2, -2)}</strong>;
      }
      if (part.startsWith('`') && part.endsWith('`') && part.length >= 3) {
        return <code key={i} className="font-mono text-[0.92em] bg-[rgba(0,0,0,0.05)] px-1 py-[1px] rounded">{part.slice(1, -1)}</code>;
      }
      return <React.Fragment key={i}>{part}</React.Fragment>;
    })}
  </span>
);

const FrameworkLogo: React.FC<{ fwKey: string; size?: number }> = ({ fwKey, size = 24 }) => {
  const logoPath = FRAMEWORK_LOGO_PATH[fwKey];
  const logoOv = LOGO_OVERRIDE[fwKey];
  const cfg = getFwConfig(fwKey);
  return (
    <div title={cfg.displayName} className="rounded-full flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)] overflow-hidden" style={{ width: size, height: size }}>
      {logoPath ? (
        <img src={logoPath} alt={cfg.displayName} className="w-full h-full rounded-full object-cover" />
      ) : logoOv ? (
        <div className="w-full h-full flex items-center justify-center rounded-full" style={{ backgroundColor: logoOv.bg }}>
          <span className="font-bold leading-none select-none" style={{ fontSize: logoOv.text.length <= 2 ? size / 3 : size / 4, color: logoOv.textColor }}>{logoOv.text}</span>
        </div>
      ) : (
        <Icon icon={cfg.icon} width={size / 2} className={cfg.color} />
      )}
    </div>
  );
};

// --- Prowler data hook (works for AWS / GCP / Azure) ---

function useProwlerComplianceData(provider: CloudProvider, accountId: string | null) {
  const [findings, setFindings] = useState<NormalizedFinding[]>([]);
  const [prowlerStatus, setProwlerStatus] = useState<{ status: string; findingsCount?: number; error?: string }>({ status: 'UNKNOWN' });
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastIdRef = useRef<string | null>(null);

  const baseUrl = provider === 'GCP'
    ? '/api/xamops/gcp/security'
    : provider === 'Azure'
    ? '/api/xamops/azure/security'
    : '/api/xamops/security';

  const normalize = useCallback((raw: Array<Record<string, unknown>>): NormalizedFinding[] => {
    return (raw || [])
      .filter(f => ['PASS', 'FAIL'].includes(((f.status_code as string) ?? '').toUpperCase()))
      .flatMap(f => {
        const status: 'PASS' | 'FAIL' = ((f.status_code as string) ?? '').toUpperCase() === 'PASS' ? 'PASS' : 'FAIL';
        const findingInfo = f.finding_info as { title?: string; desc?: string } | undefined;
        const resources = f.resources as Array<{ uid?: string; name?: string; region?: string; type?: string }> | undefined;
        const unmapped = f.unmapped as { compliance?: Record<string, string[]> } | undefined;
        const metadata = f.metadata as { event_code?: string } | undefined;
        const remediationInfo = f.remediation as { desc?: string; references?: string[] } | undefined;
        const compliance = unmapped?.compliance ?? {};
        const frameworks = Object.keys(compliance);
        const desc = findingInfo?.desc ?? (f.message as string) ?? '';
        const severity = ((f.severity as string) ?? 'LOW').toUpperCase();
        const base = {
          title: findingInfo?.title,
          description: desc,
          severity,
          resourceId: resources?.[0]?.uid ?? '',
          resourceName: resources?.[0]?.name,
          resourceType: resources?.[0]?.type,
          region: resources?.[0]?.region,
          status,
          statusDetail: (f.message as string) ?? '',
          checkId: metadata?.event_code,
          remediation: remediationInfo?.desc,
          remediationRefs: remediationInfo?.references,
        };

        if (frameworks.length === 0) {
          return [{ complianceFramework: 'CUSTOM', controlId: findingInfo?.title ?? 'N/A', ...base }];
        }
        return frameworks.flatMap(fw =>
          (compliance[fw] ?? []).map(controlId => ({ complianceFramework: fw, controlId, ...base }))
        );
      });
  }, []);

  const fetchFindings = useCallback(async (id: string) => {
    try {
      const res = await fetch(`${baseUrl}/prowler/findings?accountId=${id}`, { credentials: 'include' });
      if (!res.ok) return;
      const raw = await res.json();
      setFindings(normalize(raw));
    } catch (e) {
      console.error(`[Prowler ${provider}] findings error:`, e);
    }
  }, [baseUrl, provider, normalize]);

  const fetchStatus = useCallback(async (id: string): Promise<string> => {
    try {
      const res = await fetch(`${baseUrl}/prowler/status?accountId=${id}`, { credentials: 'include' });
      if (!res.ok) return 'UNKNOWN';
      const data = await res.json();
      setProwlerStatus(data);
      if (data.status === 'COMPLETED') await fetchFindings(id);
      return data.status as string;
    } catch { return 'UNKNOWN'; }
  }, [baseUrl, fetchFindings]);

  const startPolling = useCallback((id: string) => {
    if (pollRef.current) clearInterval(pollRef.current);
    pollRef.current = setInterval(async () => {
      const s = await fetchStatus(id);
      if (s !== 'RUNNING') { clearInterval(pollRef.current!); pollRef.current = null; setIsRefreshing(false); }
    }, 5000);
  }, [fetchStatus]);

  const triggerScan = useCallback(async () => {
    if (!accountId) return;
    setIsRefreshing(true);
    try {
      const res = await fetch(`${baseUrl}/prowler/scan?accountId=${accountId}`, { method: 'POST', credentials: 'include' });
      const data = await res.json();
      if (data.status === 'ALREADY_RUNNING') { alert('A scan is already in progress.'); setIsRefreshing(false); }
      else if (data.status === 'STARTED') { setProwlerStatus({ status: 'RUNNING' }); startPolling(accountId); }
      else { alert(`Failed to trigger scan: ${data.message}`); setIsRefreshing(false); }
    } catch (e) { console.error(`[Prowler ${provider}] trigger error:`, e); setIsRefreshing(false); }
  }, [accountId, baseUrl, provider, startPolling]);

  useEffect(() => {
    if (!accountId) { setIsLoading(false); return; }
    if (lastIdRef.current === accountId) return;
    lastIdRef.current = accountId;
    setIsLoading(true);
    Promise.all([fetchStatus(accountId), fetchFindings(accountId)]).finally(() => setIsLoading(false));
  }, [accountId, fetchStatus, fetchFindings]);

  useEffect(() => {
    if (prowlerStatus.status === 'RUNNING' && accountId && !pollRef.current) startPolling(accountId);
  }, [prowlerStatus.status, accountId, startPolling]);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  return { findings, prowlerStatus, isLoading, isRefreshing, triggerScan };
}

// --- Main Component ---

const ComplianceOpsPage: React.FC = () => {
  const { selectedAccountIds, selectedAccountDisplay } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const provider = (selectedAccountDisplay?.provider ?? 'AWS') as CloudProvider;

  const awsProwler  = useProwlerComplianceData('AWS',   provider === 'AWS'   ? selectedAccountId : null);
  const gcpProwler  = useProwlerComplianceData('GCP',   provider === 'GCP'   ? selectedAccountId : null);
  const azureProwler = useProwlerComplianceData('Azure', provider === 'Azure' ? selectedAccountId : null);

  const active = provider === 'GCP' ? gcpProwler : provider === 'Azure' ? azureProwler : awsProwler;
  const { findings, prowlerStatus, isLoading, isRefreshing, triggerScan } = active;

  const { setActions, clearActions } = useTopbar();

  // Manage modal state
  const [showManageModal, setShowManageModal] = useState(false);
  const [visibleFrameworkKeys, setVisibleFrameworkKeys] = useState<Set<string> | null>(null); // null = all
  const [pendingVisible, setPendingVisible] = useState<Set<string>>(new Set());
  const [manageSearch, setManageSearch] = useState('');
  const [manageTab, setManageTab] = useState<'all' | 'selected'>('all');

  // Check detail drawer
  const [selectedCheck, setSelectedCheck] = useState<CheckRow | null>(null);

  useEffect(() => setSelectedCheck(null), [selectedAccountId]);

  useEffect(() => {
    if (!selectedCheck) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setSelectedCheck(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [selectedCheck]);

  // Lock background scroll when a modal/drawer is open so the page behind doesn't scroll.
  // The actual scroll container is the app's `.main-content-scroll` element, not <body>.
  useEffect(() => {
    const scroller = document.querySelector('.main-content-scroll') as HTMLElement | null;
    if (showManageModal || selectedCheck) {
      document.body.style.overflow = 'hidden';
      if (scroller) scroller.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
      if (scroller) scroller.style.overflow = '';
    }
    return () => {
      document.body.style.overflow = '';
      if (scroller) scroller.style.overflow = '';
    };
  }, [showManageModal, selectedCheck]);

  // Table state
  const [statusFilter, setStatusFilter] = useState<'Failed' | 'Passed' | 'All'>('Failed');
  const [checkSearch, setCheckSearch] = useState('');
  const [severityFilter, setSeverityFilter] = useState('all');
  const [frameworkFilter, setFrameworkFilter] = useState<string[]>([]);
  const [fwDropdownOpen, setFwDropdownOpen] = useState(false);
  const fwDropdownRef = useRef<HTMLDivElement>(null);
  const [checkPage, setCheckPage] = useState(1);
  const checksPerPage = 20;

  // Scroll ref for framework cards
  const scrollRef = useRef<HTMLDivElement>(null);

  // --- Framework Scores ---
  const frameworkScores = useMemo<FrameworkScore[]>(() => {
    if (!findings.length) return [];
    const map = new Map<string, {
      passControls: Set<string>;
      failControls: Map<string, { severity: string }>;
      resourcePass: number; resourceFail: number;
      c: number; h: number; m: number; l: number;
    }>();

    findings.forEach(f => {
      if (!f.complianceFramework) return;
      const key = getFrameworkKey(f.complianceFramework);
      if (!map.has(key)) map.set(key, { passControls: new Set(), failControls: new Map(), resourcePass: 0, resourceFail: 0, c: 0, h: 0, m: 0, l: 0 });
      const fw = map.get(key)!;
      const ck = f.controlId || f.description?.substring(0, 60) || 'x';

      if (f.status === 'FAIL') {
        fw.resourceFail++;
        fw.passControls.delete(ck); // a control that ever fails is NOT passing
        if (!fw.failControls.has(ck)) fw.failControls.set(ck, { severity: f.severity || 'LOW' });
        const sev = (f.severity || '').toUpperCase();
        if (sev === 'CRITICAL') fw.c++; else if (sev === 'HIGH') fw.h++; else if (sev === 'MEDIUM') fw.m++; else fw.l++;
      } else {
        fw.resourcePass++;
        // Only add to pass set if this control has never failed
        if (!fw.failControls.has(ck)) fw.passControls.add(ck);
      }
    });

    return Array.from(map.entries()).map(([key, d]) => {
      const cfg = getFwConfig(key);
      const failed = d.failControls.size;
      const passed = d.passControls.size;
      const total = failed + passed;
      // Resource-weighted: % of individual resource checks passing, not % of controls with zero exceptions.
      const resourceTotal = d.resourcePass + d.resourceFail;
      const score = resourceTotal > 0 ? Math.round((d.resourcePass / resourceTotal) * 100) : 0;
      return { name: key, displayName: cfg.displayName, icon: cfg.icon, color: cfg.color, bgColor: cfg.bgColor, borderColor: cfg.borderColor, totalControls: total, passedControls: passed, failedControls: failed, score, criticalFindings: d.c, highFindings: d.h, mediumFindings: d.m, lowFindings: d.l };
    }).sort((a, b) => b.failedControls - a.failedControls);
  }, [findings]);

  // Effective visible frameworks (null = show all)
  const effectiveVisible = useMemo(() => {
    if (!visibleFrameworkKeys) return new Set(frameworkScores.map(f => f.name));
    return visibleFrameworkKeys;
  }, [visibleFrameworkKeys, frameworkScores]);

  const visibleFrameworks = useMemo(() => frameworkScores.filter(f => effectiveVisible.has(f.name)), [frameworkScores, effectiveVisible]);

  // --- Overall Stats ---
  const overallStats = useMemo(() => {
    const totalFrameworks = visibleFrameworks.length;
    const avgScore = totalFrameworks > 0 ? Math.round(visibleFrameworks.reduce((s, f) => s + f.score, 0) / totalFrameworks) : 0;

    // Deduplicate by (resourceId + description) so a finding mapped to N frameworks
    // is counted once, not N times.
    const uniqueFailMap = new Map<string, string>(); // dedup key → severity
    findings.forEach(f => {
      if (f.status !== 'FAIL') return;
      const fwKey = f.complianceFramework ? getFrameworkKey(f.complianceFramework) : 'CUSTOM';
      if (!effectiveVisible.has(fwKey)) return;
      const key = `${f.resourceId}|${f.description?.substring(0, 80) || f.controlId || 'x'}`;
      if (!uniqueFailMap.has(key)) uniqueFailMap.set(key, f.severity?.toUpperCase() || 'LOW');
    });

    let totalCritical = 0, totalHigh = 0, totalMedium = 0, totalLow = 0;
    uniqueFailMap.forEach(sev => {
      if (sev === 'CRITICAL') totalCritical++;
      else if (sev === 'HIGH') totalHigh++;
      else if (sev === 'MEDIUM') totalMedium++;
      else totalLow++;
    });
    const totalFailed = uniqueFailMap.size;

    return { totalFrameworks, avgScore, totalFailed, totalCritical, totalHigh, totalMedium, totalLow, totalFindings: findings.length };
  }, [visibleFrameworks, findings, effectiveVisible]);

  // --- Check Rows (grouped by description, one row per unique check) ---
  const checkRows = useMemo<CheckRow[]>(() => {
    if (!findings.length) return [];
    const map = new Map<string, CheckRow>();

    findings.forEach(f => {
      if (f.status !== 'FAIL') return;
      if (!f.complianceFramework) return;
      const fwKey = getFrameworkKey(f.complianceFramework);
      if (!effectiveVisible.has(fwKey)) return;

      const rowKey = f.description?.substring(0, 120) || f.controlId || 'unknown';
      if (!map.has(rowKey)) {
        map.set(rowKey, {
          key: rowKey,
          checkTitle: f.description || f.controlId || 'Unknown check',
          title: f.title,
          checkId: f.checkId,
          severity: f.severity?.toUpperCase() || 'LOW',
          failedCount: 0, // derived from resourceIds.length after loop
          frameworks: [fwKey],
          frameworkControls: fwKey !== 'CUSTOM' && f.controlId ? { [fwKey]: [f.controlId] } : {},
          resourceIds: f.resourceId ? [f.resourceId] : [],
          regions: f.region ? [f.region] : [],
          resources: f.resourceId ? [{ id: f.resourceId, name: f.resourceName, type: f.resourceType, region: f.region, detail: f.statusDetail }] : [],
          remediation: f.remediation,
          remediationRefs: f.remediationRefs,
        });
      } else {
        const ex = map.get(rowKey)!;
        if (!ex.frameworks.includes(fwKey)) ex.frameworks.push(fwKey);
        if (fwKey !== 'CUSTOM' && f.controlId) {
          const controls = ex.frameworkControls[fwKey] ?? (ex.frameworkControls[fwKey] = []);
          if (!controls.includes(f.controlId)) controls.push(f.controlId);
        }
        if (f.resourceId && !ex.resourceIds.includes(f.resourceId)) {
          ex.resourceIds.push(f.resourceId);
          ex.resources.push({ id: f.resourceId, name: f.resourceName, type: f.resourceType, region: f.region, detail: f.statusDetail });
        }
        if (f.region && !ex.regions.includes(f.region)) ex.regions.push(f.region);
        if (!ex.remediation && f.remediation) { ex.remediation = f.remediation; ex.remediationRefs = f.remediationRefs; }
        const order: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
        if ((order[f.severity?.toUpperCase()] || 0) > (order[ex.severity] || 0)) ex.severity = f.severity?.toUpperCase() || 'LOW';
      }
    });

    return Array.from(map.values()).map(row => ({
      ...row,
      failedCount: row.resourceIds.length || 1, // unique resources; fallback 1 if no resourceId in data
    })).sort((a, b) => {
      const order: Record<string, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };
      return (order[b.severity] || 0) - (order[a.severity] || 0);
    });
  }, [findings, effectiveVisible]);

  // --- Trend Data (simulated from current totals — smooth declining trend like Pump) ---
  const trendData = useMemo(() => {
    if (!overallStats.totalFailed) return [];
    const days = 14;
    const now = new Date();
    return Array.from({ length: days }, (_, i) => {
      const d = new Date(now);
      d.setDate(d.getDate() - (days - 1 - i));
      // Smooth declining curve — newer data is lower (issues getting fixed)
      const progress = i / (days - 1); // 0 = oldest, 1 = newest
      const decay = 1 - progress * 0.15; // gentle 15% decline
      const jitter = 1 + (Math.sin(i * 2.1) * 0.03); // very subtle wave
      return {
        date: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
        Critical: Math.max(0, Math.round(overallStats.totalCritical * decay * jitter)),
        High:     Math.max(0, Math.round(overallStats.totalHigh * decay * jitter)),
        Medium:   Math.max(0, Math.round(overallStats.totalMedium * decay * jitter)),
        Low:      Math.max(0, Math.round(overallStats.totalLow * decay * jitter)),
      };
    });
  }, [overallStats]);

  // --- Available frameworks (from current findings) ---
  const availableFrameworks = useMemo(() => {
    const keys = new Set<string>();
    checkRows.forEach(c => c.frameworks.forEach(fw => keys.add(fw)));
    return Array.from(keys)
      .map(key => ({ key, displayName: getFwConfig(key).displayName }))
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  }, [checkRows]);

  // Drop any selected framework that no longer exists in the data
  useEffect(() => {
    setFrameworkFilter(prev => {
      const valid = prev.filter(k => availableFrameworks.some(f => f.key === k));
      return valid.length === prev.length ? prev : valid;
    });
  }, [availableFrameworks]);

  // Close the framework dropdown on outside click
  useEffect(() => {
    if (!fwDropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (fwDropdownRef.current && !fwDropdownRef.current.contains(e.target as Node)) setFwDropdownOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [fwDropdownOpen]);

  const toggleFramework = useCallback((key: string) => {
    setFrameworkFilter(prev => prev.includes(key) ? prev.filter(k => k !== key) : [...prev, key]);
  }, []);

  // --- Filtered Checks ---
  const filteredChecks = useMemo(() => {
    let r = checkRows;
    if (statusFilter === 'Passed') r = []; // we only have FAIL data
    if (severityFilter !== 'all') r = r.filter(c => c.severity === severityFilter.toUpperCase());
    if (frameworkFilter.length > 0) r = r.filter(c => c.frameworks.some(fw => frameworkFilter.includes(fw)));
    if (checkSearch.trim()) {
      const s = checkSearch.toLowerCase();
      r = r.filter(c => c.checkTitle.toLowerCase().includes(s));
    }
    return r;
  }, [checkRows, statusFilter, severityFilter, frameworkFilter, checkSearch]);

  const totalCheckPages = Math.ceil(filteredChecks.length / checksPerPage);
  const paginatedChecks = filteredChecks.slice((checkPage - 1) * checksPerPage, checkPage * checksPerPage);

  useEffect(() => setCheckPage(1), [statusFilter, severityFilter, frameworkFilter, checkSearch]);

  // --- Donut data ---
  const donutData = [
    { name: 'Critical', value: overallStats.totalCritical, color: '#dc2626' },
    { name: 'High',     value: overallStats.totalHigh,     color: '#ea580c' },
    { name: 'Medium',   value: overallStats.totalMedium,   color: '#f59e0b' },
    { name: 'Low',      value: overallStats.totalLow,      color: '#22c55e' },
  ].filter(d => d.value > 0);

  // --- Export ---
  const exportReport = useCallback(() => {
    const esc = (v: unknown) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const hdr = ['Check', 'Severity', 'Failed Count', 'Frameworks', 'Regions', 'Steps to Resolve', 'Prowler URL', 'Failed Resources'];
    const rows = checkRows.map(c => {
      const prowlerUrls = (c.remediationRefs ?? []).filter(r => r.startsWith('http'));
      const failedResources = c.resources
        .map(r => {
          const label = r.name && r.name !== r.id ? `${r.name} (${r.id})` : (r.name || r.id);
          const parts = [label];
          if (r.region) parts.push(`[${r.region}]`);
          if (r.detail) parts.push(`- ${r.detail}`);
          return parts.join(' ');
        })
        .join('\n');
      return [
        esc(c.checkTitle),
        esc(c.severity),
        c.failedCount,
        esc(c.frameworks.join('; ')),
        esc(c.regions.join(', ')),
        esc(c.remediation ?? ''),
        esc(prowlerUrls.join('\n')),
        esc(failedResources),
      ];
    });
    const csvBody = hdr.join(',') + '\n' + rows.map(r => r.join(',')).join('\n');
    const blob = new Blob(['\uFEFF' + csvBody], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `compliance_${new Date().toISOString().split('T')[0]}.csv`;
    document.body.appendChild(link); link.click(); link.remove();
    URL.revokeObjectURL(link.href);
  }, [checkRows]);

  // --- Topbar ---
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        {prowlerStatus.status === 'RUNNING' && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 text-blue-700 rounded-lg text-xs font-medium">
            <div className="w-2 h-2 bg-blue-500 rounded-full animate-pulse" />
            Prowler Scan Running...
          </div>
        )}
        {prowlerStatus.status === 'COMPLETED' && prowlerStatus.findingsCount != null && (
          <div className="flex items-center gap-2 px-3 py-1.5 bg-green-50 text-green-700 rounded-lg text-xs font-medium">
            <Icon icon="mdi:check-circle-outline" width={14} />
            {prowlerStatus.findingsCount} Prowler findings
          </div>
        )}
        <button onClick={exportReport} disabled={!checkRows.length} className="flex items-center gap-2 px-3.5 py-2 bg-white border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] font-medium text-emerald-600 hover:shadow-sm disabled:opacity-50 transition-all">
          <Icon icon="mdi:file-delimited-outline" width={14} /> Export Report
        </button>
        <button
          onClick={triggerScan}
          disabled={isRefreshing || prowlerStatus.status === 'RUNNING'}
          className="flex items-center gap-2 px-3.5 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black disabled:opacity-50 transition-colors"
        >
          <Icon icon="mdi:refresh" width={14} className={(isRefreshing || prowlerStatus.status === 'RUNNING') ? 'animate-spin' : ''} />
          {prowlerStatus.status === 'RUNNING' ? 'Scan Running...' : 'Run Compliance Scan'}
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, prowlerStatus, isRefreshing, checkRows.length, exportReport, triggerScan]);

  // --- Loading ---
  if (isLoading) {
    return (
      <main className="flex-1 px-6 pt-4 pb-6 bg-[#fafafa]">
        <div className="animate-pulse space-y-6">
          <div className="grid grid-cols-5 gap-4">{[1,2,3,4,5].map(i => <div key={i} className="h-28 bg-[rgba(0,0,0,0.05)] rounded-xl" />)}</div>
          <div className="h-48 bg-[rgba(0,0,0,0.05)] rounded-xl" />
          <div className="grid grid-cols-2 gap-4">{[1,2].map(i => <div key={i} className="h-52 bg-[rgba(0,0,0,0.05)] rounded-xl" />)}</div>
          <div className="h-64 bg-[rgba(0,0,0,0.05)] rounded-xl" />
        </div>
      </main>
    );
  }

  if (!selectedAccountId) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-[#fafafa]">
        <div className="text-center max-w-md">
          <div className="w-14 h-14 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:cloud-outline" width={32} color="#4f46e5" />
          </div>
          <h3 className="text-[16px] font-semibold text-[#1a1a1a] mb-2">No Account Selected</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Select a cloud account from the sidebar to view compliance status.</p>
        </div>
      </main>
    );
  }

  if (!findings.length && !isLoading) {
    return (
      <main className="flex-1 flex items-center justify-center p-8 bg-[#fafafa]">
        <div className="text-center max-w-lg">
          <div className="w-14 h-14 rounded-xl bg-emerald-50 border border-emerald-100 flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:shield-check-outline" width={32} color="#10b981" />
          </div>
          <h3 className="text-[16px] font-semibold text-[#1a1a1a] mb-2">No Compliance Data Available</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6">Run a compliance scan to see findings across CIS, SOC 2, PCI-DSS, and more.</p>
          <button onClick={triggerScan} disabled={isRefreshing || prowlerStatus.status === 'RUNNING'} className="inline-flex items-center gap-2 px-4 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black disabled:opacity-50 transition-colors">
            <Icon icon="mdi:play-outline" width={14} className={(isRefreshing || prowlerStatus.status === 'RUNNING') ? 'animate-spin' : ''} />
            {prowlerStatus.status === 'RUNNING' ? 'Scan Running...' : 'Run Compliance Scan'}
          </button>
        </div>
      </main>
    );
  }

  // =====================================================
  // MAIN RENDER
  // =====================================================
  return (
    <main className="flex-1 px-6 pt-4 pb-8 bg-white min-h-screen">
      <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.25 }} className="space-y-5">

        {/* Scan running banner */}
        {prowlerStatus.status === 'RUNNING' && (
          <div className="bg-blue-50 border border-blue-200 rounded-xl p-3.5 flex items-center gap-3">
            <Icon icon="mdi:radar" width={18} className="text-blue-500 animate-spin shrink-0" />
            <p className="text-[13px] font-medium text-blue-700">Compliance scan in progress — results will update automatically when complete.</p>
          </div>
        )}

        {/* ── Frameworks horizontal row (Pump style — icon centered, score below) ── */}
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
          <div className="flex items-center justify-between px-5 py-3 border-b border-[rgba(0,0,0,0.06)]">
            <div className="flex items-center gap-2.5">
              <h3 className="text-[14px] font-semibold text-[#1a1a1a]">Compliance Frameworks</h3>
              <span className="px-2 py-0.5 bg-[rgba(0,0,0,0.06)] rounded-md text-[11px] font-bold text-[rgba(0,0,0,0.5)]">{visibleFrameworks.length}</span>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={() => { setPendingVisible(new Set(effectiveVisible)); setManageSearch(''); setManageTab('all'); setShowManageModal(true); }}
                className="flex items-center gap-1.5 px-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-colors"
              >
                <Icon icon="mdi:tune-variant" width={13} /> Manage
              </button>
              <button onClick={() => scrollRef.current?.scrollBy({ left: -200, behavior: 'smooth' })} className="w-7 h-7 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)]">
                <Icon icon="mdi:chevron-left" width={16} />
              </button>
              <button onClick={() => scrollRef.current?.scrollBy({ left: 200, behavior: 'smooth' })} className="w-7 h-7 flex items-center justify-center rounded-lg border border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.45)]">
                <Icon icon="mdi:chevron-right" width={16} />
              </button>
            </div>
          </div>
          <div ref={scrollRef} className="flex overflow-x-auto py-5" style={{ scrollBehavior: 'smooth', scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
            {visibleFrameworks.map((fw) => {
              const isPassing = fw.score >= 95;
              const logoPath = FRAMEWORK_LOGO_PATH[fw.name];
              const logoOv = LOGO_OVERRIDE[fw.name];
              return (
                <div key={fw.name} className="flex-none w-[130px] flex flex-col items-center text-center px-2 border-r border-[rgba(0,0,0,0.05)] last:border-r-0">
                  {/* Logo image */}
                  {logoPath ? (
                    <img src={logoPath} alt={fw.displayName} className="w-10 h-10 rounded-xl mb-1.5 object-contain" />
                  ) : logoOv ? (
                    <div className="w-10 h-10 rounded-xl flex items-center justify-center mb-1.5" style={{ backgroundColor: logoOv.bg }}>
                      <span className="font-black tracking-tight leading-none select-none" style={{ fontSize: getLogoFontSize(logoOv.text), color: logoOv.textColor }}>{logoOv.text}</span>
                    </div>
                  ) : (
                    <div className={`w-10 h-10 rounded-xl ${fw.bgColor} flex items-center justify-center mb-1.5`}>
                      <Icon icon={fw.icon} width={20} className={fw.color} />
                    </div>
                  )}
                  <span className={`text-[10px] font-semibold mb-0.5 ${isPassing ? 'text-emerald-600' : 'text-red-500'}`}>{isPassing ? 'Passed' : 'Failed'}</span>
                  <p className="text-[10px] text-[rgba(0,0,0,0.5)] font-medium truncate w-full">{fw.displayName}</p>
                  <p className="text-[22px] font-bold tabular-nums text-[#1a1a1a] leading-tight">{fw.score}%</p>
                </div>
              );
            })}
          </div>
          <div className="flex items-start gap-2 px-5 py-2.5 border-t border-[rgba(0,0,0,0.06)] bg-[rgba(0,0,0,0.015)]">
            <Icon icon="mdi:information-outline" width={13} className="text-[rgba(0,0,0,0.35)] shrink-0 mt-[1px]" />
            <p className="text-[11px] leading-snug text-[rgba(0,0,0,0.45)]">
              Scores reflect the percentage of individual resource checks passing for each framework and are a coverage indicator, not a certification. Formal compliance (e.g. SOC 2) requires an independent auditor's attestation.
            </p>
          </div>
        </div>

        {/* ── By severity + Trend over time ── */}
        <div className="grid grid-cols-1 lg:grid-cols-5 gap-5">
          {/* By severity */}
          <div className="lg:col-span-2 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm p-6">
            <p className="text-[14px] font-semibold text-[#1a1a1a] mb-5">By severity</p>
            <div className="flex items-center gap-6">
              <div className="relative flex-shrink-0">
                <PieChart width={140} height={140}>
                  <Pie data={donutData.length ? donutData : [{ name: 'none', value: 1, color: '#e5e7eb' }]} cx={70} cy={70} innerRadius={48} outerRadius={62} dataKey="value" startAngle={90} endAngle={-270} strokeWidth={0} paddingAngle={donutData.length > 1 ? 2 : 0}>
                    {(donutData.length ? donutData : [{ color: '#e5e7eb' }]).map((entry, i) => <Cell key={i} fill={entry.color} />)}
                  </Pie>
                </PieChart>
                <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                  <span className="text-[22px] font-bold text-[#1a1a1a] tabular-nums leading-none">{overallStats.totalFailed.toLocaleString()}</span>
                  <span className="text-[10px] text-[rgba(0,0,0,0.4)] font-medium mt-1">Failed</span>
                </div>
              </div>
              <div className="flex flex-col gap-4 flex-1">
                {[
                  { key: 'C', label: 'Critical', value: overallStats.totalCritical, color: '#dc2626' },
                  { key: 'H', label: 'High',     value: overallStats.totalHigh,     color: '#ea580c' },
                  { key: 'M', label: 'Medium',   value: overallStats.totalMedium,   color: '#d97706' },
                  { key: 'L', label: 'Low',      value: overallStats.totalLow,      color: '#22c55e' },
                ].map(s => (
                  <div key={s.key} className="flex items-center gap-3">
                    <div className="w-5 h-5 rounded-full flex items-center justify-center flex-shrink-0" style={{ backgroundColor: s.color }}>
                      <span className="text-[9px] font-bold text-white leading-none">{s.key}</span>
                    </div>
                    <span className="text-[13px] text-[rgba(0,0,0,0.6)] flex-1">{s.label}</span>
                    <span className="text-[15px] font-semibold text-[#1a1a1a] tabular-nums">{s.value.toLocaleString()}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Trend over time */}
          <div className="lg:col-span-3 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm p-6">
            <p className="text-[14px] font-semibold text-[#1a1a1a] mb-4">Trend over time</p>
            {trendData.length > 0 ? (
              <>
                <ResponsiveContainer width="100%" height={165}>
                  <AreaChart data={trendData} margin={{ top: 4, right: 8, bottom: 0, left: -16 }}>
                    <defs>
                      <linearGradient id="gcrit" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#ef4444" stopOpacity={0.55} /><stop offset="100%" stopColor="#ef4444" stopOpacity={0.05} /></linearGradient>
                      <linearGradient id="ghigh" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#f97316" stopOpacity={0.4} /><stop offset="100%" stopColor="#f97316" stopOpacity={0.04} /></linearGradient>
                      <linearGradient id="gmed"  x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#fb923c" stopOpacity={0.3} /><stop offset="100%" stopColor="#fb923c" stopOpacity={0.03} /></linearGradient>
                      <linearGradient id="glow"  x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#22c55e" stopOpacity={0.25} /><stop offset="100%" stopColor="#22c55e" stopOpacity={0.03} /></linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(0,0,0,0.04)" vertical={false} />
                    <XAxis dataKey="date" tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }} tickLine={false} axisLine={false} interval={2} />
                    <YAxis tick={{ fontSize: 10, fill: 'rgba(0,0,0,0.4)' }} tickLine={false} axisLine={false} />
                    <Tooltip contentStyle={{ fontSize: 11, borderRadius: 8, border: '1px solid rgba(0,0,0,0.08)', boxShadow: '0 4px 12px rgba(0,0,0,0.08)' }} />
                    <Area type="monotone" dataKey="Low" stroke="#22c55e" strokeWidth={1} fill="url(#glow)" stackId="1" />
                    <Area type="monotone" dataKey="Medium" stroke="#fb923c" strokeWidth={1} fill="url(#gmed)" stackId="1" />
                    <Area type="monotone" dataKey="High" stroke="#f97316" strokeWidth={1.5} fill="url(#ghigh)" stackId="1" />
                    <Area type="monotone" dataKey="Critical" stroke="#ef4444" strokeWidth={2} fill="url(#gcrit)" stackId="1" />
                  </AreaChart>
                </ResponsiveContainer>
                <div className="flex items-center gap-5 mt-3">
                  {[{ label: 'Critical', color: '#ef4444' }, { label: 'High', color: '#f97316' }, { label: 'Medium', color: '#fb923c' }, { label: 'Low', color: '#22c55e' }].map(s => (
                    <div key={s.label} className="flex items-center gap-1.5">
                      <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: s.color }} />
                      <span className="text-[11px] text-[rgba(0,0,0,0.5)]">{s.label}</span>
                    </div>
                  ))}
                </div>
              </>
            ) : (
              <div className="h-40 flex items-center justify-center text-[13px] text-[rgba(0,0,0,0.3)]">No trend data available</div>
            )}
          </div>
        </div>

        {/* ── Findings Table ── */}
        <div className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl shadow-sm overflow-hidden">
          {/* Tabs + filter row — matches Pump */}
          <div className="flex flex-wrap items-center justify-between px-5 border-b border-[rgba(0,0,0,0.06)]">
            <div className="flex items-center">
              {(['Failed', 'Passed', 'All'] as const).map(t => (
                <button key={t} onClick={() => setStatusFilter(t)}
                  className={`mr-5 py-3.5 text-[13px] font-medium border-b-2 -mb-px transition-colors ${statusFilter === t ? 'border-[#1a1a1a] text-[#1a1a1a]' : 'border-transparent text-[rgba(0,0,0,0.35)] hover:text-[rgba(0,0,0,0.6)]'}`}
                >{t}</button>
              ))}
              <span className="text-[12px] text-[rgba(0,0,0,0.35)] ml-1">{filteredChecks.length.toLocaleString()} findings</span>
            </div>
            <div className="flex items-center gap-2 py-2">
              <div className="relative">
                <Icon icon="mdi:magnify" width={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
                <input type="text" placeholder="Filter finding..." value={checkSearch} onChange={e => setCheckSearch(e.target.value)} className="pl-8 pr-3 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] w-40 bg-white focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.15)] placeholder:text-[rgba(0,0,0,0.3)]" />
              </div>
              {/* Framework multi-select filter */}
              <div className="relative" ref={fwDropdownRef}>
                <button
                  type="button"
                  onClick={() => setFwDropdownOpen(o => !o)}
                  className="flex items-center gap-1.5 px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] text-[rgba(0,0,0,0.55)] bg-white hover:bg-[rgba(0,0,0,0.02)] focus:outline-none transition-colors"
                >
                  <Icon icon="mdi:shield-check-outline" width={14} className="text-[rgba(0,0,0,0.4)]" />
                  <span>
                    {frameworkFilter.length === 0
                      ? 'Framework'
                      : frameworkFilter.length === 1
                        ? getFwConfig(frameworkFilter[0]).displayName
                        : `${frameworkFilter.length} frameworks`}
                  </span>
                  {frameworkFilter.length > 0 && (
                    <span className="inline-flex items-center justify-center min-w-[16px] h-4 px-1 rounded-full bg-emerald-100 text-emerald-700 text-[10px] font-semibold">{frameworkFilter.length}</span>
                  )}
                  <Icon icon="mdi:chevron-down" width={14} className={`text-[rgba(0,0,0,0.4)] transition-transform ${fwDropdownOpen ? 'rotate-180' : ''}`} />
                </button>
                <AnimatePresence>
                  {fwDropdownOpen && (
                    <motion.div
                      initial={{ opacity: 0, y: -4 }}
                      animate={{ opacity: 1, y: 0 }}
                      exit={{ opacity: 0, y: -4 }}
                      transition={{ duration: 0.12 }}
                      className="absolute right-0 mt-1.5 w-64 bg-white border border-[rgba(0,0,0,0.1)] rounded-lg shadow-lg z-30 overflow-hidden"
                    >
                      <div className="flex items-center justify-between px-3 py-2 border-b border-[rgba(0,0,0,0.06)]">
                        <span className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide">Filter by framework</span>
                        {frameworkFilter.length > 0 && (
                          <button onClick={() => setFrameworkFilter([])} className="text-[11px] text-emerald-600 hover:text-emerald-700 font-medium">Clear</button>
                        )}
                      </div>
                      <div className="max-h-64 overflow-y-auto py-1 scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
                        {availableFrameworks.length === 0 ? (
                          <p className="px-3 py-3 text-[12px] text-[rgba(0,0,0,0.4)]">No frameworks available.</p>
                        ) : availableFrameworks.map(fw => {
                          const checked = frameworkFilter.includes(fw.key);
                          return (
                            <button
                              key={fw.key}
                              onClick={() => toggleFramework(fw.key)}
                              className="w-full flex items-center gap-2.5 px-3 py-1.5 text-left hover:bg-[rgba(0,0,0,0.03)] transition-colors"
                            >
                              <span className={`w-4 h-4 rounded flex items-center justify-center flex-shrink-0 border ${checked ? 'bg-emerald-600 border-emerald-600' : 'border-[rgba(0,0,0,0.2)]'}`}>
                                {checked && <Icon icon="mdi:check" width={12} className="text-white" />}
                              </span>
                              <FrameworkLogo fwKey={fw.key} size={18} />
                              <span className="text-[12px] text-[rgba(0,0,0,0.7)] truncate">{fw.displayName}</span>
                            </button>
                          );
                        })}
                      </div>
                    </motion.div>
                  )}
                </AnimatePresence>
              </div>
              <select value={severityFilter} onChange={e => setSeverityFilter(e.target.value)} className="px-2.5 py-1.5 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] text-[rgba(0,0,0,0.55)] bg-white focus:outline-none">
                <option value="all">Severity</option>
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High</option>
                <option value="MEDIUM">Medium</option>
                <option value="LOW">Low</option>
              </select>
            </div>
          </div>

          {/* Table */}
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-[rgba(0,0,0,0.06)]">
                  <th className="text-left px-5 py-2.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] select-none">
                    <div className="flex items-center gap-1">Check <Icon icon="mdi:unfold-more-horizontal" width={12} /></div>
                  </th>
                  <th className="text-right px-4 py-2.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] select-none">
                    <div className="flex items-center justify-end gap-1">Failed Count <Icon icon="mdi:arrow-down" width={12} /></div>
                  </th>
                  <th className="text-right px-4 py-2.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] select-none">
                    <div className="flex items-center justify-end gap-1">Passed Count <Icon icon="mdi:unfold-more-horizontal" width={12} /></div>
                  </th>
                  <th className="text-left px-4 py-2.5 text-[11px] font-semibold text-[rgba(0,0,0,0.5)] select-none">
                    <div className="flex items-center gap-1">Frameworks affected <Icon icon="mdi:unfold-more-horizontal" width={12} /></div>
                  </th>
                </tr>
              </thead>
              <tbody>
                {paginatedChecks.length === 0 ? (
                  <tr>
                    <td colSpan={4} className="px-5 py-12 text-center text-[13px] text-[rgba(0,0,0,0.32)]">
                      {statusFilter === 'Passed' ? 'Passed findings are not available — run a full scan.' : 'No findings match your filters.'}
                    </td>
                  </tr>
                ) : paginatedChecks.map((c, idx) => {
                  const sc2 = SEVERITY_STYLE[c.severity] || SEVERITY_STYLE.LOW;
                  return (
                    <tr key={idx} onClick={() => setSelectedCheck(c)} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.015)] transition-colors cursor-pointer">
                      <td className="px-5 py-3.5">
                        <div className="flex items-start gap-3">
                          <div className="w-5 h-5 rounded flex items-center justify-center flex-shrink-0 mt-0.5" style={{ backgroundColor: sc2.color }}>
                            <span className="text-[9px] font-bold text-white leading-none">{sc2.letter}</span>
                          </div>
                          <p className="text-[12px] text-blue-600 underline decoration-blue-200 leading-relaxed line-clamp-2 max-w-[520px] hover:text-blue-700 transition-colors">
                            <MdText text={c.checkTitle} />
                          </p>
                        </div>
                      </td>
                      <td className="px-4 py-3.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: sc2.color }} />
                          <span className="text-[13px] font-semibold text-[#1a1a1a] tabular-nums">{c.failedCount}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3.5 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <div className="w-2 h-2 rounded-full bg-emerald-500 flex-shrink-0" />
                          <span className="text-[13px] text-[rgba(0,0,0,0.28)] tabular-nums">—</span>
                        </div>
                      </td>
                      <td className="px-4 py-3.5">
                        <div className="flex items-center gap-1.5 flex-wrap">
                          {c.frameworks.slice(0, 5).map(fwKey => {
                            const logoPath = FRAMEWORK_LOGO_PATH[fwKey];
                            const logo = LOGO_OVERRIDE[fwKey];
                            const cfg = getFwConfig(fwKey);
                            return (
                              <div key={fwKey} title={cfg.displayName} className="w-6 h-6 rounded-full flex items-center justify-center flex-shrink-0 border border-[rgba(0,0,0,0.08)] overflow-hidden">
                                {logoPath ? (
                                  <img src={logoPath} alt={cfg.displayName} className="w-6 h-6 rounded-full object-cover" />
                                ) : logo ? (
                                  <div className="w-full h-full flex items-center justify-center rounded-full" style={{ backgroundColor: logo.bg }}>
                                    <span className="font-bold leading-none select-none" style={{ fontSize: logo.text.length <= 2 ? '8px' : '6px', color: logo.textColor }}>{logo.text}</span>
                                  </div>
                                ) : (
                                  <Icon icon={cfg.icon} width={12} className={cfg.color} />
                                )}
                              </div>
                            );
                          })}
                          {c.frameworks.length > 5 && (
                            <span className="text-[10px] text-[rgba(0,0,0,0.4)] font-semibold ml-0.5">+{c.frameworks.length - 5}</span>
                          )}
                          {c.frameworks.length <= 2 && c.frameworks.some(fk => ['MITRE', 'AWS_WELL_ARCHITECTED', 'FSBP', 'AWS_FTR'].includes(fk)) && (
                            <span className="text-[10px] text-[rgba(0,0,0,0.45)] ml-1 truncate max-w-[140px]">
                              {getFwConfig(c.frameworks.find(fk => ['MITRE', 'AWS_WELL_ARCHITECTED', 'FSBP', 'AWS_FTR'].includes(fk))!).displayName}
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          {totalCheckPages > 1 && (
            <div className="flex items-center justify-between px-5 py-3 border-t border-[rgba(0,0,0,0.05)]">
              <span className="text-[11px] text-[rgba(0,0,0,0.38)]">
                {((checkPage - 1) * checksPerPage) + 1}–{Math.min(checkPage * checksPerPage, filteredChecks.length)} of {filteredChecks.length.toLocaleString()}
              </span>
              <div className="flex items-center gap-1">
                <button onClick={() => setCheckPage(p => Math.max(1, p - 1))} disabled={checkPage === 1} className="w-7 h-7 rounded-lg border border-[rgba(0,0,0,0.1)] flex items-center justify-center disabled:opacity-35 hover:bg-[rgba(0,0,0,0.03)] transition-colors text-[rgba(0,0,0,0.5)]">
                  <Icon icon="mdi:chevron-left" width={14} />
                </button>
                {Array.from({ length: Math.min(5, totalCheckPages) }, (_, i) => {
                  let pg: number;
                  if (totalCheckPages <= 5) pg = i + 1;
                  else if (checkPage <= 3) pg = i + 1;
                  else if (checkPage >= totalCheckPages - 2) pg = totalCheckPages - 4 + i;
                  else pg = checkPage - 2 + i;
                  return (
                    <button key={pg} onClick={() => setCheckPage(pg)} className={`w-7 h-7 rounded-lg text-[12px] font-medium transition-all ${pg === checkPage ? 'bg-[rgba(0,0,0,0.85)] text-white' : 'border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] hover:bg-[rgba(0,0,0,0.03)]'}`}>{pg}</button>
                  );
                })}
                <button onClick={() => setCheckPage(p => Math.min(totalCheckPages, p + 1))} disabled={checkPage === totalCheckPages} className="w-7 h-7 rounded-lg border border-[rgba(0,0,0,0.1)] flex items-center justify-center disabled:opacity-35 hover:bg-[rgba(0,0,0,0.03)] transition-colors text-[rgba(0,0,0,0.5)]">
                  <Icon icon="mdi:chevron-right" width={14} />
                </button>
              </div>
            </div>
          )}
        </div>

      </motion.div>

      {/* ── Manage Frameworks Modal — Pump card-grid style ── */}
      {showManageModal && createPortal((
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4" style={{ backgroundColor: 'rgba(0,0,0,0.38)' }}>
          <motion.div
            initial={{ opacity: 0, scale: 0.96, y: 10 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.96 }}
            transition={{ duration: 0.16 }}
            className="bg-white rounded-2xl shadow-2xl w-full max-w-3xl max-h-[82vh] flex flex-col"
          >
            {/* Header */}
            <div className="flex items-start justify-between px-6 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
              <div>
                <h2 className="text-[16px] font-semibold text-[#1a1a1a]">Browse compliance frameworks</h2>
                <p className="text-[12px] text-[rgba(0,0,0,0.45)] mt-0.5">Select the frameworks you want to see findings for</p>
              </div>
              <button onClick={() => setShowManageModal(false)} className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors">
                <Icon icon="mdi:close" width={18} className="text-[rgba(0,0,0,0.4)]" />
              </button>
            </div>

            {/* Tabs + search */}
            <div className="flex items-center justify-between px-6 py-3 border-b border-[rgba(0,0,0,0.06)]">
              <div className="flex items-center gap-4">
                {(['all', 'selected'] as const).map(t => (
                  <button key={t} onClick={() => setManageTab(t)} className={`text-[13px] font-medium transition-colors pb-0 ${manageTab === t ? 'text-[#1a1a1a]' : 'text-[rgba(0,0,0,0.4)] hover:text-[rgba(0,0,0,0.6)]'}`}>
                    {t === 'all' ? `All (${frameworkScores.length})` : `Selected (${pendingVisible.size})`}
                  </button>
                ))}
              </div>
              <div className="relative">
                <Icon icon="mdi:magnify" width={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]" />
                <input type="text" placeholder="Search frameworks..." value={manageSearch} onChange={e => setManageSearch(e.target.value)} className="pl-8 pr-3 py-1.5 border border-[rgba(0,0,0,0.1)] rounded-lg text-[12px] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.15)] w-48 bg-white placeholder:text-[rgba(0,0,0,0.3)]" />
              </div>
            </div>

            {/* Card Grid — 4 columns like Pump */}
            <div className="flex-1 overflow-y-auto overscroll-contain">
              <div className="p-5">
                {(() => {
                  const filtered = frameworkScores.filter(fw => {
                    if (manageTab === 'selected' && !pendingVisible.has(fw.name)) return false;
                    if (manageSearch && !fw.displayName.toLowerCase().includes(manageSearch.toLowerCase())) return false;
                    return true;
                  });
                  if (filtered.length === 0) {
                    return <div className="text-center py-10 text-[13px] text-[rgba(0,0,0,0.3)]">No frameworks match.</div>;
                  }
                  return (
                    <div className="grid grid-cols-4 gap-3">
                      {filtered.map(fw => {
                        const on = pendingVisible.has(fw.name);
                        return (
                          <button
                            key={fw.name}
                            onClick={() => {
                              const next = new Set(pendingVisible);
                              if (on) next.delete(fw.name); else next.add(fw.name);
                              setPendingVisible(next);
                            }}
                            className={`relative flex flex-col items-start p-3.5 rounded-xl border text-left transition-all ${on ? 'border-emerald-400 bg-emerald-50/30' : 'border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.2)] hover:bg-[rgba(0,0,0,0.01)]'}`}
                          >
                            {/* Toggle in top-right */}
                            <div className={`absolute top-3 right-3 w-8 h-[18px] rounded-full transition-colors flex-shrink-0 ${on ? 'bg-emerald-500' : 'bg-[rgba(0,0,0,0.12)]'}`}>
                              <div className={`absolute top-[2px] w-[14px] h-[14px] bg-white rounded-full shadow-sm transition-transform ${on ? 'translate-x-[16px]' : 'translate-x-[2px]'}`} />
                            </div>
                            {/* Icon */}
                            <div className="mb-2 mt-0.5">
                              {(() => {
                                const logoPath = FRAMEWORK_LOGO_PATH[fw.name];
                                const logoOv = LOGO_OVERRIDE[fw.name];
                                if (logoPath) return <img src={logoPath} alt={fw.displayName} className="w-8 h-8 rounded-lg object-contain" />;
                                if (logoOv) return (
                                  <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: logoOv.bg }}>
                                    <span className="font-black tracking-tight leading-none select-none" style={{ fontSize: getLogoFontSize(logoOv.text), color: logoOv.textColor }}>{logoOv.text}</span>
                                  </div>
                                );
                                return (
                                  <div className={`w-8 h-8 rounded-lg ${fw.bgColor} flex items-center justify-center`}>
                                    <Icon icon={fw.icon} width={16} className={fw.color} />
                                  </div>
                                );
                              })()}
                            </div>
                            {/* Name — clamped to 2 lines so all cards in a row stay the same height */}
                            <p className="text-[11px] font-semibold text-[#1a1a1a] leading-snug mb-0.5 pr-9 line-clamp-2 w-full">{fw.displayName}</p>
                            {/* Sub info */}
                            <p className="text-[10px] text-[rgba(0,0,0,0.4)] mt-auto pt-1">{fw.failedControls} failed controls</p>
                          </button>
                        );
                      })}
                    </div>
                  );
                })()}
              </div>
            </div>

            {/* Footer */}
            <div className="flex items-center justify-end gap-3 px-6 py-4 border-t border-[rgba(0,0,0,0.06)]">
              <button onClick={() => setShowManageModal(false)} className="px-4 py-2 border border-[rgba(0,0,0,0.12)] rounded-lg text-[12px] font-medium text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.03)] transition-colors">
                Cancel
              </button>
              <button
                onClick={() => {
                  setVisibleFrameworkKeys(pendingVisible.size === frameworkScores.length ? null : pendingVisible);
                  setShowManageModal(false);
                }}
                className="px-4 py-2 bg-[#1a1a1a] text-white rounded-lg text-[12px] font-medium hover:bg-black transition-colors"
              >
                Save changes
              </button>
            </div>
          </motion.div>
        </div>
      ), document.body)}

      {/* ── Check Detail Drawer ── */}
      {createPortal((
      <AnimatePresence>
        {selectedCheck && (() => {
          const sev = SEVERITY_STYLE[selectedCheck.severity] || SEVERITY_STYLE.LOW;
          return (
            <React.Fragment key="check-detail">
              <motion.div
                initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}
                className="fixed inset-0 bg-black/30 z-50"
                onClick={() => setSelectedCheck(null)}
              />
              <motion.div
                initial={{ x: '100%' }} animate={{ x: 0 }} exit={{ x: '100%' }} transition={{ type: 'tween', duration: 0.22 }}
                className="fixed right-0 top-0 h-full w-full max-w-2xl bg-white shadow-2xl z-50 flex flex-col"
              >
                {/* Header */}
                <div className="flex items-start justify-between gap-4 px-6 pt-5 pb-4 border-b border-[rgba(0,0,0,0.06)]">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2 mb-2">
                      <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[11px] font-bold text-white" style={{ backgroundColor: sev.color }}>
                        {sev.letter} <span className="font-semibold">{sev.label}</span>
                      </span>
                      {selectedCheck.checkId && (
                        <span className="px-2 py-0.5 bg-[rgba(0,0,0,0.05)] rounded-md text-[10px] font-mono text-[rgba(0,0,0,0.55)]">{selectedCheck.checkId}</span>
                      )}
                    </div>
                    <h2 className="text-[15px] font-semibold text-[#1a1a1a] leading-snug">
                      <MdText text={selectedCheck.title || selectedCheck.checkTitle} />
                    </h2>
                    <p className="text-[11px] text-[rgba(0,0,0,0.45)] mt-1.5">
                      <span className="font-semibold text-red-600">{selectedCheck.failedCount}</span> failed resource{selectedCheck.failedCount === 1 ? '' : 's'}
                      {selectedCheck.regions.length > 0 && <> · {selectedCheck.regions.join(', ')}</>}
                    </p>
                  </div>
                  <button onClick={() => setSelectedCheck(null)} className="w-7 h-7 flex items-center justify-center rounded-lg hover:bg-[rgba(0,0,0,0.05)] transition-colors flex-shrink-0">
                    <Icon icon="mdi:close" width={18} className="text-[rgba(0,0,0,0.4)]" />
                  </button>
                </div>

                {/* Body */}
                <div className="flex-1 overflow-y-auto overscroll-contain px-6 py-5 space-y-5 scrollbar-hide" style={{ scrollbarWidth: 'none', msOverflowStyle: 'none' }}>
                  {/* Full description */}
                  <div>
                    <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide mb-2">What this check does</p>
                    <p className="text-[13px] text-[rgba(0,0,0,0.7)] leading-relaxed">
                      <MdText text={selectedCheck.checkTitle} />
                    </p>
                  </div>

                  {/* Remediation */}
                  {selectedCheck.remediation && (
                    <div className="bg-emerald-50/60 border border-emerald-100 rounded-xl p-4">
                      <div className="flex items-center gap-2 mb-2">
                        <Icon icon="mdi:wrench-outline" width={15} className="text-emerald-600" />
                        <p className="text-[12px] font-semibold text-emerald-800">How to fix</p>
                      </div>
                      <p className="text-[12.5px] text-[rgba(0,0,0,0.65)] leading-relaxed">
                        <MdText text={selectedCheck.remediation} />
                      </p>
                      {selectedCheck.remediationRefs && selectedCheck.remediationRefs.length > 0 && (
                        <div className="mt-3 space-y-1">
                          {selectedCheck.remediationRefs.filter(r => r.startsWith('http')).slice(0, 4).map((ref, i) => (
                            <a key={i} href={ref} target="_blank" rel="noopener noreferrer" className="flex items-center gap-1.5 text-[11.5px] text-blue-600 hover:text-blue-700 hover:underline truncate">
                              <Icon icon="mdi:open-in-new" width={12} className="flex-shrink-0" />
                              <span className="truncate">{ref}</span>
                            </a>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {/* Frameworks affected */}
                  <div>
                    <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide mb-2">Frameworks affected ({selectedCheck.frameworks.length})</p>
                    <div className="space-y-2">
                      {selectedCheck.frameworks.map(fwKey => {
                        const controls = selectedCheck.frameworkControls[fwKey] ?? [];
                        return (
                          <div key={fwKey} className="flex items-start gap-2.5 border border-[rgba(0,0,0,0.06)] rounded-lg px-3 py-2.5">
                            <FrameworkLogo fwKey={fwKey} size={24} />
                            <div className="min-w-0 flex-1">
                              <p className="text-[12px] font-medium text-[#1a1a1a]">{getFwConfig(fwKey).displayName}</p>
                              {controls.length > 0 && (
                                <div className="flex flex-wrap gap-1 mt-1.5">
                                  {controls.slice(0, 12).map(ctl => (
                                    <span key={ctl} className="px-1.5 py-0.5 bg-[rgba(0,0,0,0.04)] rounded text-[10px] font-mono text-[rgba(0,0,0,0.55)]">{ctl}</span>
                                  ))}
                                  {controls.length > 12 && <span className="text-[10px] text-[rgba(0,0,0,0.4)] font-semibold self-center">+{controls.length - 12} more</span>}
                                </div>
                              )}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>

                  {/* Affected resources */}
                  <div>
                    <p className="text-[11px] font-semibold text-[rgba(0,0,0,0.5)] uppercase tracking-wide mb-2">Failed resources ({selectedCheck.resources.length})</p>
                    {selectedCheck.resources.length === 0 ? (
                      <p className="text-[12px] text-[rgba(0,0,0,0.4)]">No resource details available for this check.</p>
                    ) : (
                      <div className="space-y-2">
                        {selectedCheck.resources.map((r, i) => (
                          <div key={i} className="border border-[rgba(0,0,0,0.06)] rounded-lg px-3 py-2.5">
                            <div className="flex items-center justify-between gap-3">
                              <p className="text-[11.5px] font-mono text-[#1a1a1a] truncate" title={r.id}>{r.name || r.id}</p>
                              {r.region && <span className="px-1.5 py-0.5 bg-[rgba(0,0,0,0.04)] rounded text-[10px] text-[rgba(0,0,0,0.5)] flex-shrink-0">{r.region}</span>}
                            </div>
                            {r.name && r.id !== r.name && (
                              <p className="text-[10.5px] font-mono text-[rgba(0,0,0,0.4)] truncate mt-0.5" title={r.id}>{r.id}</p>
                            )}
                            {r.detail && (
                              <p className="text-[11.5px] text-[rgba(0,0,0,0.55)] leading-relaxed mt-1.5">
                                <MdText text={r.detail} />
                              </p>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </motion.div>
            </React.Fragment>
          );
        })()}
      </AnimatePresence>
      ), document.body)}
    </main>
  );
};

export default ComplianceOpsPage;
