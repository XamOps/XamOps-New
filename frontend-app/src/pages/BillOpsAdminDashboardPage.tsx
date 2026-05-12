/**
 * BillOps Admin Dashboard Page — Tabbed Layout
 *
 * Tabs:
 *  1. Overview  — Stats, Charts, Top 10 Accounts (with MoM % change)
 *  2. Clients   — Per-client spend grouped from top accounts
 *  3. Invoices  — Recent invoices with status filter
 *  4. Tickets   — Recent tickets with severity/status filter
 */

import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Chart from 'chart.js/auto';
import { useBillOpsAdminDashboard } from '../hooks/useBillOpsAdminDashboard';
import { useTopbar } from '../contexts/TopbarContext';
import type { TopSpendingAccount, AdminInvoice, AdminTicket, SpendingTrendItem } from '../types/billopsAdminDashboard';
import { apiFetch } from '../utils/apiClient';
import { useCurrency } from '../hooks/useCurrency';
import { useTabState } from '../hooks/useTabState';

interface CustomerSpotSavings {
  clientId: number;
  clientName: string;
  awsSavings: number;
  gcpSavings: number;
  azureSavings: number;
  totalSavings: number;
}

interface ClientsApiAccount {
  id: number;
  name: string;
  providerAccountId: string;
  provider: string;
  consumption: number;
  creditsApplied?: number;
}

interface ClientsApiData {
  id: number;
  name: string;
  accounts: ClientsApiAccount[];
}

type ActiveTab = 'overview' | 'clients' | 'invoices' | 'tickets';

const TABS: { id: ActiveTab; label: string; icon: string }[] = [
  { id: 'overview', label: 'Overview', icon: 'fas fa-chart-pie' },
  { id: 'clients', label: 'Clients', icon: 'fas fa-users' },
  { id: 'invoices', label: 'Invoices', icon: 'fas fa-file-invoice-dollar' },
  { id: 'tickets', label: 'Tickets', icon: 'fas fa-ticket-alt' },
];

