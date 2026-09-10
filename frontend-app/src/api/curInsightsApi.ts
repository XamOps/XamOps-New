import { apiFetch } from '../utils/apiClient';

/**
 * Cost insights that only a Cost and Usage Report can answer.
 *
 * Every one of these returns 409 when the account has no usable report — deliberately,
 * because there is no Cost Explorer equivalent to fall back to. Callers treat that as
 * "hide the widget", never as an error worth showing.
 */

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
  return (await res.json()) as SavingsRate;
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
  return (await res.json()) as PriceVolumeChange[];
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
  return (await res.json()) as ServiceCharge[];
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
  return (await res.json()) as UnusedCommitment;
};

/** One network charge, already categorised by what drives it. */
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
}

/**
 * Network spend for a month, split into the categories that drive it.
 *
 * Invisible in every other view: a service breakdown files NAT gateway charges under EC2
 * and cross-AZ traffic under whichever service moved the bytes, so the network total never
 * appears anywhere as a single number.
 *
 * Null means "no report for this account" — the caller should omit the section.
 */
export const fetchDataTransferCosts = async (
  accountId: string,
  period?: string,
): Promise<TransferCharge[] | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/data-transfer/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return (await res.json()) as TransferCharge[];
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
  return (await res.json()) as TopResources;
};
