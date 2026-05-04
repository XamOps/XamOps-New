/**
 * Authentication Context
 *
 * Manages user roles, profile, and tenant/impersonation state
 * Converted from: _sidebar.html (userRoles, tenant management logic)
 */

import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import apiClient from '../utils/apiClient';
import { UserRole, UserProfile, Tenant, TenantUser } from '../types/auth';
import { Account } from '../types/account';
import { activateDemoModeIfNeeded } from '../demo/demoConfig';

interface AuthContextValue {
  userRoles: UserRole[];
  userProfile: UserProfile | null;
  isLoading: boolean;
  /** True once fetchUserRoles has completed and sessionStorage is in a clean state.
   *  AccountContext waits for this before firing fetchAccounts so it never reads
   *  stale impersonateUserId / selectedTenantId values. */
  authReady: boolean;

  // Super admin features
  tenantList: Tenant[];
  tenantUsers: TenantUser[];
  tenantAccounts: Account[];
  selectedTenant: string;
  impersonateUser: string;
  selectedAccountId: string;
  environment: 'local' | 'uat' | 'prod';

  // Methods
  fetchUserRoles: (billopsHint?: boolean) => Promise<void>;
  hasAnyRole: (roles: UserRole | UserRole[]) => boolean;
  switchTenant: (tenantId: string) => void;
  switchUser: (userId: string) => void;
  switchAccount: (accountId: string) => void;
  resetContext: () => void;
  fetchTenants: () => Promise<void>;
  fetchTenantUsers: (tenantId: string) => Promise<void>;
  fetchTenantAccounts: (userId: string, tenantId?: string) => Promise<void>;
  fetchEnvironment: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export const AuthProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [userRoles, setUserRoles] = useState<UserRole[]>([]);

  const [userProfile, setUserProfile] = useState<UserProfile | null>(null);
  // Start as true so components wait for the real fetch before rendering role-gated UI.
  // This prevents a stale `cached_user_roles` left by a previous user in the same tab
  // from briefly rendering the wrong dashboard when a different user logs in.
  const [isLoading, setIsLoading] = useState(true);
  // Becomes true once fetchUserRoles completes (success or failure).
  // AccountContext waits for this before calling fetchAccounts so it never
  // reads stale impersonateUserId / selectedTenantId from a previous session.
  const [authReady, setAuthReady] = useState(false);

  const [tenantList, setTenantList] = useState<Tenant[]>([]);
  const [tenantUsers, setTenantUsers] = useState<TenantUser[]>([]);
  const [tenantAccounts, setTenantAccounts] = useState<Account[]>([]);
  const [environment, setEnvironment] = useState<'local' | 'uat' | 'prod'>('local');

  const [selectedTenant, setSelectedTenant] = useState<string>(
    () => sessionStorage.getItem('selectedTenantId') || ''
  );
  const [impersonateUser, setImpersonateUser] = useState<string>(
    () => sessionStorage.getItem('impersonateUserId') || ''
  );
  const [selectedAccountId, setSelectedAccountId] = useState<string>(
    () => sessionStorage.getItem('superAdminSelectedAccountId') || ''
  );

  // Initialization guard to prevent double mounting in React Strict Mode
  const initializedRef = React.useRef(false);

  // Redirect to login when a mid-session 401 is detected (session expired server-side)
  useEffect(() => {
    const handleSessionExpired = () => {
      sessionStorage.clear();
      globalThis.location.href = '/login';
    };
    window.addEventListener('session-expired', handleSessionExpired);
    return () => window.removeEventListener('session-expired', handleSessionExpired);
  }, []);

  /**
   * Fetch environment info (super admin only)
   */
  const fetchEnvironment = useCallback(async () => {
    try {
      const data = await apiClient.get<{ environment: 'local' | 'uat' | 'prod' }>('/api/xamops/superadmin/environment');
      setEnvironment(data.environment);
    } catch (e) {
      console.error('Failed to load environment info', e);
    }
  }, []);

  /**
   * Fetch tenants (super admin only)
   */
  const fetchTenants = useCallback(async () => {
    try {
      const data = await apiClient.get<Tenant[]>('/api/xamops/superadmin/tenants');
      setTenantList(data);
    } catch (e) {
      console.error('Failed to load tenants', e);
    }
  }, []);

  /**
   * Fetch tenant users (super admin only)
   */
  const fetchTenantUsers = useCallback(async (tenantId: string) => {
    try {
      const data = await apiClient.get<TenantUser[]>(`/api/xamops/superadmin/users?tenantId=${tenantId}`);
      setTenantUsers(data);
    } catch (e) {
      console.error('Failed to load tenant users', e);
    }
  }, []);

