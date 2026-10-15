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

/**
 * Which cost a figure is computed from. Matches the backend enum's wire form.
 *
 * `unblended` is what AWS charged on the day and matches the invoice; `amortized` spreads a
 * commitment across its term, which is the only basis on which two months are comparable.
 */
export type CostBasis = 'unblended' | 'amortized';

/**
 * Adds the basis to a query, unless it is the default.
 *
 * `basis=unblended` on the wire would be semantically identical and operationally expensive:
 * `CurInsightCache` keys on the raw query string, so it would fork a second server-side entry and
 * a second Athena scan for the same figures — which is exactly what the react-query no-suffix rule
 * exists to prevent, arriving one layer down. `CostBasis.from(null)` is `UNBLENDED`, so omitting it
 * says the same thing.
 */
const withBasis = (params: URLSearchParams, basis?: CostBasis): URLSearchParams => {
  if (basis && basis !== 'unblended') params.set('basis', basis);
  return params;
};

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
  basis?: CostBasis,
): Promise<ServiceCharge[] | null> => {
  const params = new URLSearchParams();
  if (period) params.set('period', period);
  withBasis(params, basis);
  const query = params.toString();
  const res = await apiFetch(
    `/api/xamops/cur-insights/charges-by-service/${encodeURIComponent(accountId)}${
      query ? `?${query}` : ''
    }`,
  );
  if (!res.ok) return null;
  return readShape<ServiceCharge[]>(res, isList);
};

/**
 * One line item type's share of the bill.
 *
 * Declared here rather than in `gcpCostInsightsApi`, which carried it with a comment saying it
 * would move the moment AWS grew a frontend for `/cur-insights/charge-types`. GCP and Azure now
 * re-import it, the way they already do for `ServiceCharge` and `TopResources`.
 */
export interface ChargeTypeAmount {
  /** Raw code: Usage, DiscountedUsage, Tax, Credit… Unmapped codes are shown verbatim. */
  chargeType: string;
  /** Negative for credits, refunds and SavingsPlanNegation, so the rows sum to the invoice. */
  cost: number;
}

/**
 * Spend split by line item type — the ledger that proves the invoice total adds up.
 *
 * On the amortized basis the commitment fee types are absent rather than zero: their cost is
 * already inside reserved and Savings Plan usage, so listing them would count it twice.
 *
 * Null means "no report for this account" — the caller should omit the section.
 */
export const fetchChargeTypes = async (
  accountId: string,
  period?: string,
  basis?: CostBasis,
): Promise<ChargeTypeAmount[] | null> => {
  const params = new URLSearchParams();
  if (period) params.set('period', period);
  withBasis(params, basis);
  const query = params.toString();
  const res = await apiFetch(
    `/api/xamops/cur-insights/charge-types/${encodeURIComponent(accountId)}${
      query ? `?${query}` : ''
    }`,
  );
  if (!res.ok) return null;
  return readShape<ChargeTypeAmount[]>(res, isList);
};

/**
 * Which of the charges card's optional questions this report can answer.
 *
 * Each `*Detail` is written by the backend because only the backend knows which column is missing,
 * and is empty when its flag is true — the UI renders nothing for an empty one.
 */
export interface ReportCapabilities {
  amortizedAvailable: boolean;
  /** Exactly which columns are absent, so the sentence can name them. */
  amortizedMissingColumns: string[];
  unitAvailable: boolean;
  /** Why the amortized basis is unavailable, naming the columns. Empty when it is available. */
  amortizedDetail: string;
  /** Why quantities carry no unit. Empty when they do. */
  unitDetail: string;
}

/**
 * What the report can be asked, without asking it anything. Scans no data.
 *
 * Null means the probe itself failed, which is a different fact from "the report cannot do this" —
 * the basis toggle says a different sentence for each.
 *
 * `isRecordWith` checks arrays and numbers only, so a 2xx body carrying the array and no booleans
 * is accepted and reads as every flag false, i.e. "the report cannot do any of this". That
 * degradation is benign and fails closed; extending the shared validator with a `booleans` list for
 * one caller would be the larger change.
 */
export const fetchReportCapabilities = async (
  accountId: string,
  period?: string,
): Promise<ReportCapabilities | null> => {
  const query = period ? `?period=${encodeURIComponent(period)}` : '';
  const res = await apiFetch(
    `/api/xamops/cur-insights/capabilities/${encodeURIComponent(accountId)}${query}`,
  );
  if (!res.ok) return null;
  return readShape<ReportCapabilities>(res, isRecordWith(['amortizedMissingColumns'], []));
};

