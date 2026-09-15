import { useState, useEffect, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';
import type {
  XamSecureSummary, ThreatDetection, VulnerabilityFinding, PostureFinding,
  ComplianceFramework, OnboardingAccount, PageResp, InventoryAsset, ApiEndpoint, SbomComponent,
  ThreatPolicy, Workflow,
  SensorCoverage,
  IssueSummary, IssueDetail, ThreatStoriesSummary, ThreatStoryDetail, ThreatOverview,
  VulnGroupPage, VulnGroupDimension, ThreatGroups, ThreatGroupDimension,
  CodeScansResponse, CodeScanFindings, FrameworkControls, AssetRelationships,
  InventoryCategory,
  InventoryRisk, NetworkTopology,
  AttackSurfaceSummary, VulnerabilityDashboard, CveGroupRow, CveResourceFinding,
  ConfigurationsDashboard, ResourceDetail, SyncStatus, IdentitiesSummary,
} from '../types/xamsecure';

const CACHE_DURATION = 30 * 60 * 1000;        // sections with no sync stamp to key off
const STAMPED_MAX_AGE = 12 * 60 * 60 * 1000;  // ceiling for stamped entries; see stampFor
const STAMP_REFRESH_MS = 60 * 1000;           // how often the stamps themselves are re-read
const THREAT_POLL_MS = 60 * 1000;             // runtime feed

// Bump this version whenever a cached response's shape changes, so stale
// localStorage entries miss and refetch instead of masking new fields.
// v5 also changed the key separator to '|' so a section name can be recovered from a key.
const CACHE_VERSION = 'v5';

const ALL_DOMAINS = ['DETECTIONS', 'VULNERABILITIES', 'POSTURE', 'INVENTORY', 'API_ENDPOINTS'] as const;

/**
 * Which backend sync produces each cached section, or null where nothing does.
 *
 * A section backed by a synced table only changes when that sync lands, so its cache entry is
 * keyed by the sync's completion time rather than expiring on a timer. The full sync runs every
 * six hours, so a thirty-minute timer was throwing away good data roughly eleven times out of
 * twelve and re-running the same queries to get the same rows back.
 *
 * null means the section is served live from the vendor API, or mixes live and stored data, so no
 * stamp describes it and the timer is the only honest option. Verified per method rather than
 * assumed: policies, workflows, components and code scans are live; stories, compliance and the
 * network graph are mixed; config_dashboard delegates, so it is treated as live.
 */
const SECTION_SYNC: Record<string, readonly string[] | null> = {
  summary: ALL_DOMAINS,
  attack_surface: ['POSTURE'],
  identities: ['POSTURE', 'INVENTORY'],
  vuln_dashboard: ['VULNERABILITIES'],
  inventory_categories: ['INVENTORY'],
  inventory_risk: ['INVENTORY'],
  sensor_coverage: ['INVENTORY'],
  apisec: ['API_ENDPOINTS'],
  // Checked before the prefixes below, which is what keeps inventory_network off the
  // INVENTORY stamp — the network graph is built from live vendor calls.
  inventory_network: null,
  compliance: null,
  stories: null,
  components: null,
  policies: null,
  workflows: null,
  code_scans: null,
  config_dashboard: null,
};

/** Sections whose names carry parameters (page, severity, category, days). */
const SECTION_SYNC_PREFIX: ReadonlyArray<readonly [string, readonly string[]]> = [
  ['issues_', ['POSTURE']],
  ['threat_overview_', ['DETECTIONS']],
  ['vulns_', ['VULNERABILITIES']],
  ['posture_', ['POSTURE']],
  ['inventory_', ['INVENTORY']],
];

function domainsFor(section: string): readonly string[] | null {
  if (section in SECTION_SYNC) return SECTION_SYNC[section];
  for (const [prefix, domains] of SECTION_SYNC_PREFIX) {
    if (section.startsWith(prefix)) return domains;
  }
  return null;
}

/**
 * Last-success times per sync domain, module-scoped so the cache key helper can reach them
 * without every call site having to thread them through. The hook is the only writer.
 */
let syncStamps: Record<string, string | null> = {};
let syncStampsAt = 0;

/** Marks a key as timer-expiring rather than stamp-invalidated. */
const TIMER_STAMP = 't';

function stampFor(section: string): string {
  const domains = domainsFor(section);
  if (!domains || syncStampsAt === 0) return TIMER_STAMP;
  // '0' for a domain that has never synced, so the key still changes once it does.
  return domains.map((d) => syncStamps[d] ?? '0').join('.');
}

export function xamSecureCacheKey(section: string, accountId: string | null): string {
  return ['xamsecure', CACHE_VERSION, section, accountId ?? 'all', stampFor(section)].join('|');
}

/**
 * Replaces the sync stamps. Returns true when any domain moved, which means every stamped cache
 * entry keyed off the old value is now unreachable and will be refetched on next use.
 */
export function applySyncStamps(status: SyncStatus): boolean {
  const next: Record<string, string | null> = {};
  status.domains.forEach((d) => { next[d.domain] = d.lastSuccessAt; });
  const changed = ALL_DOMAINS.some((d) => next[d] !== syncStamps[d]);
  syncStamps = next;
  syncStampsAt = Date.now();
  return changed;
}

function loadCache<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    const expiry = localStorage.getItem(`${key}_expiry`);
    if (raw && expiry && Date.now() <= parseInt(expiry)) return JSON.parse(raw) as T;
  } catch { /* ignore */ }
  return null;
}

