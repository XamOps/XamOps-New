/**
 * XamOps sidebar module/tab catalog.
 *
 * Canonical source of truth for the tab keys used to scope a XamOps user's sidebar.
 * These keys are stored (as strings) in the shared `user_tab_access` table and are
 * consumed in three places:
 *   1. BillOps admin user manager  — selecting tabs when creating a primary XamOps user
 *   2. XamOps "My Team" page        — selecting tabs when creating a XamOps sub-user
 *   3. Sidebar                      — gating which sections/items render
 *
 * Semantics: a user with NO assigned tabs has NO restriction (full sidebar). A user with
 * one or more assigned tabs sees only the modules/items whose keys are in their list.
 */

export interface XamOpsTab {
  key: string;
  label: string;
  icon: string;
}

export interface XamOpsModule {
  key: string;
  label: string;
  icon: string;
  tabs: XamOpsTab[];
}

export const XAMOPS_MODULES: XamOpsModule[] = [
  {
    key: 'dashboard',
    label: 'Dashboard',
    icon: 'mdi:view-dashboard-outline',
    tabs: [
      { key: 'account-dashboard', label: 'Account Dashboard', icon: 'mdi:chart-line' },
    ],
  },
  {
    key: 'finops',
    label: 'FinOps',
    icon: 'mdi:currency-usd',
    tabs: [
      { key: 'finops-cost', label: 'Cost Management', icon: 'mdi:cash' },
      { key: 'finops-reservations', label: 'Reservations', icon: 'mdi:file-document-outline' },
      { key: 'finops-reports', label: 'FinOps Reports', icon: 'mdi:file-chart-outline' },
      { key: 'finops-waste', label: 'Waste Management', icon: 'mdi:recycle' },
      { key: 'finops-rightsizing', label: 'Rightsizing', icon: 'mdi:arrow-collapse-all' },
      { key: 'finops-disk-rightsizing', label: 'Disk Rightsizing', icon: 'mdi:harddisk' },
      { key: 'finops-cloudsitter', label: 'CloudSitter', icon: 'mdi:clock-outline' },
      { key: 'finops-flex-compute', label: 'Flex Compute', icon: 'mdi:chip' },
      { key: 'finops-autospotting', label: 'AutoSpotting', icon: 'mdi:auto-fix' },
    ],
  },
  {
    key: 'cloudops',
    label: 'CloudOps',
    icon: 'mdi:server',
    tabs: [
      { key: 'cloudops-cloudk8s', label: 'CloudK8s', icon: 'mdi:kubernetes' },
      { key: 'cloudops-code-quality', label: 'Code Quality', icon: 'mdi:chart-line' },
      { key: 'cloudops-cicd', label: 'CICD Pipelines', icon: 'mdi:cogs' },
      { key: 'cloudops-devops-box', label: 'DevOps-in-the-Box', icon: 'mdi:toolbox-outline' },
      { key: 'cloudops-aiops', label: 'AIOps', icon: 'mdi:brain' },
      { key: 'cloudops-dbops', label: 'DbOps', icon: 'mdi:database' },
    ],
  },
  {
    key: 'secops',
    label: 'SecOps',
    icon: 'mdi:shield-outline',
    tabs: [
      { key: 'secops-security-center', label: 'Security Center', icon: 'mdi:shield-check-outline' },
      { key: 'secops-war', label: 'WAR', icon: 'mdi:radar' },
      // Upwind CNAPP.
      { key: 'secops-xamsecure', label: 'XamSecure', icon: 'mdi:shield-account-outline' },
      // DSPM (Matters.ai). One key for all its sub-tabs, like XamSecure; the sub-tabs come from
      // constants/xamlensTabs.ts.
      //
      // New keys are a trap in this allow-list: fail-OPEN for a user with no rows, fail-CLOSED for a
      // user with any, so a key added today is missing from every list saved before it. Migration
      // V20260929_2 grants this one to every user who held secops-xamsecure (where the DSPM tabs used
      // to live), so nobody loses access in the move. Any future key here needs the same treatment.
      { key: 'secops-xamlens', label: 'XamLens', icon: 'mdi:database-eye-outline' },
      { key: 'secops-compliance', label: 'ComplianceOps', icon: 'mdi:file-certificate-outline' },
      { key: 'secops-performance', label: 'Performance Insights', icon: 'mdi:speedometer' },
      { key: 'secops-alerts', label: 'Alerts', icon: 'mdi:bell-outline' },
    ],
  },
  {
    key: 'observability',
    label: 'Observability',
    icon: 'mdi:satellite-uplink',
    tabs: [
      { key: 'obs-overview', label: 'Overview', icon: 'mdi:chart-pie' },
      { key: 'obs-logs', label: 'Logs', icon: 'mdi:format-list-bulleted' },
      { key: 'obs-metrics', label: 'Metrics', icon: 'mdi:chart-line' },
      { key: 'obs-traces', label: 'Traces', icon: 'mdi:vector-polyline' },
      { key: 'obs-alerts', label: 'Alerts', icon: 'mdi:bell-outline' },
      { key: 'obs-setup', label: 'Setup', icon: 'mdi:cog-outline' },
      { key: 'obs-grafana', label: 'Grafana', icon: 'mdi:chart-areaspline' },
    ],
  },
  {
    key: 'sre',
    label: 'SRE',
    icon: 'mdi:stethoscope',
    tabs: [
      { key: 'sre-dashboard', label: 'Dashboard', icon: 'mdi:chart-pie' },
      { key: 'sre-investigations', label: 'Investigations', icon: 'mdi:magnify' },
      { key: 'sre-setup', label: 'Setup', icon: 'mdi:cog-outline' },
    ],
  },
  {
    key: 'mediaops',
    label: 'MediaOps',
    icon: 'mdi:filmstrip',
    tabs: [
      { key: 'mediaops-overview', label: 'Overview', icon: 'mdi:chart-pie' },
      { key: 'mediaops-transcode', label: 'Transcoding Jobs', icon: 'mdi:swap-horizontal' },
      { key: 'mediaops-streams', label: 'Live Streams', icon: 'mdi:broadcast' },
      { key: 'mediaops-storage', label: 'Media Storage', icon: 'mdi:database' },
      // Cost Savings hidden for now
      // { key: 'mediaops-cost-savings', label: 'Cost Savings', icon: 'mdi:piggy-bank-outline' },
    ],
  },
  {
    key: 'licenses',
    label: 'Licenses',
    icon: 'mdi:key-outline',
    tabs: [
      { key: 'licenses-marketops', label: 'MarketOps', icon: 'mdi:storefront-outline' },
      { key: 'licenses-tools', label: 'Licenses & Tools', icon: 'mdi:key-outline' },
    ],
  },
  {
    key: 'tickets',
    label: 'Tickets',
    icon: 'mdi:ticket-outline',
    tabs: [
      { key: 'xam-tickets-technical', label: 'Technical', icon: 'mdi:server-outline' },
      { key: 'xam-tickets-account-billing', label: 'Account & Billing', icon: 'mdi:file-chart-outline' },
    ],
  },
];

