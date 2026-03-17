/**
 * BillOps Admin Invoice Detail Page
 *
 * Admin interface for managing a single invoice:
 * - View full invoice (line items grouped by service, discounts, totals)
 * - Apply / remove discounts  (DRAFT only)
 * - Finalize invoice           (DRAFT only)
 * - Download PDF preview
 *
 * Route: /billops-admin-invoice-detail?id=<invoiceId>
 */

import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { apiFetch } from '../utils/apiClient';
import '../styles/billopsAdminInvoices.css';

// ─── Types ────────────────────────────────────────────────────────────────────

interface LineItemDto {
  id: number;
  serviceName: string;
  regionName: string;
  resourceName: string;
  usageQuantity: string;
  unit: string;
  cost: number;
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
  lineItems: LineItemDto[];
  discounts: DiscountDto[];
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

const fmt = (v: number | null | undefined): string =>
  new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(v ?? 0);

const getAccountId = (inv: InvoiceDetail): string => {
  if (inv.awsAccountId && inv.awsAccountId !== 'N/A') return inv.awsAccountId;
  if (inv.gcpProjectId && inv.gcpProjectId !== 'N/A') return inv.gcpProjectId;
  return inv.azureSubscriptionId || '—';
};

// ─── Component ────────────────────────────────────────────────────────────────

const BillOpsAdminInvoiceDetailPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const invoiceId = searchParams.get('id');

  const [invoice, setInvoice] = useState<InvoiceDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Discount form
  const [discountService, setDiscountService] = useState('');
  const [discountPct, setDiscountPct] = useState('');
  const [applyingDiscount, setApplyingDiscount] = useState(false);
  const [discountError, setDiscountError] = useState<string | null>(null);

  // Finalize
  const [finalizing, setFinalizing] = useState(false);
  const [confirmFinalize, setConfirmFinalize] = useState(false);

  // Expanded service / region groups
  const [expandedServices, setExpandedServices] = useState<Set<string>>(new Set());
  const [expandedRegions, setExpandedRegions] = useState<Set<string>>(new Set());

  // Live USD→INR exchange rate
  const [usdRate, setUsdRate] = useState<number | null>(null);
  const [rateDate, setRateDate] = useState<string | null>(null);
  const [rateLoading, setRateLoading] = useState(true);

