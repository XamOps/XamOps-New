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
      { key: 'secops-xamsecure', label: 'XamSecure (CNAPP)', icon: 'mdi:shield-account-outline' },
      // The DSPM half of XamSecure. A separate key because the two are separately sold, so a customer
      // with both may still want an individual user scoped to one. Granting this key alone does nothing
      // unless the org also holds the matters_dspm_enabled add-on.
      { key: 'secops-xamsecure-dspm', label: 'XamSecure (DSPM)', icon: 'mdi:database-lock-outline' },
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
      { key: 'mediaops-cost-savings', label: 'Cost Savings', icon: 'mdi:piggy-bank-outline' },
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
 * Given a user's assigned tab keys, return a gate function.
 * If the list is empty/undefined there is NO restriction (everything is allowed).
 */
export const makeXamTabGate = (allowedTabs: string[] | null | undefined) => {
  const restricted = Array.isArray(allowedTabs) && allowedTabs.length > 0;
  const allowedSet = new Set(allowedTabs ?? []);
  const isTabAllowed = (key: string): boolean => !restricted || allowedSet.has(key);
  const isModuleAllowed = (moduleKey: string): boolean => {
    if (!restricted) return true;
    const mod = XAMOPS_MODULES.find(m => m.key === moduleKey);
    if (!mod) return true;
    return mod.tabs.some(t => allowedSet.has(t.key));
  };
  return { restricted, isTabAllowed, isModuleAllowed };
};
