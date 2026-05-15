/**
 * MarketOps — Subscribed Marketplace Products & Meter Billing
 *
 * Shows all marketplace product subscriptions for the currently selected
 * cloud account, with detailed meter billing per product:
 *   - Monthly tab: 12-month bar chart + full billing table
 *   - Daily tab:   per-day bar chart for a selected month + non-zero rows table
 */

import React, { useState, useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, Cell,
} from 'recharts';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbarActions } from '../contexts/TopbarContext';
import {
  useSubscriptions,
  useMeterBilling,
  useDailyMeterBilling,
  useCatalogBrowse,
  useCatalogFacets,
  useProductDetail,
  useCancellationsList,
  submitCreditRequest,
  triggerSync,
  cancelSubscription,
  updatePoNumber,
} from '../hooks/useMarketOps';
import { useBuyerUsage } from '../hooks/useIsv';
import { BuyerSubscription } from '../types/isv';
import { MarketplaceSubscription, DailyMeterBilling, CatalogProduct } from '../types/marketops';
import IsvMeteringTab from './IsvMeteringPage';
import { useTabState } from '../hooks/useTabState';

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatUsd(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value);
}

function formatMonthLabel(yearMonth: string): string {
  const [year, month] = yearMonth.split('-');
  const names = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${names[parseInt(month, 10) - 1]} '${year.slice(2)}`;
}

function formatDayLabel(date: string): string {
  return String(parseInt(date.split('-')[2], 10));
}

function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
}

function daysUntil(dateStr: string | null | undefined): number | null {
  if (!dateStr) return null;
  const diff = new Date(dateStr).getTime() - Date.now();
  return Math.ceil(diff / (1000 * 60 * 60 * 24));
}

function offerTypeLabel(offerType: string | null | undefined): string {
  if (!offerType) return '—';
  const map: Record<string, string> = {
    PUBLIC: 'Public',
    PRIVATE: 'Private',
    ENTERPRISE_CONTRACT: 'Enterprise Contract',
  };
  return map[offerType] ?? offerType;
}

function paymentScheduleLabel(schedule: string | null | undefined): string {
  if (!schedule) return '—';
  const map: Record<string, string> = {
    FIXED_UPFRONT: 'Fixed Upfront',
    RECURRING: 'Recurring',
    PAYMENT_SCHEDULE: 'Payment Schedule',
    FREE_TRIAL: 'Free Trial',
    PAY_AS_YOU_GO: 'Pay-as-you-go',
    BYOL: 'BYOL',
    CONFIGURABLE_UPFRONT: 'Configurable Upfront',
  };
  return map[schedule] ?? schedule;
}

// ─── Chart Tooltip ────────────────────────────────────────────────────────────

const ChartTooltip = ({ active, payload, label }: any) => {
  if (!active || !payload?.length) return null;
  return (
    <div className="bg-white border border-gray-100 rounded-xl shadow-lg px-3 py-2 text-xs pointer-events-none">
      <p className="text-gray-400 mb-1">{label}</p>
      <p className="font-bold text-indigo-700">{formatUsd(Number(payload[0]?.value ?? 0))}</p>
    </div>
  );
};

// ─── Monthly Bar Chart ────────────────────────────────────────────────────────

const MonthlyBarChart: React.FC<{ data: { label: string; costUsd: number }[] }> = ({ data }) => (
  <ResponsiveContainer width="100%" height={210}>
    <BarChart data={data} margin={{ top: 6, right: 8, left: 0, bottom: 0 }} barCategoryGap="32%">
      <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" vertical={false} />
      <XAxis
        dataKey="label"
        tick={{ fontSize: 11, fill: '#9ca3af' }}
        axisLine={false}
        tickLine={false}
      />
      <YAxis
        tickFormatter={v => `$${v}`}
        tick={{ fontSize: 10, fill: '#9ca3af' }}
        axisLine={false}
        tickLine={false}
        width={50}
      />
      <Tooltip content={<ChartTooltip />} cursor={{ fill: 'rgba(99,102,241,0.07)' }} />
      <Bar dataKey="costUsd" radius={[5, 5, 0, 0]}>
        {data.map((_, i) => (
          <Cell
            key={i}
            fill={i === data.length - 1 ? '#6366f1' : '#a5b4fc'}
          />
        ))}
      </Bar>
    </BarChart>
  </ResponsiveContainer>
);

// ─── Daily Bar Chart ──────────────────────────────────────────────────────────

const DailyBarChart: React.FC<{ data: DailyMeterBilling[] }> = ({ data }) => {
  const chartData = data.map(row => ({
    label: formatDayLabel(row.date),
    date: row.date,
    costUsd: Number(row.costUsd),
  }));

  return (
    <ResponsiveContainer width="100%" height={210}>
      <BarChart data={chartData} margin={{ top: 6, right: 8, left: 0, bottom: 0 }} barCategoryGap="18%">
        <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" vertical={false} />
        <XAxis
          dataKey="label"
          tick={{ fontSize: 10, fill: '#9ca3af' }}
          axisLine={false}
          tickLine={false}
          interval={4}
        />
        <YAxis
          tickFormatter={v => `$${v}`}
          tick={{ fontSize: 10, fill: '#9ca3af' }}
          axisLine={false}
          tickLine={false}
          width={50}
        />
        <Tooltip content={<ChartTooltip />} cursor={{ fill: 'rgba(139,92,246,0.07)' }} />
        <Bar dataKey="costUsd" radius={[4, 4, 0, 0]} fill="#8b5cf6" />
      </BarChart>
    </ResponsiveContainer>
  );
};

// ─── Shared panel states ──────────────────────────────────────────────────────

const PanelLoading: React.FC<{ text?: string }> = ({ text = 'Loading…' }) => (
  <div className="flex items-center justify-center py-12 gap-2 text-gray-400">
    <i className="fas fa-spinner fa-spin" />
    <span className="text-sm">{text}</span>
  </div>
);

const PanelError: React.FC<{ message: string }> = ({ message }) => (
  <div className="flex items-center gap-2 text-red-500 text-sm py-4">
    <i className="fas fa-exclamation-circle" />
    <span>{message}</span>
  </div>
);

const PanelEmpty: React.FC = () => (
  <div className="text-center py-12 text-gray-400">
    <i className="fas fa-inbox text-3xl mb-3 block" />
    <p className="text-sm">No billing data found for this product.</p>
  </div>
);

// ─── Meter Billing Detail Panel ───────────────────────────────────────────────

interface BillingDetailProps {
  cloudAccountId: string;
  subscription: MarketplaceSubscription;
  onClose: () => void;
  onPo: () => void;
  onCancel: () => void;
}

