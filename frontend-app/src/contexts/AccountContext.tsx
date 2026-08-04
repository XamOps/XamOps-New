/**
 * Account Context
 *
 * Manages cloud accounts, selection, and multi-account mode
 * Converted from: _sidebar.html (account management logic)
 */

import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode, useMemo, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import apiClient from '../utils/apiClient';
import { Account, CloudProvider } from '../types/account';
import { useAuth } from './AuthContext';

interface AccountContextValue {
  accounts: Account[];
  selectedAccountIds: string[];
  tempSelectedAccounts: string[];
  multiAccountMode: boolean;
  searchTerm: string;
  isLoading: boolean;
  isDashboardPage: boolean;
  isBillingPage: boolean;

  // Computed values
  selectedAccountDisplay: {
    name: string;
    id: string;
    provider?: CloudProvider;
  };
  filteredAccounts: Account[];
  awsAccounts: Account[];
  gcpAccounts: Account[];
  azureAccounts: Account[];

  // Methods
  fetchAccounts: () => Promise<void>;
  toggleAccount: (accountId: string) => void;
  isAccountDisabled: (accountId: string) => boolean;
  isAccountSelected: (accountId: string) => boolean;
  selectAllAws: () => void;
  selectAllGcp: () => void;
  selectAllAzure: () => void;
  clearAllSelections: () => void;
  applySelection: () => void;
  toggleMultiAccountMode: () => void;
  setSearchTerm: (term: string) => void;
  getProviderIcon: (provider: CloudProvider) => string;
}

const AccountContext = createContext<AccountContextValue | undefined>(undefined);

/**
 * Provider-specific page equivalents.
 *
 * Each row groups the "same" logical page across AWS / GCP / Azure. This lets us
 * keep the user on the equivalent page when they switch the active cloud provider
 * from the account selector (e.g. /azure-cloudlist -> /cloudlist when switching to
 * an AWS account). Some pages have no equivalent for a given provider (e.g. there
 * is no Azure Cloud Map) — in that case we fall back to the provider dashboard.
 */
type ProviderRoutes = Partial<Record<CloudProvider, string>>;

const PROVIDER_PAGE_MAP: ProviderRoutes[] = [
  { AWS: '/dashboard', GCP: '/gcp-dashboard', Azure: '/azure-dashboard' },
  { AWS: '/cloudlist', GCP: '/gcp-cloudlist', Azure: '/azure-cloudlist' },
  { AWS: '/finops', GCP: '/gcp-finops', Azure: '/azure-finops' },
  { AWS: '/cost', GCP: '/gcp-cost', Azure: '/azure-cost' },
  { AWS: '/waste', GCP: '/gcp-waste', Azure: '/azure-waste' },
  { AWS: '/rightsizing', GCP: '/gcp-rightsizing', Azure: '/azure-rightsizing' },
  { AWS: '/reservation', GCP: '/gcp-reservations', Azure: '/azure-reservations' },
  { AWS: '/ebs-rightsizing', GCP: '/gcp-disk-rightsizing', Azure: '/azure-disk-rightsizing' },
  { AWS: '/cloudk8s', GCP: '/gcp-cloudk8s', Azure: '/azure-cloudk8s' },
  { AWS: '/xamsecure', GCP: '/gcp-security', Azure: '/azure-security' },
  // Azure has no WAR baseline yet, so switching to it falls back to the dashboard.
  { AWS: '/war', GCP: '/gcp-war' },
  { AWS: '/performance', GCP: '/gcp-performance', Azure: '/azure-performance' },
  { AWS: '/alerts', GCP: '/gcp-alerts', Azure: '/azure-alerts' },
  { AWS: '/cloudmap', GCP: '/gcp-cloudmap' },
  { AWS: '/dataops', GCP: '/gcp-dataops', Azure: '/azure-dataops' },
  { AWS: '/cloudsitter', GCP: '/gcp-cloudsitter', Azure: '/azure-cloudsitter' },
  { AWS: '/spot-automation', GCP: '/gcp-autospotting', Azure: '/azure-autospotting' },
];

