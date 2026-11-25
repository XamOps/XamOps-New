/**
 * XamPulse (Observability 1.0) tab catalog: single source of truth for the page router, sidebar and command palette.
 * `accessKey` is the xp-* key checked with isXamTabAllowed.
 */
export type XamPulseTab = 'overview' | 'hosts' | 'containers' | 'logs' | 'metrics' | 'alerts' | 'insights' | 'setup';

export interface XamPulseTabDef {
  id: XamPulseTab;
  label: string;
  icon: string;
  accessKey: string;
  blurb: string;
}

export const XAMPULSE_TABS: XamPulseTabDef[] = [
  { id: 'overview', label: 'Overview', icon: 'fas fa-heart-pulse', accessKey: 'xp-overview', blurb: 'Fleet health at a glance' },
  { id: 'hosts', label: 'Hosts', icon: 'fas fa-server', accessKey: 'xp-hosts', blurb: 'Every EC2 instance, with Host 360' },
  { id: 'containers', label: 'Containers', icon: 'fas fa-cubes', accessKey: 'xp-containers', blurb: 'Docker containers across hosts' },
  { id: 'logs', label: 'Logs', icon: 'fas fa-stream', accessKey: 'xp-logs', blurb: 'Search, live tail and patterns' },
  { id: 'metrics', label: 'Metrics', icon: 'fas fa-chart-line', accessKey: 'xp-metrics', blurb: 'Explore CPU, memory, disk, network' },
  { id: 'alerts', label: 'Alerts', icon: 'fas fa-bell', accessKey: 'xp-alerts', blurb: 'Incidents, rules, silences and channels' },
  { id: 'insights', label: 'Insights', icon: 'fas fa-lightbulb', accessKey: 'xp-insights', blurb: 'Evidence-backed findings and forecasts' },
  { id: 'setup', label: 'Setup & Storage', icon: 'fas fa-plug', accessKey: 'xp-setup', blurb: 'Connect AWS, retention, trust' },
];

export const VALID_XAMPULSE_TABS = new Set<string>(XAMPULSE_TABS.map((t) => t.id));
export const XAMPULSE_BASE_PATH = '/xampulse';
export const xamPulsePath = (tab: XamPulseTab, search = ''): string => `${XAMPULSE_BASE_PATH}/${tab}${search}`;

function resourceSearch(accountId: number, search = ''): string {
  const params = new URLSearchParams(search.replace(/^\?/, ''));
  params.set('accountId', String(accountId));
  return `?${params.toString()}`;
}

/** Resource links always include the authorized cloud-account identity to disambiguate duplicate AWS ids. */
export const xamPulseHostPath = (instanceId: string, accountId: number, search = ''): string =>
  `${XAMPULSE_BASE_PATH}/hosts/${encodeURIComponent(instanceId)}${resourceSearch(accountId, search)}`;

/** Path of a container's own page, by name on one host so it survives redeploys. */
export const xamPulseContainerPath = (instanceId: string, name: string, accountId: number, search = ''): string =>
  `${XAMPULSE_BASE_PATH}/containers/${encodeURIComponent(instanceId)}/${encodeURIComponent(name)}${resourceSearch(accountId, search)}`;
