/**
 * MarketOps — Subscribed Marketplace Products & Meter Billing
 *
 * Shows all marketplace product subscriptions for the currently selected
 * cloud account, with detailed meter billing per product:
 *   - Monthly tab: 12-month bar chart + full billing table
 *   - Daily tab:   per-day bar chart for a selected month + non-zero rows table
 */

import React, { useState, useEffect, useMemo } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, Cell,
} from 'recharts';
import { useAccounts } from '../contexts/AccountContext';
import {
  useSubscriptions,
  useMeterBilling,
  useDailyMeterBilling,
  triggerSync,
} from '../hooks/useMarketOps';
import { useBuyerUsage } from '../hooks/useIsv';
import { BuyerSubscription } from '../types/isv';
import { MarketplaceSubscription, DailyMeterBilling } from '../types/marketops';
import IsvMeteringTab from './IsvMeteringPage';

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
}

const BillingDetailPanel: React.FC<BillingDetailProps> = ({
  cloudAccountId,
  subscription,
  onClose,
}) => {
  const [activeTab, setActiveTab] = useState<'monthly' | 'daily'>('monthly');
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
          {/* Monthly / Daily toggle */}
          <div className="flex items-center bg-gray-100 rounded-xl p-1">
            {(['monthly', 'daily'] as const).map(tab => (
              <button
                key={tab}
                onClick={() => setActiveTab(tab)}
                className={`px-3 py-1 rounded-lg text-xs font-semibold capitalize transition-all ${
                  activeTab === tab
                    ? 'bg-white text-indigo-700 shadow-sm'
                    : 'text-gray-500 hover:text-gray-700'
                }`}
              >
                {tab}
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

      </div>
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

// ─── Product Card ─────────────────────────────────────────────────────────────

interface ProductCardProps {
  subscription: MarketplaceSubscription;
  selected: boolean;
  onClick: () => void;
}

const statusColors: Record<string, string> = {
  ACTIVE:   'bg-green-100 text-green-700',
  INACTIVE: 'bg-amber-100 text-amber-700',
  EXPIRED:  'bg-red-100 text-red-600',
};

const ProductCard: React.FC<ProductCardProps> = ({ subscription, selected, onClick }) => (
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
      <span
        className={`text-xs font-semibold px-2.5 py-1 rounded-full ${
          statusColors[subscription.subscriptionStatus] ?? 'bg-gray-100 text-gray-600'
        }`}
      >
        {subscription.subscriptionStatus}
      </span>
    </div>

    {/* Product name */}
    <h3 className="font-bold text-gray-800 text-base mb-0.5 truncate">
      {subscription.productTitle}
    </h3>
    {subscription.vendorName && (
      <p className="text-xs text-gray-400 mb-3 truncate">{subscription.vendorName}</p>
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
        View Billing
      </span>
    </div>
  </div>
);

// ─── Main Page ────────────────────────────────────────────────────────────────

type PageTab = 'marketops' | 'isv';

const MarketplacePurchasesPage: React.FC = () => {
  const [pageTab, setPageTab] = useState<PageTab>('marketops');
  const { selectedAccountIds, selectedAccountDisplay, accounts } = useAccounts();
  const cloudAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const selectedAccount = accounts.find(a => a.id === cloudAccountId);
  const isAwsAccount = selectedAccount?.provider === 'AWS';

  const { data: subscriptions, loading, error, refetch } = useSubscriptions(
    isAwsAccount ? cloudAccountId : null
  );

  const [selectedProduct, setSelectedProduct] = useState<MarketplaceSubscription | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

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

  return (
    <main className="flex-1 p-6 bg-transparent space-y-6">

      {/* ── Page Header ── */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">MarketOps</h1>
          <p className="text-sm text-gray-500 mt-0.5">
            {pageTab === 'isv'
              ? 'Register and manage ISV products for metered billing on AWS Marketplace'
              : <>Subscribed marketplace products for{' '}
                  <span className="font-semibold text-gray-700">
                    {selectedAccountDisplay?.name ?? cloudAccountId ?? 'no account selected'}
                  </span>
                </>
            }
          </p>
        </div>

        <div className="flex items-center gap-3">
          {/* Page-level tab toggle */}
          <div className="flex items-center bg-gray-100 rounded-xl p-1 gap-0.5">
            <button onClick={() => setPageTab('marketops')} className={tabCls(pageTab === 'marketops')}>
              <i className="fas fa-store mr-1.5" />Marketplace Purchases
            </button>
            <button onClick={() => setPageTab('isv')} className={tabCls(pageTab === 'isv')}>
              <i className="fas fa-plug mr-1.5" />ISV Metering
            </button>
          </div>

          {/* Sync button — marketops tab + AWS account only */}
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
      </div>

      {/* ══ ISV Metering Tab ══ */}
      {pageTab === 'isv' && <IsvMeteringTab />}

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
                      />
                    ))}
                  </div>

                  {/* Billing detail panel */}
                  {selectedProduct && cloudAccountId && (
                    <BillingDetailPanel
                      cloudAccountId={cloudAccountId}
                      subscription={selectedProduct}
                      onClose={() => setSelectedProduct(null)}
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
