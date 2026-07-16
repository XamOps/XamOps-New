import { useState, useEffect, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';
import type {
  XamSecureSummary, ThreatDetection, VulnerabilityFinding, PostureFinding,
  ComplianceFramework, OnboardingAccount, PageResp, InventoryAsset, ApiEndpoint, SbomComponent,
  ThreatPolicy, Workflow,
  IssueSummary, IssueDetail, ThreatStoriesSummary, InventoryCategory, NetworkTopology,
  AttackSurfaceSummary, VulnerabilityDashboard, CveGroupRow, CveResourceFinding,
  ConfigurationsDashboard,
} from '../types/xamsecure';

const CACHE_DURATION = 30 * 60 * 1000; // 30 min for scan-based tabs
const THREAT_POLL_MS = 60 * 1000;      // runtime feed

// Bump this version whenever a cached response's shape changes, so stale
// localStorage entries miss and refetch instead of masking new fields.
const CACHE_VERSION = 'v3';

export function xamSecureCacheKey(section: string, accountId: string | null): string {
  return `xamsecure_${CACHE_VERSION}_${section}_${accountId ?? 'all'}`;
}

function loadCache<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    const expiry = localStorage.getItem(`${key}_expiry`);
    if (raw && expiry && Date.now() <= parseInt(expiry)) return JSON.parse(raw) as T;
  } catch { /* ignore */ }
  return null;
}

function saveCache(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    localStorage.setItem(`${key}_expiry`, (Date.now() + CACHE_DURATION).toString());
  } catch { /* ignore */ }
}

interface UseXamSecureDataParams {
  selectedAccountId: string | null;
  activeTab: string;
}