const BillOpsAdminDashboardPage: React.FC = () => {
  const { data, loading, isRefreshing, isRefreshingTopSpending, error, refreshDashboard, refreshTopSpending } = useBillOpsAdminDashboard();
  const { setActions, clearActions } = useTopbar();
  const { loadRates, convert } = useCurrency();

  const [activeTab, setActiveTab] = useTabState<ActiveTab>('overview');
  const [invoiceFilter, setInvoiceFilter] = useState<string>('ALL');
  const [ticketFilter, setTicketFilter] = useState<string>('ALL');

  const [customerSavings, setCustomerSavings] = useState<CustomerSpotSavings[]>([]);
  const [customerSavingsLoading, setCustomerSavingsLoading] = useState(true);

  const [clientsData, setClientsData] = useState<ClientsApiData[] | null>(null);
  const [clientsLoading, setClientsLoading] = useState(true);
  const [clientsRefreshing, setClientsRefreshing] = useState(false);

  const fetchClientsData = React.useCallback((forceRefresh = false) => {
    const url = forceRefresh
      ? '/api/xamops/billops-admin/clients?forceRefresh=true'
      : '/api/xamops/billops-admin/clients';
    if (forceRefresh) setClientsRefreshing(true);
    apiFetch(url, { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then((d: ClientsApiData[]) => setClientsData(d))
      .catch(err => console.error('Failed to load clients data for spend total', err))
      .finally(() => { setClientsLoading(false); setClientsRefreshing(false); });
  }, []);

  useEffect(() => { fetchClientsData(false); }, [fetchClientsData]);

  useEffect(() => {
    apiFetch('/api/admin/autospotting/customer-savings', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then((d: CustomerSpotSavings[]) => setCustomerSavings(d))
      .catch(err => console.error('Failed to load customer spot savings', err))
      .finally(() => setCustomerSavingsLoading(false));
  }, []);

  // Chart refs
  const providerChartRef = useRef<HTMLCanvasElement>(null);
  const trendChartRef = useRef<HTMLCanvasElement>(null);
  const providerChartInstance = useRef<Chart<'doughnut'> | null>(null);
  const trendChartInstance = useRef<Chart<'line'> | null>(null);

  useEffect(() => { loadRates('inr'); }, [loadRates]);

  // ── Converted data (GCP INR → USD) ────────────────────────────────────────
  const convertedData = React.useMemo(() => {
    if (!data) return null;

    const needsConversion = (provider: string) => provider === 'GCP' || provider === 'Azure';

    const convertedTopAccounts = data.topSpendingAccounts.map(account => ({
      ...account,
      currentMonthSpending: needsConversion(account.provider)
        ? (convert(account.currentMonthSpending, 'usd') ?? account.currentMonthSpending)
        : account.currentMonthSpending,
      lastMonthSpending: needsConversion(account.provider)
        ? (convert(account.lastMonthSpending, 'usd') ?? account.lastMonthSpending)
        : account.lastMonthSpending,
    }));

    const spendingByProvider = {
      AWS: data.spendingByProvider.AWS || 0,
      Azure: data.spendingByProvider.Azure
        ? (convert(data.spendingByProvider.Azure, 'usd') ?? data.spendingByProvider.Azure)
        : 0,
      GCP: data.spendingByProvider.GCP
        ? (convert(data.spendingByProvider.GCP, 'usd') ?? data.spendingByProvider.GCP)
        : 0,
    };

    const totalSpending = Object.values(spendingByProvider).reduce((s, v) => s + (v || 0), 0);

    const convertedSpendingTrend = data.spendingTrend?.map(item => {
      if (!item.costByProvider) return item;
      const aws = item.costByProvider.AWS || 0;
      const azure = convert(item.costByProvider.Azure || 0, 'usd') ?? (item.costByProvider.Azure || 0);
      const gcp = convert(item.costByProvider.GCP || 0, 'usd') ?? (item.costByProvider.GCP || 0);
      return { ...item, cost: aws + azure + gcp, costByProvider: { AWS: aws, Azure: azure, GCP: gcp } };
    }) || [];

    const convertedRecentInvoices = data.recentInvoices.map(invoice => ({
      ...invoice,
      amount: needsConversion(invoice.provider ?? '')
        ? (convert(invoice.amount, 'usd') ?? invoice.amount)
        : invoice.amount,
    }));

    return {
      ...data,
      topSpendingAccounts: convertedTopAccounts,
      totalSpending,
      spendingByProvider,
      spendingTrend: convertedSpendingTrend,
      recentInvoices: convertedRecentInvoices,
    };
  }, [data, convert]);

  // ── Spend totals from clients endpoint (same source as Client Details page) ─
  const clientsSpend = React.useMemo(() => {
    if (!clientsData) return null;
    const byProvider: Record<string, number> = { AWS: 0, GCP: 0, Azure: 0 };
    let total = 0;
    for (const client of clientsData) {
      for (const account of client.accounts) {
        const raw = account.consumption || 0;
        const inUsd = (account.provider === 'GCP' || account.provider === 'Azure')
          ? (convert(raw, 'usd') ?? raw)
          : raw;
        byProvider[account.provider] = (byProvider[account.provider] || 0) + inUsd;
        total += inUsd;
      }
    }
    return { byProvider, total };
  }, [clientsData, convert]);

  // ── Top 10 accounts derived from clientsData (same source as Client Details page) ──
  const top10FromClients = React.useMemo(() => {
    if (!clientsData) return convertedData?.topSpendingAccounts ?? [];

    // Build a lookup of lastMonthSpending from the backend top accounts by providerAccountId
    const lastMonthMap: Record<string, number> = {};
    if (data?.topSpendingAccounts) {
      for (const acc of data.topSpendingAccounts) {
        if (acc.accountIdentifier) lastMonthMap[acc.accountIdentifier] = acc.lastMonthSpending ?? 0;
      }
    }

    const needsConversion = (provider: string) => provider === 'GCP' || provider === 'Azure';

    const allAccounts: TopSpendingAccount[] = [];
    for (const client of clientsData) {
      for (const acc of client.accounts) {
        const raw = acc.consumption || 0;
        const currentMonthSpending = needsConversion(acc.provider)
          ? (convert(raw, 'usd') ?? raw)
          : raw;
        const rawLast = lastMonthMap[acc.providerAccountId] ?? 0;
        const lastMonthSpending = needsConversion(acc.provider)
          ? (convert(rawLast, 'usd') ?? rawLast)
          : rawLast;
        allAccounts.push({
          id: String(acc.id),
          accountName: acc.name,
          accountIdentifier: acc.providerAccountId,
          provider: acc.provider as 'AWS' | 'GCP' | 'Azure',
          clientName: client.name,
          status: 'ACTIVE',
          currentMonthSpending,
          lastMonthSpending,
        });
      }
    }

    return allAccounts
      .sort((a, b) => (b.currentMonthSpending ?? 0) - (a.currentMonthSpending ?? 0))
      .slice(0, 10);
  }, [clientsData, data, convert, convertedData]);

  // ── Clients grouped from top accounts ─────────────────────────────────────
  const clientGroups = React.useMemo(() => {
    if (!convertedData?.topSpendingAccounts) return [];
    const map: Record<string, { clientName: string; totalSpend: number; accounts: TopSpendingAccount[] }> = {};
    for (const acc of convertedData.topSpendingAccounts) {
      if (!map[acc.clientName]) map[acc.clientName] = { clientName: acc.clientName, totalSpend: 0, accounts: [] };
      map[acc.clientName].totalSpend += acc.currentMonthSpending || 0;
      map[acc.clientName].accounts.push(acc);
    }
    return Object.values(map).sort((a, b) => b.totalSpend - a.totalSpend);
  }, [convertedData]);

  // ── Filtered invoices/tickets ──────────────────────────────────────────────
  const filteredInvoices = React.useMemo(() => {
    if (!convertedData?.recentInvoices) return [];
    return invoiceFilter === 'ALL'
      ? convertedData.recentInvoices
      : convertedData.recentInvoices.filter(i => i.status === invoiceFilter);
  }, [convertedData, invoiceFilter]);

  const filteredTickets = React.useMemo(() => {
    if (!data?.recentTickets) return [];
    return ticketFilter === 'ALL'
      ? data.recentTickets
      : data.recentTickets.filter(t => t.status === ticketFilter || t.severity === ticketFilter);
  }, [data, ticketFilter]);

  // ── Format helpers ─────────────────────────────────────────────────────────
  const formatCurrency = (value: number | null | undefined): string => {
    if (value === null || value === undefined) return '$0.00';
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2 }).format(value);
  };

  const getMoMChange = (current: number, last: number): { pct: number; up: boolean } | null => {
    if (!last || last === 0) return null;
    const pct = ((current - last) / last) * 100;
    return { pct: Math.abs(pct), up: pct >= 0 };
  };

  const getStatusClass = (status: string): string => ({
    ACTIVE: 'admin-status-active', PENDING: 'admin-status-pending', INACTIVE: 'admin-status-inactive',
    DRAFT: 'admin-status-draft', FINALIZED: 'admin-status-finalized', PAID: 'admin-status-paid',
    OVERDUE: 'admin-status-overdue', OPEN: 'admin-status-open', IN_PROGRESS: 'admin-status-in-progress',
    CLOSED: 'admin-status-closed',
  }[status] || '');

  const getSeverityClass = (severity?: string): string => ({
    LOW: 'admin-severity-low', MEDIUM: 'admin-severity-medium',
    HIGH: 'admin-severity-high', CRITICAL: 'admin-severity-critical',
  }[severity || ''] || '');

  const getProviderIconClass = (provider: string): string => ({
    AWS: 'fab fa-aws admin-provider-aws',
    GCP: 'fab fa-google admin-provider-gcp',
    Azure: 'fab fa-microsoft admin-provider-azure',
  }[provider] || 'fas fa-cloud');

  // ── Charts ─────────────────────────────────────────────────────────────────
  useEffect(() => {
    const spendingByProvider = clientsSpend?.byProvider ?? convertedData?.spendingByProvider;
    if (!providerChartRef.current || !spendingByProvider) return;
    if (providerChartInstance.current) providerChartInstance.current.destroy();
    const ctx = providerChartRef.current.getContext('2d');
    if (!ctx) return;
    const values = Object.values(spendingByProvider);
    if (values.every(v => v === 0)) return;
    providerChartInstance.current = new Chart(ctx, {
      type: 'doughnut',
      data: {
        labels: Object.keys(spendingByProvider),
        datasets: [{ data: values, backgroundColor: ['#FF9900', '#0078D4', '#34A853'], borderWidth: 0, hoverOffset: 4 }],
      },
      options: {
        responsive: true, maintainAspectRatio: false, cutout: '75%',
        plugins: {
          legend: { position: 'right', labels: { usePointStyle: true, padding: 20, font: { family: 'Inter', weight: '500' } } },
          tooltip: {
            backgroundColor: 'rgba(255,255,255,0.95)', bodyColor: '#1f2937', borderColor: '#e5e7eb', borderWidth: 1, padding: 10,
            callbacks: { label: (ctx) => `${ctx.label}: ${formatCurrency(ctx.raw as number)}` },
          },
        },
      },
    });
    return () => { providerChartInstance.current?.destroy(); };
  }, [clientsSpend, convertedData?.spendingByProvider]);

  useEffect(() => {
    if (!trendChartRef.current || !convertedData?.spendingTrend?.length) return;
    if (trendChartInstance.current) trendChartInstance.current.destroy();
    const ctx = trendChartRef.current.getContext('2d');
    if (!ctx) return;
    trendChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: convertedData.spendingTrend.map((i: SpendingTrendItem) => i.date),
        datasets: [{
          label: 'Monthly Spending (USD)',
          data: convertedData.spendingTrend.map((i: SpendingTrendItem) => i.cost),
          borderColor: '#6366f1', backgroundColor: 'rgba(99,102,241,0.1)',
          borderWidth: 3, fill: true, tension: 0.4,
          pointBackgroundColor: '#fff', pointBorderColor: '#6366f1', pointBorderWidth: 2, pointRadius: 4, pointHoverRadius: 6,
        }],
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(255,255,255,0.95)', bodyColor: '#1f2937', titleColor: '#111827',
            borderColor: '#e5e7eb', borderWidth: 1, padding: 12, displayColors: false,
            callbacks: { label: (ctx) => `Spend: ${formatCurrency(ctx.raw as number)}` },
          },
        },
        scales: {
          y: { beginAtZero: true, ticks: { callback: (v) => `$${Number(v).toLocaleString()}`, font: { family: 'Inter' } }, grid: { color: '#f3f4f6' } },
          x: { grid: { display: false }, ticks: { font: { family: 'Inter' } } },
        },
      },
    });
    return () => { trendChartInstance.current?.destroy(); };
  }, [convertedData?.spendingTrend]);

  // ── Topbar actions ─────────────────────────────────────────────────────────
  const handleRefresh = React.useCallback(() => {
    refreshDashboard();
    fetchClientsData(true);
  }, [refreshDashboard, fetchClientsData]);

  useEffect(() => {
    const refreshing = isRefreshing || clientsRefreshing;
    setActions(
      <div className="flex items-center gap-4">
        {data?.lastUpdated && (
          <div className="text-xs text-slate-500 font-medium hidden md:block">
            Last updated: {new Date(data.lastUpdated).toLocaleString()}
          </div>
        )}
        <button
          onClick={handleRefresh} disabled={refreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-xl text-xs font-bold shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${refreshing ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, data?.lastUpdated, isRefreshing, clientsRefreshing, handleRefresh, refreshDashboard]);

  // ── Shimmer skeleton ───────────────────────────────────────────────────────
  const Shimmer = ({ className }: { className: string }) => (
    <div className={`bg-gray-200 admin-shimmer rounded-lg animate-pulse ${className}`} />
  );

  // ══════════════════════════════════════════════════════════════════════════
  return (
    <div className="admin-dashboard flex-1 flex flex-col h-full bg-transparent overflow-hidden">
      <main className="flex-1 overflow-y-auto p-8 space-y-6 admin-scrollbar">

        {/* Error */}
        {error && (
          <div className="admin-error-alert p-4 rounded-r shadow-sm">
            <div className="flex items-center gap-3">
              <i className="fas fa-exclamation-circle text-red-500 text-xl" />
              <span className="text-red-700 font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* ── Stats Grid (always visible) ─────────────────────────────────── */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {/* Total Spend */}
          <div className="admin-glass-card admin-metric-card admin-border-indigo rounded-xl p-6">
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Total System Spend</h3>
              <div className="admin-icon-indigo w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-dollar-sign text-indigo-600" />
              </div>
            </div>
            {!loading && !clientsLoading ? (
              <div className="admin-fade-in">
                <span className="text-3xl font-black admin-metric-value">
                  {formatCurrency(clientsSpend?.total ?? convertedData?.totalSpending)}
                </span>
                <p className="text-xs text-gray-500 mt-2 font-medium">Aggregated across all clients</p>
              </div>
            ) : <div className="space-y-3"><Shimmer className="h-10 w-36" /><Shimmer className="h-3 w-24" /></div>}
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
                  <span className="text-3xl font-black admin-metric-value">{data?.totalAccounts || 0}</span>
                  <div className="admin-provider-stack">
                    <div className="admin-provider-icon" title="AWS"><i className="fab fa-aws admin-provider-aws" /></div>
                    <div className="admin-provider-icon" title="GCP"><i className="fab fa-google admin-provider-gcp" /></div>
                    <div className="admin-provider-icon" title="Azure"><i className="fab fa-microsoft admin-provider-azure" /></div>
                  </div>
                </div>
                <div className="text-xs text-gray-500 mt-2 font-medium">
                  <span>{data?.awsAccountsCount || 0}</span> AWS |{' '}
                  <span>{data?.gcpAccountsCount || 0}</span> GCP |{' '}
                  <span>{data?.azureAccountsCount || 0}</span> Azure
                </div>
              </div>
            ) : <div className="space-y-3"><Shimmer className="h-10 w-20" /><Shimmer className="h-3 w-full" /></div>}
          </div>

          {/* Invoices */}
          <div
            className="admin-glass-card admin-metric-card admin-border-blue rounded-xl p-6 cursor-pointer"
            onClick={() => setActiveTab('invoices')}
            title="Click to view invoices"
          >
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">Total Invoices</h3>
              <div className="admin-icon-blue w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-file-invoice-dollar text-blue-600" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in">
                <span className="text-3xl font-black admin-metric-value">
                  {(data?.pendingInvoices || 0) + (data?.finalizedInvoices || 0)}
                </span>
                <div className="text-xs text-gray-500 mt-2 font-medium">
                  <span className="text-blue-600 font-bold">{data?.pendingInvoices || 0}</span> Pending |{' '}
                  <span className="text-green-600 font-bold">{data?.finalizedInvoices || 0}</span> Finalized
                </div>
              </div>
            ) : <div className="space-y-3"><Shimmer className="h-10 w-24" /><Shimmer className="h-3 w-32" /></div>}
          </div>

          {/* Tickets */}
          <div
            className="admin-glass-card admin-metric-card admin-border-orange rounded-xl p-6 cursor-pointer"
            onClick={() => setActiveTab('tickets')}
            title="Click to view tickets"
          >
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">System Tickets</h3>
              <div className="admin-icon-orange w-10 h-10 rounded-lg flex items-center justify-center">
                <i className="fas fa-ticket-alt text-orange-600" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in">
                <span className="text-3xl font-black admin-metric-value">{data?.openTickets || 0}</span>
                <p className="text-xs text-gray-500 mt-2 font-medium flex items-center gap-1">
                  <i className="fas fa-users text-[10px] text-gray-400" />
                  Total Clients: <span className="text-gray-900 font-bold">{data?.totalClients || 0}</span>
                </p>
              </div>
            ) : <div className="space-y-3"><Shimmer className="h-10 w-24" /><Shimmer className="h-3 w-32" /></div>}
          </div>
        </div>

        {/* ── Tab Navigation ───────────────────────────────────────────────── */}
        <div className="flex gap-1 p-1 bg-white/60 backdrop-blur-sm rounded-xl border border-gray-100 shadow-sm w-fit">
          {TABS.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all ${activeTab === tab.id
                  ? 'bg-indigo-600 text-white shadow-md shadow-indigo-500/20'
                  : 'text-gray-500 hover:text-gray-800 hover:bg-gray-100'
                }`}
            >
              <i className={`${tab.icon} text-xs`} />
              {tab.label}
              {tab.id === 'invoices' && (data?.pendingInvoices || 0) > 0 && (
                <span className="ml-1 min-w-[18px] h-[18px] flex items-center justify-center px-1 bg-blue-100 text-blue-700 text-[10px] font-bold rounded-full">
                  {data?.pendingInvoices}
                </span>
              )}
              {tab.id === 'tickets' && (data?.openTickets || 0) > 0 && (
                <span className="ml-1 min-w-[18px] h-[18px] flex items-center justify-center px-1 bg-orange-100 text-orange-700 text-[10px] font-bold rounded-full">
                  {data?.openTickets}
                </span>
              )}
            </button>
          ))}
        </div>

        {/* ══════════════════════════════════════════════════════════════════
            TAB 1 — OVERVIEW
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'overview' && (
          <div className="space-y-6">
            {/* Charts Row */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
              <div className="admin-glass-card admin-border-top-indigo rounded-xl p-6 flex flex-col">
                <div className="mb-4">
                  <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                    <i className="fas fa-chart-pie text-indigo-600" />Global Spending Distribution
                  </h3>
                  <p className="text-xs text-gray-500 mt-1 font-medium">By Cloud Provider</p>
                </div>
                {!loading && !Object.values(clientsSpend?.byProvider ?? convertedData?.spendingByProvider ?? {}).some(v => v > 0) ? (
                  <div className="flex-1 flex items-center justify-center text-gray-400 text-sm font-medium">
                    <div className="text-center"><i className="fas fa-chart-pie text-3xl mb-2 text-gray-200 block" />No spending data available</div>
                  </div>
                ) : (
                  <div className="admin-chart-container"><canvas ref={providerChartRef} /></div>
                )}
              </div>

              <div className="admin-glass-card admin-border-top-purple rounded-xl p-6 flex flex-col">
                <div className="mb-4">
                  <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                    <i className="fas fa-chart-line text-purple-600" />Spending Trend (Last 6 Months)
                  </h3>
                  <p className="text-xs text-gray-500 mt-1 font-medium">Aggregated Cloud Spend in USD</p>
                </div>
                {!loading && !convertedData?.spendingTrend?.length ? (
                  <div className="flex-1 flex items-center justify-center text-gray-400 text-sm font-medium">
                    <div className="text-center"><i className="fas fa-chart-line text-3xl mb-2 text-gray-200 block" />No trend data available</div>
                  </div>
                ) : (
                  <div className="admin-chart-container" style={{ height: '300px' }}><canvas ref={trendChartRef} /></div>
                )}
              </div>
            </div>

            {/* Top 10 Accounts */}
            <div className="admin-glass-card admin-border-cyan rounded-xl p-6">
              <div className="flex items-center justify-between mb-6">
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-trophy text-cyan-600" />Top 10 Spending Accounts
                </h3>
                <button
                  onClick={refreshTopSpending}
                  disabled={isRefreshingTopSpending}
                  title="Fetch live current-month cost from CE API"
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-cyan-700 bg-cyan-50 hover:bg-cyan-100 border border-cyan-200 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <i className={`fas fa-bolt text-[10px] ${isRefreshingTopSpending ? 'animate-pulse' : ''}`} />
                  {isRefreshingTopSpending ? 'Fetching live...' : 'Live Refresh'}
                </button>
              </div>
              <div className="overflow-x-auto rounded-xl border border-gray-100">
                {clientsLoading ? (
                  <div className="p-4 space-y-3">
                    {[1, 2, 3].map(i => <Shimmer key={i} className="h-10 w-full" />)}
                  </div>
                ) : (
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50/80 backdrop-blur-sm">
                      <tr>
                        <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">#</th>
                        <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Account</th>
                        <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Provider</th>
                        <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Client</th>
                        <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">This Month</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">Last Month</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">MoM</th>
                      </tr>
                    </thead>
                    <tbody className="bg-white/40 divide-y divide-gray-200">
                      {top10FromClients?.length ? (
                        top10FromClients.map((account: TopSpendingAccount, idx: number) => {
                          const mom = getMoMChange(account.currentMonthSpending, account.lastMonthSpending);
                          return (
                            <tr key={account.id} className="admin-table-row">
                              <td className="px-6 py-4 text-sm text-gray-400 font-bold">{idx + 1}</td>
                              <td className="px-6 py-4 whitespace-nowrap">
                                <div className="text-sm font-bold text-gray-900">{account.accountName}</div>
                                {account.accountIdentifier && (
                                  <div className="text-[10px] text-gray-400 font-mono mt-0.5">{account.accountIdentifier}</div>
                                )}
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap">
                                <span className="admin-provider-badge">
                                  <i className={getProviderIconClass(account.provider)} />
                                  <span>{account.provider}</span>
                                </span>
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600 font-medium">{account.clientName}</td>
                              <td className="px-6 py-4 whitespace-nowrap">
                                <span className={`admin-status-badge ${getStatusClass(account.status)}`}>{account.status}</span>
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap text-sm text-right font-black text-gray-900">
                                {formatCurrency(account.currentMonthSpending)}
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap text-sm text-right text-gray-500">
                                {formatCurrency(account.lastMonthSpending)}
                              </td>
                              <td className="px-6 py-4 whitespace-nowrap text-sm text-right">
                                {mom ? (
                                  <span className={`inline-flex items-center gap-1 font-bold text-xs ${mom.up ? 'text-red-600' : 'text-green-600'}`}>
                                    <i className={`fas fa-arrow-${mom.up ? 'up' : 'down'} text-[9px]`} />
                                    {mom.pct.toFixed(1)}%
                                  </span>
                                ) : <span className="text-gray-300 text-xs">—</span>}
                              </td>
                            </tr>
                          );
                        })
                      ) : (
                        <tr>
                          <td colSpan={8} className="px-6 py-8 text-center text-gray-400 text-sm">No account data available</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                )}
              </div>
            </div>

            {/* AutoSpotting Savings */}
            <div className="admin-glass-card admin-border-green rounded-xl p-6">
              <div className="flex items-center justify-between mb-6">
                <div>
                  <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                    <i className="fas fa-bolt text-green-600" />AutoSpotting Savings by Customer
                  </h3>
                  <p className="text-xs text-gray-500 mt-1 font-medium">Only customers with AutoSpotting enabled</p>
                </div>
                <Link to="/autospotting-admin" className="text-xs font-bold text-green-600 hover:text-green-800 hover:underline uppercase tracking-wide">
                  Full Report &rarr;
                </Link>
              </div>
              <div className="overflow-x-auto rounded-xl border border-gray-100">
                {customerSavingsLoading ? (
                  <div className="p-4 space-y-3">{[1, 2, 3].map(i => <Shimmer key={i} className="h-10 w-full" />)}</div>
                ) : customerSavings.length === 0 ? (
                  <div className="px-6 py-10 text-center text-gray-400 text-sm font-medium">
                    <i className="fas fa-bolt text-gray-300 text-2xl mb-2 block" />No customers have AutoSpotting enabled yet
                  </div>
                ) : (
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead className="bg-gray-50/80 backdrop-blur-sm">
                      <tr>
                        <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Customer</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider"><i className="fab fa-aws admin-provider-aws mr-1" />AWS</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider"><i className="fab fa-google admin-provider-gcp mr-1" />GCP</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider"><i className="fab fa-microsoft admin-provider-azure mr-1" />Azure</th>
                        <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">Total Saved</th>
                      </tr>
                    </thead>
                    <tbody className="bg-white/40 divide-y divide-gray-200">
                      {customerSavings.map(row => (
                        <tr key={row.clientId} className="admin-table-row">
                          <td className="px-6 py-4 text-sm font-bold text-gray-900">{row.clientName}</td>
                          <td className="px-6 py-4 text-sm text-right text-gray-700 font-medium">{row.awsSavings > 0 ? formatCurrency(row.awsSavings) : <span className="text-gray-300">—</span>}</td>
                          <td className="px-6 py-4 text-sm text-right text-gray-700 font-medium">{row.gcpSavings > 0 ? formatCurrency(row.gcpSavings) : <span className="text-gray-300">—</span>}</td>
                          <td className="px-6 py-4 text-sm text-right text-gray-700 font-medium">{row.azureSavings > 0 ? formatCurrency(row.azureSavings) : <span className="text-gray-300">—</span>}</td>
                          <td className="px-6 py-4 text-sm text-right font-black text-green-700">{formatCurrency(row.totalSavings)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </div>
          </div>
        )}

        {/* ══════════════════════════════════════════════════════════════════
            TAB 2 — CLIENTS
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'clients' && (
          <div className="admin-glass-card admin-border-purple rounded-xl p-6">
            <div className="flex items-center justify-between mb-6">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-users text-purple-600" />Client Spending Summary
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">
                  Grouped from top 10 spending accounts · {clientGroups.length} client{clientGroups.length !== 1 ? 's' : ''}
                </p>
              </div>
            </div>
            <div className="overflow-x-auto rounded-xl border border-gray-100">
              {loading ? (
                <div className="p-4 space-y-3">{[1, 2, 3].map(i => <Shimmer key={i} className="h-14 w-full" />)}</div>
              ) : clientGroups.length === 0 ? (
                <div className="px-6 py-10 text-center text-gray-400 text-sm font-medium">
                  <i className="fas fa-users text-3xl mb-2 text-gray-200 block" />No client data available
                </div>
              ) : (
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50/80 backdrop-blur-sm">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Client</th>
                      <th className="px-6 py-3 text-center text-xs font-bold text-gray-500 uppercase tracking-wider">Accounts</th>
                      <th className="px-6 py-3 text-center text-xs font-bold text-gray-500 uppercase tracking-wider">Providers</th>
                      <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">This Month</th>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Top Account</th>
                    </tr>
                  </thead>
                  <tbody className="bg-white/40 divide-y divide-gray-200">
                    {clientGroups.map(group => {
                      const providers = [...new Set(group.accounts.map(a => a.provider))];
                      const topAccount = group.accounts[0];
                      return (
                        <tr key={group.clientName} className="admin-table-row">
                          <td className="px-6 py-4 whitespace-nowrap">
                            <div className="flex items-center gap-3">
                              <div className="w-9 h-9 rounded-full bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white font-black text-sm shadow-md">
                                {group.clientName.charAt(0).toUpperCase()}
                              </div>
                              <span className="text-sm font-bold text-gray-900">{group.clientName}</span>
                            </div>
                          </td>
                          <td className="px-6 py-4 text-center">
                            <span className="inline-flex items-center justify-center w-7 h-7 bg-indigo-100 text-indigo-700 text-xs font-black rounded-full">
                              {group.accounts.length}
                            </span>
                          </td>
                          <td className="px-6 py-4 text-center">
                            <div className="flex items-center justify-center gap-1">
                              {providers.map(p => (
                                <span key={p} className="admin-provider-badge text-xs px-2 py-0.5">
                                  <i className={getProviderIconClass(p)} /><span>{p}</span>
                                </span>
                              ))}
                            </div>
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap text-right font-black text-gray-900 text-sm">
                            {formatCurrency(group.totalSpend)}
                          </td>
                          <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-600 font-medium">
                            {topAccount?.accountName}
                            {topAccount?.accountIdentifier && (
                              <span className="ml-2 text-[10px] text-gray-400 font-mono">{topAccount.accountIdentifier}</span>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}

        {/* ══════════════════════════════════════════════════════════════════
            TAB 3 — INVOICES
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'invoices' && (
          <div className="admin-glass-card rounded-xl p-6">
            <div className="flex items-center justify-between mb-6">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-file-invoice-dollar text-blue-600" />Recent Invoices
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">Last 10 invoices across all clients</p>
              </div>
              <div className="flex items-center gap-3">
                {/* Status filter */}
                <div className="flex gap-1 p-1 bg-gray-100 rounded-lg">
                  {['ALL', 'DRAFT', 'FINALIZED', 'PAID', 'OVERDUE'].map(f => (
                    <button
                      key={f}
                      onClick={() => setInvoiceFilter(f)}
                      className={`px-3 py-1 rounded-md text-xs font-semibold transition-all ${invoiceFilter === f ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
                        }`}
                    >
                      {f === 'ALL' ? 'All' : f.charAt(0) + f.slice(1).toLowerCase()}
                    </button>
                  ))}
                </div>
                <Link to="/billops-admin-invoices" className="text-xs font-bold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide">
                  View All &rarr;
                </Link>
              </div>
            </div>

            <div className="overflow-x-auto rounded-xl border border-gray-100">
              {loading ? (
                <div className="p-4 space-y-3">{[1, 2, 3, 4].map(i => <Shimmer key={i} className="h-12 w-full" />)}</div>
              ) : filteredInvoices.length === 0 ? (
                <div className="px-6 py-10 text-center text-gray-400 text-sm font-medium">
                  <i className="fas fa-file-invoice text-3xl mb-2 text-gray-200 block" />
                  {invoiceFilter === 'ALL' ? 'No recent invoices found' : `No ${invoiceFilter.toLowerCase()} invoices`}
                </div>
              ) : (
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50/80 backdrop-blur-sm">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Invoice #</th>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Client</th>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Account</th>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Provider</th>
                      <th className="px-6 py-3 text-right text-xs font-bold text-gray-500 uppercase tracking-wider">Amount</th>
                      <th className="px-6 py-3 text-center text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
                    </tr>
                  </thead>
                  <tbody className="bg-white/40 divide-y divide-gray-200">
                    {filteredInvoices.map((invoice: AdminInvoice) => (
                      <tr key={invoice.id} className={`admin-table-row ${invoice.status === 'OVERDUE' ? 'bg-red-50/40' : ''}`}>
                        <td className="px-6 py-4 whitespace-nowrap">
                          <div className="flex items-center gap-2">
                            {invoice.status === 'OVERDUE' && <i className="fas fa-exclamation-circle text-red-500 text-xs" />}
                            <span className="text-sm font-bold text-gray-900">{invoice.invoiceNumber}</span>
                          </div>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-700 font-medium">{invoice.clientName}</td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{invoice.accountName}</td>
                        <td className="px-6 py-4 whitespace-nowrap">
                          {invoice.provider && (
                            <span className="admin-provider-badge text-xs">
                              <i className={getProviderIconClass(invoice.provider)} /><span>{invoice.provider}</span>
                            </span>
                          )}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-sm text-right font-black text-gray-900">
                          {formatCurrency(invoice.amount)}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-center">
                          <span className={`admin-status-badge ${getStatusClass(invoice.status)}`}>{invoice.status}</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}

        {/* ══════════════════════════════════════════════════════════════════
            TAB 4 — TICKETS
        ══════════════════════════════════════════════════════════════════ */}
        {activeTab === 'tickets' && (
          <div className="admin-glass-card rounded-xl p-6">
            <div className="flex items-center justify-between mb-6">
              <div>
                <h3 className="text-lg font-black text-gray-900 flex items-center gap-2">
                  <i className="fas fa-ticket-alt text-orange-600" />Recent Tickets
                </h3>
                <p className="text-xs text-gray-500 mt-1 font-medium">Last 10 tickets across all clients</p>
              </div>
              <div className="flex items-center gap-3">
                {/* Status/Severity filter */}
                <div className="flex gap-1 p-1 bg-gray-100 rounded-lg">
                  {['ALL', 'OPEN', 'IN_PROGRESS', 'CLOSED', 'HIGH', 'CRITICAL'].map(f => (
                    <button
                      key={f}
                      onClick={() => setTicketFilter(f)}
                      className={`px-3 py-1 rounded-md text-xs font-semibold transition-all ${ticketFilter === f ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
                        }`}
                    >
                      {f === 'ALL' ? 'All' : f === 'IN_PROGRESS' ? 'In Progress' : f.charAt(0) + f.slice(1).toLowerCase()}
                    </button>
                  ))}
                </div>
                <Link to="/billops-admin-tickets" className="text-xs font-bold text-indigo-600 hover:text-indigo-800 hover:underline uppercase tracking-wide">
                  View All &rarr;
                </Link>
              </div>
            </div>

            <div className="overflow-x-auto rounded-xl border border-gray-100">
              {loading ? (
                <div className="p-4 space-y-3">{[1, 2, 3, 4].map(i => <Shimmer key={i} className="h-12 w-full" />)}</div>
              ) : filteredTickets.length === 0 ? (
                <div className="px-6 py-10 text-center text-gray-400 text-sm font-medium">
                  <i className="fas fa-ticket-alt text-3xl mb-2 text-gray-200 block" />
                  {ticketFilter === 'ALL' ? 'No recent tickets found' : `No ${ticketFilter.toLowerCase()} tickets`}
                </div>
              ) : (
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50/80 backdrop-blur-sm">
                    <tr>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Subject</th>
                      <th className="px-6 py-3 text-left text-xs font-bold text-gray-500 uppercase tracking-wider">Category</th>
                      <th className="px-6 py-3 text-center text-xs font-bold text-gray-500 uppercase tracking-wider">Severity</th>
                      <th className="px-6 py-3 text-center text-xs font-bold text-gray-500 uppercase tracking-wider">Status</th>
                    </tr>
                  </thead>
                  <tbody className="bg-white/40 divide-y divide-gray-200">
                    {filteredTickets.map((ticket: AdminTicket) => (
                      <tr key={ticket.id} className={`admin-table-row ${ticket.severity === 'CRITICAL' ? 'bg-red-50/30' : ''}`}>
                        <td className="px-6 py-4">
                          <div className="flex items-center gap-2">
                            {ticket.severity === 'CRITICAL' && <i className="fas fa-fire text-red-500 text-xs" />}
                            <span className="text-sm font-bold text-gray-900">{ticket.subject}</span>
                          </div>
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-xs text-gray-500 uppercase font-semibold tracking-wide">
                          {ticket.category}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-center">
                          {ticket.severity ? (
                            <span className={`text-xs font-bold uppercase ${getSeverityClass(ticket.severity)}`}>
                              {ticket.severity}
                            </span>
                          ) : <span className="text-gray-300 text-xs">—</span>}
                        </td>
                        <td className="px-6 py-4 whitespace-nowrap text-center">
                          <span className={`admin-status-badge ${getStatusClass(ticket.status)}`}>
                            {ticket.status.replace('_', ' ')}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )}

      </main>
    </div>
  );
};

export default BillOpsAdminDashboardPage;
