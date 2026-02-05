/**
 * BillOps Admin Dashboard Page
 *
 * Converted from: billops/dashboard_admin.html
 *
 * BillOps Command Center - Admin Dashboard with:
 * - System-wide metrics (total spending, accounts, invoices, tickets)
 * - Top 10 spending accounts table
 * - Recent invoices and tickets
 * - Charts: Provider Distribution (doughnut) and Account Status (bar)
 *
 * What stayed the same:
 * - Same API endpoint: /api/billops/dashboard/admin
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

import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Chart from 'chart.js/auto';
import { useBillOpsAdminDashboard } from '../hooks/useBillOpsAdminDashboard';
import { useBillOpsAdminNotifications } from '../hooks/useBillOpsAdminNotifications';
import { useTopbar } from '../contexts/TopbarContext';
import type { TopSpendingAccount, AdminInvoice, AdminTicket, SpendingTrendItem } from '../types/billopsAdminDashboard';
import BillOpsNotificationDropdown from '../components/BillOps/BillOpsNotificationDropdown';

import { useCurrency } from '../hooks/useCurrency';

const BillOpsAdminDashboardPage: React.FC = () => {
  const { data, loading, isRefreshing, error, refreshDashboard } = useBillOpsAdminDashboard();
  const { notifications, counts, isLoading: notificationsLoading } = useBillOpsAdminNotifications();
  const { setActions, clearActions } = useTopbar();
  const { loadRates, convert } = useCurrency();

  // State for notification dropdown
  const [isNotificationOpen, setIsNotificationOpen] = useState(false);
  const notificationRef = useRef<HTMLDivElement>(null);

  // Chart refs
  const providerChartRef = useRef<HTMLCanvasElement>(null);
  const trendChartRef = useRef<HTMLCanvasElement>(null);
  const providerChartInstance = useRef<Chart<'doughnut'> | null>(null);
  const trendChartInstance = useRef<Chart<'line'> | null>(null);

  // Load currency rates (Assuming GCP data is in INR and needs conversion to USD)
  useEffect(() => {
    loadRates('inr');
  }, [loadRates]);

  /**
   * Memoized converted data to ensure consistent USD presentation
   */
  const convertedData = React.useMemo(() => {
    if (!data) return null;

    // Convert top accounts spending
    const convertedTopAccounts = data.topSpendingAccounts.map(account => ({
      ...account,
      currentMonthSpending: account.provider === 'GCP'
        ? (convert(account.currentMonthSpending, 'usd') ?? account.currentMonthSpending)
        : account.currentMonthSpending,
      lastMonthSpending: account.provider === 'GCP'
        ? (convert(account.lastMonthSpending, 'usd') ?? account.lastMonthSpending)
        : account.lastMonthSpending,
    }));

    // Recalculate provider distribution
    const spendingByProvider = {
      AWS: data.spendingByProvider.AWS || 0,
      Azure: data.spendingByProvider.Azure || 0,
      GCP: data.spendingByProvider.GCP ? (convert(data.spendingByProvider.GCP, 'usd') ?? data.spendingByProvider.GCP) : 0
    };

    // Recalculate total spending
    const totalSpending = Object.values(spendingByProvider).reduce((sum, val) => sum + (val || 0), 0);

    // Convert spendingTrend if it exists
    const convertedSpendingTrend = data.spendingTrend?.map(item => {
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
    }) || [];

    // Convert recent invoices
    const convertedRecentInvoices = data.recentInvoices.map(invoice => ({
      ...invoice,
      amount: invoice.provider === 'GCP'
        ? (convert(invoice.amount, 'usd') ?? invoice.amount)
        : invoice.amount
    }));

    return {
      ...data,
      topSpendingAccounts: convertedTopAccounts,
      totalSpending,
      spendingByProvider,
      spendingTrend: convertedSpendingTrend,
      recentInvoices: convertedRecentInvoices
    };
  }, [data, convert]);

  /**
   * Format currency value
   */
  const formatCurrency = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '$0.00';
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  };

  /**
   * Render Provider Distribution Chart (Doughnut)
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
            label: 'Monthly Spending (USD)',
            data: values,
            borderColor: '#6366f1',
            backgroundColor: 'rgba(99, 102, 241, 0.1)',
            borderWidth: 3,
            fill: true,
            tension: 0.4,
            pointBackgroundColor: '#fff',
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
          legend: {
            display: false,
          },
          tooltip: {
            backgroundColor: 'rgba(255, 255, 255, 0.95)',
            bodyColor: '#1f2937',
            titleColor: '#111827',
            borderColor: '#e5e7eb',
            borderWidth: 1,
            padding: 12,
            displayColors: false,
            callbacks: {
              label: (context) => `Spend: ${formatCurrency(context.raw as number)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: {
              callback: (value) => `$${Number(value).toLocaleString()}`,
              font: { family: 'Inter' },
            },
            grid: {
              color: '#f3f4f6',
            },
          },
          x: {
            grid: {
              display: false,
            },
            ticks: {
              font: { family: 'Inter' },
            },
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
   * Get status badge class
   */
  const getStatusClass = (status: string): string => {
    const statusMap: Record<string, string> = {
      ACTIVE: 'admin-status-active',
      PENDING: 'admin-status-pending',
      INACTIVE: 'admin-status-inactive',
      DRAFT: 'admin-status-draft',
      FINALIZED: 'admin-status-finalized',
      PAID: 'admin-status-paid',
      OVERDUE: 'admin-status-overdue',
      OPEN: 'admin-status-open',
      IN_PROGRESS: 'admin-status-in-progress',
      CLOSED: 'admin-status-closed',
    };
    return statusMap[status] || '';
  };

  /**
   * Get severity class
   */
  const getSeverityClass = (severity?: string): string => {
    if (!severity) return '';
    const severityMap: Record<string, string> = {
      LOW: 'admin-severity-low',
      MEDIUM: 'admin-severity-medium',
      HIGH: 'admin-severity-high',
      CRITICAL: 'admin-severity-critical',
    };
    return severityMap[severity] || '';
  };

  /**
   * Get provider icon class
   */
  const getProviderIconClass = (provider: string): string => {
    const providerMap: Record<string, string> = {
      AWS: 'fab fa-aws admin-provider-aws',
      GCP: 'fab fa-google admin-provider-gcp',
      Azure: 'fab fa-microsoft admin-provider-azure',
    };
    return providerMap[provider] || 'fas fa-cloud';
  };

  /**
   * Close notification dropdown when clicking outside
   */
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (notificationRef.current && !notificationRef.current.contains(event.target as Node)) {
        setIsNotificationOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

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

        {/* Notification Bell - Only for Tickets and Chat */}
        <div className="relative" ref={notificationRef}>
          <button
            onClick={() => setIsNotificationOpen(!isNotificationOpen)}
            className="w-10 h-10 flex items-center justify-center rounded-xl bg-slate-100/50 text-slate-500 hover:bg-indigo-50 hover:text-indigo-600 transition-all relative"
            title="Notifications (Tickets & Messages)"
          >
            <i className="fas fa-bell"></i>
            {counts.total > 0 && (
              <span className="absolute -top-1 -right-1 min-w-[18px] h-[18px] flex items-center justify-center px-1 bg-gradient-to-br from-orange-500 to-red-500 text-white text-[10px] font-bold rounded-full border-2 border-white shadow-lg">
                {counts.total}
              </span>
            )}
          </button>

          {isNotificationOpen && (
            <BillOpsNotificationDropdown
              notifications={notifications}
              counts={counts}
              isLoading={notificationsLoading}
              onClose={() => setIsNotificationOpen(false)}
            />
          )}
        </div>

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
  }, [setActions, clearActions, data?.lastUpdated, isRefreshing, refreshDashboard, notifications, counts, notificationsLoading, isNotificationOpen]);

  return (
    <div className="admin-dashboard flex-1 flex flex-col h-full bg-transparent overflow-hidden">

      {/* Main Content */}
      <main className="flex-1 overflow-y-auto p-8 space-y-6 admin-scrollbar">
        {/* Error State */}
        {error && (
          <div className="admin-error-alert p-4 rounded-r shadow-sm">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-500 text-xl" />
              <span className="text-red-700 font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* Stats Grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {/* Total System Spending */}
          <div className="admin-glass-card admin-metric-card admin-border-indigo rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                Total System Spend
              </h3>
              <div className="admin-icon-indigo w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-dollar-sign text-indigo-600" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-3xl font-black admin-metric-value">
                    {formatCurrency(convertedData?.totalSpending)}
                  </span>
                </div>
                <p className="text-xs text-gray-500 mt-2 font-medium">Aggregated across all clients</p>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-36 bg-gray-200 admin-shimmer rounded-lg" />
                <div className="h-3 w-24 bg-gray-200 admin-shimmer rounded-full" />
              </div>
            )}
          </div>

          {/* Total Accounts */}
          <div className="admin-glass-card admin-metric-card admin-border-purple rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Total Accounts</h3>
              <div className="admin-icon-purple w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-layer-group text-purple-600" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in">
                <div className="flex items-baseline justify-between mb-2">
                  <span className="text-3xl font-black admin-metric-value">
                    {data?.totalAccounts || 0}
                  </span>
                  <div className="admin-provider-stack">
                    <div className="admin-provider-icon" title="AWS">
                      <i className="fab fa-aws admin-provider-aws" />
                    </div>
                    <div className="admin-provider-icon" title="GCP">
                      <i className="fab fa-google admin-provider-gcp" />
                    </div>
                    <div className="admin-provider-icon" title="Azure">
                      <i className="fab fa-microsoft admin-provider-azure" />
                    </div>
                  </div>
                </div>
                <div className="text-xs text-gray-500 mt-2 font-medium">
                  <span>{data?.awsAccountsCount || 0}</span> AWS |{' '}
                  <span>{data?.gcpAccountsCount || 0}</span> GCP |{' '}
                  <span>{data?.azureAccountsCount || 0}</span> Azure
                </div>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-20 bg-gray-200 admin-shimmer rounded-lg" />
                <div className="h-3 w-full bg-gray-200 admin-shimmer rounded-full" />
              </div>
            )}
          </div>

          {/* Total Invoices */}
          <div className="admin-glass-card admin-metric-card admin-border-blue rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                Total Invoices
              </h3>
              <div className="admin-icon-blue w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-file-invoice-dollar text-blue-600" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-3xl font-black admin-metric-value">
                    {(data?.pendingInvoices || 0) + (data?.finalizedInvoices || 0)}
                  </span>
                </div>
                <div className="text-xs text-gray-500 mt-2 font-medium">
                  <span className="text-blue-600 font-bold">{data?.pendingInvoices || 0}</span> Pending |{' '}
                  <span className="text-green-600 font-bold">{data?.finalizedInvoices || 0}</span> Finalized
                </div>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-24 bg-gray-200 admin-shimmer rounded-lg" />
                <div className="h-3 w-32 bg-gray-200 admin-shimmer rounded-full" />
              </div>
            )}
          </div>

          {/* System Tickets */}
          <div className="admin-glass-card admin-metric-card admin-border-orange rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">
                System Tickets
              </h3>
              <div className="admin-icon-orange w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-ticket-alt text-orange-600" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in">
                <div className="flex items-baseline gap-2 mb-1">
                  <span className="text-3xl font-black admin-metric-value">
                    {data?.openTickets || 0}
                  </span>
                </div>
                <p className="text-xs text-gray-500 mt-2 font-medium flex items-center gap-1">
                  <i className="fas fa-users text-[10px] text-gray-400" />
                  Total Clients:{' '}
                  <span className="text-gray-900 font-bold">{data?.totalClients || 0}</span>
                </p>
              </div>
            ) : (
              <div className="animate-pulse space-y-4">
                <div className="h-10 w-24 bg-gray-200 admin-shimmer rounded-lg" />
                <div className="h-3 w-32 bg-gray-200 admin-shimmer rounded-full" />
              </div>
            )}
          </div>
        </div>

        {/* Charts Row */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Provider Distribution Chart */}
          <div className="admin-glass-card admin-border-top-indigo rounded-xl p-6 flex flex-col">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-chart-pie text-indigo-600" />
                  Global Spending Distribution
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">By Cloud Provider</p>
              </div>
            </div>
            <div className="admin-chart-container">
              <canvas ref={providerChartRef} />
            </div>
          </div>

          {/* Spending Trend Chart */}
          <div className="admin-glass-card admin-border-top-purple rounded-xl p-6 flex flex-col">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-chart-line text-purple-600" />
                  Spending Trend (Last 6 Months)
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">Aggregated Cloud Spend in USD</p>
              </div>
            </div>
            <div className="admin-chart-container" style={{ height: '300px' }}>
              <canvas ref={trendChartRef} />
            </div>
          </div>
        </div>

        {/* Top Spending Accounts Table */}
        <div className="admin-glass-card admin-border-cyan rounded-xl p-6">
          <div className="flex items-center justify-between mb-6">
            <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
              <i className="fas fa-trophy text-cyan-600" />
              Top 10 Spending Accounts
            </h3>
          </div>

          <div className="overflow-x-auto rounded-xl border border-gray-100">
            {loading ? (
              <div className="p-4 space-y-4">
                <div className="h-10 w-full bg-gray-100 admin-shimmer rounded-lg animate-pulse" />
                <div className="h-10 w-full bg-gray-100 admin-shimmer rounded-lg animate-pulse" />
                <div className="h-10 w-full bg-gray-100 admin-shimmer rounded-lg animate-pulse" />
              </div>
            ) : (
              <table className="min-w-full divide-y divide-gray-200">
                <thead className="bg-gray-50/80 backdrop-blur-sm">
                  <tr>
                    <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Account Name
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Provider
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Client
                    </th>
                    <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Status
                    </th>
                    <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Current Month
                    </th>
                    <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">
                      Last Month
                    </th>
                  </tr>
                </thead>
                <tbody className="bg-white/40 divide-y divide-gray-200">
                  {convertedData?.topSpendingAccounts && convertedData.topSpendingAccounts.length > 0 ? (
                    convertedData.topSpendingAccounts.map((account: TopSpendingAccount) => (
                      <tr key={account.id} className="admin-table-row">
                        <td className="px-6 py-4 whitespace-nowrap text-sm font-bold text-gray-900">
                          {account.accountName}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm">
                          <span className="admin-provider-badge">
                            <i className={getProviderIconClass(account.provider)} />
                            <span>{account.provider}</span>
                          </span>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600 font-medium">
                          {account.clientName}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm">
                          <span className={`admin-status-badge ${getStatusClass(account.status)}`}>
                            {account.status}
                          </span>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-right font-black text-gray-900">
                          {formatCurrency(account.currentMonthSpending)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-right text-gray-500">
                          {formatCurrency(account.lastMonthSpending)}
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={6} className="px-6 py-8 text-center text-gray-500">
                        No account data available
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            )}
          </div>
        </div>

        {/* Recent Activity Grid */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Recent Invoices */}
          <div className="admin-glass-card rounded-xl p-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-black text-gray-900">Recent Invoices</h3>
              <Link
                to="/billops-invoices"
                className="text-xs font-bold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide"
              >
                View All &rarr;
              </Link>
            </div>
            <div className="space-y-3">
              {loading ? (
                <>
                  <div className="h-12 bg-gray-100 rounded-lg admin-shimmer animate-pulse" />
                  <div className="h-12 bg-gray-100 rounded-lg admin-shimmer animate-pulse" />
                </>
              ) : convertedData?.recentInvoices && convertedData.recentInvoices.length > 0 ? (
                convertedData.recentInvoices.map((invoice: AdminInvoice) => (
                  <div
                    key={invoice.id}
                    className="admin-list-item flex items-center justify-between p-3 rounded-lg group"
                  >
                    <div className="flex items-center gap-3">
                      <div className="w-8 h-8 rounded-full bg-indigo-100 text-indigo-600 flex items-center justify-center">
                        <i className="fas fa-file-invoice text-xs" />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-gray-900 group-hover:text-indigo-700 transition-colors">
                          {invoice.invoiceNumber}
                        </p>
                        <p className="text-[10px] text-gray-500 font-semibold">
                          {invoice.clientName} - {invoice.accountName}
                        </p>
                      </div>
                    </div>
                    <div className="text-right">
                      <p className="text-sm font-black text-gray-900">
                        {formatCurrency(invoice.amount)}
                      </p>
                      <span className={`admin-status-badge-sm ${getStatusClass(invoice.status)}`}>
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
          <div className="admin-glass-card rounded-xl p-6">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-black text-gray-900">Recent Tickets</h3>
              <Link
                to="/billops-tickets"
                className="text-xs font-bold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide"
              >
                View All &rarr;
              </Link>
            </div>
            <div className="space-y-3">
              {loading ? (
                <>
                  <div className="h-12 bg-gray-100 rounded-lg admin-shimmer animate-pulse" />
                  <div className="h-12 bg-gray-100 rounded-lg admin-shimmer animate-pulse" />
                </>
              ) : data?.recentTickets && data.recentTickets.length > 0 ? (
                data.recentTickets.map((ticket: AdminTicket) => (
                  <div
                    key={ticket.id}
                    className="admin-list-item flex items-center justify-between p-3 rounded-lg group"
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
                      <span className={`admin-status-badge-sm ${getStatusClass(ticket.status)}`}>
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

export default BillOpsAdminDashboardPage;
