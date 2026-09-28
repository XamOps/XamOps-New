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
 *
 * ── Groups ──
 * XamSecure now covers two separately sold products from two vendors, so every tab declares which
 * one it belongs to:
 *
 *   CNAPP — Upwind. Cloud workload and posture security. Gated on the client having Upwind-connected
 *           cloud accounts, and scoped by the sidebar account picker.
 *   DSPM  — Matters.ai. Data security posture: where sensitive data lives. Gated on the
 *           `matters_dspm_enabled` entitlement, and deliberately *not* scoped by the account picker
 *           because its assets carry a Matters integration id rather than a cloud account number.
 *
 * The two gates are independent. A client may hold either, both, or neither, and the nav and the
 * landing tab both have to cope with each combination — see `firstTabForGroups`.
 */

export type XamSecureGroup = 'CNAPP' | 'DSPM';

export type XamSecureTab =
  | 'issues' | 'overview' | 'attack-surface' | 'threats' | 'vulnerabilities' | 'posture'
  | 'inventory' | 'api-security' | 'identities' | 'components' | 'code' | 'threat-policies'
  | 'workflows' | 'compliance' | 'onboarding'
  | 'dspm-cloud' | 'dspm-saas' | 'dspm-endpoint' | 'dspm-status';

export interface XamSecureTabDef {
  id: XamSecureTab;
  label: string;
  /** Font Awesome class, matching the rest of the sidebar's icon vocabulary. */
  icon: string;
  /** Which product this tab belongs to. Drives both nav grouping and entitlement gating. */
  group: XamSecureGroup;
  /** Defaults to true. False keeps the route valid while hiding it from the nav. */
  sidebar?: boolean;
  /**
   * Marks a tab as announced but not yet built.
   *
   * The nav shows it with a "Soon" pill and the page renders a placeholder instead of the real content.
   * Chosen over simply omitting the tab because a roadmapped capability and a capability that does not
   * exist look identical when both are absent — a customer cannot tell that SaaS coverage is coming, and
   * asks for it as if it were missing.
   *
   * The placeholder must never imply data. It says what the tab will do and that it is not live yet; it
   * does not render empty tables or zeroed counters, which would read as "we looked and found nothing".
   */
  comingSoon?: boolean;
  /**
   * One sentence shown on the placeholder, describing what the tab will cover.
   * Only meaningful alongside {@link comingSoon}.
   */
  comingSoonBlurb?: string;
}

export const XAMSECURE_TABS: XamSecureTabDef[] = [
  // ── CNAPP (Upwind) ──
  { id: 'overview', label: 'Home', icon: 'fas fa-house', group: 'CNAPP' },
  { id: 'issues', label: 'Issues', icon: 'fas fa-triangle-exclamation', group: 'CNAPP' },
  { id: 'inventory', label: 'Inventory', icon: 'fas fa-boxes-stacked', group: 'CNAPP' },
  { id: 'attack-surface', label: 'Attack Surface', icon: 'fas fa-globe', group: 'CNAPP' },
  { id: 'vulnerabilities', label: 'Vulnerabilities', icon: 'fas fa-bug', group: 'CNAPP' },
  { id: 'posture', label: 'Configurations', icon: 'fas fa-clipboard-check', group: 'CNAPP' },
  { id: 'api-security', label: 'APIs', icon: 'fas fa-plug', group: 'CNAPP' },
  { id: 'identities', label: 'Identities', icon: 'fas fa-user-shield', group: 'CNAPP' },
  { id: 'threats', label: 'Threats', icon: 'fas fa-bolt', group: 'CNAPP' },
  // "Packages", not "Components". Upwind's Components area is sensor and agent fleet
  // management (Sensors / Cloud Scanners / Integrations / Workflows, with health and coverage);
  // this tab is a software bill of materials. Sharing their label implied parity with a feature
  // we cannot build — nine candidate sensor endpoints all return 404, so there is no API behind
  // their version.
  { id: 'components', label: 'Packages', icon: 'fas fa-cubes', group: 'CNAPP' },
  // Upwind's Code area: CI scan history from the shift-left stream. Org-level data, so the tab
  // renders a withheld state for clients that do not own the whole Upwind organization.
  { id: 'code', label: 'Code', icon: 'fas fa-code-branch', group: 'CNAPP' },
  { id: 'workflows', label: 'Workflows', icon: 'fas fa-diagram-project', group: 'CNAPP', sidebar: false },
  { id: 'onboarding', label: 'Onboarding', icon: 'fas fa-plug-circle-plus', group: 'CNAPP', sidebar: false },

  // ── DSPM (Matters.ai) ──
  // Cloud mirrors the vendor's own Cloud screen: cloud datastores plus self-hosted ones, which Matters
  // lists together (its Cloud view totals 68 where the API's `cloud` category counts 67 — the extra row
  // is a self-hosted Postgres).
  { id: 'dspm-cloud', label: 'Cloud Data', icon: 'fas fa-database', group: 'DSPM' },
  // SaaS and Endpoint are real categories in the same API and are genuinely on the way, so they are
  // announced rather than hidden. Both are marked coming-soon instead of being wired up now because
  // their data belongs with seat and device management, and where they finally live is still open.
  {
    id: 'dspm-saas', label: 'SaaS Data', icon: 'fas fa-cloud', group: 'DSPM',
    comingSoon: true,
    comingSoonBlurb: 'Sensitive data discovered across SaaS accounts — Google Workspace, Microsoft 365, '
      + 'GitLab, Jira and Confluence — with per-account record counts and risk.',
  },
  {
    id: 'dspm-endpoint', label: 'Endpoint Data', icon: 'fas fa-laptop', group: 'DSPM',
    comingSoon: true,
    comingSoonBlurb: 'Sensitive data found on managed endpoints, with device inventory, agent health '
      + 'and per-device findings.',
  },
  // Connection state. Reachable but not in the nav: it is only interesting when something is wrong,
  // and the Cloud tab links to it in that case.
  { id: 'dspm-status', label: 'Connection', icon: 'fas fa-circle-nodes', group: 'DSPM', sidebar: false },
];

