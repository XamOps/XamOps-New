/**
 * The Dashboard's display rules (design 6.4, brief 10.1): environment names, the console's tooltip texts,
 * the bar links into the Datastores page and the compact count format. Pure; the page and its widgets
 * read these.
 */
import type { DatastoreTabSlug } from '../../../constants/xamlensTabs';
import type { DashboardEnvironmentId, TopRiskyTab } from '../../../types/dspmPages';

/** The card titles, as the console's screenshot shows them (brief 10.1: "On Prem", no hyphen). */
export const ENVIRONMENT_NAMES: Readonly<Record<DashboardEnvironmentId, string>> = {
  cloud: 'Cloud',
  saas: 'SaaS',
  endpoint: 'Endpoint',
  on_prem: 'On Prem',
};

/**
 * The cards' info tooltips, copied verbatim (typo included) from the console's public bundle chunk
 * `tooltips-a0def156.js` (keys `environmentCloud`, `environmentSaaS`, `environmentEndpoint`,
 * `environmentOnPrem`), read 2026-11-19.
 */
export const ENVIRONMENT_TOOLTIPS: Readonly<Record<DashboardEnvironmentId, string>> = {
  cloud: 'Sensitive records stored across cloud datastores. Includes a breakdown by top services such as AWS, '
    + 'GCP, and Azure to highlight where cloud data exposure is concentrated.',
  saas: 'Sensitive records across SaaS datastores. Shows key platforms like Google Workspace, Microsoft 365, and '
    + 'Jira to identify where externally managed data resides.',
  endpoint: 'Sensitive records on employee devices. Breaks down data across operating systems like macOS, '
    + 'Windows, and Linux to understand endpoint-level risk.',
  on_prem: 'Sensitive records within on-premise datastores. Includes sources like file servers, databases, and '
    + 'datatsores to track internally hosted data exposure.',
};

/** The Top Risky Datastores tooltip (`topRiskyDatastores` of the same chunk). */
export const TOP_RISKY_TOOLTIP = 'Shows the datastores that currently present the highest sensitive data risk in '
  + 'the selected environment. Rankings are based on the volume of sensitive records and the severity of '
  + 'underlying risk factors, helping teams quickly identify which storage locations may require immediate '
  + 'review or remediation.';

/** The Top Risky Datastores tabs, in the console's order. */
export const TOP_RISKY_TABS: ReadonlyArray<{ id: TopRiskyTab; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'cloud', label: 'Cloud' },
  { id: 'saas', label: 'SaaS' },
  { id: 'endpoint', label: 'Endpoint' },
  { id: 'on_prem', label: 'On-Prem' },
];

/** The Datastores tab of an environment or Top Risky tab (`on_prem` → `on-prem`). */
export function datastoresSlug(id: TopRiskyTab): DatastoreTabSlug {
  return id === 'on_prem' ? 'on-prem' : id;
}

/**
 * Where a bar leads: the environment's Datastores tab filtered by the bar's provider (`provider`) or OS
 * (`os`), e.g. `/xamlens/datastores/on-prem?provider=SELF_HOSTED_POSTGRES`.
 */
export function dashboardBarLink(environment: DashboardEnvironmentId, filter: 'provider' | 'os', key: string): string {
  return `/xamlens/datastores/${datastoresSlug(environment)}?${filter}=${encodeURIComponent(key)}`;
}

const COMPACT = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });

/** A count as the console's cards print it, to one decimal: 512, 4.2K, 37.5K, 1.2M. */
export function formatCompactCount(n: number): string {
  return COMPACT.format(n);
}