const BillingDetailPanel: React.FC<BillingDetailProps> = ({
  cloudAccountId,
  subscription,
  onClose,
  onPo,
  onCancel,
}) => {
  const [activeTab, setActiveTab] = useTabState<'monthly' | 'daily' | 'agreement' | 'cancellations' | 'credits'>('monthly');
  const [creditModalOpen, setCreditModalOpen] = useState(false);
  const [selectedMonth, setSelectedMonth] = useState<string>('');

  // Monthly data — always fetched (drives month selector options too)
  const { data: monthlyData, loading: monthlyLoading, error: monthlyError, accountLevel } =
    useMeterBilling(cloudAccountId, subscription.productCode);

  // Daily data — only fetched when on daily tab and a month is selected
  const { data: dailyData, loading: dailyLoading, error: dailyError, accountLevel: dailyAccountLevel } =
    useDailyMeterBilling(
      activeTab === 'daily' && !!selectedMonth ? cloudAccountId : null,
      activeTab === 'daily' && !!selectedMonth ? subscription.productCode : null,
      selectedMonth || null,
    );

  // Cancellations data — only fetched when on that tab
  const { data: cancellations, loading: cancellLoading, error: cancellError } =
    useCancellationsList(
      cloudAccountId,
      subscription.agreementId,
      activeTab === 'cancellations'
    );

  // Auto-select most recent month once monthly data is available
  useEffect(() => {
    if (monthlyData.length > 0 && !selectedMonth) {
      setSelectedMonth(monthlyData[0].yearMonth); // API returns newest-first
    }
  }, [monthlyData, selectedMonth]);

  // Unique months for the day-picker (newest-first)
  const availableMonths = useMemo(() => {
    const months = [...new Set(monthlyData.map(r => r.yearMonth))];
    return months.sort((a, b) => b.localeCompare(a));
  }, [monthlyData]);

  // Aggregate monthly rows by yearMonth for the bar chart (exclude zero-cost months)
  const monthlyChartData = useMemo(() => {
    const agg: Record<string, number> = {};
    monthlyData.forEach(row => {
      agg[row.yearMonth] = (agg[row.yearMonth] ?? 0) + Number(row.costUsd);
    });
    return Object.entries(agg)
      .sort(([a], [b]) => a.localeCompare(b))
      .filter(([, costUsd]) => costUsd > 0)
      .map(([month, costUsd]) => ({ month, label: formatMonthLabel(month), costUsd }));
  }, [monthlyData]);

  const monthlyTotal = monthlyData.reduce((s, r) => s + Number(r.costUsd), 0);
  const dailyTotal   = dailyData.reduce((s, r) => s + Number(r.costUsd), 0);
  const selectedMonthLabel = selectedMonth ? formatMonthLabel(selectedMonth) : '';
  const dailyNonZero = dailyData.filter(r => Number(r.costUsd) > 0.0001);

  return (
    <div className="mt-6 rounded-2xl border border-indigo-100 bg-white/90 backdrop-blur shadow-xl overflow-hidden">

      {/* ── Panel header ── */}
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 bg-gradient-to-r from-slate-50 to-indigo-50">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-sm">
            <i className="fas fa-chart-bar text-white text-sm" />
          </div>
          <div>
            <p className="font-semibold text-gray-800 text-sm">{subscription.productTitle}</p>
            <p className="text-xs text-gray-400">Meter Billing</p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          {/* Monthly / Daily / Agreement toggle */}
          <div className="flex items-center bg-gray-100 rounded-xl p-1">
            {([
              { id: 'monthly',       label: 'Monthly' },
              { id: 'daily',         label: 'Daily' },
              { id: 'agreement',     label: 'Agreement' },
              { id: 'cancellations', label: 'Cancellations' },
              { id: 'credits',       label: 'Credits' },
            ] as const).map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id)}
                className={`px-3 py-1 rounded-lg text-xs font-semibold transition-all ${
                  activeTab === tab.id
                    ? 'bg-white text-indigo-700 shadow-sm'
                    : 'text-gray-500 hover:text-gray-700'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>
          <button
            onClick={onClose}
            className="w-7 h-7 flex items-center justify-center rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-all"
            aria-label="Close"
          >
            <i className="fas fa-times text-xs" />
          </button>
        </div>
      </div>

      {/* ── Body ── */}
      <div className="p-6">

        {/* Account-level billing notice */}
        {accountLevel && (
          <div className="flex items-start gap-3 mb-5 px-4 py-3 rounded-xl bg-amber-50 border border-amber-200 text-sm">
            <i className="fas fa-info-circle text-amber-500 mt-0.5 flex-shrink-0" />
            <p className="text-amber-800">
              <span className="font-semibold">Account-level billing shown.</span>{' '}
              AWS Cost Explorer groups all marketplace subscriptions under shared usage types —
              per-product cost attribution is not available when multiple subscriptions exist.
              The data below reflects your total account marketplace spend.
            </p>
          </div>
        )}

        {/* ════════════ MONTHLY TAB ════════════ */}
        {activeTab === 'monthly' && (
          <>
            {monthlyLoading && <PanelLoading text="Loading monthly billing…" />}
            {!monthlyLoading && monthlyError && <PanelError message={monthlyError} />}
            {!monthlyLoading && !monthlyError && monthlyData.length === 0 && <PanelEmpty />}

            {!monthlyLoading && !monthlyError && monthlyData.length > 0 && (
              <div className="space-y-5">

                {/* Summary strip */}
                <div className="flex items-center gap-6 flex-wrap text-sm">
                  <div className="flex items-center gap-2">
                    <span className="w-3 h-3 rounded-sm bg-indigo-300 inline-block" />
                    <span className="text-gray-500">12-month total</span>
                    <span className="font-bold text-gray-800 ml-1">{formatUsd(monthlyTotal)}</span>
                  </div>
                  {monthlyChartData.length > 0 && (
                    <div className="flex items-center gap-2">
                      <span className="w-3 h-3 rounded-sm bg-indigo-600 inline-block" />
                      <span className="text-gray-500">Latest month</span>
                      <span className="font-bold text-gray-800 ml-1">
                        {formatUsd(monthlyChartData[monthlyChartData.length - 1]?.costUsd ?? 0)}
                      </span>
                    </div>
                  )}
                </div>

                {/* Bar chart */}
                <MonthlyBarChart data={monthlyChartData} />

                {/* Table */}
                <div className="overflow-x-auto rounded-xl border border-gray-100">
                  <table className="w-full text-sm">
                    <thead className="bg-gray-50">
                      <tr>
                        {['Month', 'Usage Type', 'Quantity', 'Cost (USD)'].map(h => (
                          <th
                            key={h}
                            className={`py-2.5 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wide ${
                              h === 'Cost (USD)' || h === 'Quantity' ? 'text-right' : 'text-left'
                            }`}
                          >
                            {h}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                      {monthlyData.map((row, idx) => (
                        <tr key={idx} className="hover:bg-indigo-50/30 transition-colors">
                          <td className="py-2.5 px-4 font-medium text-gray-700">{row.yearMonth}</td>
                          <td className="py-2.5 px-4 text-gray-400 font-mono text-xs max-w-xs truncate">
                            {row.usageType ?? '—'}
                          </td>
                          <td className="py-2.5 px-4 text-right text-gray-600">
                            {row.usageQuantity != null ? Number(row.usageQuantity).toFixed(2) : '—'}
                          </td>
                          <td className="py-2.5 px-4 text-right font-semibold text-indigo-700">
                            {formatUsd(Number(row.costUsd))}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                    <tfoot>
                      <tr className="bg-gray-50 border-t-2 border-gray-200">
                        <td colSpan={3} className="py-2.5 px-4 text-xs font-semibold text-gray-500 uppercase">
                          Total
                        </td>
                        <td className="py-2.5 px-4 text-right font-bold text-indigo-800">
                          {formatUsd(monthlyTotal)}
                        </td>
                      </tr>
                    </tfoot>
                  </table>
                </div>
              </div>
            )}
          </>
        )}

        {/* ════════════ DAILY TAB ════════════ */}
        {activeTab === 'daily' && (
          <>
            {monthlyLoading && <PanelLoading text="Loading billing data…" />}
            {!monthlyLoading && availableMonths.length === 0 && <PanelEmpty />}

            {!monthlyLoading && availableMonths.length > 0 && (
              <div className="space-y-5">

                {/* Account-level billing notice for daily tab */}
                {dailyAccountLevel && (
                  <div className="flex items-start gap-3 px-4 py-3 rounded-xl bg-amber-50 border border-amber-200 text-sm">
                    <i className="fas fa-info-circle text-amber-500 mt-0.5 flex-shrink-0" />
                    <p className="text-amber-800">
                      <span className="font-semibold">Account-level billing shown.</span>{' '}
                      AWS Cost Explorer groups all marketplace subscriptions under shared usage types —
                      per-product cost attribution is not available when multiple subscriptions exist.
                      The data below reflects your total account marketplace spend.
                    </p>
                  </div>
                )}

                {/* Month selector + summary */}
                <div className="flex items-center flex-wrap gap-4">
                  <div className="flex items-center gap-2">
                    <label className="text-sm text-gray-500 font-medium whitespace-nowrap">
                      Select month:
                    </label>
                    <select
                      value={selectedMonth}
                      onChange={e => setSelectedMonth(e.target.value)}
                      className="text-sm border border-gray-200 rounded-xl px-3 py-1.5 text-gray-700 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all"
                    >
                      {availableMonths.map(m => (
                        <option key={m} value={m}>
                          {formatMonthLabel(m)} ({m})
                        </option>
                      ))}
                    </select>
                  </div>

                  {dailyLoading && (
                    <span className="text-xs text-indigo-500 flex items-center gap-1.5">
                      <i className="fas fa-spinner fa-spin" />
                      Fetching daily data from AWS…
                    </span>
                  )}

                  {!dailyLoading && dailyData.length > 0 && (
                    <div className="flex items-center gap-2 text-sm">
                      <span className="w-3 h-3 rounded-sm bg-violet-500 inline-block" />
                      <span className="text-gray-500">{selectedMonthLabel} total</span>
                      <span className="font-bold text-gray-800 ml-1">{formatUsd(dailyTotal)}</span>
                    </div>
                  )}
                </div>

                {dailyError && <PanelError message={dailyError} />}

                {!dailyLoading && !dailyError && dailyData.length > 0 && (
                  <>
                    {/* Bar chart */}
                    <DailyBarChart data={dailyData} />

                    {/* Table — only non-zero days */}
                    {dailyNonZero.length === 0 ? (
                      <p className="text-center text-sm text-gray-400 py-4">
                        No charges recorded in {selectedMonthLabel}.
                      </p>
                    ) : (
                      <div className="overflow-x-auto rounded-xl border border-gray-100">
                        <table className="w-full text-sm">
                          <thead className="bg-gray-50">
                            <tr>
                              {['Date', 'Quantity', 'Cost (USD)'].map(h => (
                                <th
                                  key={h}
                                  className={`py-2.5 px-4 text-xs font-semibold text-gray-500 uppercase tracking-wide ${
                                    h !== 'Date' ? 'text-right' : 'text-left'
                                  }`}
                                >
                                  {h}
                                </th>
                              ))}
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-gray-50">
                            {dailyNonZero.map((row, idx) => (
                              <tr key={idx} className="hover:bg-violet-50/30 transition-colors">
                                <td className="py-2.5 px-4 font-medium text-gray-700">{row.date}</td>
                                <td className="py-2.5 px-4 text-right text-gray-600">
                                  {row.usageQuantity != null ? Number(row.usageQuantity).toFixed(2) : '—'}
                                </td>
                                <td className="py-2.5 px-4 text-right font-semibold text-violet-700">
                                  {formatUsd(Number(row.costUsd))}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                          <tfoot>
                            <tr className="bg-gray-50 border-t-2 border-gray-200">
                              <td colSpan={2} className="py-2.5 px-4 text-xs font-semibold text-gray-500 uppercase">
                                Total
                              </td>
                              <td className="py-2.5 px-4 text-right font-bold text-violet-800">
                                {formatUsd(dailyTotal)}
                              </td>
                            </tr>
                          </tfoot>
                        </table>
                      </div>
                    )}
                  </>
                )}
              </div>
            )}
          </>
        )}

        {/* ════════════ AGREEMENT TAB ════════════ */}
        {activeTab === 'agreement' && (
          <div className="space-y-5">
            {!subscription.agreementId ? (
              <div className="text-center py-12 text-gray-400">
                <i className="fas fa-file-contract text-3xl mb-3 block" />
                <p className="text-sm">No agreement details available.</p>
                <p className="text-xs mt-1 text-gray-300">
                  Requires <code className="bg-gray-100 px-1 rounded">aws-marketplace:DescribeAgreement</code> permission on the IAM role.
                </p>
              </div>
            ) : (
              <>
                {/* Agreement info grid */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  {[
                    { label: 'Agreement ID', value: subscription.agreementId, mono: true },
                    { label: 'Offer Type', value: offerTypeLabel(subscription.offerType) },
                    { label: 'Start Date', value: formatDate(subscription.startDate) },
                    { label: 'Expiry Date', value: formatDate(subscription.expiryDate) },
                    { label: 'Payment Schedule', value: paymentScheduleLabel(subscription.paymentSchedule) },
                    { label: 'Payment Terms', value: subscription.paymentTermSummary ?? '—' },
                    { label: 'Auto-Renew', value: subscription.autoRenew === true ? 'Yes' : subscription.autoRenew === false ? 'No' : '—' },
                    { label: 'Vendor', value: subscription.vendorName ?? '—' },
                  ].map(({ label, value, mono }) => (
                    <div key={label} className="rounded-xl border border-gray-100 bg-gray-50/60 px-4 py-3">
                      <p className="text-xs text-gray-400 font-medium mb-1">{label}</p>
                      <p className={`text-sm font-semibold text-gray-800 break-all ${mono ? 'font-mono text-xs' : ''}`}>
                        {value || '—'}
                      </p>
                    </div>
                  ))}
                </div>

                {/* Expiry countdown */}
                {(() => {
                  const d = daysUntil(subscription.expiryDate);
                  if (d === null) return null;
                  const color = d <= 7
                    ? 'bg-red-50 border-red-200 text-red-800'
                    : d <= 30
                      ? 'bg-amber-50 border-amber-200 text-amber-800'
                      : d <= 90
                        ? 'bg-yellow-50 border-yellow-200 text-yellow-800'
                        : 'bg-green-50 border-green-200 text-green-800';
                  const icon = d <= 30 ? 'fa-exclamation-triangle' : 'fa-calendar-check';
                  return (
                    <div className={`flex items-center gap-3 px-4 py-3 rounded-xl border text-sm ${color}`}>
                      <i className={`fas ${icon} flex-shrink-0`} />
                      <span>
                        {d === 0
                          ? 'This agreement expires today.'
                          : d < 0
                            ? `This agreement expired ${Math.abs(d)} day${Math.abs(d) !== 1 ? 's' : ''} ago.`
                            : `This agreement expires in ${d} day${d !== 1 ? 's' : ''} on ${formatDate(subscription.expiryDate)}.`}
                      </span>
                    </div>
                  );
                })()}

                {/* PO section */}
                <div className="rounded-xl border border-gray-100 bg-gray-50/60 px-4 py-3">
                  <div className="flex items-center justify-between mb-1">
                    <p className="text-xs text-gray-400 font-medium">Purchase Order</p>
                    <button
                      onClick={onPo}
                      className="text-[10px] font-semibold text-indigo-600 hover:text-indigo-800 transition-colors flex items-center gap-1"
                    >
                      <i className="fas fa-pencil-alt" />
                      {subscription.poNumber ? 'Edit' : 'Add PO'}
                    </button>
                  </div>
                  {subscription.poNumber ? (
                    <div>
                      <p className="text-sm font-semibold text-gray-800">{subscription.poNumber}</p>
                      {subscription.poNotes && (
                        <p className="text-xs text-gray-500 mt-0.5">{subscription.poNotes}</p>
                      )}
                    </div>
                  ) : (
                    <p className="text-sm text-gray-400 italic">No PO number attached</p>
                  )}
                </div>

                {/* Cancel action */}
                {subscription.subscriptionStatus === 'ACTIVE' && (
                  <div className="flex justify-end pt-1">
                    <button
                      onClick={onCancel}
                      className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold border border-red-200 text-red-600 hover:bg-red-50 transition-all"
                    >
                      <i className="fas fa-ban" />
                      Cancel Subscription
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {/* ── Cancellations tab ── */}
        {activeTab === 'cancellations' && (
          <div className="space-y-4">
            {cancellLoading && (
              <div className="flex items-center justify-center py-12 gap-3 text-gray-400">
                <i className="fas fa-spinner fa-spin" />
                <span className="text-sm">Loading cancellation requests…</span>
              </div>
            )}
            {cancellError && (
              <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-sm text-amber-700">
                <i className="fas fa-info-circle mr-2" />{cancellError}
              </div>
            )}
            {!cancellLoading && !cancellError && cancellations.length === 0 && (
              <div className="text-center py-12 text-gray-400">
                <i className="fas fa-check-circle text-3xl mb-3 block text-green-300" />
                <p className="text-sm">No cancellation requests found for this agreement.</p>
              </div>
            )}
            {cancellations.map((c, i) => {
              const status = (c.Status ?? c.status) as string;
              const statusColor = status === 'ACTIVE'
                ? 'bg-amber-50 text-amber-700 border-amber-200'
                : status === 'CANCELLED' || status === 'COMPLETED'
                  ? 'bg-green-50 text-green-700 border-green-200'
                  : 'bg-gray-50 text-gray-600 border-gray-200';
              return (
                <div key={(c.AgreementCancellationRequestId ?? i) as string}
                  className="rounded-xl border border-gray-100 bg-gray-50 px-4 py-3 text-sm space-y-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs text-gray-500 truncate max-w-[60%]">
                      {(c.AgreementCancellationRequestId ?? '—') as string}
                    </span>
                    <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full border ${statusColor}`}>
                      {status ?? '—'}
                    </span>
                  </div>
                  {!!(c.RequestedOn || c.CreatedTime) && (
                    <p className="text-xs text-gray-400">
                      <i className="fas fa-clock mr-1" />
                      Requested: {new Date((c.RequestedOn ?? c.CreatedTime) as string).toLocaleDateString()}
                    </p>
                  )}
                  {!!c.EffectiveDate && (
                    <p className="text-xs text-gray-400">
                      <i className="fas fa-calendar mr-1" />
                      Effective: {new Date(c.EffectiveDate as string).toLocaleDateString()}
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* ── Credits tab ── */}
        {activeTab === 'credits' && (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <p className="text-sm text-gray-500">Billing adjustments and credit requests for this subscription.</p>
              <button
                onClick={() => setCreditModalOpen(true)}
                className="flex items-center gap-2 px-3 py-1.5 rounded-xl text-xs font-semibold bg-indigo-600 text-white hover:bg-indigo-700 transition-colors"
              >
                <i className="fas fa-plus" /> Request Credit
              </button>
            </div>
            <div className="text-center py-12 text-gray-400">
              <i className="fas fa-receipt text-3xl mb-3 block text-gray-300" />
              <p className="text-sm">Use the button above to submit a billing credit request.</p>
              <p className="text-xs mt-1 text-gray-300">Credits are applied against a specific invoice.</p>
            </div>
          </div>
        )}

      </div>

      {/* ── Request Credit Modal ── */}
      {creditModalOpen && (
        <CreditRequestModal
          subscription={subscription}
          cloudAccountId={cloudAccountId}
          onClose={() => setCreditModalOpen(false)}
        />
      )}

    </div>
  );
};

