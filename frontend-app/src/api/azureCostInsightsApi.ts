import { apiFetch } from '../utils/apiClient';
import type {
  ChargeTypeAmount,
  DataTransferBreakdown,
  PriceVolumeChange,
  ResourceChange,
  ServiceCharge,
  TopResources,
} from './curInsightsApi';

/**
 * The Azure Cost Management dimensions the Azure cost page never read.
 *
 * <p>Every type here is imported rather than redeclared, and that is the point: the backend returns
 * field-for-field identical payloads to the AWS Cost and Usage Report endpoints, so the Cost page's
 * invoice, resource, network and price-vs-volume sections render Azure with one implementation. A
 * parallel set of types would compile today and drift the moment one side gained a field. GCP made
 * the same choice in `gcpCostInsightsApi`, and the three now share the components as well as the
 * shapes.
 *
 * <p>Null means "this subscription cannot answer". Which dimensions Cost Management will group by
 * depends on the agreement behind the subscription — EA, MCA and pay-as-you-go differ, and there is
 * no catalogue to consult — so a section that needs `ResourceId` or `UsageQuantity` can be
 * unavailable on an otherwise healthy account. The caller hides the section rather than drawing an
 * empty one, exactly as on the AWS and GCP sides. The reason travels in the 409 body for support,
 * not for display.
 */

const BASE = '/api/xamops/azure/cost-insights';

/** Shared guard: a non-2xx is "not applicable here", and a malformed body is not data. */
const read = async <T>(path: string, isValid: (body: unknown) => boolean): Promise<T | null> => {
  const res = await apiFetch(path);
  // 409 = this scope cannot be asked, 502 = Cost Management could not be read. Neither is worth
  // interrupting the page for: the rest of the cost page is unaffected.
  if (!res.ok) return null;
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    // Empty body, or HTML from something upstream that answered in the service's place.
    return null;
  }
  if (!isValid(body)) {
    console.warn('[Azure cost insights] Ignoring unexpected response shape from', path);
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
    // Keyed on the fields callers actually read: the 409 and 502 bodies are objects too, so only the
    // presence of real data separates a payload from an explanation of its absence.
    return (
      lists.every((f) => Array.isArray(record[f])) &&
      numbers.every((f) => typeof record[f] === 'number')
    );
  };

const withPeriod = (path: string, subscriptionId: string, period?: string): string =>
  `${BASE}/${path}/${encodeURIComponent(subscriptionId)}` +
  (period ? `?period=${encodeURIComponent(period)}` : '');

/**
 * Every billed line at invoice grain: service, region, meter, unit, quantity, cost.
 *
 * Azure spreads across three columns what AWS puts in one usage type, so `description` is the meter
 * name a reader recognises ("D2 v3/D2s v3") and `usageType` is the meter subcategory behind it
 * ("Dsv3 Series"). `quantity` reads zero on a scope that will not aggregate `UsageQuantity` rather
 * than failing the whole section.
 */
export const fetchAzureChargesByService = (
  subscriptionId: string,
  period?: string,
): Promise<ServiceCharge[] | null> =>
  read<ServiceCharge[]>(withPeriod('charges-by-service', subscriptionId, period), isList);

/**
 * What each individual resource cost — the VM, the disk, the storage account.
 *
 * The `instanceType` field carries the **resource group** on Azure, which is what the AWS component
 * renders as a badge beside the name. It is the Azure fact worth putting there: a resource group is
 * how Azure spend is owned, found and deleted.
 *
 * Null when the scope will not group by `ResourceId` at all — a limit of the agreement behind the
 * subscription, not a subscription with no resources.
 */
export const fetchAzureTopResources = (
  subscriptionId: string,
  period?: string,
): Promise<TopResources | null> =>
  read<TopResources>(
    withPeriod('top-resources', subscriptionId, period),
    hasFields(['resources'], ['totalCost']),
  );

/**
 * Network spend by category, with the route each charge took and the resources responsible.
 *
 * `routeDataAvailable` is always false: Cost Management carries no from/to column at any scope, so
 * every route shown is inferred from the meter name and the billed region. The payload says so
 * rather than letting the UI present an inferred route as a reported one.
 */
export const fetchAzureNetworkCosts = (
  subscriptionId: string,
  period?: string,
): Promise<DataTransferBreakdown | null> =>
  read<DataTransferBreakdown>(
    withPeriod('network', subscriptionId, period),
    hasFields(['charges', 'flows']),
  );

/**
 * How much of each service's month-on-month change was rate, and how much was usage.
 *
 * Null when the scope will not aggregate `UsageQuantity`: without quantities the split would
 * attribute every movement to price, which describes the query rather than the bill.
 */
