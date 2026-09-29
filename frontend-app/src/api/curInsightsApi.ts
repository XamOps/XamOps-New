import { apiFetch } from '../utils/apiClient';

/**
 * Cost insights that only a Cost and Usage Report can answer.
 *
 * Every one of these returns 409 when the account has no usable report — deliberately,
 * because there is no Cost Explorer equivalent to fall back to. Callers treat that as
 * "hide the widget", never as an error worth showing.
 */

// ── Response shape checking ──────────────────────────────────────────────────

/**
 * Reads a JSON body, returning null when it is unreadable or cannot be the type the
 * endpoint declares.
 *
 * <p>`Response.json()` is typed `any`, so a body of the wrong shape used to be cast into
 * a typed field unchecked and then fail wherever it was eventually used, far from the
 * request that produced it. An `{error, detail}` object arriving with a 2xx status — a
 * proxy answering instead of the service — reached `for (const c of charges)` in the
 * charges table and took the entire Cost page down with "N is not iterable", losing the
 * budgets, breakdown and forecast along with it.
 *
 * <p>Null is the right answer rather than a thrown error because these endpoints already
 * have a meaning for "no usable data" that every caller handles: the section hides
 * itself. A body that cannot be the declared type is that case.
 */
const readShape = async <T>(
  res: Response,
  isValid: (body: unknown) => boolean,
): Promise<T | null> => {
  let body: unknown;
  try {
    body = await res.json();
  } catch {
    // Empty body, or HTML from something upstream that answered in the service's place.
    return null;
  }
  if (!isValid(body)) {
    console.warn('[CUR] Ignoring unexpected response shape from', res.url);
    return null;
  }
  return body as T;
};

const isRecord = (body: unknown): body is Record<string, unknown> =>
  typeof body === 'object' && body !== null && !Array.isArray(body);

/** For endpoints declared to return a list. */
const isList = (body: unknown): boolean => Array.isArray(body);

/**
 * For endpoints declared to return a record, keyed on the fields callers actually read.
 *
 * <p>"Is an object" alone would not do: the 409 and 502 bodies are objects too, so only
 * the presence of the real fields separates data from an explanation of its absence.
 */
const isRecordWith =
  (lists: string[] = [], numbers: string[] = []) =>
  (body: unknown): boolean =>
    isRecord(body) &&
    lists.every((field) => Array.isArray(body[field])) &&
    numbers.every((field) => typeof body[field] === 'number');

export interface SavingsRate {
  /** What was actually paid, with commitments spread across their term. */
  amortizedCost: number;
  /** What the same usage would have cost at public on-demand rates. */
  publicOnDemandCost: number;
  savings: number;
  /** 0–1. Multiply by 100 for a percentage. */
  rate: number;
}