/** Flat list of every XamOps tab key. */
export const ALL_XAMOPS_TAB_KEYS: string[] = XAMOPS_MODULES.flatMap(m => m.tabs.map(t => t.key));

/**
 * A permission stored in `user_tab_access` like a tab key, but not a sidebar tab
 * (docs/cloudk8s/ui-revamp-design.md D2). It is deliberately absent from XAMOPS_MODULES, so
 * ALL_XAMOPS_TAB_KEYS, "Select all", module toggles and makeXamTabGate never grant it: an admin
 * restricting a member's sidebar must not hand out a write permission without seeing it. The pickers
 * show it nested under its parent tab (components/Shared/XamTabPermissionToggle.tsx).
 */
export interface XamOpsTabPermission {
  key: string;
  /** The tab the permission belongs to; it only counts while that tab is granted. */
  parent: string;
  label: string;
}

export const XAMOPS_TAB_PERMISSIONS: XamOpsTabPermission[] = [
  { key: 'cloudops-cloudk8s-automation', parent: 'cloudops-cloudk8s', label: 'Allow automation changes' },
];

const permissionOf = (key: string) => XAMOPS_TAB_PERMISSIONS.find(p => p.key === key);

/**
 * Drops a permission whose parent tab isn't in the list. Applied on every change, so unchecking a
 * tab, its module or everything also removes the permission, and no member is left whose only row is
 * a permission (which makeXamTabGate would read as "restricted to nothing").
 */
export const normalizeAllowedTabs = (keys: readonly string[]): string[] => {
  const present = new Set(keys);
  return keys.filter(key => {
    const permission = permissionOf(key);
    return !permission || present.has(permission.parent);
  });
};