  /**
   * Fetch tenant accounts (super admin only)
   */
  const fetchTenantAccounts = useCallback(async (userId: string, tenantId?: string) => {
    try {
      const url = tenantId
        ? `/api/xamops/superadmin/accounts-by-user/${userId}?tenantId=${tenantId}`
        : `/api/xamops/superadmin/accounts-by-user/${userId}`;
      const data = await apiClient.get<Account[]>(url);
      setTenantAccounts(data);
    } catch (e) {
      console.error('Failed to load tenant accounts', e);
    }
  }, []);

  /**
   * Fetch user roles and profile
   * Uses same logic as original: tries primary endpoint, falls back to secondary
   */
  const fetchUserRoles = useCallback(async (billopsHint?: boolean) => {
    const path = globalThis.location.pathname;
    // Prefer billops endpoint when:
    // - the current route is a billops page, OR
    // - in-memory roles already show a billops user (avoids wrong endpoint after SPA navigation), OR
    // - the caller passes an explicit hint (used by LoginForm based on the backend's redirectUrl).
    const isBillopsPage = path.includes('/billops');
    const isBillopsUser = userRoles.some(r => r.startsWith('ROLE_BILLOPS'));
    const preferBillops = isBillopsPage || isBillopsUser || (billopsHint === true);
    const primaryEndpoint = preferBillops ? '/api/billops/profile' : '/api/xamops/user/profile';
    const fallbackEndpoint = preferBillops ? '/api/xamops/user/profile' : '/api/billops/profile';

    console.log('🔍 Fetching User Roles...');
    console.log('  📍 Path:', path);
    console.log('  🌐 Is BillOps Page / User:', preferBillops);
    console.log('  🎯 Primary Endpoint:', primaryEndpoint);

    setIsLoading(true);

    try {
      // Try primary endpoint
      const data = await apiClient.get<UserProfile>(primaryEndpoint);
      console.log('✅ User Profile Data (Primary):', JSON.stringify(data, null, 2));

      const roles = data.roles || data.authorities || [];
      console.log('🔐 Extracted Roles:', roles);

      setUserRoles(roles);
      setUserProfile(data);
      sessionStorage.setItem('cached_user_roles', JSON.stringify(roles));
      activateDemoModeIfNeeded(data.email);

      // If super admin, fetch tenants and environment
      if (roles.includes('ROLE_SUPER_ADMIN')) {
        await fetchEnvironment();
        await fetchTenants();
        const currentTenant = sessionStorage.getItem('selectedTenantId') || '';
        const currentImpersonateUser = sessionStorage.getItem('impersonateUserId') || '';
        if (currentTenant) {
          await fetchTenantUsers(currentTenant);
        }
        if (currentImpersonateUser) {
          await fetchTenantAccounts(currentImpersonateUser, currentTenant);
        }
      } else {
        // For regular users: restore tenant from profile. Always overwrite any stale
        // value (e.g. a client name injected by the old Login-As popup bug).
        const tenantId = (data as any).tenantId || (data as any).tenant_id;
        if (tenantId) {
          sessionStorage.setItem('selectedTenantId', tenantId);
          setSelectedTenant(tenantId);
        } else {
          sessionStorage.removeItem('selectedTenantId');
          setSelectedTenant('');
        }
        // Clear super-admin-only keys for regular users, EXCEPT during a billops
        // "Login as" session — impersonateUserId must be kept so the backend
        // returns only that client's accounts (not all accounts).
        if (sessionStorage.getItem('billops_impersonation') !== 'true') {
          sessionStorage.removeItem('impersonateUserId');
          setImpersonateUser('');
        }
        sessionStorage.removeItem('superAdminSelectedAccountId');
        setSelectedAccountId('');
      }
    } catch (err) {
      console.log(`ℹ️ No profile at ${primaryEndpoint} (not authenticated or not a ${isBillopsPage ? 'billops' : 'xamops'} session), trying fallback...`);

      // Try fallback endpoint
      try {
        const data = await apiClient.get<UserProfile>(fallbackEndpoint);
        console.log('✅ User Profile Data (Fallback):', JSON.stringify(data, null, 2));

        const roles = data.roles || data.authorities || [];
        console.log('🔐 Extracted Roles (Fallback):', roles);

        setUserRoles(roles);
        setUserProfile(data);
        sessionStorage.setItem('cached_user_roles', JSON.stringify(roles));
        activateDemoModeIfNeeded(data.email);

        if (roles.includes('ROLE_SUPER_ADMIN')) {
          await fetchEnvironment();
          await fetchTenants();
          const currentTenant = sessionStorage.getItem('selectedTenantId') || '';
          const currentImpersonateUser = sessionStorage.getItem('impersonateUserId') || '';
          if (currentTenant) {
            await fetchTenantUsers(currentTenant);
          }
          if (currentImpersonateUser) {
            await fetchTenantAccounts(currentImpersonateUser, currentTenant);
          }
        } else {
          // For regular users: restore tenant from profile (overwrite any stale value).
          const tenantId = (data as any).tenantId || (data as any).tenant_id;
          if (tenantId) {
            sessionStorage.setItem('selectedTenantId', tenantId);
            setSelectedTenant(tenantId);
          } else {
            sessionStorage.removeItem('selectedTenantId');
            setSelectedTenant('');
          }
          // Clear super-admin-only keys for regular users, EXCEPT during a billops
          // "Login as" session — impersonateUserId must be kept so the backend
          // returns only that client's accounts (not all accounts).
          if (sessionStorage.getItem('billops_impersonation') !== 'true') {
            sessionStorage.removeItem('impersonateUserId');
            setImpersonateUser('');
          }
          sessionStorage.removeItem('superAdminSelectedAccountId');
          setSelectedAccountId('');
        }
      } catch (error_) {
        console.log('ℹ️ User not authenticated — no active session found. Login required.');
      }
    } finally {
      setIsLoading(false);
      // Signal that auth is done and sessionStorage values are now trustworthy.
      // AccountContext uses this to delay fetchAccounts until after cleanup.
      setAuthReady(true);
    }
  }, [fetchTenants, fetchTenantUsers, userRoles]);