/** Null means "no report for this account" — the widget should not render. */
export const fetchSavingsRate = async (
  accountId: string,
  period?: string,
): Promise<SavingsRate | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/savings-rate/${encodeURIComponent(accountId)}${query}`,
  );
  // 409 = no report configured, 502 = the report could not be read. Neither is worth
  // interrupting the page for: the rest of FinOps is unaffected.
  if (!res.ok) return null;
  return readShape<SavingsRate>(res, isRecordWith([], ['publicOnDemandCost', 'rate']));
};

/** One service's cost change, split into the part caused by rate and the part by usage. */
export interface PriceVolumeChange {
  service: string;
  priorCost: number;
  currentCost: number;
  costChange: number;
  priorQuantity: number;
  currentQuantity: number;
  /** Change attributable to a different unit rate. */
  priceEffect: number;
  /** Change attributable to using more or less. */
  volumeEffect: number;
}

/**
 * Compares a month against the one before it, splitting each service's change into rate
 * and usage.
 *
 * Null means "no report for this account" — the section should not render.
 */
export const fetchPriceVsVolume = async (
  accountId: string,
  period?: string,
): Promise<PriceVolumeChange[] | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/price-vs-volume/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return readShape<PriceVolumeChange[]>(res, isList);
};

/** One billed line as the AWS invoice shows it. */
export interface ServiceCharge {
  service: string;
  /** Region code, or "Global" for region-less charges like tax and support. */
  region: string;
  description: string;
  usageType: string;
  unit: string;
  quantity: number;
  cost: number;
}

/**
 * Charges by service in invoice form — description, usage quantity, amount.
 *
 * Defaults to the current month, which is meaningful here: it describes what has been
 * billed so far rather than comparing two periods of different length.
 */
export const fetchChargesByService = async (
  accountId: string,
  period?: string,
): Promise<ServiceCharge[] | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/charges-by-service/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return readShape<ServiceCharge[]>(res, isList);
};

/** Commitment spend that bought nothing. */
export interface UnusedCommitment {
  /** Recurring (hourly) reservation fees not matched by usage. */
  unusedRecurringFee: number;
  /** Amortised upfront portion not matched by usage. */
  unusedUpfrontFee: number;
  total: number;
}

/**
 * Reservation and Savings Plan spend that went unused in a month.
 *
 * Null means "no report for this account" — the caller should omit the card.
 */
export const fetchUnusedCommitment = async (
  accountId: string,
  period?: string,
): Promise<UnusedCommitment | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/unused-commitment/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return readShape<UnusedCommitment>(res, isRecordWith([], ['total']));
};

/** One network charge, already categorised by what drives it, and routed where it went. */
export interface TransferCharge {
  /** NAT Gateway, Cross-AZ, Inter-region, Internet egress, Internet ingress, Other network. */
  category: string;
  service: string;
  /** Raw AWS usage type, e.g. "APS3-NatGateway-Bytes". */
  usageType: string;
  /** GB, Hrs, and so on. */
  unit: string;
  quantity: number;
  cost: number;
  /** The billed region, or "Global" for charges that have none. */
  region: string;
  /**
   * Where the traffic came from, as the report names it: "Asia Pacific (Mumbai)",
   * "ap-south-1", or an edge location. Empty when unknown — check
   * `routeDataAvailable` before reading an empty value as "no route".
   */
  fromLocation: string;
  /** Where it went, on the same terms. */
  toLocation: string;
  /**
   * True when the route was read out of the usage type because the report's own location
   * columns were blank. Worth surfacing: a reader deciding whether to act on
   * "Mumbai → N. Virginia" is entitled to know whether AWS said it or we inferred it.
   */
  routeDerived: boolean;
}

/**
 * One resource's traffic on one route — the finest thing a CUR can describe.
 *
 * Deliberately not "resource to resource". A line item names only the resource being
 * charged, so the far end is a location and never a peer id. That answer is in VPC Flow
 * Logs, not in any billing report.
 */
export interface TransferFlow {
  category: string;
  /** The gateway, bucket, instance or load balancer. Empty for charges with no resource. */
  resourceId: string;
  /** The resource's `Name` tag, or "" when the report does not carry it. */
  name: string;
  service: string;
  region: string;
  /** The zone billed, which is what makes a cross-AZ row actionable. Often empty. */
  availabilityZone: string;
  fromLocation: string;
  toLocation: string;
  /** True when the route came from the usage type rather than the report's own columns. */
  routeDerived: boolean;
  usageType: string;
  /** GB-denominated usage only, so a gateway's hours are not added to its bytes. */
  gigabytes: number;
  /** Hour-denominated usage only. */
  hours: number;
  cost: number;
  /** Every flow in this category, not only the listed ones. */
  categoryCost: number;
  /** How many there were in total, so the caller can say what it is not showing. */
  categoryFlows: number;
}

export interface DataTransferBreakdown {
  /** Billing grain. Correct even on a report with no resource ids. */
  charges: TransferCharge[];
  /** Costliest resource-and-route pairs per category. Empty without resource ids. */
  flows: TransferFlow[];
  /**
   * Whether the report carries the from/to location columns at all.
   *
   * False does not mean the routes are missing — they are recovered from the usage type —
   * it means every route shown was inferred rather than reported.
   */
  routeDataAvailable: boolean;
  /**
   * Whether the report carries `line_item_resource_id`.
   *
   * False means `flows` is empty because the report cannot attribute charges to resources,
   * not because no resource moved any bytes. The two are indistinguishable from an empty
   * list, and "no resources" would be a claim about the infrastructure when the truth is a
   * setting on the export.
   */
  resourceDataAvailable: boolean;
}

/**
 * Network spend for a month, split into the categories that drive it, the routes the
 * traffic took, and the resources that moved it.
 *
 * Invisible in every other view: a service breakdown files NAT gateway charges under EC2
 * and cross-AZ traffic under whichever service moved the bytes, so the network total never
 * appears anywhere as a single number — let alone attributed to a region pair or a resource.
 *
 * Null means "no report for this account" — the caller should omit the section.
 */
export const fetchDataTransferCosts = async (
  accountId: string,
  period?: string,
): Promise<DataTransferBreakdown | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/data-transfer/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return readShape<DataTransferBreakdown>(res, isRecordWith(['charges', 'flows']));
};

/** One resource's cost for a month. An empty `resourceId` is an account-level charge. */
export interface ResourceCost {
  /** e.g. "i-0a3f2b1c8d4e5f6a7", or "" for tax, support and other resource-less charges. */
  resourceId: string;
  /**
   * The resource's `Name` tag, or "" when the report does not carry it — normal for an
   * account that never activated the tag for cost allocation, so callers fall back to
   * the id rather than treating it as missing data.
   */
  name: string;
  service: string;
  region: string;
  /** Instance type where the resource has one; empty otherwise. */
  instanceType: string;
  cost: number;
}

export interface TopResources {
  resources: ResourceCost[];
  /** Every resource in the period, not only the returned ones. */
  totalCost: number;
  /** How many there were in total, so the caller can say what it is not showing. */
  groupCount: number;
}

/**
 * What each individual resource cost, most expensive first.
 *
 * Has no Cost Explorer equivalent: CE's resource-level view covers EC2 alone and retains
 * fourteen days, so this is the only way to answer "which bucket is the $2,527".
 *
 * Null means "no report for this account" — the caller should omit the section.
 */
export const fetchTopResources = async (
  accountId: string,
  period?: string,
): Promise<TopResources | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/top-resources/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return readShape<TopResources>(res, isRecordWith(['resources'], ['totalCost']));
};

// ── Tag coverage and cost allocation ─────────────────────────────────────────

/** One required tag's share of the bill. */
export interface TagKeyCoverage {
  tagKey: string;
  taggedCost: number;
  untaggedCost: number;
}

/** One resource with spend that belongs to nobody. */
export interface UntaggedResource {
  resourceId: string;
  /** The resource's Name tag, or "" — see ResourceCost.name. */
  name: string;
  service: string;
  region: string;
  /** The unattributed part of its spend, not its total. */
  cost: number;
  missingTags: string[];
}

/** How much of the bill can be attributed to an owner. */
export interface TagCoverage {
  /** The required tags this report could actually be asked about. */
  checkedTags: string[];
  /**
   * Required tags absent from the report — never activated for cost allocation, so
   * unknowable rather than missing. Scoring these as 0% would be a claim about the
   * infrastructure when the truth is a setting in the billing console.
   */
  unavailableTags: string[];
  /** Resource spend in the period; excludes tax and other charges that can carry no tag. */
  totalCost: number;
  /** Spend carrying every checked tag. */
  allocatedCost: number;
  byTag: TagKeyCoverage[];
  /**
   * Unattributed spend by service, complete. Each service carries its own total and count
   * even though it lists only its costliest few resources.
   */
  untaggedServices: UntaggedService[];
  /** How many resources have unattributed spend in total — the folded lists cannot say. */
  untaggedCount: number;
  /** How many of the costliest resources `concentrationCost` covers. */
  concentrationHead: number;
  /** What those costliest few account for — how much of the problem the top of the list is. */
  concentrationCost: number;
  /** Every tag in the report, for the chargeback picker. */
  availableTagKeys: string[];
}

/** One service's unattributed spend. */
export interface UntaggedService {
  service: string;
  /** Every untagged resource in the service, not just the listed ones. */
  cost: number;
  resourceCount: number;
  /** The costliest few, for the drill-down. */
  resources: UntaggedResource[];
}

/** Where to download every untagged resource as a spreadsheet. */
export const untaggedCsvUrl = (accountId: string, period?: string): string => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  return `/api/xamops/cur-insights/tag-coverage/${encodeURIComponent(accountId)}/untagged.csv${query}`;
};

/** One tag value's spend — a row of the chargeback report. */
export interface TagValueCost {
  value: string;
  cost: number;
  resourceCount: number;
}

/**
 * How much of the bill can be attributed to an owner, and what cannot.
 *
 * Null means "no report for this account" — the caller should omit the section.
 */
export const fetchTagCoverage = async (
  accountId: string,
  period?: string,
): Promise<TagCoverage | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/tag-coverage/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  // Every list the panel walks, because it walks them unconditionally: a partial body
  // would render most of the panel and then fail on the one field it lacked.
  return readShape<TagCoverage>(
    res,
    isRecordWith(
      ['checkedTags', 'unavailableTags', 'byTag', 'untaggedServices', 'availableTagKeys'],
      ['totalCost'],
    ),
  );
};

/** Spend under one tag, by value. Null means no report; empty means the tag is not in it. */
export const fetchCostByTag = async (
  accountId: string,
  tagKey: string,
  period?: string,
): Promise<TagValueCost[] | null> => {
  const params = new URLSearchParams({ tagKey });
  if (period) params.set('period', period);
  const res = await apiFetch(
    `/api/xamops/cur-insights/cost-by-tag/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) return null;
  return readShape<TagValueCost[]>(res, isList);
};