/** The list with one key checked or unchecked, normalized. */
export const toggleAllowedTab = (keys: readonly string[], key: string): string[] =>
  normalizeAllowedTabs(keys.includes(key) ? keys.filter(k => k !== key) : [...keys, key]);

/** "Select all": every sidebar tab, keeping permissions already granted but never adding one. */
export const selectAllXamTabs = (keys: readonly string[]): string[] =>
  normalizeAllowedTabs([...ALL_XAMOPS_TAB_KEYS, ...keys.filter(key => permissionOf(key) !== undefined)]);

/** A platform-wide page rule, set in the BillOps admin User Manager → XamOps Page Visibility. */
export type XamGlobalTabMode = 'HIDE' | 'GRANT';

/**
 * The platform-wide rules that apply to the current user, as `/api/xamops/team/profile` returns
 * them (`hiddenTabs` / `grantedTabs`). Rules the user is an exception to are already left out by
 * the backend. Keys are module keys or tab keys; a tab key wins over its module key.
 */
export interface XamGlobalTabRules {
  hidden?: readonly string[] | null;
  granted?: readonly string[] | null;
}

const MODULE_OF_TAB = new Map<string, string>(
  XAMOPS_MODULES.flatMap(m => m.tabs.map(t => [t.key, m.key] as const)),
);

/** The module a tab belongs to, or undefined for a key outside the catalog. */
export const xamModuleKeyOfTab = (tabKey: string): string | undefined => MODULE_OF_TAB.get(tabKey);

/**
 * Given a user's assigned tab keys, return a gate function.
 * If the list is empty/undefined there is NO restriction (everything is allowed).
 *
 * `globalRules` layers the platform-wide rules on top, per tab:
 *   1. the rule on the tab itself, else the rule on its module;
 *   2. HIDE → not allowed, whatever the user's own list says;
 *   3. GRANT → allowed, even when the user's own list leaves it out;
 *   4. no rule → the user's own list decides, exactly as without `globalRules`.
 */
export const makeXamTabGate = (
  allowedTabs: string[] | null | undefined,
  globalRules?: XamGlobalTabRules | null,
) => {
  const restricted = Array.isArray(allowedTabs) && allowedTabs.length > 0;
  const allowedSet = new Set(allowedTabs ?? []);
  const hidden = new Set(globalRules?.hidden ?? []);
  const granted = new Set(globalRules?.granted ?? []);
  const hasGlobalRules = hidden.size > 0 || granted.size > 0;

  const ownRule = (key: string): XamGlobalTabMode | undefined =>
    hidden.has(key) ? 'HIDE' : granted.has(key) ? 'GRANT' : undefined;

  /** The platform-wide rule deciding this key for this user, if any. */
  const globalRuleFor = (key: string): XamGlobalTabMode | undefined => {
    const rule = ownRule(key);
    if (rule) return rule;
    const moduleKey = MODULE_OF_TAB.get(key);
    return moduleKey ? ownRule(moduleKey) : undefined;
  };

  const isTabAllowed = (key: string): boolean => {
    const rule = globalRuleFor(key);
    if (rule === 'HIDE') return false;
    if (rule === 'GRANT') return true;
    return !restricted || allowedSet.has(key);
  };

  const isModuleAllowed = (moduleKey: string): boolean => {
    if (!restricted && !hasGlobalRules) return true;
    const mod = XAMOPS_MODULES.find(m => m.key === moduleKey);
    if (!mod) return ownRule(moduleKey) !== 'HIDE';
    return mod.tabs.some(t => isTabAllowed(t.key));
  };

  /** True when a platform-wide HIDE removes this key for this user. Drives the URL guard. */
  const isGloballyHidden = (key: string): boolean => globalRuleFor(key) === 'HIDE';

  return { restricted, isTabAllowed, isModuleAllowed, globalRuleFor, isGloballyHidden };
};

/**
 * Route prefix → the XamOps tab key that owns it, for the URL guard (XamTabRouteGuard).
 * A path matches a prefix when it equals it or continues with "/". The longest match wins, so
 * `/observability/logs` resolves to obs-logs while `/observability` itself is obs-overview.
 *
 * Covers the AWS, GCP and Azure variants the sidebar links to. A route missing here is simply not
 * URL-guarded; it is still removed from the sidebar.
 */
