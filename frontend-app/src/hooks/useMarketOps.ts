import { useState, useEffect, useCallback } from 'react';
import {
  MarketplaceSubscription,
  MeterBillingMonth,
  MeterBillingResponse,
  DailyMeterBilling,
  DailyMeterBillingResponse,
  MarketOpsSyncResult,
  CatalogBrowseResponse,
  CatalogProduct,
  CatalogFacetsResponse,
  ProductDetailResponse,
  CancelSubscriptionResult,
  PoUpdateResult,
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

export function useCatalogBrowse(
  cloudAccountId: string | null,
  search: string,
  category: string,
  pricingModel = ''
) {
  const [products, setProducts] = useState<CatalogProduct[]>([]);
  const [nextToken, setNextToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetch = useCallback(async (append = false, token?: string | null) => {
    if (!cloudAccountId) {
      setProducts([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const url = `/api/marketops/procurement/catalog/search?cloudAccountId=${encodeURIComponent(cloudAccountId)}`;
      const body: Record<string, unknown> = { entityType: 'SaaSProduct', maxResults: 20 };
      if (search) body.search = search;
      if (category) body.category = category;
      if (pricingModel) body.pricingModel = pricingModel;
      if (token) body.nextToken = token;

      const json = await apiFetchJson<CatalogBrowseResponse>(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      setProducts(prev => append ? [...prev, ...(json.products ?? [])] : (json.products ?? []));
      setNextToken(json.nextToken ?? null);
    } catch (e: any) {
      setError(e.message ?? 'Failed to load catalog');
    } finally {
      setLoading(false);
    }
  }, [cloudAccountId, search, category, pricingModel]);

  useEffect(() => {
    fetch(false);
  }, [fetch]);

  const loadMore = () => {
    if (nextToken) fetch(true, nextToken);
  };

  return { products, nextToken, loading, error, refetch: () => fetch(false), loadMore };
}

export function useCancellationsList(
  cloudAccountId: string | null,
  agreementId: string | null,
  active: boolean
) {
  const [data, setData] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!cloudAccountId || !agreementId || !active) return;
    setLoading(true);
    setError(null);
    apiFetchJson<{ cancellationRequests: Record<string, unknown>[] }>(
      `/api/marketops/procurement/agreements/cancellations/list?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ agreementId }),
      }
    )
      .then(json => setData(json.cancellationRequests ?? []))
      .catch((e: any) => setError(e.message ?? 'Failed to load cancellations'))
      .finally(() => setLoading(false));
  }, [cloudAccountId, agreementId, active]);

  return { data, loading, error };
}

export async function submitCreditRequest(
  cloudAccountId: string,
  entries: {
    agreementId: string;
    originalInvoiceId: string;
    adjustmentAmount: string;
    currencyCode: string;
    adjustmentReasonCode: string;
    description?: string;
    clientToken: string;
  }[]
): Promise<Record<string, unknown>> {
  return apiFetchJson<Record<string, unknown>>(
    `/api/marketops/procurement/agreements/adjustments/batch?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ entries }),
    }
  );
}

export function useProductDetail(cloudAccountId: string | null, listingId: string | null) {
  const [data, setData] = useState<ProductDetailResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!cloudAccountId || !listingId) { setData(null); return; }
    setLoading(true);
    setError(null);

    const base = `/api/marketops/procurement/catalog`;
    const qs   = `?cloudAccountId=${encodeURIComponent(cloudAccountId)}`;
    const post = (path: string, body: object) =>
      apiFetchJson<Record<string, unknown>>(`${base}${path}${qs}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

    // Step 1: fetch listing to get the real productId + offerId
    post('/listing', { listingId })
      .then(listingResp => {
        const listing = (listingResp.listing as Record<string, unknown>) ?? null;
        const entities = (listing?.associatedEntities as any[]) ?? [];
        const productId: string | null = entities[0]?.product?.productId ?? null;
        const offerId: string | null   = entities[0]?.offer?.offerId ?? null;

        // Step 2: fetch purchase options, fulfillment options, and offer terms in parallel
        return Promise.all([
          productId ? post('/purchase-options',    { productId }) : Promise.resolve({ purchaseOptions: [] }),
          productId ? post('/fulfillment-options', { productId }) : Promise.resolve({ fulfillmentOptions: [] }),
          offerId   ? post('/offer-terms',         { offerId })   : Promise.resolve({ terms: [] }),
        ]).then(([purchaseResp, fulfillResp, termsResp]) => {
          setData({
            listing,
            purchaseOptions:    (purchaseResp.purchaseOptions as ProductDetailResponse['purchaseOptions']) ?? [],
            fulfillmentOptions: (fulfillResp.fulfillmentOptions as ProductDetailResponse['fulfillmentOptions']) ?? [],
            offerTerms:         (termsResp.terms as ProductDetailResponse['offerTerms']) ?? [],
          });
        });
      })
      .catch((e: any) => setError(e.message ?? 'Failed to load product detail'))
      .finally(() => setLoading(false));
  }, [cloudAccountId, listingId]);

  return { data, loading, error };
}

export function useCatalogFacets(cloudAccountId: string | null) {
  const [facets, setFacets] = useState<CatalogFacetsResponse['facets']>({});
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!cloudAccountId) return;
    setLoading(true);
    apiFetchJson<CatalogFacetsResponse>(
      `/api/marketops/procurement/catalog/facets?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ facetTypes: ['CATEGORY', 'PRICING_MODEL'] }),
      }
    )
      .then(json => setFacets(json.facets ?? {}))
      .catch(() => setFacets({}))
      .finally(() => setLoading(false));
  }, [cloudAccountId]);

  return { facets, loading };
}

export async function cancelSubscription(
  cloudAccountId: string,
  subscriptionId: number
): Promise<CancelSubscriptionResult> {
  return apiFetchJson<CancelSubscriptionResult>(
    `/api/marketops/subscriptions/${subscriptionId}/cancel?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
    { method: 'POST' }
  );
}

export async function updatePoNumber(
  cloudAccountId: string,
  subscriptionId: number,
  poNumber: string,
  poNotes: string
): Promise<PoUpdateResult> {
  return apiFetchJson<PoUpdateResult>(
    `/api/marketops/subscriptions/${subscriptionId}/po?cloudAccountId=${encodeURIComponent(cloudAccountId)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ poNumber, poNotes }),
    }
  );
}
