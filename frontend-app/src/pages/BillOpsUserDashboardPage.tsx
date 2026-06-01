import React, { useEffect, useRef, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '@iconify/react';
import Chart from 'chart.js/auto';
import { useBillOpsUserDashboard } from '../hooks/useBillOpsUserDashboard';
import { useTopbar } from '../contexts/TopbarContext';
import { useCurrency } from '../hooks/useCurrency';
import { useAccounts } from '../contexts/AccountContext';
import type { CloudAccount, Invoice, Ticket, SpendingTrendItem } from '../types/billopsUserDashboard';

const STATUS_BADGE: Record<string, string> = {
  ACTIVE: 'bg-emerald-100 text-emerald-700',
  PENDING: 'bg-amber-100 text-amber-700',
  INACTIVE: 'bg-gray-100 text-gray-500',
  DRAFT: 'bg-gray-100 text-gray-500',
  FINALIZED: 'bg-blue-100 text-blue-700',
  PAID: 'bg-emerald-100 text-emerald-700',
  OVERDUE: 'bg-red-100 text-red-700',
  OPEN: 'bg-emerald-100 text-emerald-700',
  IN_PROGRESS: 'bg-blue-100 text-blue-700',
  CLOSED: 'bg-gray-100 text-gray-500',
};

const SEVERITY_COLOR: Record<string, string> = {
  LOW: 'text-blue-600',
  MEDIUM: 'text-amber-600',
  HIGH: 'text-red-600',
  CRITICAL: 'text-red-700',
};

const PROVIDER_ICON: Record<string, string> = {
  AWS: 'simple-icons:amazonaws',
  GCP: 'simple-icons:googlecloud',
  Azure: 'simple-icons:microsoftazure',
};

const PROVIDER_COLOR: Record<string, string> = {
  AWS: 'text-[#FF9900]',
  GCP: 'text-[#34A853]',
  Azure: 'text-[#0078D4]',
};

const CloudSVG = ({ className = 'text-indigo-400' }: { className?: string }) => (
  <svg className={`absolute right-2 bottom-2 opacity-[0.07] ${className}`} width="80" height="60" viewBox="0 0 80 60">
    <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
  </svg>
);

const BEBAS: React.CSSProperties = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  color: 'rgb(10,10,10)',
  fontVariantNumeric: 'tabular-nums',
};