  /**
   * Check if user has any of the specified roles
   */
  const hasAnyRole = useCallback(
    (roles: UserRole | UserRole[]): boolean => {
      const rolesToCheck = Array.isArray(roles) ? roles : [roles];
      return rolesToCheck.some(role => userRoles.includes(role));
    },
    [userRoles]
  );

  const switchTenant = useCallback((tenantId: string) => {
    if (tenantId) {
      console.log('🏢 Switching Tenant (Soft):', tenantId);
      sessionStorage.setItem('selectedTenantId', tenantId);
      sessionStorage.removeItem('impersonateUserId');
      sessionStorage.removeItem('superAdminSelectedAccountId');
      sessionStorage.removeItem('selectedAccountIds');
      sessionStorage.removeItem('cached_accounts');

      setSelectedTenant(tenantId);
      setImpersonateUser('');
      setSelectedAccountId('');
      setTenantUsers([]);
      setTenantAccounts([]);

      // Notify other contexts to refresh
      globalThis.dispatchEvent(new CustomEvent('account-changed', {
        detail: { tenantId, reason: 'tenant-switch' }
      }));
    } else {
      resetContext();
    }
  }, []);

  /**
   * Switch user / impersonate (super admin only)
   */
  const switchUser = useCallback((userId: string) => {
    if (userId) {
      console.log('👤 Impersonating User (Soft):', userId);
      sessionStorage.setItem('impersonateUserId', userId);
      sessionStorage.removeItem('superAdminSelectedAccountId');
      sessionStorage.removeItem('selectedAccountIds');
      sessionStorage.removeItem('cached_accounts');

      setImpersonateUser(userId);
      setSelectedAccountId('');
      setTenantAccounts([]);

      // Notify other contexts to refresh
      globalThis.dispatchEvent(new CustomEvent('account-changed', {
        detail: { userId, reason: 'user-switch' }
      }));
    }
  }, []);

  /**
   * Switch account (super admin only)
   */
  const switchAccount = useCallback((accountId: string) => {
    if (accountId) {
      console.log('💳 Switching Account (Soft):', accountId);
      sessionStorage.setItem('superAdminSelectedAccountId', accountId);
      // Also update the main account context selection
      sessionStorage.setItem('selectedAccountIds', JSON.stringify([accountId]));

      setSelectedAccountId(accountId);

      // Notify other contexts to refresh
      globalThis.dispatchEvent(new CustomEvent('account-changed', {
        detail: { accountId, reason: 'account-switch' }
      }));
    }
  }, []);