/**
 * Drops earlier entries for the same section and account under a different stamp.
 *
 * Without this, every sync would leave its predecessor's payload behind and localStorage would
 * grow until a write threw, at which point the try/catch would silently stop caching altogether.
 */
function pruneSupersededEntries(key: string): void {
  const cut = key.lastIndexOf('|');
  if (cut < 0) return;
  const prefix = key.slice(0, cut + 1);
  const doomed: string[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const k = localStorage.key(i);
    if (k && k.startsWith(prefix) && k !== key && k !== `${key}_expiry`
        && !k.endsWith('_expiry')) {
      doomed.push(k);
    }
  }
  doomed.forEach((k) => {
    localStorage.removeItem(k);
    localStorage.removeItem(`${k}_expiry`);
  });
}

let stampPrime: Promise<boolean> | null = null;

/**
 * Reads the sync stamps, at most once per STAMP_REFRESH_MS and never concurrently. Resolves true
 * when a domain moved.
 *
 * Has to complete before any cache read, because the stamps are part of the cache key. On
 * failure the stamps stay unset, stampFor falls back to TIMER_STAMP, and everything reverts to
 * the old timer behaviour rather than losing caching altogether.
 */
async function primeSyncStamps(): Promise<boolean> {
  if (Date.now() - syncStampsAt < STAMP_REFRESH_MS) return false;
  if (stampPrime) return stampPrime;
  stampPrime = apiClient.get<SyncStatus>('/api/xamsecure/sync-status')
    .then((s) => applySyncStamps(s))
    .catch(() => false)
    .finally(() => { stampPrime = null; });
  return stampPrime;
}