// ─── Credit Request Modal ─────────────────────────────────────────────────────

const REASON_CODES = [
  'BILLING_ERROR', 'DUPLICATE_CHARGE', 'SERVICE_DISRUPTION',
  'CONTRACT_AMENDMENT', 'GOODWILL', 'OTHER',
];

const CreditRequestModal: React.FC<{
  subscription: MarketplaceSubscription;
  cloudAccountId: string;
  onClose: () => void;
}> = ({ subscription, cloudAccountId, onClose }) => {
  const [invoiceId, setInvoiceId]   = useState('');
  const [amount, setAmount]         = useState('');
  const [currency, setCurrency]     = useState('USD');
  const [reasonCode, setReasonCode] = useState(REASON_CODES[0]);
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError]           = useState<string | null>(null);
  const [success, setSuccess]       = useState(false);

  const handleSubmit = async () => {
    if (!invoiceId || !amount || !reasonCode) {
      setError('Invoice ID, amount, and reason are required.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await submitCreditRequest(cloudAccountId, [{
        agreementId:          subscription.agreementId!,
        originalInvoiceId:    invoiceId,
        adjustmentAmount:     amount,
        currencyCode:         currency,
        adjustmentReasonCode: reasonCode,
        description:          description || undefined,
        clientToken:          `${subscription.agreementId}-${Date.now()}`,
      }]);
      setSuccess(true);
    } catch (e: any) {
      setError(e.message ?? 'Failed to submit credit request');
    } finally {
      setSubmitting(false);
    }
  };

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center" onClick={onClose}>
      <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" />
      <div className="relative bg-white rounded-2xl shadow-2xl w-full max-w-md mx-4 p-6 space-y-4" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <h2 className="font-bold text-gray-800 text-base flex items-center gap-2">
            <i className="fas fa-receipt text-indigo-500" /> Request Billing Credit
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition-colors">
            <i className="fas fa-times" />
          </button>
        </div>

        {success ? (
          <div className="text-center py-8 space-y-3">
            <i className="fas fa-check-circle text-4xl text-green-500 block" />
            <p className="font-semibold text-gray-800">Credit request submitted!</p>
            <p className="text-sm text-gray-500">AWS will review and process the billing adjustment.</p>
            <button onClick={onClose} className="mt-2 px-4 py-2 rounded-xl bg-indigo-600 text-white text-sm font-semibold hover:bg-indigo-700 transition-colors">
              Done
            </button>
          </div>
        ) : (
          <>
            <p className="text-xs text-gray-500">
              Subscription: <span className="font-semibold text-gray-700">{subscription.productTitle}</span>
            </p>

            <div className="space-y-3">
              <div>
                <label className="text-xs font-semibold text-gray-600 block mb-1">Invoice ID <span className="text-red-400">*</span></label>
                <input
                  value={invoiceId} onChange={e => setInvoiceId(e.target.value)}
                  placeholder="INV-XXXXXXXX"
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-300"
                />
              </div>
              <div className="flex gap-2">
                <div className="flex-1">
                  <label className="text-xs font-semibold text-gray-600 block mb-1">Amount <span className="text-red-400">*</span></label>
                  <input
                    value={amount} onChange={e => setAmount(e.target.value)}
                    placeholder="100.00" type="number" min="0" step="0.01"
                    className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-300"
                  />
                </div>
                <div className="w-24">
                  <label className="text-xs font-semibold text-gray-600 block mb-1">Currency</label>
                  <select value={currency} onChange={e => setCurrency(e.target.value)}
                    className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-300">
                    {['USD', 'EUR', 'GBP', 'JPY', 'AUD', 'CAD'].map(c => <option key={c}>{c}</option>)}
                  </select>
                </div>
              </div>
              <div>
                <label className="text-xs font-semibold text-gray-600 block mb-1">Reason <span className="text-red-400">*</span></label>
                <select value={reasonCode} onChange={e => setReasonCode(e.target.value)}
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-300">
                  {REASON_CODES.map(r => <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>)}
                </select>
              </div>
              <div>
                <label className="text-xs font-semibold text-gray-600 block mb-1">Description (optional)</label>
                <textarea
                  value={description} onChange={e => setDescription(e.target.value)}
                  rows={2} placeholder="Describe the issue…"
                  className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-300 resize-none"
                />
              </div>
            </div>

            {error && (
              <p className="text-xs text-red-600 bg-red-50 border border-red-100 rounded-xl px-3 py-2">
                <i className="fas fa-exclamation-circle mr-1" />{error}
              </p>
            )}

            <div className="flex gap-2 pt-1">
              <button onClick={onClose} className="flex-1 px-4 py-2 rounded-xl text-sm font-semibold border border-gray-200 text-gray-600 hover:bg-gray-50 transition-colors">
                Cancel
              </button>
              <button
                onClick={handleSubmit} disabled={submitting}
                className="flex-1 px-4 py-2 rounded-xl text-sm font-semibold bg-indigo-600 text-white hover:bg-indigo-700 transition-colors disabled:opacity-60 flex items-center justify-center gap-2"
              >
                {submitting ? <><i className="fas fa-spinner fa-spin" /> Submitting…</> : 'Submit Request'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>,
    document.body
  );
};

// ─── PO Number Modal ─────────────────────────────────────────────────────────

interface PoModalProps {
  subscription: MarketplaceSubscription;
  cloudAccountId: string;
  onClose: () => void;
  onSaved: (poNumber: string, poNotes: string) => void;
}

const PoModal: React.FC<PoModalProps> = ({ subscription, cloudAccountId, onClose, onSaved }) => {
  const [poNumber, setPoNumber] = useState(subscription.poNumber ?? '');
  const [poNotes, setPoNotes]   = useState(subscription.poNotes ?? '');
  const [saving, setSaving]     = useState(false);
  const [error, setError]       = useState<string | null>(null);

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await updatePoNumber(cloudAccountId, subscription.id, poNumber, poNotes);
      onSaved(poNumber, poNotes);
      onClose();
    } catch (e: any) {
      setError(e.message ?? 'Failed to save PO number');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md mx-4 overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 bg-gradient-to-r from-slate-50 to-indigo-50">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center">
              <i className="fas fa-file-invoice text-white text-sm" />
            </div>
            <div>
              <p className="font-semibold text-gray-800 text-sm">Purchase Order</p>
              <p className="text-xs text-gray-400 truncate max-w-48">{subscription.productTitle}</p>
            </div>
          </div>
          <button onClick={onClose} className="w-7 h-7 flex items-center justify-center rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-all">
            <i className="fas fa-times text-xs" />
          </button>
        </div>
        <div className="p-6 space-y-4">
          {error && (
            <div className="flex items-center gap-2 text-red-600 text-sm bg-red-50 border border-red-100 rounded-xl px-3 py-2">
              <i className="fas fa-exclamation-circle" />
              <span>{error}</span>
            </div>
          )}
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5">
              PO Number
            </label>
            <input
              type="text"
              value={poNumber}
              onChange={e => setPoNumber(e.target.value)}
              placeholder="e.g. PO-2026-00123"
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm text-gray-800 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all"
            />
          </div>
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1.5">
              Notes <span className="font-normal text-gray-400 normal-case">(optional)</span>
            </label>
            <textarea
              value={poNotes}
              onChange={e => setPoNotes(e.target.value)}
              placeholder="e.g. Approved by Finance team on 2026-05-01"
              rows={3}
              className="w-full border border-gray-200 rounded-xl px-3 py-2 text-sm text-gray-800 bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all resize-none"
            />
          </div>
          <div className="flex items-center justify-end gap-3 pt-2">
            <button
              onClick={onClose}
              className="px-4 py-2 rounded-xl text-sm font-semibold text-gray-600 hover:bg-gray-100 transition-all"
            >
              Cancel
            </button>
            <button
              onClick={handleSave}
              disabled={saving}
              className="px-4 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-indigo-600 to-violet-600 text-white shadow-sm hover:shadow-md transition-all disabled:opacity-60"
            >
              {saving ? <><i className="fas fa-spinner fa-spin mr-1.5" />Saving…</> : 'Save PO Number'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

// ─── Cancel Confirm Modal ─────────────────────────────────────────────────────

interface CancelModalProps {
  subscription: MarketplaceSubscription;
  cloudAccountId: string;
  onClose: () => void;
  onCancelled: () => void;
}

const CancelModal: React.FC<CancelModalProps> = ({ subscription, cloudAccountId, onClose, onCancelled }) => {
  const [cancelling, setCancelling] = useState(false);
  const [error, setError]           = useState<string | null>(null);
  const [result, setResult]         = useState<string | null>(null);

  const handleConfirm = async () => {
    setCancelling(true);
    setError(null);
    try {
      const res = await cancelSubscription(cloudAccountId, subscription.id);
      setResult(res.message ?? 'Cancellation submitted.');
      onCancelled();
    } catch (e: any) {
      setError(e.message ?? 'Cancellation failed');
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md mx-4 overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100 bg-gradient-to-r from-red-50 to-rose-50">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-red-500 to-rose-600 flex items-center justify-center">
              <i className="fas fa-ban text-white text-sm" />
            </div>
            <div>
              <p className="font-semibold text-gray-800 text-sm">Cancel Subscription</p>
              <p className="text-xs text-gray-400 truncate max-w-48">{subscription.productTitle}</p>
            </div>
          </div>
          <button onClick={onClose} className="w-7 h-7 flex items-center justify-center rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-all">
            <i className="fas fa-times text-xs" />
          </button>
        </div>
        <div className="p-6 space-y-4">
          {result ? (
            <div className="flex items-start gap-3 bg-green-50 border border-green-200 rounded-xl px-4 py-3 text-sm text-green-800">
              <i className="fas fa-check-circle mt-0.5 flex-shrink-0 text-green-500" />
              <p>{result}</p>
            </div>
          ) : (
            <>
              <div className="flex items-start gap-3 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-sm text-amber-800">
                <i className="fas fa-exclamation-triangle mt-0.5 flex-shrink-0 text-amber-500" />
                <p>
                  This will submit a <strong>cancellation request</strong> to AWS Marketplace for{' '}
                  <strong>{subscription.productTitle}</strong>. AWS processes cancellations asynchronously.
                  Some agreements may not be cancellable based on their terms.
                </p>
              </div>
              {subscription.agreementId && (
                <div className="text-xs text-gray-400 bg-gray-50 rounded-xl px-3 py-2 font-mono break-all">
                  Agreement: {subscription.agreementId}
                </div>
              )}
              {error && (
                <div className="flex items-center gap-2 text-red-600 text-sm bg-red-50 border border-red-100 rounded-xl px-3 py-2">
                  <i className="fas fa-exclamation-circle" />
                  <span>{error}</span>
                </div>
              )}
              <div className="flex items-center justify-end gap-3 pt-2">
                <button
                  onClick={onClose}
                  className="px-4 py-2 rounded-xl text-sm font-semibold text-gray-600 hover:bg-gray-100 transition-all"
                >
                  Keep Subscription
                </button>
                <button
                  onClick={handleConfirm}
                  disabled={cancelling}
                  className="px-4 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-red-500 to-rose-600 text-white shadow-sm hover:shadow-md transition-all disabled:opacity-60"
                >
                  {cancelling ? <><i className="fas fa-spinner fa-spin mr-1.5" />Cancelling…</> : 'Yes, Cancel Subscription'}
                </button>
              </div>
            </>
          )}
          {result && (
            <div className="flex justify-end">
              <button onClick={onClose} className="px-4 py-2 rounded-xl text-sm font-semibold bg-gray-100 text-gray-700 hover:bg-gray-200 transition-all">
                Close
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

// ─── Product Detail Drawer ────────────────────────────────────────────────────

const ProductDetailDrawer: React.FC<{
  product: import('../types/marketops').CatalogProduct;
  cloudAccountId: string;
  onClose: () => void;
}> = ({ product, cloudAccountId, onClose }) => {
  const { data, loading, error } = useProductDetail(cloudAccountId, product.listingId);

  return createPortal(
    <div className="fixed inset-0 z-50 flex justify-end" onClick={onClose}>
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/30 backdrop-blur-sm" />

      {/* Panel */}
      <div
        className="relative w-full max-w-xl bg-white h-full shadow-2xl flex flex-col overflow-hidden"
        onClick={e => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-start gap-4 px-6 py-5 border-b border-gray-100 bg-gradient-to-r from-violet-50 to-indigo-50">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center shadow-sm flex-shrink-0">
            <i className="fas fa-store text-white" />
          </div>
          <div className="flex-1 min-w-0">
            <h2 className="font-bold text-gray-800 text-base leading-snug">{product.name}</h2>
            {product.sellerName && (
              <p className="text-xs text-gray-500 mt-0.5">
                <i className="fas fa-building mr-1 text-gray-400" />{product.sellerName}
              </p>
            )}
            <div className="flex flex-wrap gap-1 mt-2">
              {product.pricingModels.map(pm => (
                <span key={pm} className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-green-50 text-green-700 border border-green-100">{pm}</span>
              ))}
              {product.badges.map(b => (
                <span key={b} className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 border border-amber-100">{b}</span>
              ))}
            </div>
          </div>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600 transition-colors flex-shrink-0 mt-0.5">
            <i className="fas fa-times text-lg" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-6">

          {/* Description */}
          {product.shortDescription && (
            <p className="text-sm text-gray-600 leading-relaxed">{product.shortDescription}</p>
          )}

          {/* Categories + fulfillment */}
          <div className="flex flex-wrap gap-1.5">
            {product.categories.map(c => (
              <span key={c} className="text-xs px-2.5 py-1 rounded-full bg-indigo-50 text-indigo-600 border border-indigo-100 font-medium">{c}</span>
            ))}
            {product.fulfillmentTypes.map(f => (
              <span key={f} className="text-xs px-2.5 py-1 rounded-full bg-blue-50 text-blue-600 border border-blue-100 font-medium">{f}</span>
            ))}
          </div>

          {loading && (
            <div className="flex items-center gap-3 text-gray-400 py-8 justify-center">
              <i className="fas fa-spinner fa-spin" />
              <span className="text-sm">Loading product details…</span>
            </div>
          )}

          {error && (
            <div className="bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-sm text-amber-700">
              <i className="fas fa-info-circle mr-2" />{error}
            </div>
          )}

          {data && (
            <>
              {/* Purchase Options */}
              {data.purchaseOptions.length > 0 && (
                <div>
                  <h3 className="font-semibold text-gray-700 text-sm mb-3 flex items-center gap-2">
                    <i className="fas fa-tag text-indigo-400" /> Purchase Plans
                  </h3>
                  <div className="space-y-2">
                    {data.purchaseOptions.map((opt, i) => (
                      <div key={(opt as any).purchaseOptionId ?? i} className="rounded-xl border border-gray-100 bg-gray-50 px-4 py-3 text-sm">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium text-gray-800">
                            {(opt as any).purchaseOptionName ?? `Option ${i + 1}`}
                          </span>
                          {(opt as any).purchaseOptionType && (
                            <span className="text-xs px-2 py-0.5 rounded-full bg-green-50 text-green-700 border border-green-100 flex-shrink-0">
                              {(opt as any).purchaseOptionType}
                            </span>
                          )}
                        </div>
                        {(opt as any).sellerName && (
                          <p className="text-xs text-gray-400 mt-1">
                            <i className="fas fa-building mr-1" />{(opt as any).sellerName}
                          </p>
                        )}
                        {((opt as any).badges ?? []).length > 0 && (
                          <div className="flex gap-1 mt-1.5 flex-wrap">
                            {((opt as any).badges as string[]).map((b: string) => (
                              <span key={b} className="text-[10px] px-1.5 py-0.5 rounded-full bg-amber-50 text-amber-700 border border-amber-100">{b}</span>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Fulfillment Options */}
              {data.fulfillmentOptions.length > 0 && (
                <div>
                  <h3 className="font-semibold text-gray-700 text-sm mb-3 flex items-center gap-2">
                    <i className="fas fa-cubes text-blue-400" /> Fulfillment Options
                  </h3>
                  <div className="space-y-2">
                    {data.fulfillmentOptions.map((opt, i) => (
                      <div key={(opt as any).fulfillmentOptionId ?? i} className="rounded-xl border border-gray-100 bg-gray-50 px-4 py-3 text-sm">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium text-gray-800">
                            {(opt as any).displayName ?? (opt as any).name ?? `Option ${i + 1}`}
                          </span>
                          {(opt as any).type && (
                            <span className="text-xs px-2 py-0.5 rounded-full bg-blue-50 text-blue-600 border border-blue-100 flex-shrink-0">
                              {(opt as any).type}
                            </span>
                          )}
                        </div>
                        {(opt as any).version && (
                          <p className="text-xs text-gray-400 mt-1">v{(opt as any).version}</p>
                        )}
                        {(opt as any).recommendedInstanceType && (
                          <p className="text-xs text-gray-500 mt-1">
                            <i className="fas fa-server mr-1 text-gray-400" />Recommended: {(opt as any).recommendedInstanceType}
                          </p>
                        )}
                        {((opt as any).operatingSystems ?? []).length > 0 && (
                          <div className="flex gap-1 mt-1.5 flex-wrap">
                            {((opt as any).operatingSystems as any[]).map((os: any, oi: number) => (
                              <span key={oi} className="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 text-gray-500 border border-gray-200">
                                {os.operatingSystemName ?? os.operatingSystemFamilyName}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Offer Terms */}
              {data.offerTerms && data.offerTerms.length > 0 && (
                <div>
                  <h3 className="font-semibold text-gray-700 text-sm mb-3 flex items-center gap-2">
                    <i className="fas fa-file-contract text-purple-400" /> Offer Terms
                  </h3>
                  <div className="space-y-2">
                    {data.offerTerms.map((term, i) => {
                      const label = (term.termType ?? '').replace(/([A-Z])/g, ' $1').trim();
                      return (
                        <div key={term.id ?? i} className="rounded-xl border border-gray-100 bg-gray-50 px-4 py-3 text-sm">
                          <div className="flex items-center justify-between gap-2 mb-1">
                            <span className="font-medium text-gray-800 capitalize">{label}</span>
                            {term.currencyCode && term.price && (
                              <span className="text-xs font-semibold text-indigo-600">
                                {term.currencyCode} {term.price}{term.duration ? ` / ${term.duration}` : ''}{term.billingPeriod ? ` (${term.billingPeriod})` : ''}
                              </span>
                            )}
                            {term.agreementDuration && !term.price && (
                              <span className="text-xs text-gray-400">{term.agreementDuration}</span>
                            )}
                          </div>
                          {term.refundPolicy && (
                            <p className="text-xs text-gray-500 mt-0.5">{term.refundPolicy}</p>
                          )}
                          {term.rateCardCount != null && (
                            <p className="text-xs text-gray-400 mt-0.5">{term.rateCardCount} rate card{term.rateCardCount !== 1 ? 's' : ''}</p>
                          )}
                          {term.maxTotalChargeAmount && (
                            <p className="text-xs text-gray-500 mt-0.5">Max charge: {term.currencyCode} {term.maxTotalChargeAmount}</p>
                          )}
                          {term.documents && term.documents.length > 0 && (
                            <div className="flex flex-wrap gap-2 mt-1.5">
                              {term.documents.map((doc, di) => (
                                <a key={di} href={doc.url} target="_blank" rel="noopener noreferrer"
                                  className="text-xs text-indigo-500 hover:text-indigo-700 underline flex items-center gap-1">
                                  <i className="fas fa-file-alt text-[9px]" />{doc.name || 'View Document'}
                                </a>
                              ))}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Listing metadata */}
              {data.listing && (
                <div>
                  <h3 className="font-semibold text-gray-700 text-sm mb-3 flex items-center gap-2">
                    <i className="fas fa-info-circle text-gray-400" /> Details
                  </h3>
                  <div className="rounded-xl border border-gray-100 bg-gray-50 px-4 py-3 space-y-1.5 text-xs text-gray-600">
                    {(data.listing.listingId as string) && (
                      <div className="flex justify-between">
                        <span className="text-gray-400">Listing ID</span>
                        <span className="font-mono text-gray-600 truncate max-w-[60%]">{data.listing.listingId as string}</span>
                      </div>
                    )}
                    {product.averageRating != null && (
                      <div className="flex justify-between">
                        <span className="text-gray-400">Avg Rating</span>
                        <span className="text-amber-600 font-medium">
                          <i className="fas fa-star mr-1 text-[10px]" />{product.averageRating.toFixed(1)}
                        </span>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </>
          )}
        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-gray-100 flex items-center justify-between bg-gray-50">
          <a
            href={`https://aws.amazon.com/marketplace/pp/${product.listingId}`}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-2 text-sm font-semibold text-indigo-600 hover:text-indigo-800 transition-colors"
          >
            <i className="fab fa-aws" /> Subscribe on AWS Marketplace
            <i className="fas fa-external-link-alt text-[10px]" />
          </a>
          <button onClick={onClose} className="text-sm text-gray-400 hover:text-gray-600 transition-colors">
            Close
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
};

// ─── Catalog Product Card ─────────────────────────────────────────────────────

const CatalogCard: React.FC<{ product: CatalogProduct; onClick: () => void }> = ({ product, onClick }) => (
  <div onClick={onClick} className="group rounded-2xl border border-gray-100 bg-white/80 hover:border-indigo-200 hover:shadow-md hover:-translate-y-0.5 transition-all duration-200 p-5 flex flex-col gap-3 cursor-pointer">
    <div className="flex items-start justify-between">
      <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center shadow-sm flex-shrink-0">
        <i className="fas fa-store text-white" />
      </div>
      <div className="flex flex-col items-end gap-1">
        {product.pricingModels.slice(0, 1).map(pm => (
          <span key={pm} className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-green-50 text-green-700 border border-green-100">
            {pm}
          </span>
        ))}
        {product.badges.slice(0, 1).map(b => (
          <span key={b} className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 border border-amber-100">
            {b}
          </span>
        ))}
      </div>
    </div>

    <div className="flex-1">
      <h3 className="font-bold text-gray-800 text-sm leading-snug mb-1 line-clamp-2">{product.name}</h3>
      {product.sellerName && (
        <p className="text-xs text-gray-400 mb-2 truncate">
          <i className="fas fa-building mr-1 text-gray-300" />
          {product.sellerName}
        </p>
      )}
      {product.averageRating != null && (
        <p className="text-xs text-amber-500 mb-1 flex items-center gap-1">
          <i className="fas fa-star text-[10px]" />
          {product.averageRating.toFixed(1)}
        </p>
      )}
      {product.shortDescription && (
        <p className="text-xs text-gray-500 line-clamp-3 leading-relaxed">{product.shortDescription}</p>
      )}
    </div>

    {product.categories.length > 0 && (
      <div className="flex flex-wrap gap-1">
        {product.categories.slice(0, 3).map(cat => (
          <span key={cat} className="text-[10px] px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-600 border border-indigo-100 font-medium">
            {cat}
          </span>
        ))}
        {product.categories.length > 3 && (
          <span className="text-[10px] px-2 py-0.5 rounded-full bg-gray-50 text-gray-400 border border-gray-100">
            +{product.categories.length - 3} more
          </span>
        )}
      </div>
    )}

    {product.fulfillmentTypes.length > 0 && (
      <div className="flex flex-wrap gap-1">
        {product.fulfillmentTypes.slice(0, 2).map(ft => (
          <span key={ft} className="text-[10px] px-2 py-0.5 rounded-full bg-blue-50 text-blue-600 border border-blue-100 font-medium">
            {ft}
          </span>
        ))}
      </div>
    )}

    <div className="flex items-center justify-end pt-2 border-t border-gray-100 text-xs text-gray-400">
      <a
        href={`https://aws.amazon.com/marketplace/pp/${product.listingId}`}
        target="_blank"
        rel="noopener noreferrer"
        className="flex items-center gap-1 text-indigo-500 hover:text-indigo-700 font-medium transition-colors"
        onClick={e => e.stopPropagation()}
      >
        View on AWS <i className="fas fa-external-link-alt text-[9px]" />
      </a>
    </div>
  </div>
);

// ─── Discover Products Panel ──────────────────────────────────────────────────

const DiscoverPanel: React.FC<{ cloudAccountId: string }> = ({ cloudAccountId }) => {
  const [search, setSearch]             = useState('');
  const [category, setCategory]         = useState('');
  const [pricingModel, setPricingModel] = useState('');
  const [inputVal, setInputVal]         = useState('');
  const [selectedProduct, setSelectedProduct] = useState<import('../types/marketops').CatalogProduct | null>(null);
  const debounceRef                     = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleSearchChange = (val: string) => {
    setInputVal(val);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => setSearch(val), 500);
  };

  const { facets } = useCatalogFacets(cloudAccountId);
  const { products, nextToken, loading, error, loadMore } =
    useCatalogBrowse(cloudAccountId, search, category, pricingModel);

  const categories   = facets.CATEGORY      ?? [];
  const pricingModels = facets.PRICING_MODEL ?? [];

  return (
    <div className="space-y-5">
      {/* Header + search */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-violet-500 to-indigo-600 flex items-center justify-center shadow-sm">
            <i className="fas fa-search text-white text-sm" />
          </div>
          <div>
            <h2 className="font-bold text-gray-800 text-base">Discover Products</h2>
            <p className="text-xs text-gray-400">Browse public SaaS products on AWS Marketplace</p>
          </div>
        </div>
        <div className="flex items-center gap-3 flex-wrap">
          {/* Category filter */}
          {categories.length > 0 && (
            <select
              value={category}
              onChange={e => setCategory(e.target.value)}
              className="py-2 px-3 rounded-xl border border-gray-200 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all text-gray-600"
            >
              <option value="">All Categories</option>
              {categories.map(c => (
                <option key={c.value} value={c.value}>{c.value} ({c.count})</option>
              ))}
            </select>
          )}
          {/* Pricing model filter */}
          {pricingModels.length > 0 && (
            <select
              value={pricingModel}
              onChange={e => setPricingModel(e.target.value)}
              className="py-2 px-3 rounded-xl border border-gray-200 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all text-gray-600"
            >
              <option value="">All Pricing</option>
              {pricingModels.map(p => (
                <option key={p.value} value={p.value}>{p.value} ({p.count})</option>
              ))}
            </select>
          )}
          {/* Search */}
          <div className="relative">
            <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-xs" />
            <input
              type="text"
              value={inputVal}
              onChange={e => handleSearchChange(e.target.value)}
              placeholder="Search products…"
              className="pl-8 pr-4 py-2 rounded-xl border border-gray-200 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-indigo-300 transition-all w-52"
            />
            {inputVal && (
              <button
                onClick={() => { setInputVal(''); setSearch(''); }}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-300 hover:text-gray-500 transition-colors"
              >
                <i className="fas fa-times text-xs" />
              </button>
            )}
          </div>
        </div>
      </div>

      {loading && products.length === 0 && (
        <div className="flex items-center justify-center py-16 gap-3 text-gray-400">
          <i className="fas fa-spinner fa-spin text-xl" />
          <span className="text-sm">Loading catalog from AWS Marketplace…</span>
        </div>
      )}

      {error && (
        <div className="flex items-start gap-3 bg-amber-50 border border-amber-200 rounded-xl px-4 py-3 text-sm text-amber-800">
          <i className="fas fa-info-circle mt-0.5 flex-shrink-0 text-amber-500" />
          <div>
            <p className="font-semibold">Catalog browse unavailable</p>
            <p className="text-xs mt-0.5">{error}</p>
            <p className="text-xs mt-1 text-amber-600">
              Ensure the IAM role has <code className="bg-amber-100 px-1 rounded">aws-marketplace:SearchListings</code> and{' '}
              <code className="bg-amber-100 px-1 rounded">aws-marketplace:SearchFacets</code> permissions.
            </p>
          </div>
        </div>
      )}

      {!loading && !error && products.length === 0 && (
        <div className="text-center py-16 text-gray-400">
          <i className="fas fa-store text-3xl mb-3 block" />
          <p className="text-sm">No products found{search ? ` for "${search}"` : ''}.</p>
        </div>
      )}

      {selectedProduct && (
        <ProductDetailDrawer
          product={selectedProduct}
          cloudAccountId={cloudAccountId}
          onClose={() => setSelectedProduct(null)}
        />
      )}

      {products.length > 0 && (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
            {products.map(p => (
              <CatalogCard key={p.listingId} product={p} onClick={() => setSelectedProduct(p)} />
            ))}
          </div>

          {nextToken && (
            <div className="flex justify-center">
              <button
                onClick={loadMore}
                disabled={loading}
                className="px-6 py-2.5 rounded-xl text-sm font-semibold border border-indigo-200 text-indigo-700 hover:bg-indigo-50 transition-all disabled:opacity-60 flex items-center gap-2"
              >
                {loading
                  ? <><i className="fas fa-spinner fa-spin" />Loading…</>
                  : <><i className="fas fa-chevron-down" />Load More</>}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
};

// ─── Buyer Metered Usage Panel ────────────────────────────────────────────────

const BuyerUsagePanel: React.FC<{ cloudAccountId: string }> = ({ cloudAccountId }) => {
  const { data, loading, error } = useBuyerUsage(cloudAccountId);

  if (loading) return null; // silently loading — don't disrupt the main content
  if (error || data.length === 0) return null; // no subscriptions = nothing to show

  return (
    <div className="rounded-2xl border border-indigo-100 bg-gradient-to-br from-indigo-50/60 to-violet-50/40 p-5 space-y-4">
      <div className="flex items-center gap-2">
        <div className="w-8 h-8 rounded-xl bg-gradient-to-br from-indigo-500 to-violet-600 flex items-center justify-center shadow-sm">
          <i className="fas fa-chart-pie text-white text-xs" />
        </div>
        <div>
          <h3 className="font-bold text-gray-800 text-sm">Your Metered Usage (last 30 days)</h3>
          <p className="text-xs text-gray-400">Usage XamOps reports to AWS Marketplace on your behalf</p>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
        {data.map((sub: BuyerSubscription) => (
          <div key={sub.productCode} className="rounded-xl bg-white/80 border border-indigo-100 p-4">
            <div className="flex items-center justify-between mb-3">
              <div>
                <p className="font-semibold text-gray-800 text-sm truncate">{sub.productName}</p>
                <code className="text-xs text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded mt-0.5 inline-block">
                  {sub.productCode}
                </code>
              </div>
              <span className={`text-xs font-semibold px-2 py-0.5 rounded-full ${
                sub.status === 'ACTIVE' ? 'bg-green-100 text-green-700' :
                sub.status === 'CANCELLED' ? 'bg-amber-100 text-amber-700' :
                'bg-red-100 text-red-600'
              }`}>{sub.status}</span>
            </div>

            {sub.usageLast30Days.length === 0 ? (
              <p className="text-xs text-gray-400 italic">No metered usage in last 30 days</p>
            ) : (
              <div className="space-y-2">
                {sub.usageLast30Days.map(d => (
                  <div key={d.dimension} className="flex items-center justify-between">
                    <code className="text-xs text-indigo-700 bg-indigo-50 px-1.5 py-0.5 rounded">
                      {d.dimension}
                    </code>
                    <span className="text-xs font-semibold text-gray-700">
                      {d.totalQuantity.toLocaleString()}
                    </span>
                  </div>
                ))}
              </div>
            )}

            <p className="text-[10px] text-gray-300 mt-3">
              Period: {new Date(sub.periodStart).toLocaleDateString()} – {new Date(sub.periodEnd).toLocaleDateString()}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
};

// ─── Renewal Expiry Banner ────────────────────────────────────────────────────

const RenewalBanner: React.FC<{ subscriptions: MarketplaceSubscription[] }> = ({ subscriptions }) => {
  const expiring = subscriptions
    .filter(s => s.expiryDate !== null)
    .map(s => ({ ...s, daysLeft: daysUntil(s.expiryDate) }))
    .filter(s => s.daysLeft !== null && s.daysLeft >= 0 && s.daysLeft <= 90)
    .sort((a, b) => (a.daysLeft ?? 0) - (b.daysLeft ?? 0));

  if (expiring.length === 0) return null;

  const critical = expiring.filter(s => (s.daysLeft ?? 99) <= 30);
  const warning  = expiring.filter(s => (s.daysLeft ?? 0) > 30 && (s.daysLeft ?? 0) <= 90);

  const bannerColor = critical.length > 0
    ? 'bg-red-50 border-red-200'
    : 'bg-amber-50 border-amber-200';
  const iconColor   = critical.length > 0 ? 'text-red-500' : 'text-amber-500';
  const textColor   = critical.length > 0 ? 'text-red-800' : 'text-amber-800';

  return (
    <div className={`rounded-2xl border px-5 py-4 ${bannerColor}`}>
      <div className="flex items-start gap-3">
        <i className={`fas fa-calendar-exclamation mt-0.5 flex-shrink-0 ${iconColor}`} />
        <div className="flex-1 min-w-0">
          <p className={`text-sm font-semibold ${textColor}`}>
            {critical.length > 0
              ? `${critical.length} agreement${critical.length > 1 ? 's' : ''} expiring within 30 days`
              : `${warning.length} agreement${warning.length > 1 ? 's' : ''} expiring within 90 days`}
          </p>
          <div className="mt-2 flex flex-wrap gap-2">
            {expiring.map(s => {
              const d = s.daysLeft ?? 0;
              const chipColor = d <= 7
                ? 'bg-red-100 text-red-700 border-red-200'
                : d <= 30
                  ? 'bg-red-50 text-red-600 border-red-100'
                  : 'bg-amber-50 text-amber-700 border-amber-100';
              return (
                <span
                  key={s.productCode}
                  className={`inline-flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full border ${chipColor}`}
                >
                  <i className="fas fa-cube text-[10px]" />
                  {s.productTitle}
                  <span className="font-bold">
                    {d === 0 ? 'expires today' : d === 1 ? 'expires tomorrow' : `${d}d left`}
                  </span>
                </span>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};

// ─── Product Card ─────────────────────────────────────────────────────────────

interface ProductCardProps {
  subscription: MarketplaceSubscription;
  selected: boolean;
  onClick: () => void;
  onPo: () => void;
  onCancel: () => void;
}

const statusColors: Record<string, string> = {
  ACTIVE:   'bg-green-100 text-green-700',
  INACTIVE: 'bg-amber-100 text-amber-700',
  EXPIRED:  'bg-red-100 text-red-600',
};

const ProductCard: React.FC<ProductCardProps> = ({ subscription, selected, onClick, onPo, onCancel }) => {
  const days = daysUntil(subscription.expiryDate);
  const expiryUrgent = days !== null && days >= 0 && days <= 30;

  return (
    <div
      onClick={onClick}
      className={`
        group cursor-pointer rounded-2xl border p-5 transition-all duration-200
        ${selected
          ? 'border-blue-400 bg-blue-50/60 shadow-lg ring-2 ring-blue-200'
          : 'border-gray-100 bg-white/70 hover:border-blue-200 hover:shadow-md hover:-translate-y-0.5'}
      `}
    >
      {/* Icon + status */}
      <div className="flex items-start justify-between mb-4">
        <div className="w-11 h-11 rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center shadow-sm">
          <i className="fas fa-cube text-white text-lg" />
        </div>
        <div className="flex flex-col items-end gap-1">
          <span
            className={`text-xs font-semibold px-2.5 py-1 rounded-full ${
              statusColors[subscription.subscriptionStatus] ?? 'bg-gray-100 text-gray-600'
            }`}
          >
            {subscription.subscriptionStatus}
          </span>
          {subscription.offerType && (
            <span className="text-[10px] font-medium px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-600 border border-indigo-100">
              {offerTypeLabel(subscription.offerType)}
            </span>
          )}
        </div>
      </div>

      {/* Product name + vendor */}
      <h3 className="font-bold text-gray-800 text-base mb-0.5 truncate">
        {subscription.productTitle}
      </h3>
      {subscription.vendorName && (
        <p className="text-xs text-gray-400 mb-1 truncate">
          <i className="fas fa-building mr-1 text-gray-300" />
          {subscription.vendorName}
        </p>
      )}

      {/* Payment term */}
      {subscription.paymentTermSummary && (
        <p className="text-xs text-indigo-600 font-medium truncate mb-1">
          <i className="fas fa-credit-card mr-1 text-indigo-400" />
          {subscription.paymentTermSummary}
        </p>
      )}

      {/* Expiry badge */}
      {days !== null && days >= 0 && (
        <div className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full mb-2 ${
          expiryUrgent
            ? 'bg-red-50 text-red-600 border border-red-100'
            : days <= 90
              ? 'bg-amber-50 text-amber-600 border border-amber-100'
              : 'bg-gray-50 text-gray-400 border border-gray-100'
        }`}>
          <i className="fas fa-calendar-alt text-[10px]" />
          {days === 0 ? 'Expires today' : days === 1 ? 'Expires tomorrow' : `Expires in ${days}d`}
        </div>
      )}

      {/* PO badge */}
      {subscription.poNumber && (
        <p className="text-xs text-gray-500 truncate mb-1">
          <i className="fas fa-file-invoice mr-1 text-gray-300" />
          {subscription.poNumber}
        </p>
      )}

      {/* Cost */}
      <div className="flex items-end justify-between mt-3 pt-3 border-t border-gray-100">
        <div>
          <p className="text-xs text-gray-400 mb-0.5">Total billed</p>
          <p className="font-bold text-gray-800 text-lg">{formatUsd(subscription.totalCostUsd)}</p>
        </div>
        <span className={`text-xs font-medium text-blue-600 transition-all ${
          selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
        }`}>
          <i className="fas fa-chart-bar mr-1" />
          View Details
        </span>
      </div>

      {/* Action buttons */}
      <div className="flex items-center gap-2 mt-3 pt-3 border-t border-gray-50">
        <button
          onClick={e => { e.stopPropagation(); onPo(); }}
          className="flex-1 flex items-center justify-center gap-1.5 text-xs font-semibold py-1.5 rounded-lg border border-indigo-100 text-indigo-600 hover:bg-indigo-50 transition-all"
        >
          <i className="fas fa-file-invoice text-[10px]" />
          {subscription.poNumber ? 'Edit PO' : 'Add PO'}
        </button>
        {subscription.subscriptionStatus === 'ACTIVE' && (
          <button
            onClick={e => { e.stopPropagation(); onCancel(); }}
            className="flex-1 flex items-center justify-center gap-1.5 text-xs font-semibold py-1.5 rounded-lg border border-red-100 text-red-500 hover:bg-red-50 transition-all"
          >
            <i className="fas fa-ban text-[10px]" />
            Cancel
          </button>
        )}
      </div>
    </div>
  );
};

// ─── Main Page ────────────────────────────────────────────────────────────────

type PageTab = 'marketops' | 'discover' | 'isv';

const MarketplacePurchasesPage: React.FC = () => {
  const [pageTab, setPageTab] = useState<PageTab>('marketops');
  const { selectedAccountIds, accounts } = useAccounts();
  const cloudAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const selectedAccount = accounts.find(a => a.id === cloudAccountId);
  const isAwsAccount = selectedAccount?.provider === 'AWS';

  const { data: subscriptions, loading, error, refetch } = useSubscriptions(
    isAwsAccount ? cloudAccountId : null
  );

  const [selectedProduct, setSelectedProduct] = useState<MarketplaceSubscription | null>(null);
  const [syncing, setSyncing]   = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  // Phase 2 modal state
  const [poModalSub, setPoModalSub]         = useState<MarketplaceSubscription | null>(null);
  const [cancelModalSub, setCancelModalSub] = useState<MarketplaceSubscription | null>(null);

  const handleSync = async () => {
    if (!cloudAccountId) return;
    setSyncing(true);
    setSyncError(null);
    try {
      await triggerSync(cloudAccountId);
      setSelectedProduct(null);
      await refetch();
    } catch (e: any) {
      setSyncError(e.message ?? 'Sync failed');
    } finally {
      setSyncing(false);
    }
  };

  const handleProductClick = (sub: MarketplaceSubscription) => {
    setSelectedProduct(prev => (prev?.productCode === sub.productCode ? null : sub));
  };

  const tabCls = (active: boolean) =>
    `px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
      active ? 'bg-white text-indigo-700 shadow-sm' : 'text-gray-500 hover:text-gray-700'
    }`;

  const { setTitle, setActions, clearActions } = useTopbarActions();

  useEffect(() => {
    setTitle('MarketOps');
    setActions(
      <div className="flex items-center gap-3">
        <div className="flex items-center bg-gray-100 rounded-xl p-1 gap-0.5">
          <button onClick={() => setPageTab('marketops')} className={tabCls(pageTab === 'marketops')}>
            <i className="fas fa-store mr-1.5" />My Subscriptions
          </button>
          <button onClick={() => setPageTab('discover')} className={tabCls(pageTab === 'discover')}>
            <i className="fas fa-search mr-1.5" />Discover
          </button>
          <button onClick={() => setPageTab('isv')} className={tabCls(pageTab === 'isv')}>
            <i className="fas fa-plug mr-1.5" />ISV Metering
          </button>
        </div>
        {pageTab === 'marketops' && cloudAccountId && isAwsAccount && (
          <button
            onClick={handleSync}
            disabled={syncing}
            className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-sm hover:shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-60 disabled:cursor-not-allowed"
          >
            <i className={`fas fa-sync-alt ${syncing ? 'animate-spin' : ''}`} />
            {syncing ? 'Syncing…' : 'Sync from AWS'}
          </button>
        )}
      </div>
    );
    return () => {
      clearActions();
      setTitle(null);
    };
  }, [setTitle, setActions, clearActions, pageTab, cloudAccountId, isAwsAccount, syncing, handleSync]);

  // When a PO is saved locally, update the selected product state so detail panel reflects it
  const handlePoSaved = (sub: MarketplaceSubscription, poNumber: string, poNotes: string) => {
    if (selectedProduct?.id === sub.id) {
      setSelectedProduct({ ...selectedProduct, poNumber, poNotes });
    }
    refetch();
  };

  const handleCancelled = () => {
    setSelectedProduct(null);
    refetch();
  };

  return (
    <main className="flex-1 p-6 bg-transparent space-y-6">

      {/* ── Modals (portal-style, rendered at top level) ── */}
      {poModalSub && cloudAccountId && (
        <PoModal
          subscription={poModalSub}
          cloudAccountId={cloudAccountId}
          onClose={() => setPoModalSub(null)}
          onSaved={(poNumber, poNotes) => handlePoSaved(poModalSub, poNumber, poNotes)}
        />
      )}
      {cancelModalSub && cloudAccountId && (
        <CancelModal
          subscription={cancelModalSub}
          cloudAccountId={cloudAccountId}
          onClose={() => setCancelModalSub(null)}
          onCancelled={handleCancelled}
        />
      )}

      {/* ══ ISV Metering Tab ══ */}
      {pageTab === 'isv' && <IsvMeteringTab />}

      {/* ══ Discover Tab ══ */}
      {pageTab === 'discover' && (
        <>
          {!cloudAccountId && (
            <div className="flex flex-col items-center justify-center min-h-64 gap-4 text-gray-400">
              <i className="fas fa-search text-5xl" />
              <p className="text-lg font-medium">Select an account to browse the marketplace</p>
            </div>
          )}
          {cloudAccountId && !isAwsAccount && (
            <div className="flex flex-col items-center justify-center min-h-64 gap-4 text-gray-400">
              <i className="fab fa-aws text-5xl" />
              <p className="text-lg font-medium">Catalog browse is available for AWS accounts only</p>
            </div>
          )}
          {cloudAccountId && isAwsAccount && (
            <DiscoverPanel cloudAccountId={cloudAccountId} />
          )}
        </>
      )}

      {/* ══ Marketplace Purchases Tab ══ */}
      {pageTab === 'marketops' && (
        <>
          {/* No account selected */}
          {!cloudAccountId && (
            <div className="flex flex-col items-center justify-center min-h-64 gap-4 text-gray-400">
              <i className="fas fa-store text-5xl" />
              <p className="text-lg font-medium">Select an account from the sidebar to view MarketOps</p>
            </div>
          )}

          {/* Non-AWS account */}
          {cloudAccountId && !isAwsAccount && (
            <div className="flex flex-col items-center justify-center min-h-64 gap-4 text-gray-400">
              <i className="fab fa-aws text-5xl" />
              <p className="text-lg font-medium">MarketOps is currently available for AWS accounts only</p>
              <p className="text-sm">Select an AWS account from the sidebar to view marketplace subscriptions.</p>
            </div>
          )}

          {/* AWS account — main content */}
          {cloudAccountId && isAwsAccount && (
            <>
              {syncError && (
                <div className="flex items-center gap-2 text-red-600 text-sm bg-red-50 border border-red-100 rounded-xl px-4 py-3">
                  <i className="fas fa-exclamation-circle" />
                  <span>{syncError}</span>
                </div>
              )}

              {loading && (
                <div className="flex items-center justify-center py-20 gap-3 text-gray-400">
                  <i className="fas fa-spinner fa-spin text-2xl" />
                  <span>Loading subscriptions…</span>
                </div>
              )}

              {!loading && error && (
                <div className="rounded-2xl border border-red-100 bg-red-50 p-6 text-center text-red-500">
                  <i className="fas fa-exclamation-triangle text-3xl mb-3 block" />
                  <p className="font-semibold">Failed to load subscriptions</p>
                  <p className="text-sm mt-1">{error}</p>
                  <button
                    onClick={refetch}
                    className="mt-4 px-4 py-2 rounded-xl bg-red-500 text-white text-sm font-semibold hover:bg-red-600 transition-colors"
                  >
                    Retry
                  </button>
                </div>
              )}

              {!loading && !error && subscriptions.length === 0 && (
                <div className="flex flex-col items-center justify-center py-20 gap-4 text-gray-400">
                  <div className="w-16 h-16 rounded-2xl bg-gray-100 flex items-center justify-center">
                    <i className="fas fa-store text-3xl text-gray-300" />
                  </div>
                  <p className="text-base font-medium text-gray-500">No marketplace subscriptions found</p>
                  <p className="text-sm text-gray-400 max-w-sm text-center">
                    Subscribe to products on AWS Marketplace and click{' '}
                    <strong>Sync from AWS</strong> to see them here.
                  </p>
                </div>
              )}

              {!loading && !error && subscriptions.length > 0 && (
                <>
                  {/* Renewal expiry banner */}
                  <RenewalBanner subscriptions={subscriptions} />

                  {/* Summary strip */}
                  {(() => {
                    const directTotal = subscriptions.reduce((sum, s) => sum + s.totalCostUsd, 0);
                    const accountTotal = subscriptions[0]?.accountTotalCostUsd;
                    const showAccountTotal = directTotal === 0 && accountTotal != null && accountTotal > 0;
                    return (
                      <div className="flex items-center gap-6 px-5 py-3 rounded-2xl bg-white/70 border border-gray-100 shadow-sm w-fit flex-wrap">
                        <div className="flex items-center gap-2 text-sm text-gray-600">
                          <i className="fas fa-cube text-blue-500" />
                          <span><strong className="text-gray-800">{subscriptions.length}</strong> products</span>
                        </div>
                        <div className="w-px h-4 bg-gray-200" />
                        <div className="flex items-center gap-2 text-sm text-gray-600">
                          <i className="fas fa-check-circle text-green-500" />
                          <span>
                            <strong className="text-gray-800">
                              {subscriptions.filter(s => s.subscriptionStatus === 'ACTIVE').length}
                            </strong>{' '}active
                          </span>
                        </div>
                        <div className="w-px h-4 bg-gray-200" />
                        {showAccountTotal ? (
                          <div className="flex items-center gap-2 text-sm text-amber-700">
                            <i className="fas fa-layer-group text-amber-500" />
                            <span>
                              <strong className="text-amber-800">{formatUsd(accountTotal)}</strong>
                              {' '}account marketplace spend
                              <span className="ml-1.5 text-xs text-amber-500 font-normal">(shared billing)</span>
                            </span>
                          </div>
                        ) : (
                          <div className="flex items-center gap-2 text-sm text-gray-600">
                            <i className="fas fa-dollar-sign text-indigo-500" />
                            <span>
                              <strong className="text-gray-800">{formatUsd(directTotal)}</strong>{' '}total billed
                            </span>
                          </div>
                        )}
                      </div>
                    );
                  })()}

                  {/* Cards */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                    {subscriptions.map(sub => (
                      <ProductCard
                        key={sub.productCode}
                        subscription={sub}
                        selected={selectedProduct?.productCode === sub.productCode}
                        onClick={() => handleProductClick(sub)}
                        onPo={() => setPoModalSub(sub)}
                        onCancel={() => setCancelModalSub(sub)}
                      />
                    ))}
                  </div>

                  {/* Billing detail panel */}
                  {selectedProduct && cloudAccountId && (
                    <BillingDetailPanel
                      cloudAccountId={cloudAccountId}
                      subscription={selectedProduct}
                      onClose={() => setSelectedProduct(null)}
                      onPo={() => setPoModalSub(selectedProduct)}
                      onCancel={() => setCancelModalSub(selectedProduct)}
                    />
                  )}

                  {/* Buyer metered usage — only shown if this account has ISV subscriptions */}
                  <BuyerUsagePanel cloudAccountId={cloudAccountId} />
                </>
              )}
            </>
          )}
        </>
      )}
    </main>
  );
};

export default MarketplacePurchasesPage;