/**
 * Tab definition for an id, or undefined for an unknown one.
 *
 * Accepts null as well as undefined so it composes with {@link firstTabForGroups}, which returns null
 * when a client has no groups — otherwise every call site would have to coalesce first.
 */
export const tabDef = (tab: string | null | undefined): XamSecureTabDef | undefined =>
  XAMSECURE_TABS.find((t) => t.id === tab);

/**
 * Whether a tab is announced but not yet built.
 *
 * Read by the page to decide between real content and a placeholder, and by the landing-tab helper so a
 * client is never dropped onto an unbuilt tab as their first impression.
 */
export const isComingSoon = (tab: string | null | undefined): boolean =>
  Boolean(tabDef(tab)?.comingSoon);

/** Nav entries, in declared order. */
export const XAMSECURE_SIDEBAR_TABS = XAMSECURE_TABS.filter((t) => t.sidebar !== false);

export const VALID_XAMSECURE_TABS = new Set<string>(XAMSECURE_TABS.map((t) => t.id));

/** Display names for the group headings in the nav. */
export const XAMSECURE_GROUP_LABELS: Record<XamSecureGroup, string> = {
  CNAPP: 'CNAPP',
  DSPM: 'DSPM',
};

/** Nav order of the groups. */
export const XAMSECURE_GROUP_ORDER: XamSecureGroup[] = ['CNAPP', 'DSPM'];

/** Sidebar-visible tabs for one group. */
export const sidebarTabsForGroup = (group: XamSecureGroup): XamSecureTabDef[] =>
  XAMSECURE_SIDEBAR_TABS.filter((t) => t.group === group);

/** The group a tab id belongs to, or null for an unknown id. */
export const groupForTab = (tab: string | null | undefined): XamSecureGroup | null =>
  XAMSECURE_TABS.find((t) => t.id === tab)?.group ?? null;

/**
 * Landing tab for a client, given the groups they are entitled to.
 *
 * Needed because `/xamsecure` used to redirect unconditionally to `/xamsecure/overview`, which is a
 * CNAPP tab. A DSPM-only customer following that redirect would land on an Upwind page they have no
 * data for and no way to leave except by guessing a URL.
 *
 * Returns null when no group is available, which the caller renders as "no security products
 * enabled" rather than redirecting into an empty tab.
 */
export const firstTabForGroups = (groups: XamSecureGroup[]): XamSecureTab | null => {
  for (const group of XAMSECURE_GROUP_ORDER) {
    if (!groups.includes(group)) continue;
    // Skips coming-soon tabs. Landing a customer on a placeholder as the first thing they see would be
    // a poor introduction to a product they are paying for, even though the tab is legitimately listed.
    const first = sidebarTabsForGroup(group).find((t) => !t.comingSoon);
    if (first) return first.id;
  }
  return null;
};
