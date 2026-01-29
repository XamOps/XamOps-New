/**
 * Account Context
 *
 * Manages cloud accounts, selection, and multi-account mode
 * Converted from: _sidebar.html (account management logic)
 */

import React, { createContext, useContext, useState, useEffect, useCallback, ReactNode } from 'react';
import apiClient from '../utils/apiClient';
import { Account, CloudProvider } from '../types/account';

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

export const AccountProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
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
  const initializedRef = React.useRef(false);

  /**
   * Check current page type
   */
  const checkCurrentPage = useCallback(() => {
    const path = window.location.pathname;
    setIsDashboardPage(path.includes('dashboard'));
    setIsBillingPage(path.includes('billing'));
  }, []);

  /**
   * Fetch accounts from backend
   * Note: This function should not trigger re-renders or loops
   */
  const fetchAccounts = useCallback(async () => {
    setIsLoading(true);

    try {
      const data = await apiClient.get<Account[]>('/api/xamops/account-manager/accounts');
      const connectedAccounts = data.filter(acc => acc.status === 'CONNECTED');

      setAccounts(connectedAccounts);
      sessionStorage.setItem('cached_accounts', JSON.stringify(connectedAccounts));

      // Auto-select first account if none selected
      const currentSelectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
      if (currentSelectedIds.length === 0 && connectedAccounts.length > 0) {
        console.log('📝 No account selected, auto-selecting first account:', connectedAccounts[0].id);
        const firstId = connectedAccounts[0].id;
        setSelectedAccountIds([firstId]);
        setTempSelectedAccounts([firstId]);
        sessionStorage.setItem('selectedAccountIds', JSON.stringify([firstId]));
        // Note: Don't auto-apply on unified-dashboard React page 
        // The page doesn't need account-specific navigation
      }
    } catch (err) {
      console.error('Failed to fetch accounts:', err);
    } finally {
      setIsLoading(false);
    }
  }, []); // No dependencies to prevent re-creation

  /**
   * Toggle account selection
   */
  const toggleAccount = useCallback(
    (accountId: string) => {
      const account = accounts.find(a => a.id === accountId);
      if (!account) return;

      // Check if account is disabled
      if (isAccountDisabled(accountId)) {
        const firstAccount = accounts.find(a => a.id === tempSelectedAccounts[0]);
        alert(
          `Cannot select ${account.provider} accounts. You have already selected ${firstAccount?.provider} accounts. Please clear your selection first.`
        );
        return;
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

  /**
   * Check if account should be disabled (different provider in multi-account mode)
   */
  const isAccountDisabled = useCallback(
    (accountId: string): boolean => {
      if (!multiAccountMode || !isDashboardPage) return false;
      if (tempSelectedAccounts.length === 0) return false;

      const account = accounts.find(a => a.id === accountId);
      const firstAccount = accounts.find(a => a.id === tempSelectedAccounts[0]);

      return !!(account && firstAccount && account.provider !== firstAccount.provider);
    },
    [accounts, tempSelectedAccounts, multiAccountMode, isDashboardPage]
  );

  /**
   * Check if account is selected
   */
  const isAccountSelected = useCallback(
    (accountId: string): boolean => {
      return tempSelectedAccounts.includes(accountId);
    },
    [tempSelectedAccounts]
  );

  /**
   * Select all AWS accounts
   */
  const selectAllAws = useCallback(() => {
    const awsIds = accounts
      .filter(acc => acc.provider === 'AWS')
      .map(acc => acc.id)
      .slice(0, 10);
    setTempSelectedAccounts(awsIds);
  }, [accounts]);

  /**
   * Select all GCP accounts
   */
  const selectAllGcp = useCallback(() => {
    const gcpIds = accounts
      .filter(acc => acc.provider === 'GCP')
      .map(acc => acc.id)
      .slice(0, 10);
    setTempSelectedAccounts(gcpIds);
  }, [accounts]);

  /**
   * Select all Azure accounts
   */
  const selectAllAzure = useCallback(() => {
    const azureIds = accounts
      .filter(acc => acc.provider === 'Azure')
      .map(acc => acc.id)
      .slice(0, 10);
    setTempSelectedAccounts(azureIds);
  }, [accounts]);

  /**
   * Clear all selections
   */
  const clearAllSelections = useCallback(() => {
    setTempSelectedAccounts([]);
  }, []);

  /**
   * Apply selection and navigate
   * This is the core navigation logic from the original sidebar
   */
  const applySelection = useCallback(() => {
    console.log('[Sidebar] applySelection called');
    if (tempSelectedAccounts.length === 0) {
      alert('Please select at least one account');
      return;
    }

    setSelectedAccountIds([...tempSelectedAccounts]);
    sessionStorage.setItem('selectedAccountIds', JSON.stringify(tempSelectedAccounts));
    sessionStorage.setItem('multiAccountMode', String(multiAccountMode));

    // Determine provider context
    const firstSelectedId = tempSelectedAccounts[0];
    const account = accounts.find(a => a.id === firstSelectedId);
    const provider = account?.provider || 'AWS';
    console.log('[Sidebar] Selected Provider:', provider);

    // Determine target page based on role and provider
    let targetPage: string;
    const isBillOpsUser = false; // This should come from useAuth, but we'll handle it in the component

    if (isBillOpsUser) {
      switch (provider) {
        case 'GCP':
          targetPage = '/billops/gcp-billing.html';
          break;
        case 'Azure':
          targetPage = '/billops/azure-billing.html';
          break;
        case 'AWS':
        default:
          targetPage = '/billops/billing.html';
          break;
      }
    } else {
      // List of routes that have been converted to React
      const reactRoutes = [
        '/dashboard',
        '/unified-dashboard',
        '/cloudshell',
        '/cloudlist',
        '/finops',
        '/cost',
        '/waste',
        '/rightsizing',
        '/reservation',
        '/cloudsitter',
        '/spot-automation',
        '/grafana-dashboard',
        '/cloudk8s',
        '/eks-details',
        '/security',
        '/performance',
        '/alerts',
        '/tickets',
        '/ticket-detail',
        '/account-manager',
        '/billops-dashboard',
        '/billops-billing'
      ];

      const currentPath = window.location.pathname;
      const isReactRoute = reactRoutes.some(route => currentPath === route || currentPath.startsWith(route + '/'));

      if (isReactRoute) {
        targetPage = currentPath; // Stay on React route
      } else {
        // Default to HTML pages based on provider
        switch (provider) {
          case 'GCP':
            targetPage = '/gcp_dashboard.html';
            break;
          case 'Azure':
            targetPage = '/azure_dashboard.html';
            break;
          case 'AWS':
          default:
            targetPage = '/dashboard.html';
            break;
        }

        // Exceptions for special pages (legacy compatibility)
        if (window.location.pathname === '/cloudshell.html') {
          targetPage = '/cloudshell.html';
        }
        if (window.location.pathname.includes('cloudlist.html')) {
          if (provider === 'GCP') targetPage = '/gcp_cloudlist.html';
          else if (provider === 'Azure') targetPage = '/azure_cloudlist.html';
          else targetPage = '/cloudlist.html';
        }
      }
    }

    console.log('[Sidebar] Target Page:', targetPage);

    // Handle multi-account mode with soft navigation
    if (multiAccountMode && tempSelectedAccounts.length > 1 && isDashboardPage) {
      if (window.location.pathname === targetPage) {
        console.log('[Sidebar] Multi-account soft update. Dispatching event.');
        window.dispatchEvent(
          new CustomEvent('multi-account-selection-changed', {
            detail: { accountIds: tempSelectedAccounts },
          })
        );
        return;
      }
    }

    // Construct URL
    const urlParams = new URLSearchParams();
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
    console.log('[Sidebar] Constructed Target URL:', targetUrl);

    // Smart navigation check
    if (window.location.pathname === targetPage) {
      console.log('[Sidebar] Soft navigation detected. Updating state and dispatching account-changed.');
      window.history.pushState(null, '', targetUrl);
      window.dispatchEvent(
        new CustomEvent('account-changed', {
          detail: { accountId: firstSelectedId, accountIds: tempSelectedAccounts },
        })
      );
    } else {
      console.log('[Sidebar] Hard navigation required. Redirecting...');
      window.location.href = targetUrl;
    }
  }, [tempSelectedAccounts, accounts, multiAccountMode, isDashboardPage]);

  /**
   * Toggle multi-account mode
   */
  const toggleMultiAccountMode = useCallback(() => {
    setMultiAccountMode(prev => !prev);
  }, []);

  /**
   * Get provider icon
   */
  const getProviderIcon = useCallback((provider: CloudProvider): string => {
    switch (provider) {
      case 'GCP':
        return '/icons/gcp-icon.png';
      case 'Azure':
        return '/icons/azure-icon.png';
      case 'AWS':
      default:
        return '/icons/aws-icon.png';
    }
  }, []);

  // Computed values
  const selectedAccountDisplay = React.useMemo(() => {
    if (selectedAccountIds.length === 0) {
      return {
        name: 'Select Accounts',
        id: accounts.length === 0 && !isLoading ? 'No accounts connected' : 'No account selected',
      };
    }
    if (selectedAccountIds.length === 1) {
      const account = accounts.find(a => a.id === selectedAccountIds[0]);
      return {
        name: account?.name || 'Loading...',
        id: account?.id || '',
        provider: account?.provider || 'AWS',
      };
    }

    const firstAccount = accounts.find(a => a.id === selectedAccountIds[0]);
    const provider = firstAccount?.provider || 'AWS';
    return {
      name: `${selectedAccountIds.length} ${provider} Accounts`,
      id: 'Multi-Account View',
      provider: provider,
    };
  }, [selectedAccountIds, accounts, isLoading]);

  const filteredAccounts = React.useMemo(() => {
    if (searchTerm === '') return accounts;
    const term = searchTerm.toLowerCase();
    return accounts.filter(
      account =>
        account.name.toLowerCase().includes(term) ||
        (account.id && account.id.toLowerCase().includes(term))
    );
  }, [accounts, searchTerm]);

  const awsAccounts = React.useMemo(
    () => filteredAccounts.filter(acc => acc.provider === 'AWS'),
    [filteredAccounts]
  );

  const gcpAccounts = React.useMemo(
    () => filteredAccounts.filter(acc => acc.provider === 'GCP'),
    [filteredAccounts]
  );

  const azureAccounts = React.useMemo(
    () => filteredAccounts.filter(acc => acc.provider === 'Azure'),
    [filteredAccounts]
  );

  // Initialize on mount - with guard for React Strict Mode
  useEffect(() => {
    // Guard against double initialization
    if (initializedRef.current) {
      return;
    }
    initializedRef.current = true;

    console.log('🚀 Account Context Init - Starting...');
    checkCurrentPage();
    fetchAccounts();

    // Listen for account changes
    const handleAccountChanged = () => {
      console.log('🔄 Account changed event received, fetching accounts...');
      fetchAccounts();
    };
    window.addEventListener('account-changed', handleAccountChanged);

    // Handle browser back/forward buttons
    const handlePopState = () => {
      console.log('[AccountContext] Popstate event detected:', window.location.href);
      const urlParams = new URLSearchParams(window.location.search);
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

        window.dispatchEvent(
          new CustomEvent('account-changed', {
            detail: {
              accountId: recoveredIds[0],
              accountIds: recoveredIds,
            },
          })
        );
      }
    };
    window.addEventListener('popstate', handlePopState);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged);
      window.removeEventListener('popstate', handlePopState);
    };
  }, []); // Empty dependency array - only run on mount

  const value: AccountContextValue = {
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
  };

  return <AccountContext.Provider value={value}>{children}</AccountContext.Provider>;
};

/**
 * Custom hook to use account context
 */
export const useAccounts = () => {
  const context = useContext(AccountContext);
  if (!context) {
    throw new Error('useAccounts must be used within AccountProvider');
  }
  return context;
};
