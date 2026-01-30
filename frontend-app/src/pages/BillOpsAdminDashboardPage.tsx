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

import React, { useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import Chart from 'chart.js/auto';
import { useBillOpsAdminDashboard } from '../hooks/useBillOpsAdminDashboard';
import type { TopSpendingAccount, AdminInvoice, AdminTicket } from '../types/billopsAdminDashboard';

const BillOpsAdminDashboardPage: React.FC = () => {
  const { data, loading, isRefreshing, error, refreshDashboard } = useBillOpsAdminDashboard();

  // Chart refs
  const providerChartRef = useRef<HTMLCanvasElement>(null);
  const statusChartRef = useRef<HTMLCanvasElement>(null);
  const providerChartInstance = useRef<Chart | null>(null);
  const statusChartInstance = useRef<Chart | null>(null);

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
    if (!providerChartRef.current || !data?.spendingByProvider) return;

    // Destroy existing chart
    if (providerChartInstance.current) {
      providerChartInstance.current.destroy();
    }

    const ctx = providerChartRef.current.getContext('2d');
    if (!ctx) return;

    const spendingData = data.spendingByProvider;
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
  }, [data?.spendingByProvider]);

  /**
   * Render Account Status Chart (Bar)
   */
  useEffect(() => {
    if (!statusChartRef.current || !data?.accountsByStatus) return;

    // Destroy existing chart
    if (statusChartInstance.current) {
      statusChartInstance.current.destroy();
    }

    const ctx = statusChartRef.current.getContext('2d');
    if (!ctx) return;

    const statusData = data.accountsByStatus;
    const labels = Object.keys(statusData);
    const values = Object.values(statusData);

    if (labels.length === 0) return;

    statusChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels,
        datasets: [
          {
            label: 'Accounts',
            data: values,
            backgroundColor: '#6366f1',
            borderRadius: 6,
            barThickness: 32,
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
        },
        scales: {
          y: {
            beginAtZero: true,
            ticks: {
              stepSize: 1,
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
      if (statusChartInstance.current) {
        statusChartInstance.current.destroy();
      }
    };
  }, [data?.accountsByStatus]);

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

  return (
    <div className="admin-dashboard flex-1 flex flex-col h-screen overflow-hidden">
      {/* Header */}
      <header className="admin-header h-20 flex items-center justify-between px-8 sticky top-0 z-20">
        <div className="flex items-center gap-4">
          <div className="admin-header-icon w-12 h-12 rounded-xl flex items-center justify-center text-white transition-transform">
            <i className="fas fa-user-shield text-lg" />
          </div>
          <div>
            <h1 className="text-xl font-black text-gray-900 tracking-tight flex items-center gap-2">
              BillOps Command Center
              <span className="text-xs font-semibold px-2 py-0.5 bg-gradient-to-r from-purple-100 to-pink-100 text-purple-600 rounded-full">
                Admin
              </span>
            </h1>
            <div className="flex items-center gap-3 text-xs text-gray-500 mt-1">
              <div className="flex items-center gap-1.5">
                <div className="w-2 h-2 bg-green-500 rounded-full admin-live-indicator" />
                <span className="font-medium">System Overview</span>
              </div>
              <span className="text-gray-300">|</span>
              <span className="text-gray-400">All Clients</span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-4">
          {data?.lastUpdated && (
            <div className="text-xs text-gray-500 font-medium hidden md:block">
              Last updated: {new Date(data.lastUpdated).toLocaleString()}
            </div>
          )}
          <button
            onClick={refreshDashboard}
            disabled={isRefreshing}
            className="admin-refresh-btn flex items-center gap-2 px-4 py-2 text-sm font-semibold text-white rounded-lg shadow-md"
          >
            <i className={`fas fa-sync-alt ${isRefreshing ? 'admin-spin' : ''}`} />
            Refresh
          </button>
        </div>
      </header>

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
                    {formatCurrency(data?.totalSpending)}
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

          {/* Account Status Chart */}
          <div className="admin-glass-card admin-border-top-purple rounded-xl p-6 flex flex-col">
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-chart-bar text-purple-600" />
                  Account Status
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">Active vs Inactive</p>
              </div>
            </div>
            <div className="admin-chart-container">
              <canvas ref={statusChartRef} />
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
                  {data?.topSpendingAccounts && data.topSpendingAccounts.length > 0 ? (
                    data.topSpendingAccounts.map((account: TopSpendingAccount) => (
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
              ) : data?.recentInvoices && data.recentInvoices.length > 0 ? (
                data.recentInvoices.map((invoice: AdminInvoice) => (
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
