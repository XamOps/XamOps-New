/**
 * BillOps Admin — Invoice detail
 *
 * One invoice, laid out like a bill in the AWS Billing console:
 * - a summary that leads with the total due and shows how it is reached
 *   (charges, discounts, taxable value, GST);
 * - "Charges by service", the same service → region → usage type → rate line breakdown
 *   as the cost page and the AWS bill, searchable and expandable;
 * - discounts, and finalizing, for drafts.
 *
 * Route: /billops-admin-invoice-detail?id=<invoiceId>
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../utils/apiClient';
import { awsRegionName } from '../utils/awsRegionNames';
import { AlertDialog, AriaTabGroup } from '../components/ui';
import '../styles/billopsInvoiceUi.css';

// ─── Types ───────────────────────────────────────────────────────────────────

interface LineItemDto {
  id: number;
  serviceName: string;
  regionName: string;
  /** The rate line's description on report-built invoices; the resource or usage type otherwise. */
  resourceName: string;
  usageQuantity: string;
  unit: string;
  cost: number;
  /**
   * Set on invoices built from the Cost and Usage Report, where lines sit under their
   * usage type as they do on the AWS bill. Absent on Cost Explorer invoices and repriced
   * CloudFront lines, which stay directly under their region.
   */
  usageType?: string | null;
  /** The rate an admin set, when private pricing produced this line's amount. */
  privateUnitRate?: number | null;
  /** The unit that rate was quoted in — "GB", "Hrs", "Per 10000 Requests". */
  privateRateUnit?: string | null;
  /** What the line cost at provider rates, kept only once a private rate replaced it. */
  originalCost?: number | null;
  /** Whether an invoice-wide discount must skip this line. Decided by the server. */
  discountShielded?: boolean;
}

interface DiscountDto {
  id: number;
  serviceName: string;
  percentage: number;
  description: string;
}

/** A rate an admin applied by hand to part of this invoice. */
interface PrivatePricingDto {
  id: number;
  serviceName: string;
  regionName?: string | null;
  usageType?: string | null;
  /** Set when the rate prices one charge line on its own; region and usage type are then null. */
  lineItemId?: number | null;
  scopeLabel: string;
  unitRate: number;
  rateUnit?: string | null;
  excludeFromOverallDiscount: boolean;
  lineCount?: number | null;
  skippedLineCount?: number | null;
  originalAmount?: number | null;
  pricedAmount?: number | null;
  description?: string | null;
}

interface InvoiceDetail {
  id: number;
  invoiceNumber: string;
  invoiceDate: string;
  billingPeriod: string;
  status: 'DRAFT' | 'FINALIZED' | 'VOID';
  clientName: string;
  accountName: string;
  provider: string;
  awsAccountId: string;
  gcpProjectId: string;
  azureSubscriptionId: string;
  preDiscountTotal: number;
  discountAmount: number;
  taxAmount: number;
  amount: number;
  marketplaceCharge?: number | null;
  /** Editable % markup the admin applies only to the AWS Marketplace charge; null/0 = none. */
  marketplaceHoldingCostPercent?: number | null;
  /** How much of the charges are billed at a rate an admin set rather than at provider cost. */
  privatePricedTotal?: number | null;
  /** The part of that an invoice-wide discount may not reduce. */
  discountShieldedTotal?: number | null;
  includeIrn?: boolean;
  lineItems: LineItemDto[];
  discounts: DiscountDto[];
  privatePricing?: PrivatePricingDto[];
  dataSource?: 'CUR' | 'COST_EXPLORER' | null;
  provisional?: boolean;
  /** File name of the AWS bill PDF the draft was created from; null when generated. */
  uploadedFileName?: string | null;
  /** INR per 1 USD this invoice was finalized at; null until finalized with a chosen rate. */
  exchangeRate?: number | null;
}

interface UsageTypeNode { usageType: string; total: number; lines: LineItemDto[] }
interface RegionNode {
  key: string;
  /** Display label, which is "Global" when the lines carry no region. */
  region: string;
  /**
   * The region exactly as the line items carry it, empty string included.
   *
   * Kept apart from {@link region} because a private rate is matched against the stored
   * value: pricing a CloudFront region by its "Global" label would match no charge at all.
   */
  rawRegion: string;
  total: number;
  usageTypes: UsageTypeNode[];
  flat: LineItemDto[];
}
interface ServiceNode { service: string; total: number; regions: RegionNode[]; lineCount: number }

/**
 * The part of the bill a private rate applies to, taken from the row the admin clicked.
 * A line scope also carries the line's region and usage type, for overlap checks only;
 * the server stores just the line's id.
 */
interface PricingScope { service: string; region?: string; usageType?: string; lineId?: number; lineLabel?: string }

/** Two scopes are the same row of the tree. */
const sameScope = (a: PricingScope, b: PricingScope): boolean =>
  a.service === b.service
  && (a.lineId ?? null) === (b.lineId ?? null)
  && (a.lineId != null || ((a.region ?? '') === (b.region ?? '') && (a.usageType ?? '') === (b.usageType ?? '')));

/** How deep in the tree a scope sits: service 1, region 2, usage type 3, single charge 4. */
const scopeDepth = (s: { region?: string | null; usageType?: string | null; lineId?: number | null; lineItemId?: number | null }): number =>
  (s.lineId ?? s.lineItemId) != null ? 4 : 1 + (s.region ? 1 : 0) + (s.usageType ? 1 : 0);

// ─── Formatting ──────────────────────────────────────────────────────────────

const usd = (v: number | null | undefined): string => {
  const value = v ?? 0;
  // Sub-cent lines keep their digits; rounding them to $0.00 would hide real charges.
  const small = value !== 0 && Math.abs(value) < 0.01;
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: small ? 4 : 2,
  }).format(value);
};

const inr = (v: number): string =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(v);

/**
 * Usage quantities span a huge range — 0.000012 GB-Mo to 147,128,739 Requests — so a
 * fixed number of decimals is unreadable at one end and misleading at the other. Same
 * rule as the cost page's "Charges by service", so the two read identically.
 */
const formatQuantity = (raw: string | null | undefined): string => {
  const v = Number(raw);
  if (raw == null || raw === '' || Number.isNaN(v)) return '';
  if (v === 0) return '0';
  const abs = Math.abs(v);
  if (abs >= 1000) return v.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (abs >= 1) return v.toLocaleString('en-US', { maximumFractionDigits: 3 });
  return v.toLocaleString('en-US', { maximumFractionDigits: 6 });
};

/** A rate shown at its own precision: $0.0085 stays $0.0085, not $0.01. */
const rate = (v: number | null | undefined): string => {
  const value = v ?? 0;
  return `$${value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 10 })}`;
};

// ─── Private pricing preview ─────────────────────────────────────────────────
//
// Mirrors PrivatePricingCalculator on the server so the admin sees what a rate will bill
// before committing to it. The figures shown after applying are the server's own, read
// back off the returned invoice -- this is only the preview.

/** The quantity to multiply, or null when the line carries none that can be trusted. */
const usageQuantityOf = (raw: string | null | undefined): number | null => {
  if (raw == null || raw.trim() === '') return null;
  const token = raw.trim().split(/\s+/)[0].replace(/,/g, '');
  if (token === '') return null;
  const value = Number(token);
  return Number.isFinite(value) ? value : null;
};

/** Divisor implied by a rate unit such as "Per 10000 Requests"; 1 when it names none. */
const rateUnitDivisor = (rateUnit: string): number => {
  const match = /per\s*([\d,]+)/i.exec(rateUnit);
  if (!match) return 1;
  const n = Number(match[1].replace(/,/g, ''));
  return Number.isFinite(n) && n > 0 ? n : 1;
};

/**
 * Why a line inside a scope would be left at provider cost, or null if it can be priced.
 * Credits are checked first: a Savings Plan negation has a perfectly readable quantity,
 * which is exactly what makes multiplying it dangerous.
 */
const pricingSkipReason = (line: LineItemDto): string | null => {
  if ((line.cost ?? 0) < 0) return 'credit or Savings Plan line';
  const quantity = usageQuantityOf(line.usageQuantity);
  if (quantity === null) return 'no readable quantity';
  if (quantity === 0) return 'no measured usage';
  return null;
};

const formatPeriod = (period: string | null | undefined): string => {
  if (!period) return '—';
  const [y, m] = period.split('-').map(Number);
  if (!y || !m) return period;
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
};

const formatDate = (iso: string | null | undefined): string => {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' });
};

const accountIdOf = (inv: InvoiceDetail): string => {
  if (inv.awsAccountId && inv.awsAccountId !== 'N/A') return inv.awsAccountId;
  if (inv.gcpProjectId && inv.gcpProjectId !== 'N/A') return inv.gcpProjectId;
  return inv.azureSubscriptionId || '—';
};

const regionLabel = awsRegionName;

