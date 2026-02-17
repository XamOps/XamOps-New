/**
 * useFlexComputeData Hook
 *
 * Custom hook to manage FlexCompute (CloudiDR) data and operations.
 * CloudiDR authentication is handled automatically by the backend.
 */

import { useState, useEffect, useCallback } from 'react';
import type {
  FlexComputeCapacity,
  FlexComputeKPIs,
  SearchCapacityForm,
  ConfigureCapacityForm,
  CapacityPricing,
} from '../types/flexCompute';

const defaultKPIs: FlexComputeKPIs = {
  totalSavings: 0,
  totalCost: 0,
  awsCost: 0,
  totalUsageHours: 0,
  remainingHours: 0,
};

export const useFlexComputeData = () => {
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
          awsCost: acc.awsCost + (item.awsPrice || 0),
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
   * Compute the UTC offset for a given IANA timezone as a whole-number INTEGER of hours.
   * Backend field is java.lang.Integer, valid range -18 to +18.
   * Uses Intl so DST is handled automatically for the current moment.
   * Fractional-hour timezones (e.g. IST +5:30, Nepal +5:45) are rounded to nearest integer.
   */
  const getUtcOffsetHours = (timezone: string): number => {
    const now = new Date();
    const utcStr = now.toLocaleString('sv-SE', { timeZone: 'UTC' });
    const tzStr  = now.toLocaleString('sv-SE', { timeZone: timezone });
    const utcDate = new Date(utcStr.replace(' ', 'T') + 'Z');
    const tzDate  = new Date(tzStr.replace(' ', 'T') + 'Z');
    const offsetMinutes = (tzDate.getTime() - utcDate.getTime()) / 60000;
    return Math.round(offsetMinutes / 60);
  };

  /**
   * Convert a wall-clock datetime string ("YYYY-MM-DDTHH:mm") entered in the given
   * IANA timezone into UTC date ("MM/DD/YYYY") and time ("HH:mm") strings.
   *
   * The backend compares startTime directly against server UTC — so we must
   * send UTC, not local wall-clock time.
   *
   * Algorithm: treat wall-clock as provisional UTC → ask Intl what the target
   * timezone would show at that moment → shift by the difference to reach actual UTC.
   */
  const wallClockToUtc = (datetimeStr: string, timezone: string): { date: string; time: string } => {
    const [datePart, timePart] = datetimeStr.split('T');
    const [year, month, day] = datePart.split('-').map(Number);
    const [hours, minutes] = (timePart || '00:00').split(':').map(Number);

    // Step 1: provisional UTC using wall-clock components
    const provisional = new Date(Date.UTC(year, month - 1, day, hours, minutes));

    // Step 2: what does the target timezone show at this provisional UTC?
    const tzStr = provisional.toLocaleString('sv-SE', { timeZone: timezone });
    const [tzDatePart, tzTimePart] = tzStr.split(' ');
    const [ty, tm, td] = tzDatePart.split('-').map(Number);
    const [th, tmin] = tzTimePart.split(':').map(Number);

    // Step 3: correction = (what we want) − (what timezone currently shows)
    const wantedMs = Date.UTC(year, month - 1, day, hours, minutes);
    const gotMs    = Date.UTC(ty, tm - 1, td, th, tmin);
    const actualUtc = new Date(provisional.getTime() + (wantedMs - gotMs));

    // Step 4: round to nearest 15-min interval in UTC
    const r = Math.round(actualUtc.getUTCMinutes() / 15) * 15;
    if (r === 60) {
      actualUtc.setUTCHours(actualUtc.getUTCHours() + 1);
      actualUtc.setUTCMinutes(0);
    } else {
      actualUtc.setUTCMinutes(r);
    }

    return {
      date: `${String(actualUtc.getUTCMonth() + 1).padStart(2, '0')}/${String(actualUtc.getUTCDate()).padStart(2, '0')}/${actualUtc.getUTCFullYear()}`,
      time: `${String(actualUtc.getUTCHours()).padStart(2, '0')}:${String(actualUtc.getUTCMinutes()).padStart(2, '0')}`,
    };
  };

  /**
   * Parse API error responses into user-friendly messages
   */
  const parseApiError = (status: number, errorData: any, operation: string): string => {
    // CloudiDR nested error format:
    // { error: { code, message, details: { description, timestamp } } }
    const cloudidrDesc = errorData?.error?.details?.description;
    if (typeof cloudidrDesc === 'string' && cloudidrDesc.trim()) return cloudidrDesc;

    // CloudiDR simple error string
    if (typeof errorData?.error === 'string') return errorData.error;

    // Flat field error: { field: "startDate", message: "..." }
    if (errorData?.field && errorData?.message) {
      const fieldLabels: Record<string, string> = {
        startDate: 'Start Date', endDate: 'End Date',
        startTime: 'Start Time', endTime: 'End Time',
        instanceType: 'Instance Type', quantity: 'Quantity',
        zoneId: 'Availability Zone', region: 'Region',
        accountId: 'CSP Account', platform: 'Platform',
        utcOffsetHours: 'Timezone',
      };
      const label = fieldLabels[errorData.field] || errorData.field;
      return `${label}: ${errorData.message}`;
    }

    // Array of validation errors
    if (Array.isArray(errorData?.errors) && errorData.errors.length > 0) {
      return errorData.errors.map((e: any) => {
        const label = e.field ? `${e.field}: ` : '';
        return `${label}${e.message || e.defaultMessage || JSON.stringify(e)}`;
      }).join(' | ');
    }

    // Plain message string
    if (typeof errorData?.message === 'string') return errorData.message;

    // HTTP status fallbacks
    switch (status) {
      case 400: return `Invalid request for ${operation}. Please check your inputs and try again.`;
      case 401: return 'Session expired. Please refresh the page and log in again.';
      case 403: return 'You do not have permission to perform this action.';
      case 404: return `No capacity found matching your criteria. Try different instance type, region, or dates.`;
      case 409: return 'This capacity is no longer available. Please search again.';
      case 422: return 'One or more fields are invalid. Please review your inputs.';
      case 500: return 'A server error occurred. Please try again in a moment.';
      case 503: return 'The capacity service is temporarily unavailable. Please try again shortly.';
      default: return `${operation.charAt(0).toUpperCase() + operation.slice(1)} failed (HTTP ${status}). Please try again.`;
    }
  };

  /**
   * Search capacity (wizard step 1)
   */
  const searchCapacity = useCallback(async (form: SearchCapacityForm): Promise<CapacityPricing | null> => {
    try {
      setIsSearching(true);
      setError(null);
      setSearchResult(null);

      // Convert wall-clock times to UTC before sending — backend validates against server UTC
      const startUtc = wallClockToUtc(form.startDate, form.timeZone);
      const endUtc   = wallClockToUtc(form.endDate,   form.timeZone);

      const response = await fetch('/api/flexcompute/capacity/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          instanceType: form.instanceType,
          platform: form.platform,
          zoneId: form.zoneId,
          startDate: startUtc.date,
          endDate: endUtc.date,
          utcOffsetHours: getUtcOffsetHours(form.timeZone),
          quantity: form.quantity,
          region: form.region,
          capacityName: form.capacityName,
        }),
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(parseApiError(response.status, errorData, 'search'));
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
   */
  const reserveCapacity = useCallback(async (form: SearchCapacityForm): Promise<any> => {
    try {
      setError(null);

      if (!form.cspAccount || !form.zoneId || !form.capacityName || !form.instanceType || !form.startDate || !form.endDate) {
        throw new Error('Missing required fields for reservation');
      }

      // Convert wall-clock times to UTC — backend validates startTime against server UTC
      const startUtc = wallClockToUtc(form.startDate, form.timeZone);
      const endUtc   = wallClockToUtc(form.endDate,   form.timeZone);

      const requestBody = {
        accountId: form.cspAccount,
        zoneId: form.zoneId,
        policyName: form.capacityName,
        instanceType: form.instanceType,
        platform: form.platform,
        startDate: startUtc.date,
        endDate: endUtc.date,
        startTime: startUtc.time,
        endTime: endUtc.time,
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

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        console.error('[FlexCompute] Reserve capacity error response:', errorData);
        throw new Error(parseApiError(response.status, errorData, 'reserve'));
      }

      const data = await response.json();
      console.log('[FlexCompute] Reserve capacity response:', data);
      return data;
    } catch (err) {
      console.error('[FlexCompute] Reserve capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Reserve failed');
      return null;
    }
  }, []);

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
        const errorData = await response.json().catch(() => ({}));
        throw new Error(parseApiError(response.status, errorData, 'configure'));
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
  // Returns null on success, error message string on failure
  const purchaseCapacity = useCallback(async (capacityId: string): Promise<string | null> => {
    try {
      setIsPurchasing(true);
      setError(null);

      const requestBody = { cidrId: capacityId };
      console.log('[FlexCompute] Purchase capacity request:', requestBody);

      const response = await fetch('/api/flexcompute/capacity/purchase', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(parseApiError(response.status, errorData, 'purchase'));
      }

      await fetchCapacities();
      await fetchKpis();
      return null;
    } catch (err) {
      console.error('[FlexCompute] Purchase capacity failed:', err);
      const msg = err instanceof Error ? err.message : 'Purchase failed';
      setError(msg);
      return msg;
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
        const errorData = await response.json().catch(() => ({}));
        throw new Error(parseApiError(response.status, errorData, 'extend'));
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
        const errorData = await response.json().catch(() => ({}));
        throw new Error(parseApiError(response.status, errorData, 'cancel'));
      }

      await fetchCapacities();
      await fetchKpis();
      return true;
    } catch (err) {
      console.error('[FlexCompute] Cancel capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Cancel failed');
      return false;
    }
  }, [fetchCapacities, fetchKpis]);

  // Fetch data on mount
  useEffect(() => {
    fetchCapacities();
    fetchKpis();
    fetchLinkedAccounts();
  }, [fetchCapacities, fetchKpis, fetchLinkedAccounts]);

  return {
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
