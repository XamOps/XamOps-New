import { useEffect, useState } from 'react';
import { useAuth } from '../../../contexts/AuthContext';
import { makeXamTabGate } from '../../../constants/xamopsTabs';
import { apiFetchJson } from '../../../utils/apiClient';
import { isXpDemo } from '../../../api/xampulseApi';

/**
 * Tab access for XamPulse. Mirrors how the sidebar resolves it: the user's allowed-tab list from the profile
 * (a user with no list is unrestricted). In demo mode every tab is shown.
 *
 * The profile endpoint (/api/xamops/team/profile) is the same one the sidebar reads. If it fails we fall
 * back to unrestricted, and the server stays authoritative for data access.
 */
export function useXamTabAccess(): { isTabAllowed: (key: string) => boolean } {
  const { hasAnyRole } = useAuth();
  const isXamops = hasAnyRole('ROLE_XAMOPS');
  const [allowed, setAllowed] = useState<string[] | null>(null);
  useEffect(() => {
    if (!isXamops || isXpDemo()) return undefined;
    let cancelled = false;
    apiFetchJson<{ allowedTabs?: string[] }>('/api/xamops/team/profile')
      .then((d) => { if (!cancelled) setAllowed(d?.allowedTabs ?? []); })
      .catch(() => { if (!cancelled) setAllowed([]); });
    return () => { cancelled = true; };
  }, [isXamops]);
  return makeXamTabGate(allowed);
}

/** Admin gate for channels and setup actions. The server stays authoritative. */
export function useIsXpAdmin(): boolean {
  const { hasAnyRole } = useAuth();
  let cached: string[] = [];
  try {
    cached = JSON.parse(window.sessionStorage.getItem('cached_user_roles') ?? '[]') as string[];
  } catch {
    cached = [];
  }
  // Same set as the server's XamPulseRoles.ADMIN. This deployment has a single XamOps product role: the
  // standard ROLE_XAMOPS user is the account operator who onboards instances and selects the log bucket.
  return hasAnyRole(['ROLE_XAMOPS', 'ROLE_SUPER_ADMIN'])
    || cached.includes('ROLE_XAMOPS')
    || isXpDemo();
}
