/**
 * useFlexComputeData Hook
 *
 * Custom hook to manage FlexCompute (CloudiDR) data and operations
 */

import { useState, useEffect, useCallback } from 'react';
import type {
  FlexComputeCapacity,
  FlexComputeKPIs,
  SearchCapacityForm,
  ConfigureCapacityForm,
  CapacityPricing,
  CloudiDRAuthStatus,
} from '../types/flexCompute';

const defaultKPIs: FlexComputeKPIs = {
  totalSavings: 0,
  totalCost: 0,
  totalUsageHours: 0,
  remainingHours: 0,
};

export const useFlexComputeData = () => {
  // Auth state
  const [authStatus, setAuthStatus] = useState<CloudiDRAuthStatus>({ authenticated: false });
  const [authLoading, setAuthLoading] = useState(true);

  // Dashboard data
  const [capacities, setCapacities] = useState<FlexComputeCapacity[]>([]);
  const [kpis, setKpis] = useState<FlexComputeKPIs>(defaultKPIs);
  const [isLoading, setIsLoading] = useState(false);

  // Linked accounts from CloudiDR
  const [linkedAccounts, setLinkedAccounts] = useState<Array<{ accountId: string; accountName: string; provider: string; status: string }>>([]);
  const [accountsLoading, setAccountsLoading] = useState(false);

  // Wizard data
  const [searchResult, setSearchResult] = useState<CapacityPricing | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [isPurchasing, setIsPurchasing] = useState(false);

  // Error state
  const [error, setError] = useState<string | null>(null);

  /**
   * Check CloudiDR authentication status
   */
  const checkAuthStatus = useCallback(async () => {
    try {
      setAuthLoading(true);
      const response = await fetch('/api/flexcompute/auth/status', {
        credentials: 'include',
      });

      if (response.ok) {
        const data = await response.json();
        setAuthStatus({
          authenticated: data.authenticated,
          email: data.email,
        });
      } else {
        setAuthStatus({ authenticated: false });
      }
    } catch (err) {
      console.error('[FlexCompute] Auth status check failed:', err);
      setAuthStatus({ authenticated: false });
    } finally {
      setAuthLoading(false);
    }
  }, []);

  /**
   * Authenticate with CloudiDR
   */
  const authenticateCloudIDR = useCallback(async (email: string, password: string): Promise<boolean> => {
    try {
      setError(null);
      const response = await fetch('/api/flexcompute/auth', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ email, password }),
      });

      const data = await response.json();

      if (response.ok && data.authenticated) {
        setAuthStatus({ authenticated: true, email });
        return true;
      } else {
        setError(data.error || 'Authentication failed');
        return false;
      }
    } catch (err) {
      console.error('[FlexCompute] Authentication failed:', err);
      setError('Failed to connect to CloudiDR');
      return false;
    }
  }, []);

  /**
   * Disconnect from CloudiDR
   */
  const disconnectCloudIDR = useCallback(async () => {
    try {
      await fetch('/api/flexcompute/auth', {
        method: 'DELETE',
        credentials: 'include',
      });
      setAuthStatus({ authenticated: false });
      setCapacities([]);
      setKpis(defaultKPIs);
    } catch (err) {
      console.error('[FlexCompute] Disconnect failed:', err);
    }
  }, []);

  /**
   * Fetch all capacities (dashboard data)
   */
  const fetchCapacities = useCallback(async () => {
    try {
      setIsLoading(true);
      setError(null);

      const response = await fetch('/api/flexcompute/capacity', {
        credentials: 'include',
      });

      if (response.status === 401) {
        setAuthStatus({ authenticated: false });
        return;
      }

      if (response.ok) {
        const responseData = await response.json();
        // Handle paginated response format: { data: [], page, pageSize, total }
        let data: FlexComputeCapacity[] = [];

        if (Array.isArray(responseData)) {
          // Old format: direct array
          data = responseData;
        } else if (responseData && Array.isArray(responseData.data)) {
          // New format: paginated response with data array
          data = responseData.data;
        }

        setCapacities(data);

        // Calculate KPIs from capacity data
        const computedKpis: FlexComputeKPIs = data.reduce(
          (acc, item) => ({
            totalSavings: acc.totalSavings + (item.totalSavings || 0),
            totalCost: acc.totalCost + (item.totalCost || 0),
            totalUsageHours: acc.totalUsageHours + (item.totalUsageHours || 0),
            remainingHours: acc.remainingHours + (item.remainingHours || 0),
          }),
          { ...defaultKPIs }
        );
        setKpis(computedKpis);
      }
    } catch (err) {
      console.error('[FlexCompute] Fetch capacities failed:', err);
      setError('Failed to load capacity data');
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Fetch linked CloudiDR accounts
   */
  const fetchLinkedAccounts = useCallback(async () => {
    try {
      setAccountsLoading(true);
      const response = await fetch('/api/flexcompute/linked-accounts', {
        credentials: 'include',
      });

      if (response.ok) {
        const accounts = await response.json();
        setLinkedAccounts(accounts || []);
      }
    } catch (err) {
      console.error('[FlexCompute] Fetch linked accounts failed:', err);
    } finally {
      setAccountsLoading(false);
    }
  }, []);

  /**
   * Search capacity (wizard step 1)
   */
  const searchCapacity = useCallback(async (form: SearchCapacityForm): Promise<CapacityPricing | null> => {
    try {
      setIsSearching(true);
      setError(null);
      setSearchResult(null);

      const response = await fetch('/api/flexcompute/capacity/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          capacity_name: form.capacityName,
          csp_account: form.cspAccount,
          region: form.region,
          zone_id: form.zoneId,
          instance_type: form.instanceType,
          platform: form.platform,
          tenancy: form.tenancy,
          quantity: form.quantity,
          time_zone: form.timeZone,
          start_date: form.startDate,
          end_date: form.endDate,
          auto_renewal: form.autoRenewal,
        }),
      });

      if (response.status === 401) {
        setAuthStatus({ authenticated: false });
        return null;
      }

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Search failed');
      }

      const data = await response.json();
      const pricing: CapacityPricing = {
        capacityId: data.capacity_id || data.capacityId || '',
        capacityName: data.capacity_name || data.capacityName || form.capacityName,
        instanceType: data.instance_type || data.instanceType || form.instanceType,
        region: data.region || form.region,
        zoneId: data.zone_id || data.zoneId || form.zoneId,
        quantity: data.quantity || form.quantity,
        platform: data.platform || form.platform,
        tenancy: data.tenancy || form.tenancy,
        ourPrice: data.our_price || data.ourPrice || 0,
        awsPrice: data.aws_price || data.awsPrice || 0,
        savingsPercent: data.savings_percent || data.savingsPercent || 0,
        fees: data.fees || 0,
        total: data.total || 0,
        startDate: data.start_date || data.startDate || form.startDate,
        endDate: data.end_date || data.endDate || form.endDate,
        status: data.status || 'available',
      };

      setSearchResult(pricing);
      return pricing;
    } catch (err) {
      console.error('[FlexCompute] Search capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Search failed');
      return null;
    } finally {
      setIsSearching(false);
    }
  }, []);

  /**
   * Configure capacity (wizard step 2)
   */
  const configureCapacity = useCallback(async (form: ConfigureCapacityForm): Promise<boolean> => {
    try {
      setError(null);

      const response = await fetch('/api/flexcompute/capacity/configure', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          capacity_id: form.capacityId,
          vpc_id: form.vpcId,
          security_group_id: form.securityGroupId,
          ssh_key_name: form.sshKeyName,
          ebs_volume_size: form.ebsVolumeSize,
          ami_image_id: form.amiImageId,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Configuration failed');
      }

      return true;
    } catch (err) {
      console.error('[FlexCompute] Configure capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Configuration failed');
      return false;
    }
  }, []);

  /**
   * Purchase capacity (wizard step 3)
   */
  const purchaseCapacity = useCallback(async (capacityId: string): Promise<boolean> => {
    try {
      setIsPurchasing(true);
      setError(null);

      const response = await fetch('/api/flexcompute/capacity/purchase', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          capacity_id: capacityId,
          terms_accepted: true,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Purchase failed');
      }

      // Refresh capacity list after purchase
      await fetchCapacities();
      return true;
    } catch (err) {
      console.error('[FlexCompute] Purchase capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Purchase failed');
      return false;
    } finally {
      setIsPurchasing(false);
    }
  }, [fetchCapacities]);

  // Initial load
  useEffect(() => {
    checkAuthStatus();
  }, [checkAuthStatus]);

  // Fetch capacities and linked accounts when authenticated
  useEffect(() => {
    if (authStatus.authenticated) {
      fetchCapacities();
      fetchLinkedAccounts();
    }
  }, [authStatus.authenticated, fetchCapacities, fetchLinkedAccounts]);

  return {
    // Auth
    authStatus,
    authLoading,
    authenticateCloudIDR,
    disconnectCloudIDR,
    checkAuthStatus,

    // Linked Accounts
    linkedAccounts,
    accountsLoading,
    fetchLinkedAccounts,

    // Dashboard
    capacities,
    kpis,
    isLoading,
    fetchCapacities,

    // Wizard
    searchResult,
    isSearching,
    isPurchasing,
    searchCapacity,
    configureCapacity,
    purchaseCapacity,
    setSearchResult,

    // Error
    error,
    setError,
  };
};
