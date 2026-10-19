/**
 * Demo Mode Configuration
 *
 * When a user whose email matches one of the DEMO_EMAILS list logs in,
 * the frontend stores demo_mode + demo_role in sessionStorage and all
 * subsequent API calls are intercepted to return static mock data.
 *
 * To add / change demo users: update DEMO_EMAILS below and ensure the
 * corresponding backend accounts exist with the correct role.
 */

export const DEMO_EMAILS: Record<string, 'ROLE_XAMOPS' | 'ROLE_BILLOPS_ADMIN'> = {
  'demo.xamops@xammer.in': 'ROLE_XAMOPS',
  'demo.billops@xammer.in': 'ROLE_BILLOPS_ADMIN',
};

export const DEMO_MODE_KEY = 'demo_mode';
export const DEMO_ROLE_KEY = 'demo_role';

/**
 * The host that always runs in demo mode. Any user who signs in on this host
 * (with any email, via Google/Microsoft) is shown the shared read-only demo
 * estate — demo mode is not gated by the DEMO_EMAILS allowlist there.
 */
const DEMO_HOST = 'demo.xamops.com';

/**
 * Returns true when the current browser host is the demo host.
 *
 * Honours a `VITE_DEMO_HOST` build-time override (so the demo behaviour can be
 * exercised on a preview/staging hostname) and otherwise compares the live
 * hostname to {@link DEMO_HOST}. The `import.meta.env` read is wrapped in a
 * try/catch so this module still compiles and runs in non-Vite contexts
 * (e.g. the vitest/jsdom test runner, which does not define `import.meta.env`).
 */
export function isDemoHost(): boolean {
  let override: string | undefined;
  try {
    const raw = (import.meta as any)?.env?.VITE_DEMO_HOST;
    if (typeof raw === 'string' && raw.trim()) override = raw.trim();
  } catch {
    // import.meta.env not available (non-Vite runtime) — fall through to the default host.
  }
  if (override) return window.location.hostname === override;
  return window.location.hostname === DEMO_HOST;
}

/** Call this after the user profile is loaded to activate demo mode if applicable. */
export function activateDemoModeIfNeeded(email: string | undefined): void {
  if (!email) return;
  // On the demo host every signed-in user sees the demo estate, so the role is
  // forced to ROLE_XAMOPS regardless of email. On the live host we fall back to
  // the existing DEMO_EMAILS allowlist mapping.
  const hostForced = isDemoHost();
  const role = hostForced ? 'ROLE_XAMOPS' : DEMO_EMAILS[email.toLowerCase()];
  if (role) {
    // Wipe any stale localStorage caches (e.g. DbOps 12-hour cache) so the
    // demo always starts fresh and shows our mock data, not old null values.
    localStorage.clear();
    sessionStorage.setItem(DEMO_MODE_KEY, 'true');
    sessionStorage.setItem(DEMO_ROLE_KEY, role);
  }
}

/** Returns true when the current session is a demo session. */
export function isDemoMode(): boolean {
  return sessionStorage.getItem(DEMO_MODE_KEY) === 'true';
}

/** Returns the role of the current demo user, or null if not in demo mode. */
export function getDemoRole(): 'ROLE_XAMOPS' | 'ROLE_BILLOPS_ADMIN' | null {
  if (!isDemoMode()) return null;
  const role = sessionStorage.getItem(DEMO_ROLE_KEY);
  if (role === 'ROLE_XAMOPS' || role === 'ROLE_BILLOPS_ADMIN') return role;
  return null;
}
