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

/**
 * Converts a wall-clock datetime string ("YYYY-MM-DDTHH:mm") in the given IANA timezone
 * to a UTC date and time.
 * Returns { date: "YYYY-MM-DD", time: "HH:mm" } in UTC.
 */
function wallClockToUtc(datetime: string, timezone?: string): { date: string; time: string } {
  const tz = timezone || Intl.DateTimeFormat().resolvedOptions().timeZone;
  const parts = datetime.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/);
  if (!parts) {
    throw new Error(`Invalid datetime format: ${datetime}. Expected "YYYY-MM-DDTHH:mm".`);
  }
  const [, year, month, day, hour, minute] = parts;
  // Treat the datetime as UTC temporarily to compute the timezone offset at that moment
  const assumedUtc = new Date(`${year}-${month}-${day}T${hour}:${minute}:00Z`);
  // Re-format in the target timezone to find what local time corresponds to that UTC instant
  const localParts = new Intl.DateTimeFormat('en-CA', {
    timeZone: tz,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(assumedUtc);
  const get = (type: string) => Number(localParts.find(p => p.type === type)?.value ?? 0);
  const localMs = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'));
  const offsetMs = localMs - assumedUtc.getTime();
  // Wall-clock ms (treated as UTC) minus offset gives true UTC
  const wallMs = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
  const utcDate = new Date(wallMs - offsetMs);
  const pad = (n: number) => String(n).padStart(2, '0');
  return {
    date: `${utcDate.getUTCFullYear()}-${pad(utcDate.getUTCMonth() + 1)}-${pad(utcDate.getUTCDate())}`,
    time: `${pad(utcDate.getUTCHours())}:${pad(utcDate.getUTCMinutes())}`,
  };
}

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
  const [kpiCapacities, setKpiCapacities] = useState<FlexComputeCapacity[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  // Linked accounts from CloudiDR
  const [linkedAccounts, setLinkedAccounts] = useState<any[]>([]);
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

      setKpiCapacities(allData);
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
   * Returns the UTC offset in whole hours for an IANA timezone (rounded to nearest integer).
   * CloudiDR only accepts integer offsets. Fractional zones (e.g. IST +5:30) are rounded.
   */
  const getUtcOffsetHours = (timezone: string): number => {
    const now = new Date();
    const utcStr = now.toLocaleString('sv-SE', { timeZone: 'UTC' });
    const tzStr = now.toLocaleString('sv-SE', { timeZone: timezone });
    const utcDate = new Date(utcStr.replace(' ', 'T') + 'Z');
    const tzDate = new Date(tzStr.replace(' ', 'T') + 'Z');
    return Math.round((tzDate.getTime() - utcDate.getTime()) / 3600000);
  };

  /**
   * Extract wall-clock date ("MM/DD/YYYY") and time ("HH:mm") from a datetime string
   * ("YYYY-MM-DDTHH:mm") without any timezone conversion.
   * CloudiDR handles the conversion using the utcOffset we send alongside.
   */
  const wallClockParts = (datetimeStr: string): { date: string; time: string } => {
    const [datePart, timePart] = datetimeStr.split('T');
    const [year, month, day] = datePart.split('-');
    return {
      date: `${month}/${day}/${year}`,
      time: timePart || '00:00',
    };
  };

  /**
   * Parse API error responses into user-friendly messages
   */
  const parseApiError = (status: number, errorData: any, operation: string): string => {
    // CloudiDR nested error format:
    // { error: { code, message, details: { description, timestamp } } }
    const cloudidrDesc = errorData?.error?.details?.description;
    if (typeof cloudidrDesc === 'string' && cloudidrDesc.trim()) {
      // Map internal CloudiDR messages to user-friendly text
      if (cloudidrDesc.toLowerCase().includes('payment method') || cloudidrDesc.toLowerCase().includes('payment methods not found')) {
        return 'No payment method is configured for this account. Please contact XamOps support to complete setup before purchasing capacity.';
      }
      return cloudidrDesc;
    }

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

      const startParts = wallClockParts(form.startDate);
      const endParts = wallClockParts(form.endDate);
      const utcOffset = getUtcOffsetHours(form.timeZone);

      const response = await fetch('/api/flexcompute/capacity/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          instanceType: form.instanceType,
          platform: form.platform,
          zoneId: form.zoneId,
          startDate: startParts.date,
          endDate: endParts.date,
          utcOffsetHours: utcOffset,
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
        awsPrice: data.aws_price || data.awsPrice || 0,
        ourPrice: (data.aws_price || data.awsPrice || 0) * 0.75,
        savingsPercent: 25,
        fees: data.fees || 0,
        total: (data.aws_price || data.awsPrice || 0) * 0.75,
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

      const startParts = wallClockParts(form.startDate);
      const endParts = wallClockParts(form.endDate);
      const utcOffset = getUtcOffsetHours(form.timeZone);

      const requestBody = {
        accountId: form.cspAccount,
        zoneId: form.zoneId,
        policyName: form.capacityName,
        instanceType: form.instanceType,
        platform: form.platform,
        startDate: startParts.date,
        endDate: endParts.date,
        startTime: startParts.time,
        endTime: endParts.time,
        utcOffsetHours: String(utcOffset),
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

      const isCustom = (value: string) => {
        if (!value || value.trim() === '') return false;
        const v = value.trim().toLowerCase();
        return v !== 'default' && v !== 'none';
      };

      const requestBody: Record<string, unknown> = { cidrId: form.capacityId };
      if (isCustom(form.vpcId))         requestBody.vpc = form.vpcId.trim();
      if (isCustom(form.securityGroupId)) requestBody.securityGroups = form.securityGroupId.trim();
      if (isCustom(form.sshKeyName))    requestBody.sshPairKey = form.sshKeyName.trim();
      if (isCustom(form.amiImageId))    requestBody.amiImage = form.amiImageId.trim();
      if (form.ebsVolumeSize && form.ebsVolumeSize !== 10) requestBody.ebsVolumeSize = form.ebsVolumeSize;

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

      await fetchCapacities();
      return true;
    } catch (err) {
      console.error('[FlexCompute] Configure capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Configuration failed');
      return false;
    }
  }, [fetchCapacities]);

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
   * Extend capacity end date.
   * Accepts a wall-clock datetime string ("YYYY-MM-DDTHH:mm") in the given IANA timezone
   * and converts to UTC before sending to the backend.
   */
  const extendCapacity = useCallback(async (
    cidrId: string,
    datetime: string,
    timezone: string,
  ): Promise<boolean> => {
    try {
      setError(null);

      const parts = wallClockParts(datetime);
      const requestBody = { cidrId, endDate: parts.date, endTime: parts.time, utcOffset: String(getUtcOffsetHours(timezone)) };
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
   * Update auto-renewal settings for a capacity
   */
  const renewCapacity = useCallback(async (
    cidrId: string,
    autoRenewalEnabled: boolean,
    frequency: '24hrs' | '1week' | '1month',
  ): Promise<boolean> => {
    const frequencyMap: Record<string, string> = {
      '24hrs': 'one-day',
      '1week': 'one-week',
      '1month': 'one-month',
    };
    try {
      setError(null);
      const requestBody = {
        cidrId,
        autoRenewalEnabled,
        frequency: frequencyMap[frequency] ?? 'one-day',
      };
      console.log('[FlexCompute] Renew capacity request:', requestBody);

      const response = await fetch('/api/flexcompute/capacity/auto-renewal', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(requestBody),
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({}));
        throw new Error(parseApiError(response.status, errorData, 'auto-renewal'));
      }

      return true;
    } catch (err) {
      console.error('[FlexCompute] Renew capacity failed:', err);
      setError(err instanceof Error ? err.message : 'Auto-renewal update failed');
      return false;
    }
  }, []);

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
    kpiCapacities,
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
    renewCapacity,
    setSearchResult,

    // Error
    error,
    setError,
  };
};