export const XAMOPS_ROUTE_TABS: Readonly<Record<string, string>> = {
  '/dashboard': 'account-dashboard',
  '/gcp-dashboard': 'account-dashboard',
  '/azure-dashboard': 'account-dashboard',

  '/cost': 'finops-cost',
  '/gcp-cost': 'finops-cost',
  '/azure-cost': 'finops-cost',
  '/reservation': 'finops-reservations',
  '/gcp-reservations': 'finops-reservations',
  '/azure-reservations': 'finops-reservations',
  '/finops': 'finops-reports',
  '/gcp-finops': 'finops-reports',
  '/azure-finops': 'finops-reports',
  '/waste': 'finops-waste',
  '/gcp-waste': 'finops-waste',
  '/azure-waste': 'finops-waste',
  '/rightsizing': 'finops-rightsizing',
  '/gcp-rightsizing': 'finops-rightsizing',
  '/azure-rightsizing': 'finops-rightsizing',
  '/ebs-rightsizing': 'finops-disk-rightsizing',
  '/gcp-disk-rightsizing': 'finops-disk-rightsizing',
  '/azure-disk-rightsizing': 'finops-disk-rightsizing',
  '/cloudsitter': 'finops-cloudsitter',
  '/gcp-cloudsitter': 'finops-cloudsitter',
  '/azure-cloudsitter': 'finops-cloudsitter',
  '/flex-compute': 'finops-flex-compute',
  '/autospotting': 'finops-autospotting',
  '/spot-automation': 'finops-autospotting',
  '/gcp-autospotting': 'finops-autospotting',
  '/asg-detail': 'finops-autospotting',
  '/asg-config': 'finops-autospotting',

  '/cloudk8s': 'cloudops-cloudk8s',
  '/gcp-cloudk8s': 'cloudops-cloudk8s',
  '/azure-cloudk8s': 'cloudops-cloudk8s',
  '/eks-details': 'cloudops-cloudk8s',
  '/gke-details': 'cloudops-cloudk8s',
  '/aks-details': 'cloudops-cloudk8s',
  '/sonarqube': 'cloudops-code-quality',
  '/cicd-pipelines': 'cloudops-cicd',
  '/devops-in-the-box': 'cloudops-devops-box',
  '/aiops': 'cloudops-aiops',
  '/dataops': 'cloudops-dbops',
  '/gcp-dataops': 'cloudops-dbops',
  '/azure-dataops': 'cloudops-dbops',

  '/security': 'secops-security-center',
  '/gcp-security': 'secops-security-center',
  '/azure-security': 'secops-security-center',
  '/war': 'secops-war',
  '/gcp-war': 'secops-war',
  '/azure-war': 'secops-war',
  '/xamsecure': 'secops-xamsecure',
  '/xamlens': 'secops-xamlens',
  '/complianceops': 'secops-compliance',
  '/soc2-program': 'secops-compliance',
  '/performance': 'secops-performance',
  '/gcp-performance': 'secops-performance',
  '/azure-performance': 'secops-performance',
  '/alerts': 'secops-alerts',
  '/gcp-alerts': 'secops-alerts',
  '/azure-alerts': 'secops-alerts',

  '/observability': 'obs-overview',
  '/observability/logs': 'obs-logs',
  '/observability/metrics': 'obs-metrics',
  '/observability/traces': 'obs-traces',
  '/observability/alerts': 'obs-alerts',
  '/observability/setup': 'obs-setup',
  '/grafana-dashboard': 'obs-grafana',

  '/sre': 'sre-dashboard',
  '/sre/investigations': 'sre-investigations',
  '/sre/setup': 'sre-setup',

  '/media-ops': 'mediaops-overview',
  '/media-ops/transcode': 'mediaops-transcode',
  '/media-ops/streams': 'mediaops-streams',
  '/media-ops/storage': 'mediaops-storage',

  '/marketplace-purchases': 'licenses-marketops',
  '/workspace-licenses': 'licenses-tools',

  '/tickets': 'xam-tickets-technical',
  '/ticket-detail': 'xam-tickets-technical',
  '/billops-tickets': 'xam-tickets-account-billing',
  '/billops-ticket-detail': 'xam-tickets-account-billing',
};

const ROUTE_PREFIXES_LONGEST_FIRST = Object.keys(XAMOPS_ROUTE_TABS).sort((a, b) => b.length - a.length);

/** The XamOps tab key owning a pathname, or undefined when the route is not a catalog page. */
export const xamTabKeyForPath = (pathname: string): string | undefined => {
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname;
  const prefix = ROUTE_PREFIXES_LONGEST_FIRST.find(p => path === p || path.startsWith(`${p}/`));
  return prefix ? XAMOPS_ROUTE_TABS[prefix] : undefined;
};
