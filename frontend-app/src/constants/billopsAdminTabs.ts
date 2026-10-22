/**
 * BillOps admin sidebar section catalog.
 *
 * Canonical source of truth for the tab keys that scope a BillOps admin user's navigation.
 * The keys are stored as strings in the shared `user_tab_access` table — the same table that
 * holds XamOps tab keys — and are consumed in four places:
 *   1. BillOps admin user manager — selecting sections when creating/editing an admin user
 *   2. BillOps admin "My Team" page — selecting sections when creating an admin sub-user
 *   3. Sidebar — gating which admin nav items render
 *   4. App's DashboardGuard — landing a restricted admin on a section they can actually open
 *
 * Semantics, identical to constants/xamopsTabs.ts: an admin with NO assigned sections has NO
 * restriction (full admin sidebar). An admin with one or more assigned sections sees only those.
 *
 * The direction of that rule is the thing to watch when editing this file. Because a user with
 * any row is restricted to exactly their rows, a key added here is missing from every selection
 * saved before it — so a new section is invisible to already-restricted admins until someone
 * re-saves them (or a migration grants it, the way V20260929_2 did for secops-xamlens). Adding a
 * key is therefore a fail-closed change for existing restricted users and a no-op for everyone
 * else.
 *
 * The groupings below are presentation only: nothing is stored per module, and a module is
 * "allowed" when any section inside it is. They exist so the picker reads as a short list of
 * areas rather than fourteen loose checkboxes.
 */

export interface BillOpsAdminTab {
  key: string;
  label: string;
  icon: string;
  /** React route the section opens, used by the post-login redirect. */
  route: string;
}

export interface BillOpsAdminModule {
  key: string;
  label: string;
  icon: string;
  tabs: BillOpsAdminTab[];
}

export const BILLOPS_ADMIN_MODULES: BillOpsAdminModule[] = [
  {
    key: 'overview',
    label: 'Overview',
    icon: 'mdi:view-dashboard-outline',
    tabs: [
      { key: 'admin-dashboard', label: 'Admin Dashboard', icon: 'mdi:chart-bar', route: '/billops-admin-dashboard' },
    ],
  },
  {
    key: 'billing',
    label: 'Billing & Credits',
    icon: 'mdi:currency-usd',
    tabs: [
      { key: 'all-invoices', label: 'Billing', icon: 'mdi:file-document-outline', route: '/billops-admin-invoices' },
      { key: 'credit-requests', label: 'Credit Requests', icon: 'mdi:cash-multiple', route: '/billops-admin-credits' },
      { key: 'historical-billing', label: 'Historical Billing', icon: 'mdi:history', route: '/billops-admin-historical-billing' },
    ],
  },
  {
    key: 'support',
    label: 'Support',
    icon: 'mdi:headset',
    tabs: [
      { key: 'support-inbox', label: 'Chat', icon: 'mdi:message-text-outline', route: '/billops-support-dashboard' },
      { key: 'all-tickets', label: 'Tickets', icon: 'mdi:ticket-outline', route: '/billops-admin-tickets' },
    ],
  },
  {
    key: 'clients',
    label: 'Clients',
    icon: 'mdi:account-group-outline',
    tabs: [
      { key: 'client-details', label: 'Accounts', icon: 'mdi:account-cog-outline', route: '/billops-client-details' },
      { key: 'client-intake', label: 'Client Intake', icon: 'mdi:clipboard-list-outline', route: '/billops-client-intake' },
      { key: 'demo-users', label: 'Demo Users', icon: 'mdi:account-clock-outline', route: '/billops-admin-demo-users' },
      { key: 'agreement-admin', label: 'Agreements', icon: 'mdi:file-sign', route: '/billops-admin-agreements' },
    ],
  },
  {
    key: 'platform',
    label: 'Platform Admin',
    icon: 'mdi:cog-outline',
    tabs: [
      { key: 'autospotting-admin', label: 'AutoSpotting', icon: 'mdi:auto-fix', route: '/autospotting-admin' },
      { key: 'dspm-admin', label: 'XamLens', icon: 'mdi:database-eye-outline', route: '/dspm-admin' },
      { key: 'user-manager', label: 'User Manager', icon: 'mdi:account-settings-outline', route: '/billops-admin-user-manager' },
    ],
  },
];

/** Flat list of every BillOps admin section, in picker order. */
export const ALL_BILLOPS_ADMIN_TABS: BillOpsAdminTab[] =
  BILLOPS_ADMIN_MODULES.flatMap(m => m.tabs);

/** Flat list of every BillOps admin section key. */
export const ALL_BILLOPS_ADMIN_TAB_KEYS: string[] = ALL_BILLOPS_ADMIN_TABS.map(t => t.key);

/** Section key → React route, for the post-login redirect. */
export const BILLOPS_ADMIN_TAB_ROUTES: Record<string, string> =
  Object.fromEntries(ALL_BILLOPS_ADMIN_TABS.map(t => [t.key, t.route]));

/**
 * The order the post-login redirect tries sections in. Picker order, so a restricted admin lands
 * on the highest-level area they hold rather than whichever key happens to sort first.
 */
export const BILLOPS_ADMIN_TAB_ORDER: string[] = ALL_BILLOPS_ADMIN_TAB_KEYS;

/**
 * Given an admin's assigned section keys, return a gate.
 * An empty/undefined list is NO restriction — everything is allowed.
 *
 * Mirrors makeXamTabGate so the two sidebars behave the same way.
 */
export const makeBillopsTabGate = (allowedTabs: string[] | null | undefined) => {
  const restricted = Array.isArray(allowedTabs) && allowedTabs.length > 0;
  const allowedSet = new Set(allowedTabs ?? []);
  const isTabAllowed = (key: string): boolean => !restricted || allowedSet.has(key);
  const isModuleAllowed = (moduleKey: string): boolean => {
    if (!restricted) return true;
    const mod = BILLOPS_ADMIN_MODULES.find(m => m.key === moduleKey);
    if (!mod) return true;
    return mod.tabs.some(t => allowedSet.has(t.key));
  };
  return { restricted, isTabAllowed, isModuleAllowed };
};

/** The first section a restricted admin can open, or null when they are unrestricted. */
export const firstAllowedBillopsRoute = (allowedTabs: string[] | null | undefined): string | null => {
  if (!Array.isArray(allowedTabs) || allowedTabs.length === 0) return null;
  const key = BILLOPS_ADMIN_TAB_ORDER.find(k => allowedTabs.includes(k));
  return key ? BILLOPS_ADMIN_TAB_ROUTES[key] : null;
};
