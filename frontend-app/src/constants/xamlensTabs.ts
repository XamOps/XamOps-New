/**
 * XamLens tab catalog, read by both the page router and the sidebar.
 *
 * XamLens is the data security posture management (DSPM) module, backed by Matters.ai. It used to live
 * inside XamSecure as a second product group; XamSecure is Upwind CNAPP only again.
 *
 * Each asset tab reads one backend surface (`/api/dspm/{surface}/…`). `accessKey` is the per-user tab
 * key from the `xamlens` module in xamopsTabs.ts, which is what scopes a restricted user's sidebar.
 */

import type { DspmSurface } from '../hooks/useDspmData';

export type XamLensTab = 'cloud' | 'saas' | 'endpoint' | 'connection';

export interface XamLensTabDef {
  id: XamLensTab;
  label: string;
  /** Font Awesome class, matching the rest of the sidebar. */
  icon: string;
  /** Backend surface for the asset tabs. Absent for the connection page. */
  surface?: DspmSurface;
  /** Per-user tab key. Null for a route that is not in the nav. */
  accessKey: string | null;
  /** Defaults to true. False keeps the route valid while hiding it from the nav. */
  sidebar?: boolean;
}

export const XAMLENS_TABS: XamLensTabDef[] = [
  // Cloud covers self-hosted datastores too, matching the vendor console's Cloud screen.
  { id: 'cloud', label: 'Cloud Data', icon: 'fas fa-database', surface: 'cloud', accessKey: 'xamlens-cloud' },
  { id: 'saas', label: 'SaaS Data', icon: 'fas fa-cloud', surface: 'saas', accessKey: 'xamlens-saas' },
  { id: 'endpoint', label: 'Endpoint Data', icon: 'fas fa-laptop', surface: 'endpoint', accessKey: 'xamlens-endpoint' },
  // Connection state. Deep-link only: it matters when something is wrong, not in the healthy path.
  { id: 'connection', label: 'Connection', icon: 'fas fa-circle-nodes', accessKey: null, sidebar: false },
];

/** Nav entries, in declared order. */
export const XAMLENS_SIDEBAR_TABS = XAMLENS_TABS.filter((t) => t.sidebar !== false);

/** Tab definition for a route segment, or undefined for an unknown one. */
export const xamLensTab = (id: string | null | undefined): XamLensTabDef | undefined =>
  XAMLENS_TABS.find((t) => t.id === id);
