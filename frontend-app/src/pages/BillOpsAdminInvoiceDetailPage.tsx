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
}

interface DiscountDto {
  id: number;
  serviceName: string;
  percentage: number;
  description: string;
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
  includeIrn?: boolean;
  lineItems: LineItemDto[];
  discounts: DiscountDto[];
  dataSource?: 'CUR' | 'COST_EXPLORER' | null;
  provisional?: boolean;
}

interface UsageTypeNode { usageType: string; total: number; lines: LineItemDto[] }
interface RegionNode { key: string; region: string; total: number; usageTypes: UsageTypeNode[]; flat: LineItemDto[] }
interface ServiceNode { service: string; total: number; regions: RegionNode[]; lineCount: number }

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

// ─── Charges tree ────────────────────────────────────────────────────────────

/**
 * Service → region → usage type → line, the breakdown of the AWS bill and of the cost
 * page's "Charges by service". Lines without a usage type (Cost Explorer invoices,
 * repriced CloudFront) skip that level and sit straight under their region.
 */
const buildTree = (lines: LineItemDto[]): ServiceNode[] => {
  const services = new Map<string, Map<string, { byUsage: Map<string, LineItemDto[]>; flat: LineItemDto[] }>>();
  for (const line of lines) {
    const svc = line.serviceName || 'Other';
    const rgn = line.regionName || 'Global';
    const regions = services.get(svc) ?? new Map();
    const bucket = regions.get(rgn) ?? { byUsage: new Map<string, LineItemDto[]>(), flat: [] as LineItemDto[] };
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
        .map(([region, { byUsage, flat }]) => {
          const usageTypes = [...byUsage.entries()]
            .map(([usageType, ls]) => ({ usageType, total: sum(ls), lines: [...ls].sort(byCost) }))
            .sort((a, b) => b.total - a.total);
          return {
            key: `${service}|${region}`,
            region,
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

  const [finalizing, setFinalizing] = useState(false);
  const [confirmFinalize, setConfirmFinalize] = useState(false);
  const [downloading, setDownloading] = useState<'pdf' | 'detail' | null>(null);

  const [usdRate, setUsdRate] = useState<number | null>(null);
  const [rateDate, setRateDate] = useState<string | null>(null);

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

  const finalizeInvoice = useCallback(async () => {
    if (!invoice) return;
    setConfirmFinalize(false);
    setFinalizing(true);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/finalize`, {
        method: 'POST',
        credentials: 'include',
      });
      if (!res.ok) throw new Error('Failed to finalize invoice.');
      setInvoice(await res.json());
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to finalize invoice.');
    } finally {
      setFinalizing(false);
    }
  }, [invoice]);

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
  const taxable = (invoice.preDiscountTotal ?? 0) - (invoice.discountAmount ?? 0);

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <AlertDialog
        isOpen={confirmFinalize}
        title="Finalize this invoice?"
        description={`${invoice.invoiceNumber} will be marked finalized and sent to the client. Discounts can no longer be changed, and this can't be undone.`}
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
                onClick={() => setConfirmFinalize(true)}
                disabled={finalizing}
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
                {usdRate
                  ? <>≈ {inr((invoice.amount ?? 0) * usdRate)} at ₹{usdRate.toFixed(2)} per USD{rateDate ? ` (${rateDate})` : ''}</>
                  : 'USD, including tax'}
              </p>

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
                  <dd>
                    {invoice.dataSource === 'CUR'
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
                            </tr>

                            {svcOpen && svc.regions.map(rgn => {
                              const rgnOpen = isRegionOpen(rgn.key);
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
                                  </tr>

                                  {rgnOpen && rgn.usageTypes.map(ut => (
                                    <React.Fragment key={`${rgn.key}|${ut.usageType}`}>
                                      <tr className="inv-tree-row--usage">
                                        <td className="inv-tree-cell">{ut.usageType}</td>
                                        <td className="hidden md:table-cell" />
                                        <td className="is-num inv-qty-cell" />
                                        <td className="is-num inv-num">{usd(ut.total)}</td>
                                      </tr>
                                      {ut.lines.map(line => (
                                        <tr key={line.id} className="inv-tree-row--line">
                                          <td className="inv-tree-cell">
                                            {line.resourceName || '—'}
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
                                        </tr>
                                      ))}
                                    </React.Fragment>
                                  ))}

                                  {rgnOpen && rgn.flat.map(line => (
                                    <tr key={line.id} className="inv-tree-row--line is-flat">
                                      <td className="inv-tree-cell">
                                        {line.resourceName || '—'}
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
                                    </tr>
                                  ))}
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
                        </tr>
                      </tfoot>
                    )}
                  </table>
                  <p className="px-5 py-3 text-[11.5px] inv-muted leading-relaxed" style={{ borderTop: '1px solid var(--inv-divider)' }}>
                    Negative amounts are credits, or usage covered by a Savings Plan or Reserved Instance.
                    {invoice.dataSource === 'CUR' && ' Built from the same Cost and Usage Report lines as the cost page’s “Charges by service”.'}
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
                      {serviceNames.map(name => <option key={name} value={name}>{name}</option>)}
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
