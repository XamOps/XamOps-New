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


  useEffect(() => {
    apiFetch('/api/xamops/billops-admin/clients', { credentials: 'include' })
      .then(r => r.ok ? r.json() : Promise.reject(r.status))
      .then((d: ClientsApiData[]) => setClientsData(d))
      .catch(err => console.error('Failed to load clients data for spend total', err))
      .finally(() => setClientsLoading(false));
  }, []);

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
  const providerChartInstance = useRef<Chart<'bar'> | null>(null);
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
    const labels = Object.keys(spendingByProvider);
    const values = Object.values(spendingByProvider) as number[];
    if (values.every(v => v === 0)) return;
    const total = values.reduce((s, v) => s + v, 0);
    const colorMap: Record<string, { bar: string; hover: string }> = {
      AWS:   { bar: '#F59E0B', hover: '#D97706' },
      Azure: { bar: '#3B82F6', hover: '#2563EB' },
      GCP:   { bar: '#10B981', hover: '#059669' },
    };
    const bgColors   = labels.map(l => colorMap[l]?.bar   ?? '#6366f1');
    const hoverColors = labels.map(l => colorMap[l]?.hover ?? '#4f46e5');
    providerChartInstance.current = new Chart(ctx, {
      type: 'bar',
      data: {
        labels,
        datasets: [{
          data: values,
          backgroundColor: bgColors,
          hoverBackgroundColor: hoverColors,
          borderRadius: 6,
          borderSkipped: false,
          barThickness: 32,
        }],
      },
      options: {
        indexAxis: 'y',
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: '#1f2937',
            titleColor: '#f9fafb',
            bodyColor: '#d1d5db',
            borderColor: '#374151',
            borderWidth: 1,
            padding: 12,
            cornerRadius: 6,
            callbacks: {
              label: (item) => {
                const pct = total > 0 ? ((item.raw as number) / total * 100).toFixed(1) : '0.0';
                return `  ${formatCurrency(item.raw as number)}   ${pct}%`;
              },
            },
          },
        },
        scales: {
          x: {
            grid: { color: '#f3f4f6' },
            ticks: {
              font: { family: 'Inter', size: 11 },
              color: '#9ca3af',
              callback: (v) => `$${Number(v).toLocaleString()}`,
            },
          },
          y: {
            grid: { display: false },
            ticks: { font: { family: 'Inter', size: 13, weight: '600' }, color: '#111827' },
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
    // Vertical gradient fill: indigo at top → transparent at bottom
    const gradient = ctx.createLinearGradient(0, 0, 0, 220);
    gradient.addColorStop(0, 'rgba(99,102,241,0.22)');
    gradient.addColorStop(0.6, 'rgba(99,102,241,0.06)');
    gradient.addColorStop(1, 'rgba(99,102,241,0)');

    trendChartInstance.current = new Chart(ctx, {
      type: 'line',
      data: {
        labels: convertedData.spendingTrend.map((i: SpendingTrendItem) => i.date),
        datasets: [{
          label: 'Monthly Spending (USD)',
          data: convertedData.spendingTrend.map((i: SpendingTrendItem) => i.cost),
          borderColor: '#6366f1',
          backgroundColor: gradient,
          borderWidth: 2.5,
          fill: true,
          tension: 0.45,
          pointBackgroundColor: '#6366f1',
          pointBorderColor: '#fff',
          pointBorderWidth: 2,
          pointRadius: 5,
          pointHoverRadius: 7,
          pointHoverBackgroundColor: '#4f46e5',
          pointHoverBorderColor: '#fff',
          pointHoverBorderWidth: 2,
        }],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: '#1f2937',
            titleColor: '#f9fafb',
            bodyColor: '#d1d5db',
            borderColor: '#374151',
            borderWidth: 1,
            padding: 12,
            cornerRadius: 6,
            displayColors: false,
            callbacks: {
              title: (items) => items[0].label,
              label: (item) => `  Spend: ${formatCurrency(item.raw as number)}`,
            },
          },
        },
        scales: {
          y: {
            beginAtZero: false,
            grid: { color: '#f3f4f6', lineWidth: 1 },
            ticks: {
              callback: (v) => `$${Number(v).toLocaleString()}`,
              font: { family: 'Inter', size: 11 },
              color: '#9ca3af',
              maxTicksLimit: 5,
            },
          },
          x: {
            grid: { display: false },
            ticks: {
              font: { family: 'Inter', size: 11 },
              color: '#6b7280',
            },
          },
        },
      },
    });
    return () => { trendChartInstance.current?.destroy(); };
  }, [convertedData?.spendingTrend]);

  // ── Topbar actions ─────────────────────────────────────────────────────────
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={refreshDashboard} disabled={isRefreshing}
          className="flex items-center gap-2 px-3 py-1.5 bg-indigo-600 text-white rounded-md text-xs font-semibold hover:bg-indigo-700 transition-colors disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt text-[10px] ${isRefreshing ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, data?.lastUpdated, isRefreshing, refreshDashboard]);

  // ── Shimmer skeleton ───────────────────────────────────────────────────────
  const Shimmer = ({ className }: { className: string }) => (
    <div className={`admin-shimmer rounded ${className}`} />
  );

  // ══════════════════════════════════════════════════════════════════════════
  return (
    <div className="admin-dashboard flex-1 flex flex-col h-full bg-transparent overflow-hidden">
      <main className="flex-1 overflow-y-auto p-5 space-y-4 admin-scrollbar">

        {/* Error */}
        {error && (
          <div className="admin-error-alert p-3 rounded">
            <div className="flex items-center gap-2">
              <i className="fas fa-exclamation-circle text-red-500" />
              <span className="text-red-700 text-sm font-medium">{error}</span>
            </div>
          </div>
        )}

        {/* ── Stats Grid (always visible) ─────────────────────────────────── */}
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {/* Total Spend */}
          <div className="admin-glass-card admin-metric-card admin-border-indigo rounded-md p-4">
            <div className="flex items-start justify-between">
              <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest leading-none">Total System Spend</p>
              <div className="admin-icon-indigo w-7 h-7 rounded-md flex items-center justify-center flex-shrink-0">
                <i className="fas fa-dollar-sign text-indigo-600 text-xs" />
              </div>
            </div>
            {!loading && !clientsLoading ? (
              <div className="admin-fade-in mt-3">
                <span className="text-[26px] font-bold admin-metric-value leading-none tracking-tight">
                  {formatCurrency(clientsSpend?.total ?? convertedData?.totalSpending)}
                </span>
                <p className="text-[11px] text-gray-400 mt-2">All clients · current month</p>
              </div>
            ) : <div className="space-y-2 mt-3"><Shimmer className="h-7 w-32" /><Shimmer className="h-2.5 w-24" /></div>}
          </div>

          {/* Total Accounts */}
          <div className="admin-glass-card admin-metric-card admin-border-purple rounded-md p-4">
            <div className="flex items-start justify-between">
              <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest leading-none">Cloud Accounts</p>
              <div className="admin-icon-purple w-7 h-7 rounded-md flex items-center justify-center flex-shrink-0">
                <i className="fas fa-layer-group text-purple-600 text-xs" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in mt-3">
                <div className="flex items-end justify-between">
                  <span className="text-[26px] font-bold admin-metric-value leading-none tracking-tight">{data?.totalAccounts || 0}</span>
                  <div className="admin-provider-stack mb-0.5">
                    <div className="admin-provider-icon" title="AWS"><i className="fab fa-aws admin-provider-aws" /></div>
                    <div className="admin-provider-icon" title="GCP"><i className="fab fa-google admin-provider-gcp" /></div>
                    <div className="admin-provider-icon" title="Azure"><i className="fab fa-microsoft admin-provider-azure" /></div>
                  </div>
                </div>
                <p className="text-[11px] text-gray-400 mt-2">
                  {data?.awsAccountsCount || 0} AWS · {data?.gcpAccountsCount || 0} GCP · {data?.azureAccountsCount || 0} Azure
                </p>
              </div>
            ) : <div className="space-y-2 mt-3"><Shimmer className="h-7 w-16" /><Shimmer className="h-2.5 w-full" /></div>}
          </div>

          {/* Invoices */}
          <div
            className="admin-glass-card admin-metric-card admin-border-blue rounded-md p-4 cursor-pointer"
            onClick={() => setActiveTab('invoices')}
            title="Click to view invoices"
          >
            <div className="flex items-start justify-between">
              <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest leading-none">Invoices</p>
              <div className="admin-icon-blue w-7 h-7 rounded-md flex items-center justify-center flex-shrink-0">
                <i className="fas fa-file-invoice-dollar text-blue-600 text-xs" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in mt-3">
                <span className="text-[26px] font-bold admin-metric-value leading-none tracking-tight">
                  {(data?.pendingInvoices || 0) + (data?.finalizedInvoices || 0)}
                </span>
                <p className="text-[11px] text-gray-400 mt-2">
                  <span className="text-amber-600 font-semibold">{data?.pendingInvoices || 0} pending</span>
                  <span className="mx-1 text-gray-300">·</span>
                  <span className="text-green-600 font-semibold">{data?.finalizedInvoices || 0} finalized</span>
                </p>
              </div>
            ) : <div className="space-y-2 mt-3"><Shimmer className="h-7 w-20" /><Shimmer className="h-2.5 w-28" /></div>}
          </div>

          {/* Tickets */}
          <div
            className="admin-glass-card admin-metric-card admin-border-orange rounded-md p-4 cursor-pointer"
            onClick={() => setActiveTab('tickets')}
            title="Click to view tickets"
          >
            <div className="flex items-start justify-between">
              <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest leading-none">Open Tickets</p>
              <div className="admin-icon-orange w-7 h-7 rounded-md flex items-center justify-center flex-shrink-0">
                <i className="fas fa-ticket-alt text-orange-600 text-xs" />
              </div>
            </div>
            {!loading ? (
              <div className="admin-fade-in mt-3">
                <span className="text-[26px] font-bold admin-metric-value leading-none tracking-tight">{data?.openTickets || 0}</span>
                <p className="text-[11px] text-gray-400 mt-2">
                  <span className="text-gray-600 font-semibold">{data?.totalClients || 0}</span> total clients
                </p>
              </div>
            ) : <div className="space-y-2 mt-3"><Shimmer className="h-7 w-20" /><Shimmer className="h-2.5 w-28" /></div>}
          </div>
        </div>

        {/* ── Tab Navigation ───────────────────────────────────────────────── */}
        <div className="flex items-center gap-0.5 border-b border-gray-200">
          {TABS.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium transition-colors border-b-2 -mb-px ${
                activeTab === tab.id
                  ? 'border-indigo-600 text-indigo-600'
                  : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
              }`}
            >
              <i className={`${tab.icon} text-xs`} />
              {tab.label}
              {tab.id === 'invoices' && (data?.pendingInvoices || 0) > 0 && (
                <span className="ml-1 min-w-[18px] h-[18px] px-1 flex items-center justify-center bg-amber-100 text-amber-700 text-[10px] font-bold rounded">
                  {data?.pendingInvoices}
                </span>
              )}
              {tab.id === 'tickets' && (data?.openTickets || 0) > 0 && (
                <span className="ml-1 min-w-[18px] h-[18px] px-1 flex items-center justify-center bg-red-100 text-red-600 text-[10px] font-bold rounded">
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
              <div className="admin-glass-card admin-border-top-indigo rounded-md p-5 flex flex-col">
                <div className="mb-4 pb-3 border-b border-gray-100">
                  <h3 className="text-[13px] font-semibold text-gray-900 tracking-tight">Global Spending Distribution</h3>
                  <p className="text-xs text-gray-400 mt-0.5">By cloud provider · current month</p>
                </div>
                {!loading && !Object.values(clientsSpend?.byProvider ?? convertedData?.spendingByProvider ?? {}).some(v => v > 0) ? (
                  <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">
                    <div className="text-center"><i className="fas fa-chart-pie text-2xl mb-2 text-gray-200 block" />No spending data available</div>
                  </div>
                ) : (
                  <div className="admin-chart-container"><canvas ref={providerChartRef} /></div>
                )}
              </div>

              <div className="admin-glass-card admin-border-top-purple rounded-md p-5 flex flex-col">
                <div className="mb-4 pb-3 border-b border-gray-100">
                  <h3 className="text-[13px] font-semibold text-gray-900 tracking-tight">Spending Trend</h3>
                  <p className="text-xs text-gray-400 mt-0.5">Aggregated cloud spend in USD · last 6 months</p>
                </div>
                {!loading && !convertedData?.spendingTrend?.length ? (
                  <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">
                    <div className="text-center"><i className="fas fa-chart-line text-2xl mb-2 text-gray-200 block" />No trend data available</div>
                  </div>
                ) : (
                  <div className="admin-chart-container"><canvas ref={trendChartRef} /></div>
                )}
              </div>
            </div>

            {/* Top 10 Accounts */}
            <div className="admin-glass-card admin-border-cyan rounded-md p-5">
              <div className="flex items-center justify-between pb-3 mb-4 border-b border-gray-100">
                <div>
                  <h3 className="text-[13px] font-semibold text-gray-900 flex items-center gap-2 tracking-tight">
                    <i className="fas fa-trophy text-cyan-600 text-xs" />Top 10 Spending Accounts
                  </h3>
                  <p className="text-xs text-gray-400 mt-0.5">Ranked by current month spend</p>
                </div>
                <button
                  onClick={refreshTopSpending}
                  disabled={isRefreshingTopSpending}
                  title="Fetch live current-month cost from CE API"
                  className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium text-cyan-700 bg-cyan-50 hover:bg-cyan-100 border border-cyan-200 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <i className={`fas fa-bolt text-[10px] ${isRefreshingTopSpending ? 'animate-pulse' : ''}`} />
                  {isRefreshingTopSpending ? 'Fetching...' : 'Live Refresh'}
                </button>
              </div>
              <div className="overflow-x-auto rounded-md border border-gray-100">
                {clientsLoading ? (
                  <div className="p-4 space-y-2">
                    {[1, 2, 3].map(i => <Shimmer key={i} className="h-9 w-full" />)}
                  </div>
                ) : (
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead>
                      <tr>
                        <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">#</th>
                        <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Account</th>
                        <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Provider</th>
                        <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Client</th>
                        <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Status</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">This Month</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Last Month</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">MoM</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {top10FromClients?.length ? (
                        top10FromClients.map((account: TopSpendingAccount, idx: number) => {
                          const mom = getMoMChange(account.currentMonthSpending, account.lastMonthSpending);
                          return (
                            <tr key={account.id} className="admin-table-row">
                              <td className="px-4 py-3 text-xs text-gray-400 font-medium">{idx + 1}</td>
                              <td className="px-4 py-3 whitespace-nowrap">
                                <div className="text-sm font-medium text-gray-900">{account.accountName}</div>
                                {account.accountIdentifier && (
                                  <div className="text-[10px] text-gray-400 font-mono mt-0.5">{account.accountIdentifier}</div>
                                )}
                              </td>
                              <td className="px-4 py-3 whitespace-nowrap">
                                <span className="admin-provider-badge">
                                  <i className={getProviderIconClass(account.provider)} />
                                  <span>{account.provider}</span>
                                </span>
                              </td>
                              <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-600">{account.clientName}</td>
                              <td className="px-4 py-3 whitespace-nowrap">
                                <span className={`admin-status-badge ${getStatusClass(account.status)}`}>{account.status}</span>
                              </td>
                              <td className="px-4 py-3 whitespace-nowrap text-sm text-right font-semibold text-gray-900">
                                {formatCurrency(account.currentMonthSpending)}
                              </td>
                              <td className="px-4 py-3 whitespace-nowrap text-sm text-right text-gray-400">
                                {formatCurrency(account.lastMonthSpending)}
                              </td>
                              <td className="px-4 py-3 whitespace-nowrap text-sm text-right">
                                {mom ? (
                                  <span className={`inline-flex items-center gap-1 font-semibold text-xs ${mom.up ? 'text-red-600' : 'text-green-600'}`}>
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
                          <td colSpan={8} className="px-4 py-8 text-center text-gray-400 text-sm">No account data available</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                )}
              </div>
            </div>

            {/* AutoSpotting Savings */}
            <div className="admin-glass-card admin-border-green rounded-md p-5">
              <div className="flex items-center justify-between pb-3 mb-4 border-b border-gray-100">
                <div>
                  <h3 className="text-[13px] font-semibold text-gray-900 flex items-center gap-2 tracking-tight">
                    <i className="fas fa-bolt text-green-600 text-xs" />AutoSpotting Savings
                  </h3>
                  <p className="text-xs text-gray-400 mt-0.5">Customers with AutoSpotting enabled</p>
                </div>
                <Link to="/autospotting-admin" className="text-xs font-medium text-green-600 hover:text-green-800 hover:underline">
                  Full Report →
                </Link>
              </div>
              <div className="overflow-x-auto rounded-md border border-gray-100">
                {customerSavingsLoading ? (
                  <div className="p-4 space-y-2">{[1, 2, 3].map(i => <Shimmer key={i} className="h-9 w-full" />)}</div>
                ) : customerSavings.length === 0 ? (
                  <div className="px-6 py-10 text-center text-gray-400 text-sm">
                    <i className="fas fa-bolt text-gray-300 text-2xl mb-2 block" />No customers have AutoSpotting enabled yet
                  </div>
                ) : (
                  <table className="min-w-full divide-y divide-gray-200">
                    <thead>
                      <tr>
                        <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Customer</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50"><i className="fab fa-aws admin-provider-aws mr-1" />AWS</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50"><i className="fab fa-google admin-provider-gcp mr-1" />GCP</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50"><i className="fab fa-microsoft admin-provider-azure mr-1" />Azure</th>
                        <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Total Saved</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                      {customerSavings.map(row => (
                        <tr key={row.clientId} className="admin-table-row">
                          <td className="px-4 py-3 text-sm font-medium text-gray-900">{row.clientName}</td>
                          <td className="px-4 py-3 text-sm text-right text-gray-600">{row.awsSavings > 0 ? formatCurrency(row.awsSavings) : <span className="text-gray-300">—</span>}</td>
                          <td className="px-4 py-3 text-sm text-right text-gray-600">{row.gcpSavings > 0 ? formatCurrency(row.gcpSavings) : <span className="text-gray-300">—</span>}</td>
                          <td className="px-4 py-3 text-sm text-right text-gray-600">{row.azureSavings > 0 ? formatCurrency(row.azureSavings) : <span className="text-gray-300">—</span>}</td>
                          <td className="px-4 py-3 text-sm text-right font-semibold text-green-700">{formatCurrency(row.totalSavings)}</td>
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
          <div className="admin-glass-card admin-border-purple rounded-md p-5">
            <div className="flex items-center justify-between pb-3 mb-4 border-b border-gray-100">
              <div>
                <h3 className="text-[13px] font-semibold text-gray-900 flex items-center gap-2 tracking-tight">
                  <i className="fas fa-users text-purple-600 text-xs" />Client Spending Summary
                </h3>
                <p className="text-xs text-gray-400 mt-0.5">
                  {clientGroups.length} client{clientGroups.length !== 1 ? 's' : ''} · grouped from top spending accounts
                </p>
              </div>
            </div>
            <div className="overflow-x-auto rounded-md border border-gray-100">
              {loading ? (
                <div className="p-4 space-y-2">{[1, 2, 3].map(i => <Shimmer key={i} className="h-12 w-full" />)}</div>
              ) : clientGroups.length === 0 ? (
                <div className="px-6 py-10 text-center text-gray-400 text-sm">
                  <i className="fas fa-users text-2xl mb-2 text-gray-200 block" />No client data available
                </div>
              ) : (
                <table className="min-w-full divide-y divide-gray-200">
                  <thead>
                    <tr>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Client</th>
                      <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Accounts</th>
                      <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Providers</th>
                      <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">This Month</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Top Account</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {clientGroups.map(group => {
                      const providers = [...new Set(group.accounts.map(a => a.provider))];
                      const topAccount = group.accounts[0];
                      return (
                        <tr key={group.clientName} className="admin-table-row">
                          <td className="px-4 py-3 whitespace-nowrap">
                            <div className="flex items-center gap-2.5">
                              <div className="w-7 h-7 rounded-md bg-indigo-600 flex items-center justify-center text-white font-semibold text-xs">
                                {group.clientName.charAt(0).toUpperCase()}
                              </div>
                              <span className="text-sm font-medium text-gray-900">{group.clientName}</span>
                            </div>
                          </td>
                          <td className="px-4 py-3 text-center">
                            <span className="inline-flex items-center justify-center px-2 py-0.5 bg-gray-100 text-gray-600 text-xs font-semibold rounded">
                              {group.accounts.length}
                            </span>
                          </td>
                          <td className="px-4 py-3 text-center">
                            <div className="flex items-center justify-center gap-1">
                              {providers.map(p => (
                                <span key={p} className="admin-provider-badge">
                                  <i className={getProviderIconClass(p)} /><span>{p}</span>
                                </span>
                              ))}
                            </div>
                          </td>
                          <td className="px-4 py-3 whitespace-nowrap text-right font-semibold text-gray-900 text-sm">
                            {formatCurrency(group.totalSpend)}
                          </td>
                          <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-500">
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
          <div className="admin-glass-card rounded-md p-5">
            <div className="flex items-center justify-between pb-3 mb-4 border-b border-gray-100">
              <div>
                <h3 className="text-[13px] font-semibold text-gray-900 flex items-center gap-2 tracking-tight">
                  <i className="fas fa-file-invoice-dollar text-blue-600 text-xs" />Recent Invoices
                </h3>
                <p className="text-xs text-gray-400 mt-0.5">Last 10 invoices across all clients</p>
              </div>
              <div className="flex items-center gap-3">
                <div className="flex gap-0.5 border border-gray-200 rounded-md overflow-hidden">
                  {['ALL', 'DRAFT', 'FINALIZED', 'PAID', 'OVERDUE'].map(f => (
                    <button
                      key={f}
                      onClick={() => setInvoiceFilter(f)}
                      className={`px-3 py-1.5 text-xs font-medium transition-colors ${invoiceFilter === f ? 'bg-indigo-600 text-white' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'}`}
                    >
                      {f === 'ALL' ? 'All' : f.charAt(0) + f.slice(1).toLowerCase()}
                    </button>
                  ))}
                </div>
                <Link to="/billops-admin-invoices" className="text-xs font-medium text-indigo-600 hover:text-indigo-800 hover:underline">
                  View All →
                </Link>
              </div>
            </div>

            <div className="overflow-x-auto rounded-md border border-gray-100">
              {loading ? (
                <div className="p-4 space-y-2">{[1, 2, 3, 4].map(i => <Shimmer key={i} className="h-10 w-full" />)}</div>
              ) : filteredInvoices.length === 0 ? (
                <div className="px-6 py-10 text-center text-gray-400 text-sm">
                  <i className="fas fa-file-invoice text-2xl mb-2 text-gray-200 block" />
                  {invoiceFilter === 'ALL' ? 'No recent invoices found' : `No ${invoiceFilter.toLowerCase()} invoices`}
                </div>
              ) : (
                <table className="min-w-full divide-y divide-gray-200">
                  <thead>
                    <tr>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Invoice #</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Client</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Account</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Provider</th>
                      <th className="px-4 py-2.5 text-right text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Amount</th>
                      <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {filteredInvoices.map((invoice: AdminInvoice) => (
                      <tr key={invoice.id} className={`admin-table-row ${invoice.status === 'OVERDUE' ? 'bg-red-50/50' : ''}`}>
                        <td className="px-4 py-3 whitespace-nowrap">
                          <div className="flex items-center gap-2">
                            {invoice.status === 'OVERDUE' && <i className="fas fa-exclamation-circle text-red-500 text-xs" />}
                            <span className="text-sm font-medium text-gray-900">{invoice.invoiceNumber}</span>
                          </div>
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-700">{invoice.clientName}</td>
                        <td className="px-4 py-3 whitespace-nowrap text-sm text-gray-500">{invoice.accountName}</td>
                        <td className="px-4 py-3 whitespace-nowrap">
                          {invoice.provider && (
                            <span className="admin-provider-badge">
                              <i className={getProviderIconClass(invoice.provider)} /><span>{invoice.provider}</span>
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap text-sm text-right font-semibold text-gray-900">
                          {formatCurrency(invoice.amount)}
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap text-center">
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
          <div className="admin-glass-card rounded-md p-5">
            <div className="flex items-center justify-between pb-3 mb-4 border-b border-gray-100">
              <div>
                <h3 className="text-[13px] font-semibold text-gray-900 flex items-center gap-2 tracking-tight">
                  <i className="fas fa-ticket-alt text-orange-600 text-xs" />Recent Tickets
                </h3>
                <p className="text-xs text-gray-400 mt-0.5">Last 10 tickets across all clients</p>
              </div>
              <div className="flex items-center gap-3">
                <div className="flex gap-0.5 border border-gray-200 rounded-md overflow-hidden">
                  {['ALL', 'OPEN', 'IN_PROGRESS', 'CLOSED', 'HIGH', 'CRITICAL'].map(f => (
                    <button
                      key={f}
                      onClick={() => setTicketFilter(f)}
                      className={`px-3 py-1.5 text-xs font-medium transition-colors ${ticketFilter === f ? 'bg-indigo-600 text-white' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'}`}
                    >
                      {f === 'ALL' ? 'All' : f === 'IN_PROGRESS' ? 'In Progress' : f.charAt(0) + f.slice(1).toLowerCase()}
                    </button>
                  ))}
                </div>
                <Link to="/billops-admin-tickets" className="text-xs font-medium text-indigo-600 hover:text-indigo-800 hover:underline">
                  View All →
                </Link>
              </div>
            </div>

            <div className="overflow-x-auto rounded-md border border-gray-100">
              {loading ? (
                <div className="p-4 space-y-2">{[1, 2, 3, 4].map(i => <Shimmer key={i} className="h-10 w-full" />)}</div>
              ) : filteredTickets.length === 0 ? (
                <div className="px-6 py-10 text-center text-gray-400 text-sm">
                  <i className="fas fa-ticket-alt text-2xl mb-2 text-gray-200 block" />
                  {ticketFilter === 'ALL' ? 'No recent tickets found' : `No ${ticketFilter.toLowerCase()} tickets`}
                </div>
              ) : (
                <table className="min-w-full divide-y divide-gray-200">
                  <thead>
                    <tr>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Subject</th>
                      <th className="px-4 py-2.5 text-left text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Category</th>
                      <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Severity</th>
                      <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-gray-500 uppercase tracking-widest bg-gray-50">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {filteredTickets.map((ticket: AdminTicket) => (
                      <tr key={ticket.id} className={`admin-table-row ${ticket.severity === 'CRITICAL' ? 'bg-red-50/30' : ''}`}>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            {ticket.severity === 'CRITICAL' && <i className="fas fa-fire text-red-500 text-xs" />}
                            <span className="text-sm font-medium text-gray-900">{ticket.subject}</span>
                          </div>
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap text-xs text-gray-500 uppercase font-medium tracking-wide">
                          {ticket.category}
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap text-center">
                          {ticket.severity ? (
                            <span className={`text-xs font-semibold uppercase ${getSeverityClass(ticket.severity)}`}>
                              {ticket.severity}
                            </span>
                          ) : <span className="text-gray-300 text-xs">—</span>}
                        </td>
                        <td className="px-4 py-3 whitespace-nowrap text-center">
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
