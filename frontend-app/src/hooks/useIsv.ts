import { useState, useEffect, useCallback } from 'react';
import {
  IsvProduct,
  IsvCustomer,
  MeteringRecord,
  SubscriptionEvent,
  BuyerSubscription,
  IsvAnalytics,
  IsvProductRequest,
  IsvDashboard,
} from '../types/isv';
import { apiFetchJson } from '../utils/apiClient';

// ─── Product list ─────────────────────────────────────────────────────────────

export function useIsvProducts(ownerEmail?: string) {
  const [data, setData] = useState<IsvProduct[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const url = ownerEmail
        ? `/api/isv/products?ownerEmail=${encodeURIComponent(ownerEmail)}`
        : '/api/isv/products';
      const json = await apiFetchJson<IsvProduct[]>(url);
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load ISV products');
    } finally {
      setLoading(false);
    }
  }, [ownerEmail]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── Single product dashboard ─────────────────────────────────────────────────

export function useIsvDashboard(productCode: string | null) {
  const [data, setData] = useState<IsvDashboard | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!productCode) { setData(null); return; }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<IsvDashboard>(
        `/api/isv/products/${encodeURIComponent(productCode)}/dashboard`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load dashboard');
    } finally {
      setLoading(false);
    }
  }, [productCode]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── Customers ────────────────────────────────────────────────────────────────

export function useIsvCustomers(productCode: string | null) {
  const [data, setData] = useState<IsvCustomer[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!productCode) { setData([]); return; }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<IsvCustomer[]>(
        `/api/isv/products/${encodeURIComponent(productCode)}/customers`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load customers');
    } finally {
      setLoading(false);
    }
  }, [productCode]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── Metering records ─────────────────────────────────────────────────────────

export function useIsvMeteringRecords(productCode: string | null, limit = 50) {
  const [data, setData] = useState<MeteringRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!productCode) { setData([]); return; }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<MeteringRecord[]>(
        `/api/isv/products/${encodeURIComponent(productCode)}/metering-records?limit=${limit}`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load metering records');
    } finally {
      setLoading(false);
    }
  }, [productCode, limit]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── SNS Subscription Events ──────────────────────────────────────────────────

export function useSubscriptionEvents(productCode: string | null, limit = 50) {
  const [data, setData] = useState<SubscriptionEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!productCode) { setData([]); return; }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<SubscriptionEvent[]>(
        `/api/isv/products/${encodeURIComponent(productCode)}/events?limit=${limit}`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load subscription events');
    } finally {
      setLoading(false);
    }
  }, [productCode, limit]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── Buyer Usage ──────────────────────────────────────────────────────────────

export function useBuyerUsage(cloudAccountId: string | null | undefined) {
  const [data, setData] = useState<BuyerSubscription[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!cloudAccountId) { setData([]); return; }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<BuyerSubscription[]>(
        `/api/metering/buyer/usage?cloudAccountId=${encodeURIComponent(cloudAccountId)}`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load buyer usage');
    } finally {
      setLoading(false);
    }
  }, [cloudAccountId]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── ISV Analytics ────────────────────────────────────────────────────────────

export function useIsvAnalytics(productCode: string | null) {
  const [data, setData] = useState<IsvAnalytics | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async () => {
    if (!productCode) { setData(null); return; }
    setLoading(true);
    setError(null);
    try {
      const json = await apiFetchJson<IsvAnalytics>(
        `/api/isv/products/${encodeURIComponent(productCode)}/analytics`
      );
      setData(json);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load analytics');
    } finally {
      setLoading(false);
    }
  }, [productCode]);

  useEffect(() => { fetch(); }, [fetch]);
  return { data, loading, error, refetch: fetch };
}

// ─── Mutations ────────────────────────────────────────────────────────────────

export async function createIsvProduct(request: IsvProductRequest): Promise<IsvProduct> {
  return apiFetchJson<IsvProduct>('/api/isv/products', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
}

export async function updateIsvProduct(productCode: string, request: Partial<IsvProductRequest>): Promise<IsvProduct> {
  return apiFetchJson<IsvProduct>(`/api/isv/products/${encodeURIComponent(productCode)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
}

export async function deactivateIsvProduct(productCode: string): Promise<IsvProduct> {
  return apiFetchJson<IsvProduct>(`/api/isv/products/${encodeURIComponent(productCode)}`, {
    method: 'DELETE',
  });
}

export async function regenerateApiKey(productCode: string): Promise<{ productCode: string; newApiKey: string; message: string }> {
  return apiFetchJson(`/api/isv/products/${encodeURIComponent(productCode)}/regenerate-key`, {
    method: 'POST',
  });
}

export async function cancelIsvCustomer(productCode: string, customerId: string): Promise<void> {
  await apiFetchJson(`/api/isv/products/${encodeURIComponent(productCode)}/customers/${encodeURIComponent(customerId)}`, {
    method: 'DELETE',
  });
}

export async function triggerMeteringEngine(): Promise<Record<string, unknown>> {
  return apiFetchJson('/api/isv/metering/trigger', { method: 'POST' });
}
