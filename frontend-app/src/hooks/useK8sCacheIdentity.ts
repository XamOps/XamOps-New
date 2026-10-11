/**
 * Keeps the CloudK8s module caches scoped to who is looking (docs/cloudk8s/ui-revamp-design.md §15):
 * clears them when the selected account, the profile email (AuthContext.userProfile.email) or the
 * impersonated user id (sessionStorage.impersonateUserId) changes. Mount it once per CloudK8s page.
 */
import { useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { useAccounts } from '../contexts/AccountContext';
import { IMPERSONATE_STORAGE_KEY, syncK8sCacheIdentity } from '../utils/k8sCache';

export function useK8sCacheIdentity(): void {
  const { userProfile } = useAuth();
  const { selectedAccountIds } = useAccounts();
  const email = userProfile?.email ?? null;
  const accountKey = selectedAccountIds.length ? [...selectedAccountIds].sort().join(',') : null;
  // sessionStorage changes don't re-render; reading on every render catches the next navigation,
  // and cache keys read it at access time anyway, so a stale entry is never served meanwhile.
  const impersonateUserId = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(IMPERSONATE_STORAGE_KEY) : null;

  useEffect(() => {
    syncK8sCacheIdentity({ email, accountKey, impersonateUserId: impersonateUserId || null });
  }, [email, accountKey, impersonateUserId]);
}

export default useK8sCacheIdentity;
