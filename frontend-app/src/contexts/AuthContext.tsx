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

interface AuthContextValue {
  userRoles: UserRole[];
  userProfile: UserProfile | null;
  isLoading: boolean;

  // Super admin features
  tenantList: Tenant[];
  tenantUsers: TenantUser[];
  tenantAccounts: Account[];
  selectedTenant: string;
  impersonateUser: string;
  selectedAccountId: string;
  environment: 'local' | 'uat' | 'prod';

  // Methods
  fetchUserRoles: () => Promise<void>;
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
  // Load from cache immediately to prevent flicker
  const [userRoles, setUserRoles] = useState<UserRole[]>(() => {
    const cached = sessionStorage.getItem('cached_user_roles');
    return cached ? JSON.parse(cached) : [];
  });

  const [userProfile, setUserProfile] = useState<UserProfile | null>(null);
  const [isLoading, setIsLoading] = useState(false);

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
  const fetchUserRoles = useCallback(async () => {
    const path = globalThis.location.pathname;
    const isBillopsPage = path.includes('/billops/');
    const primaryEndpoint = isBillopsPage ? '/api/billops/profile' : '/api/xamops/user/profile';
    const fallbackEndpoint = isBillopsPage ? '/api/xamops/user/profile' : '/api/billops/profile';

    console.log('🔍 Fetching User Roles...');
    console.log('  📍 Path:', path);
    console.log('  🌐 Is BillOps Page:', isBillopsPage);
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
        // For regular users: read their tenant from the profile response if available.
        // The backend may return tenantId so the frontend can route requests correctly.
        const tenantId = (data as any).tenantId || (data as any).tenant_id;
        if (tenantId) {
          sessionStorage.setItem('selectedTenantId', tenantId);
          setSelectedTenant(tenantId);
        } else if (!sessionStorage.getItem('selectedTenantId')) {
          sessionStorage.removeItem('selectedTenantId');
          setSelectedTenant('');
        }
        // Always clear super-admin-only keys for regular users
        sessionStorage.removeItem('impersonateUserId');
        sessionStorage.removeItem('superAdminSelectedAccountId');
        setImpersonateUser('');
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
          // For regular users: read their tenant from the profile response if available.
          const tenantId = (data as any).tenantId || (data as any).tenant_id;
          if (tenantId) {
            sessionStorage.setItem('selectedTenantId', tenantId);
            setSelectedTenant(tenantId);
          } else if (!sessionStorage.getItem('selectedTenantId')) {
            sessionStorage.removeItem('selectedTenantId');
            setSelectedTenant('');
          }
          // Always clear super-admin-only keys for regular users
          sessionStorage.removeItem('impersonateUserId');
          sessionStorage.removeItem('superAdminSelectedAccountId');
          setImpersonateUser('');
          setSelectedAccountId('');
        }
      } catch (error_) {
        console.log('ℹ️ User not authenticated — no active session found. Login required.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [fetchTenants, fetchTenantUsers]);

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

  // Check for impersonateUserId and tenantId in URL query parameters
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const userId = params.get('impersonateUserId');
    const tenantId = params.get('tenantId');

    if (userId || tenantId) {
      console.log('🔄 Detected impersonation/tenant context in URL:', { userId, tenantId });

      // Clear existing context to ensure clean isolation
      if (userId) {
        sessionStorage.removeItem('impersonateUserId');
        sessionStorage.setItem('impersonateUserId', userId);
        setImpersonateUser(userId);
      }

      if (tenantId) {
        sessionStorage.removeItem('selectedTenantId');
        sessionStorage.setItem('selectedTenantId', tenantId);
        setSelectedTenant(tenantId);
      }

      // Clear stale account cache so AccountContext re-fetches with the
      // correct impersonation header after reload instead of showing the
      // previous user's (admin's) accounts.
      sessionStorage.removeItem('cached_accounts');
      sessionStorage.removeItem('selectedAccountIds');

      // Clean up the URL
      const newUrl = window.location.pathname;
      window.history.replaceState({}, '', newUrl);

      // Refresh to apply context
      window.location.reload();
    }
  }, []);

  const value: AuthContextValue = React.useMemo(() => ({
    userRoles,
    userProfile,
    isLoading,
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