  // ── Fetch invoice ──────────────────────────────────────────────────────────

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
        throw new Error(`Server error: ${res.status}`);
      }
      const data: InvoiceDetail = await res.json();
      setInvoice(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load invoice.');
    } finally {
      setLoading(false);
    }
  }, [invoiceId, navigate]);

  useEffect(() => {
    fetchInvoice();
  }, [fetchInvoice]);

  // ── Fetch live USD→INR rate (fawazahmed0/exchange-api) ────────────────────
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
            setRateLoading(false);
            return;
          }
        } catch {
          // try next endpoint
        }
      }
      setRateLoading(false); // all failed
    })();
  }, []);

  // ── Group line items by service ───────────────────────────────────────────

  const serviceGroups = useMemo(() => {
    if (!invoice?.lineItems) return [];
    const svcMap = new Map<string, { total: number; regionMap: Map<string, { regionTotal: number; items: LineItemDto[] }> }>();
    for (const item of invoice.lineItems) {
      const svcKey = item.serviceName || 'Other';
      if (!svcMap.has(svcKey)) svcMap.set(svcKey, { total: 0, regionMap: new Map() });
      const svcGroup = svcMap.get(svcKey)!;
      svcGroup.total += item.cost ?? 0;
      const regionKey = item.regionName || 'Global';
      if (!svcGroup.regionMap.has(regionKey)) svcGroup.regionMap.set(regionKey, { regionTotal: 0, items: [] });
      const rGroup = svcGroup.regionMap.get(regionKey)!;
      rGroup.regionTotal += item.cost ?? 0;
      rGroup.items.push(item);
    }
    return Array.from(svcMap.entries())
      .map(([service, { total, regionMap }]) => ({
        service,
        total,
        regions: Array.from(regionMap.entries())
          .map(([regionName, { regionTotal, items }]) => ({ regionName, regionTotal, items }))
          .sort((a, b) => b.regionTotal - a.regionTotal),
      }))
      .sort((a, b) => b.total - a.total);
  }, [invoice]);

  const serviceNames = useMemo(() => {
    if (!invoice?.lineItems) return [];
    return Array.from(new Set(invoice.lineItems.map(i => i.serviceName || 'Other'))).sort();
  }, [invoice]);

  const toggleService = (service: string) => {
    setExpandedServices(prev => {
      const next = new Set(prev);
      if (next.has(service)) next.delete(service);
      else next.add(service);
      return next;
    });
  };

  const toggleRegion = (service: string, region: string) => {
    const key = `${service}-${region}`;
    setExpandedRegions(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  // ── Apply discount ────────────────────────────────────────────────────────

  const applyDiscount = useCallback(async () => {
    if (!invoice || !discountService.trim() || !discountPct.trim()) {
      setDiscountError('Please enter service name and percentage.');
      return;
    }
    const pct = parseFloat(discountPct);
    if (isNaN(pct) || pct <= 0 || pct > 100) {
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
      const updated: InvoiceDetail = await res.json();
      setInvoice(updated);
      setDiscountService('');
      setDiscountPct('');
    } catch (err) {
      setDiscountError(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setApplyingDiscount(false);
    }
  }, [invoice, discountService, discountPct]);

  // ── Remove discount ────────────────────────────────────────────────────────

  const removeDiscount = useCallback(async (discountId: number) => {
    if (!invoice) return;
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/discounts/${discountId}`, {
        method: 'DELETE',
        credentials: 'include',
      });
      if (!res.ok) throw new Error('Failed to remove discount.');
      const updated: InvoiceDetail = await res.json();
      setInvoice(updated);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to remove discount.');
    }
  }, [invoice]);

  // ── Finalize ───────────────────────────────────────────────────────────────

  const finalizeInvoice = useCallback(async () => {
    if (!invoice) return;
    setFinalizing(true);
    setConfirmFinalize(false);
    try {
      const res = await apiFetch(`/api/admin/invoices/${invoice.id}/finalize`, {
        method: 'POST',
        credentials: 'include',
      });
      if (!res.ok) throw new Error('Failed to finalize invoice.');
      const updated: InvoiceDetail = await res.json();
      setInvoice(updated);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to finalize invoice.');
    } finally {
      setFinalizing(false);
    }
  }, [invoice]);

  // ── Download PDF ───────────────────────────────────────────────────────────

  const downloadPdf = useCallback(async () => {
    if (!invoice) return;
    try {
      const detailRes = await apiFetch(`/api/admin/invoices/${invoice.id}`, { credentials: 'include' });
      if (!detailRes.ok) throw new Error('Failed to fetch invoice.');
      const fullDto = await detailRes.json();

      const pdfRes = await apiFetch('/api/billops/invoices/download-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(fullDto),
      });
      if (!pdfRes.ok) throw new Error('PDF generation failed.');

      const blob = await pdfRes.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.style.display = 'none';
      a.href = url;
      a.download = `preview-${invoice.invoiceNumber}.pdf`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Download failed.');
    }
  }, [invoice]);

  // ── Render ─────────────────────────────────────────────────────────────────

  if (!invoiceId) {
    return (
      <div className="admin-invoices-page flex-1 flex items-center justify-center">
        <p className="text-red-500 font-medium">No invoice ID provided.</p>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="admin-invoices-page flex-1 flex flex-col h-full overflow-hidden bg-transparent">
        <div className="p-8 space-y-4">
          <div className="h-8 invoice-skeleton-pulse w-48 rounded-lg" />
          <div className="h-40 invoice-skeleton-pulse w-full rounded-xl" />
          <div className="h-64 invoice-skeleton-pulse w-full rounded-xl" />
        </div>
      </div>
    );
  }

  if (error || !invoice) {
    return (
      <div className="admin-invoices-page flex-1 flex flex-col h-full overflow-hidden bg-transparent">
        <div className="p-8">
          <div className="invoice-alert invoice-alert-error">
            <div className="invoice-alert-icon"><i className="fas fa-times-circle" /></div>
            <div>
              <p className="text-sm text-red-700 font-bold">Error loading invoice</p>
              <p className="text-sm text-red-600">{error}</p>
              <button onClick={() => navigate('/billops-admin-invoices')} className="mt-2 text-sm font-medium text-red-800 hover:underline">
                ← Back to Invoices
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const isDraft = invoice.status === 'DRAFT';
  const accountId = getAccountId(invoice);

  return (
    <div className="admin-invoices-page flex-1 flex flex-col h-full overflow-hidden bg-transparent">
      <main className="flex-1 overflow-y-auto p-8 space-y-6 admin-invoices-scrollbar">

        {/* ── Header Bar ────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div className="flex items-center gap-4">
            <button
              onClick={() => navigate('/billops-admin-invoices')}
              className="flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-indigo-600 transition-colors"
            >
              <i className="fas fa-arrow-left" />
              Back to Invoices
            </button>
            <div className="h-5 w-px bg-slate-200" />
            <h2 className="text-xl font-bold text-slate-800">{invoice.invoiceNumber}</h2>
            <span className={`invoice-status-badge ${
              invoice.status === 'FINALIZED' ? 'invoice-status-finalized' :
              invoice.status === 'DRAFT' ? 'invoice-status-draft' :
              'invoice-status-void'
            }`}>
              <i className={`fas ${invoice.status === 'FINALIZED' ? 'fa-check' : invoice.status === 'VOID' ? 'fa-ban' : 'fa-pencil-alt'}`} />
              {invoice.status}
            </span>
          </div>

          <div className="flex items-center gap-3">
            <button onClick={downloadPdf} className="invoice-action-btn flex items-center gap-2 px-4 py-2 text-sm font-medium" title="Download PDF Preview">
              <i className="fas fa-file-pdf" />
              Download PDF
            </button>
            {isDraft && (
              <button
                onClick={() => setConfirmFinalize(true)}
                disabled={finalizing}
                className="invoice-generate-btn flex items-center gap-2 px-5 py-2 text-sm"
              >
                {finalizing ? <><i className="fas fa-spinner fa-spin" /> Finalizing…</> : <><i className="fas fa-check-circle" /> Finalize Invoice</>}
              </button>
            )}
          </div>
        </div>

        {/* ── Invoice Info Card ──────────────────────────────────────────── */}
        <div className="invoice-glass-card p-6">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Account</p>
              <p className="text-sm font-semibold text-slate-800">{invoice.accountName}</p>
              <p className="text-xs text-slate-500">{accountId}</p>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Client</p>
              <p className="text-sm font-semibold text-slate-800">{invoice.clientName || '—'}</p>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Billing Period</p>
              <p className="text-sm font-semibold text-slate-800">{invoice.billingPeriod}</p>
            </div>
            <div>
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Invoice Date</p>
              <p className="text-sm font-semibold text-slate-800">{invoice.invoiceDate || '—'}</p>
            </div>
          </div>
        </div>

        {/* ── Financial Summary ─────────────────────────────────────────── */}
        <div className="invoice-glass-card p-6">
          <div className="flex items-center justify-between flex-wrap gap-3 mb-4">
            <h3 className="card-title">
              <i className="fas fa-calculator" />
              Financial Summary
            </h3>
            {/* Live exchange rate badge */}
            <div className={`flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-semibold border ${
              rateLoading
                ? 'bg-slate-50 border-slate-200 text-slate-400'
                : usdRate
                ? 'bg-blue-50 border-blue-200 text-blue-700'
                : 'bg-amber-50 border-amber-200 text-amber-600'
            }`}>
              <i className={`fas ${rateLoading ? 'fa-spinner fa-spin' : usdRate ? 'fa-exchange-alt' : 'fa-exclamation-triangle'}`} />
              {rateLoading
                ? 'Fetching rate…'
                : usdRate
                ? <>1 USD&nbsp;=&nbsp;<span className="font-bold">₹{usdRate.toFixed(2)}</span>{rateDate && <span className="ml-1.5 font-normal text-blue-500">({rateDate})</span>}</>
                : 'Rate unavailable'}
            </div>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
            <div className="text-center p-4 bg-slate-50 rounded-xl">
              <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-1">Pre-Discount</p>
              <p className="text-lg font-bold text-slate-700">{fmt(invoice.preDiscountTotal)}</p>
            </div>
            <div className="text-center p-4 bg-green-50 rounded-xl">
              <p className="text-xs font-semibold text-green-500 uppercase tracking-wide mb-1">Discount</p>
              <p className="text-lg font-bold text-green-600">−{fmt(invoice.discountAmount)}</p>
            </div>
            <div className="text-center p-4 bg-amber-50 rounded-xl">
              <p className="text-xs font-semibold text-amber-500 uppercase tracking-wide mb-1">Tax</p>
              <p className="text-lg font-bold text-amber-600">+{fmt(invoice.taxAmount)}</p>
            </div>
            <div className="text-center p-4 bg-indigo-50 rounded-xl border-2 border-indigo-200">
              <p className="text-xs font-semibold text-indigo-500 uppercase tracking-wide mb-1">Total Due</p>
              <p className="text-xl font-extrabold text-indigo-700">{fmt(invoice.amount)}</p>
            </div>
          </div>
        </div>

        {/* ── Applied Discounts ─────────────────────────────────────────── */}
        {invoice.discounts && invoice.discounts.length > 0 && (
          <div className="invoice-glass-card p-6">
            <h3 className="card-title mb-4">
              <i className="fas fa-tag" />
              Applied Discounts
            </h3>
            <div className="space-y-2">
              {invoice.discounts.map(d => (
                <div key={d.id} className="flex items-center justify-between p-3 bg-green-50 rounded-lg border border-green-100">
                  <div>
                    <span className="text-sm font-semibold text-green-800">{d.serviceName}</span>
                    <span className="ml-3 text-sm text-green-600">{d.percentage}% off</span>
                    {d.description && <span className="ml-2 text-xs text-green-500">— {d.description}</span>}
                  </div>
                  {isDraft && (
                    <button
                      onClick={() => removeDiscount(d.id)}
                      className="text-red-400 hover:text-red-600 transition-colors p-1"
                      title="Remove discount"
                    >
                      <i className="fas fa-times" />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* ── Apply Discount Form (DRAFT only) ─────────────────────────── */}
        {isDraft && (
          <div className="invoice-generate-card p-6">
            <h3 className="card-title mb-4">
              <i className="fas fa-percent" />
              Apply Discount
            </h3>
            {discountError && (
              <div className="invoice-alert invoice-alert-warning mb-4">
                <div className="invoice-alert-icon"><i className="fas fa-exclamation-triangle" /></div>
                <p className="text-sm text-yellow-700">{discountError}</p>
              </div>
            )}
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4 items-end">
              <div>
                <label className="invoice-form-label">Service Name</label>
                <select
                  value={discountService}
                  onChange={e => setDiscountService(e.target.value)}
                  className="invoice-form-input"
                >
                  <option value="">Select a service…</option>
                  <option value="ALL">ALL (entire invoice)</option>
                  {serviceNames.map(name => (
                    <option key={name} value={name}>{name}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="invoice-form-label">Discount %</label>
                <input
                  type="number"
                  value={discountPct}
                  onChange={e => setDiscountPct(e.target.value)}
                  placeholder="e.g. 10"
                  min="0.01"
                  max="100"
                  step="0.01"
                  className="invoice-form-input"
                />
              </div>
              <button
                onClick={applyDiscount}
                disabled={applyingDiscount || !discountService.trim() || !discountPct.trim()}
                className="invoice-generate-btn"
              >
                {applyingDiscount ? <><i className="fas fa-spinner fa-spin" /> Applying…</> : 'Apply Discount'}
              </button>
            </div>
          </div>
        )}

        {/* ── Line Items ────────────────────────────────────────────────── */}
        <div className="invoice-glass-card p-6">
          <h3 className="card-title mb-4">
            <i className="fas fa-list-ul" />
            Line Items
            <span className="ml-2 text-xs font-normal text-slate-400">({invoice.lineItems?.length ?? 0} items)</span>
          </h3>

          {serviceGroups.length === 0 ? (
            <div className="invoice-empty-state">
              <i className="fas fa-inbox" />
              <p>No line items found.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="w-8 px-2 py-3" />
                    <th className="px-4 py-3 text-left text-xs font-semibold text-slate-500 uppercase tracking-wide">Service / Resource</th>
                    <th className="px-4 py-3 text-right text-xs font-semibold text-slate-500 uppercase tracking-wide">Usage Quantity</th>
                    <th className="px-4 py-3 text-right text-xs font-semibold text-slate-500 uppercase tracking-wide">Amount (USD)</th>
                  </tr>
                </thead>
                <tbody>
                  {serviceGroups.map(({ service, total, regions }) => (
                    <React.Fragment key={service}>
                      {/* Service row */}
                      <tr
                        onClick={() => toggleService(service)}
                        className={`cursor-pointer transition-colors hover:bg-gray-50/80 border-b border-slate-50 ${expandedServices.has(service) ? 'bg-indigo-50/30' : ''}`}
                      >
                        <td className="px-2 py-4 text-center text-gray-400">
                          <i className={`fas fa-chevron-right text-xs transition-transform duration-200 ${expandedServices.has(service) ? 'rotate-90 text-indigo-500' : ''}`} />
                        </td>
                        <td className="px-4 py-4">
                          <div className="flex items-center">
                            <div className={`w-8 h-8 rounded-lg flex items-center justify-center mr-3 transition-colors ${expandedServices.has(service) ? 'bg-indigo-100 text-indigo-600' : 'bg-gray-100 text-gray-500'}`}>
                              <i className="fas fa-cube text-xs" />
                            </div>
                            <span className="font-semibold text-gray-900">{service}</span>
                          </div>
                        </td>
                        <td className="px-4 py-4 text-right text-slate-400 text-xs">—</td>
                        <td className="px-4 py-4 text-right font-bold text-slate-700">{fmt(total)}</td>
                      </tr>

                      {/* Region rows */}
                      {expandedServices.has(service) && regions.map(({ regionName, regionTotal, items }) => (
                        <React.Fragment key={`${service}-${regionName}`}>
                          <tr
                            onClick={() => toggleRegion(service, regionName)}
                            className={`cursor-pointer transition-colors hover:bg-gray-50 border-b border-slate-50 ${expandedRegions.has(`${service}-${regionName}`) ? 'bg-gray-50' : ''}`}
                          >
                            <td className="px-2 py-3 text-center text-gray-300">
                              <i className={`fas fa-chevron-right text-xs transition-transform duration-200 ml-2 ${expandedRegions.has(`${service}-${regionName}`) ? 'rotate-90 text-gray-500' : ''}`} />
                            </td>
                            <td className="px-4 py-3">
                              <div className="flex items-center pl-10">
                                <i className="fas fa-globe-americas text-gray-400 mr-2 text-xs" />
                                <span className="text-gray-700 font-medium text-sm">{regionName}</span>
                              </div>
                            </td>
                            <td className="px-4 py-3 text-right text-slate-400 text-xs">—</td>
                            <td className="px-4 py-3 text-right font-semibold text-slate-600 text-sm">{fmt(regionTotal)}</td>
                          </tr>

                          {/* Resource rows */}
                          {expandedRegions.has(`${service}-${regionName}`) && items.map((item, idx) => (
                            <tr key={`${item.id}-${idx}`} className="hover:bg-gray-50/50 transition-colors border-b border-slate-50">
                              <td className="px-2 py-2" />
                              <td className="px-4 py-2">
                                <div className="pl-16 flex items-center">
                                  <div className="w-1.5 h-1.5 bg-gray-300 rounded-full mr-3 flex-shrink-0" />
                                  <span className="text-gray-600 text-xs font-mono truncate max-w-md" title={item.resourceName}>
                                    {item.resourceName || '—'}
                                  </span>
                                </div>
                              </td>
                              <td className="px-4 py-2 text-right text-gray-500 text-xs font-mono">
                                {item.usageQuantity != null ? `${Number(item.usageQuantity).toFixed(3)} ${item.unit || ''}`.trim() : 'N/A'}
                              </td>
                              <td className="px-4 py-2 text-right text-gray-600 text-sm font-medium">{fmt(item.cost)}</td>
                            </tr>
                          ))}
                        </React.Fragment>
                      ))}
                    </React.Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* ── Finalize Confirmation ─────────────────────────────────────── */}
        {confirmFinalize && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
            <div className="bg-white rounded-2xl shadow-2xl p-8 max-w-sm w-full mx-4">
              <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-amber-100 rounded-full">
                <i className="fas fa-exclamation-triangle text-amber-500 text-2xl" />
              </div>
              <h2 className="text-xl font-bold text-center text-slate-800 mb-2">Finalize Invoice?</h2>
              <p className="text-sm text-slate-500 text-center mb-6">
                This will mark <span className="font-semibold text-slate-700">{invoice.invoiceNumber}</span> as finalized and send it to the client. This action cannot be undone.
              </p>
              <div className="flex gap-3">
                <button
                  onClick={() => setConfirmFinalize(false)}
                  className="flex-1 px-4 py-2 text-sm font-medium text-slate-600 bg-slate-100 hover:bg-slate-200 rounded-xl transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={finalizeInvoice}
                  className="flex-1 px-4 py-2 text-sm font-medium text-white bg-indigo-600 hover:bg-indigo-700 rounded-xl transition-colors"
                >
                  Yes, Finalize
                </button>
              </div>
            </div>
          </div>
        )}

      </main>
    </div>
  );
};

export default BillOpsAdminInvoiceDetailPage;