const providerDashboard = (provider: CloudProvider): string => {
  if (provider === 'GCP') return '/gcp-dashboard';
  if (provider === 'Azure') return '/azure-dashboard';
  return '/dashboard';
};

/**
 * Given the current path and a target provider, return the equivalent page for
 * that provider. Returns null when the current page is not provider-specific
 * (e.g. /account-manager, /tickets), so the caller can keep its default behavior.
 * Falls back to the provider dashboard when the page has no variant for the
 * target provider.
 */
const mapPageToProvider = (currentPath: string, provider: CloudProvider): string | null => {
  const group = PROVIDER_PAGE_MAP.find(
    row => row.AWS === currentPath || row.GCP === currentPath || row.Azure === currentPath
  );
  if (!group) return null;
  return group[provider] ?? providerDashboard(provider);
};

export const AccountProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { hasAnyRole, authReady } = useAuth();

  // Load from cache immediately to prevent flicker
  const [accounts, setAccounts] = useState<Account[]>(() => {
    const cached = sessionStorage.getItem('cached_accounts');
    return cached ? JSON.parse(cached) : [];
  });

  const [selectedAccountIds, setSelectedAccountIds] = useState<string[]>(() => {
    return JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
  });

  const [tempSelectedAccounts, setTempSelectedAccounts] = useState<string[]>(() => {
    return JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
  });

  const [multiAccountMode, setMultiAccountMode] = useState<boolean>(() => {
    return sessionStorage.getItem('multiAccountMode') === 'true';
  });

  const [searchTerm, setSearchTerm] = useState<string>('');
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isDashboardPage, setIsDashboardPage] = useState<boolean>(false);
  const [isBillingPage, setIsBillingPage] = useState<boolean>(false);

  // Initialization guard to prevent double mounting in React Strict Mode
  const initializedRef = useRef(false);

  /**
   * Check current page type
   */
  const checkCurrentPage = useCallback(() => {
    const path = location.pathname;
    setIsDashboardPage(path.includes('dashboard'));
    setIsBillingPage(path.includes('billing') || path.includes('finops'));
  }, [location.pathname]);

  /**
   * Fetch accounts from backend
   */
  const fetchAccounts = useCallback(async () => {
    // Clear stale state immediately so the sidebar never shows a previous user's
    // accounts while the new fetch is in-flight (or if the fetch fails for this user).
    // NOTE: preserve selectedAccountIds during the fetch so useDashboardData keeps
    // its loading state and doesn't flash a blank screen mid-cycle.
    setAccounts([]);
    sessionStorage.removeItem('cached_accounts');

    setIsLoading(true);

    try {
      console.log('🔄 [AccountContext] fetchAccounts. ImpersonateId:', sessionStorage.getItem('impersonateUserId'));
      const data = await apiClient.get<Account[]>('/api/xamops/account-manager/accounts');
      console.log('📊 [AccountContext] Accounts received:', data.length, data);

      // Included more statuses for visibility, especially during impersonation
      let filtered = data.filter(acc => {
        // Filter out accounts with invalid IDs
        const hasValidId = acc.id &&
          typeof acc.id === 'string' &&
          acc.id.trim() !== '';

        const hasValidStatus = ['CONNECTED', 'PENDING', 'DISCONNECTED', 'ERROR'].includes(acc.status) || !acc.status;

        // Log accounts with null IDs for debugging
        if (!hasValidId) {
          console.warn('[AccountContext] Filtering out account with invalid ID:', acc);
        }

        return hasValidId && hasValidStatus;
      });

      // When the sidebar was opened via "Login as" from the BillOps admin panel,
      // allowedAccountIds limits the visible accounts to only what that specific
      // user is permitted to see (their client's accounts only).
      const allowedAccountIds = sessionStorage.getItem('allowedAccountIds');
      if (allowedAccountIds) {
        const allowed = new Set(allowedAccountIds.split(',').map(id => id.trim()));
        filtered = filtered.filter(acc => allowed.has(acc.id));
        console.log('📊 [AccountContext] Restricted to allowedAccountIds:', [...allowed], '→', filtered.length, 'accounts');
      }

      console.log('📊 [AccountContext] Filtered (Visible) accounts:', filtered.length);
      setAccounts(filtered);
      sessionStorage.setItem('cached_accounts', JSON.stringify(filtered));

      const filteredIds = new Set(filtered.map(a => a.id));

      // Validate previously selected accounts against the new list.
      // This handles user-switch: stale ids from a previous session are cleared.
      const currentSelectedIds: string[] = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
      const validSelectedIds = currentSelectedIds.filter(id => filteredIds.has(id));

      if (validSelectedIds.length !== currentSelectedIds.length) {
        // Some ids were stale — clear them
        sessionStorage.removeItem('selectedAccountIds');
        setSelectedAccountIds([]);
        setTempSelectedAccounts([]);
      }

      // Auto-select first account if none (or all) selected were invalid
      if (validSelectedIds.length === 0 && filtered.length > 0) {
        // Prioritize AWS accounts, then fall back to first available account
        const firstAwsAccount = filtered.find(acc => acc.provider === 'AWS');
        const accountToSelect = firstAwsAccount || filtered[0];
        console.log('📝 No account selected, auto-selecting account:', accountToSelect.id, '(provider:', accountToSelect.provider + ')');
        setSelectedAccountIds([accountToSelect.id]);
        setTempSelectedAccounts([accountToSelect.id]);
        sessionStorage.setItem('selectedAccountIds', JSON.stringify([accountToSelect.id]));
      }
    } catch (err) {
      console.error('Failed to fetch accounts:', err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Sync account selection from URL query params on every navigation.
   * When navigating from the unified dashboard modal (?accountId=xxx), the URL
   * param is the source of truth — update context + sessionStorage so the sidebar
   * and all page hooks immediately reflect the correct account.
   */
  useEffect(() => {
    const urlParams = new URLSearchParams(location.search);
    const urlAccountId = urlParams.get('accountId') || urlParams.get('projectId') || urlParams.get('subscriptionId');
    if (!urlAccountId) return;

    const current = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
    if (current[0] === urlAccountId) return; // already in sync, nothing to do

    console.log('[AccountContext] Syncing selection from URL param:', urlAccountId);
    sessionStorage.setItem('selectedAccountIds', JSON.stringify([urlAccountId]));
    setSelectedAccountIds([urlAccountId]);
    setTempSelectedAccounts([urlAccountId]);
  }, [location.search]);

  /**
   * Initialize on mount — but only after AuthContext has finished its profile
   * fetch and corrected sessionStorage (authReady = true).  Waiting here
   * prevents fetchAccounts from sending stale impersonateUserId / selectedTenantId
   * values that were left by a previous Login-As session contamination.
   */
  useEffect(() => {
    if (!authReady) return; // wait for AuthContext to clean up sessionStorage first
    if (initializedRef.current) return;
    initializedRef.current = true;

    console.log('🚀 Account Context Init - Starting (authReady)...');
    checkCurrentPage();
    fetchAccounts();

    // Listen for account changes from legacy scripts or Super Admin Panel
    const handleAccountChangedEvent = (event: Event) => {
      const reason = (event as CustomEvent).detail?.reason as string | undefined;
      console.log('🔄 Account changed event received, reason:', reason);

      // Sync state from sessionStorage
      const newSelectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
      const newMultiMode = sessionStorage.getItem('multiAccountMode') === 'true';

      setSelectedAccountIds(newSelectedIds);
      setTempSelectedAccounts(newSelectedIds);
      setMultiAccountMode(newMultiMode);

      // Only re-fetch the account list when the account set itself changes (tenant or user
      // switch). For plain account selection events the list is identical — calling
      // fetchAccounts() here would clear selectedAccountIds and auto-select the first
      // account, overwriting the user's intended selection.
      if (reason === 'tenant-switch' || reason === 'user-switch') {
        fetchAccounts();
      }
    };
    globalThis.addEventListener('account-changed', handleAccountChangedEvent);

    return () => {
      globalThis.removeEventListener('account-changed', handleAccountChangedEvent);
    };
  }, [authReady, checkCurrentPage, fetchAccounts]);

  /**
   * Handle browser back/forward buttons (PopState)
   */
  useEffect(() => {
    const handlePopState = () => {
      console.log('[AccountContext] Popstate event detected:', globalThis.location.href);
      const urlParams = new URLSearchParams(globalThis.location.search);
      const urlAccountIds = urlParams.get('accountIds');
      const urlAccountId = urlParams.get('accountId');
      const urlProjectId = urlParams.get('projectId');

      let recoveredIds: string[] = [];
      if (urlAccountIds) {
        recoveredIds = urlAccountIds.split(',');
      } else if (urlAccountId) {
        recoveredIds = [urlAccountId];
      } else if (urlProjectId) {
        recoveredIds = [urlProjectId];
      }

      if (recoveredIds.length > 0) {
        console.log('[AccountContext] Restoring selection from history:', recoveredIds);
        setSelectedAccountIds(recoveredIds);
        setTempSelectedAccounts(recoveredIds);
        sessionStorage.setItem('selectedAccountIds', JSON.stringify(recoveredIds));
      }
    };

    globalThis.addEventListener('popstate', handlePopState);
    return () => globalThis.removeEventListener('popstate', handlePopState);
  }, []);

  /**
   * Toggle account selection
   */
  const toggleAccount = useCallback(
    (accountId: string) => {
      const account = accounts.find(a => a.id === accountId);
      if (!account) return;

      // Check for provider mismatch in multi-mode
      if (multiAccountMode && isDashboardPage && tempSelectedAccounts.length > 0) {
        const firstAccount = accounts.find(a => a.id === tempSelectedAccounts[0]);
        if (firstAccount && account.provider !== firstAccount.provider) {
          alert(
            `Cannot select ${account.provider} accounts. You have already selected ${firstAccount.provider} accounts. Please clear your selection first.`
          );
          return;
        }
      }

      if (multiAccountMode && isDashboardPage) {
        const index = tempSelectedAccounts.indexOf(accountId);
        if (index > -1) {
          setTempSelectedAccounts(prev => prev.filter(id => id !== accountId));
        } else {
          if (tempSelectedAccounts.length >= 10) {
            alert('Maximum 10 accounts can be selected');
            return;
          }
          setTempSelectedAccounts(prev => [...prev, accountId]);
        }
      } else {
        setTempSelectedAccounts([accountId]);
      }
    },
    [accounts, tempSelectedAccounts, multiAccountMode, isDashboardPage]
  );

  const isAccountDisabled = useCallback(
    (accountId: string): boolean => {
      if (!multiAccountMode || !isDashboardPage || tempSelectedAccounts.length === 0) return false;
      const account = accounts.find(a => a.id === accountId);
      const firstAccount = accounts.find(a => a.id === tempSelectedAccounts[0]);
      return !!(account && firstAccount && account.provider !== firstAccount.provider);
    },
    [accounts, tempSelectedAccounts, multiAccountMode, isDashboardPage]
  );

  const isAccountSelected = useCallback(
    (accountId: string): boolean => tempSelectedAccounts.includes(accountId),
    [tempSelectedAccounts]
  );

  const selectAllAws = useCallback(() => {
    const awsIds = accounts
      .filter(acc => acc.provider === 'AWS')
      .map(acc => acc.id)
      .slice(0, 10);
    setTempSelectedAccounts(awsIds);
  }, [accounts]);

  const selectAllGcp = useCallback(() => {
    const gcpIds = accounts
      .filter(acc => acc.provider === 'GCP')
      .map(acc => acc.id)
      .slice(0, 10);
    setTempSelectedAccounts(gcpIds);
  }, [accounts]);

  const selectAllAzure = useCallback(() => {
    const azureIds = accounts
      .filter(acc => acc.provider === 'Azure')
      .map(acc => acc.id)
      .slice(0, 10);
    setTempSelectedAccounts(azureIds);
  }, [accounts]);

  const clearAllSelections = useCallback(() => setTempSelectedAccounts([]), []);

  /**
   * Apply selection and navigate using React Router (Soft Navigation)
   */
  const applySelection = useCallback(() => {
    console.log('[AccountContext] applySelection called');
    if (tempSelectedAccounts.length === 0) {
      alert('Please select at least one account');
      return;
    }

    setSelectedAccountIds([...tempSelectedAccounts]);
    sessionStorage.setItem('selectedAccountIds', JSON.stringify(tempSelectedAccounts));
    sessionStorage.setItem('multiAccountMode', String(multiAccountMode));

    const firstSelectedId = tempSelectedAccounts[0];
    const account = accounts.find(a => a.id === firstSelectedId);
    const provider = account?.provider || 'AWS';

    // Determine target page based on provider
    let targetPage = '/dashboard';

    // Check if user is ROLE_BILLOPS (and not ROLE_XAMOPS) or ROLE_BILLOPS_ADMIN.
    // During a "Login as" impersonation session the admin's real ROLE_BILLOPS_ADMIN
    // must NOT control routing — we want to land on the impersonated user's dashboard.
    const isBillopsImpersonation = sessionStorage.getItem('billops_impersonation') === 'true';
    const isBillOpsOnly = !isBillopsImpersonation && hasAnyRole('ROLE_BILLOPS') && !hasAnyRole('ROLE_XAMOPS');
    const isBillOpsAdmin = !isBillopsImpersonation && (hasAnyRole('ROLE_BILLOPS_ADMIN') || hasAnyRole('ROLE_BILLOPS_ADMIN_SUB'));

    if (isBillOpsAdmin) {
      targetPage = '/billops-admin-dashboard';
    } else if (isBillOpsOnly) {
      targetPage = '/billops-dashboard';
    } else {
      // Default landing page is the selected provider's dashboard.
      targetPage = providerDashboard(provider);

      // If the user is currently on a provider-specific page, keep them on the
      // equivalent page for the newly selected provider. This makes switching
      // cloud/account from ANY page redirect to the matching page for that cloud
      // (e.g. /azure-cloudlist -> /cloudlist when switching to an AWS account).
      const mappedPage = mapPageToProvider(location.pathname, provider);
      if (mappedPage) {
        targetPage = mappedPage;
      } else if (location.pathname.includes('billing') || location.pathname.includes('finops')) {
        // Generic billing/finops pages without a provider variant: stay put.
        targetPage = location.pathname;
      }
    }

    const urlParams = new URL(globalThis.location.href).searchParams;

    // Clear provider-specific identifiers so stale params from the previous
    // provider don't leak into the new provider's page when switching clouds.
    urlParams.delete('accountId');
    urlParams.delete('projectId');
    urlParams.delete('subscriptionId');
    urlParams.set('accountIds', tempSelectedAccounts.join(','));

    if (account && tempSelectedAccounts.length === 1) {
      if (account.provider === 'Azure' && account.azureSubscriptionId) {
        urlParams.set('subscriptionId', account.azureSubscriptionId);
      } else if (account.provider === 'GCP') {
        urlParams.set('projectId', account.id);
      } else if (account.provider === 'AWS') {
        urlParams.set('accountId', account.id);
      }
    }

    const targetUrl = `${targetPage}?${urlParams.toString()}`;

    if (location.pathname === targetPage) {
      console.log('[AccountContext] Soft navigation same page. Updating URL.');
      navigate(targetUrl, { replace: true });
      globalThis.dispatchEvent(new CustomEvent('account-changed', {
        detail: { accountId: firstSelectedId, accountIds: tempSelectedAccounts, reason: 'sidebar-selection' }
      }));
    } else {
      console.log('[AccountContext] Navigating to:', targetPage);
      navigate(targetUrl);
    }
  }, [tempSelectedAccounts, accounts, multiAccountMode, location.pathname, navigate]);

  const toggleMultiAccountMode = useCallback(() => {
    setMultiAccountMode(prev => !prev);
    sessionStorage.setItem('multiAccountMode', String(!multiAccountMode));
  }, [multiAccountMode]);

  const getProviderIcon = useCallback((provider: CloudProvider): string => {
    switch (provider) {
      case 'GCP': return 'fa-google';
      case 'Azure': return 'fa-microsoft';
      case 'AWS': return 'fa-aws';
      default: return 'fa-cloud';
    }
  }, []);

  // Computed values
  const filteredAccounts = useMemo(() => {
    if (!searchTerm) return accounts;
    const term = searchTerm.toLowerCase();
    return accounts.filter(acc =>
      acc.name.toLowerCase().includes(term) || acc.id.toLowerCase().includes(term)
    );
  }, [accounts, searchTerm]);

  const awsAccounts = useMemo(() => filteredAccounts.filter(a => a.provider === 'AWS'), [filteredAccounts]);
  const gcpAccounts = useMemo(() => filteredAccounts.filter(a => a.provider === 'GCP'), [filteredAccounts]);
  const azureAccounts = useMemo(() => filteredAccounts.filter(a => a.provider === 'Azure'), [filteredAccounts]);

  const selectedAccountDisplay = useMemo(() => {
    if (selectedAccountIds.length === 0) {
      return { name: 'Select Accounts', id: accounts.length === 0 && !isLoading ? 'No accounts connected' : 'No account selected' };
    }
    if (selectedAccountIds.length > 1) {
      const firstAcc = accounts.find(a => a.id === selectedAccountIds[0]);
      return { name: `${selectedAccountIds.length} ${firstAcc?.provider || ''} Accounts`, id: 'Multi-Account View', provider: firstAcc?.provider };
    }
    const acc = accounts.find(a => a.id === selectedAccountIds[0]);
    return { name: acc?.name || 'Selected Account', id: acc?.id || selectedAccountIds[0], provider: acc?.provider };
  }, [accounts, selectedAccountIds, isLoading]);

  const value = useMemo(() => ({
    accounts,
    selectedAccountIds,
    tempSelectedAccounts,
    multiAccountMode,
    searchTerm,
    isLoading,
    isDashboardPage,
    isBillingPage,
    selectedAccountDisplay,
    filteredAccounts,
    awsAccounts,
    gcpAccounts,
    azureAccounts,
    fetchAccounts,
    toggleAccount,
    isAccountDisabled,
    isAccountSelected,
    selectAllAws,
    selectAllGcp,
    selectAllAzure,
    clearAllSelections,
    applySelection,
    toggleMultiAccountMode,
    setSearchTerm,
    getProviderIcon,
  }), [
    accounts, selectedAccountIds, tempSelectedAccounts, multiAccountMode, searchTerm, isLoading,
    isDashboardPage, isBillingPage, selectedAccountDisplay, filteredAccounts, awsAccounts,
    gcpAccounts, azureAccounts, fetchAccounts, toggleAccount, isAccountDisabled, isAccountSelected,
    selectAllAws, selectAllGcp, selectAllAzure, clearAllSelections, applySelection,
    toggleMultiAccountMode, getProviderIcon,
  ]);

  return (
    <AccountContext.Provider value={value}>
      {children}
    </AccountContext.Provider>
  );
};

export const useAccounts = () => {
  const context = useContext(AccountContext);
  if (context === undefined) {
    throw new Error('useAccounts must be used within an AccountProvider');
  }
  return context;
};

export default AccountProvider;