  /**
   * Reset tenant context
   */
  const resetContext = useCallback(() => {
    console.log('🔄 Resetting Super Admin Context (Soft)');
    sessionStorage.removeItem('selectedTenantId');
    sessionStorage.removeItem('impersonateUserId');
    sessionStorage.removeItem('superAdminSelectedAccountId');
    sessionStorage.removeItem('cached_user_roles');
    sessionStorage.removeItem('selectedAccountIds');
    sessionStorage.removeItem('cached_accounts');
    sessionStorage.removeItem('billops_impersonation');
    sessionStorage.removeItem('allowedAccountIds');

    setSelectedTenant('');
    setImpersonateUser('');
    setSelectedAccountId('');
    setTenantUsers([]);
    setTenantAccounts([]);

    // Notify other contexts to refresh
    globalThis.dispatchEvent(new CustomEvent('account-changed', {
      detail: { reason: 'context-reset' }
    }));
  }, []);

  /**
   * Centralized logout
   */
  const logout = useCallback(async () => {
    try {
      console.log('🚪 Logging out...');
      await apiClient.post('/logout', {});
    } catch (err) {
      console.error('Logout API failed:', err);
    } finally {
      // Clear all session storage
      console.log('🧹 Clearing session storage...');
      sessionStorage.clear();

      // Reset state
      setUserRoles([]);
      setUserProfile(null);

      // Redirect to landing page
      globalThis.location.href = '/';
    }
  }, []);

  // Fetch user roles on mount - with guard for React Strict Mode
  useEffect(() => {
    // Guard against double initialization
    if (initializedRef.current) {
      return;
    }
    initializedRef.current = true;

    fetchUserRoles();
  }, []); // Empty dependency array - only run on mount

  // Handle billops "Login As" JWT passed in URL hash: /unified-dashboard#imp=<token>
  // The JWT never hits the server as a query param, so the admin's JSESSIONID is
  // never overwritten. We decode the payload client-side only to extract tenant/account
  // context — the backend re-validates the full signature on every API request.
  useEffect(() => {
    const hash = window.location.hash;
    if (!hash.startsWith('#imp=')) return;

    const token = hash.slice(5); // strip "#imp="
    try {
      // Decode JWT payload (middle segment) — no verification, just context extraction
      const payloadB64 = token.split('.')[1];
      const claims = JSON.parse(atob(payloadB64.replace(/-/g, '+').replace(/_/g, '/')));

      sessionStorage.setItem('impersonation_jwt', token);
      sessionStorage.setItem('billops_impersonation', 'true');

      if (claims.sub) {
        sessionStorage.setItem('impersonateUserId', claims.sub);
        setImpersonateUser(claims.sub);
      }
      if (claims.tenantId) {
        sessionStorage.setItem('selectedTenantId', claims.tenantId);
        setSelectedTenant(claims.tenantId);
      }
      if (claims.allowedAccountIds) {
        sessionStorage.setItem('allowedAccountIds', claims.allowedAccountIds);
      } else {
        sessionStorage.removeItem('allowedAccountIds');
      }

      // Clear stale account cache so AccountContext re-fetches for this user
      sessionStorage.removeItem('cached_accounts');
      sessionStorage.removeItem('selectedAccountIds');

      // Remove the token from the address bar before reloading
      window.history.replaceState({}, '', window.location.pathname);
      window.location.reload();
    } catch (e) {
      console.error('Failed to parse impersonation JWT from hash', e);
      window.history.replaceState({}, '', window.location.pathname);
    }
  }, []);

  const value: AuthContextValue = React.useMemo(() => ({
    userRoles,
    userProfile,
    isLoading,
    authReady,
    tenantList,
    tenantUsers,
    tenantAccounts,
    selectedTenant,
    impersonateUser,
    selectedAccountId,
    environment,
    fetchUserRoles,
    hasAnyRole,
    switchTenant,
    switchUser,
    switchAccount,
    resetContext,
    fetchTenants,
    fetchTenantUsers,
    fetchTenantAccounts,
    fetchEnvironment,
    logout,
  }), [
    userRoles,
    userProfile,
    isLoading,
    authReady,
    tenantList,
    tenantUsers,
    tenantAccounts,
    selectedTenant,
    impersonateUser,
    selectedAccountId,
    environment,
    fetchUserRoles,
    hasAnyRole,
    switchTenant,
    switchUser,
    switchAccount,
    resetContext,
    fetchTenants,
    fetchTenantUsers,
    fetchTenantAccounts,
    fetchEnvironment,
    logout,
  ]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

/**
 * Custom hook to use auth context
 */
export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
};