export const fetchAzurePriceVsVolume = (
  subscriptionId: string,
  period?: string,
): Promise<PriceVolumeChange[] | null> =>
  read<PriceVolumeChange[]>(withPeriod('price-vs-volume', subscriptionId, period), isList);

/**
 * One resource's month-over-month movement, which meters moved, and its daily spend.
 *
 * Null when the scope will not group by `ResourceId` — the same condition that makes the resource table
 * unavailable, so a reader who can see the table can open the panel.
 */
export const fetchAzureResourceChange = (
  subscriptionId: string,
  resourceId: string,
  period?: string,
): Promise<ResourceChange | null> => {
  const params = new URLSearchParams({ resourceId });
  if (period) params.set('period', period);
  return read<ResourceChange>(
    `${BASE}/resource-change/${encodeURIComponent(subscriptionId)}?${params}`,
    hasFields(['lines', 'daily'], ['costChange']),
  );
};

/** Usage against purchases, refunds, unused commitment and rounding, on an amortized view. */
export const fetchAzureChargeTypes = (
  subscriptionId: string,
  period?: string,
): Promise<ChargeTypeAmount[] | null> =>
  read<ChargeTypeAmount[]>(withPeriod('charge-types', subscriptionId, period), isList);

/** Reservation and savings plan spend that bought nothing. */
export interface AzureUnusedCommitment {
  /** Unused reservation amortisation for the month. */
  unusedRecurringFee: number;
  /** Unused savings plan amortisation for the month. */
  unusedUpfrontFee: number;
  total: number;
}

export const fetchAzureUnusedCommitment = (
  subscriptionId: string,
  period?: string,
): Promise<AzureUnusedCommitment | null> =>
  read<AzureUnusedCommitment>(
    withPeriod('unused-commitment', subscriptionId, period),
    hasFields([], ['total']),
  );

// ── Breakdown ────────────────────────────────────────────────────────────────

/** The dimensions the Azure cost breakdown can be grouped by. */
export type AzureBreakdownDimension =
  | 'SERVICE'
  | 'REGION'
  | 'RESOURCE_GROUP'
  | 'RESOURCE_TYPE'
  | 'CHARGE_TYPE'
  | 'PRICING_MODEL'
  | 'PUBLISHER'
  | 'TAG';

/** One row of the breakdown. Matches `CostDataItem`, so the shared table renders it unchanged. */
export interface AzureCostAmount {
  name: string;
  amount: number;
}

export interface AzureBreakdown {
  costs: AzureCostAmount[];
  /** ISO code every amount above is denominated in. */
  currency: string;
  groupBy: AzureBreakdownDimension;
  tagKey?: string;
}

/**
 * Spend for an arbitrary date range, grouped by one dimension.
 *
 * The addition that matters most for Azure is `RESOURCE_GROUP`: it is how Azure spend is actually
 * owned — one team, one application, one environment — so grouping by it answers chargeback
 * questions a meter-category breakdown cannot, and it has no AWS equivalent to have been copied
 * from. `TAG` needs a `tagKey`; the rest ignore it.
 *
 * Unlike the existing Azure cost endpoints, which serve a fixed month-to-date figure out of the
 * ingestion cache, this can be asked about any range.
 */
export const fetchAzureBreakdown = (
  subscriptionId: string,
  startDate: string,
  endDate: string,
  groupBy: AzureBreakdownDimension,
  tagKey?: string,
): Promise<AzureBreakdown | null> => {
  const params = new URLSearchParams({ startDate, endDate, groupBy });
  if (groupBy === 'TAG' && tagKey) params.set('tagKey', tagKey);
  return read<AzureBreakdown>(
    `${BASE}/breakdown/${encodeURIComponent(subscriptionId)}?${params}`,
    hasFields(['costs']),
  );
};

/**
 * Every tag key Cost Management knows about, for the chargeback picker.
 *
 * Always resolves to a list — an empty one included. An absent picker is a smaller loss than a null
 * the filter bar would read as "hide yourself".
 */
export const fetchAzureTagKeys = async (subscriptionId: string): Promise<string[]> => {
  const keys = await read<string[]>(
    `${BASE}/tag-keys/${encodeURIComponent(subscriptionId)}`,
    isList,
  );
  return keys ?? [];
};

/**
 * The currency every Azure cost figure for this subscription is denominated in.
 *
 * Its own call because the page needs it before any section has loaded — the KPI cards render a
 * symbol on first paint. Falls back to USD, which is what the components assumed before one of them
 * started assuming rupees.
 */
export const fetchAzureCurrency = async (subscriptionId: string): Promise<string> => {
  const body = await read<{ currency?: string }>(
    `${BASE}/currency/${encodeURIComponent(subscriptionId)}`,
    (b) => typeof b === 'object' && b !== null && !Array.isArray(b),
  );
  return body?.currency || 'USD';
};
