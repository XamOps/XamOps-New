/**
 * XamLens tab catalog, read by both the page router and the sidebar.
 *
 * XamLens is the data security posture management (DSPM) sub-tab of SecOps, backed by Matters.ai, and
 * sits beside XamSecure (Upwind CNAPP). Like XamSecure, one per-user tab key (`secops-xamlens`) covers
 * all of its routes; the route gate matches by prefix, so `/xamlens/datastores/…` needs no new key.
 *
 * The Datastores page (`/xamlens/datastores/:tab`) replaces the old Cloud, SaaS and Endpoint pages, whose
 * deep links redirect to the matching tab. Misconfigurations (`/xamlens/misconfigurations`) sits under the
 * console's "Risks" section, which the sidebar heads above "Data Inventory", as the console does. Risks has
 * only that entry: the console's Alerts, Exposure and DAM pages have no API data.
 */

export type XamLensTab = 'misconfigurations' | 'datastores' | 'data-lineage' | 'connection';

export interface XamLensTabDef {
  id: XamLensTab;
  label: string;
  /** Font Awesome class, matching the rest of the sidebar. */
  icon: string;
  href: string;
  /** The sidebar entry is active on any path starting with this. */
  activePrefix: string;
  /** Defaults to true. False keeps the route valid while hiding it from the nav. */
  sidebar?: boolean;
  /** The Matters console's sidebar section the entry sits under; the sidebar heads each section once. */
  section?: string;
}

export const XAMLENS_HOME = '/xamlens/datastores/all';

/** The Data Lineage page; its steps live in the query (`lineageQuery.ts`). */
export const XAMLENS_LINEAGE_HOME = '/xamlens/data-lineage';

export const XAMLENS_TABS: XamLensTabDef[] = [
  { id: 'misconfigurations', label: 'Misconfigurations', icon: 'fas fa-shield-alt', href: '/xamlens/misconfigurations', activePrefix: '/xamlens/misconfigurations', section: 'Risks' },
  { id: 'datastores', label: 'Datastores', icon: 'fas fa-database', href: XAMLENS_HOME, activePrefix: '/xamlens/datastores', section: 'Data Inventory' },
  { id: 'data-lineage', label: 'Data Lineage', icon: 'fas fa-diagram-project', href: XAMLENS_LINEAGE_HOME, activePrefix: XAMLENS_LINEAGE_HOME, section: 'Data Inventory' },
  // Connection state. Deep-link only: it matters when something is wrong, not in the healthy path.
  { id: 'connection', label: 'Connection', icon: 'fas fa-circle-nodes', href: '/xamlens/connection', activePrefix: '/xamlens/connection', sidebar: false },
];

/** Nav entries, in declared order. */
export const XAMLENS_SIDEBAR_TABS = XAMLENS_TABS.filter(t => t.sidebar !== false);

/** URL segment of a Datastores tab. */
export type DatastoreTabSlug = 'all' | 'cloud' | 'saas' | 'endpoint' | 'on-prem';

/** The backend's `tab` parameter for the same tab. */
export type DatastoreApiTab = 'all' | 'cloud' | 'saas' | 'endpoint' | 'on_prem';

export const DATASTORE_TABS: ReadonlyArray<{ slug: DatastoreTabSlug; label: string; apiTab: DatastoreApiTab }> = [
  { slug: 'all', label: 'All', apiTab: 'all' }, { slug: 'cloud', label: 'Cloud', apiTab: 'cloud' },
  { slug: 'saas', label: 'SaaS', apiTab: 'saas' }, { slug: 'endpoint', label: 'Endpoint', apiTab: 'endpoint' },
  { slug: 'on-prem', label: 'On-Prem', apiTab: 'on_prem' },
];

/** Old deep links: /xamlens/cloud, /xamlens/saas, /xamlens/endpoint. */
export const LEGACY_XAMLENS_SEGMENTS: Record<string, DatastoreTabSlug> = { cloud: 'cloud', saas: 'saas', endpoint: 'endpoint' };

/** Tab definition for a route segment, or undefined for an unknown one. */
export const datastoreTab = (slug: string | undefined) => DATASTORE_TABS.find(t => t.slug === slug);
