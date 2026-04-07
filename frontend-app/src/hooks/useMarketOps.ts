import { useState, useEffect, useCallback } from 'react';
import {
  MarketplaceSubscription,
  MeterBillingMonth,
  MeterBillingResponse,
  DailyMeterBilling,
  DailyMeterBillingResponse,
  MarketOpsSyncResult,
} from '../types/marketops';
import { apiFetchJson } from '../utils/apiClient';

export function useSubscriptions(cloudAccountId: string | null) {
  const [data, setData] = useState<MarketplaceSubscription[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!cloudAccountId) {
      setData([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<MarketplaceSubscription[]>(
        `/api/marketops/subscriptions?cloudAccountId=${encodeURIComponent(cloudAccountId)}`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load subscriptions');
    } finally {
      setLoading(false);
    }
  }, [cloudAccountId]);

  useEffect(() => {
    fetch();
  }, [fetch]);

  return { data, loading, error, refetch: fetch };
}

export function useMeterBilling(cloudAccountId: string | null, productCode: string | null) {
  const [data, setData] = useState<MeterBillingMonth[]>([]);
  const [accountLevel, setAccountLevel] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!cloudAccountId || !productCode) {
      setData([]);
      setAccountLevel(false);
      return;
    }
    setLoading(true);
    setError(null);
    apiFetchJson<MeterBillingResponse>(
      `/api/marketops/subscriptions/${encodeURIComponent(productCode)}/billing?cloudAccountId=${encodeURIComponent(cloudAccountId)}`
    )
      .then((json) => {
        setData(json.entries ?? []);
        setAccountLevel(json.accountLevel ?? false);
      })
      .catch((e: any) => setError(e.message ?? 'Failed to load billing'))
      .finally(() => setLoading(false));
  }, [cloudAccountId, productCode]);

  return { data, loading, error, accountLevel };
}

export function useDailyMeterBilling(
  cloudAccountId: string | null,
  productCode: string | null,
  yearMonth: string | null
) {
  const [data, setData] = useState<DailyMeterBilling[]>([]);
  const [accountLevel, setAccountLevel] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!cloudAccountId || !productCode || !yearMonth) {
      setData([]);
      setAccountLevel(false);
      return;
    }
    setLoading(true);
    setError(null);
    apiFetchJson<DailyMeterBillingResponse>(
      `/api/marketops/subscriptions/${encodeURIComponent(productCode)}/billing/daily` +
      `?cloudAccountId=${encodeURIComponent(cloudAccountId)}&yearMonth=${encodeURIComponent(yearMonth)}`
    )
      .then((json) => {
        setData(json.entries ?? []);
        setAccountLevel(json.accountLevel ?? false);
      })
      .catch((e: any) => setError(e.message ?? 'Failed to load daily billing'))
      .finally(() => setLoading(false));
  }, [cloudAccountId, productCode, yearMonth]);

  return { data, loading, error, accountLevel };
}

export async function triggerSync(cloudAccountId: string): Promise<MarketOpsSyncResult> {
  return apiFetchJson<MarketOpsSyncResult>(
    `/api/marketops/sync?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
    { method: 'POST' }
  );
}