const BillOpsUserDashboardPage: React.FC = () => {
  const { accounts, isLoading: accountsLoading } = useAccounts();
  const hasAccounts = accounts.length > 0;

  const { data, loading, isRefreshing, error, refreshDashboard } = useBillOpsUserDashboard();
  const { setActions, clearActions } = useTopbar();
  const { loadRates, convert } = useCurrency();

  // Chart refs
  const trendChartRef = useRef<HTMLCanvasElement>(null);
  const providerChartRef = useRef<HTMLCanvasElement>(null);
  const trendChartInstance = useRef<Chart<'line'> | null>(null);
  const providerChartInstance = useRef<Chart<'doughnut'> | null>(null);

  // Load currency rates (GCP data is in INR and needs conversion to USD)
  useEffect(() => {
    loadRates('inr');
  }, [loadRates]);

  const formatCurrency = (value: number | null | undefined, provider?: string): string => {
    if (value === null || value === undefined) return '$0.00';

    let finalValue = value;

    if (provider === 'GCP') {
      const converted = convert(value, 'usd');
      if (converted !== null) {
        finalValue = converted;
      }
    }

    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(finalValue);
  };

  const convertedData = useMemo(() => {
    if (!data) return null;

    const convertedAccounts = data.accounts.map(account => ({
      ...account,
      currentMonthSpending: account.provider === 'GCP'
        ? (convert(account.currentMonthSpending, 'usd') ?? account.currentMonthSpending)
        : account.currentMonthSpending,
      lastMonthSpending: account.provider === 'GCP'
        ? (convert(account.lastMonthSpending, 'usd') ?? account.lastMonthSpending)
        : account.lastMonthSpending,
    }));

    const currentMonthSpending = convertedAccounts.reduce((sum, acc) => sum + acc.currentMonthSpending, 0);
    const lastMonthSpending = convertedAccounts.reduce((sum, acc) => sum + acc.lastMonthSpending, 0);

    const spendingByProvider = {
      AWS: data.spendingByProvider.AWS || 0,
      Azure: data.spendingByProvider.Azure || 0,
      GCP: data.spendingByProvider.GCP ? (convert(data.spendingByProvider.GCP, 'usd') ?? data.spendingByProvider.GCP) : 0
    };

    const convertedSpendingTrend = data.spendingTrend.map(item => {
      if (!item.costByProvider) return item;

      const aws = item.costByProvider.AWS || 0;
      const azure = item.costByProvider.Azure || 0;
      const gcpRaw = item.costByProvider.GCP || 0;
      const gcpConverted = convert(gcpRaw, 'usd') ?? gcpRaw;

      return {
        ...item,
        cost: aws + azure + gcpConverted,
        costByProvider: {
          AWS: aws,
          Azure: azure,
          GCP: gcpConverted
        }
      };
    });

    return {
      ...data,
      accounts: convertedAccounts,
      currentMonthSpending,
      lastMonthSpending,
      spendingByProvider,
      spendingTrend: convertedSpendingTrend
    };
  }, [data, convert]);

  const spendingTrend = useMemo(() => {
    if (!convertedData?.currentMonthSpending || !convertedData?.lastMonthSpending) {
      return null;
    }

    const current = convertedData.currentMonthSpending || 0;
    const last = convertedData.lastMonthSpending || 0;

    if (last === 0) return null;

    const change = ((current - last) / last) * 100;
    return {
      percentage: Math.abs(change).toFixed(1),
      isIncrease: current > last,
    };
  }, [convertedData?.currentMonthSpending, convertedData?.lastMonthSpending]);

  const getProgressWidth = (current: number, last: number): string => {
    if (!last || last === 0) return '50%';
    const ratio = Math.min((current / last) * 100, 100);
    return `${ratio}%`;
  };

  // Spending Trend Chart
  useEffect(() => {
    if (!trendChartRef.current || !convertedData?.spendingTrend?.length) return;

    if (trendChartInstance.current) {
      trendChartInstance.current.destroy();
    }

    const ctx = trendChartRef.current.getContext('2d');
    if (!ctx) return;

    const labels = convertedData.spendingTrend.map((item: SpendingTrendItem) => item.date);
    const values = convertedData.spendingTrend.map((item: SpendingTrendItem) => item.cost);

    trendChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: 'Spending',
            data: values,
            borderColor: '#6366f1',
            backgroundColor: (context) => {
              const chart = context.chart;
              const { ctx: chartCtx, chartArea } = chart;
              if (!chartArea) return 'rgba(99, 102, 241, 0.1)';
              const gradient = chartCtx.createLinearGradient(0, chartArea.top, 0, chartArea.bottom);
              gradient.addColorStop(0, 'rgba(99, 102, 241, 0.4)');
              gradient.addColorStop(1, 'rgba(99, 102, 241, 0.0)');
              return gradient;
            },
            borderWidth: 3,
            fill: true,
            tension: 0.4,
            pointBackgroundColor: '#ffffff',
            pointBorderColor: '#6366f1',
            pointBorderWidth: 2,
            pointRadius: 4,
            pointHoverRadius: 6,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            titleColor: '#1f2937',
            bodyColor: '#1f2937',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 10,
            titleFont: { weight: 'bold' },
            displayColors: false,
            callbacks: {
              label: (context) => `Spending: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            grid: { color: '#f3f4f6' },
            ticks: {
              font: { family: 'Inter' },
              color: '#9ca3af',
              callback: (value) => {
                const num = Number(value);
                if (num >= 1000) return '$' + (num / 1000).toFixed(1) + 'k';
                return '$' + num;
              },
            },
          },
          x: {
            grid: { display: false },
            ticks: { font: { family: 'Inter' }, color: '#9ca3af' },
          },
        },
      },
    });

    return () => {
      if (trendChartInstance.current) {
        trendChartInstance.current.destroy();
      }
    };
  }, [convertedData?.spendingTrend]);

  // Provider Distribution Chart
  useEffect(() => {
    if (!providerChartRef.current || !convertedData?.spendingByProvider) return;

    if (providerChartInstance.current) {
      providerChartInstance.current.destroy();
    }

    const ctx = providerChartRef.current.getContext('2d');
    if (!ctx) return;

    const spendingData = convertedData.spendingByProvider;
    const labels = Object.keys(spendingData);
    const values = Object.values(spendingData);

    if (labels.length === 0 || values.every((v) => v === 0)) return;

    providerChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels,
        datasets: [
          {
            data: values,
            backgroundColor: ['#FF9900', '#34A853', '#0078D4'],
            borderWidth: 0,
            hoverOffset: 4,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        cutout: '75%',
        plugins: {
          legend: {
            position: 'right',
            labels: {
              usePointStyle: true,
              padding: 20,
              font: { family: 'Inter', weight: '500' },
            },
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            bodyColor: '#1f2937',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 10,
            callbacks: {
              label: (context) => `${context.label}: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
      },
    });

    return () => {
      if (providerChartInstance.current) {
        providerChartInstance.current.destroy();
      }
    };
  }, [convertedData?.spendingByProvider]);

  // Topbar Actions
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {data?.lastUpdated && (
          <div className="text-xs text-[rgba(0,0,0,0.5)] font-medium hidden md:block">
            Last updated: {new Date(data.lastUpdated).toLocaleString()}
          </div>
        )}
        <button
          onClick={refreshDashboard}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, data?.lastUpdated, isRefreshing, refreshDashboard]);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen space-y-6">

      {/* No Accounts Empty State */}
      {!accountsLoading && !hasAccounts && (
        <div className="text-center py-16 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm">
          <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:power-plug-outline" width={28} className="text-amber-500" />
          </div>
          <h3 className="text-base font-bold text-[rgba(0,0,0,0.88)] mb-1">No Accounts Connected</h3>
          <p className="text-sm text-[rgba(0,0,0,0.5)] max-w-sm mx-auto mb-5">
            Connect a cloud account to view your dashboard.
          </p>
          <a
            href="/account-manager"
            className="inline-flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white rounded-md text-[12px] font-medium hover:bg-black transition-colors"
          >
            <Icon icon="mdi:plus" width={14} />
            Add Account
          </a>
        </div>
      )}

      {/* Error State */}
      {(accountsLoading || hasAccounts) && error && (
        <div className="bg-red-50 border border-red-100 rounded-xl p-4">
          <div className="flex items-center gap-3">
            <Icon icon="mdi:alert-circle-outline" width={20} className="text-red-500" />
            <span className="text-red-700 font-medium text-sm">{error}</span>
          </div>
        </div>
      )}

      {/* Main dashboard content — only when accounts exist */}
      {(accountsLoading || hasAccounts) && <>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">

        {/* Current Month Spend */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
          <CloudSVG className="text-indigo-400" />
          <div className="flex items-center justify-between mb-3">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
              Current Month Spend
            </span>
            <div className="w-8 h-8 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
              <Icon icon="mdi:currency-usd" width={16} className="text-indigo-500" />
            </div>
          </div>
          {!loading ? (
            <>
              <div style={BEBAS}>{formatCurrency(convertedData?.currentMonthSpending)}</div>
              {spendingTrend && (
                <div className="mt-2 text-xs font-medium text-[rgba(0,0,0,0.5)] flex items-center gap-1">
                  <Icon
                    icon={spendingTrend.isIncrease ? 'mdi:arrow-up' : 'mdi:arrow-down'}
                    width={12}
                    className={spendingTrend.isIncrease ? 'text-red-500' : 'text-emerald-500'}
                  />
                  <span className={spendingTrend.isIncrease ? 'text-red-500' : 'text-emerald-500'}>
                    {spendingTrend.percentage}%
                  </span>
                  <span>from last month</span>
                </div>
              )}
            </>
          ) : (
            <div className="animate-pulse space-y-2">
              <div className="h-8 w-36 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
              <div className="h-3 w-24 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
            </div>
          )}
        </div>

        {/* My Accounts */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
          <CloudSVG className="text-purple-400" />
          <div className="flex items-center justify-between mb-3">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
              My Accounts
            </span>
            <div className="w-8 h-8 rounded-lg bg-purple-50 border border-purple-100 flex items-center justify-center">
              <Icon icon="mdi:cloud-outline" width={16} className="text-purple-500" />
            </div>
          </div>
          {!loading ? (
            <>
              <div style={BEBAS}>{convertedData?.totalAccounts || 0}</div>
              <div className="mt-2 flex items-center gap-2">
                <span className="flex items-center gap-1 text-xs text-[rgba(0,0,0,0.5)]">
                  <Icon icon="simple-icons:amazonaws" width={11} className="text-[#FF9900]" />
                  {convertedData?.awsAccountsCount || 0}
                </span>
                <span className="flex items-center gap-1 text-xs text-[rgba(0,0,0,0.5)]">
                  <Icon icon="simple-icons:googlecloud" width={11} className="text-[#34A853]" />
                  {convertedData?.gcpAccountsCount || 0}
                </span>
                <span className="flex items-center gap-1 text-xs text-[rgba(0,0,0,0.5)]">
                  <Icon icon="simple-icons:microsoftazure" width={11} className="text-[#0078D4]" />
                  {convertedData?.azureAccountsCount || 0}
                </span>
              </div>
            </>
          ) : (
            <div className="animate-pulse space-y-2">
              <div className="h-8 w-20 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
              <div className="h-3 w-full bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
            </div>
          )}
        </div>

        {/* Pending Invoices */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
          <CloudSVG className="text-blue-400" />
          <div className="flex items-center justify-between mb-3">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
              Pending Invoices
            </span>
            <div className="w-8 h-8 rounded-lg bg-blue-50 border border-blue-100 flex items-center justify-center">
              <Icon icon="mdi:file-document-outline" width={16} className="text-blue-500" />
            </div>
          </div>
          {!loading ? (
            <>
              <div style={BEBAS}>{convertedData?.pendingInvoices || 0}</div>
              <div className="mt-2 flex items-center gap-1 text-xs text-blue-600 font-medium">
                <Icon icon="mdi:clock-outline" width={12} />
                <span>Action Required</span>
              </div>
            </>
          ) : (
            <div className="animate-pulse space-y-2">
              <div className="h-8 w-24 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
              <div className="h-3 w-32 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
            </div>
          )}
        </div>

        {/* Open Support Tickets */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden">
          <CloudSVG className="text-orange-400" />
          <div className="flex items-center justify-between mb-3">
            <span className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">
              Open Support Tickets
            </span>
            <div className="w-8 h-8 rounded-lg bg-orange-50 border border-orange-100 flex items-center justify-center">
              <Icon icon="mdi:ticket-outline" width={16} className="text-orange-500" />
            </div>
          </div>
          {!loading ? (
            <>
              <div style={BEBAS}>{convertedData?.openTickets || 0}</div>
              <p className="mt-2 text-xs text-[rgba(0,0,0,0.4)] font-medium">Active support requests</p>
            </>
          ) : (
            <div className="animate-pulse space-y-2">
              <div className="h-8 w-24 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
              <div className="h-3 w-32 bg-[rgba(0,0,0,0.06)] rounded animate-pulse" />
            </div>
          )}
        </div>
      </div>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Spending Trend Chart */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col">
          <div className="flex items-center gap-2 mb-1">
            <Icon icon="mdi:chart-line" width={16} className="text-indigo-500" />
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Spending Trend</span>
          </div>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mb-4">Last 6 Months Analysis</p>
          <div className="relative h-52">
            <canvas ref={trendChartRef} />
          </div>
        </div>

        {/* Provider Distribution Chart */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 flex flex-col">
          <div className="flex items-center gap-2 mb-1">
            <Icon icon="mdi:chart-pie" width={16} className="text-purple-500" />
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Spending by Provider</span>
          </div>
          <p className="text-xs text-[rgba(0,0,0,0.4)] mb-4">Distribution Breakdown</p>
          <div className="relative h-52">
            <canvas ref={providerChartRef} />
          </div>
        </div>
      </div>

      {/* My Cloud Accounts */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:server-outline" width={16} className="text-[rgba(0,0,0,0.5)]" />
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">My Cloud Accounts</span>
          </div>
          {convertedData?.accounts && (
            <span className="text-xs font-semibold px-2 py-0.5 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded text-[rgba(0,0,0,0.5)]">
              {convertedData.accounts.length} total
            </span>
          )}
        </div>

        {loading ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-32 bg-[rgba(0,0,0,0.04)] rounded-xl animate-pulse" />
            ))}
          </div>
        ) : convertedData?.accounts && convertedData.accounts.length > 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {convertedData.accounts.map((account: CloudAccount) => (
              <div key={account.id} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl p-4 hover:shadow-sm transition-shadow">
                <div className="flex items-start justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <Icon
                      icon={PROVIDER_ICON[account.provider] ?? 'mdi:cloud-outline'}
                      width={16}
                      className={PROVIDER_COLOR[account.provider] ?? 'text-[rgba(0,0,0,0.5)]'}
                    />
                    <span className="font-semibold text-[rgba(0,0,0,0.88)] text-sm truncate">
                      {account.accountName}
                    </span>
                  </div>
                  <span className={`px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider rounded-full ${STATUS_BADGE[account.status] ?? 'bg-gray-100 text-gray-500'}`}>
                    {account.status}
                  </span>
                </div>
                <div className="space-y-2 mt-3">
                  <div className="flex justify-between items-center">
                    <span className="text-xs text-[rgba(0,0,0,0.4)]">This Month</span>
                    <span className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
                      {formatCurrency(account.currentMonthSpending)}
                    </span>
                  </div>
                  <div className="w-full h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-indigo-500 rounded-full transition-all"
                      style={{ width: getProgressWidth(account.currentMonthSpending, account.lastMonthSpending) }}
                    />
                  </div>
                  <div className="flex justify-between items-center">
                    <span className="text-xs text-[rgba(0,0,0,0.35)]">Last Month</span>
                    <span className="text-xs text-[rgba(0,0,0,0.5)] font-semibold">
                      {formatCurrency(account.lastMonthSpending)}
                    </span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center py-10">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
              <Icon icon="mdi:inbox-outline" width={24} className="text-[rgba(0,0,0,0.3)]" />
            </div>
            <p className="text-sm text-[rgba(0,0,0,0.4)]">No accounts connected</p>
          </div>
        )}
      </div>

      {/* Recent Activity Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">

        {/* Recent Invoices */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
          <div className="flex items-center justify-between mb-4">
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Recent Invoices</span>
            <Link
              to="/billops-invoices"
              className="text-xs font-semibold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide"
            >
              View All →
            </Link>
          </div>
          <div>
            {convertedData?.recentInvoices && convertedData.recentInvoices.length > 0 ? (
              convertedData.recentInvoices.map((invoice: Invoice) => (
                <div
                  key={invoice.id}
                  className="flex items-center justify-between px-3 py-2.5 border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors rounded group"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
                      <Icon icon="mdi:file-document-outline" width={14} className="text-indigo-500" />
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-[rgba(0,0,0,0.88)] group-hover:text-indigo-700 transition-colors">
                        {invoice.invoiceNumber}
                      </p>
                      <p className="text-[10px] text-[rgba(0,0,0,0.4)] uppercase font-semibold">
                        {invoice.billingPeriod}
                      </p>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-bold text-[rgba(0,0,0,0.88)]">
                      {formatCurrency(invoice.amount)}
                    </p>
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold uppercase ${STATUS_BADGE[invoice.status] ?? 'bg-gray-100 text-gray-500'}`}>
                      {invoice.status}
                    </span>
                  </div>
                </div>
              ))
            ) : (
              <p className="text-center text-[rgba(0,0,0,0.4)] py-6 text-sm">
                No recent invoices found
              </p>
            )}
          </div>
        </div>

        {/* Recent Tickets */}
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5">
          <div className="flex items-center justify-between mb-4">
            <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Recent Tickets</span>
            <Link
              to="/billops-tickets"
              className="text-xs font-semibold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide"
            >
              View All →
            </Link>
          </div>
          <div>
            {data?.recentTickets && data.recentTickets.length > 0 ? (
              data.recentTickets.map((ticket: Ticket) => (
                <div
                  key={ticket.id}
                  className="flex items-center justify-between px-3 py-2.5 border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors rounded group"
                >
                  <div className="flex items-center gap-3">
                    <div className="w-8 h-8 rounded-lg bg-orange-50 border border-orange-100 flex items-center justify-center">
                      <Icon icon="mdi:lifebuoy" width={14} className="text-orange-500" />
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-[rgba(0,0,0,0.88)] group-hover:text-indigo-700 transition-colors">
                        {ticket.subject}
                      </p>
                      <p className="text-[10px] text-[rgba(0,0,0,0.4)] uppercase font-semibold">
                        {ticket.category}
                        {ticket.severity && (
                          <>
                            {' · '}
                            <span className={SEVERITY_COLOR[ticket.severity] ?? 'text-[rgba(0,0,0,0.5)]'}>
                              {ticket.severity}
                            </span>
                          </>
                        )}
                      </p>
                    </div>
                  </div>
                  <div className="text-right">
                    <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold uppercase ${STATUS_BADGE[ticket.status] ?? 'bg-gray-100 text-gray-500'}`}>
                      {ticket.status.replace('_', ' ')}
                    </span>
                  </div>
                </div>
              ))
            ) : (
              <p className="text-center text-[rgba(0,0,0,0.4)] py-6 text-sm">
                No recent tickets found
              </p>
            )}
          </div>
        </div>
      </div>

      </> /* end accountsLoading || hasAccounts */
      }
    </main>
  );
};

export default BillOpsUserDashboardPage;
