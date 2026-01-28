/**
 * Authentication Context
 *
 * Manages user roles, profile, and tenant/impersonation state
 * Converted from: _sidebar.html (userRoles, tenant management logic)
 */

import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import apiClient from '../utils/apiClient';
import { UserRole, UserProfile, Tenant, TenantUser } from '../types/auth';

interface AuthContextValue {
  userRoles: UserRole[];
  userProfile: UserProfile | null;
  isLoading: boolean;

  // Super admin features
  tenantList: Tenant[];
  tenantUsers: TenantUser[];
  selectedTenant: string;
  impersonateUser: string;

  // Methods
  fetchUserRoles: () => Promise<void>;
  hasAnyRole: (roles: UserRole | UserRole[]) => boolean;
  switchTenant: (tenantId: string) => void;
  switchUser: (userId: string) => void;
  resetContext: () => void;
  fetchTenants: () => Promise<void>;
  fetchTenantUsers: (tenantId: string) => Promise<void>;
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

  // Super admin state
  const [tenantList, setTenantList] = useState<Tenant[]>([]);
  const [tenantUsers, setTenantUsers] = useState<TenantUser[]>([]);
  const [selectedTenant, setSelectedTenant] = useState<string>(
    () => sessionStorage.getItem('selectedTenantId') || ''
  );
  const [impersonateUser, setImpersonateUser] = useState<string>(
    () => sessionStorage.getItem('impersonateUserId') || ''
  );

  // Initialization guard to prevent double mounting in React Strict Mode
  const initializedRef = React.useRef(false);

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
  const fetchTenantUsers = useCallback(async (_tenantId: string) => {
    try {
      const data = await apiClient.get<TenantUser[]>('/api/xamops/superadmin/users');
      setTenantUsers(data);
    } catch (e) {
      console.error('Failed to load tenant users', e);
    }
  }, []);

  /**
   * Fetch user roles and profile
   * Uses same logic as original: tries primary endpoint, falls back to secondary
   */
  const fetchUserRoles = useCallback(async () => {
    const path = window.location.pathname;
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
      console.log('✅ User Profile Data (Primary):', data);

      const roles = data.roles || data.authorities || [];
      console.log('🔐 Extracted Roles:', roles);

      setUserRoles(roles);
      setUserProfile(data);
      sessionStorage.setItem('cached_user_roles', JSON.stringify(roles));

      // If super admin, fetch tenants
      if (roles.includes('ROLE_SUPER_ADMIN')) {
        await fetchTenants();
        const currentTenant = sessionStorage.getItem('selectedTenantId') || '';
        if (currentTenant) {
          await fetchTenantUsers(currentTenant);
        }
      }
    } catch (err) {
      console.warn(`⚠️ Could not fetch user profile from ${primaryEndpoint}, trying fallback:`, err);

      // Try fallback endpoint
      try {
        const data = await apiClient.get<UserProfile>(fallbackEndpoint);
        console.log('✅ User Profile Data (Fallback):', data);

        const roles = data.roles || data.authorities || [];
        console.log('🔐 Extracted Roles (Fallback):', roles);

        setUserRoles(roles);
        setUserProfile(data);
        sessionStorage.setItem('cached_user_roles', JSON.stringify(roles));

        if (roles.includes('ROLE_SUPER_ADMIN')) {
          await fetchTenants();
          const currentTenant = sessionStorage.getItem('selectedTenantId') || '';
          if (currentTenant) {
            await fetchTenantUsers(currentTenant);
          }
        }
      } catch (fallbackErr) {
        console.error('❌ Could not fetch user profile from either endpoint:', fallbackErr);
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

  /**
   * Switch tenant (super admin only)
   */
  const switchTenant = useCallback((tenantId: string) => {
    if (tenantId) {
      sessionStorage.setItem('selectedTenantId', tenantId);
      sessionStorage.removeItem('impersonateUserId');
      setSelectedTenant(tenantId);
      setImpersonateUser('');
      window.location.reload();
    } else {
      resetContext();
    }
  }, []);

  /**
   * Switch user / impersonate (super admin only)
   */
  const switchUser = useCallback((userId: string) => {
    if (userId) {
      sessionStorage.setItem('impersonateUserId', userId);
      setImpersonateUser(userId);
      window.location.reload();
    }
  }, []);

  /**
   * Reset tenant context
   */
  const resetContext = useCallback(() => {
    sessionStorage.removeItem('selectedTenantId');
    sessionStorage.removeItem('impersonateUserId');
    setSelectedTenant('');
    setImpersonateUser('');
    window.location.reload();
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

  const value: AuthContextValue = {
    userRoles,
    userProfile,
    isLoading,
    tenantList,
    tenantUsers,
    selectedTenant,
    impersonateUser,
    fetchUserRoles,
    hasAnyRole,
    switchTenant,
    switchUser,
    resetContext,
    fetchTenants,
    fetchTenantUsers,
  };

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
