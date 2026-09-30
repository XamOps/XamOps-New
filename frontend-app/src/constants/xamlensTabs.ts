/**
 * XamLens tab catalog, read by both the page router and the sidebar.
 *
 * XamLens is the data security posture management (DSPM) sub-tab of SecOps, backed by Matters.ai, and
 * sits beside XamSecure (Upwind CNAPP). Like XamSecure, one per-user tab key (`secops-xamlens`) covers
 * all of its tabs.
 *
 * Each asset tab reads one backend surface (`/api/dspm/{surface}/…`).
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
  /** Defaults to true. False keeps the route valid while hiding it from the nav. */
  sidebar?: boolean;
}

export const XAMLENS_TABS: XamLensTabDef[] = [
  // Cloud covers self-hosted datastores too, matching the vendor console's Cloud screen.
  { id: 'cloud', label: 'Cloud', icon: 'fas fa-database', surface: 'cloud' },
  { id: 'saas', label: 'SaaS', icon: 'fas fa-cloud', surface: 'saas' },
  { id: 'endpoint', label: 'Endpoint', icon: 'fas fa-laptop', surface: 'endpoint' },
  // Connection state. Deep-link only: it matters when something is wrong, not in the healthy path.
  { id: 'connection', label: 'Connection', icon: 'fas fa-circle-nodes', sidebar: false },
];

/** Nav entries, in declared order. */
export const XAMLENS_SIDEBAR_TABS = XAMLENS_TABS.filter((t) => t.sidebar !== false);

/** Tab definition for a route segment, or undefined for an unknown one. */
export const xamLensTab = (id: string | null | undefined): XamLensTabDef | undefined =>
  XAMLENS_TABS.find((t) => t.id === id);