/** Commitment spend that bought nothing. */
export interface UnusedCommitment {
  /** Recurring (hourly) reservation fees not matched by usage. */
  unusedRecurringFee: number;
  /** Amortised upfront portion not matched by usage. */
  unusedUpfrontFee: number;
  total: number;
  /**
   * False when the report does not carry the columns this is measured from, so the totals are zero
   * and mean nothing — "this report cannot say" rather than "you wasted nothing".
   *
   * Optional because only AWS populates it: Azure declares its own `AzureUnusedCommitment` and does
   * not share this interface, so the field is AWS-only by construction. Absent means "no such
   * concept here", which must keep rendering — hence callers test `available !== false`.
   */
  available?: boolean;
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

/**
 * One service's resources, ranked, capped inside that service.
 *
 * Returns the same shape as `fetchTopResources`, so one panel renders either. The difference is
 * what the figures mean: `totalCost` and `groupCount` here describe the service, not the account,
 * which is what lets the panel state what it is not showing instead of guessing. The account-wide
 * endpoint returns the costliest 500 groups in the whole account, so a service low down the bill
 * had none of its resources in that list at all.
 *
 * Null means the report cannot answer — no report, or a 422 from the basis guard. That is not the
 * same as an empty list, and the panel renders them differently.
 */
export const fetchServiceResources = async (
  accountId: string,
  service: string,
  period?: string,
  basis?: CostBasis,
): Promise<TopResources | null> => {
  // URLSearchParams, not a template literal: a service name is free text from the report, and
  // "AWS Backup & Restore" concatenated raw would cut the query string in half at the & while
  // "Route 53 Resolver + DNS Firewall" would lose the + to a space.
  const params = new URLSearchParams({ service });
  if (period) params.set('period', period);
  withBasis(params, basis);
  const res = await apiFetch(
    `/api/xamops/cur-insights/service-resources/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) return null;
  return readShape<TopResources>(res, isRecordWith(['resources'], ['totalCost']));
};

// ── One resource, in depth ───────────────────────────────────────────────────

/**
 * One usage type's movement inside a resource.
 *
 * <p>The per-line split is exact rather than indicative, which is what makes this worth showing
 * separately from the service-level table: one usage type bills in one unit, so cost ÷ quantity is a
 * real unit rate. The service-level view has to blend units across everything a service bills for and
 * can only point at a direction.
 */
export interface ResourceLineChange {
  /** The billed line as the invoice words it. Falls back to the usage type when blank. */
  description: string;
  /** The stable code behind the description — usage type on AWS, SKU id on GCP, meter on Azure. */
  usageType: string;
  /** GB-Mo, Hrs, requests. Empty on Azure, which does not return it for this query. */
  unit: string;
  priorCost: number;
  currentCost: number;
  costChange: number;
  priorQuantity: number;
  currentQuantity: number;
  priceEffect: number;
  volumeEffect: number;
}

/** One day's cost. `date` is ISO-8601, so it sorts as a string. */
export interface DailyCost {
  date: string;
  cost: number;
}

/**
 * Everything the resource drill-down panel shows for one resource.
 *
 * <p>Answers the question a reader has the moment they see which resource costs the most: and why did
 * *that* change. Identical in shape across all three clouds, so one panel renders any of them.
 */
export interface ResourceChange {
  resourceId: string;
  /** The Name tag, the resource's own name, or empty — see ResourceCost.name. */
  name: string;
  service: string;
  region: string;
  /** Instance type on AWS, resource group on Azure, empty on GCP. Matches ResourceCost. */
  instanceType: string;
  priorCost: number;
  currentCost: number;
  costChange: number;
  priorQuantity: number;
  currentQuantity: number;
  priceEffect: number;
  volumeEffect: number;
  /**
   * Spend in the selected month that the per-line comparison does not cover.
   *
   * <p>`currentCost` and `lines` count usage rows only, because those are the rows carrying a quantity
   * a unit rate can be computed from. Anything the resource was charged as discounted usage, Savings
   * Plan coverage, a reservation fee, tax or a credit is real spend this breakdown cannot decompose —
   * and on a commitment-covered instance it is most of the cost.
   *
   * <p>Reported so the panel can reconcile itself against the resource row that opened it instead of
   * quietly showing a smaller number. Zero on the common case.
   *
   * <p>Optional because only the AWS report sends it; GCP and Azure compute their equivalents without
   * a usage-only restriction, so there is nothing excluded to report. Absent means "not applicable
   * here", which is why the UI omits the note rather than rendering a zero.
   */
  excludedCost?: number;
  /**
   * False when the billing source does not reach the earlier month, so the prior figures and both
   * effects are zero and mean nothing.
   *
   * A CUR and a GCP billing export both begin when they were created and never backfill, so this is
   * the normal state for a recently connected one. It is a flag rather than an absent payload because
   * the lines and the daily series are still real — only the comparison is impossible, and showing
   * zeros as a genuine earlier month would make every resource look newly created.
   */
  comparisonAvailable: boolean;
  /** Per usage type, largest movement first. */
  lines: ResourceLineChange[];
  /** The selected month's spend, day by day. Empty when the daily query could not run. */
  daily: DailyCost[];
}

/**
 * One resource's month-over-month movement, which usage types moved, and its daily spend.
 *
 * Null means the billing source cannot attribute cost to this resource — no report, or a scope that
 * will not group by resource. That is the same condition that hides the resource table itself, so a
 * reader who can see the table can open the panel.
 */
export const fetchResourceChange = async (
  accountId: string,
  resourceId: string,
  period?: string,
  /**
   * The row's service and region.
   *
   * <p>Passed so the panel answers the figure the reader clicked. The resource table groups by
   * (resource, service, region), so one resource spanning two services is several rows each showing
   * part of its cost — and a panel filtered on the id alone reported the resource's whole cost, a
   * larger number than the row that opened it with nothing to explain the gap.
   */
  scope?: { service?: string; region?: string },
): Promise<ResourceChange | null> => {
  const params = new URLSearchParams({ resourceId });
  if (period) params.set('period', period);
  if (scope?.service) params.set('service', scope.service);
  if (scope?.region) params.set('region', scope.region);
  const res = await apiFetch(
    `/api/xamops/cur-insights/resource-change/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) return null;
  return readShape<ResourceChange>(res, isRecordWith(['lines', 'daily'], ['costChange']));
};

// ── A set of resources: which they are, and what they cost ───────────────────

/** Which resource one id names, and which graph node ids could be it. */
export interface ResourceIdentity {
  /** The id as asked about, so an entry is matched by value and never by position. */
  requestedId: string;
  /**
   * Every identifier the inventory holds for the resource, in the order to try them against a
   * graph index — `resource_id` first, because that is the id the inventory-plane graph path emits
   * and that is the preferred path; the ARN hits instead on the describe-scan path, for the dozen
   * types whose service API identifier *is* the ARN.
   *
   * Never empty: an id the inventory does not know carries itself, which is correct — with no row,
   * the only string that could name a node is the one the caller already has.
   */
  nodeIdCandidates: string[];
  /**
   * The inventory's short id, for display. Null when the inventory does not know the id.
   *
   * **Not "the graph id".** It is always the ARN tail, so for a load balancer it is
   * `50dc6c495c0c9188` while the describe-path graph names the node `my-lb` — reading this field as
   * the node id reports every load balancer as missing from the topology. Match on
   * `nodeIdCandidates` instead.
   */
  resourceId: string | null;
  /** The UI-facing type ("EC2 Instance"), or null when unresolved. */
  resourceType: string | null;
  resourceName: string | null;
  region: string | null;
}

export interface ResourceIdentities {
  /** One entry per requested id, in request order, including the unresolved ones. */
  identities: ResourceIdentity[];
  requested: number;
  /** How many matched an inventory row. */
  resolved: number;
}

/** One resource's whole month, keyed by the id that was asked about. */
export interface ResourceCostEntry {
  /** The id **as requested**, not the CUR id it matched. */
  resourceId: string;
  /** Every charge type, across every service and region, on the requested basis. */
  cost: number;
}

export interface ResourceCostsResponse {
  /** One entry per priced id, cost-descending. An absent id has no attributable cost. */
  costs: ResourceCostEntry[];
  /**
   * Ids whose cost cannot be attributed to one resource: two live resources answer to the name, so
   * pricing either would be a guess. Rendered as its own sentence, not as "no cost".
   */
  ambiguous: string[];
  requestedCount: number;
  /** How many came back with a figure, so the caller can state the gap in its own words. */
  pricedCount: number;
  /** The matched set's total. Not the sum of `costs` when one resource is priced under two names. */
  totalCost: number;
  groupCount: number;
  basis: string;
}

/*
 * Mirrors CurAdvancedMetricsService.MAX_LOOKUP_IDS / MAX_IDS_CHARS / MAX_ID_CHARS.
 *
 * Duplicated deliberately. The Java side argues against a second copy of a limit — "a number that
 * can drift from the one the query is built under" — and that argument is about the server's own
 * layers, where one copy is avoidable because it can read the field. This copy cannot be: the
 * client has to know the limit in order to trim to it, and the alternative is sending a request it
 * knows may 400 and then rendering a failure for a condition it could have described. Lowering a
 * Java constant without lowering its twin here turns a trimmed request into a 400; each Java
 * constant carries a comment pointing back at this block, which is the cheapest thing that makes
 * the pair findable.
 */
export const MAX_COST_IDS = 100;
export const MAX_COST_IDS_CHARS = 3_000;
export const MAX_COST_ID_CHARS = 2_048;

/**
 * Trims an id list to what the backend accepts, in model order.
 *
 * <p>The order the three rules are applied in is not the server's — it checks total length, then
 * count, then per-id length, and returns on the first violation — and does not need to be: the
 * server returns on the first failure while this has to satisfy all three, so it applies them in
 * the order that discards least. What must agree is the three numbers and what each is measured on.
 *
 * <p>The focus id is never dropped. It is the one figure the tab must always be able to show, and
 * its absence would read as "this resource has no cost" rather than as a trim. Peers are dropped
 * from the END of the order they arrive in — the model's own order, declared relations before
 * inferred ones, then by relation — so what survives is the half the model already considers more
 * informative.
 *
 * <p>Total length is measured on the string that becomes the `ids` parameter, the ids joined by
 * commas, because that is what the server measures before splitting. Measuring the array's total
 * instead would under-count by one character per id and a correctly trimmed request would still
 * 400.
 *
 * @param focusId the resource at the centre of the flow
 * @param peerIds the rendered peers' ids, in model order, already filtered to the non-external ones
 * @returns the ids to send in model order, plus the peer counts needed to say what was left out
 */
export const trimCostIds = (
  focusId: string,
  peerIds: string[],
): { sent: string[]; sentPeerCount: number; totalPeerCount: number } => {
  const totalPeerCount = peerIds.length;
  // An id longer than the inventory's widest identifier column cannot price under any
  // circumstances, so dropping it costs nothing and buys room for one that can.
  const usable = peerIds.filter((id) => id.length <= MAX_COST_ID_CHARS);

  const sent: string[] = [];
  const seen = new Set<string>();
  const take = (id: string) => {
    if (seen.has(id)) return;
    seen.add(id);
    sent.push(id);
  };
  /*
   * A focus id of its own past MAX_COST_ID_CHARS is not taken, and on a peerless resource that
   * leaves `sent` empty — the request then carries `ids=`, the backend 400s, and the tab renders
   * "the costs could not be read". Accepted rather than special-cased: at 2,048 characters it
   * needs an identifier longer than any AWS ARN, and the alternative is sending an id the server
   * is documented to reject and calling the 400 an outage.
   */
  if (focusId && focusId.length <= MAX_COST_ID_CHARS) take(focusId);
  for (const id of usable) {
    // Counted after dedupe, so a duplicate cannot consume a slot.
    if (seen.size >= MAX_COST_IDS) break;
    take(id);
  }

  /*
   * Then the character budget, trimming from the end. `sent.length > 1` keeps the first entry,
   * which is the focus whenever the focus was taken at all — the only case where it is not is the
   * over-long focus above, and there the surviving entry is a peer rather than the focus. Stated
   * precisely because "the focus is never dropped" is the rule, and position is only a proxy for
   * it.
   */
  while (sent.length > 1 && sent.join(',').length > MAX_COST_IDS_CHARS) {
    const dropped = sent.pop();
    if (dropped) seen.delete(dropped);
  }

  const focusSent = focusId && sent.includes(focusId) ? 1 : 0;
  return { sent, sentPeerCount: sent.length - focusSent, totalPeerCount };
};

/**
 * Which resource each of these ids names.
 *
 * <p>**Throws** on a non-2xx and on an unexpected shape — the only fetcher in this file that does,
 * and a requirement rather than a style choice. `readShape` returns null for an unreadable body
 * whatever the status, which for every other endpoint here means "hide the widget". The
 * connectivity tab decides "this resource is not in the account topology" from an empty candidate
 * match, so a null answer on a 502 would resolve the query *successfully*, leave `isError` false,
 * and render that benign, retry-less state permanently — a transient backend blip turned into a
 * confident, wrong, unrecoverable statement about the reader's infrastructure. The status travels
 * in the message because a 400 here is a bug in our own request construction and is worth warning
 * about separately.
 */
export const fetchResourceIdentities = async (
  accountId: string,
  ids: string[],
): Promise<ResourceIdentities> => {
  const params = new URLSearchParams({ ids: ids.join(',') });
  const res = await apiFetch(
    `/api/xamops/cur-insights/resource-identities/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) throw new Error(`resource-identities ${res.status}`);
  const body = await readShape<ResourceIdentities>(res, isRecordWith(['identities']));
  if (!body) throw new Error('resource-identities: unexpected shape');
  return body;
};

/**
 * What each of a set of resources cost for a month — one query, whatever the set size.
 *
 * <p>Every charge type, across every service and region, which is deliberately different from
 * `fetchResourceChange`'s usage-only figure: a peer is a different resource with its own services,
 * and scoping it by the focus resource's service would zero almost every peer.
 *
 * <p>Keeps the ordinary null-returning contract, unlike the identity fetcher above: a null costs
 * answer has its own non-destructive rendering — a banner above a flow that still works — so it
 * does not need to be told apart from a benign absence.
 */
export const fetchResourceCosts = async (
  accountId: string,
  ids: string[],
  period?: string,
  basis?: CostBasis,
): Promise<ResourceCostsResponse | null> => {
  const params = new URLSearchParams({ ids: ids.join(',') });
  if (period) params.set('period', period);
  withBasis(params, basis);
  const res = await apiFetch(
    `/api/xamops/cur-insights/resource-costs/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) {
    if (res.status === 400) {
      // Our own bug: the client trimmed against its copy of the limits and the server refused it
      // anyway, which means the two copies no longer agree.
      console.warn('[CUR] resource-costs refused a trimmed id list — the client and server id'
        + ' limits have drifted apart');
    }
    return null;
  }
  return readShape<ResourceCostsResponse>(res, isRecordWith(['costs'], ['totalCost']));
};

// ── A resource's security, compliance and performance findings ───────────────

/** Where a finding row navigates to: a product page path plus the query that filters it. */
export interface SecurityDeepLink {
  path: string;
  query: Record<string, string>;
}

/** One finding against the resource, with where to go to see it in full. */
export interface ResourceSecurityFinding {
  title: string;
  /** Normalised severity: CRITICAL | HIGH | MEDIUM | LOW | INFORMATIONAL. */
  severity: string;
  /** Optional extra context shown under the title. */
  detail?: string;
  deepLink: SecurityDeepLink;
}

/** One product's findings for the resource, already sorted most-severe-first by the server. */
export interface ResourceSecurityGroup {
  /** Stable product code: SECURITY_CENTER | COMPLIANCEOPS | PERFORMANCE. */
  product: string;
  label: string;
  route: string;
  findings: ResourceSecurityFinding[];
}

/**
 * Every SecOps finding that references one resource, grouped by product.
 *
 * `errors` carries one entry per product whose read failed, so a single surface being down shows
 * as a line in the tab rather than failing the whole request.
 */
export interface ResourceSecurityFindings {
  resourceId: string;
  matched: boolean;
  groups: ResourceSecurityGroup[];
  errors: { product: string; message: string }[];
}

/**
 * What Security Center, ComplianceOps and Performance Insights know about one resource.
 *
 * Null means the request could not be read at all (no account, a transient failure) — the tab
 * renders its own "unavailable" state for that, distinct from a 200 with empty `groups`.
 */
export const fetchResourceSecurity = async (
  accountId: string,
  resourceId: string,
  region?: string,
  period?: string,
): Promise<ResourceSecurityFindings | null> => {
  const params = new URLSearchParams({ resourceId });
  if (region) params.set('region', region);
  if (period) params.set('period', period);
  const res = await apiFetch(
    `/api/xamops/cur-insights/resource-security/${encodeURIComponent(accountId)}?${params}`,
  );
  if (!res.ok) return null;
  return readShape<ResourceSecurityFindings>(res, isRecordWith(['groups', 'errors']));
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
