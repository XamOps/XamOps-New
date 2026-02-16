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

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [statusFilter, setStatusFilter] = useState<string[]>([]);

  /**
   * Fetch KPIs by loading ALL ACTIVE/COMPLETED capacities (separate from paginated table data)
   */
  const fetchKpis = useCallback(async () => {
    try {
      // Fetch all ACTIVE+COMPLETED capacities with a large page size for KPI aggregation
      const params = new URLSearchParams();
      params.set('page', '1');
      params.set('pageSize', '500');
      params.set('sort', 'desc');
      params.append('statuses', 'ACTIVE');
      params.append('statuses', 'COMPLETED');

      const response = await fetch(`/api/flexcompute/capacity?${params.toString()}`, {
        credentials: 'include',
      });

      if (!response.ok) return;

      const responseData = await response.json();
      let allData: FlexComputeCapacity[] = [];

      if (Array.isArray(responseData)) {
        allData = responseData;
      } else if (responseData && Array.isArray(responseData.data)) {
        allData = responseData.data;
      }

      const computedKpis: FlexComputeKPIs = allData.reduce(
        (acc, item) => ({
          totalSavings: acc.totalSavings + (item.totalSavings || 0),
          totalCost: acc.totalCost + (item.totalCost || 0),
          totalUsageHours: acc.totalUsageHours + (item.totalUsageHours || 0),
          remainingHours: acc.remainingHours + (item.remainingHours || 0),
        }),
        { ...defaultKPIs }
      );
      setKpis(computedKpis);
    } catch (err) {
      console.error('[FlexCompute] Fetch KPIs failed:', err);
    }
  }, []);

  /**
   * Fetch capacities with pagination and filters
   */
  const fetchCapacities = useCallback(async (
    page: number = 1,
    size: number = 10,
    statuses: string[] = [],
  ) => {
    try {
      setIsLoading(true);
      setError(null);

      const params = new URLSearchParams();
      params.set('page', String(page));
      params.set('pageSize', String(size));
      params.set('sort', 'desc');
      if (statuses.length > 0) {
        statuses.forEach((s) => params.append('statuses', s));
      }

      const response = await fetch(`/api/flexcompute/capacity?${params.toString()}`, {
        credentials: 'include',
      });

      if (response.status === 401) {
        setAuthStatus({ authenticated: false });
        return;
      }

      if (response.ok) {
        const responseData = await response.json();
        let data: FlexComputeCapacity[] = [];

        if (Array.isArray(responseData)) {
          data = responseData;
        } else if (responseData && Array.isArray(responseData.data)) {
          data = responseData.data;
          setTotalElements(responseData.totalElements || responseData.total || data.length);
          setTotalPages(responseData.totalPageNumbers || Math.ceil((responseData.totalElements || data.length) / size));
        }

        setCapacities(data);
        setCurrentPage(page);
        setPageSize(size);
        setStatusFilter(statuses);
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

      // Convert datetime-local format (YYYY-MM-DDTHH:mm) to date format (MM/dd/yyyy)
      const formatDateForAPI = (datetimeStr: string): string => {
        if (!datetimeStr) return '';
        const date = new Date(datetimeStr);
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const year = date.getFullYear();
        return `${month}/${day}/${year}`;
      };

      // Calculate UTC offset in hours from timezone
      const getUtcOffsetHours = (timezone: string): number => {
        // Map timezone to UTC offset
        const timezoneOffsets: { [key: string]: number } = {
          'America/Los_Angeles': -8,
          'America/Denver': -7,
          'America/Chicago': -6,
          'America/New_York': -5,
          'Europe/London': 0,
          'Europe/Berlin': 1,
          'Asia/Kolkata': 5.5,
          'Asia/Tokyo': 9,
          'Australia/Sydney': 11,
        };
        return timezoneOffsets[timezone] || 0;
      };

      const response = await fetch('/api/flexcompute/capacity/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          instanceType: form.instanceType,
          platform: form.platform,
          zoneId: form.zoneId,
          startDate: formatDateForAPI(form.startDate),
          endDate: formatDateForAPI(form.endDate),
          utcOffsetHours: getUtcOffsetHours(form.timeZone),
          // Additional fields for enriching the response
          quantity: form.quantity,
          region: form.region,
          capacityName: form.capacityName,
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
   * Reserve capacity - Creates 15-minute temporary reservation with pricing
   * Called after search, before configure
   */
  const reserveCapacity = useCallback(async (form: SearchCapacityForm): Promise<any> => {
    try {
      setError(null);

      // Validate required fields
      if (!form.cspAccount || !form.zoneId || !form.capacityName || !form.instanceType || !form.startDate || !form.endDate) {
        throw new Error('Missing required fields for reservation');
      }

      // Format date and time from datetime-local format (YYYY-MM-DDTHH:mm)
      const formatDate = (datetimeStr: string): string => {
        if (!datetimeStr) return '';
        const date = new Date(datetimeStr);
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const year = date.getFullYear();
        return `${month}/${day}/${year}`;
      };

      const formatTime = (datetimeStr: string): string => {
        if (!datetimeStr) return '';
        const date = new Date(datetimeStr);
        const hours = date.getHours();
        const minutes = date.getMinutes();

        // Round to nearest 15-minute increment (0, 15, 30, 45)
        const roundedMinutes = Math.round(minutes / 15) * 15;
        const finalMinutes = roundedMinutes === 60 ? 0 : roundedMinutes;
        const finalHours = roundedMinutes === 60 ? (hours + 1) % 24 : hours;

        return `${String(finalHours).padStart(2, '0')}:${String(finalMinutes).padStart(2, '0')}`;
      };

      // Calculate UTC offset in hours from timezone
      const getUtcOffsetHours = (timezone: string): string => {
        const timezoneOffsets: { [key: string]: string } = {
          'America/Los_Angeles': '-8',
          'America/Denver': '-7',
          'America/Chicago': '-6',
          'America/New_York': '-5',
          'Europe/London': '0',
          'Europe/Berlin': '1',
          'Asia/Kolkata': '5.5',
          'Asia/Tokyo': '9',
          'Australia/Sydney': '11',
        };
        return timezoneOffsets[timezone] || '0';
      };

      const requestBody = {
        accountId: form.cspAccount,
        zoneId: form.zoneId,
        policyName: form.capacityName,
        instanceType: form.instanceType,
        platform: form.platform,
        startDate: formatDate(form.startDate),
        endDate: formatDate(form.endDate),
        startTime: formatTime(form.startDate),
        endTime: formatTime(form.endDate),
        utcOffsetHours: getUtcOffsetHours(form.timeZone),
        quantity: form.quantity,
      };

      console.log('[FlexCompute] Reserve capacity request:', requestBody);

      const response = await fetch('/api/flexcompute/capacity/reserve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(requestBody),
      });

      if (response.status === 401) {
        setAuthStatus({ authenticated: false });
        return null;
      }

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
        const errorMessage = errorData.error || `Reserve failed with status ${response.status}`;
        console.error('[FlexCompute] Reserve capacity error response:', errorData);
        throw new Error(errorMessage);
      }

      const data = await response.json();
      console.log('[FlexCompute] Reserve capacity response:', data);
      return data; // Returns ReserveCapacityResponse with myPolicy, pricing, etc.
    } catch (err) {
      console.error('[FlexCompute] Reserve capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Reserve failed');
      return null;
    }
  }, [setAuthStatus]);

  /**
   * Configure capacity (wizard step 2)
   */
  const configureCapacity = useCallback(async (form: ConfigureCapacityForm): Promise<boolean> => {
    try {
      setError(null);

      const requestBody = {
        cidrId: form.capacityId,
        vpc: form.vpcId,
        securityGroups: form.securityGroupId,
        sshPairKey: form.sshKeyName,
        ebsVolumeSize: form.ebsVolumeSize,
        amiImage: form.amiImageId,
      };

      console.log('[FlexCompute] Configure capacity request:', requestBody);

      const response = await fetch('/api/flexcompute/capacity/configure', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(requestBody),
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

      const requestBody = {
        cidrId: capacityId,
      };

      console.log('[FlexCompute] Purchase capacity request:', requestBody);

      const response = await fetch('/api/flexcompute/capacity/purchase', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Purchase failed');
      }

      // Refresh capacity list and KPIs after purchase
      await fetchCapacities();
      await fetchKpis();
      return true;
    } catch (err) {
      console.error('[FlexCompute] Purchase capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Purchase failed');
      return false;
    } finally {
      setIsPurchasing(false);
    }
  }, [fetchCapacities, fetchKpis]);

  /**
   * Extend capacity end date
   */
  const extendCapacity = useCallback(async (
    cidrId: string,
    endDate: string,
    endTime: string,
    utcOffset: string,
  ): Promise<boolean> => {
    try {
      setError(null);

      const requestBody = { cidrId, endDate, endTime, utcOffset };
      console.log('[FlexCompute] Extend capacity request:', requestBody);

      const response = await fetch('/api/flexcompute/capacity/extend', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Extend failed');
      }

      await fetchCapacities();
      await fetchKpis();
      return true;
    } catch (err) {
      console.error('[FlexCompute] Extend capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Extend failed');
      return false;
    }
  }, [fetchCapacities, fetchKpis]);

  /**
   * Cancel capacity (dashboard action)
   */
  const cancelCapacity = useCallback(async (cidrId: string): Promise<boolean> => {
    try {
      setError(null);

      console.log('[FlexCompute] Cancel capacity request:', cidrId);

      const response = await fetch(`/api/flexcompute/capacity/${cidrId}`, {
        method: 'DELETE',
        credentials: 'include',
      });

      if (!response.ok) {
        const errorData = await response.json();
        throw new Error(errorData.error || 'Cancel failed');
      }

      // Refresh capacity list and KPIs after cancellation
      await fetchCapacities();
      await fetchKpis();
      return true;
    } catch (err) {
      console.error('[FlexCompute] Cancel capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Cancel failed');
      return false;
    }
  }, [fetchCapacities, fetchKpis]);

  // Initial load
  useEffect(() => {
    checkAuthStatus();
  }, [checkAuthStatus]);

  // Fetch capacities, KPIs, and linked accounts when authenticated
  useEffect(() => {
    if (authStatus.authenticated) {
      fetchCapacities();
      fetchKpis();
      fetchLinkedAccounts();
    }
  }, [authStatus.authenticated, fetchCapacities, fetchKpis, fetchLinkedAccounts]);

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
    fetchKpis,
    currentPage,
    pageSize,
    totalElements,
    totalPages,

    // Wizard
    searchResult,
    isSearching,
    isPurchasing,
    searchCapacity,
    reserveCapacity,
    configureCapacity,
    purchaseCapacity,
    cancelCapacity,
    extendCapacity,
    setSearchResult,

    // Error
    error,
    setError,
  };
};
