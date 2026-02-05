/**
 * BillOps User Dashboard Page
 *
 * Converted from: billops/dashboard_user.html
 *
 * BillOps Command Center - User Dashboard with:
 * - Spending metrics and trends
 * - Cloud accounts overview
 * - Recent invoices and tickets
 * - Charts: Spending Trend (line) and Provider Distribution (doughnut)
 *
 * What stayed the same:
 * - Same API endpoint: /api/billops/dashboard/user
 * - Same data structure and fields
 * - Same caching strategy (localStorage)
 * - Same polling interval (30 seconds)
 * - All business logic preserved
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Using custom hook for data fetching
 * - Chart.js integration via useEffect and refs
 * - Enhanced UI with smoother animations
 */

import React, { useEffect, useRef, useMemo } from 'react';
import { Link } from 'react-router-dom';
import Chart from 'chart.js/auto';
import { useBillOpsUserDashboard } from '../hooks/useBillOpsUserDashboard';
import { useTopbar } from '../contexts/TopbarContext';
import { useCurrency } from '../hooks/useCurrency';
import type { CloudAccount, Invoice, Ticket, SpendingTrendItem } from '../types/billopsUserDashboard';

const BillOpsUserDashboardPage: React.FC = () => {
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

  /**
   * Format currency value
   */
  const formatCurrency = (value: number | null | undefined, provider?: string): string => {
    if (value === null || value === undefined) return '$0.00';

    let finalValue = value;

    // Convert GCP data (assumed INR) to USD
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

  /**
   * Memoize converted data to ensure consistent metrics across the UI
   */
  const convertedData = useMemo(() => {
    if (!data) return null;

    // Convert account spending
    const convertedAccounts = data.accounts.map(account => ({
      ...account,
      currentMonthSpending: account.provider === 'GCP'
        ? (convert(account.currentMonthSpending, 'usd') ?? account.currentMonthSpending)
        : account.currentMonthSpending,
      lastMonthSpending: account.provider === 'GCP'
        ? (convert(account.lastMonthSpending, 'usd') ?? account.lastMonthSpending)
        : account.lastMonthSpending,
    }));

    // Recalculate totals
    const currentMonthSpending = convertedAccounts.reduce((sum, acc) => sum + acc.currentMonthSpending, 0);
    const lastMonthSpending = convertedAccounts.reduce((sum, acc) => sum + acc.lastMonthSpending, 0);

    // Recalculate provider distribution
    const spendingByProvider = {
      AWS: data.spendingByProvider.AWS || 0,
      Azure: data.spendingByProvider.Azure || 0,
      GCP: data.spendingByProvider.GCP ? (convert(data.spendingByProvider.GCP, 'usd') ?? data.spendingByProvider.GCP) : 0
    };

    // Convert spendingTrend using provider breakdown
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

  /**
   * Calculate spending trend percentage
   */
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

  /**
   * Calculate progress bar width based on spending comparison
   */
  const getProgressWidth = (current: number, last: number): string => {
    if (!last || last === 0) return '50%';
    const ratio = Math.min((current / last) * 100, 100);
    return `${ratio}%`;
  };

  /**
   * Render Spending Trend Chart
   */
  useEffect(() => {
    if (!trendChartRef.current || !convertedData?.spendingTrend?.length) return;

    // Destroy existing chart
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

  /**
   * Render Provider Distribution Chart
   */
  useEffect(() => {
    if (!providerChartRef.current || !convertedData?.spendingByProvider) return;

    // Destroy existing chart
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

  /**
   * Get status badge class
   */
  const getStatusClass = (status: string): string => {
    const statusMap: Record<string, string> = {
      ACTIVE: 'billops-status-active',
      PENDING: 'billops-status-pending',
      INACTIVE: 'billops-status-inactive',
      DRAFT: 'billops-status-draft',
      FINALIZED: 'billops-status-finalized',
      PAID: 'billops-status-paid',
      OVERDUE: 'billops-status-overdue',
      OPEN: 'billops-status-open',
      IN_PROGRESS: 'billops-status-in-progress',
      CLOSED: 'billops-status-closed',
    };
    return statusMap[status] || '';
  };

  /**
   * Get severity class
   */
  const getSeverityClass = (severity?: string): string => {
    if (!severity) return '';
    const severityMap: Record<string, string> = {
      LOW: 'billops-severity-low',
      MEDIUM: 'billops-severity-medium',
      HIGH: 'billops-severity-high',
      CRITICAL: 'billops-severity-critical',
    };
    return severityMap[severity] || '';
  };

  /**
   * Get provider icon class
   */
  const getProviderIconClass = (provider: string): string => {
    const providerMap: Record<string, string> = {
      AWS: 'fab fa-aws billops-provider-aws',
      GCP: 'fab fa-google billops-provider-gcp',
      Azure: 'fab fa-microsoft billops-provider-azure',
    };
    return providerMap[provider] || 'fas fa-cloud';
  };

  /**
   * Set Topbar Actions
   */
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {data?.lastUpdated && (
          <div className="text-xs text-slate-500 font-medium hidden md:block">
            Last updated: {new Date(data.lastUpdated).toLocaleString()}
          </div>
        )}
        <button
          onClick={refreshDashboard}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, data?.lastUpdated, isRefreshing, refreshDashboard]);

  return (
    <div className="billops-user-dashboard flex-1 flex flex-col h-full bg-transparent overflow-hidden">

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 space-y-6 billops-scrollbar">
        {/* Error State */}
        {error && (
          <div className="billops-error-alert p-4 rounded-r shadow-sm">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-500 text-xl" />
              <span className="text-red-700 font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* Stats Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {/* Current Month Spending */}
          <div className="billops-glass-card billops-metric-card billops-border-indigo rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                Current Month Spend
              </h3>
              <div className="billops-icon-indigo w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-dollar-sign text-indigo-600" />
              </div>
            </div>
            {!loading ? (
              <div className="billops-fade-in">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-3xl font-black billops-metric-value">
                    {formatCurrency(convertedData?.currentMonthSpending)}
                  </span>
                </div>
                {spendingTrend && (
                  <div className="mt-2 text-xs font-medium text-gray-500 flex items-center gap-1">
                    <i
                      className={`fas ${spendingTrend.isIncrease ? 'fa-arrow-up billops-trend-up' : 'fa-arrow-down billops-trend-down'}`}
                    />
                    <span className={spendingTrend.isIncrease ? 'billops-trend-up' : 'billops-trend-down'}>
                      {spendingTrend.percentage}%
                    </span>
                    <span>from last month</span>
                  </div>
                )}
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-36 bg-gray-200 billops-shimmer rounded-lg" />
                <div className="h-3 w-24 bg-gray-200 billops-shimmer rounded-full" />
              </div>
            )}
          </div>

          {/* Total Accounts */}
          <div className="billops-glass-card billops-metric-card billops-border-purple rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">My Accounts</h3>
              <div className="billops-icon-purple w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-cloud text-purple-600" />
              </div>
            </div>
            {!loading ? (
              <div className="billops-fade-in">
                <div className="flex items-baseline justify-between mb-2">
                  <span className="text-3xl font-black billops-metric-value">
                    {convertedData?.totalAccounts || 0}
                  </span>
                  <div className="billops-provider-stack">
                    <div className="billops-provider-icon" title="AWS">
                      <i className="fab fa-aws billops-provider-aws" />
                    </div>
                    <div className="billops-provider-icon" title="GCP">
                      <i className="fab fa-google billops-provider-gcp" />
                    </div>
                    <div className="billops-provider-icon" title="Azure">
                      <i className="fab fa-microsoft billops-provider-azure" />
                    </div>
                  </div>
                </div>
                <div className="text-xs text-gray-500 mt-2 font-medium">
                  <span>{convertedData?.awsAccountsCount || 0}</span> AWS |{' '}
                  <span>{convertedData?.gcpAccountsCount || 0}</span> GCP |{' '}
                  <span>{convertedData?.azureAccountsCount || 0}</span> Azure
                </div>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-20 bg-gray-200 billops-shimmer rounded-lg" />
                <div className="h-3 w-full bg-gray-200 billops-shimmer rounded-full" />
              </div>
            )}
          </div>

          {/* Pending Invoices */}
          <div className="billops-glass-card billops-metric-card billops-border-blue rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                Pending Invoices
              </h3>
              <div className="billops-icon-blue w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-file-invoice text-blue-600" />
              </div>
            </div>
            {!loading ? (
              <div className="billops-fade-in">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-3xl font-black billops-metric-value">
                    {convertedData?.pendingInvoices || 0}
                  </span>
                </div>
                <p className="text-xs text-blue-600 mt-2 font-medium flex items-center gap-1">
                  <i className="fas fa-clock" /> Action Required
                </p>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-24 bg-gray-200 billops-shimmer rounded-lg" />
                <div className="h-3 w-32 bg-gray-200 billops-shimmer rounded-full" />
              </div>
            )}
          </div>

          {/* Open Tickets */}
          <div className="billops-glass-card billops-metric-card billops-border-orange rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                Open Support Tickets
              </h3>
              <div className="billops-icon-orange w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-ticket-alt text-orange-600" />
              </div>
            </div>
            {!loading ? (
              <div className="billops-fade-in">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-3xl font-black billops-metric-value">
                    {convertedData?.openTickets || 0}
                  </span>
                </div>
                <p className="text-xs text-gray-500 mt-2 font-medium">Active support requests</p>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-24 bg-gray-200 billops-shimmer rounded-lg" />
                <div className="h-3 w-32 bg-gray-200 billops-shimmer rounded-full" />
              </div>
            )}
          </div>
        </div>

        {/* Charts Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Spending Trend Chart */}
          <div className="billops-glass-card billops-border-top-indigo rounded-xl p-6 flex flex-col">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-chart-line text-indigo-600" />
                  Spending Trend
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">Last 6 Months Analysis</p>
              </div>
            </div>
            <div className="billops-chart-container">
              <canvas ref={trendChartRef} />
            </div>
          </div>

          {/* Provider Distribution Chart */}
          <div className="billops-glass-card billops-border-top-purple rounded-xl p-6 flex flex-col">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-chart-pie text-purple-600" />
                  Spending by Provider
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">Distribution Breakdown</p>
              </div>
            </div>
            <div className="billops-chart-container">
              <canvas ref={providerChartRef} />
            </div>
          </div>
        </div>

        {/* My Cloud Accounts */}
        <div className="billops-glass-card billops-border-cyan rounded-xl p-6">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
              <i className="fas fa-server text-cyan-600" />
              My Cloud Accounts
            </h3>
            {convertedData?.accounts && (
              <span className="text-xs font-semibold px-2 py-1 bg-gray-100 rounded-lg text-gray-600">
                Total: {convertedData.accounts.length}
              </span>
            )}
          </div>

          {loading ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-32 bg-gray-100 rounded-xl billops-shimmer animate-pulse" />
              ))}
            </div>
          ) : convertedData?.accounts && convertedData.accounts.length > 0 ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {convertedData.accounts.map((account: CloudAccount) => (
                <div key={account.id} className="billops-account-card rounded-xl p-4 group">
                  <div className="flex items-start justify-between mb-3">
                    <div className="flex-1">
                      <div className="flex items-center gap-2">
                        <i className={getProviderIconClass(account.provider)} />
                        <h4 className="font-bold text-gray-900 text-sm truncate">
                          {account.accountName}
                        </h4>
                      </div>
                    </div>
                    <span
                      className={`px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider rounded-full shadow-sm ${getStatusClass(account.status)}`}
                    >
                      {account.status}
                    </span>
                  </div>
                  <div className="space-y-2 mt-4">
                    <div className="flex justify-between items-center text-sm">
                      <span className="text-gray-500 font-medium text-xs">This Month</span>
                      <span className="font-black text-gray-800">
                        {formatCurrency(account.currentMonthSpending)}
                      </span>
                    </div>
                    <div className="billops-progress-track">
                      <div
                        className="billops-progress-bar"
                        style={{
                          width: getProgressWidth(
                            account.currentMonthSpending,
                            account.lastMonthSpending
                          ),
                        }}
                      />
                    </div>
                    <div className="flex justify-between items-center text-xs">
                      <span className="text-gray-400">Last Month</span>
                      <span className="text-gray-600 font-semibold">
                        {formatCurrency(account.lastMonthSpending)}
                      </span>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="billops-empty-state col-span-3 text-center py-10 text-gray-400 rounded-xl">
              <i className="fas fa-inbox text-4xl mb-3 opacity-50" />
              <p>No accounts connected</p>
            </div>
          )}
        </div>

        {/* Recent Activity Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Recent Invoices */}
          <div className="billops-glass-card rounded-xl p-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-black text-gray-900">Recent Invoices</h3>
              <Link
                to="/billops/invoices"
                className="text-xs font-bold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide"
              >
                View All &rarr;
              </Link>
            </div>
            <div className="space-y-3">
              {convertedData?.recentInvoices && convertedData.recentInvoices.length > 0 ? (
                convertedData.recentInvoices.map((invoice: Invoice) => (
                  <div
                    key={invoice.id}
                    className="billops-list-item flex items-center justify-between p-3 rounded-lg group"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-full bg-indigo-100 text-indigo-600 flex items-center justify-center">
                        <i className="fas fa-file-alt text-xs" />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-gray-900 group-hover:text-indigo-700 transition-colors">
                          {invoice.invoiceNumber}
                        </p>
                        <p className="text-[10px] text-gray-500 uppercase font-semibold">
                          {invoice.billingPeriod}
                        </p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-sm font-black text-gray-900">
                        {formatCurrency(invoice.amount)}
                      </p>
                      <span
                        className={`text-[10px] px-2 py-0.5 rounded-full font-bold uppercase ${getStatusClass(invoice.status)}`}
                      >
                        {invoice.status}
                      </span>
                    </div>
                  </div>
                ))
              ) : (
                <p className="text-center text-gray-400 py-4 text-sm font-medium">
                  No recent invoices found
                </p>
              )}
            </div>
          </div>

          {/* Recent Tickets */}
          <div className="billops-glass-card rounded-xl p-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-black text-gray-900">Recent Tickets</h3>
              <Link
                to="/billops/tickets"
                className="text-xs font-bold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide"
              >
                View All &rarr;
              </Link>
            </div>
            <div className="space-y-3">
              {data?.recentTickets && data.recentTickets.length > 0 ? (
                data.recentTickets.map((ticket: Ticket) => (
                  <div
                    key={ticket.id}
                    className="billops-list-item flex items-center justify-between p-3 rounded-lg group"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-full bg-orange-100 text-orange-600 flex items-center justify-center">
                        <i className="fas fa-life-ring text-xs" />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-gray-900 group-hover:text-indigo-700 transition-colors">
                          {ticket.subject}
                        </p>
                        <p className="text-[10px] text-gray-500 uppercase font-semibold">
                          {ticket.category}
                          {ticket.severity && (
                            <>
                              {' '}
                              &bull;{' '}
                              <span className={getSeverityClass(ticket.severity)}>
                                {ticket.severity}
                              </span>
                            </>
                          )}
                        </p>
                      </div>
                    </div>
                    <div className="text-right">
                      <span
                        className={`text-[10px] px-2 py-0.5 rounded-full font-bold uppercase ${getStatusClass(ticket.status)}`}
                      >
                        {ticket.status.replace('_', ' ')}
                      </span>
                    </div>
                  </div>
                ))
              ) : (
                <p className="text-center text-gray-400 py-4 text-sm font-medium">
                  No recent tickets found
                </p>
              )}
            </div>
          </div>
        </div>
      </main>
    </div>
  );
};

export default BillOpsUserDashboardPage;