/**
 * Marks a charge billed at an admin's rate rather than the provider's cost, showing the
 * rate and what the provider charged.
 *
 * <p>On the line itself because this is the one place the two figures can be compared, and
 * because a charge that does not match the provider's bill invites exactly that question.
 */
const PricedBadge: React.FC<{ line: LineItemDto }> = ({ line }) => {
  if (line.privateUnitRate == null) return null;
  const provider = line.originalCost == null ? null : usd(line.originalCost);
  // The same phrasing the detail bill prints, so the page and the customer's bill
  // describe one rate the same way. A unit already written "Per 10000 Requests" is not
  // prefixed again.
  // Falls back to the line's own unit when no rate unit was recorded, as the bill does:
  // a rate quoted without a unit carries no divisor, so it is per one unit of whatever
  // the quantity measures.
  const rateUnit = line.privateRateUnit
    || (line.unit && !['n/a', 'na', 'none'].includes(line.unit.trim().toLowerCase()) ? line.unit : '');
  const phrase = rateUnit
    ? `${rate(line.privateUnitRate)} ${/^per/i.test(rateUnit) ? '' : 'per '}${rateUnit}`
    : rate(line.privateUnitRate);
  return (
    <span
      className="inv-pill inv-pill--priced"
      style={{ marginLeft: 8 }}
      title={`Billed at ${phrase}`
        + (provider ? `, instead of the provider's ${provider}` : '')
        + (line.discountShielded ? '. The overall discount skips this charge.' : '')}
    >
      <i className="fas fa-tag" aria-hidden="true" />
      {phrase}
      {provider && <span className="inv-pill__was">was {provider}</span>}
      {line.discountShielded && <i className="fas fa-shield-alt" aria-hidden="true" />}
    </span>
  );
};

// ─── Charges tree ────────────────────────────────────────────────────────────

/**
 * Service → region → usage type → line, the breakdown of the AWS bill and of the cost
 * page's "Charges by service". Lines without a usage type (Cost Explorer invoices,
 * repriced CloudFront) skip that level and sit straight under their region.
 */
const buildTree = (lines: LineItemDto[]): ServiceNode[] => {
  const services = new Map<string, Map<string, { byUsage: Map<string, LineItemDto[]>; flat: LineItemDto[]; raw: string }>>();
  for (const line of lines) {
    const svc = line.serviceName || 'Other';
    const rgn = line.regionName || 'Global';
    const regions = services.get(svc) ?? new Map();
    const bucket = regions.get(rgn)
      ?? { byUsage: new Map<string, LineItemDto[]>(), flat: [] as LineItemDto[], raw: line.regionName || '' };
    if (line.usageType) {
      const list = bucket.byUsage.get(line.usageType) ?? [];
      list.push(line);
      bucket.byUsage.set(line.usageType, list);
    } else {
      bucket.flat.push(line);
    }
    regions.set(rgn, bucket);
    services.set(svc, regions);
  }

  const sum = (ls: LineItemDto[]) => ls.reduce((s, l) => s + (l.cost ?? 0), 0);
  const byCost = (a: LineItemDto, b: LineItemDto) => (b.cost ?? 0) - (a.cost ?? 0);

  return [...services.entries()]
    .map(([service, regionMap]) => {
      const regions: RegionNode[] = [...regionMap.entries()]
        .map(([region, { byUsage, flat, raw }]) => {
          const usageTypes = [...byUsage.entries()]
            .map(([usageType, ls]) => ({ usageType, total: sum(ls), lines: [...ls].sort(byCost) }))
            .sort((a, b) => b.total - a.total);
          return {
            key: `${service}|${region}`,
            region,
            rawRegion: raw,
            total: usageTypes.reduce((s, u) => s + u.total, 0) + sum(flat),
            usageTypes,
            flat: [...flat].sort(byCost),
          };
        })
        .sort((a, b) => b.total - a.total);
      return {
        service,
        total: regions.reduce((s, r) => s + r.total, 0),
        regions,
        lineCount: regions.reduce((n, r) => n + r.flat.length + r.usageTypes.reduce((m, u) => m + u.lines.length, 0), 0),
      };
    })
    .sort((a, b) => b.total - a.total);
};

// ─── Page ────────────────────────────────────────────────────────────────────

const BillOpsAdminInvoiceDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const invoiceId = searchParams.get('id');

  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [tab, setTab] = useState<'charges' | 'discounts'>('charges');
  const [query, setQuery] = useState('');
  const [openServices, setOpenServices] = useState<Set<string>>(new Set());
  const [openRegions, setOpenRegions] = useState<Set<string>>(new Set());

  const [discountService, setDiscountService] = useState('');
  const [discountPct, setDiscountPct] = useState('');
  const [applyingDiscount, setApplyingDiscount] = useState(false);
  const [discountError, setDiscountError] = useState<string | null>(null);

  // AWS Marketplace holding cost: an editable, optional % markup on the Marketplace charge.
  const [holdingPctInput, setHoldingPctInput] = useState('');
  const [applyingHoldingCost, setApplyingHoldingCost] = useState(false);
  const [holdingCostError, setHoldingCostError] = useState<string | null>(null);

  // Private pricing is applied from the charges tree itself: the row the admin clicked
  // IS the scope, since the tree's three levels — service, region, usage type — are
  // exactly the levels a rate can be scoped to. Null when no editor is open.
  const [pricingScope, setPricingScope] = useState<PricingScope | null>(null);
  const [priceRate, setPriceRate] = useState('');
  const [priceRateUnit, setPriceRateUnit] = useState('');
  const [priceShield, setPriceShield] = useState(true);
  const [applyingPrice, setApplyingPrice] = useState(false);
  const [pricingError, setPricingError] = useState<string | null>(null);
  const [pricingBusyId, setPricingBusyId] = useState<number | null>(null);

  const [finalizing, setFinalizing] = useState(false);
  const [confirmFinalize, setConfirmFinalize] = useState(false);
  const [downloading, setDownloading] = useState<'pdf' | 'detail' | null>(null);

  const [usdRate, setUsdRate] = useState<number | null>(null);
  const [rateDate, setRateDate] = useState<string | null>(null);

  // The USD→INR rate an admin reviews and locks in at finalize. String-backed for the
  // input; seeded once from the invoice's own rate if set, else the live rate when it
  // loads, without clobbering an edit already made. `rateTouched` tracks that edit.
  const [chosenRate, setChosenRate] = useState('');
  const [rateTouched, setRateTouched] = useState(false);

  // ── Load ──────────────────────────────────────────────────────────────────

  const fetchInvoice = useCallback(async () => {
    if (!invoiceId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoiceId}`, { credentials: 'include' });
      if (!res.ok) {
        if (res.status === 401 || res.status === 403) {
          navigate('/');
          return;
        }
        throw new Error(res.status === 404 ? 'This invoice no longer exists.' : `Server error: ${res.status}`);
      }
      setInvoice(await res.json());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load invoice.');
    } finally {
      setLoading(false);
    }
  }, [invoiceId, navigate]);

  useEffect(() => { fetchInvoice(); }, [fetchInvoice]);

  // Live USD→INR, for the equivalent shown under the total (fawazahmed0/exchange-api).
  useEffect(() => {
    const endpoints = [
      'https://latest.currency-api.pages.dev/v1/currencies/usd.min.json',
      'https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json',
    ];
    (async () => {
      for (const url of endpoints) {
        try {
          const res = await fetch(url);
          if (!res.ok) continue;
          const data = await res.json();
          const rate = data?.usd?.inr;
          if (rate && rate > 1) {
            setUsdRate(rate);
            setRateDate(data?.date ?? null);
            return;
          }
        } catch {
          // try the next endpoint
        }
      }
    })();
  }, []);

  // Seed the editable rate from the effective source once it becomes available: the
  // invoice's own finalized rate first, else the live rate. Leaves an admin's own edit
  // alone once they've typed into the field.
  useEffect(() => {
    if (rateTouched) return;
    const seed = invoice?.exchangeRate ?? usdRate;
    if (seed != null && seed > 0) {
      setChosenRate(String(seed));
    }
  }, [invoice?.exchangeRate, usdRate, rateTouched]);

  // ── Charges ───────────────────────────────────────────────────────────────

  const allLines = useMemo(() => invoice?.lineItems ?? [], [invoice]);
  const chargesTotal = useMemo(() => allLines.reduce((s, l) => s + (l.cost ?? 0), 0), [allLines]);

  const q = query.trim().toLowerCase();
  const matchingLines = useMemo(() => {
    if (!q) return allLines;
    return allLines.filter(l =>
      [l.serviceName, l.regionName, regionLabel(l.regionName || ''), l.usageType, l.resourceName]
        .some(v => (v || '').toLowerCase().includes(q)));
  }, [allLines, q]);

  const tree = useMemo(() => buildTree(matchingLines), [matchingLines]);
  const fullTree = useMemo(() => buildTree(allLines), [allLines]);

  // A lone service opens by itself; there is nothing else to choose between.
  useEffect(() => {
    if (fullTree.length === 1) setOpenServices(new Set([fullTree[0].service]));
  }, [fullTree]);

  // While searching, everything that matched is shown open.
  const isServiceOpen = (s: string) => Boolean(q) || openServices.has(s);
  const isRegionOpen = (k: string) => Boolean(q) || openRegions.has(k);

  const toggle = (setter: React.Dispatch<React.SetStateAction<Set<string>>>, key: string) =>
    setter(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });

  const expandAll = () => {
    setOpenServices(new Set(fullTree.map(s => s.service)));
    setOpenRegions(new Set(fullTree.flatMap(s => s.regions.map(r => r.key))));
  };
  const collapseAll = () => {
    setOpenServices(new Set());
    setOpenRegions(new Set());
  };

  const serviceNames = useMemo(() => fullTree.map(s => s.service).sort(), [fullTree]);

  // ── Private pricing ───────────────────────────────────────────────────────

  const privatePricing = useMemo(() => invoice?.privatePricing ?? [], [invoice]);

  /** Services whose charges an overall discount would skip, for the discount picker. */
  const shieldedServices = useMemo(
    () => new Set(privatePricing.filter(p => p.excludeFromOverallDiscount).map(p => p.serviceName)),
    [privatePricing]);

  /**
   * The rate already applied to exactly this scope, if any.
   *
   * Matched on the scope's own three levels rather than on which lines it covers, so the
   * row that set a rate is the row that shows and removes it.
   */
  const rateForScope = useCallback((scope: PricingScope): PrivatePricingDto | undefined =>
    privatePricing.find(p => {
      if (p.serviceName !== scope.service) return false;
      if (scope.lineId != null || p.lineItemId != null) return p.lineItemId === scope.lineId;
      return (p.regionName ?? '') === (scope.region ?? '') && (p.usageType ?? '') === (scope.usageType ?? '');
    }),
    [privatePricing]);

  const linesInScope = useCallback((scope: PricingScope): LineItemDto[] =>
    scope.lineId != null
      ? allLines.filter(l => l.id === scope.lineId)
      : allLines.filter(l =>
        (l.serviceName || 'Other') === scope.service
        && (!scope.region || (l.regionName || '') === scope.region)
        && (!scope.usageType || l.usageType === scope.usageType)),
    [allLines]);

  /**
   * Whether a rate on a broader or narrower scope already covers these charges.
   *
   * Level scopes compare by their levels. A single-charge rate compares by the charge:
   * it overlaps any level that contains that line.
   */
  const overlappingRate = useCallback((scope: PricingScope): PrivatePricingDto | undefined => {
    const scopeLineIds = new Set(linesInScope(scope).map(l => l.id));
    return privatePricing.find(p => {
      if (p.serviceName !== scope.service) return false;
      if (p.lineItemId != null) return scopeLineIds.has(p.lineItemId);
      if (scope.lineId != null) {
        const line = allLines.find(l => l.id === scope.lineId);
        return !!line
          && (!p.regionName || (line.regionName || '') === p.regionName)
          && (!p.usageType || line.usageType === p.usageType);
      }
      const regionOverlaps = !p.regionName || !scope.region || p.regionName === scope.region;
      const usageOverlaps = !p.usageType || !scope.usageType || p.usageType === scope.usageType;
      return regionOverlaps && usageOverlaps;
    });
  }, [privatePricing, linesInScope, allLines]);

  /** The invoice lines the open editor's scope would price. */
  const priceScopeLines = useMemo(
    () => (pricingScope ? linesInScope(pricingScope) : []),
    [pricingScope, linesInScope]);

  /**
   * What applying the current rate would do, worked out the way the server will.
   *
   * Shown before committing because the alternative is applying a rate to find out: the
   * quantities on this page span eight orders of magnitude, so a per-GB rate that looks
   * sensible against one line can be wildly wrong against another.
   */
  const pricePreview = useMemo(() => {
    const parsedRate = Number(priceRate);
    const rateIsUsable = priceRate.trim() !== '' && Number.isFinite(parsedRate) && parsedRate >= 0;

    const priceable = priceScopeLines.filter(l => pricingSkipReason(l) === null);
    const skipped = priceScopeLines.filter(l => pricingSkipReason(l) !== null);

    // One rate, one unit -- the check the server enforces. Placeholder units are left
    // out: the region-only fallback line writes "N/A", which names no measure.
    const units = [...new Set(priceable
      .map(l => (l.unit ?? '').trim().toLowerCase())
      .filter(u => u !== '' && u !== 'n/a' && u !== 'na' && u !== 'none'))];

    const divisor = rateUnitDivisor(priceRateUnit);
    const quantity = priceable.reduce((sum, l) => sum + (usageQuantityOf(l.usageQuantity) ?? 0), 0);
    const providerCost = priceable.reduce((sum, l) => sum + (l.cost ?? 0), 0);
    const billed = rateIsUsable ? (quantity / divisor) * parsedRate : null;

    return {
      rateIsUsable,
      lineCount: priceable.length,
      skippedCount: skipped.length,
      skipNotes: [...new Set(skipped.map(l => pricingSkipReason(l)!))],
      units,
      mixedUnits: units.length > 1,
      quantity,
      divisor,
      providerCost,
      billed,
      margin: billed === null ? null : billed - providerCost,
    };
  }, [priceScopeLines, priceRate, priceRateUnit]);

  /** The unit the scope is measured in, offered as the rate's unit by default. */
  const impliedRateUnit = pricePreview.units.length === 1 ? pricePreview.units[0] : '';

  /**
   * Opens the editor on a row, or closes it if that row is already open.
   *
   * The scope's own unit is prefilled, so pricing CloudFront egress per GB takes a rate
   * and nothing else.
   */
  const openPricingOn = useCallback((scope: PricingScope) => {
    setPricingError(null);
    setPricingScope(prev => {
      if (prev && sameScope(prev, scope)) return null;

      const lines = linesInScope(scope).filter(l => pricingSkipReason(l) === null);
      const units = [...new Set(lines
        .map(l => (l.unit ?? '').trim())
        .filter(u => u !== '' && !['n/a', 'na', 'none'].includes(u.toLowerCase())))];
      setPriceRate('');
      setPriceRateUnit(units.length === 1 ? units[0] : '');
      setPriceShield(true);
      return scope;
    });
  }, [linesInScope]);

  const closePricing = useCallback(() => {
    setPricingScope(null);
    setPricingError(null);
  }, []);

  // ── Actions ───────────────────────────────────────────────────────────────

  const applyDiscount = useCallback(async () => {
    if (!invoice || !discountService.trim() || !discountPct.trim()) {
      setDiscountError('Choose what to discount and enter a percentage.');
      return;
    }
    const pct = parseFloat(discountPct);
    if (Number.isNaN(pct) || pct <= 0 || pct > 100) {
      setDiscountError('Percentage must be between 0.01 and 100.');
      return;
    }
    setApplyingDiscount(true);
    setDiscountError(null);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/discount`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ serviceName: discountService.trim(), percentage: pct }),
      });
      if (!res.ok) throw new Error('Failed to apply discount.');
      setInvoice(await res.json());
      setDiscountService('');
      setDiscountPct('');
    } catch (err) {
      setDiscountError(err instanceof Error ? err.message : 'Failed to apply discount.');
    } finally {
      setApplyingDiscount(false);
    }
  }, [invoice, discountService, discountPct]);

  const removeDiscount = useCallback(async (discountId: number) => {
    if (!invoice) return;
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/discounts/${discountId}`, {
        method: 'DELETE',
        credentials: 'include',
      });
      if (!res.ok) throw new Error('Failed to remove discount.');
      setInvoice(await res.json());
    } catch (err) {
      setDiscountError(err instanceof Error ? err.message : 'Failed to remove discount.');
    }
  }, [invoice]);

  /** Reads the server's own explanation out of a failed pricing call. */
  const pricingErrorFrom = async (res: Response, fallback: string): Promise<string> => {
    try {
      const body = await res.json();
      return typeof body?.message === 'string' && body.message ? body.message : fallback;
    } catch {
      return fallback;
    }
  };

  /**
   * Sets or clears the AWS Marketplace holding-cost markup. Passing an empty input clears
   * it (sends {@code percent: null}). The server returns the recomputed invoice, which
   * replaces the whole invoice so Taxable/GST/Total and the holding-cost line all refresh.
   */
  const applyHoldingCost = useCallback(async (rawInput: string) => {
    if (!invoice) return;
    const trimmed = rawInput.trim();
    const percent = trimmed === '' ? null : Number(trimmed);
    if (percent !== null && (!Number.isFinite(percent) || percent < 0 || percent > 100)) {
      setHoldingCostError('Holding cost must be between 0 and 100 percent.');
      return;
    }
    setApplyingHoldingCost(true);
    setHoldingCostError(null);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/holding-cost`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ percent }),
      });
      if (!res.ok) throw new Error(await pricingErrorFrom(res, 'Failed to set holding cost.'));
      setInvoice(await res.json());
      setHoldingPctInput('');
    } catch (err) {
      setHoldingCostError(err instanceof Error ? err.message : 'Failed to set holding cost.');
    } finally {
      setApplyingHoldingCost(false);
    }
  }, [invoice]);

  const applyPricing = useCallback(async () => {
    if (!invoice || !pricingScope) return;
    const parsedRate = Number(priceRate);
    if (priceRate.trim() === '' || !Number.isFinite(parsedRate) || parsedRate < 0) {
      setPricingError('Enter a rate of zero or more.');
      return;
    }
    setApplyingPrice(true);
    setPricingError(null);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/private-pricing`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          serviceName: pricingScope.service,
          regionName: pricingScope.lineId != null ? null : pricingScope.region || null,
          usageType: pricingScope.lineId != null ? null : pricingScope.usageType || null,
          lineItemId: pricingScope.lineId ?? null,
          unitRate: parsedRate,
          rateUnit: priceRateUnit.trim() || null,
          excludeFromOverallDiscount: priceShield,
        }),
      });
      if (!res.ok) throw new Error(await pricingErrorFrom(res, 'Failed to apply private pricing.'));
      setInvoice(await res.json());
      setPricingScope(null);
      setPriceRate('');
      setPriceRateUnit('');
      setPriceShield(true);
    } catch (err) {
      setPricingError(err instanceof Error ? err.message : 'Failed to apply private pricing.');
    } finally {
      setApplyingPrice(false);
    }
  }, [invoice, pricingScope, priceRate, priceRateUnit, priceShield]);

  const togglePricingShield = useCallback(async (pricingId: number, exclude: boolean) => {
    if (!invoice) return;
    setPricingBusyId(pricingId);
    setPricingError(null);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/private-pricing/${pricingId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({ excludeFromOverallDiscount: exclude }),
      });
      if (!res.ok) throw new Error(await pricingErrorFrom(res, 'Failed to change this rate.'));
      setInvoice(await res.json());
    } catch (err) {
      setPricingError(err instanceof Error ? err.message : 'Failed to change this rate.');
    } finally {
      setPricingBusyId(null);
    }
  }, [invoice]);

  const removePricing = useCallback(async (pricingId: number) => {
    if (!invoice) return;
    setPricingBusyId(pricingId);
    setPricingError(null);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/private-pricing/${pricingId}`, {
        method: 'DELETE',
        credentials: 'include',
      });
      if (!res.ok) throw new Error(await pricingErrorFrom(res, 'Failed to remove this rate.'));
      setInvoice(await res.json());
    } catch (err) {
      setPricingError(err instanceof Error ? err.message : 'Failed to remove this rate.');
    } finally {
      setPricingBusyId(null);
    }
  }, [invoice]);

  const finalizeInvoice = useCallback(async () => {
    if (!invoice) return;
    // The rate is reviewed before anything is locked in: a NaN or <= 1 value is a mistake,
    // so stop here (the inline validation on the field explains it) rather than posting.
    const rate = Number(chosenRate);
    if (chosenRate.trim() === '' || Number.isNaN(rate) || rate <= 1) {
      setRateTouched(true);
      setConfirmFinalize(false);
      return;
    }
    setConfirmFinalize(false);
    setFinalizing(true);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/finalize`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ exchangeRate: rate }),
      });
      if (!res.ok) {
        let message = 'Failed to finalize invoice.';
        try {
          const body = await res.json();
          if (body?.message) message = body.message;
        } catch {
          // non-JSON error body; keep the default message
        }
        throw new Error(message);
      }
      setInvoice(await res.json());
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to finalize invoice.');
    } finally {
      setFinalizing(false);
    }
  }, [invoice, chosenRate]);

  const download = useCallback(async (kind: 'pdf' | 'detail') => {
    if (!invoice) return;
    setDownloading(kind);
    try {
      const detailRes = await apiFetch(`/api/admin/invoices/${invoice.id}`, { credentials: 'include' });
      if (!detailRes.ok) throw new Error('Failed to fetch invoice.');
      const fullDto = await detailRes.json();

      const endpoint = kind === 'pdf' ? '/api/billops/invoices/download-preview' : '/api/billops/invoices/download-detail';
      const pdfRes = await apiFetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(fullDto),
      });
      if (!pdfRes.ok) throw new Error(kind === 'pdf' ? 'PDF generation failed.' : 'Detail bill generation failed.');

      const blob = await pdfRes.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.style.display = 'none';
      a.href = url;
      a.download = `${kind === 'pdf' ? 'preview' : 'detail-bill'}-${invoice.invoiceNumber}.pdf`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Download failed.');
    } finally {
      setDownloading(null);
    }
  }, [invoice]);

  // ── States ────────────────────────────────────────────────────────────────

  const backToInvoices = () => navigate('/billops-admin-invoices');

  if (!invoiceId) {
    return (
      <main className="inv-page">
        <div className="inv-alert inv-alert--error" role="alert">
          <i className="fas fa-times-circle" aria-hidden="true" />
          <p className="flex-1">No invoice was specified.</p>
          <button type="button" className="inv-btn inv-btn--sm" onClick={backToInvoices}>Back to invoices</button>
        </div>
      </main>
    );
  }

  if (loading && !invoice) {
    return (
      <main className="inv-page" aria-busy="true">
        <div className="inv-shimmer" style={{ width: 180, height: 12 }} />
        <div className="inv-shimmer" style={{ width: 280, height: 26 }} />
        <div className="inv-card" style={{ height: 180 }}>
          <div className="p-6 space-y-3">
            <div className="inv-shimmer" style={{ width: 120, height: 12 }} />
            <div className="inv-shimmer" style={{ width: 240, height: 40 }} />
          </div>
        </div>
        <div className="inv-card" style={{ height: 320 }} />
      </main>
    );
  }

  if (error || !invoice) {
    return (
      <main className="inv-page">
        <div className="inv-alert inv-alert--error" role="alert">
          <i className="fas fa-times-circle" aria-hidden="true" />
          <div className="flex-1">
            <p className="inv-alert__title">Couldn&rsquo;t load this invoice</p>
            <p>{error ?? 'Unknown error.'}</p>
          </div>
          <button type="button" className="inv-btn inv-btn--sm" onClick={fetchInvoice}>Try again</button>
          <button type="button" className="inv-btn inv-btn--ghost inv-btn--sm" onClick={backToInvoices}>Back</button>
        </div>
      </main>
    );
  }

  const isDraft = invoice.status === 'DRAFT';
  const marketplace = invoice.marketplaceCharge ?? 0;
  const holdingPct = invoice.marketplaceHoldingCostPercent ?? 0;
  // Match the server exactly: it truncates the grossed-up Marketplace USD to 2 dp (DOWN),
  // so floor the grossed component before deriving the margin. Never show a taxable/GST/
  // total that disagrees with the server and the PDF.
  const grossedMarketplace = holdingPct > 0
    ? Math.floor(marketplace * (1 + holdingPct / 100) * 100) / 100
    : marketplace;
  const holdingMargin = grossedMarketplace - marketplace;
  const taxable = (invoice.preDiscountTotal ?? 0) - (invoice.discountAmount ?? 0) + holdingMargin;

  // The admin-chosen rate, parsed. Valid only when it's a real number above 1 (INR per
  // USD is always well above that), which is also what the backend enforces at finalize.
  const parsedRate = Number(chosenRate);
  const rateValid = chosenRate.trim() !== '' && !Number.isNaN(parsedRate) && parsedRate > 1;
  // The rate the finalized/preview INR figures should use: the invoice's locked-in rate
  // once finalized, else the admin's valid chosen rate while drafting, else the live rate.
  const effectiveRate: number | null = !isDraft
    ? (invoice.exchangeRate ?? null)
    : rateValid
      ? parsedRate
      : (invoice.exchangeRate ?? usdRate ?? null);
  // Whether the shown rate still comes from the live feed (so the date suffix applies).
  const rateFromLiveFeed = isDraft && !rateValid && invoice.exchangeRate == null && usdRate != null;

  // ── Private pricing, in the charges tree ──────────────────────────────────
  //
  // Every row of the tree that a rate can be scoped to carries its own control, so
  // pricing happens where the charge is being read rather than in a form somewhere else
  // that restates the same hierarchy.

  const scopeIsOpen = (scope: PricingScope): boolean => Boolean(pricingScope && sameScope(pricingScope, scope));

  /**
   * The rate control for one row: the applied rate and its discount checkbox, a note
   * when a broader rate already covers these charges, or the button that opens the editor.
   */
  const pricingCell = (scope: PricingScope) => {
    const applied = rateForScope(scope);

    if (applied) {
      const busy = pricingBusyId === applied.id;
      // Two lines, not one: the rate, its remove button and the discount checkbox do not
      // fit side by side in this column, and when they were inline the remove button was
      // pushed out of the column into the table's horizontal overflow — present in the
      // DOM, unreachable on screen.
      return (
        <div className="inv-rate-cell">
          <span className="inv-rate-cell__rate">
            <span className="inv-pill inv-pill--priced" title={applied.description ?? undefined}>
              <i className="fas fa-tag" aria-hidden="true" />
              {rate(applied.unitRate)}
              {applied.rateUnit && <span className="inv-pill__unit">/ {applied.rateUnit}</span>}
            </span>
            {isDraft && (
              <button
                type="button"
                className="inv-icon-btn inv-icon-btn--danger inv-icon-btn--sm"
                onClick={() => removePricing(applied.id)}
                disabled={busy}
                aria-label={`Remove the rate on ${applied.scopeLabel}`}
                title="Remove this rate and go back to provider cost"
              >
                <i className={`fas ${busy ? 'fa-spinner fa-spin' : 'fa-times'}`} aria-hidden="true" />
              </button>
            )}
          </span>
          {/* The checkbox the overall discount reads. Shown on the row that set the rate,
              which is where an admin looks to answer "is this one discountable?". */}
          <label className="inv-rate-cell__shield" title="Exclude these charges from the invoice's overall discount">
            <input
              type="checkbox"
              checked={applied.excludeFromOverallDiscount}
              disabled={!isDraft || busy}
              onChange={e => togglePricingShield(applied.id, e.target.checked)}
              aria-label={`Exclude ${applied.scopeLabel} from the overall discount`}
            />
            <span>No discount</span>
          </label>
        </div>
      );
    }

    if (!isDraft) return null;

    // A rate on a broader or narrower scope already owns these lines. Said here rather
    // than left to the server to refuse after the admin has typed a rate.
    const covering = overlappingRate(scope);
    if (covering) {
      // Which way to point matters: a service row whose usage type carries a rate is
      // priced below it, and telling the admin to look up would send them nowhere.
      const broader = scopeDepth(covering) < scopeDepth(scope);
      return (
        <span
          className="inv-rate-cell__covered"
          title={`Already priced by “${covering.scopeLabel}”. Remove that rate to price these separately.`}
        >
          <i className="fas fa-tag" aria-hidden="true" />
          {broader ? 'priced above' : 'priced below'}
        </span>
      );
    }

    const open = scopeIsOpen(scope);
    return (
      <button
        type="button"
        className={`inv-btn inv-btn--ghost inv-btn--sm inv-rate-btn${open ? ' is-open' : ''}`}
        onClick={() => openPricingOn(scope)}
        aria-expanded={open}
        title="Bill these charges at your own per-unit rate"
      >
        <i className="fas fa-tag" aria-hidden="true" />
        {open ? 'Cancel' : 'Set rate'}
      </button>
    );
  };

  /** The scope for one charge line: the line itself, with its levels kept for overlap checks. */
  const lineScopeOf = (service: string, line: LineItemDto): PricingScope => ({
    service,
    region: line.regionName || undefined,
    usageType: line.usageType || undefined,
    lineId: line.id,
    lineLabel: line.resourceName || undefined,
  });

  /**
   * The rate control on a single charge. Credits, Savings Plan lines and lines with no
   * usage get none: a per-unit rate has nothing to multiply there, and offering the button
   * would only lead to an editor explaining that.
   */
  const linePricingCell = (scope: PricingScope, line: LineItemDto) => {
    if (!rateForScope(scope) && pricingSkipReason(line) !== null) return null;
    return pricingCell(scope);
  };

  /** The editor, as a row of the tree directly beneath the charges it prices. */
  const pricingEditorRow = (scope: PricingScope, key: string) => {
    if (!scopeIsOpen(scope)) return null;
    const label = [scope.service, scope.region ? regionLabel(scope.region) : null, scope.usageType, scope.lineLabel]
      .filter(Boolean).join(' · ');

    return (
      <tr key={`${key}|rate-editor`} className="inv-rate-editor-row">
        <td colSpan={5}>
          <div className="inv-rate-editor">
            <p className="inv-rate-editor__scope">
              Billing <strong>{label}</strong> at your own rate
              <small>The amount becomes usage quantity × your rate, instead of the provider&rsquo;s cost.</small>
            </p>

            {pricingError && (
              <div className="inv-alert inv-alert--warning" role="alert">
                <i className="fas fa-exclamation-triangle" aria-hidden="true" />
                <p className="flex-1">{pricingError}</p>
              </div>
            )}

            <div className="inv-rate-editor__fields">
              <div>
                <label className="inv-field-label" htmlFor="rate-value">Your rate (USD)</label>
                <input
                  id="rate-value"
                  type="number"
                  className="inv-input inv-num"
                  value={priceRate}
                  onChange={e => setPriceRate(e.target.value)}
                  placeholder="e.g. 0.085"
                  min="0"
                  step="any"
                  autoFocus
                />
              </div>
              <div>
                <label className="inv-field-label" htmlFor="rate-unit">Per</label>
                <input
                  id="rate-unit"
                  type="text"
                  className="inv-input"
                  value={priceRateUnit}
                  onChange={e => setPriceRateUnit(e.target.value)}
                  placeholder={impliedRateUnit || 'e.g. GB, or Per 10000 Requests'}
                />
              </div>
              <label className="inv-rate-editor__shield" htmlFor="rate-shield">
                <input
                  id="rate-shield"
                  type="checkbox"
                  checked={priceShield}
                  onChange={e => setPriceShield(e.target.checked)}
                />
                <span>
                  Exclude from the overall discount
                  <small>An invoice-wide discount will skip these charges.</small>
                </span>
              </label>
              <div className="inv-rate-editor__actions">
                <button type="button" className="inv-btn inv-btn--ghost inv-btn--sm" onClick={closePricing}>
                  Cancel
                </button>
                <button
                  type="button"
                  className="inv-btn inv-btn--primary inv-btn--sm"
                  onClick={applyPricing}
                  disabled={applyingPrice || !priceRate || pricePreview.mixedUnits || pricePreview.lineCount === 0}
                >
                  {applyingPrice
                    ? <><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Applying…</>
                    : 'Apply rate'}
                </button>
              </div>
            </div>

            {/* What the rate will do, before it is committed. */}
            {pricePreview.mixedUnits ? (
              <p className="inv-rate-editor__note is-warning">
                <i className="fas fa-exclamation-triangle" aria-hidden="true" />
                These charges are measured in more than one unit ({pricePreview.units.join(', ')}), so one
                rate cannot price them all. Set a rate on a single usage type instead.
              </p>
            ) : pricePreview.lineCount === 0 ? (
              <p className="inv-rate-editor__note is-warning">
                <i className="fas fa-exclamation-triangle" aria-hidden="true" />
                Nothing here carries usage a per-unit rate can price
                {pricePreview.skipNotes.length > 0 && <> ({pricePreview.skipNotes.join(', ')})</>}.
              </p>
            ) : (
              <p className="inv-rate-editor__note">
                <i className="fas fa-calculator" aria-hidden="true" />
                <span>
                  <strong className="inv-num">{pricePreview.lineCount}</strong>
                  {pricePreview.lineCount === 1 ? ' line' : ' lines'}
                  {' · '}
                  <strong className="inv-num">{formatQuantity(String(pricePreview.quantity))}</strong>
                  {pricePreview.units.length === 1 ? ` ${pricePreview.units[0]}` : ''}
                  {pricePreview.billed !== null && (
                    <>
                      {' → '}
                      <strong className="inv-num">{usd(pricePreview.billed)}</strong>
                      {' (provider cost '}
                      <span className="inv-num">{usd(pricePreview.providerCost)}</span>
                      {pricePreview.margin !== null && (
                        <>, margin <span className="inv-num">{usd(pricePreview.margin)}</span></>
                      )}
                      {')'}
                    </>
                  )}
                  {pricePreview.skippedCount > 0 && (
                    <small className="block inv-muted">
                      {pricePreview.skippedCount}{' '}
                      {pricePreview.skippedCount === 1 ? 'line stays' : 'lines stay'} at provider cost
                      ({pricePreview.skipNotes.join(', ')}) — a credit or Savings Plan line is never
                      repriced.
                    </small>
                  )}
                </span>
              </p>
            )}
          </div>
        </td>
      </tr>
    );
  };

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <AlertDialog
        isOpen={confirmFinalize}
        title="Finalize this invoice?"
        description={`${invoice.invoiceNumber} will be marked finalized and sent to the client. Discounts can no longer be changed, and this can't be undone.${rateValid ? ` The invoice will be locked at ₹${parsedRate.toFixed(2)} per USD.` : ''}`}
        confirmLabel="Finalize"
        variant="warning"
        onConfirm={finalizeInvoice}
        onCancel={() => setConfirmFinalize(false)}
      />

      <main className="inv-page">
        {/* Breadcrumb */}
        <nav className="inv-breadcrumb" aria-label="Breadcrumb">
          <button type="button" onClick={backToInvoices}>Invoices</button>
          <i className="fas fa-chevron-right text-[9px]" aria-hidden="true" />
          <span aria-current="page">{invoice.invoiceNumber}</span>
        </nav>

        {/* Header */}
        <div className="flex items-start justify-between gap-4 flex-wrap" style={{ marginTop: 10 }}>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h1 className="inv-h1 inv-num">{invoice.invoiceNumber}</h1>
              <span className={`inv-pill ${isDraft ? 'inv-pill--draft' : invoice.status === 'FINALIZED' ? 'inv-pill--finalized' : 'inv-pill--void'}`}>
                <i className={`fas ${isDraft ? 'fa-pen' : invoice.status === 'FINALIZED' ? 'fa-check' : 'fa-ban'}`} aria-hidden="true" />
                {isDraft ? 'Draft' : invoice.status === 'FINALIZED' ? 'Finalized' : 'Void'}
              </span>
              {invoice.dataSource === 'CUR' && (
                <span className="inv-pill inv-pill--cur" title="Built from the Cost and Usage Report">CUR</span>
              )}
              {invoice.dataSource === 'COST_EXPLORER' && (
                <span className="inv-pill inv-pill--ce" title="Built from Cost Explorer">Cost Explorer</span>
              )}
              {invoice.uploadedFileName && (
                <span className="inv-pill inv-pill--upload" title={invoice.uploadedFileName}>Uploaded</span>
              )}
              {invoice.provisional && <span className="inv-pill inv-pill--provisional">Provisional</span>}
            </div>
            <p className="inv-desc" style={{ fontSize: 13 }}>
              {invoice.accountName} · <span className="inv-num inv-muted">{accountIdOf(invoice)}</span> · {formatPeriod(invoice.billingPeriod)}
            </p>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <button type="button" className="inv-btn" onClick={() => download('pdf')} disabled={downloading !== null}>
              <i className={`fas ${downloading === 'pdf' ? 'fa-spinner fa-spin' : 'fa-file-pdf'}`} aria-hidden="true" />
              Download PDF
            </button>
            <button
              type="button"
              className="inv-btn"
              onClick={() => download('detail')}
              disabled={downloading !== null}
              title="Every charge line, without tax"
            >
              <i className={`fas ${downloading === 'detail' ? 'fa-spinner fa-spin' : 'fa-file-invoice'}`} aria-hidden="true" />
              Detail bill
            </button>
            {isDraft && (
              <button
                type="button"
                className="inv-btn inv-btn--primary"
                onClick={() => { if (!rateValid) { setRateTouched(true); return; } setConfirmFinalize(true); }}
                disabled={finalizing || !rateValid}
                title={!rateValid ? 'Set a conversion rate greater than 1 to finalize' : undefined}
              >
                <i className={`fas ${finalizing ? 'fa-spinner fa-spin' : 'fa-check-circle'}`} aria-hidden="true" />
                {finalizing ? 'Finalizing…' : 'Finalize'}
              </button>
            )}
          </div>
        </div>

        {invoice.provisional && (
          <div className="inv-alert inv-alert--info" role="note">
            <i className="fas fa-info-circle" aria-hidden="true" />
            <div>
              <p className="inv-alert__title">Month-to-date estimate</p>
              <p>
                {formatPeriod(invoice.billingPeriod)} hadn&rsquo;t closed when this draft was built, so AWS may still change these
                figures. Create a new draft after the month closes for the final bill.
              </p>
            </div>
          </div>
        )}

        {/* Summary */}
        <section className="inv-card overflow-hidden" aria-label="Invoice summary">
          <div className="inv-summary">
            <div className="inv-summary__hero">
              <p className="inv-kpi__label">Total due</p>
              <p className="inv-hero-value">{usd(invoice.amount)}</p>
              <p className="inv-hero-sub">
                {effectiveRate != null && effectiveRate > 1
                  ? <>≈ {inr((invoice.amount ?? 0) * effectiveRate)} at ₹{effectiveRate.toFixed(2)} per USD{rateFromLiveFeed && rateDate ? ` (${rateDate})` : ''}</>
                  : 'USD, including tax'}
              </p>

              {isDraft ? (
                <div className="inv-rate" style={{ marginTop: 16 }}>
                  <label htmlFor="inv-exchange-rate" className="inv-kpi__label">
                    USD → INR conversion rate
                  </label>
                  <div className="inv-rate__field" style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 6 }}>
                    <span className="inv-ink-2" aria-hidden="true">₹</span>
                    <input
                      id="inv-exchange-rate"
                      type="number"
                      inputMode="decimal"
                      step="0.01"
                      min="1.01"
                      className="inv-input"
                      value={chosenRate}
                      aria-describedby="inv-exchange-rate-help"
                      aria-invalid={rateTouched && !rateValid}
                      onChange={e => { setChosenRate(e.target.value); setRateTouched(true); }}
                    />
                    <span className="inv-ink-2">per USD</span>
                  </div>
                  <p id="inv-exchange-rate-help" className="text-[12px] inv-muted" style={{ marginTop: 4 }}>
                    {rateTouched && !rateValid
                      ? <span role="alert" style={{ color: '#dc2626' }}>Enter a rate greater than 1 before finalizing.</span>
                      : 'Review and adjust the rate the finalized invoice will be locked at.'}
                  </p>
                </div>
              ) : invoice.exchangeRate != null && invoice.exchangeRate > 1 ? (
                <p className="inv-ink-2" style={{ marginTop: 10 }}>
                  Finalized at ₹{invoice.exchangeRate.toFixed(2)} per USD
                </p>
              ) : null}

              {/* Who and what this bill is for, beside the figure it arrives at. */}
              <dl className="inv-kv" style={{ marginTop: 22, paddingTop: 18, borderTop: '1px solid var(--inv-divider)' }}>
                <div>
                  <dt>Account</dt>
                  <dd>{invoice.accountName}<small className="inv-num">{accountIdOf(invoice)}</small></dd>
                </div>
                <div>
                  <dt>Client</dt>
                  <dd>{invoice.clientName || '—'}</dd>
                </div>
                <div>
                  <dt>Invoice date</dt>
                  <dd>{formatDate(invoice.invoiceDate)}</dd>
                </div>
                <div>
                  <dt>Invoice type</dt>
                  <dd>{invoice.includeIrn ? 'Tax invoice (with IRN)' : 'Proforma invoice'}</dd>
                </div>
                <div>
                  <dt>Figures from</dt>
                  <dd title={invoice.uploadedFileName ?? undefined}>
                    {invoice.uploadedFileName
                      ? 'Uploaded AWS bill'
                      : invoice.dataSource === 'CUR'
                      ? 'Cost and Usage Report'
                      : invoice.dataSource === 'COST_EXPLORER'
                        ? 'Cost Explorer'
                        : invoice.provider && invoice.provider !== 'AWS' ? `${invoice.provider} billing export` : '—'}
                  </dd>
                </div>
              </dl>
            </div>
            <div className="inv-summary__lines">
              <div className="inv-summary-line">
                <span>
                  Charges
                  <small>
                    {fullTree.length} service{fullTree.length === 1 ? '' : 's'}, {allLines.length} line{allLines.length === 1 ? '' : 's'}
                    {marketplace > 0 && <> · includes {usd(marketplace)} AWS Marketplace, never discounted</>}
                  </small>
                </span>
                <strong className="inv-num">{usd(invoice.preDiscountTotal)}</strong>
              </div>
              {marketplace > 0 && (
                isDraft ? (
                  <div className="inv-summary-line">
                    <span>
                      AWS Marketplace holding cost
                      <small>Markup applied only to the {usd(marketplace)} AWS Marketplace charge</small>
                      {holdingCostError && (
                        <small className="inv-error" role="alert">{holdingCostError}</small>
                      )}
                    </span>
                    <span className="flex items-center gap-1">
                      <input
                        type="number"
                        min={0}
                        max={100}
                        step={0.01}
                        className="inv-input inv-num"
                        style={{ width: 70 }}
                        placeholder="15"
                        aria-label="AWS Marketplace holding cost percent"
                        value={holdingPctInput !== '' ? holdingPctInput : (holdingPct > 0 ? String(holdingPct) : '')}
                        disabled={applyingHoldingCost}
                        onChange={e => setHoldingPctInput(e.target.value)}
                      />
                      <span>%</span>
                      <button
                        type="button"
                        className="inv-btn inv-btn--ghost inv-btn--sm"
                        disabled={applyingHoldingCost}
                        onClick={() => applyHoldingCost(holdingPctInput)}
                      >
                        Apply
                      </button>
                      {holdingPct > 0 && (
                        <button
                          type="button"
                          className="inv-btn inv-btn--ghost inv-btn--sm"
                          disabled={applyingHoldingCost}
                          onClick={() => applyHoldingCost('')}
                        >
                          Clear
                        </button>
                      )}
                    </span>
                  </div>
                ) : holdingPct > 0 ? (
                  <div className="inv-summary-line">
                    <span>AWS Marketplace holding cost</span>
                    <strong className="inv-num">{holdingPct}% on AWS Marketplace</strong>
                  </div>
                ) : null
              )}
              {holdingPct > 0 && (
                <div className="inv-summary-line">
                  <span>
                    Holding cost (AWS Marketplace)
                    <small>{holdingPct}% of {usd(marketplace)} AWS Marketplace</small>
                  </span>
                  <strong className="inv-num">+{usd(holdingMargin)}</strong>
                </div>
              )}
              {(invoice.privatePricedTotal ?? 0) !== 0 && (
                <div className="inv-summary-line">
                  <span>
                    At private rates
                    <small>
                      {privatePricing.length} rate{privatePricing.length === 1 ? '' : 's'} applied
                      {(invoice.discountShieldedTotal ?? 0) > 0
                        && <> · {usd(invoice.discountShieldedTotal)} shielded from the overall discount</>}
                    </small>
                  </span>
                  <strong className="inv-num">{usd(invoice.privatePricedTotal)}</strong>
                </div>
              )}
              <div className="inv-summary-line">
                <span>
                  Discounts
                  {invoice.discounts?.length > 0 && (
                    <small>{invoice.discounts.length} applied</small>
                  )}
                </span>
                <strong className="inv-num">{invoice.discountAmount ? `−${usd(invoice.discountAmount)}` : usd(0)}</strong>
              </div>
              <div className="inv-summary-line">
                <span>Taxable value</span>
                <strong className="inv-num">{usd(taxable)}</strong>
              </div>
              <div className="inv-summary-line">
                <span>GST</span>
                <strong className="inv-num">{usd(invoice.taxAmount)}</strong>
              </div>
              <div className="inv-summary-line inv-summary-line--total">
                <span>Total due</span>
                <span className="inv-num">{usd(invoice.amount)}</span>
              </div>
            </div>
          </div>
        </section>

        {/* Charges & discounts */}
        <section className="inv-card">
          <div className="px-5 pt-2">
            <AriaTabGroup
              tabs={[
                { id: 'charges', label: `Charges by service (${fullTree.length})` },
                { id: 'discounts', label: `Discounts (${invoice.discounts?.length ?? 0})` },
              ]}
              selectedKey={tab}
              onSelectionChange={key => setTab(key as 'charges' | 'discounts')}
            />
          </div>

          {tab === 'charges' && (
            <div role="tabpanel" aria-label="Charges by service">
              <div className="flex items-center justify-between gap-3 flex-wrap px-5 py-3">
                <div className="inv-search flex-1 max-w-md">
                  <i className="fas fa-search" aria-hidden="true" />
                  <input
                    type="search"
                    className="inv-input"
                    placeholder="Find a service, region, usage type or charge"
                    value={query}
                    onChange={e => setQuery(e.target.value)}
                    aria-label="Search charges"
                  />
                </div>
                <div className="flex items-center gap-1">
                  {q ? (
                    <span className="text-[12.5px] inv-muted">
                      {matchingLines.length} of {allLines.length} lines match
                    </span>
                  ) : (
                    <>
                      <button type="button" className="inv-btn inv-btn--ghost inv-btn--sm" onClick={expandAll}>Expand all</button>
                      <button type="button" className="inv-btn inv-btn--ghost inv-btn--sm" onClick={collapseAll}>Collapse all</button>
                    </>
                  )}
                </div>
              </div>

              {allLines.length === 0 ? (
                <div className="inv-empty">
                  <i className="fas fa-inbox" aria-hidden="true" />
                  <strong>No charges on this invoice</strong>
                </div>
              ) : tree.length === 0 ? (
                <div className="inv-empty">
                  <i className="fas fa-search" aria-hidden="true" />
                  <strong>Nothing matches &ldquo;{query}&rdquo;</strong>
                  <div className="mt-3">
                    <button type="button" className="inv-btn inv-btn--sm" onClick={() => setQuery('')}>Clear search</button>
                  </div>
                </div>
              ) : (
                <div className="inv-table-wrap">
                  <table className="inv-table inv-tree">
                    <thead>
                      <tr>
                        <th>Description</th>
                        <th className="hidden md:table-cell inv-col-share">Share of charges</th>
                        <th className="is-num inv-col-qty">Usage quantity</th>
                        <th className="is-num inv-col-amt">Amount (USD)</th>
                        <th className="inv-col-rate">Private rate</th>
                      </tr>
                    </thead>
                    <tbody>
                      {tree.map(svc => {
                        const svcOpen = isServiceOpen(svc.service);
                        const share = chargesTotal > 0 ? Math.max(0, svc.total / chargesTotal) : 0;
                        return (
                          <React.Fragment key={svc.service}>
                            <tr className="inv-tree-row--service">
                              <td className="inv-tree-cell">
                                <button
                                  type="button"
                                  className="inv-tree-toggle"
                                  aria-expanded={svcOpen}
                                  onClick={() => toggle(setOpenServices, svc.service)}
                                  disabled={Boolean(q)}
                                >
                                  <i className="fas fa-chevron-right inv-chevron" aria-hidden="true" />
                                  {svc.service}
                                </button>
                              </td>
                              <td className="hidden md:table-cell">
                                {chargesTotal > 0 && (
                                  <span className="inv-share" title={`${(share * 100).toFixed(1)}% of charges`}>
                                    <span className="inv-meter" aria-hidden="true">
                                      <span className="inv-meter__fill" style={{ width: `${Math.min(100, share * 100)}%`, display: 'block' }} />
                                    </span>
                                    <span className="inv-num">{share >= 0.001 ? `${(share * 100).toFixed(1)}%` : '<0.1%'}</span>
                                  </span>
                                )}
                              </td>
                              <td className="is-num inv-qty-cell" />
                              <td className="is-num inv-num">{usd(svc.total)}</td>
                              <td className="inv-rate-col">{pricingCell({ service: svc.service })}</td>
                            </tr>
                            {pricingEditorRow({ service: svc.service }, svc.service)}

                            {svcOpen && svc.regions.map(rgn => {
                              const rgnOpen = isRegionOpen(rgn.key);
                              // A region can only be priced on its own if the charges
                              // actually carry one. CloudFront's CUR lines have no region
                              // — the geography lives in the usage type — so there the
                              // service and usage-type levels are the real choices.
                              const rgnScope: PricingScope | null = rgn.rawRegion
                                ? { service: svc.service, region: rgn.rawRegion }
                                : null;
                              const utRegion = rgn.rawRegion || undefined;
                              return (
                                <React.Fragment key={rgn.key}>
                                  <tr className="inv-tree-row--region">
                                    <td className="inv-tree-cell">
                                      <button
                                        type="button"
                                        className="inv-tree-toggle"
                                        aria-expanded={rgnOpen}
                                        onClick={() => toggle(setOpenRegions, rgn.key)}
                                        disabled={Boolean(q)}
                                      >
                                        <i className="fas fa-chevron-right inv-chevron" aria-hidden="true" />
                                        {regionLabel(rgn.region)}
                                      </button>
                                    </td>
                                    <td className="hidden md:table-cell" />
                                    <td className="is-num inv-qty-cell" />
                                    <td className="is-num inv-num">{usd(rgn.total)}</td>
                                    <td className="inv-rate-col">{rgnScope && pricingCell(rgnScope)}</td>
                                  </tr>
                                  {rgnScope && pricingEditorRow(rgnScope, rgn.key)}

                                  {rgnOpen && rgn.usageTypes.map(ut => {
                                    const utScope: PricingScope = {
                                      service: svc.service, region: utRegion, usageType: ut.usageType,
                                    };
                                    return (
                                    <React.Fragment key={`${rgn.key}|${ut.usageType}`}>
                                      <tr className="inv-tree-row--usage">
                                        <td className="inv-tree-cell">{ut.usageType}</td>
                                        <td className="hidden md:table-cell" />
                                        <td className="is-num inv-qty-cell" />
                                        <td className="is-num inv-num">{usd(ut.total)}</td>
                                        <td className="inv-rate-col">{pricingCell(utScope)}</td>
                                      </tr>
                                      {pricingEditorRow(utScope, `${rgn.key}|${ut.usageType}`)}
                                      {ut.lines.map(line => {
                                        const lnScope = lineScopeOf(svc.service, line);
                                        return (
                                        <React.Fragment key={line.id}>
                                        <tr className="inv-tree-row--line">
                                          <td className="inv-tree-cell">
                                            {line.resourceName || '—'}
                                            <PricedBadge line={line} />
                                            <span className="inv-inline-qty inv-num">
                                              {formatQuantity(line.usageQuantity)} {line.unit}
                                            </span>
                                          </td>
                                          <td className="hidden md:table-cell" />
                                          <td className="is-num inv-num whitespace-nowrap inv-qty-cell">
                                            {formatQuantity(line.usageQuantity)}
                                            {line.unit && <span className="inv-muted ml-1">{line.unit}</span>}
                                          </td>
                                          <td className="is-num inv-num whitespace-nowrap">{usd(line.cost)}</td>
                                          <td className="inv-rate-col">{linePricingCell(lnScope, line)}</td>
                                        </tr>
                                        {pricingEditorRow(lnScope, `line|${line.id}`)}
                                        </React.Fragment>
                                        );
                                      })}
                                    </React.Fragment>
                                    );
                                  })}

                                  {rgnOpen && rgn.flat.map(line => {
                                    const lnScope = lineScopeOf(svc.service, line);
                                    return (
                                    <React.Fragment key={line.id}>
                                    <tr className="inv-tree-row--line is-flat">
                                      <td className="inv-tree-cell">
                                        {line.resourceName || '—'}
                                        <PricedBadge line={line} />
                                        <span className="inv-inline-qty inv-num">
                                          {formatQuantity(line.usageQuantity)} {line.unit && line.unit !== 'N/A' ? line.unit : ''}
                                        </span>
                                      </td>
                                      <td className="hidden md:table-cell" />
                                      <td className="is-num inv-num whitespace-nowrap inv-qty-cell">
                                        {formatQuantity(line.usageQuantity)}
                                        {line.unit && line.unit !== 'N/A' && <span className="inv-muted ml-1">{line.unit}</span>}
                                      </td>
                                      <td className="is-num inv-num whitespace-nowrap">{usd(line.cost)}</td>
                                      <td className="inv-rate-col">{linePricingCell(lnScope, line)}</td>
                                    </tr>
                                    {pricingEditorRow(lnScope, `line|${line.id}`)}
                                    </React.Fragment>
                                    );
                                  })}
                                </React.Fragment>
                              );
                            })}
                          </React.Fragment>
                        );
                      })}
                    </tbody>
                    {!q && (
                      <tfoot>
                        <tr>
                          <td className="font-bold" style={{ borderTop: '1px solid var(--inv-border)', padding: '12px 16px' }}>
                            Total charges
                          </td>
                          <td className="hidden md:table-cell" style={{ borderTop: '1px solid var(--inv-border)' }} />
                          <td className="inv-qty-cell" style={{ borderTop: '1px solid var(--inv-border)' }} />
                          <td className="is-num inv-num font-bold" style={{ borderTop: '1px solid var(--inv-border)', padding: '12px 16px' }}>
                            {usd(chargesTotal)}
                          </td>
                          <td className="inv-rate-col" style={{ borderTop: '1px solid var(--inv-border)' }} />
                        </tr>
                      </tfoot>
                    )}
                  </table>
                  <p className="px-5 py-3 text-[11.5px] inv-muted leading-relaxed" style={{ borderTop: '1px solid var(--inv-divider)' }}>
                    Negative amounts are credits, or usage covered by a Savings Plan or Reserved Instance.
                    {invoice.dataSource === 'CUR' && ' Built from the same Cost and Usage Report lines as the cost page’s “Charges by service”.'}
                    {isDraft
                      ? ' Set a rate on a service, region, usage type or single charge to bill it at usage quantity × your'
                        + ' rate instead of the provider’s cost. Credits and Savings Plan lines are never'
                        + ' repriced, and removing a rate restores the provider’s figures exactly.'
                      : ' This invoice is finalized, so its rates can no longer be changed.'}
                  </p>
                </div>
              )}
            </div>
          )}

          {tab === 'discounts' && (
            <div role="tabpanel" aria-label="Discounts" className="inv-card__body space-y-4">
              {discountError && (
                <div className="inv-alert inv-alert--warning" role="alert">
                  <i className="fas fa-exclamation-triangle" aria-hidden="true" />
                  <p className="flex-1">{discountError}</p>
                  <button type="button" className="inv-icon-btn" onClick={() => setDiscountError(null)} aria-label="Dismiss">
                    <i className="fas fa-times" aria-hidden="true" />
                  </button>
                </div>
              )}

              {isDraft && (
                <div className="grid grid-cols-1 md:grid-cols-[1fr_160px_auto] gap-3 items-end">
                  <div>
                    <label className="inv-field-label" htmlFor="discount-scope">Apply to</label>
                    <select
                      id="discount-scope"
                      className="inv-select"
                      value={discountService}
                      onChange={e => setDiscountService(e.target.value)}
                    >
                      <option value="">Choose what to discount…</option>
                      <option value="ALL">Entire invoice</option>
                      {serviceNames.map(name => (
                        <option key={name} value={name}>
                          {name}{shieldedServices.has(name) ? ' — at private rates' : ''}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="inv-field-label" htmlFor="discount-pct">Discount (%)</label>
                    <input
                      id="discount-pct"
                      type="number"
                      className="inv-input inv-num"
                      value={discountPct}
                      onChange={e => setDiscountPct(e.target.value)}
                      placeholder="e.g. 10"
                      min="0.01"
                      max="100"
                      step="0.01"
                    />
                  </div>
                  <button
                    type="button"
                    className="inv-btn inv-btn--primary"
                    onClick={applyDiscount}
                    disabled={applyingDiscount || !discountService || !discountPct}
                  >
                    {applyingDiscount ? <><i className="fas fa-spinner fa-spin" aria-hidden="true" /> Applying…</> : 'Apply discount'}
                  </button>
                </div>
              )}

              {invoice.discounts?.length ? (
                <div className="inv-card overflow-hidden" style={{ boxShadow: 'none' }}>
                  <table className="inv-table">
                    <thead>
                      <tr>
                        <th>Applies to</th>
                        <th className="is-num">Discount</th>
                        <th>Note</th>
                        {isDraft && <th className="is-num"><span className="sr-only">Remove</span></th>}
                      </tr>
                    </thead>
                    <tbody>
                      {invoice.discounts.map(d => (
                        <tr key={d.id}>
                          <td className="font-semibold">{d.serviceName === 'ALL' ? 'Entire invoice' : d.serviceName}</td>
                          <td className="is-num inv-num">{d.percentage}%</td>
                          <td className="inv-ink-2">{d.description || '—'}</td>
                          {isDraft && (
                            <td className="is-num">
                              <button
                                type="button"
                                className="inv-icon-btn inv-icon-btn--danger"
                                onClick={() => removeDiscount(d.id)}
                                aria-label={`Remove discount on ${d.serviceName}`}
                                title="Remove discount"
                              >
                                <i className="fas fa-times" aria-hidden="true" />
                              </button>
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-[13px] inv-muted">No discounts applied.</p>
              )}

              {/* Named once here, where the admin is choosing a discount, rather than left
                  to be discovered in the total afterwards. */}
              {(invoice.discountShieldedTotal ?? 0) > 0 && (
                <div className="inv-alert inv-alert--info" role="note">
                  <i className="fas fa-shield-alt" aria-hidden="true" />
                  <div className="flex-1">
                    <p className="inv-alert__title">
                      {usd(invoice.discountShieldedTotal)} is excluded from the overall discount
                    </p>
                    <p>
                      {[...shieldedServices].join(', ')} {shieldedServices.size === 1 ? 'is' : 'are'} billed
                      at private rates that already carry their margin, so an invoice-wide or
                      consumption-wide discount skips those charges. Discounting one of those services
                      by name still works, and the &ldquo;No discount&rdquo; checkbox beside the rate in
                      Charges by service lifts the exclusion.
                    </p>
                  </div>
                </div>
              )}

              {discountService !== '' && discountService !== 'ALL' && shieldedServices.has(discountService) && (
                <div className="inv-alert inv-alert--warning" role="note">
                  <i className="fas fa-exclamation-triangle" aria-hidden="true" />
                  <p className="flex-1">
                    {discountService} is billed at a private rate that already carries its margin.
                    Discounting it by name will reduce that rate.
                  </p>
                </div>
              )}

              <p className="text-[12px] inv-muted">
                AWS Marketplace charges are never discounted.
                {!isDraft && ' This invoice is finalized, so its discounts can no longer be changed.'}
              </p>
            </div>
          )}
        </section>
      </main>
    </div>
  );
};

export default BillOpsAdminInvoiceDetailPage;
