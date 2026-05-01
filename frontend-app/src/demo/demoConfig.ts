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

/** Call this after the user profile is loaded to activate demo mode if applicable. */
export function activateDemoModeIfNeeded(email: string | undefined): void {
  if (!email) return;
  const role = DEMO_EMAILS[email.toLowerCase()];
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