function saveCache(key: string, value: unknown): void {
  try {
    pruneSupersededEntries(key);
    // A stamped entry is already invalidated by its key when the sync moves, so it only needs a
    // ceiling to guard against a stamp that never changes.
    const ttl = key.endsWith(`|${TIMER_STAMP}`) ? CACHE_DURATION : STAMPED_MAX_AGE;
    localStorage.setItem(key, JSON.stringify(value));
    localStorage.setItem(`${key}_expiry`, (Date.now() + ttl).toString());
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
  const [inventoryRisk, setInventoryRisk] = useState<InventoryRisk | null>(null);
  const [networkTopology, setNetworkTopology] = useState<NetworkTopology | null>(null);
  const [sensorCoverage, setSensorCoverage] = useState<SensorCoverage | null>(null);
  const [compliance, setCompliance] = useState<ComplianceFramework[] | null>(null);
  const [configDashboard, setConfigDashboard] = useState<ConfigurationsDashboard | null>(null);
  const [apiSecurity, setApiSecurity] = useState<ApiEndpoint[] | null>(null);
  const [components, setComponents] = useState<SbomComponent[] | null>(null);
  const [identities, setIdentities] = useState<IdentitiesSummary | null>(null);
  const [threatPolicies, setThreatPolicies] = useState<ThreatPolicy[] | null>(null);
  const [workflows, setWorkflows] = useState<Workflow[] | null>(null);
  const [stories, setStories] = useState<ThreatStoriesSummary | null>(null);
  const [threatOverview, setThreatOverview] = useState<ThreatOverview | null>(null);
  const [vulnGroups, setVulnGroups] = useState<VulnGroupPage | null>(null);
  const [threatGroups, setThreatGroups] = useState<ThreatGroups | null>(null);
  const [codeScans, setCodeScans] = useState<CodeScansResponse | null>(null);
  const [onboarding, setOnboarding] = useState<OnboardingAccount[] | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const pollRef = useRef<number | null>(null);
  const threatPageRef = useRef(0);
  // Tabs already loaded this session (keyed by tab + account). Revisiting a loaded tab
  // serves the in-memory data instantly instead of refetching and flashing a spinner —
  // this is what made live-fetch tabs (APIs/Components) "reload every time you click".
  const loadedRef = useRef<Set<string>>(new Set());

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

  const loadIdentities = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('identities', selectedAccountId);
    if (!force) {
      const cached = loadCache<IdentitiesSummary>(key);
      if (cached) { setIdentities(cached); return; }
    }
    const data = await apiClient.get<IdentitiesSummary>(`/api/xamsecure/identities?${acct}`);
    setIdentities(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  /** Cross-domain view of one resource, used by every table that shows a resource. */
  const loadResourceDetail = useCallback(async (resourceId: string): Promise<ResourceDetail> => {
    return apiClient.get<ResourceDetail>(
      `/api/xamsecure/resource?${acct}resourceId=${encodeURIComponent(resourceId)}`);
  }, [acct]);

  /** Sync freshness for the header indicator. Cheap, so it is never cached. */
  const loadSyncStatus = useCallback(async (): Promise<SyncStatus> => {
    return apiClient.get<SyncStatus>('/api/xamsecure/sync-status');
  }, []);

  /**
   * One threat story in full, for the detail panel. Returns null when the story references no
   * detection in the selected accounts — the server withholds it, since stories carry no
   * account id of their own and detection ownership is what establishes tenancy.
   */
  const loadStoryDetail = useCallback(async (storyId: string): Promise<ThreatStoryDetail | null> => {
    const d = await apiClient.get<ThreatStoryDetail>(
      `/api/xamsecure/stories/${encodeURIComponent(storyId)}?${acct}`);
    return d && d.id ? d : null;
  }, [acct]);

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

  const loadThreatOverview = useCallback(async (force = false, days = 7) => {
    const key = xamSecureCacheKey(`threat_overview_${days}`, selectedAccountId);
    if (!force) {
      const cached = loadCache<ThreatOverview>(key);
      if (cached) { setThreatOverview(cached); return; }
    }
    const data = await apiClient.get<ThreatOverview>(
      `/api/xamsecure/threats/overview?${acct}days=${days}`);
    setThreatOverview(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

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

  const loadVulnByCve = useCallback(async (page = 0, severity = '', view = '') => {
    const data = await apiClient.get<PageResp<CveGroupRow>>(
      `/api/xamsecure/vulnerabilities/by-cve?${acct}page=${page}&size=50`
      + `${severity ? `&severity=${severity}` : ''}${view ? `&view=${view}` : ''}`);
    setVulnByCve(data);
  }, [acct]);

  const loadVulnGroups = useCallback(async (dimension: VulnGroupDimension, page = 0) => {
    const data = await apiClient.get<VulnGroupPage>(
      `/api/xamsecure/vulnerabilities/groups?${acct}dimension=${dimension}&page=${page}&size=50`);
    setVulnGroups(data);
  }, [acct]);

  const loadThreatGroups = useCallback(async (dimension: ThreatGroupDimension) => {
    const data = await apiClient.get<ThreatGroups>(
      `/api/xamsecure/threats/groups?${acct}dimension=${dimension}`);
    setThreatGroups(data);
  }, [acct]);

  const loadCodeScans = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('code_scans', selectedAccountId);
    if (!force) {
      const cached = loadCache<CodeScansResponse>(key);
      if (cached) { setCodeScans(cached); return; }
    }
    const data = await apiClient.get<CodeScansResponse>('/api/xamsecure/code/scans?limit=200');
    setCodeScans(data);
    saveCache(key, data);
  }, [selectedAccountId]);

  /** Fetched on open: a scan's CVE list is large and only wanted when someone drills in. */
  const loadCodeScanFindings = useCallback(
    async (fingerprint: string): Promise<CodeScanFindings | null> => {
      const d = await apiClient.get<CodeScanFindings>(
        `/api/xamsecure/code/scans/${encodeURIComponent(fingerprint)}`);
      return d && d.fingerprint ? d : null;
    }, []);

  /** Per-control detail, fetched when a framework is opened rather than with the tab. */
  const loadFrameworkControls = useCallback(
    async (frameworkId: string): Promise<FrameworkControls | null> => {
      const d = await apiClient.get<FrameworkControls>(
        `/api/xamsecure/compliance/frameworks/${encodeURIComponent(frameworkId)}/controls`);
      return d && Array.isArray(d.controls) ? d : null;
    }, []);

  /** One upstream request per asset, so only on drill-down. */
  const loadAssetRelationships = useCallback(
    async (assetId: string): Promise<AssetRelationships | null> => {
      const d = await apiClient.get<AssetRelationships>(
        `/api/xamsecure/inventory/assets/${encodeURIComponent(assetId)}/relationships`);
      return d && Array.isArray(d.relationships) ? d : null;
    }, []);

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

  const loadInventoryRisk = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('inventory_risk', selectedAccountId);
    if (!force) {
      const cached = loadCache<InventoryRisk>(key);
      if (cached) { setInventoryRisk(cached); return; }
    }
    const data = await apiClient.get<InventoryRisk>(`/api/xamsecure/inventory/risk?${acct}`);
    setInventoryRisk(data);
    saveCache(key, data);
  }, [acct, selectedAccountId]);

  /** Sensor coverage per account — whether connected accounts are actually protected. */
  const loadSensorCoverage = useCallback(async (force = false) => {
    const key = xamSecureCacheKey('sensor_coverage', selectedAccountId);
    if (!force) {
      const cached = loadCache<SensorCoverage>(key);
      if (cached) { setSensorCoverage(cached); return; }
    }
    const data = await apiClient.get<SensorCoverage>(`/api/xamsecure/sensor-coverage?${acct}`);
    setSensorCoverage(data);
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

  /**
   * True when the sidebar has no account selected.
   *
   * XamSecure must never show an account's data unless that account is selected. Omitting
   * accountId makes the server fall back to "every account this client owns", so rather than
   * issue an unscoped request we load nothing and let the page prompt for a selection.
   * The selection is briefly empty on first load and whenever stale ids are cleared, so this
   * is a reachable state, not a theoretical one.
   */
  const noAccountSelected = !selectedAccountId;

  // Load the active tab's data; Threats tab also polls every 60s.
  useEffect(() => {
    let cancelled = false;
    if (noAccountSelected) {
      setIsLoading(false);
      return;
    }
    const tabKey = `${activeTab}:${selectedAccountId}`;
    const run = async () => {
      // Threats polls on its own; every other tab, once loaded for this account, serves
      // in-memory data on revisit (no refetch, no loading flash). Refresh forces a reload.
      // Stamps are part of the cache key, so they have to land before anything reads the cache.
      // Read before the loaded-this-session check too: a sync that lands while the page is open
      // has to drop the in-memory guard, or an already-visited tab keeps showing the pre-sync
      // payload for as long as the page stays mounted. Throttled, so revisiting a tab inside the
      // window costs nothing.
      const stampsMoved = await primeSyncStamps();
      if (cancelled) return;
      if (stampsMoved) loadedRef.current.clear();

      if (activeTab !== 'threats' && loadedRef.current.has(tabKey)) return;
      setIsLoading(true);
      try {
        if (activeTab === 'issues') await loadIssues();
        else if (activeTab === 'overview') {
          await Promise.all([loadSummary(), loadIssues(), loadThreats(), loadStories(), loadVulnDashboard()]);
        }
        else if (activeTab === 'attack-surface') await loadAttackSurface();
        else if (activeTab === 'threats') {
          await Promise.all([loadThreats(), loadThreatPolicies(), loadStories(), loadThreatOverview()]);
        }
        else if (activeTab === 'vulnerabilities') {
          await Promise.all([loadVulnDashboard(), loadVulnByCve()]);
        }
        else if (activeTab === 'posture') {
          // Issues are included so the framework drawer can list each framework's failing
          // controls without an extra round-trip.
          await Promise.all([loadConfigDashboard(), loadPosture(), loadCompliance(), loadIssues()]);
        }
        else if (activeTab === 'inventory') {
          await Promise.all([loadInventory(), loadInventoryCategories(), loadInventoryRisk(),
            loadNetworkTopology(), loadSensorCoverage()]);
        }
        else if (activeTab === 'compliance') await loadCompliance();
        else if (activeTab === 'api-security') await loadApiSecurity();
        else if (activeTab === 'components') await loadComponents();
        else if (activeTab === 'code') await loadCodeScans();
        else if (activeTab === 'identities') await loadIdentities();
        else if (activeTab === 'threat-policies') await loadThreatPolicies();
        else if (activeTab === 'workflows') await loadWorkflows();
        else if (activeTab === 'onboarding') await loadOnboarding();
        loadedRef.current.add(tabKey);
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
  }, [activeTab, selectedAccountId, noAccountSelected,
      loadIssues, loadAttackSurface, loadSummary, loadThreats, loadStories,
      loadVulnerabilities, loadVulnDashboard, loadVulnByCve, loadPosture, loadInventory, loadInventoryCategories,
      loadInventoryRisk,
      loadNetworkTopology, loadSensorCoverage, loadCompliance, loadConfigDashboard,
      loadApiSecurity, loadComponents,
      loadIdentities, loadThreatPolicies, loadWorkflows, loadOnboarding, loadThreatOverview,
      loadCodeScans]);

  const refresh = useCallback(async () => {
    setIsLoading(true);
    try {
      // Re-read the stamps unconditionally: an explicit refresh is also how a user checks
      // whether a sync has landed since the page was opened.
      syncStampsAt = 0;
      await primeSyncStamps();
      if (activeTab === 'issues') await loadIssues('', true);
      else if (activeTab === 'attack-surface') await loadAttackSurface(true);
      else if (activeTab === 'overview') {
        await Promise.all([loadSummary(true), loadIssues('', true), loadThreats(), loadStories(true), loadVulnDashboard(true)]);
      }
      else if (activeTab === 'threats') {
        await Promise.all([loadThreats(), loadThreatPolicies(true), loadStories(true),
                          loadThreatOverview(true)]);
      }
      else if (activeTab === 'vulnerabilities') {
        await Promise.all([loadVulnDashboard(true), loadVulnByCve()]);
      }
      else if (activeTab === 'posture') {
        await Promise.all([loadConfigDashboard(true), loadPosture(0, '', true), loadCompliance(true),
                           loadIssues('', true)]);
      }
      else if (activeTab === 'inventory') {
        await Promise.all([loadInventory(0, '', true), loadInventoryCategories(true),
          loadInventoryRisk(true), loadNetworkTopology(true), loadSensorCoverage(true)]);
      }
      else if (activeTab === 'compliance') await loadCompliance(true);
      else if (activeTab === 'api-security') await loadApiSecurity(true);
      else if (activeTab === 'components') await loadComponents(true);
      else if (activeTab === 'code') await loadCodeScans(true);
      else if (activeTab === 'identities') await loadIdentities(true);
      else if (activeTab === 'threat-policies') await loadThreatPolicies(true);
      else if (activeTab === 'workflows') await loadWorkflows(true);
      else if (activeTab === 'onboarding') await loadOnboarding();
    } catch (err) {
      console.error('[XamSecure] refresh failed:', err);
    } finally {
      setIsLoading(false);
    }
  }, [activeTab, loadIssues, loadAttackSurface, loadSummary, loadThreats, loadStories, loadVulnerabilities,
      loadVulnDashboard, loadVulnByCve, loadPosture, loadInventory, loadInventoryCategories,
      loadInventoryRisk, loadNetworkTopology, loadSensorCoverage,
      loadCompliance, loadConfigDashboard, loadApiSecurity, loadComponents, loadIdentities,
      loadThreatPolicies, loadWorkflows, loadOnboarding, loadThreatOverview, loadCodeScans]);

  return {
    summary, issues, attackSurface, threats, vulnerabilities, vulnDashboard, vulnByCve, posture, inventory,
    noAccountSelected,
    inventoryCategories, inventoryRisk, networkTopology, sensorCoverage, compliance, configDashboard, onboarding, apiSecurity, components,
    threatPolicies, workflows, stories, threatOverview, loadThreatOverview, identities,
    vulnGroups, loadVulnGroups, threatGroups, loadThreatGroups,
    codeScans, loadCodeScanFindings, loadFrameworkControls, loadAssetRelationships,
    isLoading, refresh,
    loadIssues, loadIssueDetail, loadResourceDetail, loadStoryDetail, loadSyncStatus,
    loadThreats, loadVulnerabilities, loadVulnByCve, loadCveFindings,
    loadPosture, loadInventory, loadApiSecurity, loadComponents, archiveThreat,
  };
};
