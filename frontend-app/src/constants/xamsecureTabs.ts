/**
 * XamSecure tab catalog — the single source of truth for both the page router and the sidebar.
 *
 * Why this file exists: the list was duplicated. XamSecurePage owned the route-validating copy
 * and Sidebar.tsx kept its own hand-written array whose comment claimed it "mirrors the tabs on
 * XamSecurePage". It did not. Components was renamed to Packages on 6 Aug and the Code tab was
 * added on 12 Aug; neither reached the sidebar, so Code was deployed and reachable only by
 * typing the URL. A list that has to be edited in two places to stay correct will not stay
 * correct, so both consumers now read this one.
 *
 * `sidebar: false` marks routes that are deep-link reachable but deliberately absent from the
 * nav — Workflows and Onboarding map to Upwind's Settings area, not its security nav.
 */

export type XamSecureTab =
  | 'issues' | 'overview' | 'attack-surface' | 'threats' | 'vulnerabilities' | 'posture'
  | 'inventory' | 'api-security' | 'identities' | 'components' | 'code' | 'threat-policies'
  | 'workflows' | 'compliance' | 'onboarding';

export interface XamSecureTabDef {
  id: XamSecureTab;
  label: string;
  /** Font Awesome class, matching the rest of the sidebar's icon vocabulary. */
  icon: string;
  /** Defaults to true. False keeps the route valid while hiding it from the nav. */
  sidebar?: boolean;
}

export const XAMSECURE_TABS: XamSecureTabDef[] = [
  { id: 'overview', label: 'Home', icon: 'fas fa-house' },
  { id: 'issues', label: 'Issues', icon: 'fas fa-triangle-exclamation' },
  { id: 'inventory', label: 'Inventory', icon: 'fas fa-boxes-stacked' },
  { id: 'attack-surface', label: 'Attack Surface', icon: 'fas fa-globe' },
  { id: 'vulnerabilities', label: 'Vulnerabilities', icon: 'fas fa-bug' },
  { id: 'posture', label: 'Configurations', icon: 'fas fa-clipboard-check' },
  { id: 'api-security', label: 'APIs', icon: 'fas fa-plug' },
  { id: 'identities', label: 'Identities', icon: 'fas fa-user-shield' },
  { id: 'threats', label: 'Threats', icon: 'fas fa-bolt' },
  // "Packages", not "Components". Upwind's Components area is sensor and agent fleet
  // management (Sensors / Cloud Scanners / Integrations / Workflows, with health and coverage);
  // this tab is a software bill of materials. Sharing their label implied parity with a feature
  // we cannot build — nine candidate sensor endpoints all return 404, so there is no API behind
  // their version.
  { id: 'components', label: 'Packages', icon: 'fas fa-cubes' },
  // Upwind's Code area: CI scan history from the shift-left stream. Org-level data, so the tab
  // renders a withheld state for clients that do not own the whole Upwind organization.
  { id: 'code', label: 'Code', icon: 'fas fa-code-branch' },
  { id: 'workflows', label: 'Workflows', icon: 'fas fa-diagram-project', sidebar: false },
  { id: 'onboarding', label: 'Onboarding', icon: 'fas fa-plug-circle-plus', sidebar: false },
];

/** Nav entries, in declared order. */
export const XAMSECURE_SIDEBAR_TABS = XAMSECURE_TABS.filter((t) => t.sidebar !== false);

export const VALID_XAMSECURE_TABS = new Set<string>(XAMSECURE_TABS.map((t) => t.id));
