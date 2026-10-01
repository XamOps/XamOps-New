import { apiFetch } from '../utils/apiClient';
import type {
  DataTransferBreakdown,
  PriceVolumeChange,
  ResourceChange,
  SavingsRate,
  ServiceCharge,
  TopResources,
} from './curInsightsApi';

/**
 * One line item type's share of the bill.
 *
 * Declared here rather than in `curInsightsApi` because AWS exposes the identical
 * `/cur-insights/charge-types` endpoint and has no frontend reading it yet. When it grows one,
 * this moves there and both import it.
 */
export interface ChargeTypeAmount {
  /** "Regular", "Tax", "Committed usage discount", "Sustained usage discount", "Free tier". */
  chargeType: string;
  /** Negative for credits, as the export records them, so the rows sum to the invoice. */
  cost: number;
}

/**
 * The GCP billing export read through the same section contracts as the AWS report.
 *
 * <p>Every type here is imported from `curInsightsApi` rather than redeclared, and that is the
 * point: the backend returns field-for-field identical payloads, so the Cost page's network,
 * price-vs-volume and charge-type sections render either provider with one implementation. A
 * parallel set of types would compile today and drift the moment one side gained a field.
 *
 * <p>Null means "this project cannot answer" — reseller-billed, no readable export, or an export
 * predating a column the section needs. The caller hides the section rather than drawing an empty
 * one, exactly as on the AWS side. The reason travels in the 409 body for support, not for display;
 * `/api/xamops/gcp/costs/billing-status` is what the page shows a customer.
 */

const BASE = '/api/xamops/gcp/cost-insights';

/** Shared guard: a non-2xx is "not applicable here", and a malformed body is not data. */
const read = async <T>(path: string, isValid: (body: unknown) => boolean): Promise<T | null> => {
  const res = await apiFetch(path);
  if (!res.ok) return null;
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    return null;
  }
  if (!isValid(body)) {
    console.warn('[GCP cost insights] Ignoring unexpected response shape from', path);
    return null;
  }
  return body as T;
};

const isList = (body: unknown): boolean => Array.isArray(body);

const hasFields =
  (lists: string[] = [], numbers: string[] = []) =>
  (body: unknown): boolean => {
    if (typeof body !== 'object' || body === null || Array.isArray(body)) return false;
    const record = body as Record<string, unknown>;
    // Keyed on the fields callers actually read: the 409 and 502 bodies are objects too, so only
    // the presence of real data separates a payload from an explanation of its absence.
    return (
      lists.every((f) => Array.isArray(record[f])) &&
      numbers.every((f) => typeof record[f] === 'number')
    );
  };

const withPeriod = (path: string, projectId: string, period?: string): string =>
  `${BASE}/${path}/${encodeURIComponent(projectId)}` +
  (period ? `?period=${encodeURIComponent(period)}` : '');

/**
 * Network spend by category, with the route each charge took.
 *
 * `flows` is always empty and `resourceDataAvailable` always false for now: attributing a charge
 * to a named resource needs GCP's detailed export (`gcp_billing_export_resource_v1`), which
 * nothing in the product records yet.
 */
export const fetchGcpNetworkCosts = (
  projectId: string,
  period?: string,
): Promise<DataTransferBreakdown | null> =>
  read<DataTransferBreakdown>(withPeriod('network', projectId, period), hasFields(['charges', 'flows']));

/** Net spend against list price. Measures sustained-use and committed-use discounts and credits. */
export const fetchGcpSavingsRate = (
  projectId: string,
  period?: string,
): Promise<SavingsRate | null> =>
  read<SavingsRate>(
    withPeriod('savings-rate', projectId, period),
    hasFields([], ['publicOnDemandCost', 'rate']),
  );

/** Usage vs tax vs adjustments, with credits split by the kind of discount they are. */
export const fetchGcpChargeTypes = (
  projectId: string,
  period?: string,
): Promise<ChargeTypeAmount[] | null> =>
  read<ChargeTypeAmount[]>(withPeriod('charge-types', projectId, period), isList);

/** Every billed line at invoice grain: service, region, SKU, unit, quantity, cost. */
export const fetchGcpChargesByService = (
  projectId: string,
  period?: string,
): Promise<ServiceCharge[] | null> =>
  read<ServiceCharge[]>(withPeriod('charges-by-service', projectId, period), isList);

/**
 * What each individual resource cost.
 *
 * Null on a standard billing export, which records no resource name at all — a configuration fact
 * the caller reports rather than drawing an empty table. GCP's *detailed* export carries it.
 */
export const fetchGcpTopResources = (
  projectId: string,
  period?: string,
): Promise<TopResources | null> =>
  read<TopResources>(
    withPeriod('top-resources', projectId, period),
    hasFields(['resources'], ['totalCost']),
  );

/** How much of each service's month-on-month change was rate, and how much was usage. */
export const fetchGcpPriceVsVolume = (
  projectId: string,
  period?: string,
): Promise<PriceVolumeChange[] | null> =>
  read<PriceVolumeChange[]>(withPeriod('price-vs-volume', projectId, period), isList);

/**
 * One resource's month-over-month movement, which SKUs moved, and its daily spend.
 *
 * Null on the standard billing export, which records no resource name — the same condition that makes
 * the resource table unavailable, so a reader who can see the table can open the panel.
 */
export const fetchGcpResourceChange = (
  projectId: string,
  resourceId: string,
  period?: string,
): Promise<ResourceChange | null> => {
  const params = new URLSearchParams({ resourceId });
  if (period) params.set('period', period);
  return read<ResourceChange>(
    `${BASE}/resource-change/${encodeURIComponent(projectId)}?${params}`,
    hasFields(['lines', 'daily'], ['costChange']),
  );
};
