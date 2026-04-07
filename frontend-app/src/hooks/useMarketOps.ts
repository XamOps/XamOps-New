import { useState, useEffect, useCallback } from 'react';
import {
  MarketplaceSubscription,
  MeterBillingMonth,
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
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!cloudAccountId || !productCode) {
      setData([]);
      return;
    }
    setLoading(true);
    setError(null);
    apiFetchJson<MeterBillingMonth[]>(
      `/api/marketops/subscriptions/${encodeURIComponent(productCode)}/billing?cloudAccountId=${encodeURIComponent(cloudAccountId)}`
    )
      .then((json) => setData(json))
      .catch((e: any) => setError(e.message ?? 'Failed to load billing'))
      .finally(() => setLoading(false));
  }, [cloudAccountId, productCode]);

  return { data, loading, error };
}

export async function triggerSync(cloudAccountId: string): Promise<MarketOpsSyncResult> {
  return apiFetchJson<MarketOpsSyncResult>(
    `/api/marketops/sync?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
    { method: 'POST' }
  );
}