export const useXamSecureData = ({ selectedAccountId, activeTab }: UseXamSecureDataParams) => {
  const [summary, setSummary] = useState<XamSecureSummary | null>(null);
  const [issues, setIssues] = useState<IssueSummary[] | null>(null);
  const [attackSurface, setAttackSurface] = useState<AttackSurfaceSummary | null>(null);
  const [threats, setThreats] = useState<PageResp<ThreatDetection> | null>(null);
  const [vulnerabilities, setVulnerabilities] = useState<PageResp<VulnerabilityFinding> | null>(null);
  const [vulnDashboard, setVulnDashboard] = useState<VulnerabilityDashboard | null>(null);
  const [vulnByCve, setVulnByCve] = useState<PageResp<CveGroupRow> | null>(null);
  const [posture, setPosture] = useState<PageResp<PostureFinding> | null>(null);
  const [inventory, setInventory] = useState<PageResp<InventoryAsset> | null>(null);
  const [inventoryCategories, setInventoryCategories] = useState<InventoryCategory[] | null>(null);
  const [networkTopology, setNetworkTopology] = useState<NetworkTopology | null>(null);
  const [compliance, setCompliance] = useState<ComplianceFramework[] | null>(null);
  const [configDashboard, setConfigDashboard] = useState<ConfigurationsDashboard | null>(null);
  const [apiSecurity, setApiSecurity] = useState<ApiEndpoint[] | null>(null);
  const [components, setComponents] = useState<SbomComponent[] | null>(null);
  const [threatPolicies, setThreatPolicies] = useState<ThreatPolicy[] | null>(null);
  const [workflows, setWorkflows] = useState<Workflow[] | null>(null);
  const [stories, setStories] = useState<ThreatStoriesSummary | null>(null);
  const [onboarding, setOnboarding] = useState<OnboardingAccount[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const pollRef = useRef<number | null>(null);
  const threatPageRef = useRef(0);

  const acct = selectedAccountId ? `accountId=${encodeURIComponent(selectedAccountId)}&` : '';

  const loadSummary = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('summary', selectedAccountId);
    if (!force) {
      const cached = loadCache<XamSecureSummary>(key);
      if (cached) { setSummary(cached); return; }
    }
    const data = await apiClient.get<XamSecureSummary>(`/api/xamsecure/summary?${acct}`);
    setSummary(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadIssues = useCallback(async (severity = '', force = false) => {
    const key = xamSecureCacheKey(`issues_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<IssueSummary[]>(key);
      if (cached) { setIssues(cached); return; }
    }
    const data = await apiClient.get<IssueSummary[]>(
      `/api/xamsecure/issues?${acct}${severity ? `severity=${severity}` : ''}`);
    setIssues(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadAttackSurface = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('attack_surface', selectedAccountId);
    if (!force) {
      const cached = loadCache<AttackSurfaceSummary>(key);
      if (cached) { setAttackSurface(cached); return; }
    }
    const data = await apiClient.get<AttackSurfaceSummary>(`/api/xamsecure/attack-surface?${acct}`);
    setAttackSurface(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadIssueDetail = useCallback(async (check: string): Promise<IssueDetail> => {
    return apiClient.get<IssueDetail>(
      `/api/xamsecure/issues/detail?${acct}check=${encodeURIComponent(check)}`);
  }, [acct]);

  const loadStories = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('stories', selectedAccountId);
    if (!force) {
      const cached = loadCache<ThreatStoriesSummary>(key);
      if (cached) { setStories(cached); return; }
    }
    const data = await apiClient.get<ThreatStoriesSummary>(`/api/xamsecure/stories?${acct}`);
    setStories(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadThreats = useCallback(async (page = 0, severity = '') => {
    threatPageRef.current = page;
    const data = await apiClient.get<PageResp<ThreatDetection>>(
      `/api/xamsecure/threats?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setThreats(data);
  }, [acct]);

  const loadVulnDashboard = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('vuln_dashboard', selectedAccountId);
    if (!force) {
      const cached = loadCache<VulnerabilityDashboard>(key);
      if (cached) { setVulnDashboard(cached); return; }
    }
    const data = await apiClient.get<VulnerabilityDashboard>(`/api/xamsecure/vulnerabilities/dashboard?${acct}`);
    setVulnDashboard(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadVulnByCve = useCallback(async (page = 0, severity = '') => {
    const data = await apiClient.get<PageResp<CveGroupRow>>(
      `/api/xamsecure/vulnerabilities/by-cve?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setVulnByCve(data);
  }, [acct]);

  const loadCveFindings = useCallback(async (cveId: string): Promise<CveResourceFinding[]> => {
    return apiClient.get<CveResourceFinding[]>(
      `/api/xamsecure/vulnerabilities/cve/findings?${acct}cveId=${encodeURIComponent(cveId)}`);
  }, [acct]);

  const loadVulnerabilities = useCallback(async (page = 0, severity = '', force = false) => {
    const key = xamSecureCacheKey(`vulns_${page}_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<VulnerabilityFinding>>(key);
      if (cached) { setVulnerabilities(cached); return; }
    }
    const data = await apiClient.get<PageResp<VulnerabilityFinding>>(
      `/api/xamsecure/vulnerabilities?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setVulnerabilities(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadPosture = useCallback(async (page = 0, severity = '', force = false) => {
    const key = xamSecureCacheKey(`posture_${page}_${severity}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<PostureFinding>>(key);
      if (cached) { setPosture(cached); return; }
    }
    const data = await apiClient.get<PageResp<PostureFinding>>(
      `/api/xamsecure/posture?${acct}page=${page}&size=50${severity ? `&severity=${severity}` : ''}`);
    setPosture(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadInventory = useCallback(async (page = 0, category = '', force = false) => {
    const key = xamSecureCacheKey(`inventory_${page}_${category}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<PageResp<InventoryAsset>>(key);
      if (cached) { setInventory(cached); return; }
    }
    const data = await apiClient.get<PageResp<InventoryAsset>>(
      `/api/xamsecure/inventory?${acct}page=${page}&size=50${category ? `&category=${category}` : ''}`);
    setInventory(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadInventoryCategories = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('inventory_categories', selectedAccountId);
    if (!force) {
      const cached = loadCache<InventoryCategory[]>(key);
      if (cached) { setInventoryCategories(cached); return; }
    }
    const data = await apiClient.get<InventoryCategory[]>(`/api/xamsecure/inventory/categories?${acct}`);
    setInventoryCategories(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadNetworkTopology = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('inventory_network', selectedAccountId);
    if (!force) {
      const cached = loadCache<NetworkTopology>(key);
      if (cached) { setNetworkTopology(cached); return; }
    }
    const data = await apiClient.get<NetworkTopology>(`/api/xamsecure/inventory/network?${acct}`);
    setNetworkTopology(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadCompliance = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('compliance', selectedAccountId);
    if (!force) {
      const cached = loadCache<ComplianceFramework[]>(key);
      if (cached) { setCompliance(cached); return; }
    }
    const data = await apiClient.get<ComplianceFramework[]>(`/api/xamsecure/compliance?${acct}`);
    setCompliance(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadConfigDashboard = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('config_dashboard', selectedAccountId);
    if (!force) {
      const cached = loadCache<ConfigurationsDashboard>(key);
      if (cached) { setConfigDashboard(cached); return; }
    }
    const data = await apiClient.get<ConfigurationsDashboard>(`/api/xamsecure/configurations/dashboard?${acct}`);
    setConfigDashboard(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadApiSecurity = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('apisec', selectedAccountId);
    if (!force) {
      const cached = loadCache<ApiEndpoint[]>(key);
      if (cached) { setApiSecurity(cached); return; }
    }
    const data = await apiClient.get<ApiEndpoint[]>(`/api/xamsecure/api-security?${acct}`);
    setApiSecurity(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadComponents = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('components', selectedAccountId);
    if (!force) {
      const cached = loadCache<SbomComponent[]>(key);
      if (cached) { setComponents(cached); return; }
    }
    const data = await apiClient.get<SbomComponent[]>(`/api/xamsecure/components?${acct}`);
    setComponents(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadThreatPolicies = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('policies', selectedAccountId);
    if (!force) {
      const cached = loadCache<ThreatPolicy[]>(key);
      if (cached) { setThreatPolicies(cached); return; }
    }
    const data = await apiClient.get<ThreatPolicy[]>(`/api/xamsecure/threat-policies?${acct}`);
    setThreatPolicies(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadWorkflows = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('workflows', selectedAccountId);
    if (!force) {
      const cached = loadCache<Workflow[]>(key);
      if (cached) { setWorkflows(cached); return; }
    }
    const data = await apiClient.get<Workflow[]>(`/api/xamsecure/workflows?${acct}`);
    setWorkflows(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  const loadOnboarding = useCallback(async () => {
    const data = await apiClient.get<OnboardingAccount[]>('/api/xamsecure/onboarding/status');
    setOnboarding(data);
  }, []);

  const archiveThreat = useCallback(async (upwindId: string) => {
    await apiClient.post(`/api/xamsecure/threats/${encodeURIComponent(upwindId)}/status`, { status: 'ARCHIVED' });
    await loadThreats(threatPageRef.current);
  }, [loadThreats]);

  // Load the active tab's data; Threats tab also polls every 60s.
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      setIsLoading(true);
      try {
        if (activeTab === 'issues') await loadIssues();
        else if (activeTab === 'overview') {
          await Promise.all([loadSummary(), loadIssues(), loadThreats(), loadStories(), loadVulnDashboard()]);
        }
        else if (activeTab === 'attack-surface') await loadAttackSurface();
        else if (activeTab === 'threats') await Promise.all([loadThreats(), loadThreatPolicies()]);
        else if (activeTab === 'vulnerabilities') {
          await Promise.all([loadVulnDashboard(), loadVulnByCve()]);
        }
        else if (activeTab === 'posture') {
          await Promise.all([loadConfigDashboard(), loadPosture(), loadCompliance()]);
        }
        else if (activeTab === 'inventory') {
          await Promise.all([loadInventory(), loadInventoryCategories(), loadNetworkTopology()]);
        }
        else if (activeTab === 'compliance') await loadCompliance();
        else if (activeTab === 'api-security') await loadApiSecurity();
        else if (activeTab === 'components') await loadComponents();
        else if (activeTab === 'threat-policies') await loadThreatPolicies();
        else if (activeTab === 'workflows') await loadWorkflows();
        else if (activeTab === 'onboarding') await loadOnboarding();
      } catch (err) {
        console.error('[XamSecure] load failed:', err);
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    };
    run();

    if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    if (activeTab === 'threats') {
      pollRef.current = window.setInterval(() => {
        loadThreats(threatPageRef.current).catch((err) => console.error('[XamSecure] threat poll failed:', err));
      }, THREAT_POLL_MS);
    }
    return () => {
      cancelled = true;
      if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    };
  }, [activeTab, selectedAccountId, loadIssues, loadAttackSurface, loadSummary, loadThreats, loadStories,
      loadVulnerabilities, loadVulnDashboard, loadVulnByCve, loadPosture, loadInventory, loadInventoryCategories,
      loadNetworkTopology, loadCompliance, loadConfigDashboard, loadApiSecurity, loadComponents,
      loadThreatPolicies, loadWorkflows, loadOnboarding]);

  const refresh = useCallback(() => {
    if (activeTab === 'issues') return loadIssues('', true);
    if (activeTab === 'attack-surface') return loadAttackSurface(true);
    if (activeTab === 'overview') {
      return Promise.all([loadSummary(true), loadIssues('', true), loadThreats(), loadStories(true), loadVulnDashboard(true)]);
    }
    if (activeTab === 'threats') return Promise.all([loadThreats(), loadThreatPolicies(true)]);
    if (activeTab === 'vulnerabilities') {
      return Promise.all([loadVulnDashboard(true), loadVulnByCve()]);
    }
    if (activeTab === 'posture') {
      return Promise.all([loadConfigDashboard(true), loadPosture(0, '', true), loadCompliance(true)]);
    }
    if (activeTab === 'inventory') {
      return Promise.all([loadInventory(0, '', true), loadInventoryCategories(true), loadNetworkTopology(true)]);
    }
    if (activeTab === 'compliance') return loadCompliance(true);
    if (activeTab === 'api-security') return loadApiSecurity(true);
    if (activeTab === 'components') return loadComponents(true);
    if (activeTab === 'threat-policies') return loadThreatPolicies(true);
    if (activeTab === 'workflows') return loadWorkflows(true);
    if (activeTab === 'onboarding') return loadOnboarding();
    return Promise.resolve();
  }, [activeTab, loadIssues, loadAttackSurface, loadSummary, loadThreats, loadStories, loadVulnerabilities,
      loadVulnDashboard, loadVulnByCve, loadPosture, loadInventory, loadInventoryCategories, loadNetworkTopology,
      loadCompliance, loadConfigDashboard, loadApiSecurity, loadComponents, loadThreatPolicies,
      loadWorkflows, loadOnboarding]);

  return {
    summary, issues, attackSurface, threats, vulnerabilities, vulnDashboard, vulnByCve, posture, inventory,
    inventoryCategories, networkTopology, compliance, configDashboard, onboarding, apiSecurity, components,
    threatPolicies, workflows, stories,
    isLoading, refresh,
    loadIssues, loadIssueDetail, loadThreats, loadVulnerabilities, loadVulnByCve, loadCveFindings,
    loadPosture, loadInventory, loadApiSecurity, loadComponents, archiveThreat,
  };
};
